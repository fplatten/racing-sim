"""Trackside props (glTF) for the game.

    blender --background --factory-startup --python blender/make_props.py

Conventions (Blender frame, exported to glTF Y-up):
  grandstand: one 40 m module, X along the track, the seating faces -Y (toward the track),
              front wall base at y = 0, z = 0.
  others:     centerd on the origin, z = 0 ground.
Object name prefixes tell the game which material to apply:
  Concrete_, Crowd_, Steel_, Alu_, Banner_, Roof_, Fascia_, Glass_, Paint_, Screen_, Lamp_, Canvas_
  (trees: make_foliage.py)
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
    """Open aluminum bleachers on a steel frame (like the big oval-track grandstands): a concrete
    front wall with a sponsor banner, 16 rows of footboards and seat planks with open risers, raker
    beams on columns with cross-bracing underneath, an open back with a guard rail, and the
    cantilevered roof. Sky and sunlight show through the frame, between the rows and under the
    roof, so from behind (the back straight) it is not a solid block."""
    clear()
    length = 40.0
    rows, tread, rise, base_h = 16, 0.8, 0.48, 3.2
    depth = rows * tread
    top_h = base_h + rows * rise
    roof_h = top_h + 4.8
    concrete = mat("Concrete", (0.6, 0.6, 0.58, 1), 0, 0.8)
    crowd = mat("Crowd", (1, 1, 1, 1), 0, 0.9)
    steel = mat("Steel", (0.55, 0.56, 0.58, 1), 0.9, 0.35)
    alu = mat("Alu", (0.75, 0.76, 0.78, 1), 0.85, 0.45)
    roof = mat("Roof", (0.22, 0.23, 0.25, 1), 0.3, 0.6)
    fascia = mat("Fascia", (0.06, 0.18, 0.55, 1), 0.2, 0.35)
    banner = mat("Banner", (1, 1, 1, 1), 0, 0.5)
    lamp = mat("Lamp", (1.0, 0.95, 0.85, 1), 0, 0.2)
    objs = []

    # front wall with a sponsor banner board (BannerTexture paints it in the game)
    objs.append(box("Concrete_FrontWall", (length / 2, -0.3, base_h / 2), (length / 2, 0.3, base_h / 2), concrete))
    objs.append(quad("Banner_Front", (0, -0.615, base_h - 1.05), (length, -0.615, base_h - 1.05),
                     (length, -0.615, base_h - 0.1), (0, -0.615, base_h - 0.1), banner))
    objs.append(box("Concrete_Walkway", (length / 2, 0.3, base_h - 0.05), (length / 2, 0.3, 0.05), concrete))

    # bleachers: footboard + seat plank per row, open risers; one crowd strip per row drawn from
    # four atlas variants (crowd_atlas.png: 4 strips, 40 seats = 20 m each)
    row_tile = 20.0
    half_texel = 0.5 / 512
    for r in range(rows):
        y0 = r * tread
        z = base_h + r * rise
        zp = z + rise
        objs.append(box("Alu_Foot", (length / 2, y0 + 0.17, zp - 0.02), (length / 2, 0.16, 0.02), alu))
        objs.append(box("Alu_Seat", (length / 2, y0 + 0.60, zp + 0.42), (length / 2, 0.15, 0.02), alu))
        objs.append(box("Alu_Kick", (length / 2, y0 + 0.02, zp - 0.10), (length / 2, 0.01, 0.08), alu))
        yp = y0 + tread * 0.55
        off = (r * 0.37) % 1.0                                      # stagger the pattern per row
        k = random.randrange(4)
        vb, vt = 1 - (k + 1) / 4 + half_texel, 1 - k / 4 - half_texel
        objs.append(quad("Crowd_Row", (0, yp, zp), (length, yp, zp), (length, yp, zp + 1.0), (0, yp, zp + 1.0),
                         crowd, ((off, vb), (off + length / row_tile, vb), (off + length / row_tile, vt), (off, vt))))

    # steel frame under the deck: raker beams on columns, cross-braced, tied together along the stand
    col_y = (depth * 0.34, depth * 0.67, depth - 0.1)
    def raker_z(y):
        return base_h - 0.15 + (top_h - base_h) * y / depth
    for i in range(8):
        x = 2.5 + i * 5.0
        objs.append(beam("Steel_Raker", (x, 0.0, base_h - 0.15), (x, depth, top_h - 0.15), 0.11, steel))
        prev = None
        for cy in col_y:
            objs.append(beam("Steel_Col", (x, cy, 0.0), (x, cy, raker_z(cy)), 0.10, steel))
            if prev is not None and i % 2 == 0:                    # X-bracing in alternate frames
                objs.append(beam("Steel_Brace", (x, prev, 0.3), (x, cy, raker_z(cy) - 0.3), 0.04, steel))
                objs.append(beam("Steel_Brace", (x, cy, 0.3), (x, prev, raker_z(prev) - 0.3), 0.04, steel))
            prev = cy
    for cy in col_y:
        objs.append(beam("Steel_Tie", (0, cy, raker_z(cy) - 0.12), (length, cy, raker_z(cy) - 0.12), 0.07, steel))
        objs.append(beam("Steel_Tie", (0, cy, 1.2), (length, cy, 1.2), 0.05, steel))
    for i in range(4):                                             # longitudinal bracing bays
        x0, x1 = i * 10.0 + 2.5, i * 10.0 + 7.5
        cy = col_y[-1]
        objs.append(beam("Steel_Brace", (x0, cy, 1.2), (x1, cy, raker_z(cy) - 0.2), 0.04, steel))
    # open back: guard rail along the top row
    objs.append(box("Steel_BackRail", (length / 2, depth + 0.05, top_h + 1.1), (length / 2, 0.03, 0.03), steel))
    objs.append(box("Steel_BackRail", (length / 2, depth + 0.05, top_h + 0.6), (length / 2, 0.02, 0.02), steel))
    for i in range(21):
        objs.append(box("Steel_RailPost", (i * 2.0, depth + 0.05, top_h + 0.55), (0.03, 0.03, 0.55), steel))

    # roof: columns at the back, cantilever trusses, purlins, roof sheet, fascia, lamps
    roof_front_y = -2.0
    for i in range(5):
        x = i * 10.0 if i < 4 else length - 0.2
        x = max(0.2, min(length - 0.2, x))
        objs.append(box("Steel_Column", (x, depth + 0.3, roof_h / 2), (0.25, 0.25, roof_h / 2), steel))
        top0, top1 = Vector((x, depth + 0.3, roof_h + 1.2)), Vector((x, roof_front_y, roof_h + 0.3))
        bot0, bot1 = Vector((x, depth + 0.3, roof_h - 0.6)), Vector((x, roof_front_y, roof_h))
        for a, b in ((top0, top1), (bot0, bot1)):
            objs.append(beam("Steel_Chord", a, b, 0.12, steel))
        for k in range(6):
            t0, t1 = k / 6, (k + 1) / 6
            objs.append(beam("Steel_Diag", bot0.lerp(bot1, t0), top0.lerp(top1, t1), 0.06, steel))
    for k in range(7):                                             # purlins under the roof sheet
        t = (k + 0.5) / 7
        y = (depth + 0.6) + (roof_front_y - (depth + 0.6)) * t
        zr = (roof_h + 1.25) + ((roof_h + 0.35) - (roof_h + 1.25)) * t - 0.12
        objs.append(beam("Steel_Purlin", (0, y, zr), (length, y, zr), 0.06, steel))
    objs.append(quad("Roof_Sheet", (0, depth + 0.6, roof_h + 1.25), (length, depth + 0.6, roof_h + 1.25),
                     (length, roof_front_y, roof_h + 0.35), (0, roof_front_y, roof_h + 0.35), roof,
                     ((0, 0), (8, 0), (8, 3), (0, 3))))
    objs.append(box("Fascia_Roof", (length / 2, roof_front_y - 0.05, roof_h + 0.1), (length / 2, 0.06, 0.35), fascia))
    for i in range(8):
        x = 2.5 + i * 5.0
        objs.append(box("Lamp_Roof", (x, roof_front_y + 0.3, roof_h - 0.15), (0.35, 0.2, 0.08), lamp))
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
