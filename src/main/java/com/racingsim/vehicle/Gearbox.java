package com.racingsim.vehicle;

/**
 * 6-speed sequential gearbox with optional automatic shifting and a reverse gear
 * (gear 0 = reverse, 1..6 = forward gears).
 */
public final class Gearbox {

    private final VehicleSpec spec;
    private int gear = 1;
    private boolean automatic = true;
    private float shiftTimer;
    private float shiftCooldown;
    private float reverseHold;

    public Gearbox(VehicleSpec spec) {
        this.spec = spec;
    }

    public int getGear() { return gear; }
    public boolean isAutomatic() { return automatic; }
    public void setAutomatic(boolean auto) { this.automatic = auto; }
    public boolean isShifting() { return shiftTimer > 0f; }
    public int topGear() { return spec.gearRatios.length; }

    /** Signed total ratio engine -> wheel (negative in reverse). */
    public float totalRatio() {
        if (gear == 0) return -spec.reverseRatio * spec.finalDrive;
        return spec.gearRatios[gear - 1] * spec.finalDrive;
    }

    public void setGear(int g) {
        gear = Math.max(0, Math.min(topGear(), g));
    }

    /** Picks the forward gear best suited to a given road speed (for resets / rolling start). */
    public void selectForSpeed(float speed) {
        for (int g = 1; g <= topGear(); g++) {
            float rpm = rpmAt(speed, g);
            if (rpm < spec.autoUpshiftRpm * 0.93f) {
                gear = g;
                return;
            }
        }
        gear = topGear();
    }

    /** After a teleport: the gear for this speed, no shift in progress. */
    public void reset(float speed) {
        selectForSpeed(speed);
        shiftTimer = shiftCooldown = reverseHold = 0f;
    }

    private float rpmAt(float speed, int g) {
        float wheelOmega = speed / spec.wheelRadius;
        return wheelOmega * spec.gearRatios[g - 1] * spec.finalDrive * 60f / (2f * (float) Math.PI);
    }

    private void shiftTo(int g) {
        if (g == gear) return;
        gear = g;
        shiftTimer = spec.shiftTime;
        shiftCooldown = 0.35f;
    }

    /**
     * @param rpm          current engine rpm
     * @param forwardSpeed longitudinal car speed (m/s)
     */
    public void update(float dt, VehicleInput in, float rpm, float forwardSpeed) {
        shiftTimer = Math.max(0f, shiftTimer - dt);
        shiftCooldown = Math.max(0f, shiftCooldown - dt);

        if (in.shiftUpRequest) {
            in.shiftUpRequest = false;
            if (gear < topGear()) shiftTo(gear + 1);
        }
        if (in.shiftDownRequest) {
            in.shiftDownRequest = false;
            if (gear > 1 || (gear == 1 && Math.abs(forwardSpeed) < 2f)) shiftTo(gear - 1);
        }

        // Reverse handling (both modes): hold brake at a standstill to engage reverse,
        // press throttle while in reverse to go back to first.
        if (gear >= 1 && !in.pitHold && Math.abs(forwardSpeed) < 0.6f && in.brake > 0.5f && in.throttle < 0.05f) {
            reverseHold += dt;
            if (reverseHold > 0.6f) {
                shiftTo(0);
                reverseHold = 0f;
            }
        } else {
            reverseHold = 0f;
        }
        if (gear == 0 && in.throttle > 0.05f && forwardSpeed > -2f) {
            shiftTo(1);
        }

        if (!automatic || gear == 0 || shiftCooldown > 0f) return;
        if (rpm > spec.autoUpshiftRpm && gear < topGear()) {
            shiftTo(gear + 1);
        } else if (gear > 1) {
            float downRpm = in.brake > 0.1f ? spec.autoDownshiftRpm + 1200f : spec.autoDownshiftRpm;
            // only downshift if the lower gear would not over-rev
            float lowerRpm = rpm * spec.gearRatios[gear - 2] / spec.gearRatios[gear - 1];
            if (rpm < downRpm && lowerRpm < spec.autoUpshiftRpm - 600f) {
                shiftTo(gear - 1);
            }
        }
    }
}
