package com.racingsim.track;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Parametric description of a 2.5-mile rectangular oval.
 * <p>
 * Track coordinates:
 * <ul>
 *   <li>{@code s} - longitudinal progress along the centerline, 0..{@link #LENGTH} meters,
 *       0 = start/finish line (middle of the front straight).</li>
 *   <li>{@code d} - horizontal lateral offset from the centerline, positive = outward
 *       (toward the outer wall, i.e. the driver's right since all turns are left-handers).</li>
 * </ul>
 * World frame: Y up, the centerline lies in the XZ plane; the racing surface height is
 * given by {@link #height(float, float)} (banking pivots about the inner edge of the road).
 */
public final class TrackSpline {

    public static final float MILE = 1609.344f;
    public static final float STRAIGHT = MILE * 5f / 8f;   // 1005.84 m
    public static final float CHUTE = MILE / 8f;            //  201.17 m
    public static final float TURN = MILE / 4f;             //  402.34 m
    public static final float LENGTH = 2 * STRAIGHT + 2 * CHUTE + 4 * TURN; // 4023.36 m
    public static final float TURN_RADIUS = TURN / FastMath.HALF_PI;         // ~256 m

    public static final float BANK_ANGLE = 9.2f * FastMath.DEG_TO_RAD;
    /** Length over which banking transitions in/out around each turn boundary. */
    public static final float BANK_RAMP = 80f;

    // Cross-section layout (lateral offsets, meters)
    public static final float INNER_WALL = -18f;
    public static final float APRON_INNER = -15f;
    public static final float ROAD_INNER = -7.5f;
    public static final float ROAD_OUTER = 8.0f;
    public static final float OUTER_WALL_THICKNESS = 0.6f;
    public static final float OUTER_WALL_HEIGHT = 1.2f;
    public static final float INNER_WALL_HEIGHT = 1.0f;

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

    public TrackSpline() {
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

    /** Geometric center of the oval (y = 0). */
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
        Segment seg = segmentAt(s);
        seg.point(s - seg.startS, d, out);
        out.y = height(s, d);
        return out;
    }

    public float heading(float s) {
        s = wrapS(s);
        Segment seg = segmentAt(s);
        return seg.heading(s - seg.startS);
    }

    /** Unit forward (direction of travel) vector, horizontal. */
    public Vector3f forward(float s, Vector3f out) {
        float psi = heading(s);
        return (out == null ? new Vector3f() : out).set(FastMath.cos(psi), 0, FastMath.sin(psi));
    }

    /** Unit outward (right-hand) vector, horizontal. */
    public Vector3f right(float s, Vector3f out) {
        float psi = heading(s);
        return (out == null ? new Vector3f() : out).set(-FastMath.sin(psi), 0, FastMath.cos(psi));
    }

    /** Signed curvature of the centerline at s (positive = left turn). */
    public float curvature(float s) {
        return segmentAt(s).curvature();
    }

    /** Turning radius at lateral offset d (infinite on straights). */
    public float radius(float s, float d) {
        float k = curvature(s);
        return k == 0 ? Float.POSITIVE_INFINITY : (1f / k) + d;
    }

    public boolean inTurn(float s) {
        return curvature(s) != 0;
    }

    /** Distance from s to the start of the next turn (0 if already in a turn). */
    public float distanceToNextTurn(float s) {
        s = wrapS(s);
        if (inTurn(s)) return 0;
        float best = Float.MAX_VALUE;
        for (Segment a : arcs) {
            best = Math.min(best, wrapS(a.startS - s));
        }
        return best;
    }

    /** Turn start positions (s) - used by the AI racing line. */
    public float[] turnStarts() {
        float[] r = new float[arcs.size()];
        for (int i = 0; i < r.length; i++) r[i] = arcs.get(i).startS;
        return r;
    }

    /** Banking angle (radians) at s, smoothly ramped across turn boundaries. */
    public float bank(float s) {
        s = wrapS(s);
        float w = 0;
        for (Segment a : arcs) {
            float in = smooth((s - (a.startS - BANK_RAMP / 2)) / BANK_RAMP);
            float out = smooth(((a.startS + a.length + BANK_RAMP / 2) - s) / BANK_RAMP);
            w = Math.max(w, Math.min(in, out));
        }
        return BANK_ANGLE * w;
    }

    private static float smooth(float x) {
        x = FastMath.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    /** Height of the racing surface (road banks about its inner edge; apron/infield flat). */
    public float height(float s, float d) {
        if (d <= ROAD_INNER) return 0f;
        return (d - ROAD_INNER) * FastMath.tan(bank(s));
    }

    /** Approximate surface normal at (s, d). */
    public Vector3f surfaceNormal(float s, float d, Vector3f out) {
        if (out == null) out = new Vector3f();
        if (d <= ROAD_INNER) return out.set(0, 1, 0);
        float b = bank(s);
        Vector3f r = right(s, null);
        return out.set(0, FastMath.cos(b), 0).subtractLocal(r.multLocal(FastMath.sin(b))).normalizeLocal();
    }

    /** Projects a world position onto track coordinates. */
    public TrackPos project(Vector3f world, TrackPos out) {
        if (out == null) out = new TrackPos();
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
}
