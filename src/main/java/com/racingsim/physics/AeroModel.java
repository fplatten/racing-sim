package com.racingsim.physics;

/**
 * Speed-squared aerodynamic model: drag {@code 0.5*rho*v^2*Cd*A} and downforce
 * {@code 0.5*rho*v^2*Cl*A}, split between the axles, modulated by the draft factor.
 */
public final class AeroModel {

    public static final float AIR_DENSITY = 1.225f;

    /**
     * Wing settings: minimum downforce (the low-drag superspeedway trim, best top speed) to
     * maximum downforce (steeper wings: much more grip, but more drag and a lower top speed).
     * The defaults are the Indy car's; each car sets its own from its
     * {@link com.racingsim.vehicle.VehicleSpec} ({@link #setAreas}).
     */
    public float minDragArea = 0.75f, minLiftArea = 2.2f;
    public float maxDragArea = 0.98f, maxLiftArea = 4.0f;

    /** Drag area Cd*A (m^2). */
    public float dragArea = minDragArea;
    /** Lift (downforce) area Cl*A (m^2). */
    public float liftArea = minLiftArea;
    private float downforceLevel;

    /** Sets the car's drag and downforce areas at minimum and maximum wing, then minimum wing. */
    public void setAreas(float minDrag, float minLift, float maxDrag, float maxLift) {
        minDragArea = minDrag;
        minLiftArea = minLift;
        maxDragArea = maxDrag;
        maxLiftArea = maxLift;
        setDownforceLevel(0f);
    }

    /** Sets the wing level: 0 = minimum downforce (max speed) .. 1 = maximum downforce (max control). */
    public void setDownforceLevel(float level) {
        downforceLevel = Math.max(0f, Math.min(1f, level));
        dragArea = minDragArea + (maxDragArea - minDragArea) * downforceLevel;
        liftArea = minLiftArea + (maxLiftArea - minLiftArea) * downforceLevel;
    }

    /** Current wing level, 0 (min downforce) .. 1 (max downforce). */
    public float getDownforceLevel() { return downforceLevel; }
    /** Fraction of downforce on the front axle in clean air. */
    public float frontBalance = 0.40f;

    /** How strongly the draft factor reduces drag (1.0 => Cd*(1-draft)). */
    public float draftDragGain = 1.0f;
    /** How strongly the draft factor reduces front downforce ("dirty air" understeer). */
    public float draftFrontLiftGain = 0.4f;
    /** Small rear downforce loss in the wake. */
    public float draftRearLiftGain = 0.15f;

    /** Output values for one aero evaluation. */
    public static final class Result {
        public float drag;            // N, opposite to velocity
        public float downforceFront;  // N, along -body up at the front axle
        public float downforceRear;   // N, along -body up at the rear axle
        public float cdEffective;     // current Cd*A after drafting
    }

    public Result compute(float speed, float forwardSpeed, float draftFactor, Result out) {
        if (out == null) out = new Result();
        float q = 0.5f * AIR_DENSITY;
        out.cdEffective = dragArea * (1f - draftDragGain * draftFactor);
        out.drag = q * speed * speed * out.cdEffective;
        float v2 = forwardSpeed * forwardSpeed;
        out.downforceFront = q * v2 * liftArea * frontBalance * (1f - draftFrontLiftGain * draftFactor);
        out.downforceRear = q * v2 * liftArea * (1f - frontBalance) * (1f - draftRearLiftGain * draftFactor);
        return out;
    }
}
