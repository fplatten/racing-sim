"""Preview render of the props: blender --background --python blender/preview_props.py -- out.png"""
import math, os, sys
import bpy, addon_utils
from mathutils import Vector
sys.path.insert(0, os.path.dirname(__file__))
from common import RES, reset_scene, args_after_dashes
out = args_after_dashes()[0]
reset_scene()
addon_utils.enable("cycles", default_set=True)
placements = [("grandstand.glb", (-20, 10, 0)), ("timing_tower.glb", (40, 30, 0)), ("tree_0.glb", (28, -4, 0)),
              ("tree_1.glb", (34, -2, 0)), ("tree_2.glb", (40, -5, 0)), ("tent.glb", (50, 6, 0)), ("light_tower.glb", (60, 14, 0))]
for f, loc in placements:
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(RES, "Models/Props", f))
    for o in set(bpy.data.objects) - before:
        if o.parent is None:
            o.location = loc
bpy.ops.mesh.primitive_plane_add(size=400)
world = bpy.data.worlds.new("w"); bpy.context.scene.world = world; world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.5, 0.65, 0.9, 1)
sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN")); sun.data.energy = 4
sun.rotation_euler = (math.radians(55), 0, math.radians(-40)); bpy.context.scene.collection.objects.link(sun)
s = bpy.context.scene
s.render.engine = "CYCLES"; s.cycles.device = "CPU"; s.cycles.samples = 16; s.cycles.use_denoising = True
s.render.resolution_x, s.render.resolution_y = 1280, 640
cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam")); s.collection.objects.link(cam); s.camera = cam
cam.data.lens = 24
cam.location = (15, -45, 9)
cam.rotation_euler = (Vector((20, 10, 8)) - cam.location).to_track_quat("-Z", "Y").to_euler()
s.render.filepath = out
bpy.ops.render.render(write_still=True)
