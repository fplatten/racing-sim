package com.racingsim.vehicle;

/**
 * Physical parameters of the open-wheel car. Local car frame: +Z forward, +Y up,
 * +X to the car's LEFT. The rigid-body origin is the center of mass.
 */
public final class VehicleSpec {

    // ---- Mass & geometry ----
    public float mass = 780f;
    public float wheelRadius = 0.33f;
    public float frontAxleZ = 1.65f;
    public float rearAxleZ = -1.35f;
    public float frontHalfTrack = 0.86f;
    public float rearHalfTrack = 0.82f;
    /** Height of the suspension mount points above the center of mass. */
    public float mountHeight = 0.10f;

    // ---- Suspension ----
    public float restLength = 0.12f;
    public float springFront = 110_000f;
    public float springRear = 120_000f;
    public float damperBump = 5_500f;
    public float damperRebound = 6_500f;
    public float antiRollFront = 28_000f;
    public float antiRollRear = 20_000f;
    public float bumpStopStart = 0.09f;
    public float bumpStopRate = 600_000f;
    /** Height above the contact patch at which tire forces are applied (reduces jacking). */
    public float tireForceLift = 0.08f;

    // ---- Wheels / brakes ----
    public float wheelInertia = 1.4f;
    public float brakeTorqueFront = 3_200f;
    public float brakeTorqueRear = 2_200f;
    public float rollingResistance = 0.012f;
    /** Grip multipliers per axle (the rear tires are wider on an open-wheel car). */
    public float frontGrip = 1.0f;
    public float rearGrip = 1.10f;

    // ---- Steering ----
    /** Maximum road-wheel angle at standstill (rad). */
    public float maxSteerLow = 0.40f;
    /** Minimum maximum road-wheel angle at very high speed (rad). */
    public float maxSteerHigh = 0.08f;
    /** Speed (m/s) at which the steering limit has halved. */
    public float steerSpeedFalloff = 22f;

    // ---- Engine & gearbox ----
    public float[] torqueRpm = {0, 2000, 4000, 6000, 8000, 10000, 11000, 12000, 12500};
    public float[] torqueNm = {220, 250, 340, 400, 445, 470, 470, 440, 400};
    public float idleRpm = 3000f;
    public float redlineRpm = 12000f;
    public float limiterRpm = 12300f;
    public float[] gearRatios = {2.80f, 2.15f, 1.75f, 1.48f, 1.27f, 1.10f};
    public float reverseRatio = 2.9f;
    public float finalDrive = 3.2f;
    public float drivetrainEfficiency = 0.92f;
    public float shiftTime = 0.06f;
    public float autoUpshiftRpm = 11800f;
    public float autoDownshiftRpm = 7200f;

    /** Speed-sensitive steering lock (rad). */
    public float maxSteerAngle(float speed) {
        return Math.max(maxSteerHigh, maxSteerLow / (1f + Math.abs(speed) / steerSpeedFalloff));
    }

    public float engineTorque(float rpm) {
        if (rpm <= torqueRpm[0]) return torqueNm[0];
        for (int i = 1; i < torqueRpm.length; i++) {
            if (rpm <= torqueRpm[i]) {
                float t = (rpm - torqueRpm[i - 1]) / (torqueRpm[i] - torqueRpm[i - 1]);
                return torqueNm[i - 1] + t * (torqueNm[i] - torqueNm[i - 1]);
            }
        }
        return torqueNm[torqueNm.length - 1];
    }
}
