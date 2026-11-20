"""Builds the 1990s-style Indy car (modelled on a 1990s North American open-wheel race car)
and exports glTF binaries for the game.

    blender --background --factory-startup --python blender/make_car.py

Blender frame: car faces -Y, +X is the car's LEFT, Z up, ground at z = 0 (glTF export maps this
to the game's +Z forward / +X left / +Y up frame).

Livery UV layout (shared with com.racingsim.vehicle.LiveryPainter):
  left side   u = (y - Y_MIN) / L      v = 0.75 + 0.25 * z / Z_MAX
  right side  u = (Y_MAX - y) / L      v = 0.50 + 0.25 * z / Z_MAX
  top         u = (y - Y_MIN) / L      v = 0.10 + 0.40 * (X_MAX - x) / (2 * X_MAX)
  bottom      u = (y - Y_MIN) / L      v = 0.10 * (x + X_MAX) / (2 * X_MAX)
"""
import math
import os
import sys

import bmesh
import bpy
from mathutils import Vector, Matrix

sys.path.insert(0, os.path.dirname(__file__))
from common import out_path, reset_scene, principled, export_glb

Y_MIN, Y_MAX, Z_MAX, X_MAX = -2.5, 2.6, 1.25, 1.0
L = Y_MAX - Y_MIN

FRONT_AXLE_Y, REAR_AXLE_Y = -1.65, 1.35
FRONT_TRACK, REAR_TRACK = 0.86, 0.82
WHEEL_R = 0.33

MATS = {}


def mat(name):
    if name not in MATS:
        presets = {
            "Livery": ((0.9, 0.9, 0.9, 1), 0.0, 0.25),
            "Carbon": ((0.03, 0.03, 0.035, 1), 0.0, 0.35),
            "Black": ((0.01, 0.01, 0.01, 1), 0.0, 0.6),
            "Suspension": ((0.04, 0.04, 0.045, 1), 0.3, 0.35),
            "Helmet": ((0.9, 0.9, 0.9, 1), 0.0, 0.15),
            "Visor": ((0.02, 0.02, 0.03, 1), 0.6, 0.05),
            "Light": ((1.0, 0.05, 0.03, 1), 0.0, 0.3),
            "Tire": ((0.02, 0.02, 0.02, 1), 0.0, 0.8),
            "Rim": ((0.05, 0.05, 0.055, 1), 0.9, 0.3),
            "Hub": ((0.75, 0.6, 0.2, 1), 1.0, 0.3),
        }
        c, m, r = presets[name]
        MATS[name] = principled(name, c, m, r)
    return MATS[name]


# ============================================================================ mesh helpers
def make_object(name, verts, faces, material, smooth_faces=None, recalc=True):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([tuple(v) for v in verts], [], faces)
    mesh.update()
    if recalc:
        # consistent outward normals (needed for lighting and the livery side classification)
        bm = bmesh.new()
        bm.from_mesh(mesh)
        bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
        bm.to_mesh(mesh)
        bm.free()
        mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.scene.collection.objects.link(obj)
    mesh.materials.append(mat(material))
    for i, p in enumerate(mesh.polygons):
        p.use_smooth = smooth_faces[i] if smooth_faces is not None else True
    return obj


def catmull(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)


def interp_stations(stations, per_segment):
    """Catmull-Rom interpolation of station parameter tuples."""
    out = []
    n = len(stations)
    for i in range(n - 1):
        p0 = stations[max(i - 1, 0)]
        p1, p2 = stations[i], stations[i + 1]
        p3 = stations[min(i + 2, n - 1)]
        for k in range(per_segment):
            t = k / per_segment
            out.append(tuple(catmull(a, b, c, d, t) for a, b, c, d in zip(p0, p1, p2, p3)))
    out.append(stations[-1])
    return out


def superellipse(theta, n):
    c, s = math.cos(theta), math.sin(theta)
    return (math.copysign(abs(c) ** (2.0 / n), c), math.copysign(abs(s) ** (2.0 / n), s))


def loft(name, stations, material, ring=48, per_segment=8, cap_start=True, cap_end=True):
    """stations: (y, cx, cz, half_width, height_top, height_bottom, exponent)."""
    rings = interp_stations(stations, per_segment)
    verts, faces, smooth = [], [], []
    for (y, cx, cz, hw, ht, hb, n) in rings:
        for k in range(ring):
            th = 2 * math.pi * k / ring
            ex, ez = superellipse(th, max(n, 1.2))
            verts.append((cx + hw * ex, y, cz + (ht if ez >= 0 else hb) * ez))
    nr = len(rings)
    for i in range(nr - 1):
        for k in range(ring):
            a = i * ring + k
            b = i * ring + (k + 1) % ring
            faces.append((a, b, b + ring, a + ring))
            smooth.append(True)
    if cap_start:
        verts.append((rings[0][1], rings[0][0], rings[0][2]))
        c = len(verts) - 1
        for k in range(ring):
            faces.append((c, (k + 1) % ring, k))
            smooth.append(False)
    if cap_end:
        verts.append((rings[-1][1], rings[-1][0], rings[-1][2]))
        c = len(verts) - 1
        base = (nr - 1) * ring
        for k in range(ring):
            faces.append((c, base + k, base + (k + 1) % ring))
            smooth.append(False)
    return make_object(name, verts, faces, material, smooth)


def box(name, center, size, material, rot=None):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = size
    if rot:
        obj.rotation_euler = rot
    bpy.ops.object.transform_apply(location=False, rotation=True, scale=True)
    obj.data.materials.append(mat(material))
    return obj


def bevel(obj, width, segments=2):
    mod = obj.modifiers.new("bevel", "BEVEL")
    mod.width = width
    mod.segments = segments
    mod.limit_method = "ANGLE"
    apply_modifiers(obj)


def apply_modifiers(obj):
    with bpy.context.temp_override(object=obj, active_object=obj, selected_objects=[obj]):
        for m in list(obj.modifiers):
            bpy.ops.object.modifier_apply(modifier=m.name)


def airfoil_points(chord, thickness, camber, n=14):
    """NACA-like section in (forward, up) coordinates; leading edge at 0, trailing at chord."""
    upper, lower = [], []
    for i in range(n + 1):
        x = (1 - math.cos(math.pi * i / n)) / 2
        yt = 5 * thickness * (0.2969 * math.sqrt(x) - 0.126 * x - 0.3516 * x ** 2 + 0.2843 * x ** 3 - 0.1036 * x ** 4)
        yc = camber * 4 * x * (1 - x)
        upper.append((x * chord, yc + yt * chord))
        lower.append((x * chord, yc - yt * chord))
    return upper + lower[::-1][1:-1]


def wing(name, span_half, chord, y_le, z, angle_deg, thickness, camber, material, sections=12):
    """Wing element along X; leading edge at y_le (front = -Y); angle: positive = trailing edge up."""
    pts = airfoil_points(chord, thickness, camber)
    a = math.radians(angle_deg)
    ring = len(pts)
    verts, faces, smooth = [], [], []
    for s in range(sections + 1):
        x = -span_half + 2 * span_half * s / sections
        for (fx, fz) in pts:
            # rotate around leading edge (trailing edge up for downforce: inverted airfoil)
            ry = fx * math.cos(a)
            rz = -fz * 1.0 + fx * math.sin(a)
            verts.append((x, y_le + ry, z + rz))
    for s in range(sections):
        for k in range(ring):
            p = s * ring + k
            q = s * ring + (k + 1) % ring
            faces.append((p, q, q + ring, p + ring))
            smooth.append(True)
    for side in (0, sections):
        verts.append((-span_half + 2 * span_half * side / sections, y_le + chord / 2, z))
        c = len(verts) - 1
        base = side * ring
        for k in range(ring):
            faces.append((c, base + k, base + (k + 1) % ring) if side else (c, base + (k + 1) % ring, base + k))
            smooth.append(False)
    return make_object(name, verts, faces, material, smooth)


def tube(name, p0, p1, radius_a, radius_b, material, ring=10):
    """Elliptical aero tube (wishbone leg) from p0 to p1."""
    p0, p1 = Vector(p0), Vector(p1)
    d = (p1 - p0)
    length = d.length
    axis = d.normalized()
    up = Vector((0, 0, 1)) if abs(axis.z) < 0.9 else Vector((1, 0, 0))
    side = axis.cross(up).normalized()
    up2 = side.cross(axis).normalized()
    verts, faces = [], []
    for end, p in enumerate((p0, p1)):
        for k in range(ring):
            th = 2 * math.pi * k / ring
            v = p + side * (math.cos(th) * radius_a) + up2 * (math.sin(th) * radius_b)
            verts.append(tuple(v))
    for k in range(ring):
        faces.append((k, (k + 1) % ring, ring + (k + 1) % ring, ring + k))
    faces.append(tuple(range(ring - 1, -1, -1)))
    faces.append(tuple(range(ring, 2 * ring)))
    return make_object(name, verts, faces, material, [True] * ring + [False, False])


def lathe(name, profile, material, segments=64, uv_band=True):
    """Revolve (radius, x) profile points around the X axis. UV: u = angle, v = profile parameter."""
    verts, faces, uvs = [], [], []
    n = len(profile)
    # cumulative length for v
    acc = [0.0]
    for i in range(1, n):
        acc.append(acc[-1] + math.dist(profile[i - 1], profile[i]))
    total = acc[-1]
    for s in range(segments + 1):
        th = 2 * math.pi * s / segments
        for (r, x) in profile:
            verts.append((x, r * math.cos(th), r * math.sin(th)))
    for s in range(segments):
        for i in range(n - 1):
            a = s * n + i
            b = (s + 1) * n + i
            faces.append((a, a + 1, b + 1, b))
    obj = make_object(name, verts, faces, material, recalc=False)
    uv = obj.data.uv_layers.new(name="UVMap")
    for poly in obj.data.polygons:
        for li in poly.loop_indices:
            vi = obj.data.loops[li].vertex_index
            s, i = divmod(vi, n)
            uv.data[li].uv = (s / segments, acc[i] / total)
    return obj


# ============================================================================ livery UVs
def livery_uv(obj):
    mesh = obj.data
    uv = mesh.uv_layers.new(name="UVMap") if not mesh.uv_layers else mesh.uv_layers[0]
    for poly in mesh.polygons:
        nx, ny, nz = poly.normal
        ax, ay, az = abs(nx), abs(ny), abs(nz)
        if ax >= max(ay, az) * 0.95:
            kind = "L" if nx > 0 else "R"
        elif nz >= -0.25:
            kind = "T"
        else:
            kind = "B"
        for li in poly.loop_indices:
            x, y, z = mesh.vertices[mesh.loops[li].vertex_index].co
            if kind == "L":
                u, v = (y - Y_MIN) / L, 0.75 + 0.25 * z / Z_MAX
            elif kind == "R":
                u, v = (Y_MAX - y) / L, 0.50 + 0.25 * z / Z_MAX
            elif kind == "T":
                u, v = (y - Y_MIN) / L, 0.10 + 0.40 * (X_MAX - x) / (2 * X_MAX)
            else:
                u, v = (y - Y_MIN) / L, 0.10 * (x + X_MAX) / (2 * X_MAX)
            uv.data[li].uv = (min(max(u, 0.0), 1.0), min(max(v, 0.0), 1.0))


def simple_uv(obj, scale=4.0):
    mesh = obj.data
    uv = mesh.uv_layers.new(name="UVMap") if not mesh.uv_layers else mesh.uv_layers[0]
    for poly in mesh.polygons:
        nx, ny, nz = poly.normal
        for li in poly.loop_indices:
            x, y, z = mesh.vertices[mesh.loops[li].vertex_index].co
            if abs(nz) > max(abs(nx), abs(ny)):
                uv.data[li].uv = (x * scale, y * scale)
            elif abs(nx) > abs(ny):
                uv.data[li].uv = (y * scale, z * scale)
            else:
                uv.data[li].uv = (x * scale, z * scale)


# ============================================================================ car body
def build_body():
    parts = []
    # --- monocoque: nose -> cockpit -> engine cover -> gearbox fairing
    tub = loft("Livery_Tub", [
        (-2.44, 0, 0.205, 0.040, 0.040, 0.040, 2.2),
        (-2.33, 0, 0.215, 0.085, 0.075, 0.065, 2.2),
        (-2.05, 0, 0.245, 0.125, 0.115, 0.095, 2.4),
        (-1.65, 0, 0.290, 0.165, 0.150, 0.130, 2.6),
        (-1.20, 0, 0.335, 0.215, 0.180, 0.175, 3.0),
        (-0.85, 0, 0.365, 0.290, 0.215, 0.215, 3.5),
        (-0.40, 0, 0.385, 0.360, 0.220, 0.240, 4.0),
        (0.00, 0, 0.400, 0.395, 0.235, 0.255, 4.0),
        (0.35, 0, 0.445, 0.375, 0.295, 0.300, 3.5),
        (0.90, 0, 0.440, 0.295, 0.265, 0.290, 3.0),
        (1.40, 0, 0.380, 0.195, 0.200, 0.235, 2.8),
        (1.85, 0, 0.330, 0.120, 0.125, 0.170, 2.5),
        (2.08, 0, 0.310, 0.070, 0.075, 0.110, 2.2),
    ], "Livery", ring=56, per_segment=8)

    # cockpit opening
    bpy.ops.mesh.primitive_uv_sphere_add(segments=48, ring_count=24, location=(0, -0.30, 0.70))
    cutter = bpy.context.active_object
    cutter.scale = (0.255, 0.56, 0.20)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    mod = tub.modifiers.new("cockpit", "BOOLEAN")
    mod.operation = "DIFFERENCE"
    mod.object = cutter
    if hasattr(mod, "solver"):
        mod.solver = "EXACT"
    apply_modifiers(tub)
    bpy.data.objects.remove(cutter, do_unlink=True)
    bm = bmesh.new()
    bm.from_mesh(tub.data)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    bm.to_mesh(tub.data)
    bm.free()
    parts.append(tub)

    # cockpit interior + coaming
    parts.append(loft("Black_Cockpit", [
        (-0.84, 0, 0.42, 0.20, 0.10, 0.12, 3.0),
        (-0.50, 0, 0.45, 0.25, 0.12, 0.14, 3.5),
        (0.10, 0, 0.48, 0.25, 0.12, 0.14, 3.5),
        (0.24, 0, 0.50, 0.18, 0.11, 0.12, 3.0),
    ], "Black", ring=32, per_segment=4))

    # sidepods
    for side, nm in ((1, "Livery_PodL"), (-1, "Livery_PodR")):
        pod = loft(nm, [
            (-0.72, side * 0.565, 0.300, 0.190, 0.150, 0.170, 3.5),
            (-0.55, side * 0.575, 0.305, 0.215, 0.170, 0.175, 3.6),
            (0.00, side * 0.575, 0.305, 0.215, 0.170, 0.175, 3.6),
            (0.60, side * 0.535, 0.295, 0.185, 0.150, 0.170, 3.4),
            (1.05, side * 0.430, 0.270, 0.125, 0.110, 0.150, 3.0),
            (1.40, side * 0.300, 0.250, 0.060, 0.070, 0.120, 2.6),
        ], "Livery", ring=40, per_segment=8)
        parts.append(pod)
        # radiator intake (dark recess)
        parts.append(loft(nm.replace("Livery_", "Black_Intake"), [
            (-0.735, side * 0.565, 0.300, 0.160, 0.120, 0.140, 3.5),
            (-0.70, side * 0.565, 0.300, 0.160, 0.120, 0.140, 3.5),
        ], "Black", ring=32, per_segment=1))

    # airbox / roll hoop above the driver
    parts.append(loft("Livery_Airbox", [
        (-0.08, 0, 0.805, 0.090, 0.085, 0.110, 2.4),
        (0.05, 0, 0.815, 0.120, 0.095, 0.180, 2.6),
        (0.40, 0, 0.770, 0.110, 0.080, 0.220, 2.6),
        (0.85, 0, 0.660, 0.075, 0.055, 0.180, 2.4),
        (1.25, 0, 0.540, 0.045, 0.030, 0.100, 2.2),
    ], "Livery", ring=32, per_segment=6))
    parts.append(loft("Black_AirboxIntake", [
        (-0.09, 0, 0.805, 0.070, 0.065, 0.085, 2.4),
        (-0.075, 0, 0.805, 0.070, 0.065, 0.085, 2.4),
    ], "Black", ring=24, per_segment=1))

    # floor / undertray with diffuser kick-up
    floor = loft("Carbon_Floor", [
        (-1.05, 0, 0.075, 0.35, 0.012, 0.012, 6.0),
        (-0.70, 0, 0.075, 0.78, 0.012, 0.012, 8.0),
        (1.10, 0, 0.075, 0.74, 0.012, 0.012, 8.0),
        (1.55, 0, 0.090, 0.50, 0.012, 0.012, 8.0),
        (2.00, 0, 0.200, 0.42, 0.012, 0.012, 8.0),
    ], "Carbon", ring=24, per_segment=4)
    parts.append(floor)
    for x in (-0.25, 0.0, 0.25):                                 # diffuser strakes
        parts.append(box("Carbon_Strake", (x, 1.75, 0.13), (0.01, 0.5, 0.12), "Carbon"))

    # gearbox / crash structure
    parts.append(loft("Carbon_Gearbox", [
        (1.55, 0, 0.30, 0.13, 0.10, 0.12, 3.0),
        (2.05, 0, 0.30, 0.11, 0.08, 0.10, 3.0),
        (2.32, 0, 0.31, 0.07, 0.05, 0.06, 2.6),
    ], "Carbon", ring=24, per_segment=4))
    parts.append(box("Light_Rear", (0, 2.335, 0.31), (0.07, 0.012, 0.05), "Light"))

    # front wing: main plane + flap + endplates + pylons
    parts.append(wing("Livery_FrontWingMain", 0.97, 0.30, -2.44, 0.095, 4, 0.10, 0.06, "Livery"))
    parts.append(wing("Livery_FrontWingFlap", 0.93, 0.15, -2.17, 0.150, 18, 0.09, 0.05, "Livery"))
    for side in (1, -1):
        ep = box("Livery_FrontEndplate", (side * 0.985, -2.20, 0.135), (0.012, 0.52, 0.22), "Livery")
        bevel(ep, 0.004)
        parts.append(ep)
        parts.append(box("Carbon_FrontPylon", (side * 0.11, -2.27, 0.155), (0.012, 0.18, 0.11), "Carbon"))

    # rear wing: main plane + flap + big endplates + struts
    parts.append(wing("Livery_RearWingMain", 0.50, 0.32, 2.12, 0.900, 10, 0.12, 0.07, "Livery", 8))
    parts.append(wing("Livery_RearWingFlap", 0.50, 0.16, 2.40, 1.000, 32, 0.10, 0.05, "Livery", 8))
    for side in (1, -1):
        ep = box("Livery_RearEndplate", (side * 0.515, 2.28, 0.86), (0.012, 0.62, 0.56), "Livery")
        bevel(ep, 0.005)
        parts.append(ep)
        parts.append(tube("Carbon_WingStrut", (side * 0.08, 2.05, 0.38), (side * 0.10, 2.25, 0.88),
                          0.012, 0.035, "Carbon"))

    # mirrors
    for side in (1, -1):
        parts.append(loft("Livery_Mirror", [
            (-0.62, side * 0.47, 0.585, 0.020, 0.020, 0.020, 2.5),
            (-0.60, side * 0.47, 0.585, 0.055, 0.032, 0.032, 3.0),
            (-0.53, side * 0.47, 0.585, 0.050, 0.030, 0.030, 3.0),
        ], "Livery", ring=20, per_segment=2))
        parts.append(tube("Carbon_MirrorStalk", (side * 0.30, -0.58, 0.55), (side * 0.44, -0.58, 0.58),
                          0.008, 0.012, "Carbon"))

    # driver: helmet + visor + steering wheel
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32, ring_count=16, radius=0.135, location=(0, -0.28, 0.715))
    helmet = bpy.context.active_object
    helmet.name = "Helmet"
    helmet.scale = (0.95, 1.08, 1.0)
    bpy.ops.object.transform_apply(scale=True)
    helmet.data.materials.append(mat("Helmet"))
    bpy.ops.object.shade_smooth()
    parts.append(helmet)
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32, ring_count=16, radius=0.128, location=(0, -0.295, 0.72))
    visor = bpy.context.active_object
    visor.name = "Visor"
    visor.scale = (0.97, 1.08, 0.36)
    bpy.ops.object.transform_apply(scale=True)
    visor.data.materials.append(mat("Visor"))
    bpy.ops.object.shade_smooth()
    parts.append(visor)
    bpy.ops.mesh.primitive_torus_add(major_radius=0.13, minor_radius=0.014, location=(0, -0.62, 0.58),
                                     rotation=(math.radians(70), 0, 0))
    wheel = bpy.context.active_object
    wheel.name = "Black_SteeringWheel"
    wheel.data.materials.append(mat("Black"))
    parts.append(wheel)

    # suspension: double wishbones + push rods (static)
    for side in (1, -1):
        hx_f = side * (FRONT_TRACK - 0.13)
        hx_r = side * (REAR_TRACK - 0.16)
        fy, ry = FRONT_AXLE_Y, REAR_AXLE_Y
        for (a, b) in [((side * 0.15, fy - 0.22, 0.40), (hx_f, fy, 0.43)),
                       ((side * 0.16, fy + 0.20, 0.40), (hx_f, fy, 0.43)),
                       ((side * 0.13, fy - 0.24, 0.19), (hx_f, fy, 0.23)),
                       ((side * 0.15, fy + 0.22, 0.19), (hx_f, fy, 0.23)),
                       ((hx_f, fy + 0.02, 0.24), (side * 0.16, fy + 0.02, 0.50)),
                       ((side * 0.10, fy + 0.10, 0.36), (hx_f, fy + 0.10, 0.36)),        # steering arm
                       ((side * 0.30, ry - 0.30, 0.40), (hx_r, ry, 0.44)),
                       ((side * 0.20, ry + 0.25, 0.40), (hx_r, ry, 0.44)),
                       ((side * 0.30, ry - 0.30, 0.17), (hx_r, ry, 0.21)),
                       ((side * 0.16, ry + 0.30, 0.17), (hx_r, ry, 0.21)),
                       ((hx_r, ry - 0.02, 0.22), (side * 0.18, ry + 0.05, 0.47))]:
            parts.append(tube("Susp_Arm", a, b, 0.012, 0.022, "Suspension"))
        # uprights (hidden mostly inside the rims)
        parts.append(box("Susp_UprightF", (hx_f + side * 0.02, fy, 0.33), (0.05, 0.08, 0.24), "Suspension"))
        parts.append(box("Susp_UprightR", (hx_r + side * 0.02, ry, 0.33), (0.05, 0.08, 0.24), "Suspension"))
    return parts


# ============================================================================ wheels
def build_wheel(prefix, width):
    r, rim_r = WHEEL_R, 0.205
    hw = width / 2
    sh = 0.05                                                     # shoulder radius
    profile = [(rim_r + 0.005, -hw + 0.03), (rim_r + 0.03, -hw + 0.005), (r - 0.07, -hw - 0.006)]
    for k in range(7):                                            # outer shoulder arc
        a = math.pi / 2 * k / 6
        profile.append((r - sh + sh * math.sin(a), -hw + sh - sh * math.cos(a)))
    profile += [(r, -hw * 0.3), (r, hw * 0.3)]
    for k in range(7):
        a = math.pi / 2 * (6 - k) / 6
        profile.append((r - sh + sh * math.sin(a), hw - sh + sh * math.cos(a)))
    profile += [(r - 0.07, hw + 0.006), (rim_r + 0.03, hw - 0.005), (rim_r + 0.005, hw - 0.03)]
    tire = lathe(prefix + "Tire", profile, "Tire", segments=72)

    rim_profile = [(rim_r + 0.012, -hw + 0.02), (rim_r + 0.012, -hw + 0.035), (rim_r - 0.005, -hw + 0.04),
                   (rim_r - 0.005, hw - 0.04), (rim_r + 0.012, hw - 0.035), (rim_r + 0.012, hw - 0.02),
                   (rim_r - 0.012, hw - 0.02), (rim_r - 0.015, hw - 0.06), (0.07, hw - 0.07), (0.07, hw - 0.12)]
    rim = lathe(prefix + "Rim", rim_profile, "Rim", segments=48)
    objs = [tire, rim]
    for k in range(10):                                           # spokes on the outer face
        a = 2 * math.pi * k / 10
        sp = box(prefix + "Spoke", (hw - 0.07, math.cos(a) * 0.135, math.sin(a) * 0.135),
                 (0.025, 0.13, 0.022), "Rim", rot=(a + math.pi / 2, 0, 0))
        objs.append(sp)
    bpy.ops.mesh.primitive_cylinder_add(vertices=6, radius=0.045, depth=0.05, location=(hw - 0.06, 0, 0),
                                        rotation=(0, math.radians(90), 0))
    nut = bpy.context.active_object
    nut.name = prefix + "Hub"
    nut.data.materials.append(mat("Hub"))
    objs.append(nut)
    # merge rim pieces
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs[1:]:
        o.select_set(True)
    bpy.context.view_layer.objects.active = rim
    bpy.ops.object.join()
    return [tire, rim]


def join_by_material(objs):
    """Merge parts that share a material -> one draw call per material in the game."""
    groups = {}
    for o in objs:
        groups.setdefault(o.data.materials[0].name, []).append(o)
    merged = []
    for name, items in groups.items():
        bpy.ops.object.select_all(action="DESELECT")
        for o in items:
            o.select_set(True)
        bpy.context.view_layer.objects.active = items[0]
        if len(items) > 1:
            bpy.ops.object.join()
        items[0].name = name
        items[0].data.name = name
        merged.append(items[0])
    return merged


def main():
    reset_scene()
    body = build_body()
    for o in body:
        if o.name.startswith("Livery"):
            livery_uv(o)
        elif o.name.startswith(("Carbon", "Susp")):
            simple_uv(o, 10.0)
        else:
            simple_uv(o, 1.0)
    body = join_by_material(body)
    export_glb(out_path("Models/Car", "indycar.glb"), body)

    for o in list(bpy.context.scene.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    front = build_wheel("Front", 0.30)
    export_glb(out_path("Models/Car", "wheel_front.glb"), front)
    for o in list(bpy.context.scene.objects):
        bpy.data.objects.remove(o, do_unlink=True)
    rear = build_wheel("Rear", 0.38)
    export_glb(out_path("Models/Car", "wheel_rear.glb"), rear)

    # keep an editable .blend next to the scripts
    reset_scene()
    MATS.clear()
    for o in build_body():
        livery_uv(o) if o.name.startswith("Livery") else None
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(os.path.dirname(__file__), "indycar.blend"))
    print("CAR DONE")


if __name__ == "__main__":
    main()
