package com.racingsim.ai;

import com.jme3.math.FastMath;
import com.racingsim.track.TrackSpline;

/**
 * Classic oval racing line as a lateral offset d(s): high (near the wall) on the straights
 * approaching a turn, low (near the white line) at the apex, drifting back up on exit.
 */
public final class RacingLine {

    public static final float HIGH = 4.3f;
    public static final float LOW = -5.0f;
    /** How far before/after the turn the line starts moving (meters). */
    private static final float SPREAD = 140f;

    private final TrackSpline track;
    private final float[] centers;
    private final float halfWidth;

    public RacingLine(TrackSpline track) {
        this.track = track;
        float[] starts = track.turnStarts();
        centers = new float[starts.length];
        for (int i = 0; i < starts.length; i++) centers[i] = starts[i] + TrackSpline.TURN / 2f;
        halfWidth = TrackSpline.TURN / 2f + SPREAD;
    }

    public TrackSpline getTrack() { return track; }

    public float offset(float s) {
        float w = 0f;
        for (float c : centers) {
            float x = Math.abs(TrackSpline.deltaS(c, s)) / halfWidth;
            if (x < 1f) w = Math.max(w, 0.5f * (1f + FastMath.cos(FastMath.PI * x)));
        }
        return HIGH + (LOW - HIGH) * w;
    }
}
