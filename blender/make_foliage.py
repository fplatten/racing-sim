"""Trees, shrubs and foliage textures for the trackside scenery.

    blender --background --factory-startup --python blender/make_foliage.py

Textures (Textures/Props):
  leaves_atlas.png   RGBA, four 512 px quadrants (Blender UV, origin bottom-left):
                       u 0-0.5 / v 0.5-1  broadleaf cluster A (oak)
                       u 0.5-1 / v 0.5-1  broadleaf cluster B (maple)
                       u 0-0.5 / v 0-0.5  broadleaf cluster C (young tree / shrub)
                       u 0.5-1 / v 0-0.5  conifer spray, u = along the branch (trunk -> tip)
  bark_albedo.png / bark_normal.png   tileable bark (u around the trunk, v = 2 m up)
  treeline.png       RGBA silhouette of a distant wood edge, tileable horizontally (~104 m x 26 m)

Models (Models/Props), Blender Z-up, ground at z = 0, exported to glTF Y-up:
  tree_0 oak, tree_1 maple, tree_2 spruce, tree_3 young tree, shrub_0
Each model has a "Bark_Trunk" object (material Bark) and a "Leaves_Crown" object (material Leaves):
crowns are alpha-tested leaf cards with normals bent outwards from the crown centre (soft,
volumetric shading) and vertex-colour ambient occlusion (darker inside and underneath).
"""
import math
import os
import random
import sys

import bpy
import numpy as np
from mathutils import Vector

sys.path.insert(0, os.path.dirname(__file__))
from common import (out_path, save_png, fbm, periodic_value_noise, normal_from_height, reset_scene,
                    principled, export_glb)

rng = np.random.default_rng(77)
random.seed(77)
P = "Textures/Props"


def to_srgb(rgb):
    rgb = np.clip(rgb, 0, 1)
    return np.where(rgb <= 0.0031308, rgb * 12.92, 1.055 * np.power(rgb, 1 / 2.4) - 0.055)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


# =============================================================================== textures
def stamp(rgb, alpha, xs, ys, colors, radius):
    """Paints discs of `radius` px at the points (xs, ys) with per-point colours (N,3)."""
    h, w = alpha.shape
    r = int(math.ceil(radius))
    for oy in range(-r, r + 1):
        for ox in range(-r, r + 1):
            if ox * ox + oy * oy > radius * radius:
                continue
            px = np.clip(np.round(xs).astype(int) + ox, 0, w - 1)
            py = np.clip(np.round(ys).astype(int) + oy, 0, h - 1)
            rgb[py, px] = colors
            alpha[py, px] = 1.0


def line_points(x0, y0, x1, y1, step=0.7):
    n = max(2, int(math.hypot(x1 - x0, y1 - y0) / step))
    t = np.linspace(0, 1, n)
    return x0 + (x1 - x0) * t, y0 + (y1 - y0) * t


def draw_leaf(rgb, alpha, cx, cy, ang, length, width, color):
    """One leaf: base at (cx, cy), pointing along `ang`, with a midrib and a light/dark gradient."""
    h, w = alpha.shape
    r = int(length) + 2
    x0, x1 = max(0, int(cx) - r), min(w, int(cx) + r)
    y0, y1 = max(0, int(cy) - r), min(h, int(cy) + r)
    if x0 >= x1 or y0 >= y1:
        return
    ys, xs = np.mgrid[y0:y1, x0:x1].astype(np.float32)
    dx, dy = xs - cx, ys - cy
    ca, sa = math.cos(ang), math.sin(ang)
    lx = dx * ca + dy * sa
    ly = -dx * sa + dy * ca
    t = lx / length
    half = width * np.power(np.clip(np.sin(np.pi * np.clip(t, 0, 1)), 0, 1), 0.75) * (1 - 0.25 * t)
    mask = (t > 0) & (t < 1) & (np.abs(ly) < half)
    if not mask.any():
        return
    across = np.abs(ly) / np.maximum(half, 1e-3)
    shade = 0.82 + 0.28 * (1 - across) + 0.12 * (ly > 0)          # one half catches more light
    shade = np.where(across < 0.08, shade * 0.78, shade)          # midrib
    shade = shade * (0.9 + 0.2 * t)
    region = rgb[y0:y1, x0:x1]
    region[mask] = (np.array(color)[None, :] * shade[mask][:, None])
    alpha[y0:y1, x0:x1][mask] = 1.0


def broadleaf_cluster(size, base, spread, seed):
    """A twig cluster of leaves filling a circle; returns rgb (size,size,3), alpha (size,size)."""
    r = random.Random(seed)
    rgb = np.zeros((size, size, 3), np.float32)
    alpha = np.zeros((size, size), np.float32)
    c = size / 2
    twig_col = np.array([0.10, 0.07, 0.04], np.float32)
    leaves = []
    for k in range(8):
        a = k * math.tau / 8 + r.uniform(-0.3, 0.3)
        tl = size * r.uniform(0.30, 0.42)
        xs, ys = line_points(c, c, c + math.cos(a) * tl, c + math.sin(a) * tl)
        stamp(rgb, alpha, xs, ys, np.tile(twig_col, (len(xs), 1)), 2.2)
        for j in range(10):
            f = 0.15 + 0.85 * j / 10
            bx, by = c + math.cos(a) * tl * f, c + math.sin(a) * tl * f
            for side in (-1, 1):
                leaves.append((bx, by, a + side * r.uniform(0.5, 0.9)))
    for _ in range(70):                                            # fill the gaps
        rr = size * 0.36 * math.sqrt(r.random())
        a = r.uniform(0, math.tau)
        leaves.append((c + math.cos(a) * rr, c + math.sin(a) * rr, r.uniform(0, math.tau)))
    r.shuffle(leaves)
    for bx, by, a in leaves:
        length = size * r.uniform(0.085, 0.125)
        v = r.uniform(-1, 1)
        col = [max(0.0, base[i] + spread[i] * v + r.uniform(-0.012, 0.012)) for i in range(3)]
        draw_leaf(rgb, alpha, bx, by, a, length, length * r.uniform(0.28, 0.36), col)
    return rgb, alpha


def conifer_spray(size, seed):
    """Spruce branch seen from above: u (x) runs from the trunk (left) to the tip (right)."""
    r = random.Random(seed)
    rgb = np.zeros((size, size, 3), np.float32)
    alpha = np.zeros((size, size), np.float32)
    cy = size / 2
    twig = np.array([0.09, 0.06, 0.04], np.float32)
    pts_x, pts_y, cols = [], [], []

    def needles_along(x0, y0, x1, y1, tip_fresh):
        n = int(math.hypot(x1 - x0, y1 - y0) / 4.5)
        ang = math.atan2(y1 - y0, x1 - x0)
        for i in range(n):
            t = i / max(1, n - 1)
            bx, by = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
            for side in (-1, 1):
                a = ang + side * r.uniform(0.75, 1.05)
                ln = size * r.uniform(0.022, 0.032) * (1 - 0.35 * t)
                xs, ys = line_points(bx, by, bx + math.cos(a) * ln, by + math.sin(a) * ln, 0.8)
                fresh = tip_fresh and t > 0.7
                base = (0.07, 0.15, 0.06) if fresh else (0.035, 0.085, 0.045)
                v = r.uniform(0.8, 1.2)
                pts_x.append(xs)
                pts_y.append(ys)
                cols.append(np.tile(np.array(base, np.float32) * v, (len(xs), 1)))

    def envelope(x):                                               # half-width of the spray
        return size * 0.44 * math.sin(math.pi * (0.12 + 0.88 * x)) ** 0.8 * (1 - 0.35 * x)

    x0, x1 = size * 0.02, size * 0.97
    xs, ys = line_points(x0, cy, x1, cy)
    stamp(rgb, alpha, xs, ys, np.tile(twig, (len(xs), 1)), 3.0)
    needles_along(x0, cy, x1, cy, True)
    x = x0 + size * 0.05
    while x < x1 - size * 0.05:
        f = x / size
        for side in (-1, 1):
            a = side * r.uniform(0.55, 0.85)
            ln = min(envelope(f) / max(0.2, abs(math.sin(a))), size * 0.42)
            ex, ey = x + math.cos(a) * ln, cy + math.sin(a) * ln
            sx, sy = line_points(x, cy, ex, ey)
            stamp(rgb, alpha, sx, sy, np.tile(twig, (len(sx), 1)), 1.6)
            needles_along(x, cy, ex, ey, True)
        x += size * r.uniform(0.055, 0.075)
    stamp(rgb, alpha, np.concatenate(pts_x), np.concatenate(pts_y), np.concatenate(cols), 1.3)
    return rgb, alpha


def downsample(rgb, alpha):
    """2x2 box filter with premultiplied colour; transparent texels get the mean leaf colour
    so mipmaps do not fade the cards towards black."""
    h, w = alpha.shape
    pre = rgb * alpha[..., None]
    pre = pre.reshape(h // 2, 2, w // 2, 2, 3).mean(axis=(1, 3))
    a = alpha.reshape(h // 2, 2, w // 2, 2).mean(axis=(1, 3))
    mean = (rgb[alpha > 0.5].mean(axis=0) if (alpha > 0.5).any() else np.zeros(3)) * 0.85
    col = np.where(a[..., None] > 1e-3, pre / np.maximum(a[..., None], 1e-3), mean[None, None, :])
    col = col * a[..., None] + mean[None, None, :] * (1 - a[..., None])
    return col, a


def leaves_atlas():
    q = 512
    atlas = np.zeros((2 * q, 2 * q, 4), np.float32)
    quads = [
        (0, 0, broadleaf_cluster(2 * q, (0.050, 0.110, 0.030), (0.020, 0.040, 0.012), 1)),   # A: oak, dark
        (0, q, broadleaf_cluster(2 * q, (0.075, 0.150, 0.035), (0.030, 0.050, 0.015), 2)),   # B: maple, fresh
        (q, 0, broadleaf_cluster(2 * q, (0.090, 0.150, 0.040), (0.035, 0.040, 0.020), 3)),   # C: young / shrub
        (q, q, conifer_spray(2 * q, 4)),                                                      # D: spruce
    ]
    for row, col, (rgb, alpha) in quads:
        c, a = downsample(rgb, alpha)
        atlas[row:row + q, col:col + q, :3] = to_srgb(c)
        atlas[row:row + q, col:col + q, 3] = a
    save_png("leaves_atlas", atlas, out_path(P, "leaves_atlas.png"))


def bark(size=512):
    n = fbm(size, 8, 4, rng)
    xs = np.arange(size)[None, :] / size
    plates = np.abs(np.sin((xs * 14 + n * 3.0) * np.pi))           # vertical fissured plates
    fine = periodic_value_noise(size, 128, rng)
    height = 0.65 * smoothstep(0.08, 0.6, plates) + 0.25 * n + 0.10 * fine
    base = np.array([0.075, 0.060, 0.048])
    rgb = base[None, None, :] * (0.55 + 0.75 * height[..., None])
    rgba = np.ones((size, size, 4))
    rgba[..., :3] = to_srgb(rgb)
    save_png("bark_albedo", rgba, out_path(P, "bark_albedo.png"))
    save_png("bark_normal", normal_from_height(height, 6.0), out_path(P, "bark_normal.png"))


def treeline(w=2048, h=512):
    """Edge of a mixed wood seen from ~0.5 km: overlapping crowns, tileable along x."""
    r = random.Random(9)
    noise = fbm(w, 64, 5, rng)[:h]
    ys, xs = np.mgrid[0:h, 0:w].astype(np.float32)
    alpha = np.zeros((h, w), np.float32)
    rgb = np.zeros((h, w, 3), np.float32)
    crowns = []
    for _ in range(170):
        kind = "conifer" if r.random() < 0.3 else "broad"
        top = h * r.uniform(0.04, 0.48)                            # row of the crown top (0 = image top)
        crowns.append((r.uniform(0, w), top, kind, r.uniform(0.75, 1.15)))
    crowns.sort(key=lambda c: c[1])                                # tallest drawn first, lower ones in front
    for cx, top, kind, tint in crowns:
        dx = (xs - cx + w / 2) % w - w / 2                         # wrap horizontally
        if kind == "conifer":
            width = (ys - top) * r.uniform(0.28, 0.36)
            mask = (ys > top) & (np.abs(dx) < width * (1 + 0.25 * (noise - 0.5)))
            light = (0.8 + 0.25 * smoothstep(0, 1, -dx / np.maximum(width, 1))) * (1 - 0.5 * np.clip((ys - top) / h, 0, 1))
            base = np.array([0.025, 0.055, 0.035])
        else:
            rad = r.uniform(32, 70)
            cy = top + rad
            d = np.sqrt(dx * dx + (ys - cy) ** 2 * 1.1)
            crown = d < rad * (0.85 + 0.35 * noise)
            mask = crown | ((ys > cy) & (np.abs(dx) < rad * 0.95))
            # lit from above-left; below its centre a crown fades into the shared dark understory
            light = 0.75 + 0.55 * np.clip((cy - ys) / rad * 0.6 - dx / rad * 0.3 + 0.4, 0, 1)
            fade = np.clip((ys - cy) / (rad * 1.2), 0, 1)[..., None]
            base = np.array([0.045, 0.085, 0.030]) if r.random() < 0.7 else np.array([0.07, 0.10, 0.035])
            lit = base[None, None, :] * tint * light[..., None] * (0.8 + 0.4 * noise[..., None])
            dark = np.array([0.020, 0.036, 0.016])[None, None, :] * (0.85 + 0.3 * noise[..., None])
            col = lit * (1 - fade) + dark * fade
            rgb = np.where(mask[..., None], col, rgb)
            alpha = np.maximum(alpha, mask.astype(np.float32))
            continue
        col = base[None, None, :] * tint * light[..., None] * (0.8 + 0.4 * noise[..., None])
        rgb = np.where(mask[..., None], col, rgb)
        alpha = np.maximum(alpha, mask.astype(np.float32))
    rgb *= (0.55 + 0.45 * np.clip(1.2 - ys / h, 0, 1))[..., None]  # darker towards the ground
    alpha[int(h * 0.75):] = 1.0                                    # solid base: trunks and undergrowth
    rgb[int(h * 0.75):] = np.maximum(rgb[int(h * 0.75):], 0.0) * 0.9 + np.array([0.015, 0.025, 0.012]) * 0.1
    rgba = np.ones((h, w, 4))
    mean = rgb[alpha > 0.5].mean(axis=0)
    rgba[..., :3] = to_srgb(np.where(alpha[..., None] > 0.5, rgb, mean[None, None, :]))
    rgba[..., 3] = alpha
    save_png("treeline", rgba, out_path(P, "treeline.png"))


# =============================================================================== meshes
class MeshData:
    def __init__(self):
        self.verts, self.faces, self.uvs, self.cols, self.normals = [], [], [], [], []

    def add_face(self, pts, uvs, cols, normals=None):
        base = len(self.verts)
        self.verts.extend(pts)
        self.faces.append(tuple(range(base, base + len(pts))))
        self.uvs.append(uvs)
        self.cols.append(cols)
        self.normals.append(normals)

    def to_object(self, name, material, smooth=True):
        me = bpy.data.meshes.new(name)
        me.from_pydata([tuple(v) for v in self.verts], [], self.faces)
        me.update()
        uv = me.uv_layers.new(name="UVMap")
        col = me.color_attributes.new("Col", "BYTE_COLOR", "CORNER")
        custom = []
        for poly, uvs, cols, nrms in zip(me.polygons, self.uvs, self.cols, self.normals):
            poly.use_smooth = smooth
            for k, li in enumerate(poly.loop_indices):
                uv.data[li].uv = uvs[k]
                col.data[li].color = cols[k]
        if any(n is not None for n in self.normals):
            for poly, nrms in zip(me.polygons, self.normals):
                for k in range(len(poly.loop_indices)):
                    custom.append(tuple(nrms[k]) if nrms else tuple(poly.normal))
            me.normals_split_custom_set(custom)
        me.materials.append(material)
        obj = bpy.data.objects.new(name, me)
        bpy.context.collection.objects.link(obj)
        return obj


def ortho(d):
    d = d.normalized()
    a = Vector((0, 0, 1)) if abs(d.z) < 0.9 else Vector((1, 0, 0))
    u = d.cross(a).normalized()
    return u, d.cross(u).normalized()


def limb(md, p0, p1, r0, r1, seg=8, v0=0.0):
    """Tapered cylinder from p0 to p1 with bark UVs (u around, v = metres / 2)."""
    d = p1 - p0
    u, w = ortho(d)
    length = d.length
    ring0 = [p0 + (u * math.cos(a) + w * math.sin(a)) * r0 for a in np.linspace(0, math.tau, seg + 1)]
    ring1 = [p1 + (u * math.cos(a) + w * math.sin(a)) * r1 for a in np.linspace(0, math.tau, seg + 1)]
    for i in range(seg):
        c0, c1 = i / seg, (i + 1) / seg
        md.add_face([ring0[i], ring0[i + 1], ring1[i + 1], ring1[i]],
                    [(c0, v0), (c1, v0), (c1, v0 + length / 2), (c0, v0 + length / 2)],
                    [(0.85, 0.85, 0.85, 1)] * 4)
    return v0 + length / 2


def card(md, centre, a, b, uv_rect, normal_fn, ao_fn):
    """Quad leaf card centre +- a +- b, UVs over uv_rect (u0, v0, u1, v1)."""
    pts = [centre - a - b, centre + a - b, centre + a + b, centre - a + b]
    u0, v0, u1, v1 = uv_rect
    uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
    nrm = [normal_fn(p) for p in pts]
    cols = [(g, g, g, 1.0) for g in (ao_fn(p) for p in pts)]
    md.add_face(pts, uvs, cols, nrm)


QUAD = {"A": (0.004, 0.504, 0.496, 0.996), "B": (0.504, 0.504, 0.996, 0.996),
        "C": (0.004, 0.004, 0.496, 0.496), "D": (0.502, 0.004, 0.998, 0.496)}


def broadleaf_tree(name, height, crown_z, radius, radius_v, cards, card_size, quad, trunk_r, limbs, seed):
    r = random.Random(seed)
    bark_m, leaf_m = principled("Bark", (0.2, 0.15, 0.1, 1), 0, 0.9), principled("Leaves", (0.2, 0.4, 0.1, 1), 0, 0.8)
    trunk, crown = MeshData(), MeshData()
    centre = Vector((0, 0, crown_z))
    top = Vector((r.uniform(-0.3, 0.3), r.uniform(-0.3, 0.3), crown_z + radius_v * 0.35))
    if trunk_r > 0:
        mid = Vector((r.uniform(-0.25, 0.25), r.uniform(-0.25, 0.25), crown_z - radius_v * 0.55))
        v = limb(trunk, Vector((0, 0, -0.2)), mid, trunk_r * 1.15, trunk_r * 0.8)
        limb(trunk, mid, top, trunk_r * 0.8, trunk_r * 0.25, 8, v)
    ends = []
    for k in range(limbs):
        a = k * math.tau / limbs + r.uniform(-0.4, 0.4)
        z0 = crown_z - radius_v * r.uniform(0.2, 0.6)
        start = Vector((0, 0, z0))
        end = centre + Vector((math.cos(a) * radius * 0.7, math.sin(a) * radius * 0.7, r.uniform(-0.2, 0.6) * radius_v))
        if trunk_r > 0:
            limb(trunk, start, end, trunk_r * 0.45, trunk_r * 0.1, 6)
        ends.append(end)

    def normal_fn(p):
        q = p - centre
        n = Vector((q.x / radius, q.y / radius, q.z / radius_v))
        n = n.normalized() if n.length > 1e-4 else Vector((0, 0, 1))
        return (n * 0.8 + Vector((0, 0, 0.35))).normalized()

    def ao_fn(p):
        q = p - centre
        depth = math.sqrt((q.x / radius) ** 2 + (q.y / radius) ** 2 + (q.z / radius_v) ** 2)
        g = 0.38 + 0.55 * min(1.0, max(0.0, (depth - 0.25) / 0.75)) + 0.14 * max(-1.0, min(1.0, q.z / radius_v))
        return max(0.25, min(1.0, g))

    for i in range(cards):
        if i < cards * 0.3 and ends:                               # clusters at the limb ends
            e = r.choice(ends)
            p = e + Vector((r.gauss(0, 0.8), r.gauss(0, 0.8), r.gauss(0, 0.6)))
        else:
            while True:
                d = Vector((r.gauss(0, 1), r.gauss(0, 1), r.gauss(0, 1))).normalized()
                f = 0.45 + 0.55 * math.sqrt(r.random())
                p = centre + Vector((d.x * radius * f, d.y * radius * f, d.z * radius_v * f))
                if p.z > crown_z - radius_v * 0.7:
                    break
        n = Vector((r.gauss(0, 1), r.gauss(0, 1), r.gauss(0, 1) + 0.6)).normalized()
        u, w = ortho(n)
        rot = r.uniform(0, math.tau)
        a = (u * math.cos(rot) + w * math.sin(rot)) * (card_size * r.uniform(0.8, 1.2) / 2)
        b = n.cross(a)
        card(crown, p, a, b, QUAD[quad], normal_fn, ao_fn)
    objs = [crown.to_object("Leaves_Crown", leaf_m)]
    if trunk.faces:
        objs.append(trunk.to_object("Bark_Trunk", bark_m))
    export_glb(out_path("Models/Props", name + ".glb"))
    print(name, "cards", cards, "trunk faces", len(trunk.faces))


def spruce(name, height, base_radius, seed):
    r = random.Random(seed)
    bark_m, leaf_m = principled("Bark", (0.2, 0.15, 0.1, 1), 0, 0.9), principled("Leaves", (0.2, 0.4, 0.1, 1), 0, 0.8)
    trunk, crown = MeshData(), MeshData()
    limb(trunk, Vector((0, 0, -0.2)), Vector((0, 0, height)), 0.30, 0.03, 8)
    h0 = 1.3
    h = h0
    while h < height - 0.5:
        frac = (height - h) / (height - h0)                         # 1 at the bottom, 0 at the top
        length = 0.35 + base_radius * frac ** 0.9
        n = 7 if frac > 0.3 else 5
        off = r.uniform(0, math.tau)
        for k in range(n):
            ang = off + k * math.tau / n + r.uniform(-0.25, 0.25)
            droop = math.radians(r.uniform(12, 26) * frac - 6 * (1 - frac))
            dirv = Vector((math.cos(ang) * math.cos(droop), math.sin(ang) * math.cos(droop), -math.sin(droop)))
            side = dirv.cross(Vector((0, 0, 1))).normalized()
            start = Vector((0, 0, h)) + dirv * 0.08
            for roll in ((r.uniform(-0.2, 0.2),) if k % 2 else (r.uniform(-0.2, 0.2), math.pi / 2)):
                s = side * math.cos(roll) + dirv.cross(side) * math.sin(roll)
                width = min(1.7, 0.85 * length) * (0.6 if roll > 1 else 1.0) / 2
                pts = [start - s * width, start + dirv * length - s * width, start + dirv * length + s * width,
                       start + s * width]
                u0, v0, u1, v1 = QUAD["D"]
                uvs = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
                nrm, cols = [], []
                for p in pts:
                    radial = Vector((p.x, p.y, 0))
                    rl = radial.length
                    nn = (radial.normalized() if rl > 1e-3 else Vector((1, 0, 0))) + Vector((0, 0, 0.45))
                    nrm.append(tuple(nn.normalized()))
                    g = 0.35 + 0.6 * min(1.0, rl / max(0.4, length)) + 0.1 * (1 - frac)
                    g = max(0.25, min(1.0, g))
                    cols.append((g, g, g, 1.0))
                crown.add_face(pts, uvs, cols, nrm)
        h += r.uniform(0.42, 0.58)
    # leader: two crossed vertical cards at the top
    for ang in (0, math.pi / 2):
        s = Vector((math.cos(ang), math.sin(ang), 0)) * 0.35
        base = Vector((0, 0, height - 1.4))
        top = Vector((0, 0, height + 0.3))
        u0, v0, u1, v1 = QUAD["D"]
        crown.add_face([base - s, top - s * 0.2, top + s * 0.2, base + s], [(u0, v0), (u1, v0), (u1, v1), (u0, v1)],
                       [(0.9, 0.9, 0.9, 1)] * 4, [tuple(Vector((0, 0, 1)))] * 4)
    crown.to_object("Leaves_Crown", leaf_m)
    trunk.to_object("Bark_Trunk", bark_m)
    export_glb(out_path("Models/Props", name + ".glb"))
    print(name, "cards", len(crown.faces))


def clear():
    for o in list(bpy.data.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    for m in list(bpy.data.meshes):
        bpy.data.meshes.remove(m)
    for m in list(bpy.data.materials):
        bpy.data.materials.remove(m)


if __name__ == "__main__":
    reset_scene()
    leaves_atlas()
    bark()
    treeline()
    clear(); broadleaf_tree("tree_0", 13, 8.2, 5.2, 4.0, 300, 2.3, "A", 0.36, 7, 10)     # oak
    clear(); broadleaf_tree("tree_1", 15, 10.0, 4.2, 5.0, 280, 2.1, "B", 0.30, 6, 11)    # maple
    clear(); spruce("tree_2", 16, 3.3, 12)                                                # spruce
    clear(); broadleaf_tree("tree_3", 7, 4.9, 2.5, 2.6, 120, 1.5, "C", 0.13, 4, 13)      # young tree
    clear(); broadleaf_tree("shrub_0", 1.8, 0.95, 1.6, 0.95, 55, 1.1, "C", 0.0, 0, 14)   # shrub
    print("FOLIAGE DONE")
