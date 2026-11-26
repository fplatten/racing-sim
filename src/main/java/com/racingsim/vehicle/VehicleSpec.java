package com.racingsim.vehicle;

/**
 * Physical parameters of the open-wheel car. Local car frame: +Z forward, +Y up,
 * +X to the car's LEFT. The rigid-body origin is the center of mass.
 * The defaults are the 1995-style Indy car (the oval's car); {@link #formula()} is the 1995-style
 * Formula car (the road course's car). {@link #forTrack()} picks the car for the selected track.
 */
public final class VehicleSpec {

    /** Which car: the Indy car races the oval, the Formula car the road course. */
    public enum CarType { INDY, FORMULA }

    public CarType type = CarType.INDY;

    // ---- Mass & geometry ----
    public float mass = 780f;
    public float wheelRadius = 0.33f;
    public float frontAxleZ = 1.65f;
    public float rearAxleZ = -1.35f;
    public float frontHalfTrack = 0.86f;
    public float rearHalfTrack = 0.82f;
    /** Height of the suspension mount points above the center of mass. */
    public float mountHeight = 0.10f;
    /** The collider: a 2.0 x 0.5 m box this long (m), 0.12 m above the center of mass, its middle
     *  this far ahead of the center of mass (m). */
    public float bodyLength = 5.0f;
    public float bodyCenterAhead = -0.03f;

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

    // ---- Wheels / brakes / tires ----
    public float wheelInertia = 1.4f;
    public float brakeTorqueFront = 3_200f;
    public float brakeTorqueRear = 2_200f;
    public float rollingResistance = 0.012f;
    /** Grip multipliers per axle (the rear tires are wider on an open-wheel car). */
    public float frontGrip = 1.0f;
    public float rearGrip = 1.10f;
    /** The tires' peak friction coefficient and how it falls off with load ({@link com.racingsim.physics.TireModel}). */
    public float tireMu = 1.55f;
    public float tireLoadSensitivity = 0.07f;
    /** The most the brakes manage (m/s^2): the road course AI plans its braking with it. */
    public float maxBrakeDecel = 36f;

    // ---- Aero: drag and downforce areas (Cd A, Cl A in m^2) from minimum to maximum wing ----
    public float minDragArea = 0.75f, minLiftArea = 2.2f;
    public float maxDragArea = 0.98f, maxLiftArea = 4.0f;

    // ---- Steering ----
    /** Maximum road-wheel angle at standstill (rad). */
    public float maxSteerLow = 0.40f;
    /** Minimum maximum road-wheel angle at very high speed (rad). */
    public float maxSteerHigh = 0.08f;
    /** Speed (m/s) at which the steering limit has halved. */
    public float steerSpeedFalloff = 22f;

    // ---- Engine & gearbox ----
    public float[] torqueRpm = {0, 2000, 4000, 6000, 8000, 10000, 11000, 12000, 12500};
    /** Indy turbo V8 at speedway boost: ~870 hp at 12,000 rpm, at most ~235 mph on the straights
     *  without a tow (minimum wing); the tow takes it to ~243 (the old curve x 1.17 made ~740 hp
     *  and 215 mph). Tuning: {@code -DpowerScale=} scales it. */
    public float[] torqueNm = {257, 293, 397, 467, 520, 549, 549, 514, 467};
    public float idleRpm = 3000f;
    public float redlineRpm = 12000f;
    public float limiterRpm = 12300f;
    public float[] gearRatios = {2.80f, 2.15f, 1.75f, 1.48f, 1.27f, 1.10f};
    public float reverseRatio = 2.9f;
    /** Geared like a 1995 Indy car: the engine stays in its power band (11,000-12,000 rpm) all the
     *  way round (3.2 left the turns at ~9,000 rpm, 20% down on power); the limiter (12,300 rpm)
     *  is ~243 mph in a tow. On a road course it runs {@link #roadFinalDrive} ({@link #forTrack}).
     *  Tuning: {@code -DfinalDrive=}. */
    public float finalDrive = 3.55f;
    /** Final drive on a road course: shorter, for the slow turns and the climb up the long
     *  straight (~205 mph on the limiter instead of ~243). */
    public float roadFinalDrive = 4.2f;
    public float drivetrainEfficiency = 0.92f;
    public float shiftTime = 0.06f;
    public float autoUpshiftRpm = 11800f;
    public float autoDownshiftRpm = 7200f;

    /** Speed-sensitive steering lock (rad). */
    public float maxSteerAngle(float speed) {
        return Math.max(maxSteerHigh, maxSteerLow / (1f + Math.abs(speed) / steerSpeedFalloff));
    }

    /** The 1995-style Indy car (the defaults). */
    public static VehicleSpec indy() {
        return new VehicleSpec();
    }

    /**
     * The 1995-style Formula car ({@code blender/make_f1_car.py} builds its model): 600 kg with the
     * driver, 4.42 m (14.5 ft) long, 2.90 m wheelbase, 2.00 m wide over the tires, ~700 hp, 215 mph
     * flat out without a tow, 0-100 mph in about 3.5 s, 5-6 g on the brakes from top speed.
     */
    public static VehicleSpec formula() {
        VehicleSpec s = new VehicleSpec();
        s.type = CarType.FORMULA;
        s.mass = 600f;
        // 2.90 m wheelbase, 55% of the weight on the rear axle; front track 1.69 m, rear 1.61 m:
        // 2.00 m over the 0.30 / 0.38 m wide tires
        s.frontAxleZ = 1.60f;
        s.rearAxleZ = -1.30f;
        s.frontHalfTrack = 0.845f;
        s.rearHalfTrack = 0.805f;
        // the collider runs from the front wing to the rear wing
        s.bodyLength = 4.42f;
        s.bodyCenterAhead = 0.24f;
        // carbon brakes: enough for all the grip at top speed with full downforce (~6 g), 42% on
        // the front like the grip (55% of the weight and 60% of the downforce on the wider rear tires)
        s.brakeTorqueFront = 2_900f;
        s.brakeTorqueRear = 4_000f;
        s.maxBrakeDecel = 58f;
        // slicks under downforce; the rear ones are wider (0.38 m against 0.30 m)
        s.rearGrip = 1.25f;
        s.tireMu = 1.90f;
        s.tireLoadSensitivity = 0.06f;
        // 1,000-1,500 kg of downforce at 165-200 mph; the drag sets the 215 mph top speed at minimum wing
        s.minDragArea = 0.82f;
        s.minLiftArea = 2.9f;
        s.maxDragArea = 1.0f;
        s.maxLiftArea = 3.4f;
        // ~700 hp at 12,000 rpm; peak power in top gear at 215 mph, the limiter ~220 mph in a tow
        s.torqueNm = new float[]{208, 237, 322, 378, 421, 445, 445, 416, 378};
        s.finalDrive = 3.92f;
        s.roadFinalDrive = 3.92f;
        return s;
    }

    /** The car raced on the selected track: the Formula car on the road course, the Indy car on
     *  the oval. Tuning: {@code -Dcar=indy|f1} (gradle {@code -Pcar=}) races that car anywhere. */
    public static CarType selectedType() {
        String key = System.getProperty("car");
        if (key == null || key.isBlank()) {
            return com.racingsim.track.TrackSpline.isRoadCourse() ? CarType.FORMULA : CarType.INDY;
        }
        return key.trim().equalsIgnoreCase("indy") ? CarType.INDY : CarType.FORMULA;
    }

    /** The car for the selected track ({@link #selectedType}) with that track's gearing. */
    public static VehicleSpec forTrack() {
        VehicleSpec s = selectedType() == CarType.FORMULA ? formula() : indy();
        if (com.racingsim.track.TrackSpline.isRoadCourse()) s.finalDrive = s.roadFinalDrive;
        s.finalDrive = tuning("finalDrive", s.finalDrive);
        return s;
    }

    /** The lowest grip (friction the speed profile plans with) an AI driver of this car assumes:
     *  the Indy car's tires give 1.55, the Formula car's 1.9. */
    public float aiGripBase() {
        return type == CarType.FORMULA ? 1.50f : 1.22f;
    }

    /** Tuning: {@code -DpowerScale=<x>} multiplies the engine torque curve of every car. */
    private static final float POWER_SCALE = tuning("powerScale", 1f);

    public float engineTorque(float rpm) {
        if (rpm <= torqueRpm[0]) return POWER_SCALE * torqueNm[0];
        for (int i = 1; i < torqueRpm.length; i++) {
            if (rpm <= torqueRpm[i]) {
                float t = (rpm - torqueRpm[i - 1]) / (torqueRpm[i] - torqueRpm[i - 1]);
                return POWER_SCALE * (torqueNm[i - 1] + t * (torqueNm[i] - torqueNm[i - 1]));
            }
        }
        return POWER_SCALE * torqueNm[torqueNm.length - 1];
    }

    /** A tuning override given as a system property ({@code -Dname=value}), else {@code def}. */
    public static float tuning(String name, float def) {
        String v = System.getProperty(name);
        return v == null ? def : Float.parseFloat(v.trim());
    }
}
