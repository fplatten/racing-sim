package com.racingsim.track;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.collision.shapes.PlaneCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Plane;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;

import static com.racingsim.track.TrackBuilder.geom;
import static com.racingsim.track.TrackBuilder.gray;
import static com.racingsim.track.TrackBuilder.uvRect;

/**
 * The road course's surfaces (port of the Unreal version's Track/RoadCourse.cpp): the road with
 * its slopes, white edge lines, red and white kerbs through the turns, grass run-off out to a
 * barrier on each side, the checkered start/finish line, the grid boxes and braking boards. The
 * surface meshes double as the static Bullet collider, in the same sub-mesh order as the oval's
 * (asphalt, paint, grass, walls: see {@link TrackBuilder#GRASS_PART}, {@link TrackBuilder#WALL_PART}),
 * so the wheels know the road and the kerbs (paint) from the grass.
 * <p>
 * The land around it is {@link Terrain}: wooded hills that meet the barriers at the track's height
 * (render only); below everything, 2 m under the lowest point of the track, is the ground plane.
 */
final class RoadCourseBuilder {

    private static final float ROW_STEP = 2.0f;
    private static final float ASPHALT_TILE = 3f, GRASS_TILE = 4f, WALL_TILE = 4f;
    private static final float ASPHALT_SHADE = 0.80f;
    private static final ColorRGBA WHITE = ColorRGBA.White;
    private static final ColorRGBA PAINT = new ColorRGBA(0.92f, 0.92f, 0.90f, 1);
    private static final ColorRGBA KERB_RED = new ColorRGBA(0.72f, 0.05f, 0.04f, 1);
    private static final ColorRGBA STEEL = new ColorRGBA(0.35f, 0.35f, 0.37f, 1);
    private static final Vector3f UP = Vector3f.UNIT_Y;
    /** Height of the ground plane: 2 m below the road course's lowest point (y = 0). */
    static final float GROUND_Y = -2f;

    private final TrackBuilder owner;
    private final TrackSpline track;
    private final AssetManager assets;

    RoadCourseBuilder(TrackBuilder owner, TrackSpline track, AssetManager assets) {
        this.owner = owner;
        this.track = track;
        this.assets = assets;
    }

    TrackBuilder.BuiltTrack build(PhysicsSpace space) {
        Node root = new Node("Track");
        MeshBuilder asphalt = new MeshBuilder(), asphaltVis = new MeshBuilder(), paint = new MeshBuilder(),
                grass = new MeshBuilder(), wall = new MeshBuilder(), deco = new MeshBuilder();
        buildSurface(asphalt, asphaltVis, paint, grass, wall, deco);

        Material asphaltMat = Pbr.textured(assets, "Textures/Track/asphalt_albedo.png", "Textures/Track/asphalt_normal.png",
                "Textures/Track/asphalt_rough.png", 0f, 1f);
        asphaltMat.setBoolean("UseVertexColor", true);
        Material paintMat = Pbr.material(assets, WHITE, 0f, 0.55f);
        paintMat.setBoolean("UseVertexColor", true);
        paintMat.setTexture("NormalMap", Pbr.texture(assets, "Textures/Track/asphalt_normal.png", true));
        paintMat.setFloat("NormalType", 1f);
        Material grassMat = Pbr.textured(assets, "Textures/Track/grass_albedo.png", "Textures/Track/grass_normal.png", null, 0f, 0.92f);
        grassMat.setBoolean("UseVertexColor", true);
        Material wallMat = Pbr.textured(assets, "Textures/Track/wall_albedo.png", "Textures/Track/wall_normal.png",
                "Textures/Track/wall_rough.png", 0f, 1f);
        wallMat.setBoolean("UseVertexColor", true);
        Material steelDeco = Pbr.material(assets, WHITE, 0.6f, 0.45f);
        steelDeco.setBoolean("UseVertexColor", true);
        Material groundMat = Pbr.textured(assets, "Textures/Track/grass_albedo.png", "Textures/Track/grass_normal.png", null, 0f, 0.95f);
        groundMat.setBoolean("UseVertexColor", true);

        // the collider keeps the coarse road; the visible road is finely subdivided for the rubber groove
        Mesh asphaltMesh = asphalt.build(), asphaltVisMesh = asphaltVis.build(), paintMesh = paint.build(),
                grassMesh = grass.build(), wallMesh = wall.build();
        Node surfaces = new Node("TrackSurfaces");
        owner.applyGrooveOcclusion(asphaltVisMesh, asphaltMat);
        surfaces.attachChild(geom("Asphalt", asphaltVisMesh, asphaltMat, RenderQueue.ShadowMode.Receive));
        surfaces.attachChild(geom("Paint", paintMesh, paintMat, RenderQueue.ShadowMode.Receive));
        surfaces.attachChild(geom("Grass", grassMesh, grassMat, RenderQueue.ShadowMode.Receive));
        surfaces.attachChild(geom("Walls", wallMesh, wallMat, RenderQueue.ShadowMode.CastAndReceive));
        root.attachChild(surfaces);

        // static collider (mass 0) from the very same triangles: asphalt, paint (lines, kerbs),
        // grass, barriers - the oval's sub-mesh order
        RigidBodyControl collider = new RigidBodyControl(
                new MeshCollisionShape(asphaltMesh, paintMesh, grassMesh, wallMesh), 0f);
        surfaces.addControl(collider);
        collider.setFriction(0.6f);
        collider.setRestitution(0.1f);
        space.add(collider);
        com.racingsim.physics.WallContactFilter.install(space, collider, TrackBuilder.WALL_PART);

        root.attachChild(geom("Decorations", deco.build(), steelDeco, RenderQueue.ShadowMode.Cast));

        Terrain terrain = Terrain.build(track);
        Geometry ground = geom("Ground", buildTerrainMesh(terrain), groundMat, RenderQueue.ShadowMode.Receive);
        RigidBodyControl groundCollider = new RigidBodyControl(new PlaneCollisionShape(new Plane(Vector3f.UNIT_Y, GROUND_Y)), 0f);
        Node groundNode = new Node("GroundPlane");
        groundNode.addControl(groundCollider);
        groundCollider.setFriction(0.5f);
        groundCollider.setRestitution(0.05f);
        space.add(groundCollider);
        root.attachChild(groundNode);

        MikktspaceTangentGenerator.generate(root);
        root.attachChild(ground);                    // brings its own tangents
        System.out.printf("Terrain: %d x %d cells%n", terrain.nx, terrain.nz);
        return new TrackBuilder.BuiltTrack(root, collider, groundCollider, terrain);
    }

    private Vector3f p(float s, float d) {
        return track.point(s, d, new Vector3f());
    }

    private Vector3f p(float s, float d, float yAbove) {
        Vector3f v = track.point(s, d, new Vector3f());
        v.y += yAbove;
        return v;
    }

    private void buildSurface(MeshBuilder asphalt, MeshBuilder asphaltVis, MeshBuilder paint, MeshBuilder grass,
                              MeshBuilder wall, MeshBuilder deco) {
        int rows = (int) Math.ceil(TrackSpline.LENGTH / ROW_STEP);
        float step = TrackSpline.LENGTH / rows;
        float half = TrackSpline.ROAD_OUTER;
        float lineIn = half - 0.45f, lineOut = half - 0.2f;     // white edge lines
        float[] roadD = {-half, -3.25f, 0f, 3.25f, half};
        float h = TrackSpline.BARRIER_HEIGHT;
        int strips = Math.round(2 * half / 0.4f);

        for (int i = 0; i < rows; i++) {
            float s0 = i * step, s1 = (i + 1) * step, sMid = (s0 + s1) / 2;
            Vector3f inward0 = track.right(s0, null).negateLocal();
            float us0 = s0 / ASPHALT_TILE, us1 = s1 / ASPHALT_TILE;

            // ---- the road: coarse for the collider, fine strips (rubber, wear) for the eye ----
            for (int k = 0; k < roadD.length - 1; k++) {
                float d0 = roadD[k], d1 = roadD[k + 1];
                asphalt.quad(p(s0, d0), p(s1, d0), p(s1, d1), p(s0, d1), gray(ASPHALT_SHADE), UP,
                        uvRect(us0, d0 / ASPHALT_TILE, us1, d1 / ASPHALT_TILE));
            }
            for (int k = 0; k < strips; k++) {
                float d0 = -half + 2 * half * k / strips, d1 = -half + 2 * half * (k + 1) / strips;
                asphaltVis.quad(p(s0, d0), p(s1, d0), p(s1, d1), p(s0, d1),
                        gray(owner.grooveShade(s0, d0)), gray(owner.grooveShade(s1, d0)),
                        gray(owner.grooveShade(s1, d1)), gray(owner.grooveShade(s0, d1)),
                        UP, uvRect(us0, d0 / ASPHALT_TILE, us1, d1 / ASPHALT_TILE));
            }
            // white lines along both edges
            for (float sign : new float[]{-1f, 1f}) {
                float a = sign * lineIn, b = sign * lineOut;
                paint.quad(p(s0, a, 0.004f), p(s1, a, 0.004f), p(s1, b, 0.004f), p(s0, b, 0.004f), PAINT, UP,
                        uvRect(us0, a / ASPHALT_TILE, us1, b / ASPHALT_TILE));
            }

            // ---- each side: kerb (through the turns), grass run-off, the barrier ----
            for (int side = 0; side < 2; side++) {
                boolean right = side == 1;
                float sg = right ? 1f : -1f;
                boolean kerb = track.kerbAt(sMid, right);
                float edge = half + (kerb ? TrackSpline.KERB_WIDTH : 0f);
                if (kerb) {
                    // red and white blocks, 2 m each, a touch above the road
                    boolean red = i % 2 == 0;
                    float a = sg * half, b = sg * edge;
                    paint.quad(p(s0, a, 0.01f), p(s1, a, 0.01f), p(s1, b, 0.03f), p(s0, b, 0.03f), red ? KERB_RED : PAINT, UP,
                            uvRect(us0, a / ASPHALT_TILE, us1, b / ASPHALT_TILE));
                }
                float w0 = right ? track.maxD(s0) : track.minD(s0);
                float w1 = right ? track.maxD(s1) : track.minD(s1);
                float e = sg * edge;
                grass.quad(p(s0, e), p(s1, e), p(s1, w1), p(s0, w0),
                        owner.groundColor(p(s0, e)), owner.groundColor(p(s1, e)), owner.groundColor(p(s1, w1)), owner.groundColor(p(s0, w0)),
                        UP, new float[]{s0 / GRASS_TILE, e / GRASS_TILE, s1 / GRASS_TILE, e / GRASS_TILE,
                                s1 / GRASS_TILE, w1 / GRASS_TILE, s0 / GRASS_TILE, w0 / GRASS_TILE});
                // barrier: face toward the track, top, back (texture v = 0 at the top)
                Vector3f in = right ? inward0 : inward0.negate();
                float u0 = s0 / WALL_TILE, u1 = s1 / WALL_TILE;
                float back0 = w0 + sg * 0.5f, back1 = w1 + sg * 0.5f;
                wall.quad(p(s0, w0), p(s1, w1), p(s1, w1, h), p(s0, w0, h), WHITE, in, uvRect(u0, 1, u1, 0));
                wall.quad(p(s0, w0, h), p(s1, w1, h), p(s1, back1, h), p(s0, back0, h), gray(0.9f), UP, uvRect(u0, 0.02f, u1, 0.08f));
                wall.quad(p(s0, back0, h), p(s1, back1, h), p(s1, back1, -1f), p(s0, back0, -1f), gray(0.85f), in.negate(),
                        uvRect(u0, 0, u1, 1));
            }
        }

        // ---- start / finish line: a checkered band across the road at s = 0 ----
        ColorRGBA checkBlack = new ColorRGBA(0.035f, 0.035f, 0.04f, 1);
        int checkCols = 26, checkRows = 3;
        float square = 2 * half / checkCols;
        float sStart = -checkRows * square / 2;
        for (int r = 0; r < checkRows; r++) {
            float s0 = sStart + r * square, s1 = s0 + square;
            for (int c = 0; c < checkCols; c++) {
                float d0 = -half + c * square, d1 = d0 + square;
                paint.quad(p(s0, d0, 0.006f), p(s1, d0, 0.006f), p(s1, d1, 0.006f), p(s0, d1, 0.006f),
                        ((r + c) % 2 == 0) ? PAINT : checkBlack, UP,
                        uvRect(s0 / ASPHALT_TILE, d0 / ASPHALT_TILE, s1 / ASPHALT_TILE, d1 / ASPHALT_TILE));
            }
        }
        // ---- the grid: a white bracket in front of each starting place ----
        for (int slot = 0; slot < 33; slot++) {
            float[] g = com.racingsim.app.RaceDirector.gridSlot(slot, 33);
            float front = g[0] + 2.9f, d = g[1];
            stripeAcross(paint, front, front + 0.25f, d - 1.4f, d + 1.4f);
            stripeAlong(paint, front - 1.5f, front, d - 1.4f, 0.2f);
            stripeAlong(paint, front - 1.5f, front, d + 1.4f, 0.2f);
        }

        // ---- braking boards (300 / 200 / 100 m) before the slow turns, behind the outside barrier ----
        ColorRGBA yellow = new ColorRGBA(0.95f, 0.75f, 0.08f, 1), red = new ColorRGBA(0.75f, 0.06f, 0.06f, 1);
        ColorRGBA[] boardCols = {PAINT, yellow, red};
        for (float ts : track.turnStarts()) {
            float kMax = 0f;
            for (float s = ts; s < ts + 120f; s += 4f) kMax = Math.max(kMax, Math.abs(track.curvature(s)));
            if (kMax < 1f / 90f) continue;                      // a fast turn: no braking to speak of
            boolean outsideRight = track.turnDirection(ts) > 0f;
            for (int k = 0; k < 3; k++) {
                float s = TrackSpline.wrapS(ts - 300 + k * 100);
                float wd = outsideRight ? track.maxD(s) + 1.5f : track.minD(s) - 1.5f;
                Vector3f base = p(s, wd, 0f);
                Vector3f fwd = track.forward(s, null), rt = track.right(s, null);
                deco.box(base.add(0, 2.4f, 0), fwd, UP, rt, 0.12f, 0.8f, 0.8f, boardCols[k]);
                deco.box(base.add(0, 0.8f, 0), fwd, UP, rt, 0.08f, 0.8f, 0.08f, STEEL);
            }
        }
    }

    private void stripeAlong(MeshBuilder paint, float s0, float s1, float d, float width) {
        for (float s = s0; s < s1; s += 2f) {
            float e = Math.min(s + 2f, s1);
            paint.quad(p(s, d - width / 2, 0.006f), p(e, d - width / 2, 0.006f), p(e, d + width / 2, 0.006f),
                    p(s, d + width / 2, 0.006f), PAINT, UP,
                    uvRect(s / ASPHALT_TILE, d / ASPHALT_TILE, e / ASPHALT_TILE, (d + width) / ASPHALT_TILE));
        }
    }

    private void stripeAcross(MeshBuilder paint, float s0, float s1, float d0, float d1) {
        paint.quad(p(s0, d0, 0.006f), p(s1, d0, 0.006f), p(s1, d1, 0.006f), p(s0, d1, 0.006f), PAINT, UP,
                uvRect(s0 / ASPHALT_TILE, d0 / ASPHALT_TILE, s1 / ASPHALT_TILE, d1 / ASPHALT_TILE));
    }

    /**
     * The wooded hills around the circuit (render only; the cars that leave the track meet the
     * ground plane below): an indexed grid with smooth normals over {@link Terrain}'s heights,
     * colored by {@link GroundShade} near the track and turning to forest canopy (darker, patchy)
     * from {@link Terrain#FOREST_FROM} m beyond the barriers; a level skirt of forest reaches the
     * horizon. Its tangents are made here (the grid is too big for the tangent generator).
     */
    private Mesh buildTerrainMesh(Terrain t) {
        int nx = t.nx, nz = t.nz, cell = (int) Terrain.CELL;
        float tile = 6f;
        // the skirt: four trapezoids from the grid's edge out to the horizon, level with the faded edge
        float far = 6000f, xa = t.x0, xb = t.x0 + (nx - 1) * Terrain.CELL, za = t.z0, zb = t.z0 + (nz - 1) * Terrain.CELL;
        float mx = (xa + xb) / 2, mz = (za + zb) / 2;
        float[][] ring = {{mx - far, mz - far}, {mx + far, mz - far}, {mx + far, mz + far}, {mx - far, mz + far}};
        float[][] rect = {{xa, za}, {xb, za}, {xb, zb}, {xa, zb}};
        int verts = nx * nz + 16;
        float[] pos = new float[verts * 3], nrm = new float[verts * 3], uv = new float[verts * 2],
                col = new float[verts * 4], tan = new float[verts * 4];
        int[] idx = new int[((nx - 1) * (nz - 1) + 8) * 6];
        Vector3f n = new Vector3f(), tg = new Vector3f();
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int v = j * nx + i;
                float x = t.x0 + i * Terrain.CELL, z = t.z0 + j * Terrain.CELL, h = t.y[v];
                pos[v * 3] = x;
                pos[v * 3 + 1] = h;
                pos[v * 3 + 2] = z;
                n.set(yAt(t, i - 1, j) - yAt(t, i + 1, j), 2f * cell, yAt(t, i, j - 1) - yAt(t, i, j + 1)).normalizeLocal();
                nrm[v * 3] = n.x;
                nrm[v * 3 + 1] = n.y;
                nrm[v * 3 + 2] = n.z;
                tg.set(1f, 0f, 0f).subtractLocal(n.mult(n.x)).normalizeLocal();      // +x along the slope
                tan[v * 4] = tg.x;
                tan[v * 4 + 1] = tg.y;
                tan[v * 4 + 2] = tg.z;
                tan[v * 4 + 3] = -1f;
                uv[v * 2] = x / tile;
                uv[v * 2 + 1] = z / tile;
                ColorRGBA c = owner.groundColor(new Vector3f(x, h, z));
                // forest canopy far from the track (darker with distance), patchy
                float canopy = TrackSpline.smooth((t.beyond[v] - Terrain.FOREST_FROM) / 120f);
                float patch = 0.85f + 0.3f * GroundShade.fbm(x / 60f, z / 60f, 3, 91);
                col[v * 4] = FastMath.interpolateLinear(canopy, c.r, 0.20f * patch);
                col[v * 4 + 1] = FastMath.interpolateLinear(canopy, c.g, 0.27f * patch);
                col[v * 4 + 2] = FastMath.interpolateLinear(canopy, c.b, 0.15f * patch);
                col[v * 4 + 3] = 1f;
            }
        }
        int k = 0;
        for (int j = 0; j < nz - 1; j++) {
            for (int i = 0; i < nx - 1; i++) {
                int a = j * nx + i, b = a + 1, d = a + nx, e = d + 1;
                idx[k++] = a; idx[k++] = d; idx[k++] = b;
                idx[k++] = b; idx[k++] = d; idx[k++] = e;
            }
        }
        int v = nx * nz;
        for (int q = 0; q < 4; q++) {
            int q1 = (q + 1) % 4;
            float[][] corners = {ring[q], ring[q1], rect[q1], rect[q]};
            for (float[] p : corners) {
                pos[v * 3] = p[0];
                pos[v * 3 + 1] = t.edgeY;
                pos[v * 3 + 2] = p[1];
                nrm[v * 3 + 1] = 1f;
                tan[v * 4] = 1f;
                tan[v * 4 + 3] = -1f;
                uv[v * 2] = p[0] / tile;
                uv[v * 2 + 1] = p[1] / tile;
                col[v * 4] = 0.17f;
                col[v * 4 + 1] = 0.23f;
                col[v * 4 + 2] = 0.13f;
                col[v * 4 + 3] = 1f;
                v++;
            }
            int a = v - 4;
            // wound to face up whichever way round the corners go
            Vector3f p0 = new Vector3f(corners[0][0], 0, corners[0][1]), p1 = new Vector3f(corners[1][0], 0, corners[1][1]),
                    p2 = new Vector3f(corners[2][0], 0, corners[2][1]);
            boolean up = p1.subtract(p0).crossLocal(p2.subtract(p0)).y > 0f;
            if (up) {
                idx[k++] = a; idx[k++] = a + 1; idx[k++] = a + 2;
                idx[k++] = a; idx[k++] = a + 2; idx[k++] = a + 3;
            } else {
                idx[k++] = a; idx[k++] = a + 2; idx[k++] = a + 1;
                idx[k++] = a; idx[k++] = a + 3; idx[k++] = a + 2;
            }
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, pos);
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, nrm);
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, uv);
        mesh.setBuffer(VertexBuffer.Type.Color, 4, col);
        mesh.setBuffer(VertexBuffer.Type.Tangent, 4, tan);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, java.util.Arrays.copyOf(idx, k));
        mesh.updateBound();
        return mesh;
    }

    private static float yAt(Terrain t, int i, int j) {
        return t.y[Math.clamp(j, 0, t.nz - 1) * t.nx + Math.clamp(i, 0, t.nx - 1)];
    }
}
