package com.racingsim.track;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The track's geometry: the 2.5-mile rectangular oval, or the 4.352-mile road course (see
 * {@link TrackLayout}). One track is loaded at a time, chosen with {@link #selectLayout} before
 * the track is built; {@link #LENGTH}, the road edges and the walls below are that track's.
 * <p>
 * Track coordinates:
 * <ul>
 *   <li>{@code s} - longitudinal progress along the centerline, 0..{@link #LENGTH} meters,
 *       0 = start/finish line.</li>
 *   <li>{@code d} - horizontal lateral offset from the centerline, positive = the driver's right
 *       (on the oval: outward, toward the outer wall, since all its turns are left-handers).</li>
 * </ul>
 * World frame: Y up, the centerline lies in the XZ plane; the racing surface height is
 * given by {@link #height(float, float)} (on the oval the banking pivots about the inner edge of
 * the road; the road course is flat across and climbs and falls along the lap).
 * <p>
 * The road course is sampled every metre: centerline, heading, curvature, elevation (a monotone
 * cubic through key heights), the walls (barriers) on each side and the kerbs; positions between
 * samples are interpolated, and {@link #project} uses a spatial grid of the samples. (The Unreal
 * port's FTrackSpline, whose (x, y, z) is this (x, z, y).)
 */
public final class TrackSpline {

    public static final float MILE = 1609.344f;
    public static final float STRAIGHT = MILE * 5f / 8f;   // 1005.84 m
    public static final float CHUTE = MILE / 8f;            //  201.17 m
    public static final float TURN = MILE / 4f;             //  402.34 m
    public static final float OVAL_LENGTH = 2 * STRAIGHT + 2 * CHUTE + 4 * TURN; // 4023.36 m
    public static final float TURN_RADIUS = TURN / FastMath.HALF_PI;             // ~256 m
    /** The road course: 4.352 miles, clockwise, 102.2 m from its lowest point (y = 0) to its highest. */
    public static final float ROAD_LENGTH = 7004f;
    public static final float ROAD_HALF_WIDTH = 6.5f;
    /** Road course kerbs: painted, raised a little, as grippy as the road. */
    public static final float KERB_WIDTH = 1.2f;
    /** Road course barriers. */
    public static final float BARRIER_HEIGHT = 1.0f;

    private static TrackLayout layout = TrackLayout.OVAL;

    /** Lap length (m) of the current track. */
    public static float LENGTH = OVAL_LENGTH;

    public static final float BANK_ANGLE = 9.2f * FastMath.DEG_TO_RAD;
    /** Length over which banking transitions in/out around each turn boundary. */
    public static final float BANK_RAMP = 80f;

    // Cross-section of the current track (lateral offsets, meters): the oval's values; the road
    // course has no apron and its walls follow the track (see minD / maxD)
    public static float INNER_WALL = -18f;
    public static float APRON_INNER = -15f;
    public static float ROAD_INNER = -7.5f;
    public static float ROAD_OUTER = 8.0f;
    public static final float OUTER_WALL_THICKNESS = 0.6f;
    public static final float OUTER_WALL_HEIGHT = 1.2f;
    public static final float INNER_WALL_HEIGHT = 1.0f;

    /** A road-course turn: tighter than this radius (m). */
    private static final float ROAD_TURN_RADIUS = 600f;

    /**
     * Chooses the track to race on. Call before creating the {@link TrackSpline} and anything
     * built on it (track meshes, racing line, speed profiles, cars: their gearing depends on it).
     */
    public static void selectLayout(TrackLayout l) {
        layout = l;
        if (l == TrackLayout.OVAL) {
            LENGTH = OVAL_LENGTH;
            INNER_WALL = -18f;
            APRON_INNER = -15f;
            ROAD_INNER = -7.5f;
            ROAD_OUTER = 8.0f;
        } else {
            LENGTH = ROAD_LENGTH;
            ROAD_INNER = -ROAD_HALF_WIDTH;
            ROAD_OUTER = ROAD_HALF_WIDTH;
            APRON_INNER = ROAD_INNER;
            INNER_WALL = ROAD_INNER - 8f;
        }
    }

    public static TrackLayout layout() { return layout; }
    public static boolean isOval() { return layout == TrackLayout.OVAL; }
    public static boolean isRoadCourse() { return !isOval(); }

    /** Result of projecting a world position onto the track. */
    public static final class TrackPos {
        public float s;
        public float d;
    }

    private abstract static class Segment {
        float startS;
        float length;
        abstract void point(float u, float d, Vector3f out);   // y untouched
        abstract float heading(float u);
        /** Writes {u, d} (u may fall outside [0,length]). */
        abstract void project(float x, float z, float[] ud);
        abstract float curvature();
    }

    private static final class Line extends Segment {
        final float x0, z0, psi, fx, fz, rx, rz;
        Line(float x0, float z0, float psi, float length) {
            this.x0 = x0; this.z0 = z0; this.psi = psi; this.length = length;
            fx = FastMath.cos(psi); fz = FastMath.sin(psi);
            rx = -fz; rz = fx;
        }
        @Override void point(float u, float d, Vector3f out) {
            out.x = x0 + fx * u + rx * d;
            out.z = z0 + fz * u + rz * d;
        }
        @Override float heading(float u) { return psi; }
        @Override void project(float x, float z, float[] ud) {
            float px = x - x0, pz = z - z0;
            ud[0] = px * fx + pz * fz;
            ud[1] = px * rx + pz * rz;
        }
        @Override float curvature() { return 0f; }
    }

    /** Left-hand circular arc (heading decreases with u). */
    private static final class Arc extends Segment {
        final float cx, cz, r, psi0;
        Arc(float startX, float startZ, float psi0, float r, float length) {
            this.psi0 = psi0; this.r = r; this.length = length;
            // center lies to the left: C = P - right * R
            float rx = -FastMath.sin(psi0), rz = FastMath.cos(psi0);
            cx = startX - rx * r;
            cz = startZ - rz * r;
        }
        @Override void point(float u, float d, Vector3f out) {
            float psi = psi0 - u / r;
            float rx = -FastMath.sin(psi), rz = FastMath.cos(psi);
            out.x = cx + rx * (r + d);
            out.z = cz + rz * (r + d);
        }
        @Override float heading(float u) { return psi0 - u / r; }
        @Override void project(float x, float z, float[] ud) {
            float vx = x - cx, vz = z - cz;
            float dist = FastMath.sqrt(vx * vx + vz * vz);
            float psi = FastMath.atan2(-vx, vz);
            // angle traveled from the arc start, centerd on the arc's mid-point so that
            // positions beyond either end clamp to the nearest end
            float sweep = length / r;
            float delta = wrapAngle(psi0 - psi - sweep / 2) + sweep / 2;
            ud[0] = delta * r;
            ud[1] = dist - r;
        }
        @Override float curvature() { return 1f / r; }
    }

    private final List<Segment> segments = new ArrayList<>();
    private final List<Segment> arcs = new ArrayList<>();
    private final Vector3f center = new Vector3f();
    /** The layout this instance was built for. */
    private final boolean oval;

    // ---- road course: samples every ds metres ----
    private int n;
    private float ds = 1f;
    private double[] px, pz;          // centerline
    private float[] psi;              // heading, unwrapped (psi[n] would be psi[0] + total turning)
    private float psiWrap;            // total turning over the lap
    private float[] kappa;            // signed curvature, + = left
    private float[] elev, slope;
    private float[] wallLeft;         // d of the left wall (negative)
    private float[] wallRight;        // d of the right wall
    private float[] toTurn;           // distance to the next turn (0 in one)
    private float[] toExit;           // distance to the end of this / the next turn
    private byte[] turnDir;
    private byte[] kerbs;             // bit 0 left, bit 1 right
    private float[] corners;          // where each turn starts
    // spatial grid over the samples (compressed rows)
    private float gridX0, gridZ0;
    private static final float GRID_CELL = 20f;
    private int gridNx, gridNz;
    private int[] cellStart, cellItems;

    /** Builds the track chosen with {@link #selectLayout} (the oval by default). */
    public TrackSpline() {
        selectLayout(layout);
        oval = isOval();
        if (oval) buildOval();
        else buildRoadCourse();
    }

    private void buildOval() {
        float[] lens = {STRAIGHT / 2, TURN, CHUTE, TURN, STRAIGHT, TURN, CHUTE, TURN, STRAIGHT / 2};
        boolean[] isArc = {false, true, false, true, false, true, false, true, false};
        float x = 0, z = 0, psi = 0, s = 0;
        Vector3f tmp = new Vector3f();
        for (int i = 0; i < lens.length; i++) {
            Segment seg = isArc[i] ? new Arc(x, z, psi, TURN_RADIUS, lens[i]) : new Line(x, z, psi, lens[i]);
            seg.startS = s;
            segments.add(seg);
            if (isArc[i]) arcs.add(seg);
            seg.point(seg.length, 0, tmp);
            x = tmp.x; z = tmp.z;
            psi = seg.heading(seg.length);
            s += seg.length;
        }
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float ss = 0; ss < LENGTH; ss += 10f) {
            centerline(ss, tmp);
            minX = Math.min(minX, tmp.x); maxX = Math.max(maxX, tmp.x);
            minZ = Math.min(minZ, tmp.z); maxZ = Math.max(maxZ, tmp.z);
        }
        center.set((minX + maxX) / 2, 0, (minZ + maxZ) / 2);
    }

    /** Geometric center of the track (y = 0). */
    public Vector3f getCenter() { return center.clone(); }

    public static float wrapAngle(float a) {
        while (a > FastMath.PI) a -= FastMath.TWO_PI;
        while (a <= -FastMath.PI) a += FastMath.TWO_PI;
        return a;
    }

    public static float wrapS(float s) {
        s %= LENGTH;
        return s < 0 ? s + LENGTH : s;
    }

    /** Signed shortest longitudinal distance from a to b (positive when b is ahead of a). */
    public static float deltaS(float a, float b) {
        float ds = (b - a) % LENGTH;
        if (ds > LENGTH / 2) ds -= LENGTH;
        if (ds < -LENGTH / 2) ds += LENGTH;
        return ds;
    }

    private Segment segmentAt(float s) {
        s = wrapS(s);
        for (Segment seg : segments) {
            if (s < seg.startS + seg.length) return seg;
        }
        return segments.get(segments.size() - 1);
    }

    /** Centerline point (y = 0). */
    public Vector3f centerline(float s, Vector3f out) {
        return point(s, 0, out).setY(0);
    }

    /** World position for (s, d) with y on the racing surface. */
    public Vector3f point(float s, float d, Vector3f out) {
        if (out == null) out = new Vector3f();
        s = wrapS(s);
        if (!oval) {
            int i0 = locateI0(s), i1 = (i0 + 1) % n;
            float t = locateT(s, i0);
            float h = heading(s);
            float rx = -FastMath.sin(h), rz = FastMath.cos(h);
            out.x = (float) (px[i0] + (px[i1] - px[i0]) * t) + rx * d;
            out.z = (float) (pz[i0] + (pz[i1] - pz[i0]) * t) + rz * d;
            out.y = height(s, d);
            return out;
        }
        Segment seg = segmentAt(s);
        seg.point(s - seg.startS, d, out);
        out.y = height(s, d);
        return out;
    }

    public float heading(float s) {
        s = wrapS(s);
        if (!oval) {
            int i0 = locateI0(s), i1 = (i0 + 1) % n;
            float t = locateT(s, i0);
            float p1 = i1 == 0 ? psi[0] + psiWrap : psi[i1];
            return psi[i0] + (p1 - psi[i0]) * t;
        }
        Segment seg = segmentAt(s);
        return seg.heading(s - seg.startS);
    }

    /** Unit forward (direction of travel) vector, horizontal. */
    public Vector3f forward(float s, Vector3f out) {
        float psi = heading(s);
        return (out == null ? new Vector3f() : out).set(FastMath.cos(psi), 0, FastMath.sin(psi));
    }

    /** Unit right-hand vector (outward on the oval), horizontal. */
    public Vector3f right(float s, Vector3f out) {
        float psi = heading(s);
        return (out == null ? new Vector3f() : out).set(-FastMath.sin(psi), 0, FastMath.cos(psi));
    }

    /** Signed curvature of the centerline at s (positive = left turn). */
    public float curvature(float s) {
        if (!oval) return sampled(kappa, s);
        return segmentAt(s).curvature();
    }

    /** Turning radius at lateral offset d (infinite on straights). */
    public float radius(float s, float d) {
        float k = curvature(s);
        if (oval) return k == 0 ? Float.POSITIVE_INFINITY : (1f / k) + d;
        if (Math.abs(k) < 1e-6f) return Float.POSITIVE_INFINITY;
        // d grows to the right: away from the center of a left turn, toward that of a right turn
        return Math.max(1f / Math.abs(k) + (k > 0 ? d : -d), 1f);
    }

    public boolean inTurn(float s) {
        if (!oval) return Math.abs(curvature(s)) > 1f / ROAD_TURN_RADIUS;
        return curvature(s) != 0;
    }

    /** Distance from s to the start of the next turn (0 if already in a turn). */
    public float distanceToNextTurn(float s) {
        s = wrapS(s);
        if (!oval) return sampled(toTurn, s);
        if (inTurn(s)) return 0;
        float best = Float.MAX_VALUE;
        for (Segment a : arcs) {
            best = Math.min(best, wrapS(a.startS - s));
        }
        return best;
    }

    /** Distance to the end of the turn we are in, or else of the next one. */
    public float distanceToTurnExit(float s) {
        if (!oval) return sampled(toExit, s);
        for (float start : turnStarts()) {
            float into = deltaS(start, s);
            if (into >= 0f && into < TURN) return TURN - into;
        }
        return distanceToNextTurn(s) + TURN;
    }

    /** +1 when the turn we are in (or the next one) is a left-hander, -1 a right-hander. */
    public float turnDirection(float s) {
        if (oval) return 1f;
        s = wrapS(s);
        int i0 = locateI0(s);
        return turnDir[locateT(s, i0) < 0.5f ? i0 : (i0 + 1) % n];
    }

    /** Turn start positions (s) - used by the AI racing line. */
    public float[] turnStarts() {
        if (!oval) return corners.clone();
        float[] r = new float[arcs.size()];
        for (int i = 0; i < r.length; i++) r[i] = arcs.get(i).startS;
        return r;
    }

    /** The walls: the lowest d a car can reach at s (on the oval: the inner wall or the pit lane). */
    public float minD(float s) {
        if (oval) return PitLane.innerLimit(s);
        return sampled(wallLeft, s);
    }

    /** The walls: the highest d a car can reach at s. */
    public float maxD(float s) {
        if (oval) return ROAD_OUTER;
        return sampled(wallRight, s);
    }

    /** A kerb beside the road at s on the right (d &gt; 0) or left side (road course only). */
    public boolean kerbAt(float s, boolean right) {
        if (oval) return false;
        s = wrapS(s);
        int i0 = locateI0(s);
        return (kerbs[locateT(s, i0) < 0.5f ? i0 : (i0 + 1) % n] & (right ? 2 : 1)) != 0;
    }

    /** Elevation of the centerline (m; 0 on the oval). */
    public float elevation(float s) {
        return oval ? 0f : sampled(elev, s);
    }

    /** Slope of the centerline (rise per metre along the lap). */
    public float grade(float s) {
        return oval ? 0f : sampled(slope, s);
    }

    /** Under or beyond a wall (within {@code margin} of it). */
    public boolean beyondWalls(float s, float d, float margin) {
        if (oval) return d > ROAD_OUTER - margin || d < APRON_INNER;
        return d > maxD(s) - margin || d < minD(s) + margin;
    }

    /** Banking angle (radians) at s, smoothly ramped across turn boundaries (0 on the road course). */
    public float bank(float s) {
        if (!oval) return 0f;
        s = wrapS(s);
        float w = 0;
        for (Segment a : arcs) {
            float in = smooth((s - (a.startS - BANK_RAMP / 2)) / BANK_RAMP);
            float out = smooth(((a.startS + a.length + BANK_RAMP / 2) - s) / BANK_RAMP);
            w = Math.max(w, Math.min(in, out));
        }
        return BANK_ANGLE * w;
    }

    public static float smooth(float x) {
        x = FastMath.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    /**
     * Height of the racing surface: on the oval the road banks about its inner edge (apron and
     * infield flat); the road course is flat across, at the centerline's elevation.
     */
    public float height(float s, float d) {
        if (!oval) return elevation(s);
        if (d <= ROAD_INNER) return 0f;
        return (d - ROAD_INNER) * FastMath.tan(bank(s));
    }

    /** Approximate surface normal at (s, d). */
    public Vector3f surfaceNormal(float s, float d, Vector3f out) {
        if (out == null) out = new Vector3f();
        if (!oval) {
            // tilted back by the slope along the road
            float g = grade(s), h = heading(s);
            return out.set(-FastMath.cos(h) * g, 1f, -FastMath.sin(h) * g).normalizeLocal();
        }
        if (d <= ROAD_INNER) return out.set(0, 1, 0);
        float b = bank(s);
        Vector3f r = right(s, null);
        return out.set(0, FastMath.cos(b), 0).subtractLocal(r.multLocal(FastMath.sin(b))).normalizeLocal();
    }

    /** Projects a world position onto track coordinates. */
    public TrackPos project(Vector3f world, TrackPos out) {
        if (out == null) out = new TrackPos();
        if (!oval) {
            // the nearest sample, then the exact spot between it and its neighbours: along a curve
            // the centerline moves 1 / (1 + k d) as fast as a point at offset d
            int i = nearestSample(world.x, world.z, 400);
            for (int iter = 0; iter < 4; iter++) {
                float fx = FastMath.cos(psi[i]), fz = FastMath.sin(psi[i]);
                float vx = (float) (world.x - px[i]), vz = (float) (world.z - pz[i]);
                float u = vx * fx + vz * fz;
                float d = -vx * fz + vz * fx;
                if (u > ds * 0.5f && iter < 3) { i = (i + 1) % n; continue; }
                if (u < -ds * 0.5f && iter < 3) { i = (i + n - 1) % n; continue; }
                float stretch = Math.max(1f + kappa[i] * d, 0.2f);
                out.s = wrapS(i * ds + u / stretch);
                out.d = d;
                break;
            }
            return out;
        }
        float[] ud = new float[2];
        float bestCost = Float.MAX_VALUE;
        Vector3f tmp = new Vector3f();
        for (Segment seg : segments) {
            seg.project(world.x, world.z, ud);
            float u = ud[0];
            float cost;
            if (u >= 0 && u <= seg.length) {
                cost = Math.abs(ud[1]);
            } else {
                u = FastMath.clamp(u, 0, seg.length);
                seg.point(u, 0, tmp);
                float dx = world.x - tmp.x, dz = world.z - tmp.z;
                cost = FastMath.sqrt(dx * dx + dz * dz);
            }
            if (cost < bestCost) {
                bestCost = cost;
                out.s = wrapS(seg.startS + u);
                out.d = ud[1];
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------ road course

    /**
     * The road course's height (m above its lowest point) at points along the lap (s from the
     * start/finish line): down from the first-turn hairpin into the valley, up the steep climb
     * (~17% at its steepest) and the long straight to the summit (102.2 m), down through the
     * hairpin and the long double left to the lowest point, and back up to the last chicane.
     * Monotone cubic in between, so the summit and the lowest point are exactly these.
     */
    private static final float[][] ROAD_ELEVATION = {
            {0f, 34.2f}, {215f, 37.2f}, {780f, 12.2f}, {1140f, 53.2f}, {2240f, 102.2f}, {2360f, 100.2f},
            {2500f, 94.2f}, {2910f, 82.2f}, {3140f, 74.2f}, {3790f, 50.2f}, {4110f, 38.2f}, {4355f, 32.2f},
            {4520f, 27.2f}, {4800f, 12.2f}, {5070f, 0.0f}, {5410f, 4.2f}, {5780f, 14.2f}, {6050f, 23.2f},
            {6590f, 31.2f}, {6680f, 32.7f}};

    /** Centripetal Catmull-Rom point between p1 and p2 (u 0..1); writes {x, z}. */
    private static void catmullRom(double[] p0, double[] p1, double[] p2, double[] p3, double u, double[] out) {
        double t0 = 0, t1 = t0 + knot(p0, p1), t2 = t1 + knot(p1, p2), t3 = t2 + knot(p2, p3);
        double t = t1 + (t2 - t1) * u;
        for (int c = 0; c < 2; c++) {
            double a1 = p0[c] * ((t1 - t) / (t1 - t0)) + p1[c] * ((t - t0) / (t1 - t0));
            double a2 = p1[c] * ((t2 - t) / (t2 - t1)) + p2[c] * ((t - t1) / (t2 - t1));
            double a3 = p2[c] * ((t3 - t) / (t3 - t2)) + p3[c] * ((t - t2) / (t3 - t2));
            double b1 = a1 * ((t2 - t) / (t2 - t0)) + a2 * ((t - t0) / (t2 - t0));
            double b2 = a2 * ((t3 - t) / (t3 - t1)) + a3 * ((t - t1) / (t3 - t1));
            out[c] = b1 * ((t2 - t) / (t2 - t1)) + b2 * ((t - t1) / (t2 - t1));
        }
    }

    private static double knot(double[] a, double[] b) {
        float dist = (float) Math.hypot(a[0] - b[0], a[1] - b[1]);
        return Math.max(FastMath.sqrt(dist), 1e-3f);
    }

    /** Circular Gaussian blur of a lap-long array (sigma in samples). */
    static float[] blurLoop(float[] a, float sigma) {
        int num = a.length, r = (int) Math.ceil(3f * sigma);
        float[] w = new float[2 * r + 1];
        float sum = 0f;
        for (int k = -r; k <= r; k++) {
            w[k + r] = (float) Math.exp(-0.5f * (k / sigma) * (k / sigma));
            sum += w[k + r];
        }
        float[] out = new float[num];
        for (int i = 0; i < num; i++) {
            float acc = 0f;
            for (int k = -r; k <= r; k++) acc += w[k + r] * a[((i + k) % num + num) % num];
            out[i] = acc / sum;
        }
        return out;
    }

    private static int wrap(int i, int num) { return ((i % num) + num) % num; }

    private int locateI0(float s) {
        return Math.clamp((int) (s / ds), 0, n - 1);
    }

    private float locateT(float s, int i0) {
        return FastMath.clamp(s / ds - i0, 0f, 1f);
    }

    private float sampled(float[] a, float s) {
        s = wrapS(s);
        int i0 = locateI0(s), i1 = (i0 + 1) % n;
        float t = locateT(s, i0);
        return a[i0] + (a[i1] - a[i0]) * t;
    }

    private int nearestSample(float x, float z, int maxRing) {
        int cx = (int) Math.floor((x - gridX0) / GRID_CELL), cz = (int) Math.floor((z - gridZ0) / GRID_CELL);
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        // rings of cells outwards from the point's cell; once something is found, one more ring
        // settles which sample is nearest
        int foundRing = -1;
        for (int ring = 0; ring <= maxRing; ring++) {
            if (foundRing >= 0 && ring > foundRing + 1) break;
            // skip rings that lie entirely outside the grid
            if (cx + ring < 0 && cx - ring < 0) continue;
            for (int gz = cz - ring; gz <= cz + ring; gz++) {
                if (gz < 0 || gz >= gridNz) continue;
                boolean edgeRow = gz == cz - ring || gz == cz + ring;
                int stepX = (edgeRow || ring == 0) ? 1 : 2 * ring;
                for (int gx = cx - ring; gx <= cx + ring; gx += stepX) {
                    if (gx < 0 || gx >= gridNx) continue;
                    int cell = gz * gridNx + gx;
                    for (int k = cellStart[cell]; k < cellStart[cell + 1]; k++) {
                        int i = cellItems[k];
                        double d2 = (px[i] - x) * (px[i] - x) + (pz[i] - z) * (pz[i] - z);
                        if (d2 < bestD2) { bestD2 = d2; best = i; }
                    }
                }
            }
            if (best >= 0 && foundRing < 0) foundRing = ring;
            if (ring > gridNx + gridNz) break;
        }
        if (best < 0) {
            for (int i = 0; i < n; i++) {
                double d2 = (px[i] - x) * (px[i] - x) + (pz[i] - z) * (pz[i] - z);
                if (d2 < bestD2) { bestD2 = d2; best = i; }
            }
        }
        return best;
    }

    private void buildRoadCourse() {
        // ---- centerline: centripetal Catmull-Rom through the control points, scaled to the lap
        // length and resampled every metre ----
        float[] raw = RoadLayout.POINTS;
        int numCtrl = raw.length / 2;
        double[][] ctrl = new double[numCtrl][];
        for (int i = 0; i < numCtrl; i++) ctrl[i] = new double[]{raw[2 * i], raw[2 * i + 1]};
        int sub = 20;
        int numDense = numCtrl * sub;
        double[][] dense = new double[numDense][];
        for (int i = 0; i < numCtrl; i++) {
            double[] p0 = ctrl[wrap(i - 1, numCtrl)], p1 = ctrl[i], p2 = ctrl[wrap(i + 1, numCtrl)], p3 = ctrl[wrap(i + 2, numCtrl)];
            for (int k = 0; k < sub; k++) {
                double[] o = new double[2];
                catmullRom(p0, p1, p2, p3, k / (float) sub, o);
                dense[i * sub + k] = o;
            }
        }
        double[] cum = new double[numDense + 1];
        for (int i = 0; i < numDense; i++) {
            double[] a = dense[i], b = dense[(i + 1) % numDense];
            cum[i + 1] = cum[i] + Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        double scaleXY = LENGTH / cum[numDense];
        n = Math.round(LENGTH);
        ds = LENGTH / n;
        px = new double[n];
        pz = new double[n];
        int seg = 0;
        for (int i = 0; i < n; i++) {
            double target = i * ds / scaleXY;
            while (seg < numDense - 1 && cum[seg + 1] < target) seg++;
            double u = (target - cum[seg]) / Math.max(cum[seg + 1] - cum[seg], 1e-9);
            double[] a = dense[seg], b = dense[(seg + 1) % numDense];
            px[i] = (a[0] + (b[0] - a[0]) * u) * scaleXY;
            pz[i] = (a[1] + (b[1] - a[1]) * u) * scaleXY;
        }

        // ---- heading (unwrapped; it gains a whole turn over the lap) and curvature ----
        psi = new float[n];
        for (int i = 0; i < n; i++) {
            int a = wrap(i - 1, n), b = (i + 1) % n;
            psi[i] = FastMath.atan2((float) (pz[b] - pz[a]), (float) (px[b] - px[a]));
            if (i > 0) {
                while (psi[i] - psi[i - 1] > FastMath.PI) psi[i] -= FastMath.TWO_PI;
                while (psi[i] - psi[i - 1] < -FastMath.PI) psi[i] += FastMath.TWO_PI;
            }
        }
        psiWrap = psi[n - 1] > psi[0] ? FastMath.TWO_PI : -FastMath.TWO_PI;
        kappa = new float[n];
        for (int i = 0; i < n; i++) kappa[i] = -(psiAt(i + 2) - psiAt(i - 2)) / (4f * ds);   // heading grows turning right: + = left
        kappa = blurLoop(kappa, 2f);

        buildElevation();
        buildTurns();
        buildGrid();
        buildRoadWalls();
    }

    private float psiAt(int i) {
        return i < 0 ? psi[i + n] - psiWrap : i >= n ? psi[i - n] + psiWrap : psi[i];
    }

    /** Elevation: monotone cubic (Fritsch-Carlson) through the keys, around the loop. */
    private void buildElevation() {
        int k = ROAD_ELEVATION.length;
        float[] ks = new float[k], zs = new float[k];
        for (int i = 0; i < k; i++) {
            ks[i] = ROAD_ELEVATION[i][0] * (LENGTH / ROAD_LENGTH);
            zs[i] = ROAD_ELEVATION[i][1];
        }
        float[] h = new float[k], delta = new float[k];
        for (int i = 0; i < k; i++) {
            int j = (i + 1) % k;
            h[i] = (j == 0 ? ks[0] + LENGTH : ks[j]) - ks[i];
            delta[i] = (zs[j] - zs[i]) / h[i];
        }
        float[] m = new float[k];
        for (int i = 0; i < k; i++) {
            int prev = wrap(i - 1, k);
            float d0 = delta[prev], d1 = delta[i];
            if (d0 * d1 <= 0f) { m[i] = 0f; continue; }
            float h0 = h[prev], h1 = h[i];
            float w1 = 2 * h1 + h0, w2 = h1 + 2 * h0;
            m[i] = (w1 + w2) / (w1 / d0 + w2 / d1);
        }
        elev = new float[n];
        for (int i = 0; i < n; i++) {
            float s = i * ds;
            int kk = k - 1;
            for (int j = k - 1; j >= 0; j--) if (ks[j] <= s) { kk = j; break; }
            float hk = h[kk], t = (s - ks[kk]) / hk;
            float t2 = t * t, t3 = t2 * t;
            int k1 = (kk + 1) % k;
            elev[i] = (2 * t3 - 3 * t2 + 1) * zs[kk] + (t3 - 2 * t2 + t) * hk * m[kk] + (-2 * t3 + 3 * t2) * zs[k1] + (t3 - t2) * hk * m[k1];
        }
        slope = new float[n];
        for (int i = 0; i < n; i++) slope[i] = (elev[(i + 1) % n] - elev[wrap(i - 1, n)]) / (2f * ds);
    }

    private boolean isTurnSample(int i) {
        return Math.abs(kappa[i]) > 1f / ROAD_TURN_RADIUS;
    }

    /** Turns: where the curvature is tighter than ROAD_TURN_RADIUS. */
    private void buildTurns() {
        turnDir = new byte[n];
        toTurn = new float[n];
        toExit = new float[n];
        int start = 0;
        while (start < n && isTurnSample(start)) start++;          // begin on a straight
        List<Float> starts = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            int i = (start + k) % n;
            if (isTurnSample(i) && !isTurnSample(wrap(i - 1, n))) starts.add(i * ds);
        }
        corners = new float[starts.size()];
        for (int i = 0; i < corners.length; i++) corners[i] = starts.get(i);
        Arrays.sort(corners);
        // backwards round the lap (twice, for the wrap): distance to the next turn, its
        // direction, and the distance to the end of the turn we are in or the next one
        float dist = 0f, exit = 0f, dir = 1f;
        for (int pass = 0; pass < 2; pass++) {
            for (int k = n - 1; k >= 0; k--) {
                int i = (start + k) % n;
                if (isTurnSample(i)) {
                    exit = isTurnSample((i + 1) % n) ? exit + ds : ds;
                    dist = 0f;
                    dir = kappa[i] > 0f ? 1f : -1f;
                } else {
                    dist += ds;
                    exit += ds;
                }
                toTurn[i] = dist;
                toExit[i] = exit;
                turnDir[i] = (byte) (dir > 0f ? 1 : -1);
            }
        }
    }

    /** Spatial grid over the samples. */
    private void buildGrid() {
        float minX = Float.MAX_VALUE, maxX = -minX, minZ = minX, maxZ = -minX;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, (float) px[i]); maxX = Math.max(maxX, (float) px[i]);
            minZ = Math.min(minZ, (float) pz[i]); maxZ = Math.max(maxZ, (float) pz[i]);
        }
        gridX0 = minX - GRID_CELL;
        gridZ0 = minZ - GRID_CELL;
        gridNx = (int) Math.ceil((maxX - gridX0) / GRID_CELL) + 2;
        gridNz = (int) Math.ceil((maxZ - gridZ0) / GRID_CELL) + 2;
        int[] count = new int[gridNx * gridNz];
        for (int i = 0; i < n; i++) count[cellOf(i)]++;
        cellStart = new int[gridNx * gridNz + 1];
        for (int c = 0; c < gridNx * gridNz; c++) cellStart[c + 1] = cellStart[c] + count[c];
        cellItems = new int[n];
        int[] fill = cellStart.clone();
        for (int i = 0; i < n; i++) cellItems[fill[cellOf(i)]++] = i;
        center.set((minX + maxX) / 2, 0, (minZ + maxZ) / 2);
    }

    private int cellOf(int i) {
        return (int) Math.floor(((float) pz[i] - gridZ0) / GRID_CELL) * gridNx + (int) Math.floor(((float) px[i] - gridX0) / GRID_CELL);
    }

    private void buildRoadWalls() {
        // Grass run-off out to a wall (barrier) on each side: wider on the outside of the turns,
        // narrower where the road passes close to another part of the circuit (each side keeps to
        // its half of the gap) and on the inside of a tight turn (never near the turn's center).
        float half = ROAD_HALF_WIDTH;
        float[] cornerK = blurLoop(kappa, 25f);      // the turn around here (+ left)
        wallLeft = new float[n];
        wallRight = new float[n];
        for (int i = 0; i < n; i++) {
            float turniness = FastMath.clamp(Math.abs(cornerK[i]) * 120f, 0f, 1f);   // 1: tighter than ~120 m
            float outside = half + 10f + 12f * turniness;
            float inside = half + 7f;
            wallRight[i] = cornerK[i] > 0f ? outside : inside;      // left turn: the right side is the outside
            wallLeft[i] = -(cornerK[i] > 0f ? inside : outside);
        }
        // the inside of a tight turn
        for (int i = 0; i < n; i++) {
            float kMax = 0f, localK = 0f;
            for (int k = -30; k <= 30; k++) {
                float kk = kappa[wrap(i + k, n)];
                kMax = Math.max(kMax, Math.abs(kk));
                localK += kk;
            }
            if (kMax < 1e-4f) continue;
            float limit = Math.max(half + 2.5f, 0.7f / kMax);
            if (localK > 0f) wallLeft[i] = Math.max(wallLeft[i], -limit);
            else wallRight[i] = Math.min(wallRight[i], limit);
        }
        // other parts of the circuit nearby: keep to this side's half of the gap
        for (int i = 0; i < n; i++) {
            double ppx = px[i], ppz = pz[i];
            float rx = -FastMath.sin(psi[i]), rz = FastMath.cos(psi[i]);
            int cx = (int) Math.floor(((float) ppx - gridX0) / GRID_CELL), cz = (int) Math.floor(((float) ppz - gridZ0) / GRID_CELL);
            for (int gz = cz - 4; gz <= cz + 4; gz++) {
                for (int gx = cx - 4; gx <= cx + 4; gx++) {
                    if (gx < 0 || gz < 0 || gx >= gridNx || gz >= gridNz) continue;
                    int cell = gz * gridNx + gx;
                    for (int k = cellStart[cell]; k < cellStart[cell + 1]; k++) {
                        int j = cellItems[k];
                        int gap = Math.abs(j - i);
                        if (Math.min(gap, n - gap) * ds < 150f) continue;
                        float vx = (float) (px[j] - ppx), vz = (float) (pz[j] - ppz);
                        float dist = FastMath.sqrt(vx * vx + vz * vz);
                        float side = vx * rx + vz * rz;
                        if (Math.abs(side) < 0.5f * dist) continue;      // ahead or behind, not off to one side
                        float room = Math.max(half + 1.5f, 0.5f * dist - 1f);
                        if (side > 0f) wallRight[i] = Math.min(wallRight[i], room);
                        else wallLeft[i] = Math.max(wallLeft[i], -room);
                    }
                }
            }
        }
        // no sudden steps: the tightest value within 12 m, then smoothed
        for (int side = 0; side < 2; side++) {
            float[] w = side == 0 ? wallLeft : wallRight;
            float[] tight = new float[n];
            for (int i = 0; i < n; i++) {
                float v = w[i];
                for (int k = -12; k <= 12; k++) v = side == 0 ? Math.max(v, w[wrap(i + k, n)]) : Math.min(v, w[wrap(i + k, n)]);
                tight[i] = v;
            }
            tight = blurLoop(tight, 6f);
            for (int i = 0; i < n; i++) tight[i] = side == 0 ? Math.min(tight[i], -(half + 1.5f)) : Math.max(tight[i], half + 1.5f);
            if (side == 0) wallLeft = tight;
            else wallRight = tight;
        }
        // kerbs on both sides through the turns, and a little before and after
        kerbs = new byte[n];
        for (int i = 0; i < n; i++) {
            if (Math.abs(kappa[i]) < 1f / 250f) continue;
            for (int k = -18; k <= 18; k++) kerbs[wrap(i + k, n)] = 3;
        }
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float e : elev) { lo = Math.min(lo, e); hi = Math.max(hi, e); }
        System.out.printf("Road course: %d samples, %d turns, heights %.1f .. %.1f m%n", n, corners.length, lo, hi);
    }
}
