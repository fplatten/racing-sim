"""Generates the grandstand crowd atlas: four different rows of seated spectators.

    blender --background --factory-startup --python blender/make_crowd.py

Textures/Track/crowd_atlas.png, 2048 x 512: four strips of 128 px, each one row of 40 seats over
20 m and 1.0 m tall (row 0 of a strip = top). Spectators are drawn at 2x and box-filtered down for
clean alpha edges; colour is bled into the transparent texels so mip-maps do not get dark fringes.
Each person: legs / lap in trousers, jeans or shorts, a shaded torso (shirt, jacket or team top,
some with a chest print), arms resting or raised, a head with hair, a cap or sunglasses; children
and empty seats mixed in. Natural, muted clothing colours.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from common import out_path, save_png

rng = np.random.default_rng(77)
W, STRIP, STRIPS, SS, PEOPLE = 2048, 128, 4, 2, 40

SKIN = np.array([[0.94, 0.78, 0.66], [0.87, 0.68, 0.54], [0.76, 0.56, 0.42], [0.60, 0.42, 0.30],
                 [0.44, 0.30, 0.21], [0.30, 0.20, 0.14]])
HAIR = np.array([[0.06, 0.05, 0.04], [0.16, 0.10, 0.06], [0.30, 0.20, 0.12], [0.55, 0.42, 0.25],
                 [0.78, 0.66, 0.42], [0.55, 0.53, 0.50], [0.80, 0.79, 0.76]])
HAIR_W = np.array([0.24, 0.24, 0.16, 0.12, 0.08, 0.10, 0.06])
# (colour, weight): what a race crowd actually wears
SHIRTS = [((0.86, 0.86, 0.84), 16), ((0.07, 0.07, 0.08), 14), ((0.22, 0.22, 0.24), 8), ((0.45, 0.46, 0.48), 7),
          ((0.12, 0.16, 0.30), 9), ((0.28, 0.38, 0.55), 6), ((0.55, 0.12, 0.12), 6), ((0.75, 0.38, 0.12), 4),
          ((0.82, 0.70, 0.25), 4), ((0.20, 0.34, 0.20), 4), ((0.40, 0.40, 0.25), 3), ((0.38, 0.12, 0.16), 3),
          ((0.52, 0.66, 0.80), 5), ((0.80, 0.55, 0.60), 2), ((0.62, 0.56, 0.44), 4), ((0.92, 0.92, 0.70), 2),
          ((0.10, 0.30, 0.55), 3)]
PANTS = [((0.18, 0.24, 0.36), 30), ((0.12, 0.15, 0.24), 12), ((0.55, 0.50, 0.40), 14), ((0.08, 0.08, 0.09), 12),
         ((0.38, 0.38, 0.40), 8), ((0.35, 0.32, 0.25), 6)]
SEAT = np.array([0.16, 0.27, 0.48])                       # faded stadium blue


def pick(options):
    cols = np.array([c for c, _ in options])
    w = np.array([w for _, w in options], dtype=float)
    return cols[rng.choice(len(cols), p=w / w.sum())]


def vary(c, amount=0.12):
    return np.clip(np.asarray(c) * rng.uniform(1 - amount, 1 + amount) + rng.normal(0, 0.015, 3), 0, 1)


def ellipse(xx, yy, cx, cy, rx, ry):
    return ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2 < 1.0


def paint(img, mask, rgb):
    img[mask, :3] = rgb if np.ndim(rgb) == 1 else rgb[mask]
    img[mask, 3] = 1.0


def strip(seed_offset):
    h, w = STRIP * SS, W * SS
    img = np.zeros((h, w, 4))
    seat_w = w / PEOPLE
    yy_full, xx_full = np.mgrid[0:h, 0:w].astype(float)
    noise = rng.normal(0, 1, (h, w))

    # seats: molded backs with a lighter rim and a seat pan edge, shaded towards the sides
    for k in range(PEOPLE):
        cx = (k + 0.5) * seat_w
        x0, x1 = int(cx - seat_w * 0.5), int(cx + seat_w * 0.5)
        xx, yy = xx_full[:, x0:x1], yy_full[:, x0:x1]
        sub = img[:, x0:x1]
        dx = np.abs(xx - cx) / (seat_w * 0.42)
        back = (dx < 1) & (yy > h * 0.50 - (1 - dx ** 2) * h * 0.03) & (yy < h * 0.80)
        shade = (0.72 + 0.28 * np.sqrt(np.clip(1 - dx ** 2, 0, 1)))[..., None]
        col = SEAT * shade * (0.92 + 0.04 * noise[:, x0:x1, None])
        sub[back, :3] = col[back]
        sub[back, 3] = 1
        pan = (dx < 1.02) & (yy >= h * 0.80) & (yy < h * 0.86)
        sub[pan, :3] = SEAT * 0.55
        sub[pan, 3] = 1

    for k in range(PEOPLE):
        if rng.random() < 0.09:                                   # empty seat
            continue
        child = rng.random() < 0.08
        sc = 0.78 if child else rng.uniform(0.92, 1.08)
        cx = (k + 0.5 + rng.uniform(-0.12, 0.12)) * seat_w
        x0, x1 = int(max(0, cx - seat_w * 0.95)), int(min(w, cx + seat_w * 0.95))
        xx, yy = xx_full[:, x0:x1], yy_full[:, x0:x1]
        sub = img[:, x0:x1]
        nz = noise[:, x0:x1]
        lean = rng.uniform(-0.06, 0.06) * seat_w

        shoulder_y = h * (0.34 + (0.10 if child else 0) + rng.uniform(-0.03, 0.03))
        waist_y = h * 0.74
        half_sh = seat_w * rng.uniform(0.33, 0.41) * sc
        shirt = vary(pick(SHIRTS))
        pants = vary(pick(PANTS))
        skin = vary(SKIN[rng.integers(len(SKIN))], 0.05)
        shorts = rng.random() < 0.35

        # legs: lap / thighs towards the viewer, then shins and shoes
        lap = (yy > h * 0.70) & (yy < h * 0.86) & (np.abs(xx - cx) < half_sh * 1.02)
        paint(sub, lap, pants * 0.82)
        for side in (-1, 1):
            lx = cx + side * half_sh * 0.45
            shin = (yy >= h * 0.86) & (np.abs(xx - lx) < half_sh * 0.30)
            paint(sub, shin, skin * 0.85 if shorts else pants * 0.70)
            shoe = (yy >= h * 0.97) & (np.abs(xx - lx) < half_sh * 0.36)
            paint(sub, shoe, np.array([0.85, 0.85, 0.85]) if rng.random() < 0.4 else np.array([0.08, 0.08, 0.09]))

        # torso: a softly shaded cylinder, narrowing a little to the waist, with cloth folds
        t = np.clip((yy - shoulder_y) / (waist_y - shoulder_y), 0, 1)
        half = half_sh * (1 - 0.20 * t)
        dxn = (xx - cx - lean * (1 - t)) / half
        corner = 1 - 0.35 * np.clip((shoulder_y + h * 0.04 - yy) / (h * 0.04), 0, 1)   # rounded shoulders
        torso = (yy > shoulder_y) & (yy < waist_y + h * 0.02) & (np.abs(dxn) < corner)
        shade = 0.68 + 0.32 * np.sqrt(np.clip(1 - dxn ** 2, 0, 1)) - 0.10 * t
        folds = 0.04 * np.sin((xx - cx) * 0.35 + yy * 0.08) * (nz * 0.5 + 0.5)
        col = shirt[None, None, :] * (shade + folds)[..., None]
        sub[torso, :3] = np.clip(col[torso], 0, 1)
        sub[torso, 3] = 1
        r = rng.random()
        if r < 0.18:                                              # open jacket over a t-shirt
            inner = torso & (np.abs(dxn) < 0.28)
            paint(sub, inner, vary(pick(SHIRTS)) * 0.95)
        elif r < 0.40:                                            # chest print / team logo
            logo_c = vary(pick(SHIRTS))
            logo = ellipse(xx, yy, cx + lean * 0.5, shoulder_y + h * 0.10, half_sh * 0.38, h * 0.045) & torso
            paint(sub, logo, logo_c)

        # arms: resting on the lap, or raised (cheering / filming)
        raised = rng.random() < 0.07
        sleeve_len = h * 0.10 if rng.random() < 0.7 else h * 0.30
        for side in (-1, 1):
            ax = cx + side * (half_sh * 0.98)
            if raised and (side == 1 or rng.random() < 0.5):
                arm = (np.abs(xx - (ax + side * seat_w * 0.04)) < seat_w * 0.07) & (yy > h * 0.0) & (yy < shoulder_y + h * 0.05)
                paint(sub, arm & (yy > shoulder_y - sleeve_len * 0.4), shirt * 0.8)
                paint(sub, arm & (yy <= shoulder_y - sleeve_len * 0.4), skin * 0.9)
                paint(sub, ellipse(xx, yy, ax + side * seat_w * 0.04, h * 0.02, seat_w * 0.08, h * 0.035), skin)
            else:
                arm = (np.abs(xx - ax) < seat_w * 0.075) & (yy > shoulder_y + h * 0.02) & (yy < h * 0.76)
                shade_a = 0.75
                paint(sub, arm & (yy < shoulder_y + sleeve_len), shirt * shade_a)
                paint(sub, arm & (yy >= shoulder_y + sleeve_len), skin * 0.8)
                paint(sub, ellipse(xx, yy, ax - side * seat_w * 0.05, h * 0.76, seat_w * 0.08, h * 0.03), skin * 0.85)

        # neck + head with face shading, hair / cap / sunglasses
        hr = seat_w * rng.uniform(0.17, 0.195) * (0.92 if child else 1.0)
        hy = shoulder_y - hr * 1.25
        hx = cx + lean
        neck = (np.abs(xx - hx) < hr * 0.45) & (yy > hy) & (yy < shoulder_y + h * 0.01)
        paint(sub, neck, skin * 0.75)
        head = ellipse(xx, yy, hx, hy, hr, hr * 1.18)
        face_shade = 0.78 + 0.22 * np.sqrt(np.clip(1 - ((xx - hx) / hr) ** 2, 0, 1))
        sub[head, :3] = (skin[None, None, :] * face_shade[..., None])[head]
        sub[head, 3] = 1
        hair_c = HAIR[rng.choice(len(HAIR), p=HAIR_W / HAIR_W.sum())]
        style = rng.random()
        if style < 0.30:                                          # cap with a brim
            cap_c = vary(pick(SHIRTS))
            cap = head & (yy < hy - hr * 0.15)
            paint(sub, cap, cap_c * 0.9)
            brim = ellipse(xx, yy, hx, hy - hr * 0.12, hr * 1.15, hr * 0.16)
            paint(sub, brim, cap_c * 0.6)
        elif style < 0.92:                                        # hair (long hair reaches the shoulders)
            top = head & (yy < hy - hr * (0.25 + 0.2 * rng.random()))
            paint(sub, top, hair_c)
            if rng.random() < 0.3:
                long_h = ellipse(xx, yy, hx, hy + hr * 0.35, hr * 1.12, hr * 1.1) & ~ellipse(xx, yy, hx, hy + hr * 0.1, hr * 0.78, hr * 1.0)
                paint(sub, long_h & (yy < shoulder_y + h * 0.06), hair_c)
        if rng.random() < 0.32:                                   # sunglasses
            shades = (np.abs(yy - (hy + hr * 0.05)) < hr * 0.16) & (np.abs(xx - hx) < hr * 0.82) & head
            paint(sub, shades, np.array([0.03, 0.03, 0.04]))
        else:                                                     # eyes as a faint darker band
            eyes = (np.abs(yy - (hy + hr * 0.05)) < hr * 0.07) & (np.abs(xx - hx) < hr * 0.6) & head
            sub[eyes, :3] *= 0.7

    # 2x box filter -> final resolution (alpha-weighted colour)
    a = img[..., 3:4]
    prem = img[..., :3] * a
    def down(x):
        return x.reshape(h // SS, SS, w // SS, SS, -1).mean(axis=(1, 3))
    a2, p2 = down(a), down(prem)
    rgb = np.where(a2 > 1e-4, p2 / np.maximum(a2, 1e-4), 0)
    # bleed colour into transparent texels (mip-maps then keep the right colours at the edges)
    filled = a2[..., 0] > 0.01
    rgb_f = rgb.copy()
    for _ in range(6):
        acc = np.zeros_like(rgb_f)
        cnt = np.zeros(filled.shape)
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            acc += np.roll(np.roll(rgb_f * filled[..., None], dy, 0), dx, 1)
            cnt += np.roll(np.roll(filled.astype(float), dy, 0), dx, 1)
        grow = (~filled) & (cnt > 0)
        rgb_f[grow] = acc[grow] / cnt[grow][:, None]
        filled = filled | grow
    out = np.zeros((h // SS, w // SS, 4))
    out[..., :3] = rgb_f
    out[..., 3] = a2[..., 0]
    out[:3, :, 3] = 0                                            # keep a transparent gutter between strips
    return out


def main():
    strips = [strip(i) for i in range(STRIPS)]
    atlas = np.concatenate(strips, axis=0)
    save_png("crowd_atlas", np.clip(atlas, 0, 1), out_path("Textures/Track", "crowd_atlas.png"))
    print("CROWD DONE")


if __name__ == "__main__":
    main()
