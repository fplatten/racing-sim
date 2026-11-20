package com.racingsim.track;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.collision.shapes.PlaneCollisionShape;
import com.jme3.math.Plane;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;

import static com.racingsim.track.TrackSpline.*;

/**
 * Procedurally generates the oval with PBR-textured surfaces (Blender-made textures, see
 * {@code blender/make_textures.py}): asphalt with a rubbered-in racing groove, painted lines,
 * apron, infield grass, concrete walls, an Indianapolis-style pit lane with 33 boxes (see
 * {@link PitLane}), a chain-link catch fence, a chequered start/finish line,
 * pylons and turn-in boards. The surface meshes double as the static Bullet collider.
 */
public final class TrackBuilder {

    private static final float ROW_STEP = 2.0f;
    private static final float ASPHALT_TILE = 3f, GRASS_TILE = 4f, WALL_TILE = 4f;
    private static final float FENCE_HEIGHT = 4.2f;

    private static final ColorRGBA WHITE = ColorRGBA.White;
    private static final ColorRGBA PAINT = new ColorRGBA(0.92f, 0.92f, 0.90f, 1);
    private static final ColorRGBA STEEL = new ColorRGBA(0.35f, 0.35f, 0.37f, 1);

    private final TrackSpline track;
    private final AssetManager assets;

    public TrackBuilder(TrackSpline track, AssetManager assets) {
        this.track = track;
        this.assets = assets;
        this.racingLine = new com.racingsim.ai.RacingLine(track);
        this.groundShade = new GroundShade(track);
    }

    /**
     * Built track scene + colliders: {@code collider} = racing surface, apron and walls;
     * {@code groundCollider} = infinite ground plane under everything (grass), so a car that
     * leaves the track can never fall out of the world.
     */
    public record BuiltTrack(Node node, RigidBodyControl collider, RigidBodyControl groundCollider) { }

    /** Sub-mesh index of the grass strips inside the track collider (asphalt, paint, grass, walls). */
    public static final int GRASS_PART = 2;
    /** Sub-mesh indices of the walls and catch fence: never a driving surface for the wheels. */
    public static final int WALL_PART = 3, FENCE_PART = 4;

    /** Height of the surrounding ground (just below the apron, which sits at y = 0). */
    public static final float GROUND_Y = -0.05f;

    private final com.racingsim.ai.RacingLine racingLine;
    private final GroundShade groundShade;

    public BuiltTrack build(PhysicsSpace space) {
        Node root = new Node("Track");

        Dual asphalt = new Dual();
        MeshBuilder paint = new MeshBuilder(), grass = new MeshBuilder(), wall = new MeshBuilder();
        buildSurface(asphalt, paint, grass, wall);

        Material asphaltMat = Pbr.textured(assets, "Textures/Track/asphalt_albedo.png", "Textures/Track/asphalt_normal.png",
                "Textures/Track/asphalt_rough.png", 0f, 1f);
        asphaltMat.setBoolean("UseVertexColor", true);
        Material paintMat = Pbr.material(assets, WHITE, 0f, 0.55f);
        paintMat.setBoolean("UseVertexColor", true);
        paintMat.setTexture("NormalMap", Pbr.texture(assets, "Textures/Track/asphalt_normal.png", true));
        paintMat.setFloat("NormalType", 1f);
        Material grassMat = Pbr.textured(assets, "Textures/Track/grass_albedo.png", "Textures/Track/grass_normal.png", null, 0f, 0.92f);
        grassMat.setBoolean("UseVertexColor", true);                // same colouring as the ground around it
        Material wallMat = Pbr.textured(assets, "Textures/Track/wall_albedo.png", "Textures/Track/wall_normal.png",
                "Textures/Track/wall_rough.png", 0f, 1f);
        wallMat.setBoolean("UseVertexColor", true);

        // the collider keeps the coarse road; the visible road is finely subdivided for the rubber groove
        Mesh asphaltMesh = asphalt.col.build(), asphaltVisMesh = asphalt.vis.build(), paintMesh = paint.build(), grassMesh = grass.build(), wallMesh = wall.build();
        // the catch fence is part of the collider too: nothing leaves the track over the outer wall
        Mesh fenceMesh = buildFenceMesh();
        Node surfaces = new Node("TrackSurfaces");
        Material roadMat = asphaltMat.clone();                        // service roads: no groove
        applyGrooveOcclusion(asphaltVisMesh, asphaltMat);
        surfaces.attachChild(geom("Asphalt", asphaltVisMesh, asphaltMat, RenderQueue.ShadowMode.Receive));
        surfaces.attachChild(geom("Paint", paintMesh, paintMat, RenderQueue.ShadowMode.Receive));
        surfaces.attachChild(geom("Grass", grassMesh, grassMat, RenderQueue.ShadowMode.Receive));
        surfaces.attachChild(geom("Walls", wallMesh, wallMat, RenderQueue.ShadowMode.CastAndReceive));
        root.attachChild(surfaces);

        // Static collider (mass 0) built from the very same triangles
        RigidBodyControl collider = new RigidBodyControl(
                new MeshCollisionShape(asphaltMesh, paintMesh, grassMesh, wallMesh, fenceMesh), 0f);
        surfaces.addControl(collider);
        collider.setFriction(0.6f);
        collider.setRestitution(0.1f);
        space.add(collider);
        com.racingsim.physics.WallContactFilter.install(space, collider, WALL_PART);

        // catch fence (alpha-tested chain link) + posts / cables
        Material fenceMat = Pbr.material(assets, new ColorRGBA(0.75f, 0.76f, 0.78f, 1), 0.8f, 0.4f);
        fenceMat.setTexture("BaseColorMap", Pbr.texture(assets, "Textures/Track/chainlink.png", true));
        fenceMat.setFloat("AlphaDiscardThreshold", 0.35f);
        fenceMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        root.attachChild(geom("CatchFence", fenceMesh, fenceMat, RenderQueue.ShadowMode.Off));

        Material steelDeco = Pbr.material(assets, WHITE, 0.6f, 0.45f);
        steelDeco.setBoolean("UseVertexColor", true);
        root.attachChild(geom("Decorations", buildDecorations(), steelDeco, RenderQueue.ShadowMode.Cast));

        Material groundMat = Pbr.textured(assets, "Textures/Track/grass_albedo.png", "Textures/Track/grass_normal.png", null, 0f, 0.95f);
        groundMat.setBoolean("UseVertexColor", true);
        Geometry ground = geom("Ground", buildGround(), groundMat, RenderQueue.ShadowMode.Receive);
        RigidBodyControl groundCollider = new RigidBodyControl(
                new PlaneCollisionShape(new Plane(Vector3f.UNIT_Y, GROUND_Y)), 0f);
        ground.addControl(groundCollider);
        groundCollider.setFriction(0.5f);
        groundCollider.setRestitution(0.05f);
        space.add(groundCollider);
        root.attachChild(geom("ServiceRoads", buildServiceRoads(), roadMat, RenderQueue.ShadowMode.Receive));

        // normal-mapped PBR surfaces need tangents (the ground grid brings its own: it is flat)
        MikktspaceTangentGenerator.generate(root);
        root.attachChild(ground);
        return new BuiltTrack(root, collider, groundCollider);
    }

    private static Geometry geom(String name, Mesh mesh, Material mat, RenderQueue.ShadowMode shadows) {
        Geometry g = new Geometry(name, mesh);
        g.setMaterial(mat);
        g.setShadowMode(shadows);
        return g;
    }

    private Vector3f p(float s, float d) {
        return track.point(s, d, new Vector3f());
    }

    private Vector3f p(float s, float d, float yAbove) {
        Vector3f v = track.point(s, d, new Vector3f());
        v.y += yAbove;
        return v;
    }

    private static Vector3f atY(Vector3f v, float y) {
        return new Vector3f(v.x, y, v.z);
    }

    private static float[] uvRect(float u0, float v0, float u1, float v1) {
        return new float[]{u0, v0, u1, v0, u1, v1, u0, v1};
    }

    private static ColorRGBA gray(float g) {
        return new ColorRGBA(g, g, g, 1);
    }

    /** Unworn asphalt shade (vertex colour multiplier on the asphalt texture). */
    private static final float ASPHALT_SHADE = 0.80f;
    /** Lateral resolution of the visible road (m): fine enough for a smooth groove edge. */
    private static final float GROOVE_STRIP = 0.4f;
    /** How much the heaviest rubber dulls the sky reflection and ambient light. */
    private static final float GROOVE_AO = 0.45f;

    /**
     * Rubber laid down by the tires along the racing line ({@link com.racingsim.ai.RacingLine}: high
     * on the straights, down to the apex at the middle of each turn, back up on exit). A darker band
     * about a car wide with soft edges: darkest and narrowest through the turns, where the tires
     * work hardest and every car takes the same line; wider and fainter on the straights, where the
     * cars spread out. Two slightly darker wheel tracks run inside it in the turns, and the amount
     * of rubber varies a little along the lap.
     */
    private float grooveShade(float s, float d) {
        return ASPHALT_SHADE * (1f - 0.30f * groove(s, d)) * surfaceVariation(s, d);
    }

    /**
     * Non-repeating wear on the racing surface: broad lighter / darker patches (resurfacing,
     * sun-bleached binder) and the odd small dark stain (oil, fluid drips), more of them in the
     * groove where the cars run. They come from noise over the whole lap rather than from the
     * 3 m asphalt texture, so no two look alike and they never line up at speed.
     */
    private float surfaceVariation(float s, float d) {
        float patches = GroundShade.fbm(s / 55f, d / 9f, 3, 71) - 0.5f;           // about +-4 %
        float stainNoise = GroundShade.fbm(s / 7f, d / 2.2f, 2, 83);
        float stainChance = 0.74f - 0.05f * Math.min(1f, groove(s, d));
        float stain = smoothstep(stainChance, stainChance + 0.08f, stainNoise);
        return (1f + 0.09f * patches) * (1f - 0.12f * stain);
    }

    /** 0 (clean asphalt) .. ~1.1 (heaviest rubber, in the wheel tracks at the apex). */
    private float groove(float s, float d) {
        if (d < ROAD_INNER - 0.01f || d > ROAD_OUTER) return 0f;
        float turn = track.bank(s) / BANK_ANGLE;                    // 0 on the straights, 1 in the turns
        float x = Math.abs(d - racingLine.offset(s));
        float core = 1.0f + 0.9f * (1f - turn);                     // half-width of the full-strength band
        float feather = 1.5f + 0.8f * (1f - turn);                  // gradient out to clean asphalt
        float band = 1f - smoothstep(core, core + feather, x);
        float wheels = FastMath.exp(-sq((x - 0.8f) / 0.32f)) * turn;
        float amount = (0.5f + 0.5f * turn) * (0.88f + 0.24f * GroundShade.fbm(s / 37f, d / 11f, 2, 5));
        return band * (amount + 0.25f * wheels);
    }

    /**
     * Rubber also dulls the asphalt's reflection of the sky, which is most of what you see of the
     * road at a driver's grazing view angle (vertex colours only darken the diffuse part). The
     * groove is therefore also fed to the PBR shader as an ambient-occlusion value, which scales
     * the specular and image-based light: a second UV channel whose u is the AO value, sampled
     * from a 1-D identity ramp texture.
     */
    private void applyGrooveOcclusion(Mesh mesh, Material mat) {
        java.nio.FloatBuffer pos = (java.nio.FloatBuffer) mesh.getBuffer(VertexBuffer.Type.Position).getData();
        int n = mesh.getVertexCount();
        float[] uv2 = new float[n * 2];
        TrackSpline.TrackPos tp = new TrackSpline.TrackPos();
        Vector3f v = new Vector3f();
        for (int i = 0; i < n; i++) {
            v.set(pos.get(i * 3), pos.get(i * 3 + 1), pos.get(i * 3 + 2));
            track.project(v, tp);
            uv2[i * 2] = FastMath.clamp(1f - GROOVE_AO * groove(tp.s, tp.d), 0f, 1f);
            uv2[i * 2 + 1] = 0.5f;
        }
        mesh.setBuffer(VertexBuffer.Type.TexCoord2, 2, uv2);
        java.nio.ByteBuffer ramp = com.jme3.util.BufferUtils.createByteBuffer(256 * 4);
        for (int i = 0; i < 256; i++) ramp.put((byte) i).put((byte) i).put((byte) i).put((byte) 255);
        ramp.flip();
        com.jme3.texture.Texture2D tex = new com.jme3.texture.Texture2D(
                new com.jme3.texture.Image(com.jme3.texture.Image.Format.RGBA8, 256, 1, ramp,
                        com.jme3.texture.image.ColorSpace.Linear));
        tex.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
        tex.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
        tex.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        mat.setTexture("LightMap", tex);
        mat.setBoolean("LightMapAsAOMap", true);
        mat.setBoolean("SeparateTexCoord", true);
    }

    private static float smoothstep(float a, float b, float x) {
        float t = FastMath.clamp((x - a) / (b - a), 0f, 1f);
        return t * t * (3 - 2 * t);
    }

    private static float sq(float x) { return x * x; }

    private ColorRGBA groundColor(Vector3f p) {
        return groundShade.color(p.x, p.z);
    }

    /** Builds the same quads into the collider mesh and the visible mesh (only the road band differs). */
    private static final class Dual {
        final MeshBuilder col = new MeshBuilder(), vis = new MeshBuilder();

        void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, ColorRGBA color, Vector3f hint, float[] uv) {
            col.quad(a, b, c, d, color, hint, uv);
            vis.quad(a, b, c, d, color, hint, uv);
        }
    }

    private void buildSurface(Dual asphalt, MeshBuilder paint, MeshBuilder grass, MeshBuilder wall) {
        int rows = (int) Math.ceil(LENGTH / ROW_STEP);
        float step = LENGTH / rows;
        Vector3f up = Vector3f.UNIT_Y;
        float wallOut = ROAD_OUTER + OUTER_WALL_THICKNESS;
        float innerWallBack = INNER_WALL - 0.4f;
        float lineIn0 = ROAD_INNER - 0.3f, lineOut0 = ROAD_OUTER - 0.45f, lineOut1 = ROAD_OUTER - 0.2f;
        float[] roadD = {ROAD_INNER, -5.5f, -3.5f, -1.5f, 0.5f, 2.5f, 4.5f, 6.5f, lineOut0};

        for (int i = 0; i < rows; i++) {
            float s0 = i * step, s1 = (i + 1) * step;
            Vector3f r0 = track.right(s0, null), r1 = track.right(s1, null);
            Vector3f inward = r0.add(r1).normalizeLocal().negateLocal();
            Vector3f outward = inward.negate();
            float us0 = s0 / ASPHALT_TILE, us1 = s1 / ASPHALT_TILE;

            // ---- asphalt: banked road (coarse, for the collider) ----
            for (int k = 0; k < roadD.length - 1; k++) {
                float d0 = roadD[k], d1 = roadD[k + 1];
                asphalt.col.quad(p(s0, d0), p(s1, d0), p(s1, d1), p(s0, d1), gray(ASPHALT_SHADE), up,
                        uvRect(us0, d0 / ASPHALT_TILE, us1, d1 / ASPHALT_TILE));
            }
            // ---- visible road: fine strips carrying the rubber laid down along the racing line ----
            int strips = Math.round((lineOut0 - ROAD_INNER) / GROOVE_STRIP);
            for (int k = 0; k < strips; k++) {
                float d0 = ROAD_INNER + (lineOut0 - ROAD_INNER) * k / strips;
                float d1 = ROAD_INNER + (lineOut0 - ROAD_INNER) * (k + 1) / strips;
                asphalt.vis.quad(p(s0, d0), p(s1, d0), p(s1, d1), p(s0, d1),
                        gray(grooveShade(s0, d0)), gray(grooveShade(s1, d0)), gray(grooveShade(s1, d1)), gray(grooveShade(s0, d1)),
                        up, uvRect(us0, d0 / ASPHALT_TILE, us1, d1 / ASPHALT_TILE));
            }
            asphalt.quad(p(s0, lineOut1), p(s1, lineOut1), p(s1, ROAD_OUTER), p(s0, ROAD_OUTER), gray(0.7f), up,
                    uvRect(us0, lineOut1 / ASPHALT_TILE, us1, ROAD_OUTER / ASPHALT_TILE));
            float ai0 = PitLane.apronInner(s0), ai1 = PitLane.apronInner(s1);   // narrowed beside the pit exit lane
            asphalt.quad(p(s0, ai0), p(s1, ai1), p(s1, lineIn0), p(s0, lineIn0), gray(1.0f), up,
                    new float[]{us0, ai0 / ASPHALT_TILE, us1, ai1 / ASPHALT_TILE,
                            us1, lineIn0 / ASPHALT_TILE, us0, lineIn0 / ASPHALT_TILE});

            // ---- painted lines ----
            paint.quad(p(s0, lineIn0), p(s1, lineIn0), p(s1, ROAD_INNER), p(s0, ROAD_INNER), PAINT, up,
                    uvRect(us0, lineIn0 / ASPHALT_TILE, us1, ROAD_INNER / ASPHALT_TILE));
            paint.quad(p(s0, lineOut0), p(s1, lineOut0), p(s1, lineOut1), p(s0, lineOut1), PAINT, up,
                    uvRect(us0, lineOut0 / ASPHALT_TILE, us1, lineOut1 / ASPHALT_TILE));

            // ---- walls: texture v = 0 at the top of the wall, 1 at the base ----
            float u0 = s0 / WALL_TILE, u1 = s1 / WALL_TILE;
            float vTopInner = 1f - INNER_WALL_HEIGHT / OUTER_WALL_HEIGHT;
            float sMid = (s0 + s1) / 2f;
            boolean pitRow = PitLane.inPitRoad(sMid);
            boolean laneRow = PitLane.inExitLane(sMid);
            if (laneRow) {
                // ---- pit exit lane: separate paved lane, grass median between it and the track ----
                float li0 = PitLane.laneInner(s0), li1 = PitLane.laneInner(s1);
                float lo0 = PitLane.laneOuter(s0), lo1 = PitLane.laneOuter(s1);
                asphalt.quad(p(s0, li0), p(s1, li1), p(s1, lo1), p(s0, lo0), gray(0.9f), up,
                        new float[]{us0, li0 / ASPHALT_TILE, us1, li1 / ASPHALT_TILE,
                                us1, lo1 / ASPHALT_TILE, us0, lo0 / ASPHALT_TILE});
                if (ai0 - lo0 > 0.02f || ai1 - lo1 > 0.02f) {
                    grass.quad(p(s0, lo0), p(s1, lo1), p(s1, ai1), p(s0, ai0),
                            groundColor(p(s0, lo0)), groundColor(p(s1, lo1)), groundColor(p(s1, ai1)), groundColor(p(s0, ai0)), up,
                            new float[]{s0 / GRASS_TILE, lo0 / GRASS_TILE, s1 / GRASS_TILE, lo1 / GRASS_TILE,
                                    s1 / GRASS_TILE, ai1 / GRASS_TILE, s0 / GRASS_TILE, ai0 / GRASS_TILE});
                }
                if (PitLane.rel(sMid) < PitLane.END) {
                    // the pit exit road keeps its wall on the infield side until it reaches the lane
                    float bb0 = li0 - 0.4f, bb1 = li1 - 0.4f;
                    wall.quad(atY(p(s0, li0), 0), atY(p(s1, li1), 0),
                            atY(p(s1, li1), INNER_WALL_HEIGHT), atY(p(s0, li0), INNER_WALL_HEIGHT), WHITE, outward,
                            uvRect(u0, 1, u1, vTopInner));
                    wall.quad(atY(p(s0, bb0), INNER_WALL_HEIGHT), atY(p(s1, bb1), INNER_WALL_HEIGHT),
                            atY(p(s1, li1), INNER_WALL_HEIGHT), atY(p(s0, li0), INNER_WALL_HEIGHT), gray(0.9f), up,
                            uvRect(u0, 0.02f, u1, 0.06f));
                    wall.quad(atY(p(s0, bb0), 0), atY(p(s1, bb1), 0),
                            atY(p(s1, bb1), INNER_WALL_HEIGHT), atY(p(s0, bb0), INNER_WALL_HEIGHT), gray(0.85f), inward,
                            uvRect(u0, 1, u1, vTopInner));
                }
            } else if (pitRow) {
                // ---- pit complex: pit road surface from the apron in to the pit back wall ----
                float pb0 = PitLane.backD(s0), pb1 = PitLane.backD(s1);
                asphalt.quad(p(s0, pb0), p(s1, pb1), p(s1, APRON_INNER), p(s0, APRON_INNER), gray(0.92f), up,
                        new float[]{us0, pb0 / ASPHALT_TILE, us1, pb1 / ASPHALT_TILE,
                                us1, APRON_INNER / ASPHALT_TILE, us0, APRON_INNER / ASPHALT_TILE});
                // pit back wall (faces the pit road), top, back
                float pbb0 = pb0 - 0.4f, pbb1 = pb1 - 0.4f;
                wall.quad(atY(p(s0, pb0), 0), atY(p(s1, pb1), 0),
                        atY(p(s1, pb1), INNER_WALL_HEIGHT), atY(p(s0, pb0), INNER_WALL_HEIGHT), WHITE, outward,
                        uvRect(u0, 1, u1, vTopInner));
                wall.quad(atY(p(s0, pbb0), INNER_WALL_HEIGHT), atY(p(s1, pbb1), INNER_WALL_HEIGHT),
                        atY(p(s1, pb1), INNER_WALL_HEIGHT), atY(p(s0, pb0), INNER_WALL_HEIGHT), gray(0.9f), up,
                        uvRect(u0, 0.02f, u1, 0.06f));
                wall.quad(atY(p(s0, pbb0), 0), atY(p(s1, pbb1), 0),
                        atY(p(s1, pbb1), INNER_WALL_HEIGHT), atY(p(s0, pbb0), INNER_WALL_HEIGHT), gray(0.85f), inward,
                        uvRect(u0, 1, u1, vTopInner));
            } else {
                // ---- infield grass strip ----
                grass.quad(p(s0, INNER_WALL), p(s1, INNER_WALL), p(s1, APRON_INNER), p(s0, APRON_INNER),
                        groundColor(p(s0, INNER_WALL)), groundColor(p(s1, INNER_WALL)), groundColor(p(s1, APRON_INNER)),
                        groundColor(p(s0, APRON_INNER)), up,
                        uvRect(s0 / GRASS_TILE, INNER_WALL / GRASS_TILE, s1 / GRASS_TILE, APRON_INNER / GRASS_TILE));
            }
            if ((!pitRow && !laneRow) || (pitRow && PitLane.pitWallAt(sMid))) {
                // inner retaining wall - along the pits this is the pit wall
                wall.quad(atY(p(s0, INNER_WALL), 0), atY(p(s1, INNER_WALL), 0),
                        atY(p(s1, INNER_WALL), INNER_WALL_HEIGHT), atY(p(s0, INNER_WALL), INNER_WALL_HEIGHT), WHITE, outward,
                        uvRect(u0, 1, u1, vTopInner));
                wall.quad(atY(p(s0, innerWallBack), INNER_WALL_HEIGHT), atY(p(s1, innerWallBack), INNER_WALL_HEIGHT),
                        atY(p(s1, INNER_WALL), INNER_WALL_HEIGHT), atY(p(s0, INNER_WALL), INNER_WALL_HEIGHT), gray(0.9f), up,
                        uvRect(u0, 0.02f, u1, 0.06f));
                wall.quad(atY(p(s0, innerWallBack), 0), atY(p(s1, innerWallBack), 0),
                        atY(p(s1, innerWallBack), INNER_WALL_HEIGHT), atY(p(s0, innerWallBack), INNER_WALL_HEIGHT),
                        pitRow ? WHITE : gray(0.85f), inward, uvRect(u0, 1, u1, vTopInner));
            }

            wall.quad(p(s0, ROAD_OUTER), p(s1, ROAD_OUTER), p(s1, ROAD_OUTER, OUTER_WALL_HEIGHT), p(s0, ROAD_OUTER, OUTER_WALL_HEIGHT),
                    WHITE, inward, uvRect(u0, 1, u1, 0));
            Vector3f t0 = p(s0, ROAD_OUTER, OUTER_WALL_HEIGHT), t1 = p(s1, ROAD_OUTER, OUTER_WALL_HEIGHT);
            Vector3f w0 = track.point(s0, wallOut, new Vector3f()), w1 = track.point(s1, wallOut, new Vector3f());
            w0.y = t0.y; w1.y = t1.y;
            wall.quad(t0, t1, w1, w0, gray(0.9f), up, uvRect(u0, 0.02f, u1, 0.08f));
            wall.quad(w0, w1, atY(w1, 0), atY(w0, 0), gray(0.85f), outward, uvRect(u0, 0, u1, 1));
        }

        // ---- start/finish line: painted chequered band across the asphalt (centred on s = 0) ----
        ColorRGBA checkBlack = new ColorRGBA(0.035f, 0.035f, 0.04f, 1);
        int checkCols = 31, checkRows = 3;                      // ~0.5 m squares over the 15.5 m road
        float square = (ROAD_OUTER - ROAD_INNER) / checkCols;
        float sStart = -checkRows * square / 2;
        for (int r = 0; r < checkRows; r++) {
            float s0 = sStart + r * square, s1 = s0 + square;
            for (int c = 0; c < checkCols; c++) {
                float d0 = ROAD_INNER + c * square, d1 = d0 + square;
                ColorRGBA col = ((r + c) % 2 == 0) ? PAINT : checkBlack;
                paint.quad(p(s0, d0, 0.005f), p(s1, d0, 0.005f), p(s1, d1, 0.005f), p(s0, d1, 0.005f), col, up,
                        uvRect(s0 / ASPHALT_TILE, d0 / ASPHALT_TILE, s1 / ASPHALT_TILE, d1 / ASPHALT_TILE));
            }
        }
        // thin white borders either side of the chequers
        for (float sEdge : new float[]{sStart - 0.12f, -sStart}) {
            paint.quad(p(sEdge, ROAD_INNER, 0.005f), p(sEdge + 0.12f, ROAD_INNER, 0.005f),
                    p(sEdge + 0.12f, ROAD_OUTER, 0.005f), p(sEdge, ROAD_OUTER, 0.005f), PAINT, up,
                    uvRect(sEdge / ASPHALT_TILE, ROAD_INNER / ASPHALT_TILE, (sEdge + 0.12f) / ASPHALT_TILE, ROAD_OUTER / ASPHALT_TILE));
        }
        paintPitLane(paint, step);
    }

    /** Painted markings of the pit complex (see {@link PitLane}). */
    private void paintPitLane(MeshBuilder paint, float step) {
        Vector3f up = Vector3f.UNIT_Y;
        float y = 0.005f;
        // divider lines separating the pit entry / exit lanes from the racing surface (on the apron)
        int rows = (int) Math.ceil(LENGTH / step);
        for (int i = 0; i < rows; i++) {
            float s0 = i * step, s1 = s0 + step;
            float a = PitLane.dividerD(s0), b = PitLane.dividerD(s1);
            if (Float.isNaN(a) || Float.isNaN(b)) continue;
            paint.quad(p(s0, a - 0.11f, y), p(s1, b - 0.11f, y), p(s1, b + 0.11f, y), p(s0, a + 0.11f, y), PAINT, up,
                    uvRect(s0 / ASPHALT_TILE, a / ASPHALT_TILE, s1 / ASPHALT_TILE, b / ASPHALT_TILE));
        }
        // pit exit lane edge lines through turns 1 and 2 (the outer one runs into the track's line)
        for (float s = PitLane.WALL_END + 50f; s < PitLane.LANE_END; s += 2f) {
            float e = Math.min(s + 2f, PitLane.LANE_END);
            float a = PitLane.laneOuter(s) - 0.15f, b = PitLane.laneOuter(e) - 0.15f;
            paint.quad(p(s, a - 0.1f, y), p(e, b - 0.1f, y), p(e, b + 0.1f, y), p(s, a + 0.1f, y), PAINT, up,
                    uvRect(s / ASPHALT_TILE, a / ASPHALT_TILE, e / ASPHALT_TILE, b / ASPHALT_TILE));
        }
        for (float s = PitLane.END; s < PitLane.LANE_END - 30f; s += 2f) {
            float e = s + 2f;
            float a = PitLane.laneInner(s) + 0.25f, b = PitLane.laneInner(e) + 0.25f;
            paint.quad(p(s, a - 0.1f, y), p(e, b - 0.1f, y), p(e, b + 0.1f, y), p(s, a + 0.1f, y), PAINT, up,
                    uvRect(s / ASPHALT_TILE, a / ASPHALT_TILE, e / ASPHALT_TILE, b / ASPHALT_TILE));
        }
        float first = PitLane.stallStart(0), last = PitLane.stallStart(PitLane.STALLS);
        // fast-lane / box line and the line behind the boxes, along the whole row of boxes
        for (float d : new float[]{PitLane.FAST_LANE_INNER, PitLane.BOX_INNER}) {
            stripeAlong(paint, first, last, d, 0.24f, PAINT);
        }
        // a box divider at every box boundary (34 lines for 33 boxes)
        for (int k = 0; k <= PitLane.STALLS; k++) {
            float s = PitLane.stallStart(k);
            stripeAcross(paint, s - 0.08f, s + 0.08f, PitLane.BOX_INNER, PitLane.FAST_LANE_INNER, PAINT);
        }
        // the player's box: solid yellow paint
        ColorRGBA yellow = new ColorRGBA(0.98f, 0.78f, 0.05f, 1f);
        float b0 = PitLane.stallStart(PitLane.PLAYER_STALL) + 0.25f, b1 = PitLane.stallStart(PitLane.PLAYER_STALL + 1) - 0.25f;
        for (float s = b0; s < b1; s += 2f) {
            stripeAcross(paint, s, Math.min(s + 2f, b1), PitLane.BOX_INNER + 0.25f, PitLane.FAST_LANE_INNER - 0.25f, yellow);
        }
        // pit-lane speed-limit lines across the lane at both ends of the pit wall
        for (float rel : new float[]{PitLane.WALL_START + 15f, PitLane.WALL_END - 15f}) {
            stripeAcross(paint, rel - 0.3f, rel + 0.3f, PitLane.BACK_D + 0.5f, PitLane.WALL_D - 0.5f, PAINT);
        }
    }

    /** Painted stripe of the given width centred on lateral offset d, from rel s0 to s1. */
    private void stripeAlong(MeshBuilder paint, float s0, float s1, float d, float width, ColorRGBA color) {
        for (float s = s0; s < s1; s += 2f) {
            float e = Math.min(s + 2f, s1);
            paint.quad(p(s, d - width / 2, 0.005f), p(e, d - width / 2, 0.005f), p(e, d + width / 2, 0.005f),
                    p(s, d + width / 2, 0.005f), color, Vector3f.UNIT_Y,
                    uvRect(s / ASPHALT_TILE, d / ASPHALT_TILE, e / ASPHALT_TILE, (d + width) / ASPHALT_TILE));
        }
    }

    /** Painted patch spanning rel s0..s1 and lateral d0..d1. */
    private void stripeAcross(MeshBuilder paint, float s0, float s1, float d0, float d1, ColorRGBA color) {
        paint.quad(p(s0, d0, 0.005f), p(s1, d0, 0.005f), p(s1, d1, 0.005f), p(s0, d1, 0.005f), color, Vector3f.UNIT_Y,
                uvRect(s0 / ASPHALT_TILE, d0 / ASPHALT_TILE, s1 / ASPHALT_TILE, d1 / ASPHALT_TILE));
    }

    private Mesh buildFenceMesh() {
        MeshBuilder mb = new MeshBuilder();
        float fenceD = ROAD_OUTER + 0.3f;
        int rows = (int) Math.ceil(LENGTH / 4f);
        float step = LENGTH / rows;
        Vector3f inward = new Vector3f();
        for (int i = 0; i < rows; i++) {
            float s0 = i * step, s1 = (i + 1) * step;
            track.right(s0, inward).negateLocal();
            mb.quad(p(s0, fenceD, OUTER_WALL_HEIGHT), p(s1, fenceD, OUTER_WALL_HEIGHT),
                    p(s1, fenceD, OUTER_WALL_HEIGHT + FENCE_HEIGHT), p(s0, fenceD, OUTER_WALL_HEIGHT + FENCE_HEIGHT),
                    WHITE, inward, uvRect(s0 / 0.5f, FENCE_HEIGHT / 0.5f, s1 / 0.5f, 0));
        }
        return mb.build();
    }

    private Mesh buildDecorations() {
        MeshBuilder mb = new MeshBuilder();
        Vector3f up = Vector3f.UNIT_Y;

        // catch-fence posts every 4 m + overhang arms + cables
        float fenceD = ROAD_OUTER + 0.3f;
        for (float s = 0; s < LENGTH - 1; s += 4f) {
            Vector3f base = p(s, fenceD + 0.08f, OUTER_WALL_HEIGHT);
            Vector3f fwd = track.forward(s, null), right = track.right(s, null);
            mb.box(base.add(0, FENCE_HEIGHT / 2, 0), fwd, up, right, 0.05f, FENCE_HEIGHT / 2, 0.05f, STEEL);
            Vector3f arm = base.add(0, FENCE_HEIGHT, 0).addLocal(right.mult(-0.45f));
            mb.box(arm, fwd, up, right, 0.03f, 0.03f, 0.5f, STEEL);
        }
        int rows = (int) Math.ceil(LENGTH / 4f);
        float step = LENGTH / rows;
        Vector3f inward = new Vector3f();
        for (int i = 0; i < rows; i++) {
            float s0 = i * step, s1 = (i + 1) * step;
            track.right(s0, inward).negateLocal();
            for (float h : new float[]{0.9f, 2.0f, 3.1f, FENCE_HEIGHT - 0.05f}) {
                mb.quad(p(s0, fenceD - 0.06f, OUTER_WALL_HEIGHT + h), p(s1, fenceD - 0.06f, OUTER_WALL_HEIGHT + h),
                        p(s1, fenceD - 0.06f, OUTER_WALL_HEIGHT + h + 0.025f), p(s0, fenceD - 0.06f, OUTER_WALL_HEIGHT + h + 0.025f),
                        STEEL, inward);
            }
        }

        // infield pylons every 50 m (taller red/white ones every 200 m)
        ColorRGBA yellow = new ColorRGBA(0.95f, 0.75f, 0.08f, 1);
        ColorRGBA black = new ColorRGBA(0.06f, 0.06f, 0.06f, 1);
        ColorRGBA red = new ColorRGBA(0.75f, 0.06f, 0.06f, 1);
        for (float s = 0; s < LENGTH - 1; s += 50f) {
            if (PitLane.inRange(s) || PitLane.inExitLane(s)) continue;
            Vector3f base = p(s, -16.5f, 0);
            Vector3f fwd = track.forward(s, null), right = track.right(s, null);
            boolean major = ((int) (s / 50f)) % 4 == 0;
            float h = major ? 2.6f : 1.2f;
            mb.box(base.add(0, h / 2, 0), fwd, up, right, 0.3f, h / 2, 0.3f, major ? red : (((int) (s / 50)) % 2 == 0 ? yellow : black));
            if (major) mb.box(base.add(0, h + 0.3f, 0), fwd, up, right, 0.32f, 0.3f, 0.32f, PAINT);
        }

        // pit stands (timing stands with a canopy) behind each of the 33 boxes; the player's is yellow
        ColorRGBA[] teamColours = {
                new ColorRGBA(0.75f, 0.05f, 0.06f, 1), new ColorRGBA(0.06f, 0.18f, 0.60f, 1),
                new ColorRGBA(0.90f, 0.42f, 0.04f, 1), new ColorRGBA(0.05f, 0.40f, 0.15f, 1),
                new ColorRGBA(0.10f, 0.10f, 0.12f, 1), new ColorRGBA(0.75f, 0.05f, 0.45f, 1),
                new ColorRGBA(0.00f, 0.45f, 0.50f, 1), new ColorRGBA(0.85f, 0.85f, 0.87f, 1)};
        ColorRGBA standGrey = new ColorRGBA(0.30f, 0.31f, 0.33f, 1);
        for (int k = 0; k < PitLane.STALLS; k++) {
            float sc = (PitLane.stallStart(k) + PitLane.stallStart(k + 1)) / 2f;
            Vector3f fwd = track.forward(sc, null), right = track.right(sc, null);
            Vector3f base = p(sc, -37.2f, 0);
            boolean mine = k == PitLane.PLAYER_STALL;
            mb.box(base.add(0, 1.1f, 0), fwd, up, right, 1.6f, 1.1f, 0.6f, standGrey);
            mb.box(base.add(0, 2.75f, 0), fwd, up, right, 2.0f, 0.08f, 1.1f,
                    mine ? new ColorRGBA(0.98f, 0.78f, 0.05f, 1) : teamColours[k % teamColours.length]);
            for (float side : new float[]{-1.8f, 1.8f}) {
                mb.box(base.add(fwd.mult(side)).addLocal(0, 1.35f, 0), fwd, up, right, 0.05f, 1.35f, 0.05f, STEEL);
            }
        }

        // turn-in boards behind the outer wall: 300 m (white), 200 m (yellow), 100 m (red)
        ColorRGBA[] boardCols = {PAINT, yellow, red};
        for (float ts : track.turnStarts()) {
            for (int k = 0; k < 3; k++) {
                float s = wrapS(ts - 300 + k * 100);
                Vector3f base = p(s, ROAD_OUTER + 4f, 0);
                base.y = track.height(s, ROAD_OUTER) + 0.2f;
                Vector3f fwd = track.forward(s, null), right = track.right(s, null);
                mb.box(base.add(0, 6.0f, 0), fwd, up, right, 0.15f, 1.6f, 1.6f, boardCols[k]);
                mb.box(base.add(0, 2.5f, 0), fwd, up, right, 0.1f, 2.5f, 0.1f, STEEL);
            }
        }
        return mb.build();
    }

    /**
     * The land around the track: a flat grid whose vertex colours come from {@link GroundShade}
     * (mown lawns with stripes, rough meadow, darker ground under the woods, large-scale variation
     * that hides the texture tiling). Fine 5 m cells around the track and the woods, coarse 100 m
     * cells out to 2 km. Tangents are written directly (flat ground, u along +x).
     */
    private Mesh buildGround() {
        Vector3f c = track.getCenter();
        float y = GROUND_Y, tile = 6f, half = 2000f, coarse = 100f, fine = 5f;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float s = 0; s < LENGTH; s += 10f) {
            Vector3f p = track.centerline(s, new Vector3f());
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }
        // fine region: the track plus 450 m, snapped to the coarse grid
        float x0 = snap(minX - 450f - c.x, coarse, false) + c.x, x1 = snap(maxX + 450f - c.x, coarse, true) + c.x;
        float z0 = snap(minZ - 450f - c.z, coarse, false) + c.z, z1 = snap(maxZ + 450f - c.z, coarse, true) + c.z;
        int nx = Math.round((x1 - x0) / fine), nz = Math.round((z1 - z0) / fine);
        int cn = Math.round(2 * half / coarse);
        java.util.List<float[]> coarseCells = new java.util.ArrayList<>();
        for (int i = 0; i < cn; i++) {
            for (int j = 0; j < cn; j++) {
                float cx0 = c.x - half + i * coarse, cz0 = c.z - half + j * coarse;
                boolean inside = cx0 >= x0 - 0.1f && cx0 + coarse <= x1 + 0.1f && cz0 >= z0 - 0.1f && cz0 + coarse <= z1 + 0.1f;
                if (!inside) coarseCells.add(new float[]{cx0, cz0});
            }
        }
        int verts = (nx + 1) * (nz + 1) + coarseCells.size() * 4;
        int tris = nx * nz * 2 + coarseCells.size() * 2;
        float[] pos = new float[verts * 3], nrm = new float[verts * 3], uv = new float[verts * 2],
                col = new float[verts * 4], tan = new float[verts * 4];
        int[] idx = new int[tris * 3];
        int v = 0;
        for (int j = 0; j <= nz; j++) {
            for (int i = 0; i <= nx; i++) {
                v = groundVertex(v, x0 + i * fine, z0 + j * fine, y, tile, pos, nrm, uv, col, tan);
            }
        }
        int t = 0;
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                int a = j * (nx + 1) + i, b = a + 1, d = a + nx + 1, e = d + 1;
                idx[t++] = a; idx[t++] = d; idx[t++] = b;
                idx[t++] = b; idx[t++] = d; idx[t++] = e;
            }
        }
        for (float[] cell : coarseCells) {
            int a = v;
            v = groundVertex(v, cell[0], cell[1], y, tile, pos, nrm, uv, col, tan);
            v = groundVertex(v, cell[0] + coarse, cell[1], y, tile, pos, nrm, uv, col, tan);
            v = groundVertex(v, cell[0], cell[1] + coarse, y, tile, pos, nrm, uv, col, tan);
            v = groundVertex(v, cell[0] + coarse, cell[1] + coarse, y, tile, pos, nrm, uv, col, tan);
            idx[t++] = a; idx[t++] = a + 2; idx[t++] = a + 1;
            idx[t++] = a + 1; idx[t++] = a + 2; idx[t++] = a + 3;
        }
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, pos);
        mesh.setBuffer(VertexBuffer.Type.Normal, 3, nrm);
        mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, uv);
        mesh.setBuffer(VertexBuffer.Type.Color, 4, col);
        mesh.setBuffer(VertexBuffer.Type.Tangent, 4, tan);
        mesh.setBuffer(VertexBuffer.Type.Index, 3, idx);
        mesh.updateBound();
        return mesh;
    }

    private int groundVertex(int v, float x, float z, float y, float tile,
                             float[] pos, float[] nrm, float[] uv, float[] col, float[] tan) {
        pos[v * 3] = x;
        pos[v * 3 + 1] = y;
        pos[v * 3 + 2] = z;
        nrm[v * 3 + 1] = 1f;
        uv[v * 2] = x / tile;
        uv[v * 2 + 1] = z / tile;
        ColorRGBA c = groundShade.color(x, z);
        col[v * 4] = c.r;
        col[v * 4 + 1] = c.g;
        col[v * 4 + 2] = c.b;
        col[v * 4 + 3] = 1f;
        tan[v * 4] = 1f;
        tan[v * 4 + 3] = -1f;
        return v + 1;
    }

    private static float snap(float value, float step, boolean up) {
        return (float) (up ? Math.ceil(value / step) : Math.floor(value / step)) * step;
    }

    private Mesh buildServiceRoads() {
        MeshBuilder mb = new MeshBuilder();
        Vector3f c = track.getCenter();
        float y = -0.03f;
        mb.quad(new Vector3f(c.x - 600, y, c.z - 5), new Vector3f(c.x + 600, y, c.z - 5),
                new Vector3f(c.x + 600, y, c.z + 5), new Vector3f(c.x - 600, y, c.z + 5), gray(0.9f), Vector3f.UNIT_Y,
                uvRect(0, 0, 400, 3.3f));
        mb.quad(new Vector3f(c.x - 5, y, c.z - 280), new Vector3f(c.x + 5, y, c.z - 280),
                new Vector3f(c.x + 5, y, c.z + 280), new Vector3f(c.x - 5, y, c.z + 280), gray(0.9f), Vector3f.UNIT_Y,
                uvRect(0, 0, 3.3f, 187));
        return mb.build();
    }
}
