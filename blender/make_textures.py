"""Generates the tileable PBR track textures.

    blender --background --factory-startup --python blender/make_textures.py
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
from common import (out_path, save_png, fbm, periodic_value_noise, normal_from_height,
                    gray_rgba, color_rgba)

rng = np.random.default_rng(1990)
T = "Textures/Track"


def to_srgb(rgb):
    """Albedos below are authored as linear reflectance; PNG base colour maps are sRGB."""
    rgb = np.clip(rgb, 0, 1)
    return np.where(rgb <= 0.0031308, rgb * 12.92, 1.055 * np.power(rgb, 1 / 2.4) - 0.055)


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


# ----------------------------------------------------------------------------- asphalt
def asphalt(size=1024):
    """Coarse race asphalt: dark binder with exposed light aggregate (tile = 3 m).

    Only fine grain on purpose. Anything big enough to read as a feature (tar blotches, oil
    stains, light/dark patches) repeats every 3 m, and at racing speed a car covers about one
    tile per frame, so such features strobe into one stain that seems to stand still (the
    wagon-wheel effect). Stains and patches are painted per vertex instead, from non-repeating
    noise along the whole lap (TrackBuilder.surfaceVariation)."""
    large = fbm(size, 4, 3, rng)          # drawn but unused: keeps the random sequence of the
    stones_a = periodic_value_noise(size, 640, rng)
    stones_b = periodic_value_noise(size, 320, rng)
    grit = periodic_value_noise(size, 512, rng)
    aggregate = smoothstep(0.66, 0.80, stones_a * 0.65 + stones_b * 0.35) * 0.8
    tar = fbm(size, 16, 3, rng)           # other textures unchanged (no blotches any more)
    del large, tar

    height = 0.55 * aggregate + 0.25 * grit + 0.2 * stones_b
    base = 0.0925 + 0.015 * grit
    stone_col = 0.13 + 0.06 * stones_b
    g = base * (1 - aggregate) + stone_col * aggregate
    rgb = np.stack([g * 1.02, g * 1.0, g * 0.97], axis=-1)
    save_png("asphalt_albedo", color_rgba(to_srgb(rgb)), out_path(T, "asphalt_albedo.png"))
    save_png("asphalt_normal", normal_from_height(height, 3.0), out_path(T, "asphalt_normal.png"))
    rough = np.clip(0.92 - 0.22 * aggregate, 0, 1)
    save_png("asphalt_rough", gray_rgba(rough), out_path(T, "asphalt_rough.png"))


# ----------------------------------------------------------------------------- concrete wall
def concrete_wall(w=1024, h=512):
    """Painted retaining wall: u = 4 m along the wall, v = full 1.2 m height.
    Row 0 of the image is the TOP of the wall."""
    big = fbm(1024, 8, 6, rng)[:h, :w]
    fine = periodic_value_noise(1024, 256, rng)[:h, :w]
    pits = smoothstep(0.80, 0.9, periodic_value_noise(1024, 300, rng)[:h, :w])
    v = np.linspace(0, 1, h)[:, None] * np.ones((1, w))          # 0 top .. 1 bottom
    u = np.linspace(0, 1, w)[None, :] * np.ones((h, 1))

    paint = 0.86 + 0.06 * big - 0.05 * fine
    # grime and tire rubber near the base, streaking upwards
    streaks = fbm(1024, 32, 4, rng)[:h, :w]
    grime = smoothstep(0.7, 1.05, v + 0.15 * (streaks - 0.5)) * 0.7
    scuff_rows = np.zeros_like(v)
    for _ in range(6):
        y0 = rng.uniform(0.62, 0.95)
        x0, x1 = sorted(rng.uniform(0, 1, 2))
        thick = rng.uniform(0.002, 0.008)
        band = np.exp(-((v - y0) / thick) ** 2) * ((u > x0) & (u < x1))
        scuff_rows = np.maximum(scuff_rows, band * rng.uniform(0.2, 0.5))
    g = paint * (1 - 0.45 * grime) * (1 - 0.75 * scuff_rows) - 0.25 * pits
    seam = np.exp(-((np.minimum(u, 1 - u)) / 0.0025) ** 2)       # panel joint every 4 m
    g = g * (1 - 0.6 * seam)
    rgb = np.stack([g, g * 0.995, g * 0.98], axis=-1)
    save_png("wall_albedo", color_rgba(rgb), out_path(T, "wall_albedo.png"))

    height = 0.5 * fine + 0.3 * big - 0.8 * seam - 0.4 * pits
    hpad = np.zeros((1024, 1024))
    hpad[:h, :w] = height
    n = normal_from_height(hpad, 3.0)[:h, :w]
    save_png("wall_normal", n, out_path(T, "wall_normal.png"))
    rough = np.clip(0.65 + 0.2 * grime + 0.1 * fine, 0, 1)
    save_png("wall_rough", gray_rgba(rough), out_path(T, "wall_rough.png"))


# ----------------------------------------------------------------------------- grass
def grass(size=1024):
    """Mown grass (tile = 6 m on the ground). Kept even on purpose: the large-scale colour
    (stripes, meadow, dry patches, forest floor) comes from the ground mesh vertex colours, so
    nothing here repeats visibly. Blades in two sizes, small clumps, a few dry blades and clover."""
    g = np.random.default_rng(606)                                 # own generator: other textures unchanged
    blades = periodic_value_noise(size, 512, g)
    blades2 = periodic_value_noise(size, 256, g)
    clumps = fbm(size, 16, 4, g)
    dry = smoothstep(0.62, 0.80, periodic_value_noise(size, 128, g)) * 0.45
    clover = smoothstep(0.70, 0.85, fbm(size, 32, 3, g)) * 0.35
    shade = 0.72 + 0.34 * blades * blades2 + 0.16 * clumps
    green = np.array([0.075, 0.150, 0.040])
    straw = np.array([0.200, 0.180, 0.085])
    clov = np.array([0.060, 0.150, 0.055])
    rgb = green[None, None, :] * (1 - dry[..., None]) + straw[None, None, :] * dry[..., None]
    rgb = rgb * (1 - clover[..., None]) + clov[None, None, :] * clover[..., None]
    rgb = rgb * shade[..., None]
    save_png("grass_albedo", color_rgba(to_srgb(rgb)), out_path(T, "grass_albedo.png"))
    height = 0.6 * blades + 0.4 * blades2
    save_png("grass_normal", normal_from_height(height, 4.0), out_path(T, "grass_normal.png"))


# ----------------------------------------------------------------------------- chain-link fence
def chainlink(size=512, diamonds=8):
    """RGBA chain-link mesh (tile = 0.5 m), alpha = wire."""
    y, x = np.mgrid[0:size, 0:size] / size * diamonds
    a = np.abs(((x + y) % 1.0) - 0.5)
    b = np.abs(((x - y) % 1.0) - 0.5)
    wire = np.maximum(smoothstep(0.43, 0.49, a), smoothstep(0.43, 0.49, b))
    rgba = np.ones((size, size, 4))
    shade = 0.55 + 0.25 * (a > b)
    rgba[..., 0] = shade * 0.9
    rgba[..., 1] = shade * 0.92
    rgba[..., 2] = shade * 0.95
    rgba[..., 3] = wire
    save_png("chainlink", rgba, out_path(T, "chainlink.png"))


# The grandstand crowd atlas is made by make_crowd.py.


# ----------------------------------------------------------------------------- carbon fibre
def carbon(size=256, weaves=8):
    y, x = np.mgrid[0:size, 0:size] / size * weaves
    tx = ((np.floor(x) + np.floor(y)) % 2)
    fx = np.abs(np.sin(np.pi * (x if True else y)))
    fy = np.abs(np.sin(np.pi * y))
    weave = np.where(tx > 0, fx, fy)
    g = 0.035 + 0.05 * weave
    save_png("carbon_albedo", gray_rgba(to_srgb(g)), out_path("Textures/Car", "carbon_albedo.png"))
    save_png("carbon_normal", normal_from_height(weave, 1.5), out_path("Textures/Car", "carbon_normal.png"))


if __name__ == "__main__":
    asphalt()
    concrete_wall()
    grass()
    chainlink()
    carbon()
    print("TEXTURES DONE")
