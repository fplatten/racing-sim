package com.racingsim.ai;

import com.jme3.math.FastMath;
import com.racingsim.physics.AeroModel;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.VehicleSpec;

/**
 * Pre-computed target speed along the racing line: the maximum steady-state cornering
 * speed on a banked turn with aerodynamic downforce, followed by a backward braking pass
 * so the AI starts slowing down before corner entry.
 */
public final class SpeedProfile {

    private static final float RESOLUTION = 2f;
    private static final float MAX_SPEED = 150f;

    private final float[] speeds;
    private final TrackSpline track;
    private final RacingLine line;
    private final VehicleSpec spec;
    private final float gripFactor, brakingDecel;

    /**
     * @param gripFactor   friction coefficient the AI assumes (its "confidence")
     * @param brakingDecel deceleration assumed when planning braking zones (m/s^2)
     */
    public SpeedProfile(TrackSpline track, RacingLine line, VehicleSpec spec, AeroModel aero,
                        float gripFactor, float brakingDecel) {
        this.track = track;
        this.line = line;
        this.spec = spec;
        this.gripFactor = gripFactor;
        this.brakingDecel = brakingDecel;
        int n = (int) Math.ceil(TrackSpline.LENGTH / RESOLUTION);
        speeds = new float[n];
        float m = spec.mass;
        float k = 0.5f * AeroModel.AIR_DENSITY * aero.liftArea;
        for (int i = 0; i < n; i++) {
            float s = i * RESOLUTION;
            float r = track.radius(s, line.offset(s));
            speeds[i] = Float.isInfinite(r) ? MAX_SPEED : cornerSpeed(m, r, track.bank(s), gripFactor, k);
        }
        // backward braking pass (twice around to handle the wrap)
        for (int pass = 0; pass < 2; pass++) {
            for (int i = n - 1; i >= 0; i--) {
                float next = speeds[(i + 1) % n];
                float allowed = FastMath.sqrt(next * next + 2f * brakingDecel * RESOLUTION);
                if (allowed < speeds[i]) speeds[i] = allowed;
            }
        }
    }

    /** Same driver (grip confidence, braking) re-planned for a different aero setup. */
    public SpeedProfile withAero(AeroModel aero) {
        return new SpeedProfile(track, line, spec, aero, gripFactor, brakingDecel);
    }

    /**
     * Max speed on a banked curve where downforce adds to the normal load:
     * v^2 * (m(cos - mu sin)/R - mu k) = m g (sin + mu cos)
     */
    static float cornerSpeed(float m, float r, float bank, float mu, float k) {
        float cos = FastMath.cos(bank), sin = FastMath.sin(bank);
        float denom = m * (cos - mu * sin) / r - mu * k;
        if (denom <= 0f) return MAX_SPEED;
        return Math.min(MAX_SPEED, FastMath.sqrt(m * 9.81f * (sin + mu * cos) / denom));
    }

    public float at(float s) {
        float x = TrackSpline.wrapS(s) / RESOLUTION;
        int i = (int) x;
        float t = x - i;
        float a = speeds[i % speeds.length], b = speeds[(i + 1) % speeds.length];
        return a + (b - a) * t;
    }
}
