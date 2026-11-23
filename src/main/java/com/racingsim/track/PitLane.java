package com.racingsim.track;

import com.jme3.math.FastMath;

/**
 * Indianapolis-style pit lane layout, in track coordinates relative to the start/finish line
 * ({@code rel = deltaS(0, s)}, negative = before the line):
 * <pre>
 *  rel  -850 .. -600   pit-entry lane on the apron through the exit of turn 4, behind a painted divider
 *  rel  -600 .. -470   pit entry: the lane curves inwards behind the end of the pit wall
 *  rel  -470 ..  470   pit wall (where the inner wall was) with the pit lane behind it:
 *                        d  -18.4 .. -26   fast lane
 *                        d  -26   .. -34   33 pit boxes, 24 m each (box 17 = the player, centerd on the line)
 *                        d  -34   .. -40   pit stands / crew area, then the pit back wall
 *  rel   470 ..  600   pit exit road, curving out toward turn 1
 *  rel   470 .. ~1560  pit exit lane (as at Indianapolis): a separate
 *                      paved lane on the inside of the race track through turns 1 and 2,
 *                        d  -10.5 .. -7.8   the race track's (narrowed) apron
 *                        d  -17   .. -10.5  grass median
 *                        d  -26   .. -17    pit exit lane, white edge lines
 *                      over the last 160 m (exit of turn 2) the grass median tapers to nothing and the
 *                      lane slides out to merge with the race track, becoming the normal apron
 * </pre>
 */
public final class PitLane {

    public static final int STALLS = 33;
    /** Zero-based index of the player's box: the middle one of 33. */
    public static final int PLAYER_STALL = 16;
    public static final float STALL_LENGTH = 24f;

    public static final float START = -600f, WALL_START = -470f, WALL_END = 470f, END = 600f;
    /** Lateral positions (m, negative = infield). */
    public static final float WALL_D = TrackSpline.INNER_WALL;          // pit wall line (-18)
    public static final float FAST_LANE_INNER = -26f, BOX_INNER = -34f, BACK_D = -40f;
    /** Divider between the pit entry lane and the racing surface, on the apron (turn 4). */
    public static final float LANE_DIVIDER_D = -9.6f;
    public static final float ENTRY_LANE_START = -850f;

    /** Pit exit lane through turns 1 and 2 (lateral edges in the steady section). */
    public static final float LANE_OUTER = -17f, LANE_INNER = -26f, MEDIAN_OUTER = -10.5f;
    /** The race track's inner white line. */
    public static final float TRACK_EDGE = TrackSpline.ROAD_INNER - 0.3f;
    /** The exit lane ends just past the exit of turn 2, where it has merged with the race track. */
    public static final float LANE_END = TrackSpline.STRAIGHT / 2 + 2 * TrackSpline.TURN + TrackSpline.CHUTE + 50f;
    public static final float BLEND_START = LANE_END - 160f;
    /** Distance over which the grass median opens up after the end of the pit wall. */
    private static final float MEDIAN_GROW = 50f;

    private PitLane() { }

    /** Position relative to the start/finish line, in (-L/2, L/2]. */
    public static float rel(float s) {
        return TrackSpline.deltaS(0f, s);
    }

    /** True along the pit exit lane (from the end of the pit wall to its merge at the exit of turn 2). */
    public static boolean inExitLane(float s) {
        float r = rel(s);
        return r > WALL_END && r <= LANE_END;
    }

    /** True from the pit entry to the end of the pit wall (pit entry road + pit lane). */
    public static boolean inPitRoad(float s) {
        float r = rel(s);
        return r >= START && r <= WALL_END;
    }

    private static float blendT(float r) {
        return smooth((r - BLEND_START) / (LANE_END - BLEND_START));
    }

    /** Inner edge of the race track's apron at s (narrowed beside the exit lane). */
    public static float apronInner(float s) {
        if (!inExitLane(s)) return TrackSpline.APRON_INNER;
        float r = rel(s);
        float steady = r < WALL_END + MEDIAN_GROW
                ? FastMath.interpolateLinear(smooth((r - WALL_END) / MEDIAN_GROW), TrackSpline.APRON_INNER, MEDIAN_OUTER)
                : MEDIAN_OUTER;
        return FastMath.interpolateLinear(blendT(r), steady, TRACK_EDGE);
    }

    /** Outer (track-side) edge of the pit exit lane. */
    public static float laneOuter(float s) {
        return FastMath.interpolateLinear(blendT(rel(s)), LANE_OUTER, TRACK_EDGE);
    }

    /** Inner (infield-side) edge of the pit exit lane / exit road. */
    public static float laneInner(float s) {
        float r = rel(s);
        if (r < END) return backD(s);
        return FastMath.interpolateLinear(blendT(r), LANE_INNER, TrackSpline.APRON_INNER);
    }

    /** True inside the pit complex (entry, pit lane and exit roads). */
    public static boolean inRange(float s) {
        float r = rel(s);
        return r >= START && r <= END;
    }

    /** True where the pit wall separates the track from the pit lane. */
    public static boolean pitWallAt(float s) {
        float r = rel(s);
        return r >= WALL_START && r <= WALL_END;
    }

    /** Inner (infield) edge of the pit road at s: the pit back wall line. */
    public static float backD(float s) {
        float r = FastMath.clamp(rel(s), START, END);
        if (r < WALL_START) return FastMath.interpolateLinear(smooth((r - START) / (WALL_START - START)), WALL_D, BACK_D);
        if (r > WALL_END) return FastMath.interpolateLinear(smooth((END - r) / (END - WALL_END)), LANE_INNER, BACK_D);
        return BACK_D;
    }

    /**
     * Innermost lateral position a car can legitimately reach at s: the pit back wall, the open
     * infield beside the pit exit lane (no inner wall there), or the inner wall.
     */
    public static float innerLimit(float s) {
        if (inPitRoad(s)) return backD(s);
        if (inExitLane(s)) return laneInner(s) - 25f;
        return TrackSpline.INNER_WALL;
    }

    /** Start of box {@code i} (rel s); box {@link #PLAYER_STALL} spans -12 .. +12 around the line. */
    public static float stallStart(int i) {
        return (i - STALLS / 2f) * STALL_LENGTH;
    }

    /** Painted divider between the racing surface and the pit entry lane in turn 4 (NaN where absent). */
    public static float dividerD(float s) {
        float r = rel(s);
        if (r >= ENTRY_LANE_START && r < START) {
            return FastMath.interpolateLinear(smooth((r - ENTRY_LANE_START) / 90f), TRACK_EDGE, LANE_DIVIDER_D);
        }
        return Float.NaN;
    }

    private static float smooth(float x) {
        x = FastMath.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }
}
