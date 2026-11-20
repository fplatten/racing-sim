"""Trackside props (glTF) for the game.

    blender --background --factory-startup --python blender/make_props.py

Conventions (Blender frame, exported to glTF Y-up):
  grandstand: one 40 m module, X along the track, the seating faces -Y (towards the track),
              front wall base at y = 0, z = 0.
  others:     centred on the origin, z = 0 ground.
Object name prefixes tell the game which material to apply:
  Concrete_, Crowd_, Steel_, Roof_, Fascia_, Glass_, Paint_, Screen_, Lamp_, Canvas_  (trees: make_foliage.py)
"""
import math
import os
import random
import sys

import bmesh
import bpy
from mathutils import Vector

sys.path.insert(0, os.path.dirname(__file__))
from common import out_path, reset_scene, principled, export_glb

random.seed(7)
MATS = {}


def mat(name, color=(0.7, 0.7, 0.7, 1), metallic=0.0, roughness=0.6):
    if name not in MATS:
        MATS[name] = principled(name, color, metallic, roughness)
    return MATS[name]


def clear():
    for o in list(bpy.context.scene.objects):
        bpy.data.objects.remove(o, do_unlink=True)


def mesh_obj(name, verts, faces, material, uvs=None, smooth=False):
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.update()
    obj = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(obj)
    obj.data.materials.append(material)
    if uvs is not None:
        layer = me.uv_layers.new(name="UVMap")
        for poly in me.polygons:
            for li in poly.loop_indices:
                layer.data[li].uv = uvs[me.loops[li].vertex_index]
    for p in me.polygons:
        p.use_smooth = smooth
    return obj


def box(name, center, half, material, uv_scale=0.25):
    cx, cy, cz = center
    hx, hy, hz = half
    verts, faces, uvs = [], [], []
    # 6 faces with own vertices for planar UVs
    dirs = [((1, 0, 0), (0, 1, 0), (0, 0, 1)), ((-1, 0, 0), (0, -1, 0), (0, 0, 1)),
            ((0, 1, 0), (-1, 0, 0), (0, 0, 1)), ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
            ((0, 0, 1), (1, 0, 0), (0, 1, 0)), ((0, 0, -1), (1, 0, 0), (0, -1, 0))]
    for n, u, v in dirs:
        n, u, v = Vector(n), Vector(u), Vector(v)
        size = Vector((hx, hy, hz))
        nc = Vector((n.x * hx, n.y * hy, n.z * hz))
        hu = abs(u.x) * hx + abs(u.y) * hy + abs(u.z) * hz
        hv = abs(v.x) * hx + abs(v.y) * hy + abs(v.z) * hz
        base = len(verts)
        for su, sv in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            p = Vector(center) + nc + u * (su * hu) + v * (sv * hv)
            verts.append(tuple(p))
            uvs.append(((su * hu) * uv_scale + (p.x + p.y) * uv_scale * 0, (sv * hv + hv) * uv_scale))
        faces.append((base, base + 1, base + 2, base + 3))
    return mesh_obj(name, verts, faces, material, uvs)


def quad(name, a, b, c, d, material, uv=((0, 0), (1, 0), (1, 1), (0, 1))):
    return mesh_obj(name, [a, b, c, d], [(0, 1, 2, 3)], material, list(uv))


def cylinder(name, center, radius, depth, material, verts=16, rot=None):
    bpy.ops.mesh.primitive_cylinder_add(vertices=verts, radius=radius, depth=depth, location=center)
    o = bpy.context.active_object
    o.name = name
    if rot:
        o.rotation_euler = rot
        bpy.ops.object.transform_apply(rotation=True)
    o.data.materials.append(material)
    bpy.ops.object.shade_smooth()
    return o


def join(objs, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    objs[0].name = name
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return objs[0]


# ============================================================================ grandstand
def grandstand():
    clear()
    length = 40.0
    rows, tread, rise, base_h = 16, 0.8, 0.48, 3.2
    depth = rows * tread
    top_h = base_h + rows * rise
    roof_h = top_h + 4.8
    concrete = mat("Concrete", (0.6, 0.6, 0.58, 1), 0, 0.8)
    crowd = mat("Crowd", (1, 1, 1, 1), 0, 0.9)
    steel = mat("Steel", (0.55, 0.56, 0.58, 1), 0.9, 0.35)
    roof = mat("Roof", (0.22, 0.23, 0.25, 1), 0.3, 0.6)
    fascia = mat("Fascia", (0.06, 0.18, 0.55, 1), 0.2, 0.35)
    lamp = mat("Lamp", (1.0, 0.95, 0.85, 1), 0, 0.2)
    objs = []

    # front wall + advertising band
    objs.append(box("Concrete_FrontWall", (length / 2, -0.3, base_h / 2), (length / 2, 0.3, base_h / 2), concrete))
    objs.append(box("Fascia_FrontBand", (length / 2, -0.62, base_h - 0.55), (length / 2, 0.02, 0.45), fascia))
    # seating: real stepped rows - tread + riser in concrete, a row of seated spectators per step
    row_tile = 20.0                                                 # crowd_row.png = 40 seats over 20 m
    for r in range(rows):
        y0, y1 = r * tread, (r + 1) * tread
        z = base_h + r * rise
        objs.append(quad("Concrete_Riser", (0, y0, z), (length, y0, z), (length, y0, z + rise), (0, y0, z + rise),
                         concrete, ((0, 0), (length / 4, 0), (length / 4, 0.12), (0, 0.12))))
        objs.append(quad("Concrete_Tread", (0, y0, z + rise), (length, y0, z + rise), (length, y1, z + rise),
                         (0, y1, z + rise), concrete, ((0, 0.2), (length / 4, 0.2), (length / 4, 0.4), (0, 0.4))))
        yp = y0 + tread * 0.55
        zp = z + rise
        off = (r * 0.37) % 1.0                                      # stagger the pattern per row
        objs.append(quad("Crowd_Row", (0, yp, zp), (length, yp, zp), (length, yp, zp + 1.0), (0, yp, zp + 1.0),
                         crowd, ((off, 0), (off + length / row_tile, 0), (off + length / row_tile, 1), (off, 1))))
    # stepped concrete underneath (side silhouettes) + back wall
    objs.append(quad("Concrete_Back", (0, depth, 0), (length, depth, 0), (length, depth, roof_h), (0, depth, roof_h),
                     concrete, ((0, 0), (10, 0), (10, 4), (0, 4))))
    for x in (0.0, length):
        objs.append(mesh_obj("Concrete_Side", [(x, 0, 0), (x, depth, 0), (x, depth, top_h), (x, 0, base_h)],
                             [(0, 1, 2, 3)], concrete, [(0, 0), (3, 0), (3, 3), (0, 1)]))
    # roof: columns at the back, cantilever trusses, roof sheet, fascia, lamps
    roof_front_y = -2.0
    for i in range(5):
        x = i * 10.0 if i < 4 else length - 0.2
        x = max(0.2, min(length - 0.2, x))
        objs.append(box("Steel_Column", (x, depth + 0.3, roof_h / 2), (0.25, 0.25, roof_h / 2), steel))
        # cantilever truss: top chord, bottom chord, diagonals
        top0, top1 = Vector((x, depth + 0.3, roof_h + 1.2)), Vector((x, roof_front_y, roof_h + 0.3))
        bot0, bot1 = Vector((x, depth + 0.3, roof_h - 0.6)), Vector((x, roof_front_y, roof_h))
        for a, b in ((top0, top1), (bot0, bot1)):
            objs.append(beam("Steel_Chord", a, b, 0.12, steel))
        for k in range(6):
            t0, t1 = k / 6, (k + 1) / 6
            a = bot0.lerp(bot1, t0)
            b = top0.lerp(top1, t1)
            objs.append(beam("Steel_Diag", a, b, 0.06, steel))
    objs.append(quad("Roof_Sheet", (0, depth + 0.6, roof_h + 1.25), (length, depth + 0.6, roof_h + 1.25),
                     (length, roof_front_y, roof_h + 0.35), (0, roof_front_y, roof_h + 0.35), roof,
                     ((0, 0), (8, 0), (8, 3), (0, 3))))
    objs.append(box("Fascia_Roof", (length / 2, roof_front_y - 0.05, roof_h + 0.1), (length / 2, 0.06, 0.35), fascia))
    for i in range(8):
        x = 2.5 + i * 5.0
        objs.append(box("Lamp_Roof", (x, roof_front_y + 0.3, roof_h - 0.15), (0.35, 0.2, 0.08), lamp))
    # floodlight masts on the roof (like the reference)
    for x in (5.0, 25.0):
        objs.append(cylinder("Steel_Mast", (x, depth - 1.0, roof_h + 5.0), 0.18, 8.0, steel, 10))
        objs.append(box("Steel_MastHead", (x, depth - 1.0, roof_h + 9.2), (0.9, 0.15, 0.5), steel))
        for k in range(4):
            objs.append(box("Lamp_Flood", (x - 0.6 + k * 0.4, depth - 1.18, roof_h + 9.2), (0.16, 0.03, 0.16), lamp))
    # front railing
    objs.append(box("Steel_Rail", (length / 2, 0.15, base_h + 1.0), (length / 2, 0.03, 0.03), steel))
    for i in range(21):
        objs.append(box("Steel_RailPost", (i * 2.0, 0.15, base_h + 0.5), (0.03, 0.03, 0.5), steel))
    merge_by_prefix(objs)
    export_glb(out_path("Models/Props", "grandstand.glb"))


def beam(name, a, b, r, material):
    a, b = Vector(a), Vector(b)
    d = b - a
    mid = (a + b) / 2
    bpy.ops.mesh.primitive_cube_add(size=1, location=mid)
    o = bpy.context.active_object
    o.name = name
    o.scale = (r, r, d.length)
    o.rotation_euler = d.to_track_quat("Z", "Y").to_euler()
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    o.data.materials.append(material)
    return o


def bake_transforms():
    for o in bpy.context.scene.objects:
        bpy.ops.object.select_all(action="DESELECT")
        o.select_set(True)
        bpy.context.view_layer.objects.active = o
        bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def merge_by_prefix(objs):
    groups = {}
    for o in objs:
        groups.setdefault(o.name.split("_")[0], []).append(o)
    for prefix, items in groups.items():
        if len(items) > 1:
            join(items, prefix + "_Merged")
        else:
            items[0].name = prefix + "_Merged"
    bake_transforms()


# ============================================================================ timing tower
def timing_tower():
    clear()
    concrete = mat("Concrete", (0.6, 0.6, 0.58, 1), 0, 0.8)
    glass = mat("Glass", (0.05, 0.08, 0.1, 1), 0.9, 0.05)
    paint = mat("Paint", (0.92, 0.92, 0.92, 1), 0.0, 0.4)
    screen = mat("Screen", (0.2, 0.3, 0.5, 1), 0, 0.3)
    steel = mat("Steel", (0.55, 0.56, 0.58, 1), 0.9, 0.35)
    objs = [cylinder("Concrete_Core", (0, 0, 9), 3.0, 18, concrete, 24)]
    z = 18.0
    for level, r in enumerate((6.5, 7.2, 7.8)):
        objs.append(cylinder("Glass_Floor", (0, 0, z + 1.6), r, 3.2, glass, 32))
        objs.append(cylinder("Paint_Deck", (0, 0, z + 3.35), r + 0.5, 0.3, paint, 32))
        for k in range(16):
            a = 2 * math.pi * k / 16
            objs.append(box("Steel_Mullion", (math.cos(a) * r, math.sin(a) * r, z + 1.6), (0.06, 0.06, 1.6), steel))
        z += 3.5
    objs.append(cylinder("Paint_Roof", (0, 0, z + 0.6), 8.8, 0.5, paint, 32))
    objs.append(cylinder("Steel_Antenna", (0, 0, z + 4.0), 0.12, 6.0, steel, 8))
    # big screen facing the main straight (-Y)
    objs.append(box("Paint_ScreenFrame", (0, -8.6, 13.0), (5.2, 0.35, 3.2), paint))
    objs.append(box("Screen_Panel", (0, -8.98, 13.0), (4.8, 0.03, 2.8), screen))
    objs.append(box("Steel_ScreenLeg", (-3.5, -8.5, 5.0), (0.25, 0.25, 5.0), steel))
    objs.append(box("Steel_ScreenLeg", (3.5, -8.5, 5.0), (0.25, 0.25, 5.0), steel))
    merge_by_prefix(objs)
    export_glb(out_path("Models/Props", "timing_tower.glb"))


# ============================================================================ tent
def tent():
    clear()
    canvas = mat("Canvas", (0.95, 0.95, 0.93, 1), 0, 0.7)
    steel = mat("Steel", (0.55, 0.56, 0.58, 1), 0.9, 0.35)
    objs = []
    w, d, h, peak = 12.0, 8.0, 2.6, 4.2
    for i in range(3):                                            # three peaked bays
        x0 = -w / 2 + i * w / 3
        x1 = x0 + w / 3
        cx = (x0 + x1) / 2
        verts = [(x0, -d / 2, h), (x1, -d / 2, h), (x1, d / 2, h), (x0, d / 2, h), (cx, 0, peak)]
        faces = [(0, 1, 4), (1, 2, 4), (2, 3, 4), (3, 0, 4)]
        objs.append(mesh_obj("Canvas_Roof", verts, faces, canvas, [(0, 0), (1, 0), (1, 1), (0, 1), (0.5, 0.5)]))
    objs.append(quad("Canvas_Back", (-w / 2, d / 2, 0), (w / 2, d / 2, 0), (w / 2, d / 2, h), (-w / 2, d / 2, h), canvas))
    objs.append(quad("Canvas_SideL", (-w / 2, -d / 2, 0), (-w / 2, d / 2, 0), (-w / 2, d / 2, h), (-w / 2, -d / 2, h), canvas))
    objs.append(quad("Canvas_SideR", (w / 2, -d / 2, 0), (w / 2, d / 2, 0), (w / 2, d / 2, h), (w / 2, -d / 2, h), canvas))
    for x in (-w / 2, -w / 6, w / 6, w / 2):
        objs.append(cylinder("Steel_Pole", (x, -d / 2, h / 2), 0.06, h, steel, 8))
    merge_by_prefix(objs)
    export_glb(out_path("Models/Props", "tent.glb"))


# ============================================================================ light tower
def light_tower():
    clear()
    steel = mat("Steel", (0.55, 0.56, 0.58, 1), 0.9, 0.35)
    lamp = mat("Lamp", (1.0, 0.95, 0.85, 1), 0, 0.2)
    objs = [cylinder("Steel_Pole", (0, 0, 12.5), 0.3, 25, steel, 12),
            box("Steel_Head", (0, 0, 25.5), (2.0, 0.25, 1.1), steel)]
    for r in range(2):
        for c in range(5):
            objs.append(box("Lamp_Flood", (-1.6 + c * 0.8, -0.28, 25.0 + r * 1.0), (0.3, 0.03, 0.35), lamp))
    merge_by_prefix(objs)
    export_glb(out_path("Models/Props", "light_tower.glb"))


if __name__ == "__main__":
    reset_scene()
    grandstand()
    timing_tower()
    # trees and shrubs: blender/make_foliage.py
    tent()
    light_tower()
    print("PROPS DONE")
