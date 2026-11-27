package com.racingsim.vehicle;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.PhysicsRayTestResult;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.racingsim.physics.AeroModel;
import com.racingsim.physics.TireModel;
import com.racingsim.track.TrackBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom vehicle dynamics applied to a Bullet rigid body at a fixed 240 Hz step.
 * <ul>
 *   <li>4-wheel raycast suspension (spring + bump/rebound damper + anti-roll bars + bump stop)</li>
 *   <li>Pacejka tire forces from slip angle / slip ratio / vertical load, with sub-stepped
 *       wheel-spin integration for stability at low speed</li>
 *   <li>Speed-squared drag and downforce, modulated by the drafting factor</li>
 *   <li>Engine torque curve, 6-speed sequential gearbox (auto or manual), RWD, brakes</li>
 * </ul>
 * All forces are applied as impulses ({@code F * dt}) so they act exactly once per sub-step.
 * Player and AI cars use this identical component.
 */
public final class VehiclePhysics {

    public static final int FL = 0, FR = 1, RL = 2, RR = 3;
    private static final float G = 9.81f;
    private static final int WHEEL_SUBSTEPS = 16;

    /** Per-wheel state (also read by the visuals and telemetry). */
    public static final class Wheel {
        public final Vector3f mountLocal;
        public final boolean front;
        public final boolean driven;
        public float omega;            // rad/s
        public float spinAngle;        // rad (visual)
        public float steer;            // rad
        public float suspensionLength; // mount -> wheel center (m)
        public float compression;
        float prevCompression;
        public float load;             // N
        public float slipAngle;        // rad
        public float slipRatio;
        public boolean contact;
        /** True when the wheel is on the asphalt (false = grass). */
        public boolean onTrack = true;
        final Vector3f contactPoint = new Vector3f();
        final Vector3f normal = new Vector3f(0, 1, 0);

        Wheel(Vector3f mountLocal, boolean front, boolean driven) {
            this.mountLocal = mountLocal;
            this.front = front;
            this.driven = driven;
        }
    }

    private final VehicleSpec spec;
    private final PhysicsRigidBody body;
    private final PhysicsSpace space;
    /** Static surfaces the wheels can stand on: [0] = track (asphalt), others = grass. */
    private final PhysicsCollisionObject[] grounds;
    /** Grip / rolling-drag multipliers on grass compared with asphalt. */
    private static final float GRASS_GRIP = 0.55f, GRASS_ROLLING = 6f;
    private float squeal;
    private final TireModel tire = new TireModel();
    private final AeroModel aero = new AeroModel();
    private final Gearbox gearbox;
    private final VehicleInput input;
    private final Wheel[] wheels = new Wheel[4];

    private float draftFactor;

    // telemetry
    private float engineRpm;
    private float speed;
    private float forwardSpeed;
    private float lateralG;
    private float longitudinalG;
    private float steerAngle;
    private final AeroModel.Result aeroOut = new AeroModel.Result();

    // scratch
    private final Vector3f pos = new Vector3f(), vel = new Vector3f(), angVel = new Vector3f();
    private final Vector3f fwd = new Vector3f(), up = new Vector3f(), left = new Vector3f();
    private final Vector3f prevVel = new Vector3f();
    private final Quaternion rot = new Quaternion();
    private final Vector3f tmp = new Vector3f(), tmp2 = new Vector3f(), rel = new Vector3f();
    private final Vector3f from = new Vector3f(), to = new Vector3f();
    private final List<PhysicsRayTestResult> hits = new ArrayList<>();
    private final TireModel.Result tireOut = new TireModel.Result();
    private boolean hasPrevVel;
    // body velocity right after this car's own impulses of the previous tick: what changed since
    // then (besides gravity) was done by collisions in the Bullet step
    private final Vector3f postVel = new Vector3f(), postAngVel = new Vector3f();
    private boolean hasPost;
    /** Share of a collision's roll / pitch kick and upward kick the body keeps (see {@link #absorbImpact}). */
    static final float IMPACT_ROLL_KEEP = 0.25f, IMPACT_LIFT_KEEP = 0.2f;
    /** Velocity change in one tick (m/s) above which it counts as an impact rather than a resting contact. */
    private static final float IMPACT_DV = 0.6f, IMPACT_DW = 0.4f;

    public VehiclePhysics(VehicleSpec spec, PhysicsRigidBody body, PhysicsSpace space,
                          VehicleInput input, PhysicsCollisionObject... grounds) {
        this.spec = spec;
        this.body = body;
        this.space = space;
        this.grounds = grounds;
        this.input = input;
        this.gearbox = new Gearbox(spec);
        // this car's tires and aero (the Indy car's or the Formula car's)
        tire.mu = spec.tireMu;
        tire.loadSensitivity = spec.tireLoadSensitivity;
        aero.setAreas(spec.minDragArea, spec.minLiftArea, spec.maxDragArea, spec.maxLiftArea);
        float my = spec.mountHeight;
        wheels[FL] = new Wheel(new Vector3f(spec.frontHalfTrack, my, spec.frontAxleZ), true, false);
        wheels[FR] = new Wheel(new Vector3f(-spec.frontHalfTrack, my, spec.frontAxleZ), true, false);
        wheels[RL] = new Wheel(new Vector3f(spec.rearHalfTrack, my, spec.rearAxleZ), false, true);
        wheels[RR] = new Wheel(new Vector3f(-spec.rearHalfTrack, my, spec.rearAxleZ), false, true);
        for (Wheel w : wheels) w.suspensionLength = spec.restLength;
    }

    // ------------------------------------------------------------------ accessors
    public VehicleSpec getSpec() { return spec; }
    public Gearbox getGearbox() { return gearbox; }
    public Wheel[] getWheels() { return wheels; }
    public TireModel getTireModel() { return tire; }
    public AeroModel getAeroModel() { return aero; }
    public float getDraftFactor() { return draftFactor; }
    public void setDraftFactor(float f) { draftFactor = f; }
    public float getEngineRpm() { return engineRpm; }
    public float getSpeed() { return speed; }
    public float getForwardSpeed() { return forwardSpeed; }
    public float getLateralG() { return lateralG; }
    public float getLongitudinalG() { return longitudinalG; }
    public float getSteerAngle() { return steerAngle; }
    /** Current drag reduction from slipstreaming (0..1). */
    public float getDragReduction() { return aero.draftDragGain * draftFactor; }

    /** Average rear slip ratio magnitude (fraction). */
    public float getRearSlipRatio() {
        return (Math.abs(wheels[RL].slipRatio) + Math.abs(wheels[RR].slipRatio)) * 0.5f;
    }

    /** Average front slip angle magnitude (rad). */
    public float getFrontSlipAngle() {
        return (Math.abs(wheels[FL].slipAngle) + Math.abs(wheels[FR].slipAngle)) * 0.5f;
    }

    /** Average rear slip angle magnitude (rad). */
    public float getRearSlipAngle() {
        return (Math.abs(wheels[RL].slipAngle) + Math.abs(wheels[RR].slipAngle)) * 0.5f;
    }

    /** Re-initializes wheel state after a teleport (reset / rolling start). */
    public void resetState(float newSpeed) {
        for (Wheel w : wheels) {
            w.omega = newSpeed / spec.wheelRadius;
            float staticComp = spec.mass * G / 4f / (w.front ? spec.springFront : spec.springRear);
            w.compression = w.prevCompression = staticComp;
            w.suspensionLength = spec.restLength - staticComp;
            w.slipAngle = w.slipRatio = 0f;
        }
        gearbox.reset(newSpeed);
        engineRpm = Math.max(spec.idleRpm, newSpeed / spec.wheelRadius * gearbox.totalRatio() * 60f / FastMath.TWO_PI);
        hasPrevVel = false;
        hasPost = false;
        lateralG = longitudinalG = 0f;
    }

    // ------------------------------------------------------------------ simulation
    public void step(float dt) {
        body.activate();
        body.getPhysicsLocation(pos);
        body.getPhysicsRotation(rot);
        body.getLinearVelocity(vel);
        body.getAngularVelocity(angVel);
        rot.mult(Vector3f.UNIT_Z, fwd);
        rot.mult(Vector3f.UNIT_Y, up);
        rot.mult(Vector3f.UNIT_X, left);
        absorbImpact(dt);

        speed = vel.length();
        forwardSpeed = vel.dot(fwd);

        updateAccelerationTelemetry(dt);

        // ---- steering (speed-sensitive lock) ----
        steerAngle = FastMath.clamp(input.steer, -1f, 1f) * spec.maxSteerAngle(forwardSpeed);
        wheels[FL].steer = wheels[FR].steer = steerAngle;
        wheels[RL].steer = wheels[RR].steer = 0f;

        // ---- powertrain ----
        float rearOmega = (wheels[RL].omega + wheels[RR].omega) * 0.5f;
        float ratio = gearbox.totalRatio();
        float rawRpm = Math.abs(rearOmega * ratio) * 60f / FastMath.TWO_PI;
        gearbox.update(dt, input, Math.max(rawRpm, spec.idleRpm), forwardSpeed);
        ratio = gearbox.totalRatio();
        rawRpm = Math.abs(rearOmega * ratio) * 60f / FastMath.TWO_PI;
        boolean reverse = gearbox.getGear() == 0;
        float throttle = FastMath.clamp(reverse ? input.brake : input.throttle, 0f, 1f);
        float brake = FastMath.clamp(reverse ? input.throttle : input.brake, 0f, 1f);
        if (input.pitHold && throttle < 0.05f) brake = 1f;
        if (gearbox.isShifting()) throttle = 0f;

        // the clutch slips below idle so the engine can always launch the car
        float rpm = Math.max(rawRpm, spec.idleRpm + throttle * 2500f * FastMath.clamp(1f - rawRpm / spec.idleRpm, 0f, 1f));
        engineRpm = engineRpm + (rpm - engineRpm) * Math.min(1f, dt * 30f);
        float engineTorque = rawRpm > spec.limiterRpm ? 0f : spec.engineTorque(rpm) * throttle;
        if (throttle < 0.05f) {
            engineTorque -= 0.07f * spec.engineTorque(rpm) * FastMath.clamp(rawRpm / spec.redlineRpm, 0f, 1f);
        }
        float driveTorquePerWheel = engineTorque * ratio * spec.drivetrainEfficiency * 0.5f;

        // ---- aerodynamics (draft factor already evaluated for this tick) ----
        aero.compute(speed, forwardSpeed, draftFactor, aeroOut);

        // ---- suspension geometry: raycast each wheel ----
        float rayLen = spec.restLength + spec.wheelRadius + 0.05f;
        for (Wheel w : wheels) {
            rot.mult(w.mountLocal, from).addLocal(pos);
            to.set(up).multLocal(-rayLen).addLocal(from);
            float frac = castToGround(from, to, w.normal, w);
            w.prevCompression = w.compression;
            if (frac >= 0f) {
                float t = frac * rayLen;
                float susp = t - spec.wheelRadius;
                if (susp <= spec.restLength && w.normal.dot(up) > 0.5f) {
                    w.contact = true;
                    w.suspensionLength = Math.max(susp, 0f);
                    w.compression = spec.restLength - w.suspensionLength;
                    w.contactPoint.set(up).multLocal(-t).addLocal(from);
                    continue;
                }
            }
            w.contact = false;
            w.suspensionLength = spec.restLength;
            w.compression = 0f;
        }

        // ---- suspension forces ----
        float[] springForce = new float[4];
        for (int i = 0; i < 4; i++) {
            Wheel w = wheels[i];
            if (!w.contact) { springForce[i] = 0f; continue; }
            float k = w.front ? spec.springFront : spec.springRear;
            float compVel = (w.compression - w.prevCompression) / dt;
            float c = compVel > 0 ? spec.damperBump : spec.damperRebound;
            float f = k * w.compression + c * compVel;
            if (w.compression > spec.bumpStopStart) {
                f += spec.bumpStopRate * (w.compression - spec.bumpStopStart);
            }
            springForce[i] = f;
        }
        // anti-roll bars
        float arbF = spec.antiRollFront * (wheels[FL].compression - wheels[FR].compression);
        float arbR = spec.antiRollRear * (wheels[RL].compression - wheels[RR].compression);
        if (wheels[FL].contact) springForce[FL] += arbF;
        if (wheels[FR].contact) springForce[FR] -= arbF;
        if (wheels[RL].contact) springForce[RL] += arbR;
        if (wheels[RR].contact) springForce[RR] -= arbR;

        for (int i = 0; i < 4; i++) {
            Wheel w = wheels[i];
            float fz = FastMath.clamp(springForce[i], 0f, 40_000f);
            w.load = fz;
            if (w.contact && fz > 0f) {
                rot.mult(w.mountLocal, rel);
                tmp.set(up).multLocal(fz * dt);
                body.applyImpulse(tmp, rel);
            }
            float driveT = w.driven ? driveTorquePerWheel : 0f;
            float brakeT = brake * (w.front ? spec.brakeTorqueFront : spec.brakeTorqueRear);
            updateTire(w, driveT, brakeT, dt);
        }

        updateSqueal(dt);

        // ---- aero forces ----
        if (speed > 0.1f) {
            tmp.set(vel).multLocal(-aeroOut.drag * dt / speed);
            body.applyCentralImpulse(tmp);
        }
        rel.set(0, 0, spec.frontAxleZ);
        rot.multLocal(rel);
        tmp.set(up).multLocal(-aeroOut.downforceFront * dt);
        body.applyImpulse(tmp, rel);
        rel.set(0, 0, spec.rearAxleZ);
        rot.multLocal(rel);
        tmp.set(up).multLocal(-aeroOut.downforceRear * dt);
        body.applyImpulse(tmp, rel);
        body.getLinearVelocity(postVel);
        body.getAngularVelocity(postAngVel);
        hasPost = true;
    }

    /**
     * Crash energy absorption. The car body is a rigid box, so a wall or car contact hands all of
     * the impact straight back as rotation and lift: a 100 m/s brush with the wall would roll the
     * car over or throw it meters into the air. A real car's tires, suspension and crushable
     * bodywork soak most of that up and it slides along the wall on its wheels. So when a Bullet
     * step changed the velocity by an impact-sized amount, only part of the collision's roll /
     * pitch kick and of its upward kick (along the car's up axis) is kept; the yaw (spin) and the
     * sideways / braking parts of the impact stay fully physical.
     */
    private void absorbImpact(float dt) {
        if (!hasPost) return;
        Vector3f dv = tmp.set(vel).subtractLocal(postVel);
        dv.y += G * dt;                                         // remove gravity
        Vector3f dw = tmp2.set(angVel).subtractLocal(postAngVel);
        if (dv.length() < IMPACT_DV && dw.length() < IMPACT_DW) return;
        float dRoll = dw.dot(fwd), dPitch = dw.dot(left), dUp = dv.dot(up);
        angVel.subtractLocal(fwd.x * dRoll * (1 - IMPACT_ROLL_KEEP), fwd.y * dRoll * (1 - IMPACT_ROLL_KEEP), fwd.z * dRoll * (1 - IMPACT_ROLL_KEEP));
        angVel.subtractLocal(left.x * dPitch * (1 - IMPACT_ROLL_KEEP), left.y * dPitch * (1 - IMPACT_ROLL_KEEP), left.z * dPitch * (1 - IMPACT_ROLL_KEEP));
        if (dUp > 0f) {
            float cut = dUp * (1 - IMPACT_LIFT_KEEP);
            vel.subtractLocal(up.x * cut, up.y * cut, up.z * cut);
        }
        body.setLinearVelocity(vel);
        body.setAngularVelocity(angVel);
    }

    /** Tire forces for one wheel (wheel spin integrated in sub-steps). */
    private void updateTire(Wheel w, float driveTorque, float brakeTorque, float dt) {
        float r = spec.wheelRadius;
        float h = dt / WHEEL_SUBSTEPS;

        if (!w.contact || w.load <= 0f) {
            for (int k = 0; k < WHEEL_SUBSTEPS; k++) {
                w.omega += driveTorque / spec.wheelInertia * h;
                w.omega = applyBrake(w.omega, brakeTorque + 2f, h);
            }
            w.spinAngle = (w.spinAngle + w.omega * dt) % FastMath.TWO_PI;
            w.slipAngle = 0f;
            w.slipRatio = 0f;
            return;
        }

        // wheel heading projected onto the ground plane
        Vector3f n = w.normal;
        tmp.set(fwd).multLocal(FastMath.cos(w.steer)).addLocal(tmp2.set(left).multLocal(FastMath.sin(w.steer)));
        Vector3f wheelFwd = tmp.subtractLocal(tmp2.set(n).multLocal(tmp.dot(n))).normalizeLocal();
        Vector3f wheelLeft = n.cross(wheelFwd, new Vector3f());

        // velocity of the contact patch
        Vector3f arm = w.contactPoint.subtract(pos);
        Vector3f vPoint = angVel.cross(arm).addLocal(vel);
        float vx = vPoint.dot(wheelFwd);
        float vy = vPoint.dot(wheelLeft);

        w.slipAngle = FastMath.atan2(vy, Math.max(Math.abs(vx), 3f));
        float denom = Math.max(Math.abs(vx), 5f);
        float surfaceGrip = w.onTrack ? 1f : GRASS_GRIP;
        float rollTorque = spec.rollingResistance * w.load * r * (w.onTrack ? 1f : GRASS_ROLLING);

        float fxSum = 0f, fySum = 0f;
        for (int k = 0; k < WHEEL_SUBSTEPS; k++) {
            float slipRatio = FastMath.clamp((w.omega * r - vx) / denom, -1f, 2f);
            tire.compute(w.slipAngle, slipRatio, w.load, (w.front ? spec.frontGrip : spec.rearGrip) * surfaceGrip, tireOut);
            fxSum += tireOut.fx;
            fySum += tireOut.fy;
            w.omega += (driveTorque - tireOut.fx * r) / spec.wheelInertia * h;
            w.omega = applyBrake(w.omega, brakeTorque + rollTorque, h);
            w.slipRatio = slipRatio;
        }
        float fx = fxSum / WHEEL_SUBSTEPS;
        float fy = fySum / WHEEL_SUBSTEPS;
        w.spinAngle = (w.spinAngle + w.omega * dt) % FastMath.TWO_PI;

        Vector3f force = wheelFwd.mult(fx).addLocal(wheelLeft.multLocal(fy));
        arm.addLocal(up.x * spec.tireForceLift, up.y * spec.tireForceLift, up.z * spec.tireForceLift);
        body.applyImpulse(force.multLocal(dt), arm);
    }

    /** Applies a torque that always opposes rotation and can stop, but never reverse, the wheel. */
    private float applyBrake(float omega, float torque, float h) {
        float delta = torque / spec.wheelInertia * h;
        if (Math.abs(omega) <= delta) return 0f;
        return omega - Math.signum(omega) * delta;
    }

    /** Ray test against the static ground surfaces only (never other cars); returns the hit fraction or -1. */
    private float castToGround(Vector3f a, Vector3f b, Vector3f normalOut, Wheel w) {
        hits.clear();
        space.rayTest(a, b, hits);
        float best = Float.MAX_VALUE;
        for (PhysicsRayTestResult res : hits) {
            PhysicsCollisionObject obj = res.getCollisionObject();
            int surface = -1;
            for (int i = 0; i < grounds.length; i++) {
                if (grounds[i] == obj) surface = i;
            }
            if (surface < 0) continue;
            // the tops of the retaining walls are not road: a wheel standing on one would let a
            // car that slides into the wall climb it and vault over
            if (surface == 0 && res.partIndex() >= TrackBuilder.WALL_PART) continue;
            if (res.getHitFraction() < best) {
                best = res.getHitFraction();
                res.getHitNormalLocal(normalOut);
                // the grass strips (infield edge, pit-exit median) are a sub-mesh of the track collider
                w.onTrack = surface == 0 && res.partIndex() != TrackBuilder.GRASS_PART;
            }
        }
        return best == Float.MAX_VALUE ? -1f : best;
    }

    /** 0..1 tire squeal intensity: slip angles / slip ratios beyond the grip peak on asphalt. */
    public float getSqueal() { return squeal; }

    private void updateSqueal(float dt) {
        float target = 0f;
        for (Wheel w : wheels) {
            if (!w.contact || !w.onTrack || w.load < 300f) continue;
            float lat = (Math.abs(w.slipAngle) - 0.075f) / 0.12f;
            float lon = (Math.abs(w.slipRatio) - 0.10f) / 0.25f;
            float loadFactor = Math.min(1f, w.load / 2500f);
            target = Math.max(target, FastMath.clamp(Math.max(lat, lon), 0f, 1f) * loadFactor);
        }
        if (speed < 3f) target = 0f;
        squeal += (target - squeal) * Math.min(1f, dt / 0.06f);
    }

    private void updateAccelerationTelemetry(float dt) {
        if (hasPrevVel) {
            tmp.set(vel).subtractLocal(prevVel).divideLocal(dt);
            float latRaw = tmp.dot(left) / G;
            float lonRaw = tmp.dot(fwd) / G;
            float a = Math.min(1f, dt / 0.15f);
            lateralG += (latRaw - lateralG) * a;
            longitudinalG += (lonRaw - longitudinalG) * a;
        }
        prevVel.set(vel);
        hasPrevVel = true;
    }
}
