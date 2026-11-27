package com.racingsim.ai;

import com.jme3.math.FastMath;
import com.racingsim.physics.AeroModel;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.VehicleSpec;

/**
 * Pre-computed target speed along the racing line: the maximum steady-state cornering
 * speed on a banked turn with aerodynamic downforce, followed by a backward braking pass
 * so the AI starts slowing down before corner entry. On the road course the braking pass works
 * out each braking point from the grip (with downforce), what the turn already asks of the tires
 * and the slope; it also keeps speed profiles for fixed lanes off the line ({@link #laneAt}).
 */
public final class SpeedProfile {

    private static final float RESOLUTION = 2f;
    private static final float MAX_SPEED = 150f;
    /** Road course braking: how much of the grip the AI plans to use (tuning: {@code -DaiBrakeGrip=});
     *  the brakes' own limit is {@link VehicleSpec#maxBrakeDecel}. */
    private static float brakeConfidence() { return AiParams.get("aiBrakeGrip", 0.82f); }
    /** Road course lanes at d = -5, -2.5, 0, 2.5, 5. */
    private static final int NUM_LANES = 5;
    private static final float LANE_SPACING = 2.5f;

    private final float[] speeds;
    private final TrackSpline track;
    private final RacingLine line;
    private final VehicleSpec spec;
    private final float gripFactor, brakingDecel, brakeGrip;
    private final float[][] laneSpeeds;

    /**
     * @param gripFactor   friction coefficient the AI assumes (its "confidence")
     * @param brakingDecel deceleration assumed when planning braking zones (m/s^2)
     */
    public SpeedProfile(TrackSpline track, RacingLine line, VehicleSpec spec, AeroModel aero,
                        float gripFactor, float brakingDecel) {
        this(track, line, spec, aero, gripFactor, brakingDecel, brakeConfidence());
    }

    /**
     * @param brakeGrip road course: how much of the grip the braking points plan on (usually
     *                  {@code -DaiBrakeGrip=}, 0.82; more for a late-braking pass)
     */
    public SpeedProfile(TrackSpline track, RacingLine line, VehicleSpec spec, AeroModel aero,
                        float gripFactor, float brakingDecel, float brakeGrip) {
        this.brakeGrip = brakeGrip;
        this.track = track;
        this.line = line;
        this.spec = spec;
        this.gripFactor = gripFactor;
        this.brakingDecel = brakingDecel;
        int n = (int) Math.ceil(TrackSpline.LENGTH / RESOLUTION);
        speeds = new float[n];
        float m = spec.mass;
        float k = 0.5f * AeroModel.AIR_DENSITY * aero.liftArea;
        if (!TrackSpline.isOval()) {
            buildRoad(speeds, x -> line.pathCurvature((float) x), k);
            laneSpeeds = new float[NUM_LANES][n];
            for (int l = 0; l < NUM_LANES; l++) {
                float d = (l - NUM_LANES / 2) * LANE_SPACING;
                buildRoad(laneSpeeds[l], x -> {
                    float kc = track.curvature((float) x);
                    return kc / Math.max(1f + kc * d, 0.1f);
                }, k);
            }
            return;
        }
        laneSpeeds = null;
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

    /**
     * Road course: corner speeds for the curvatures, then backwards from every corner: how fast
     * the car can arrive and still slow down in time. The braking grip grows with downforce, but
     * whatever the turn already asks of the tires is not available for braking (friction circle),
     * and braking downhill takes longer (the runs downhill into the valley and the hairpins).
     */
    private void buildRoad(float[] out, java.util.function.DoubleUnaryOperator curvatureAt, float k) {
        int n = out.length;
        float m = spec.mass, g = 9.81f;
        float[] kabs = new float[n];
        for (int i = 0; i < n; i++) {
            float kp = Math.abs((float) curvatureAt.applyAsDouble(i * RESOLUTION));
            kabs[i] = kp;
            out[i] = kp < 1e-4f ? MAX_SPEED : cornerSpeed(m, 1f / kp, 0f, gripFactor, k);
        }
        // the AI aims a little short of the grip
        for (int pass = 0; pass < 2; pass++) {
            for (int i = n - 1; i >= 0; i--) {
                float next = out[(i + 1) % n];
                float gripAcc = gripFactor * (g + k * next * next / m);
                float lat = next * next * kabs[i];
                float used = FastMath.clamp(lat / gripAcc, 0f, 0.95f);
                float decel = Math.min(gripAcc * brakeGrip, spec.maxBrakeDecel) * FastMath.sqrt(1f - used * used);
                decel = Math.max(decel + g * track.grade(i * RESOLUTION), 2f);
                float allowed = FastMath.sqrt(next * next + 2f * decel * RESOLUTION);
                if (allowed < out[i]) out[i] = allowed;
            }
        }
    }

    /** Road course: the speed for a fixed lane (constant d) rather than the racing line. */
    public float laneAt(float s, float d) {
        if (laneSpeeds == null) return at(s);
        float x = FastMath.clamp(d / LANE_SPACING + NUM_LANES / 2, 0f, NUM_LANES - 1.001f);
        int l = (int) x;
        float u = x - l;
        float p = TrackSpline.wrapS(s) / RESOLUTION;
        int i = Math.min((int) p, laneSpeeds[l].length - 1);
        float t = p - i;
        return sampleAt(laneSpeeds[l], i, t) * (1f - u) + sampleAt(laneSpeeds[l + 1], i, t) * u;
    }

    private static float sampleAt(float[] a, int i, float t) {
        return a[i] + (a[(i + 1) % a.length] - a[i]) * t;
    }

    /** Same driver (grip confidence, braking) re-planned for a different aero setup. */
    public SpeedProfile withAero(AeroModel aero) {
        return new SpeedProfile(track, line, spec, aero, gripFactor, brakingDecel, brakeGrip);
    }

    /** The same profile braking on {@code brakeGrip} of the grip (a road course's late-braking pass). */
    public SpeedProfile withBrakeGrip(AeroModel aero, float brakeGrip) {
        return new SpeedProfile(track, line, spec, aero, gripFactor, brakingDecel, brakeGrip);
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
