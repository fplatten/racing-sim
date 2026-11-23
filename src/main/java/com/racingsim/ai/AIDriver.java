package com.racingsim.ai;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.VehicleInput;
import com.racingsim.vehicle.VehiclePhysics;

import java.util.List;

/**
 * AI driver producing throttle/brake/steering for a {@link Car} that runs on exactly the
 * same {@link VehiclePhysics} as the player.
 * <ul>
 *   <li>Steering: Pure Pursuit toward a look-ahead point on a dynamic lateral target
 *       (racing line blended with a passing / avoidance lane), plus an integral loop on
 *       actual vs. desired path curvature that compensates for tire slip.</li>
 *   <li>Speed: pre-computed corner-entry speed profile + traffic speed caps.</li>
 *   <li>Pack awareness: when closing on a slower car (typically while in its draft) the AI
 *       picks a free side and moves over smoothly to overtake, while keeping clear of cars
 *       alongside. Cars in its path (now or in the lane it is merging into) cap its speed, and
 *       it swerves to a free lane when a collision is imminent. The human player gets a wider
 *       berth than other AI cars.</li>
 * </ul>
 */
public final class AIDriver {

    private static final float D_MIN = -5.6f;
    private static final float D_MAX = 5.7f;
    private static final float CAR_WIDTH_CLEARANCE = 2.9f;
    private static final float PASS_OFFSET = 3.6f;
    /** Lateral distance below which a car ahead is considered "in our lane" (2 m wide cars). */
    private static final float OVERLAP = 2.4f;
    /** Deceleration assumed when planning how fast we may close on a car ahead (m/s^2). */
    private static final float FOLLOW_DECEL = 3.0f;
    /** Extra caution around the human player. */
    private static final float HUMAN_FOLLOW_DECEL = 2.5f, HUMAN_FOLLOW_GAP = 20f;
    /** Time to collision below which the AI swerves to any free lane. */
    private static final float EMERGENCY_TTC = 2.5f;
    private static final float LANE_RATE = 2.2f;      // m/s lateral target change
    private static final float WEIGHT_RATE = 0.9f;    // 1/s blend between line and lane

    private final Car car;
    private final TrackSpline track;
    private final RacingLine line;
    private SpeedProfile profile;
    private final float aggression;

    private Car passTarget;
    /** Backing out of a pass that could not be completed before the turn. */
    private boolean passYield;
    private float passLane;
    private float laneD;
    private float laneWeight;
    private float steerCmd;
    private float stuckTime;
    private float raceTime;
    /** Like a real rolling start, the field holds station until it has strung out. */
    private static final float NO_PASSING_TIME = 14f;
    /** End of turn 2 relative to the start/finish line (where the opening-lap formation ends). */
    private static final float TURN2_EXIT_REL = com.racingsim.track.TrackSpline.STRAIGHT / 2
            + 2 * com.racingsim.track.TrackSpline.TURN + com.racingsim.track.TrackSpline.CHUTE + 40f;

    private final Vector3f target = new Vector3f();
    private final Vector3f fwd = new Vector3f(), left = new Vector3f(), up = new Vector3f();
    private final Vector3f vel = new Vector3f(), angVel = new Vector3f();
    private float steerIntegral;
    /** Integral gain of the curvature-tracking steering loop (rad per (1/m) per s). */
    private static final float CURVATURE_GAIN = 18f;
    /** Lane-keeping terms: rad per meter of lateral error, rad per m/s of lateral velocity. */
    private static final float LATERAL_GAIN = 0.0025f, LATERAL_DAMPING = 0.006f;
    private final Quaternion rot = new Quaternion();

    /**
     * @param aggression 0..1 - higher values pass earlier and follow closer
     */
    public AIDriver(Car car, RacingLine line, SpeedProfile profile, float aggression) {
        this.car = car;
        this.track = line.getTrack();
        this.line = line;
        this.profile = profile;
        this.aggression = aggression;
    }

    public Car getCar() { return car; }

    /**
     * Sets this car's wing level (0 = minimum downforce .. 1 = maximum) and re-plans the corner
     * speeds for the new grip.
     */
    public void setDownforceLevel(float level) {
        car.getPhysics().getAeroModel().setDownforceLevel(level);
        profile = profile.withAero(car.getPhysics().getAeroModel());
    }
    public boolean isPassing() { return passTarget != null; }

    /** Called at the start of every race (grid placement). */
    public void resetForRace() {
        resetLane();
        raceTime = 0f;
        gridLane = car.trackD();
        formationDone = false;
        readyToRelease = false;
        mergeHold = false;
    }

    private boolean readyToRelease;

    /** True if no other car occupies the racing line around us (cars in our own lane ignored). */
    private boolean racingLineClear(List<Car> field, float s, float d) {
        float lineD = line.offset(s);
        if (Math.abs(d - lineD) < 1.2f) return true;            // already on the line
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            if (ds <= -30f || ds >= 40f || Math.abs(c.trackD() - d) <= 1.2f) continue;   // own lane: fine
            // anything on the line, or in the corridor we would cross to reach it (e.g. another
            // car merging at the same moment), blocks the merge
            float lo = Math.min(d, lineD) - 1.8f, hi = Math.max(d, lineD) + 1.8f;
            if (c.trackD() > lo && c.trackD() < hi) return false;
        }
        return true;
    }

    /** True once the car has cleared the exit of turn 2 on the opening lap. */
    private boolean formationDone;

    /** Lateral position on the grid: held (three abreast) until the green flag at the line. */
    private float gridLane;

    /** Called after a reset/teleport. */
    public void resetLane() {
        passTarget = null;
        passYield = false;
        laneD = car.trackD();
        laneWeight = 1f;
        stuckTime = 0f;
        steerIntegral = 0f;
        steerCmd = 0f;
        pitPhase = PitPhase.NONE;
    }

    /** @return true if the car has been stuck long enough to need a recovery reset. */
    public boolean needsRecovery() {
        return stuckTime > 3.5f;
    }

    public void update(float dt, List<Car> field) {
        if (pitPhase != PitPhase.NONE) {
            raceTime += dt;
            updatePit(dt, field);
            if (pitPhase != PitPhase.NONE) return;
        }
        VehiclePhysics phys = car.getPhysics();
        float s = car.trackS(), d = car.trackD();
        float v = phys.getForwardSpeed();

        // ---------------- traffic analysis ----------------
        Car blocker = null;
        float blockerGap = Float.MAX_VALUE;
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            if (ds > 0 && ds < 50f && Math.abs(c.trackD() - d) < 2.6f && ds < blockerGap) {
                blocker = c;
                blockerGap = ds;
            }
        }

        float desiredLane = line.offset(s);
        float weightTarget = 0f;
        float speedCap = Float.MAX_VALUE;
        // Rolling start, Indianapolis style: hold the grid lane (three abreast) through the green
        // flag and turns 1 and 2; the field races freely from the back straight onwards.
        float relS = com.racingsim.track.PitLane.rel(s);
        if (raceTime > 3f && relS > TURN2_EXIT_REL && relS < TURN2_EXIT_REL + 400f) readyToRelease = true;
        // each car leaves its lane only when the racing line beside it is clear (outside-lane
        // cars are already on it), so the field does not converge on the line all at once
        if (!formationDone && (raceTime > 70f || (readyToRelease && racingLineClear(field, s, d)))) {
            formationDone = true;
        }
        if (!formationDone) {
            desiredLane = gridLane;
            weightTarget = 1f;
        }
        // just out of the pits: run low on the apron until the racing line beside us is clear
        if (mergeHold) {
            boolean onTrack = d > D_MIN - 1.2f;
            if ((onTrack && racingLineClear(field, s, d)) || relS > com.racingsim.track.PitLane.LANE_END + 700f) {
                mergeHold = false;
            } else {
                desiredLane = D_MIN;
                weightTarget = 1f;
            }
        }

        // start a pass when closing on a slower car or riding its draft
        raceTime += dt;
        boolean passingAllowed = formationDone && !mergeHold && raceTime > NO_PASSING_TIME;
        if (passingAllowed && passTarget == null && blocker != null && blockerGap < 30f + 15f * aggression) {
            // a pass on the human player needs a clear speed advantage, not just a tow
            boolean closing = v > blocker.getPhysics().getForwardSpeed() + (blocker.isPlayer() ? 1.5f : 0.3f);
            boolean towing = phys.getDraftFactor() > 0.12f;
            // only start a pass that will be done before the next turn: cars run different wing
            // levels, and a low-downforce car stuck on the outside in a turn runs out of grip
            boolean room = true;
            if (!track.inTurn(s)) {
                float closingSpeed = Math.max(v - blocker.getPhysics().getForwardSpeed(), towing ? 3f : 0.3f);
                float passDistance = (blockerGap + 10f) / closingSpeed * Math.max(v, 1f);
                room = passDistance < track.distanceToNextTurn(s) - 40f;
            }
            if ((closing || towing) && room) {
                float lane = choosePassLane(blocker, field);
                if (!Float.isNaN(lane)) {
                    passTarget = blocker;
                    passYield = false;
                    passLane = lane;
                }
            }
        }
        if (passTarget != null) {
            float ds = TrackSpline.deltaS(s, passTarget.trackS());
            boolean outside = passLane > passTarget.trackD();
            boolean turnNear = track.inTurn(s) || track.distanceToNextTurn(s) < 80f;
            // still not ahead on the outside as the turn arrives: back out and tuck in behind
            if (outside && turnNear && ds > -3f) passYield = true;
            if (ds < -8f || ds > 70f || (passYield && ds > 10f) || laneBlocked(passLane, passTarget, field, -6f, 8f)) {
                passTarget = null;   // completed, abandoned or the lane closed
                passYield = false;
            } else {
                desiredLane = passLane;
                weightTarget = 1f;
                if (passYield) speedCap = Math.min(speedCap, passTarget.getPhysics().getForwardSpeed() - 2f);
            }
        }

        // Never run into the back of a car that is in our path: either overlapping our lane now
        // or overlapping the lane we are steering into (merging back to the line was a blind
        // spot). The allowed closing speed shrinks with the gap (constant deceleration), the scan
        // range grows with the closing speed, and the human player gets a much wider berth
        // because people brake, lift and wander unpredictably.
        Car threat = null;
        float threatTtc = Float.MAX_VALUE;
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            float vc = c.getPhysics().getForwardSpeed();
            float closing = v - vc;
            boolean human = c.isPlayer();
            float decel = human ? HUMAN_FOLLOW_DECEL : FOLLOW_DECEL;
            float range = Math.max(70f, closing > 0f ? closing * closing / (2f * decel) + 40f : 0f);
            if (ds <= 0f || ds > range) continue;
            float width = human ? OVERLAP + 0.8f : OVERLAP;
            float laneThere = FastMath.interpolateLinear(laneWeight, line.offset(s + ds), laneD);
            // where will that car be laterally by the time we reach it? (people cut across)
            float reach = closing > 0.5f ? Math.min(ds / closing, 3f) : 0f;
            float dPred = c.trackD() + FastMath.clamp(c.lateralRate() * reach, -6f, 6f);
            boolean inPath = Math.abs(c.trackD() - d) < width || Math.abs(c.trackD() - laneThere) < width
                    || Math.abs(dPred - d) < width || Math.abs(dPred - laneThere) < width;
            if (!inPath) continue;
            float gap = (human ? HUMAN_FOLLOW_GAP : 12f) - 3f * aggression;
            float margin = Math.max(ds - gap, 0f);
            float cap = vc + FastMath.sqrt(2f * decel * margin) - (human ? 1.5f : 0.5f);
            speedCap = Math.min(speedCap, cap);
            if (closing > 0.5f) {
                float ttc = Math.max(ds - 5f, 0f) / closing;
                if (ttc < threatTtc) {
                    threatTtc = ttc;
                    threat = c;
                }
            }
        }
        // emergency: about to hit a car we are still lined up with -> take any free lane,
        // even during the no-passing period of the rolling start
        if (threat != null && threatTtc < EMERGENCY_TTC && passTarget != threat) {
            float lane = choosePassLane(threat, field);
            if (!Float.isNaN(lane)) {
                passTarget = threat;
                passYield = false;
                passLane = lane;
                desiredLane = lane;
                weightTarget = 1f;
            }
        }

        // side-by-side avoidance
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            float dd = c.trackD() - d;
            float clearance = c.isPlayer() ? CAR_WIDTH_CLEARANCE + 0.6f : CAR_WIDTH_CLEARANCE;
            if (Math.abs(ds) < 7.5f && Math.abs(dd) < clearance + 0.4f) {
                if (dd > 0) desiredLane = Math.min(desiredLane, c.trackD() - clearance);
                else desiredLane = Math.max(desiredLane, c.trackD() + clearance);
                weightTarget = 1f;
                if (desiredLane < D_MIN + 0.4f || desiredLane > D_MAX - 0.4f) {
                    // squeezed: back out of it
                    speedCap = Math.min(speedCap, v - 1f);   // lift, don't brake
                }
            }
        }
        // inside the turns stay off the wall: understeer in traffic pushes cars up the banking
        float dMax = (track.inTurn(s) || track.distanceToNextTurn(s) < 60f) ? D_MAX - 0.8f : D_MAX;
        desiredLane = FastMath.clamp(desiredLane, D_MIN, dMax);

        // smooth lateral target
        float maxStep = LANE_RATE * dt * (weightTarget > 0 ? 1.5f : 1f);
        if (laneWeight < 0.01f) laneD = line.offset(s);   // keep lane state glued to the line
        laneD += FastMath.clamp(desiredLane - laneD, -maxStep, maxStep);
        laneWeight += FastMath.clamp(weightTarget - laneWeight, -WEIGHT_RATE * dt, WEIGHT_RATE * dt);

        // ---------------- steering: pure pursuit ----------------
        float lookahead = FastMath.clamp(10f + 0.55f * Math.abs(v), 12f, 75f);
        float sAhead = s + lookahead;
        float dTarget = FastMath.interpolateLinear(laneWeight, line.offset(sAhead), laneD);
        float intendedNow = FastMath.interpolateLinear(laneWeight, line.offset(s), laneD);
        steer(dt, d, v, sAhead, dTarget, intendedNow);

        // ---------------- speed control ----------------
        float vTarget = profile.at(s + 4f);
        boolean cornering = track.inTurn(s) || track.distanceToNextTurn(s) < 150f;
        if (cornering) {
            // dirty air costs front downforce, and an off-line lane is a tighter radius
            vTarget *= 1f - 0.25f * phys.getDraftFactor();
            if (laneWeight > 0.5f) vTarget *= 0.985f;
            if (!formationDone) vTarget *= 0.90f;      // three abreast on the opening lap: leave a margin
        }
        vTarget = Math.min(vTarget, speedCap);
        applySpeed(vTarget, s, d, intendedNow);

        // stuck detection (crashed / facing the wall)
        if (Math.abs(v) < 3f) stuckTime += dt; else stuckTime = 0f;
    }

    /** Pure pursuit toward (sAhead, dAhead) plus curvature integral and lane-keeping damping. */
    private void steer(float dt, float d, float v, float sAhead, float dAhead, float intendedNow) {
        VehicleInput in = car.getInput();
        track.point(sAhead, dAhead, target);
        car.getBody().getPhysicsRotation(rot);
        rot.mult(Vector3f.UNIT_Z, fwd);
        rot.mult(Vector3f.UNIT_Y, up);
        car.getBody().getLinearVelocity(vel);
        // Pure pursuit in the *velocity* frame, so body slip angle does not bias the target
        float speedAbs = vel.length();
        if (speedAbs > 5f && v > 0f) {
            fwd.set(vel).subtractLocal(up.mult(vel.dot(up))).normalizeLocal();
        }
        up.cross(fwd, left);
        target.subtractLocal(car.getBody().getPhysicsLocation());
        float localX = target.dot(left);
        float localZ = target.dot(fwd);
        float distSq = Math.max(localX * localX + localZ * localZ, 1f);
        float desiredCurvature = 2f * localX / distSq;
        float wheelbase = car.getSpec().frontAxleZ - car.getSpec().rearAxleZ;
        float feedForward = FastMath.atan(wheelbase * desiredCurvature);

        // integral loop on path curvature: finds the extra lock the tires' slip requires
        float yawRate = car.getBody().getAngularVelocity(angVel).dot(up);
        float actualCurvature = yawRate / Math.max(speedAbs, 5f);
        if (speedAbs > 8f) {
            steerIntegral += CURVATURE_GAIN * (desiredCurvature - actualCurvature) * dt;
        } else {
            steerIntegral *= 1f - Math.min(1f, 2f * dt);
        }
        float maxSteer = car.getSpec().maxSteerAngle(v);
        steerIntegral = FastMath.clamp(steerIntegral, -maxSteer, maxSteer);
        // lane-keeping damping: correct the lateral error to the intended path and damp the
        // lateral velocity (d grows outwards = to the right -> steer left, i.e. positive)
        float lateralCorrection = LATERAL_GAIN * (d - intendedNow) + LATERAL_DAMPING * car.lateralRate();
        lateralCorrection = FastMath.clamp(lateralCorrection, -0.4f * maxSteer, 0.4f * maxSteer);
        float delta = feedForward + steerIntegral + lateralCorrection;
        float steerTarget = FastMath.clamp(delta / maxSteer, -1f, 1f);
        float steerRate = 4f * dt;
        steerCmd += FastMath.clamp(steerTarget - steerCmd, -steerRate, steerRate);
        in.steer = steerCmd;
    }

    /** Throttle / brake toward vTarget with traction and understeer management. */
    private void applySpeed(float vTarget, float s, float d, float intended) {
        VehiclePhysics phys = car.getPhysics();
        VehicleInput in = car.getInput();
        float v = phys.getForwardSpeed();
        float err = vTarget - v;
        float throttle, brake;
        if (err > 0f) {
            throttle = FastMath.clamp(0.5f + err * 0.4f, 0f, 1f);
            brake = 0f;
        } else if (err > -2f) {
            throttle = FastMath.clamp(0.45f + err * 0.25f, 0f, 1f);
            brake = 0f;
        } else {
            throttle = 0f;
            brake = FastMath.clamp(-err * 0.12f, 0f, 1f);
            // never hold the brake hard at a standstill: that engages reverse
            if (Math.abs(v) < 1.5f) brake = Math.min(brake, HOLD_BRAKE);
        }
        // traction / stability management
        // oversteer: the rear is sliding more than the front -> feed throttle in gently
        float rearSlip = phys.getRearSlipAngle();
        float frontSlip = phys.getFrontSlipAngle();
        if (rearSlip > 0.05f && rearSlip > frontSlip) {
            throttle *= FastMath.clamp(1f - (rearSlip - 0.05f) * 6f, 0.35f, 1f);
        }
        if (phys.getRearSlipRatio() > 0.12f) throttle *= 0.6f;
        // close to the grip limit in a corner: don't add power-on oversteer
        if (track.inTurn(s) && rearSlip > 0.08f) brake = 0f;
        // understeer management: pushing wide of the intended line (dirty air, three abreast)
        // or front tires at the limit -> lift until the car tracks again
        if (track.inTurn(s) || track.distanceToNextTurn(s) < 60f) {
            float wide = d - intended;
            if (wide > 0.5f) throttle *= FastMath.clamp(1f - (wide - 0.5f) * 0.7f, 0.15f, 1f);
            if (frontSlip > 0.085f) throttle *= FastMath.clamp(1f - (frontSlip - 0.085f) * 12f, 0.3f, 1f);
        }
        in.throttle = throttle;
        in.brake = brake;
    }

    // ================================================================== pit stops
    private enum PitPhase { NONE, IN, SERVICE, OUT }

    /** Pit road speed limit (60 mph) and the lateral positions of the pit lane path. */
    public static final float PIT_SPEED = 26.8f;
    private static final float ENTRY_D = -12.3f, FAST_D = -21.9f, BOX_D = -28.5f;
    /** Where the move down to the pit entry lane starts / ends (rel s, turn 4). */
    public static final float PIT_COMMIT_REL = -1150f;
    private static final float PIT_MOVE_END_REL = -950f;
    private static final float PIT_DECEL = 4.5f;

    /** Brake used to hold a stopped car without the gearbox engaging reverse (threshold 0.5). */
    private static final float HOLD_BRAKE = 0.45f;
    private PitPhase pitPhase = PitPhase.NONE;
    private float pitStuck;
    private com.racingsim.track.PitStop pit;
    private float stopRel;
    /** After the pit exit: stay low on the apron until the racing line is clear. */
    private boolean mergeHold;

    public void setPit(com.racingsim.track.PitStop pit) { this.pit = pit; }
    public com.racingsim.track.PitStop getPit() { return pit; }
    public boolean isPitting() { return pitPhase != PitPhase.NONE; }

    /** Tells the driver to make a pit stop this lap (called before the pit entry, in turn 3). */
    public void requestPit() {
        if (pit == null || pitPhase != PitPhase.NONE) return;
        pitPhase = PitPhase.IN;
        passTarget = null;
    }

    private static float smooth(float x) {
        x = FastMath.clamp(x, 0f, 1f);
        return x * x * (3 - 2 * x);
    }

    private float boxCenter() {
        return com.racingsim.track.PitLane.stallStart(pit.stall()) + com.racingsim.track.PitLane.STALL_LENGTH / 2f;
    }

    /** Lateral position of the pit path at track position sq. */
    private float pitPathD(float sq) {
        float r = com.racingsim.track.PitLane.rel(sq);
        if (pitPhase == PitPhase.IN) {
            float boxC = boxCenter();
            if (r < PIT_COMMIT_REL) return line.offset(sq);
            if (r < PIT_MOVE_END_REL) {
                return FastMath.interpolateLinear(smooth((r - PIT_COMMIT_REL) / (PIT_MOVE_END_REL - PIT_COMMIT_REL)),
                        line.offset(sq), ENTRY_D);
            }
            if (r < com.racingsim.track.PitLane.START) return ENTRY_D;
            if (r < com.racingsim.track.PitLane.WALL_START) {
                return FastMath.interpolateLinear(smooth((r - com.racingsim.track.PitLane.START)
                        / (com.racingsim.track.PitLane.WALL_START - com.racingsim.track.PitLane.START)), ENTRY_D, FAST_D);
            }
            return FastMath.interpolateLinear(smooth((r - (boxC - 26f)) / 24f), FAST_D, BOX_D);
        }
        // leaving the box, down the fast lane and along the pit exit lane through turns 1 and 2
        if (r < com.racingsim.track.PitLane.WALL_END) {
            return FastMath.interpolateLinear(smooth((r - stopRel - 1f) / 23f), BOX_D, FAST_D);
        }
        float rq = Math.min(r, com.racingsim.track.PitLane.LANE_END);
        float sq2 = sq + (rq - r);
        float outer = com.racingsim.track.PitLane.laneOuter(sq2);
        float inner = Math.max(com.racingsim.track.PitLane.laneInner(sq2), com.racingsim.track.PitLane.LANE_INNER);
        return 0.5f * (outer + inner);
    }

    private void updatePit(float dt, List<Car> field) {
        VehiclePhysics phys = car.getPhysics();
        VehicleInput in = car.getInput();
        float s = car.trackS(), d = car.trackD();
        float v = phys.getForwardSpeed();
        float r = com.racingsim.track.PitLane.rel(s);
        stuckTime = 0f;

        // ---- phase transitions ----
        if (pitPhase == PitPhase.IN && pit.isServicing()) pitPhase = PitPhase.SERVICE;
        if (pitPhase == PitPhase.SERVICE && !pit.isServicing()) {
            pitPhase = PitPhase.OUT;
            stopRel = r;
        }
        boolean missedEntry = r > -760f && r < -480f && d > com.racingsim.track.PitLane.LANE_DIVIDER_D + 0.5f;
        // squeezed onto the race-track side of the pit wall
        boolean wrongSide = r >= com.racingsim.track.PitLane.WALL_START - 10f && r < com.racingsim.track.PitLane.WALL_START + 200f
                && d > TrackSpline.INNER_WALL - 0.5f;
        if (pitPhase == PitPhase.IN && (missedEntry || wrongSide)) {
            // missed the pit entry (traffic on the inside): stay out, try again next lap
            resetLane();
            return;
        }
        // stuck in the pit lane (blocked, spun): give up the stop and let the recovery tow us out
        pitStuck = pitPhase != PitPhase.SERVICE && Math.abs(v) < 1f
                && !com.racingsim.track.PitStop.inBox(pit.stall(), s, d) ? pitStuck + dt : 0f;
        if (pitStuck > 10f) {
            pitStuck = 0f;
            resetLane();
            stuckTime = 10f;
            return;
        }
        if (pitPhase == PitPhase.IN && r > boxCenter() + 6f) {          // overshot the box: drive on out
            pitPhase = PitPhase.OUT;
            stopRel = r;
        }
        if (pitPhase == PitPhase.OUT && r > com.racingsim.track.PitLane.LANE_END) {
            resetLane();                       // back to racing, but merge only when the line is clear
            mergeHold = true;
            return;
        }
        if (pitPhase == PitPhase.SERVICE) {                              // on the jacks
            in.throttle = 0f;
            in.brake = 1f;
            steerCmd *= 0.9f;
            in.steer = steerCmd;
            return;
        }

        // ---- path ----
        float lookahead = FastMath.clamp(4f + 0.5f * Math.abs(v), 7f, 60f);
        float dNow = pitPathD(s), dAhead = pitPathD(s + lookahead);
        float yieldCap = Float.MAX_VALUE;
        if (pitPhase == PitPhase.IN && r < com.racingsim.track.PitLane.START - 100f) {
            // never move down onto a car on our inside (racing or also heading for the pits):
            // hold our line and drop in behind it
            for (Car c : field) {
                if (c == car) continue;
                float ds = TrackSpline.deltaS(s, c.trackS());
                if (Math.abs(ds) < 9f && c.trackD() < d && d - c.trackD() < CAR_WIDTH_CLEARANCE + 0.8f) {
                    float floor = c.trackD() + CAR_WIDTH_CLEARANCE + 0.3f;
                    dNow = Math.max(dNow, floor);
                    dAhead = Math.max(dAhead, floor);
                    if (ds > -4f) yieldCap = Math.min(yieldCap, c.getPhysics().getForwardSpeed() - 2f);
                }
            }
        }
        steer(dt, d, v, s + lookahead, dAhead, dNow);

        // ---- speed ----
        float vTarget = profile.at(s + 4f);
        if (d < TrackSpline.ROAD_INNER && track.inTurn(s)) vTarget *= 0.85f;   // flat apron: less grip
        boolean onPitRoad = r >= com.racingsim.track.PitLane.WALL_START && r <= com.racingsim.track.PitLane.WALL_END;
        if (pitPhase == PitPhase.IN) {
            float toLimit = Math.max(0f, com.racingsim.track.PitLane.WALL_START - r);
            vTarget = Math.min(vTarget, FastMath.sqrt(PIT_SPEED * PIT_SPEED + 2f * PIT_DECEL * toLimit));
            float toStop = boxCenter() - 2f - r;
            vTarget = Math.min(vTarget, 1.0f + FastMath.sqrt(2f * 3f * Math.max(0f, toStop)));
        } else {
            if (onPitRoad) vTarget = Math.min(vTarget, PIT_SPEED);
            else if (r < com.racingsim.track.PitLane.BLEND_START) vTarget = Math.min(vTarget, 45f);
        }
        vTarget = Math.min(vTarget, Math.max(yieldCap, 5f));
        // follow traffic on the pit path
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            if (ds <= 0f || ds > 90f) continue;
            float pathThere = pitPathD(c.trackS());
            if (Math.abs(c.trackD() - pathThere) > 2.6f && Math.abs(c.trackD() - d) > 2.6f) continue;
            float vc = c.getPhysics().getForwardSpeed();
            vTarget = Math.min(vTarget, vc + FastMath.sqrt(2f * 2.5f * Math.max(ds - 13f, 0f)) - 1f);
        }
        // pulling out of the box: wait for cars coming down the fast lane
        boolean waiting = false;
        if (pitPhase == PitPhase.OUT && r < stopRel + 4f) {
            for (Car c : field) {
                if (c == car) continue;
                float ds = TrackSpline.deltaS(s, c.trackS());
                if (ds > -70f && ds < 6f && c.trackD() > com.racingsim.track.PitLane.FAST_LANE_INNER - 0.5f
                        && c.trackD() < TrackSpline.INNER_WALL && c.speed() > 2f) {
                    waiting = true;
                }
            }
        }
        applySpeed(Math.max(vTarget, 0f), s, d, dNow);
        if (pitPhase == PitPhase.OUT && r < stopRel + 25f) in.throttle = Math.min(in.throttle, 0.5f);
        if (waiting || (pitPhase == PitPhase.IN && boxCenter() - r < 3f && Math.abs(r - boxCenter()) < 10f)) {
            in.throttle = 0f;
            in.brake = Math.abs(v) < 1.5f ? HOLD_BRAKE : 1f;
        }
    }

    private float choosePassLane(Car blocker, List<Car> field) {
        float inside = blocker.trackD() - PASS_OFFSET;
        float outside = blocker.trackD() + PASS_OFFSET;
        // a car moving across the track: go behind it, i.e. to the side it is leaving
        float drift = blocker.lateralRate();
        if (Math.abs(drift) > 1.0f) {
            if (drift > 0) inside = Math.min(inside, blocker.trackD() - PASS_OFFSET - 1f);
            else outside = Math.max(outside, blocker.trackD() + PASS_OFFSET + 1f);
            boolean preferInside = drift > 0;
            float first = preferInside ? inside : outside, second = preferInside ? outside : inside;
            if (first >= D_MIN && first <= D_MAX && !laneBlocked(first, blocker, field, -12f, 20f)) return first;
            if (second >= D_MIN && second <= D_MAX && !laneBlocked(second, blocker, field, -12f, 20f)) return second;
            return Float.NaN;
        }
        boolean insideOk = inside >= D_MIN && !laneBlocked(inside, blocker, field, -12f, 20f);
        boolean outsideOk = outside <= D_MAX && !laneBlocked(outside, blocker, field, -12f, 20f);
        if (insideOk && outsideOk) {
            float s = car.trackS();
            boolean turnSoon = track.distanceToNextTurn(s) < 350f || track.inTurn(s);
            if (turnSoon) return inside;
            return Math.abs(inside - car.trackD()) <= Math.abs(outside - car.trackD()) ? inside : outside;
        }
        if (insideOk) return inside;
        if (outsideOk) return outside;
        return Float.NaN;
    }

    /** True if a car other than {@code ignore} occupies {@code lane} in the given gap window. */
    private boolean laneBlocked(float lane, Car ignore, List<Car> field, float dsMin, float dsMax) {
        float s = car.trackS();
        for (Car c : field) {
            if (c == car || c == ignore) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            float clearance = c.isPlayer() ? CAR_WIDTH_CLEARANCE + 0.6f : CAR_WIDTH_CLEARANCE;
            if (ds > dsMin && ds < dsMax && Math.abs(c.trackD() - lane) < clearance) return true;
        }
        return false;
    }
}
