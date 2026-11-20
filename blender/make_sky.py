"""Renders an equirectangular sky panorama (physical sky + procedural cumulus clouds).

    blender --background --factory-startup --python blender/make_sky.py

The sun is placed at SUN_ELEVATION / SUN_AZIMUTH (degrees, azimuth measured in Blender's XY
plane from +X towards +Y). The game reads the same values to align its directional light.
"""
import math
import os
import sys

import addon_utils
import bpy

sys.path.insert(0, os.path.dirname(__file__))
from common import out_path, reset_scene

SUN_ELEVATION = 13.0
SUN_AZIMUTH = 60.0
CLOUD_K = float(os.environ.get("CLOUD_K", 4.5))   # cloud radiance relative to the sky model
WIDTH, HEIGHT = int(os.environ.get("SKY_W", 4096)), int(os.environ.get("SKY_W", 4096)) // 2


def sun_vector():
    el, az = math.radians(SUN_ELEVATION), math.radians(SUN_AZIMUTH)
    return (math.cos(el) * math.cos(az), math.cos(el) * math.sin(az), math.sin(el))


def build_world():
    world = bpy.data.worlds.new("Sky")
    bpy.context.scene.world = world
    world.use_nodes = True
    nt = world.node_tree
    nt.nodes.clear()
    N = nt.nodes.new
    L = nt.links.new

    out = N("ShaderNodeOutputWorld")
    bg = N("ShaderNodeBackground")
    L(bg.outputs[0], out.inputs[0])

    sky = N("ShaderNodeTexSky")
    types = [i.identifier for i in sky.bl_rna.properties["sky_type"].enum_items]
    print("sky types", types)
    for pref in ("MULTIPLE_SCATTERING", "NISHITA", "SINGLE_SCATTERING"):
        if pref in types:
            sky.sky_type = pref
            break
    sky.sun_elevation = math.radians(SUN_ELEVATION)
    # Blender's sky sun_rotation is measured from +Y clockwise; convert from our azimuth
    sky.sun_rotation = math.radians(90.0 - SUN_AZIMUTH)
    for attr, val in (("sun_disc", True), ("sun_size", math.radians(1.2)), ("sun_intensity", 0.6),
                      ("altitude", 300.0), ("air_density", 1.0), ("aerosol_density", 0.9),
                      ("ozone_density", 2.5), ("dust_density", 0.9)):
        if hasattr(sky, attr):
            setattr(sky, attr, val)

    # --- clouds: project the view direction onto a cloud plane ---
    coord = N("ShaderNodeTexCoord")
    sep = N("ShaderNodeSeparateXYZ")
    L(coord.outputs["Generated"], sep.inputs[0])
    zc = N("ShaderNodeMath"); zc.operation = "MAXIMUM"; zc.inputs[1].default_value = 0.035
    L(sep.outputs["Z"], zc.inputs[0])
    px = N("ShaderNodeMath"); px.operation = "DIVIDE"
    py = N("ShaderNodeMath"); py.operation = "DIVIDE"
    L(sep.outputs["X"], px.inputs[0]); L(zc.outputs[0], px.inputs[1])
    L(sep.outputs["Y"], py.inputs[0]); L(zc.outputs[0], py.inputs[1])
    comb = N("ShaderNodeCombineXYZ")
    L(px.outputs[0], comb.inputs[0]); L(py.outputs[0], comb.inputs[1])

    def noise(scale, detail, rough, offset):
        mapping = N("ShaderNodeMapping")
        mapping.inputs["Location"].default_value = offset
        L(comb.outputs[0], mapping.inputs["Vector"])
        tex = N("ShaderNodeTexNoise")
        tex.noise_dimensions = "3D"
        tex.inputs["Scale"].default_value = scale
        tex.inputs["Detail"].default_value = detail
        tex.inputs["Roughness"].default_value = rough
        L(mapping.outputs[0], tex.inputs["Vector"])
        return tex

    shape = noise(0.55, 10.0, 0.58, (0, 0, 0))
    coverage = noise(0.18, 2.0, 0.5, (11.3, 4.1, 0))
    sun = sun_vector()
    # density sampled slightly towards the sun -> fake self shadowing
    shade = noise(0.55, 10.0, 0.58, (-sun[0] * 0.06, -sun[1] * 0.06, 0))

    def ramp(src, a, b):
        r = N("ShaderNodeMapRange")
        r.inputs["From Min"].default_value = a
        r.inputs["From Max"].default_value = b
        r.clamp = True
        L(src, r.inputs["Value"])
        return r.outputs["Result"]

    cov = ramp(coverage.outputs["Fac"], 0.38, 0.62)
    thr = N("ShaderNodeMath"); thr.operation = "SUBTRACT"
    L(shape.outputs["Fac"], thr.inputs[0])
    inv = N("ShaderNodeMath"); inv.operation = "MULTIPLY_ADD"
    inv.inputs[1].default_value = -0.18; inv.inputs[2].default_value = 0.56
    L(cov, inv.inputs[0])
    L(inv.outputs[0], thr.inputs[1])
    density = ramp(thr.outputs[0], 0.0, 0.07)
    horizon = ramp(sep.outputs["Z"], 0.02, 0.22)
    dens = N("ShaderNodeMath"); dens.operation = "MULTIPLY"
    L(density, dens.inputs[0]); L(horizon, dens.inputs[1])

    lit = N("ShaderNodeMath"); lit.operation = "SUBTRACT"
    L(shape.outputs["Fac"], lit.inputs[0]); L(shade.outputs["Fac"], lit.inputs[1])
    lit_f = ramp(lit.outputs[0], -0.04, 0.05)

    shadow_col = N("ShaderNodeRGB"); shadow_col.outputs[0].default_value = (CLOUD_K * 0.42, CLOUD_K * 0.45, CLOUD_K * 0.55, 1)
    bright_col = N("ShaderNodeRGB"); bright_col.outputs[0].default_value = (CLOUD_K * 1.25, CLOUD_K * 1.08, CLOUD_K * 0.85, 1)
    cloud_col = N("ShaderNodeMix"); cloud_col.data_type = "RGBA"
    L(lit_f, cloud_col.inputs["Factor"])
    L(shadow_col.outputs[0], cloud_col.inputs[6]); L(bright_col.outputs[0], cloud_col.inputs[7])

    # clouds pick up sky light: multiply by a dimmed sky colour + constant
    sky_tint = N("ShaderNodeMix"); sky_tint.data_type = "RGBA"; sky_tint.blend_type = "MULTIPLY"
    sky_tint.inputs["Factor"].default_value = 0.35
    L(cloud_col.outputs[2], sky_tint.inputs[6]); L(sky.outputs[0], sky_tint.inputs[7])
    scale = N("ShaderNodeMix"); scale.data_type = "RGBA"; scale.blend_type = "MULTIPLY"
    scale.inputs["Factor"].default_value = 1.0
    scale.inputs[7].default_value = (1.0, 1.0, 1.0, 1)
    L(sky_tint.outputs[2], scale.inputs[6])

    final = N("ShaderNodeMix"); final.data_type = "RGBA"
    L(dens.outputs[0], final.inputs["Factor"])
    L(sky.outputs[0], final.inputs[6]); L(scale.outputs[2], final.inputs[7])
    # lower hemisphere: asphalt / grass coloured ground for reflections and the light probe
    ground = N("ShaderNodeRGB"); ground.outputs[0].default_value = (CLOUD_K * 0.13, CLOUD_K * 0.13, CLOUD_K * 0.11, 1)
    below = ramp(sep.outputs["Z"], 0.0, -0.03)
    gmix = N("ShaderNodeMix"); gmix.data_type = "RGBA"
    L(below, gmix.inputs["Factor"])
    L(final.outputs[2], gmix.inputs[6]); L(ground.outputs[0], gmix.inputs[7])
    L(gmix.outputs[2], bg.inputs["Color"])
    bg.inputs["Strength"].default_value = 1.0


def build_camera():
    cam_data = bpy.data.cameras.new("Pano")
    cam_data.type = "PANO"
    if hasattr(cam_data, "panorama_type"):
        cam_data.panorama_type = "EQUIRECTANGULAR"
    else:
        cam_data.cycles.panorama_type = "EQUIRECTANGULAR"
    cam = bpy.data.objects.new("Pano", cam_data)
    bpy.context.scene.collection.objects.link(cam)
    cam.rotation_euler = (math.radians(90), 0, math.radians(-90))  # centre of image = +X
    bpy.context.scene.camera = cam


def main():
    reset_scene()
    addon_utils.enable("cycles", default_set=True)
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 24
    scene.cycles.use_denoising = True
    scene.render.resolution_x = WIDTH
    scene.render.resolution_y = HEIGHT
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "PNG"
    scene.render.image_settings.color_mode = "RGB"
    scene.view_settings.view_transform = "AgX"
    scene.view_settings.exposure = -2.4
    build_world()
    build_camera()
    scene.render.filepath = out_path("Textures/Sky", "sky_sunset.png")
    bpy.ops.render.render(write_still=True)
    print("SKY DONE", sun_vector())


if __name__ == "__main__":
    main()
