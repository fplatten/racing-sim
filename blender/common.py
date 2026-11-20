"""Shared helpers for the Blender asset scripts (run with blender --background --python ...)."""
import os
import sys

import bpy
import numpy as np

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "src", "main", "resources")


def out_path(*parts):
    p = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)


# --------------------------------------------------------------------------- textures
def save_png(name, rgba, path):
    """rgba: float array (h, w, 4) in 0..1, row 0 = TOP of the image."""
    h, w, _ = rgba.shape
    img = bpy.data.images.new(name, width=w, height=h, alpha=True, float_buffer=False)
    flipped = np.ascontiguousarray(np.clip(rgba[::-1], 0.0, 1.0), dtype=np.float32)
    img.pixels.foreach_set(flipped.ravel())
    img.filepath_raw = path
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)
    print("wrote", path)


def periodic_value_noise(size, cells, rng):
    """Tileable smooth value noise in [0,1], shape (size, size)."""
    grid = rng.random((cells, cells))
    t = np.arange(size) * cells / size
    i0 = np.floor(t).astype(int)
    f = t - i0
    f = f * f * (3 - 2 * f)
    i1 = (i0 + 1) % cells
    i0 %= cells
    a = grid[np.ix_(i0, i0)]
    b = grid[np.ix_(i0, i1)]
    c = grid[np.ix_(i1, i0)]
    d = grid[np.ix_(i1, i1)]
    fy = f[:, None]
    fx = f[None, :]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(size, base_cells, octaves, rng, gain=0.5, lacunarity=2):
    total = np.zeros((size, size))
    amp, norm, cells = 1.0, 0.0, base_cells
    for _ in range(octaves):
        if cells > size:
            break
        total += amp * periodic_value_noise(size, cells, rng)
        norm += amp
        amp *= gain
        cells *= lacunarity
    return total / norm


def normal_from_height(h, strength):
    """Tileable tangent-space normal map (OpenGL / Y+ convention) from a height field."""
    dx = (np.roll(h, -1, axis=1) - np.roll(h, 1, axis=1)) * 0.5
    dy = (np.roll(h, 1, axis=0) - np.roll(h, -1, axis=0)) * 0.5
    nx = -dx * strength
    ny = -dy * strength
    nz = np.ones_like(h)
    ln = np.sqrt(nx * nx + ny * ny + nz * nz)
    n = np.stack([nx / ln, ny / ln, nz / ln], axis=-1)
    rgba = np.ones(h.shape + (4,))
    rgba[..., :3] = n * 0.5 + 0.5
    return rgba


def gray_rgba(g):
    rgba = np.ones(g.shape + (4,))
    rgba[..., 0] = rgba[..., 1] = rgba[..., 2] = g
    return rgba


def color_rgba(rgb):
    rgba = np.ones(rgb.shape[:2] + (4,))
    rgba[..., :3] = rgb
    return rgba


# --------------------------------------------------------------------------- materials
def principled(name, color=(0.8, 0.8, 0.8, 1), metallic=0.0, roughness=0.5):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = color
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    return mat


def export_glb(path, objects=None):
    bpy.ops.object.select_all(action="DESELECT")
    if objects:
        for o in objects:
            o.select_set(True)
    bpy.ops.export_scene.gltf(
        filepath=path,
        export_format="GLB",
        use_selection=bool(objects),
        export_apply=True,
        export_yup=True,
        export_texcoords=True,
        export_normals=True,
        export_materials="EXPORT",
        export_cameras=False,
        export_lights=False,
        **({"export_vertex_color": "ACTIVE"} if "export_vertex_color" in
           bpy.ops.export_scene.gltf.get_rna_type().properties.keys() else {}),
    )
    print("exported", path)


def args_after_dashes():
    return sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
