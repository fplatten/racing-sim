package com.racingsim.physics;

import com.jme3.math.FastMath;

/**
 * Pacejka "Magic Formula" tire model with load sensitivity and a friction-ellipse
 * combination of lateral and longitudinal forces.
 *
 * <pre>
 *   F = D * sin(C * atan(B*x - E*(B*x - atan(B*x))))     D = mu(Fz) * Fz
 * </pre>
 * Lateral input x = slip angle (rad), longitudinal input x = slip ratio.
 */
public final class TireModel {

    // Lateral coefficients (peak at ~6.5 deg slip angle)
    public float latB = 18f, latC = 1.6f, latE = 0.6f;
    // Longitudinal coefficients (peak at ~10 % slip ratio)
    public float longB = 20f, longC = 1.6f, longE = 0.6f;

    /** Peak friction coefficient at the nominal load. */
    public float mu = 1.55f;
    /** Nominal vertical load for the load-sensitivity curve (N). */
    public float nominalLoad = 2500f;
    /** Fractional loss of mu per unit of (Fz/Fz0 - 1). */
    public float loadSensitivity = 0.07f;

    /** Output of {@link #compute}. */
    public static final class Result {
        public float fx;        // longitudinal force (N), + = forward along the wheel
        public float fy;        // lateral force (N), + = towards the wheel's left
        public float muEff;     // effective friction coefficient used
        public float usage;     // combined force / available grip (0..1+)
    }

    public static float magic(float x, float b, float c, float e) {
        float bx = b * x;
        return FastMath.sin(c * FastMath.atan(bx - e * (bx - FastMath.atan(bx))));
    }

    public float effectiveMu(float fz) {
        float m = mu * (1f - loadSensitivity * (fz / nominalLoad - 1f));
        return FastMath.clamp(m, mu * 0.70f, mu * 1.1f);
    }

    /**
     * @param slipAngle slip angle (rad); positive when the contact patch slides to the left
     * @param slipRatio (wheel surface speed - ground speed) / |ground speed|
     * @param fz        vertical load (N)
     */
    public Result compute(float slipAngle, float slipRatio, float fz, Result out) {
        return compute(slipAngle, slipRatio, fz, 1f, out);
    }

    /**
     * @param gripScale per-tire grip multiplier (e.g. wider rear tires)
     */
    public Result compute(float slipAngle, float slipRatio, float fz, float gripScale, Result out) {
        if (out == null) out = new Result();
        if (fz <= 0f) {
            out.fx = out.fy = out.usage = 0f;
            out.muEff = 0f;
            return out;
        }
        float m = effectiveMu(fz) * gripScale;
        float dMax = m * fz;
        float fx = dMax * magic(slipRatio, longB, longC, longE);
        float fy = -dMax * magic(slipAngle, latB, latC, latE);
        // friction ellipse: combined force can never exceed the available grip
        float mag = FastMath.sqrt(fx * fx + fy * fy);
        if (mag > dMax) {
            float k = dMax / mag;
            fx *= k;
            fy *= k;
        }
        out.fx = fx;
        out.fy = fy;
        out.muEff = m;
        out.usage = mag / dMax;
        return out;
    }
}
