package com.racingsim.ai;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.racingsim.track.TrackSpline;

/**
 * The racing line as a lateral offset d(s). On the oval the classic line: high (near the wall)
 * on the straights approaching a turn, low (near the white line) at the apex, drifting back up
 * on exit. On the road course the minimum-curvature line within the road (less the car's width):
 * out wide, clip the apex, out wide again, worked out once when the track is loaded.
 */
public final class RacingLine {

    public static final float HIGH = 4.3f;
    public static final float LOW = -5.0f;
    /** How close (m) the road-course line comes to the road edge (car center). */
    public static final float EDGE_MARGIN = 1.3f;
    /** How far before/after the turn the line starts moving (meters). */
    private static final float SPREAD = 140f;

    private final TrackSpline track;
    private final float[] centers;
    private final float halfWidth;
    // road course: offsets and path curvature every step metres
    private float step = 2f;
    private float[] offsets, curv;

    public RacingLine(TrackSpline track) {
        this.track = track;
        if (!TrackSpline.isOval()) {
            centers = new float[0];
            halfWidth = 1f;
            buildRoadLine();
            return;
        }
        float[] starts = track.turnStarts();
        centers = new float[starts.length];
        for (int i = 0; i < starts.length; i++) centers[i] = starts[i] + TrackSpline.TURN / 2f;
        halfWidth = TrackSpline.TURN / 2f + SPREAD;
    }

    public TrackSpline getTrack() { return track; }

    public float offset(float s) {
        if (offsets != null) return sample(offsets, s);
        float w = 0f;
        for (float c : centers) {
            float x = Math.abs(TrackSpline.deltaS(c, s)) / halfWidth;
            if (x < 1f) w = Math.max(w, 0.5f * (1f + FastMath.cos(FastMath.PI * x)));
        }
        return HIGH + (LOW - HIGH) * w;
    }

    /** Signed curvature of the line itself (+ = left); the centerline's on the oval. */
    public float pathCurvature(float s) {
        if (curv == null) return track.curvature(s);
        return sample(curv, s);
    }

    private float sample(float[] a, float s) {
        float x = TrackSpline.wrapS(s) / step;
        int i = Math.min((int) x, a.length - 1);
        float t = x - i;
        return a[i] + (a[(i + 1) % a.length] - a[i]) * t;
    }

    private void buildRoadLine() {
        // Minimum curvature: each point of the line is pulled toward the straight line between its
        // neighbours (Gauss-Seidel with over-relaxation), within the road less EDGE_MARGIN. Solved
        // on an 8 m spacing first (the long-range shape of each turn), then refined every 2 m.
        int m = Math.round(TrackSpline.LENGTH / 2f);
        step = TrackSpline.LENGTH / m;
        float lim = TrackSpline.ROAD_OUTER - EDGE_MARGIN;
        float[] cx = new float[m], cz = new float[m], rx = new float[m], rz = new float[m];
        Vector3f p = new Vector3f(), r = new Vector3f();
        for (int i = 0; i < m; i++) {
            track.centerline(i * step, p);
            track.right(i * step, r);
            cx[i] = p.x; cz[i] = p.z;
            rx[i] = r.x; rz[i] = r.z;
        }
        offsets = new float[m];
        relax(4, 4000, 1.6f, m, lim, cx, cz, rx, rz);
        relax(1, 2500, 1.6f, m, lim, cx, cz, rx, rz);
        // curvature of the line itself
        float[] k = new float[m];
        for (int i = 0; i < m; i++) {
            int ia = (i + m - 1) % m, ib = (i + 1) % m;
            float pax = cx[ia] + rx[ia] * offsets[ia], paz = cz[ia] + rz[ia] * offsets[ia];
            float ppx = cx[i] + rx[i] * offsets[i], ppz = cz[i] + rz[i] * offsets[i];
            float pbx = cx[ib] + rx[ib] * offsets[ib], pbz = cz[ib] + rz[ib] * offsets[ib];
            float ax = ppx - pax, az = ppz - paz, bx = pbx - ppx, bz = pbz - ppz;
            float turn = FastMath.atan2(ax * bz - az * bx, ax * bx + az * bz);   // + = turning toward +z (right)
            float len = 0.5f * (FastMath.sqrt(ax * ax + az * az) + FastMath.sqrt(bx * bx + bz * bz));
            k[i] = -turn / Math.max(len, 1e-3f);
        }
        curv = new float[m];
        for (int i = 0; i < m; i++) {
            float acc = 0f, w = 0f;
            for (int j = -4; j <= 4; j++) {
                float wk = (float) Math.exp(-0.5f * (j / 2f) * (j / 2f));
                acc += wk * k[(i + j + m) % m];
                w += wk;
            }
            curv[i] = acc / w;
        }
        float minR = Float.MAX_VALUE;
        for (float kk : curv) if (Math.abs(kk) > 1e-6f) minR = Math.min(minR, 1f / Math.abs(kk));
        System.out.printf("Racing line: %d points, tightest radius %.1f m%n", m, minR);
    }

    private void relax(int stride, int iterations, float omega, int m, float lim,
                       float[] cx, float[] cz, float[] rx, float[] rz) {
        int k = m / stride;
        for (int it = 0; it < iterations; it++) {
            for (int j = 0; j < k; j++) {
                int ia = ((j + k - 1) % k) * stride, i = j * stride, ib = ((j + 1) % k) * stride;
                float pax = cx[ia] + rx[ia] * offsets[ia], paz = cz[ia] + rz[ia] * offsets[ia];
                float ppx = cx[i] + rx[i] * offsets[i], ppz = cz[i] + rz[i] * offsets[i];
                float pbx = cx[ib] + rx[ib] * offsets[ib], pbz = cz[ib] + rz[ib] * offsets[ib];
                // the point on the chord at the same fraction of the way along
                float la = TrackSpline.deltaS(ia * step, i * step), lb = TrackSpline.deltaS(i * step, ib * step);
                float f = la / Math.max(la + lb, 1e-3f);
                float tx = pax + (pbx - pax) * f, tz = paz + (pbz - paz) * f;
                float move = (tx - ppx) * rx[i] + (tz - ppz) * rz[i];
                offsets[i] = FastMath.clamp(offsets[i] + omega * move, -lim, lim);
            }
        }
        if (stride > 1) {
            // fill in between the solved points
            for (int j = 0; j < k; j++) {
                int i = j * stride, ib = ((j + 1) % k) * stride;
                int span = (j + 1 < k) ? stride : m - i;
                for (int q = 1; q < span; q++) offsets[(i + q) % m] = FastMath.interpolateLinear(q / (float) span, offsets[i], offsets[ib]);
            }
        }
    }
}
