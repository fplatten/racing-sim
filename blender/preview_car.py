"""Renders preview images of the exported car: blender --background --python blender/preview_car.py -- out_dir"""
import math, os, sys
import bpy, addon_utils
sys.path.insert(0, os.path.dirname(__file__))
from common import RES, reset_scene, args_after_dashes

out_dir = args_after_dashes()[0]
reset_scene()
addon_utils.enable("cycles", default_set=True)
bpy.ops.import_scene.gltf(filepath=os.path.join(RES, "Models/Car/indycar.glb"))
for name, x, y in (("wheel_front.glb", 0.86, -1.65), ("wheel_front.glb", -0.86, -1.65),
                   ("wheel_rear.glb", 0.82, 1.35), ("wheel_rear.glb", -0.82, 1.35)):
    before = set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=os.path.join(RES, "Models/Car", name))
    for o in set(bpy.data.objects) - before:
        if o.parent is None:
            o.location = (x, y, 0.33)
            if x < 0:
                o.rotation_euler = (0, 0, math.pi)
# livery stand-in colors
for m in bpy.data.materials:
    if m.name.startswith("Livery") and m.node_tree:
        m.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.03, 0.12, 0.6, 1)
bpy.ops.mesh.primitive_plane_add(size=40)
floor = bpy.context.active_object
fm = bpy.data.materials.new("floor"); fm.use_nodes = True
fm.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.05, 0.05, 0.055, 1)
fm.node_tree.nodes["Principled BSDF"].inputs["Roughness"].default_value = 0.35
floor.data.materials.append(fm)
world = bpy.data.worlds.new("w"); bpy.context.scene.world = world; world.use_nodes = True
world.node_tree.nodes["Background"].inputs[0].default_value = (0.6, 0.6, 0.65, 1)
world.node_tree.nodes["Background"].inputs[1].default_value = 0.6
sun = bpy.data.objects.new("sun", bpy.data.lights.new("sun", "SUN")); sun.data.energy = 3.5
sun.rotation_euler = (math.radians(50), 0, math.radians(30)); bpy.context.scene.collection.objects.link(sun)
scene = bpy.context.scene
scene.render.engine = "CYCLES"; scene.cycles.device = "CPU"; scene.cycles.samples = 24; scene.cycles.use_denoising = True
scene.render.resolution_x, scene.render.resolution_y = 960, 540
cam = bpy.data.objects.new("cam", bpy.data.cameras.new("cam")); scene.collection.objects.link(cam); scene.camera = cam
cam.data.lens = 50
views = {"three_quarter": ((-4.2, -5.2, 1.8), (0, -0.2, 0.35)), "side": ((-8.5, 0.0, 0.6), (0, 0, 0.4)),
         "top": ((0, 0.0, 10.0), (0, 0, 0)), "front": ((0, -8.5, 0.7), (0, 0, 0.45))}
from mathutils import Vector
for name, (loc, target) in views.items():
    cam.location = loc
    d = Vector(target) - Vector(loc)
    cam.rotation_euler = d.to_track_quat("-Z", "Y").to_euler()
    scene.render.filepath = os.path.join(out_dir, f"car_{name}.png")
    bpy.ops.render.render(write_still=True)
print("PREVIEW DONE")
