package com.racingsim.track;

import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;

/**
 * Color of the land around the track (a multiplier on the tiling grass texture, written into
 * the vertex colors) and where the woods are. Shared by the ground mesh, the grass strips
 * beside the track and the tree placement in {@link Scenery}, so the grass under a wood is
 * darker and the woods sit where the ground says they do.
 * <ul>
 *   <li>Infield and the lawns just outside the track: mown, with mowing stripes along the straights.</li>
 *   <li>Further out (d &gt; ~150 m): rough meadow, yellower and patchier.</li>
 *   <li>Large-scale noise breaks up the texture tiling everywhere.</li>
 * </ul>
 */
public final class GroundShade {

    /** Width of one mowing stripe (m). */
    private static final float STRIPE = 9f;
    /** Lawns are mown out to this offset outside the track; rough meadow from {@link #MEADOW_END}. */
    private static final float MOWN_END = 140f, MEADOW_END = 200f;

    private final TrackSpline track;
    private final float stripeX, stripeZ;
    private final TrackSpline.TrackPos tp = new TrackSpline.TrackPos();
    private final Vector3f tmp = new Vector3f();

    public GroundShade(TrackSpline track) {
        this.track = track;
        Vector3f f = track.forward(0f, null);                       // stripes run along the straights
        stripeX = -f.z;
        stripeZ = f.x;
    }

    /** Ground color multiplier at world (x, z). */
    public ColorRGBA color(float x, float z) {
        tmp.set(x, 0, z);
        track.project(tmp, tp);
        float d = tp.d;
        float macro = fbm(x / 210f, z / 210f, 3, 11);
        float mid = fbm(x / 38f, z / 38f, 2, 23);
        float fine = noise(x / 9f, z / 9f, 37);

        // mown lawn: even, slightly blue-green, with stripes
        float stripe = FastMath.sin((x * stripeX + z * stripeZ) * FastMath.PI / STRIPE);
        float stripes = FastMath.clamp(stripe * 2.2f, -1f, 1f);
        float mownFactor = d < 0f ? 1f : 1f - smooth((d - MOWN_END) / (MEADOW_END - MOWN_END));
        if (d > -26f && d < 45f) stripes *= 0.3f;                   // right beside the track: edged, not striped
        float mb = 0.92f + 0.14f * macro + 0.06f * (mid - 0.5f) + 0.065f * stripes;
        float mr = mb * 0.96f, mg = mb, mbB = mb * 0.95f;

        // rough meadow: yellower, patchy, dry spots
        float dry = smooth((mid - 0.55f) / 0.25f) * 0.7f + smooth((macro - 0.6f) / 0.3f) * 0.3f;
        float rb = 0.88f + 0.22f * macro + 0.08f * (fine - 0.5f);
        float rr = rb * (1.08f + 0.35f * dry), rg = rb * (1.0f + 0.05f * dry), rbB = rb * (0.80f - 0.1f * dry);

        float r = mr * mownFactor + rr * (1 - mownFactor);
        float g = mg * mownFactor + rg * (1 - mownFactor);
        float b = mbB * mownFactor + rbB * (1 - mownFactor);

        // forest floor under the woods: darker, browner
        float wood = woodDensity(x, z, d);
        float shade = 1f - 0.38f * wood;
        r *= shade * (1f + 0.08f * wood);
        g *= shade;
        b *= shade * (1f - 0.1f * wood);
        return new ColorRGBA(Math.min(r, 1.3f), Math.min(g, 1.3f), Math.min(b, 1.3f), 1f);
    }

    /**
     * 0..1 density of woodland at (x, z) with track offset d: none on the mown lawns, patchy woods
     * from ~100 m outside the track, close to continuous toward the distant tree line.
     */
    public float woodDensity(float x, float z, float d) {
        if (d < 95f) return 0f;
        float patches = fbm(x / 140f, z / 140f, 3, 51);
        float edge = smooth((d - 95f) / 140f);                     // thicker further out
        return smooth((patches + 0.35f * edge - 0.62f) / 0.18f) * smooth((d - 95f) / 25f);
    }

    /** Track offset of a world position (convenience for callers without a TrackPos). */
    public float offsetAt(float x, float z) {
        tmp.set(x, 0, z);
        return track.project(tmp, tp).d;
    }

    // ------------------------------------------------------------------ value noise
    static float fbm(float x, float y, int octaves, int seed) {
        float sum = 0f, amp = 0.5f, norm = 0f;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x, y, seed + i * 101);
            norm += amp;
            amp *= 0.5f;
            x *= 2.03f;
            y *= 2.03f;
        }
        return sum / norm;
    }

    static float noise(float x, float y, int seed) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;
        fx = fx * fx * (3 - 2 * fx);
        fy = fy * fy * (3 - 2 * fy);
        float a = hash(x0, y0, seed), b = hash(x0 + 1, y0, seed);
        float c = hash(x0, y0 + 1, seed), e = hash(x0 + 1, y0 + 1, seed);
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + e * fx) * fy;
    }

    private static float hash(int x, int y, int seed) {
        int h = x * 374761393 + y * 668265263 + seed * 1442695041;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xffffff) / (float) 0x1000000;
    }

    private static float smooth(float t) {
        t = FastMath.clamp(t, 0f, 1f);
        return t * t * (3 - 2 * t);
    }
}
