package com.racingsim.track;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import jme3tools.optimize.GeometryBatchFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static com.racingsim.track.TrackSpline.*;

/**
 * Places the Blender-made trackside props ({@code blender/make_props.py}): covered grandstands
 * along both straights, the timing tower, hospitality tents, light towers and a few hundred
 * trees. Props are merged per material to keep the draw-call count low.
 */
public final class Scenery {

    private static final float MODULE = 40f;
    /** Leaf colour multipliers: natural, darker / bluer, lighter / yellower. */
    private static final ColorRGBA[] LEAF_TINTS = {
            new ColorRGBA(0.86f, 0.88f, 0.80f, 1f), new ColorRGBA(0.70f, 0.80f, 0.76f, 1f), new ColorRGBA(0.98f, 0.94f, 0.70f, 1f)};
    /** Trees are batched per square cell of this size (m) so off-screen woods are culled. */
    private static final float TREE_CELL = 250f;
    /** Distant tree lines: offsets from the centreline (m) and heights. */
    private static final float NEAR_TREELINE_D = 330f, NEAR_TREELINE_H = 22f;
    private static final float FAR_TREELINE_D = 560f, FAR_TREELINE_H = 30f;

    private final AssetManager assets;
    private final TrackSpline track;
    private final Map<String, Material> materials = new HashMap<>();
    private final Random rnd = new Random(1990);
    private final List<Vector3f> occupied = new ArrayList<>();

    public Scenery(AssetManager assets, TrackSpline track) {
        this.assets = assets;
        this.track = track;
        createMaterials();
    }

    private void createMaterials() {
        materials.put("Concrete", Pbr.textured(assets, "Textures/Track/wall_albedo.png", "Textures/Track/wall_normal.png",
                "Textures/Track/wall_rough.png", 0f, 1f));
        Material crowd = Pbr.material(assets, ColorRGBA.White, 0f, 0.9f);
        crowd.setTexture("BaseColorMap", Pbr.texture(assets, "Textures/Track/crowd_atlas.png", true));
        crowd.setFloat("AlphaDiscardThreshold", 0.5f);
        materials.put("Crowd", crowd);
        materials.put("Steel", Pbr.material(assets, new ColorRGBA(0.62f, 0.63f, 0.65f, 1), 0.9f, 0.35f));
        materials.put("Alu", Pbr.material(assets, new ColorRGBA(0.74f, 0.75f, 0.77f, 1), 0.85f, 0.42f));
        Material banner = Pbr.material(assets, ColorRGBA.White, 0f, 0.45f);
        banner.setTexture("BaseColorMap", BannerTexture.create());
        materials.put("Banner", banner);
        materials.put("Roof", Pbr.material(assets, new ColorRGBA(0.25f, 0.26f, 0.28f, 1), 0.4f, 0.55f));
        materials.put("Fascia", Pbr.material(assets, new ColorRGBA(0.07f, 0.20f, 0.58f, 1), 0.2f, 0.35f));
        materials.put("Glass", Pbr.material(assets, new ColorRGBA(0.10f, 0.13f, 0.16f, 1), 0.95f, 0.06f));
        materials.put("Paint", Pbr.material(assets, new ColorRGBA(0.92f, 0.92f, 0.92f, 1), 0f, 0.4f));
        Material screen = Pbr.material(assets, new ColorRGBA(0.1f, 0.1f, 0.12f, 1), 0f, 0.3f);
        screen.setColor("Emissive", new ColorRGBA(0.25f, 0.45f, 0.9f, 1));
        materials.put("Screen", screen);
        Material lamp = Pbr.material(assets, new ColorRGBA(1f, 0.95f, 0.85f, 1), 0f, 0.2f);
        lamp.setColor("Emissive", new ColorRGBA(1f, 0.92f, 0.75f, 1));
        materials.put("Lamp", lamp);
        materials.put("Bark", Pbr.textured(assets, "Textures/Props/bark_albedo.png", "Textures/Props/bark_normal.png",
                null, 0f, 0.92f));
        // leaf cards (blender/make_foliage.py): alpha-tested atlas, two-sided, vertex-colour AO;
        // three tints so neighbouring trees differ
        for (int i = 0; i < LEAF_TINTS.length; i++) {
            Material leaves = Pbr.material(assets, LEAF_TINTS[i], 0f, 0.82f);
            leaves.setTexture("BaseColorMap", Pbr.texture(assets, "Textures/Props/leaves_atlas.png", true));
            leaves.setFloat("AlphaDiscardThreshold", 0.42f);
            leaves.setBoolean("UseVertexColor", true);
            leaves.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            materials.put(i == 0 ? "Leaves" : "Leaves" + i, leaves);
        }
        Material canvas = Pbr.material(assets, new ColorRGBA(0.95f, 0.95f, 0.93f, 1), 0f, 0.8f);
        canvas.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        materials.put("Canvas", canvas);
        Material crowdSides = materials.get("Concrete").clone();
        crowdSides.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        materials.put("ConcreteTwoSided", crowdSides);
    }

    private Spatial load(String file) {
        Spatial s = assets.loadModel("Models/Props/" + file);
        s.depthFirstTraversal(sp -> {
            if (sp instanceof Geometry g) {
                String name = partName(g);
                String key = name.contains("_") ? name.substring(0, name.indexOf('_')) : name;
                Material m = materials.get(key);
                if (key.equals("Concrete") || key.equals("Crowd") || key.equals("Roof") || key.equals("Banner")) {
                    // single-sided quads in the grandstand
                    m = key.equals("Concrete") ? materials.get("ConcreteTwoSided") : m;
                    m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
                }
                g.setMaterial(m != null ? m : materials.get("Paint"));
            }
        });
        return s;
    }

    private static String partName(Geometry g) {
        Material m = g.getMaterial();
        if (m != null && m.getName() != null && !m.getName().isEmpty()) return m.getName();
        Spatial s = g;
        while (s != null) {
            if (s.getName() != null && s.getName().contains("_")) return s.getName();
            s = s.getParent();
        }
        return g.getName();
    }

    /** @param facesTrack true: the model's +Z (Blender -Y) side faces the racing surface */
    private Spatial place(Spatial template, float s, float d, boolean outside, float yawJitter, float scale) {
        Spatial sp = template.clone(false);
        Vector3f f = track.forward(s, null), r = track.right(s, null);
        Quaternion q = new Quaternion();
        if (outside) q.fromAxes(f.negate(), Vector3f.UNIT_Y, r.negate());
        else q.fromAxes(f, Vector3f.UNIT_Y, r);
        if (yawJitter != 0) q = new Quaternion().fromAngleAxis(yawJitter, Vector3f.UNIT_Y).mult(q);
        sp.setLocalRotation(q);
        Vector3f pos = track.centerline(s, new Vector3f()).addLocal(r.mult(d));
        pos.y = 0;
        sp.setLocalTranslation(pos);
        sp.setLocalScale(scale);
        occupied.add(pos);
        return sp;
    }

    /**
     * Places a straight outside-facing module along the chord between sLo and sHi at offset d
     * (in a turn), stretched a touch so neighbouring modules close up at the back.
     */
    private Spatial placeChord(Spatial template, float sLo, float sHi, float d) {
        Spatial sp = template.clone(false);
        Vector3f hi = track.centerline(sHi, new Vector3f()).addLocal(track.right(sHi, null).multLocal(d));
        Vector3f lo = track.centerline(sLo, new Vector3f()).addLocal(track.right(sLo, null).multLocal(d));
        hi.y = lo.y = 0;
        Vector3f x = lo.subtract(hi);
        float len = x.length();
        x.divideLocal(len);
        Vector3f z = x.cross(Vector3f.UNIT_Y);
        sp.setLocalRotation(new Quaternion().fromAxes(x, Vector3f.UNIT_Y, z));
        sp.setLocalTranslation(hi);
        sp.setLocalScale(len / MODULE * 1.04f, 1f, 1f);
        occupied.add(hi.add(lo).multLocal(0.5f));
        return sp;
    }

    public Node build() {
        Node root = new Node("Scenery");
        Node stands = new Node("Stands");
        Node trees = new Node("Trees");

        Spatial grandstand = load("grandstand.glb");
        // front straight: the whole straight, so the starting grid (which begins where the straight
        // does) always has stands behind it; back straight: the middle section, outside the wall
        for (float s = -STRAIGHT / 2; s + MODULE <= STRAIGHT / 2 + 1f; s += MODULE) {
            stands.attachChild(place(grandstand, wrapS(s + MODULE), 14f, true, 0, 1f));
        }
        // ...and a little way round the turns at either end, so the stands curve away with the wall
        // instead of stopping dead where the straight ends (the menu camera sees that end)
        for (int i = 1; i <= 3; i++) {
            float sIn = -STRAIGHT / 2 - i * MODULE;
            stands.attachChild(placeChord(grandstand, wrapS(sIn), wrapS(sIn + MODULE), 14f));
            float sOut = STRAIGHT / 2 + (i - 1) * MODULE;
            stands.attachChild(placeChord(grandstand, sOut, sOut + MODULE, 14f));
        }
        float backStart = STRAIGHT / 2 + 2 * TURN + CHUTE;
        for (float s = backStart + 160f; s < backStart + STRAIGHT - 160f; s += MODULE) {
            stands.attachChild(place(grandstand, s + MODULE, 14f, true, 0, 1f));
        }

        stands.attachChild(place(load("timing_tower.glb"), 70f, -62f, false, 0, 1f));
        Spatial tent = load("tent.glb");
        for (int i = 0; i < 6; i++) {
            stands.attachChild(place(tent, 180f + i * 18f, -78f - (i % 2) * 14f, false, 0, 1f));
        }
        Spatial tower = load("light_tower.glb");
        for (float s = 250f; s < LENGTH; s += 420f) {
            stands.attachChild(place(tower, s, track.inTurn(s) ? 34f : 32f, true, 0, 1f));
            float sIn = s + 210f;
            stands.attachChild(place(tower, sIn, Math.min(-32f, PitLane.innerLimit(sIn) - 12f), false, 0, 1f));
        }

        int planted = plantTrees(trees);

        MikktspaceTangentGenerator.generate(stands);
        GeometryBatchFactory.optimize(stands);
        for (Spatial cell : new ArrayList<>(trees.getChildren())) {
            MikktspaceTangentGenerator.generate(cell);                 // bark is normal-mapped
            GeometryBatchFactory.optimize((Node) cell);
        }
        System.out.println("Scenery: " + planted + " trees and shrubs in " + trees.getQuantity() + " cells");
        stands.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        trees.setShadowMode(RenderQueue.ShadowMode.Cast);
        root.attachChild(stands);
        root.attachChild(trees);
        root.attachChild(treeline(NEAR_TREELINE_D, NEAR_TREELINE_H, null, 0f));
        root.attachChild(treeline(FAR_TREELINE_D, FAR_TREELINE_H, new ColorRGBA(0.035f, 0.045f, 0.06f, 1f), 0.37f));
        return root;
    }

    // ------------------------------------------------------------------ trees
    private Spatial[][] treeTemplates;          // [species][tint]
    private static final int OAK = 0, MAPLE = 1, SPRUCE = 2, YOUNG = 3, SHRUB = 4;

    private Spatial treeTemplate(int species, int tint) {
        if (treeTemplates == null) {
            String[] files = {"tree_0.glb", "tree_1.glb", "tree_2.glb", "tree_3.glb", "shrub_0.glb"};
            treeTemplates = new Spatial[files.length][LEAF_TINTS.length];
            for (int i = 0; i < files.length; i++) {
                for (int t = 0; t < LEAF_TINTS.length; t++) {
                    Spatial sp = load(files[i]);
                    Material leaves = materials.get(t == 0 ? "Leaves" : "Leaves" + t);
                    sp.depthFirstTraversal(x -> {
                        if (x instanceof Geometry g && partName(g).startsWith("Leaves")) g.setMaterial(leaves);
                    });
                    treeTemplates[i][t] = sp;
                }
            }
        }
        return treeTemplates[species][tint];
    }

    /**
     * Woods, clumps and lone trees, all kept clear of the track, the buildings and the service
     * roads:
     * <ul>
     *   <li>Outside the track: woods where {@link GroundShade#woodDensity} says so (patchy from
     *       ~100 m out, thicker further away), mixed oak / maple / spruce / young trees, with shrubs
     *       along the wood edges; a few lone trees on the lawns.</li>
     *   <li>Infield: parkland - clumps of broadleaf trees, lone oaks, shrub groups.</li>
     *   <li>Beyond ~300 m the two tree-line rings ({@link #treeline}) close off the horizon.</li>
     * </ul>
     * @return number of trees and shrubs planted
     */
    private int plantTrees(Node trees) {
        GroundShade ground = new GroundShade(track);
        java.util.Map<Long, Node> cells = new HashMap<>();
        int planted = 0;
        Vector3f c = track.getCenter();
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float s = 0; s < LENGTH; s += 10f) {
            Vector3f p = track.centerline(s, new Vector3f());
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }
        // ---- woods outside the track (jittered grid) ----
        float step = 12.5f;
        for (float x = minX - 300f; x < maxX + 300f; x += step) {
            for (float z = minZ - 300f; z < maxZ + 300f; z += step) {
                float px = x + (rnd.nextFloat() - 0.5f) * step * 0.8f, pz = z + (rnd.nextFloat() - 0.5f) * step * 0.8f;
                float d = ground.offsetAt(px, pz);
                if (d < 90f || d > NEAR_TREELINE_D - 25f) continue;
                float density = ground.woodDensity(px, pz, d);
                if (rnd.nextFloat() > density * 0.75f) continue;
                Vector3f pos = new Vector3f(px, 0, pz);
                if (tooClose(pos, 18f) || onServiceRoad(pos, c)) continue;
                float u = rnd.nextFloat();
                int species = u < 0.32f ? OAK : u < 0.60f ? MAPLE : u < 0.86f ? SPRUCE : YOUNG;
                planted += plant(cells, species, pos, 0.8f + rnd.nextFloat() * 0.45f);
                // undergrowth where the wood thins out towards the lawn
                if (density < 0.6f && rnd.nextFloat() < 0.55f) {
                    Vector3f toTrack = track.centerline(track.project(pos, null).s, new Vector3f()).subtractLocal(pos);
                    toTrack.y = 0;
                    toTrack.normalizeLocal().multLocal(4f + rnd.nextFloat() * 3f);
                    planted += plant(cells, SHRUB, pos.add(toTrack).addLocal(rnd.nextFloat() * 3 - 1.5f, 0, rnd.nextFloat() * 3 - 1.5f),
                            0.8f + rnd.nextFloat() * 0.6f);
                }
            }
        }
        // ---- lone trees on the outer lawns ----
        for (int i = 0; i < 70; i++) {
            float s = rnd.nextFloat() * LENGTH, d = 60f + rnd.nextFloat() * 50f;
            Vector3f pos = track.centerline(s, new Vector3f()).addLocal(track.right(s, null).multLocal(d));
            pos.y = 0;
            if (tooClose(pos, 22f) || onServiceRoad(pos, c)) continue;
            planted += plant(cells, rnd.nextFloat() < 0.6f ? OAK : MAPLE, pos, 1.0f + rnd.nextFloat() * 0.35f);
        }
        // ---- infield parkland: clumps, lone oaks, shrub groups ----
        for (int k = 0; k < 34; k++) {
            Vector3f centre = infieldPoint(c, 45f);
            if (centre == null) continue;
            int n = 4 + rnd.nextInt(12);
            float radius = 10f + n * 1.6f;
            for (int i = 0; i < n; i++) {
                float a = rnd.nextFloat() * FastMath.TWO_PI, rr = radius * FastMath.sqrt(rnd.nextFloat());
                Vector3f pos = centre.add(FastMath.cos(a) * rr, 0, FastMath.sin(a) * rr);
                if (!infieldClear(pos, c, 40f)) continue;
                float u = rnd.nextFloat();
                planted += plant(cells, u < 0.45f ? OAK : u < 0.8f ? MAPLE : u < 0.9f ? YOUNG : SPRUCE, pos,
                        0.85f + rnd.nextFloat() * 0.4f);
            }
            for (int i = 0; i < 3 + rnd.nextInt(5); i++) {          // shrubs around the clump
                float a = rnd.nextFloat() * FastMath.TWO_PI;
                Vector3f pos = centre.add(FastMath.cos(a) * (radius + 4f), 0, FastMath.sin(a) * (radius + 4f));
                if (infieldClear(pos, c, 35f)) planted += plant(cells, SHRUB, pos, 0.8f + rnd.nextFloat() * 0.6f);
            }
        }
        for (int i = 0; i < 45; i++) {
            Vector3f pos = infieldPoint(c, 45f);
            if (pos != null) planted += plant(cells, rnd.nextFloat() < 0.7f ? OAK : MAPLE, pos, 1.1f + rnd.nextFloat() * 0.35f);
        }
        for (Node cell : cells.values()) trees.attachChild(cell);
        return planted;
    }

    private int plant(java.util.Map<Long, Node> cells, int species, Vector3f pos, float scale) {
        Spatial t = treeTemplate(species, rnd.nextInt(LEAF_TINTS.length)).clone(false);
        t.setLocalTranslation(pos.x, -0.05f, pos.z);
        t.setLocalRotation(new Quaternion().fromAngleAxis(rnd.nextFloat() * FastMath.TWO_PI, Vector3f.UNIT_Y));
        t.setLocalScale(scale, scale * (0.9f + rnd.nextFloat() * 0.2f), scale);
        long key = ((long) Math.floor(pos.x / TREE_CELL) << 32) ^ ((long) Math.floor(pos.z / TREE_CELL) & 0xffffffffL);
        cells.computeIfAbsent(key, k -> new Node("TreeCell")).attachChild(t);
        return 1;
    }

    /** A random free infield point at least {@code margin} m inside the track's infield limit. */
    private Vector3f infieldPoint(Vector3f c, float margin) {
        for (int attempt = 0; attempt < 40; attempt++) {
            float s = rnd.nextFloat() * LENGTH;
            float d = -(margin + rnd.nextFloat() * 330f);
            Vector3f pos = track.centerline(s, new Vector3f()).addLocal(track.right(s, null).multLocal(d));
            pos.y = 0;
            if (infieldClear(pos, c, margin)) return pos;
        }
        return null;
    }

    private boolean infieldClear(Vector3f pos, Vector3f c, float margin) {
        TrackPos tp = track.project(pos, null);
        return tp.d < PitLane.innerLimit(tp.s) - margin && !tooClose(pos, 20f) && !onServiceRoad(pos, c);
    }

    /** The infield service roads (TrackBuilder.buildServiceRoads): a cross through the centre. */
    private static boolean onServiceRoad(Vector3f p, Vector3f c) {
        float dx = Math.abs(p.x - c.x), dz = Math.abs(p.z - c.z);
        return (dz < 12f && dx < 610f) || (dx < 12f && dz < 290f);
    }

    /**
     * A distant wood edge: a vertical band following the track at offset {@code d}, textured with
     * the tree-line silhouette (alpha-tested). The far ring gets a little haze (emissive).
     */
    private Geometry treeline(float d, float height, ColorRGBA haze, float uShift) {
        MeshBuilder mb = new MeshBuilder();
        float step = 6f, along = 0f, texWidth = height * 4f;           // the texture is 4:1
        Vector3f prev = null;
        for (float s = 0; s <= LENGTH + 0.01f; s += step) {
            Vector3f p = track.centerline(s, new Vector3f()).addLocal(track.right(s, null).multLocal(d));
            p.y = -0.3f;
            if (prev != null) {
                float a0 = along / texWidth + uShift;
                along += prev.distance(p);
                float a1 = along / texWidth + uShift;
                Vector3f inward = track.right(s, null).negateLocal();
                mb.quad(prev, p, p.add(0, height, 0), prev.add(0, height, 0), ColorRGBA.White, inward,
                        new float[]{a0, 1f, a1, 1f, a1, 0f, a0, 0f});   // PNG row 0 (top) is v = 0
            }
            prev = p;
        }
        Material m = Pbr.material(assets, ColorRGBA.White, 0f, 0.95f);
        m.setTexture("BaseColorMap", Pbr.texture(assets, "Textures/Props/treeline.png", true));
        m.setFloat("AlphaDiscardThreshold", 0.5f);
        m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        if (haze != null) m.setColor("Emissive", haze);
        Geometry g = new Geometry("TreeLine", mb.build());
        g.setMaterial(m);
        g.setShadowMode(RenderQueue.ShadowMode.Off);
        return g;
    }

    private boolean tooClose(Vector3f p, float min) {
        for (Vector3f o : occupied) {
            if (o.distanceSquared(p) < min * min) return true;
        }
        return false;
    }

    private boolean clearOfTrack(Vector3f p) {
        TrackPos tp = track.project(p, null);
        return tp.d < PitLane.innerLimit(tp.s) - 20f || tp.d > ROAD_OUTER + 40f;
    }
}
