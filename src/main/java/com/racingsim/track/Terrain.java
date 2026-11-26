package com.racingsim.track;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;

/**
 * The wooded hills around the road course (port of the Unreal version's terrain in
 * Track/RoadCourse.cpp): land heights on a square grid, built by {@link RoadCourseBuilder}, so the
 * scenery can stand its trees and grandstands on the ground ({@link #height}).
 * <p>
 * The land meets each barrier at the track's height, then eases toward the lie of the land (the
 * track's heights averaged over a few hundred metres) plus rolling hills that grow away from the
 * track; at the edge of the grid it fades to a level horizon.
 */
public final class Terrain {

    /** Cell size (m), how far the grid reaches past the track (m), and the hills' size (m). */
    static final float CELL = 12f, MARGIN = 900f, HILL_HEIGHT = 55f;
    /** Beyond this distance from a wall the land is forest seen from afar (darker canopy). */
    static final float FOREST_FROM = 300f;
    private static final float EDGE_FADE = 250f;

    final float x0, z0;
    final int nx, nz;
    final float[] y;
    /** Distance beyond the nearest wall per grid point (negative: inside the walls). */
    final float[] beyond;
    /** Level of the faded edge and the skirt round it. */
    final float edgeY;

    private Terrain(float x0, float z0, int nx, int nz, float edgeY) {
        this.x0 = x0;
        this.z0 = z0;
        this.nx = nx;
        this.nz = nz;
        this.edgeY = edgeY;
        y = new float[nx * nz];
        beyond = new float[nx * nz];
    }

    /** Height (m) of the land at world (x, z) (bilinear on the grid; clamped at its edge). */
    public float height(float x, float z) {
        float fx = FastMath.clamp((x - x0) / CELL, 0f, nx - 1.001f);
        float fz = FastMath.clamp((z - z0) / CELL, 0f, nz - 1.001f);
        int ix = (int) fx, iz = (int) fz;
        float tx = fx - ix, tz = fz - iz;
        int a = iz * nx + ix;
        float lo = y[a] + (y[a + 1] - y[a]) * tx;
        float hi = y[a + nx] + (y[a + nx + 1] - y[a + nx]) * tx;
        return lo + (hi - lo) * tz;
    }

    /**
     * How far (m) world (x, z) is beyond the nearest barrier (negative: between the barriers).
     * @param tp receives the projection onto the track
     */
    public static float beyondWall(TrackSpline track, float x, float z, TrackSpline.TrackPos tp) {
        track.project(new Vector3f(x, 0, z), tp);
        return tp.d >= 0f ? tp.d - track.maxD(tp.s) : track.minD(tp.s) - tp.d;
    }

    /** Builds the heights around the track (the road course). */
    static Terrain build(TrackSpline track) {
        float minX = Float.MAX_VALUE, maxX = -minX, minZ = minX, maxZ = -minX;
        int coarseN = (int) (TrackSpline.LENGTH / 25f);
        float[] cx = new float[coarseN], cz = new float[coarseN], cy = new float[coarseN];   // every 25 m
        Vector3f c = new Vector3f();
        float meanY = 0f;
        for (int i = 0; i < coarseN; i++) {
            float s = i * 25f;
            track.centerline(s, c);
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
            cx[i] = c.x; cz[i] = c.z; cy[i] = track.elevation(s);
            meanY += cy[i];
        }
        meanY /= coarseN;
        int nx = (int) Math.ceil((maxX - minX + 2 * MARGIN) / CELL) + 1;
        int nz = (int) Math.ceil((maxZ - minZ + 2 * MARGIN) / CELL) + 1;
        Terrain t = new Terrain(minX - MARGIN, minZ - MARGIN, nx, nz, meanY - 10f);
        TrackSpline.TrackPos tp = new TrackSpline.TrackPos();
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                float x = t.x0 + i * CELL, z = t.z0 + j * CELL;
                float beyond = beyondWall(track, x, z, tp);
                float e = track.elevation(tp.s);
                // the lie of the land: inverse-distance average of the track's heights
                float wsum = 0f, ysum = 0f;
                for (int k = 0; k < coarseN; k++) {
                    float dx = cx[k] - x, dz = cz[k] - z;
                    float dd = dx * dx + dz * dz + 120f * 120f;
                    float w = 1f / (dd * dd);
                    wsum += w;
                    ysum += w * cy[k];
                }
                float regional = ysum / wsum;
                float hills = (GroundShade.fbm(x / 420f, z / 420f, 4, 7) - 0.5f) * 2f * HILL_HEIGHT;
                float h;
                if (beyond < 0f) {
                    h = e - 0.5f;                                  // under the run-off (hidden)
                } else {
                    float blend = TrackSpline.smooth(beyond / 260f);
                    h = FastMath.interpolateLinear(blend, e, regional)
                            + hills * TrackSpline.smooth((beyond - 15f) / 300f) - 0.05f;
                }
                // fade to a level horizon at the edge of the grid
                float toEdge = Math.min(Math.min(i, nx - 1 - i), Math.min(j, nz - 1 - j)) * CELL;
                h = FastMath.interpolateLinear(TrackSpline.smooth(toEdge / EDGE_FADE), t.edgeY, h);
                t.y[j * nx + i] = h;
                t.beyond[j * nx + i] = beyond;
            }
        }
        return t;
    }
}
