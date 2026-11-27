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
 *   <li>Race craft, oval style: single file on the racing line; passes are set up in the tow
 *       and made with a late pull-out on the straights, only where they can be finished before
 *       the next turn and never beside a car that already has company (no three wide). A pass
 *       that stops gaining, or is not alongside by the turn, is abandoned and the car tucks back
 *       in. Cars rejoin the line only once clear of the car alongside, lift and brake behind
 *       slower traffic, and swerve only when braking cannot prevent a hit.</li>
 *   <li>Driver types: {@code aggression} 0 is a rookie (textbook passes only, a heavy wing) and
 *       1 a veteran (passes in the chutes and into the turns, side by side through them, a
 *       light wing).</li>
 *   <li>Also: keeping clear of cars alongside, a little more room for the human player, the
 *       rolling-start formation and pit stops.</li>
 *   <li>On the road course the same race craft with the turns' own directions (the inside of a
 *       right-hander is the right), braking zones ahead of the turns (a pass is made on the
 *       brakes, down the inside), the speed profile of the lane in use when off the racing line,
 *       and a standing start.</li>
 * </ul>
 */
public final class AIDriver {

    /** Road course: how far ahead (s) the speed target looks, per m/s: the brakes are applied a
     *  moment before the braking curve is reached rather than chasing it. */
    private static final float ROAD_SPEED_LEAD = 0.2f;
    private static final float CAR_WIDTH_CLEARANCE = 2.9f;
    private static final float PASS_OFFSET = 3.6f;
    /** Lateral distance below which a car ahead is considered "in our lane" (2 m wide cars). */
    private static final float OVERLAP = 2.4f;
    /** Deceleration assumed when planning how fast we may close on a car ahead (m/s^2). */
    private static final float FOLLOW_DECEL = 3.0f;
    /** The human player is less predictable: plan gentler braking and keep a little more room,
     *  but stay close enough (15 m) to draft them. */
    private static final float HUMAN_FOLLOW_DECEL = 2.5f, HUMAN_FOLLOW_GAP = 15f;
    /** Swerve only when a hit is this close (s) and braking would need more than EMERGENCY_DECEL. */
    private static final float EMERGENCY_TTC = 1.5f;
    private static final float EMERGENCY_DECEL = 8f;
    /** Tow (draft factor) at which a follower starts setting up a slingshot pass. */
    private static final float DRAFT_FOR_PASS = 0.12f;
    private static final float LANE_RATE = 2.2f;      // m/s lateral target change
    private static final float WEIGHT_RATE = 0.9f;    // 1/s blend between line and lane

    private final Car car;
    private final TrackSpline track;
    /** The lanes this car may use (d of its center). */
    private final float dMin, dMax;
    private final RacingLine line;
    private SpeedProfile profile;
    /** Road course: the same profile braking later, for a pass down the inside into a turn. */
    private SpeedProfile lateProfile;
    private final float aggression;

    private Car passTarget;
    /** Backing out of a pass that could not be completed before the turn. */
    private boolean passYield;
    private boolean passFromDraft;
    /** The "pass" is an avoiding move (emergency swerve), not an overtake. */
    private boolean emergencyMove;
    /** Road course: on the inside of the car being passed, braking later than it into the turn. */
    private boolean outbraking, outbrakeCounted;
    private float passCooldown;
    private float passStall;

    // Personality, from aggression (rookie 0 .. veteran 1)
    private final float slingClosing;   // closing speed (m/s) needed to pull out from the tow
    private final float roomMargin;     // a pass must be finished this far (m) before the turn
    private final float yieldDs;        // on the outside at the turn, back out unless at least this far ahead (m, negative = ahead)
    private final float stallLimit;     // give a pass up when gaining less than this (m/s)
    private final float runGap;         // how close (m) a draft run closes up
    private final boolean turnPasser;   // veterans: passes in the chutes and turns, side by side through a turn
    private final float outbrakeReach;  // road course: down the inside, how far back (m) a pass may still go for the brakes
    private float topSpeed = 90f;       // flat out at this wing level, no tow (m/s)
    private float passLane;
    private float laneD;
    private float laneWeight;
    private float steerCmd;
    private float stuckTime;
    private float raceTime;
    /** Like a real rolling start, the field holds station until it has strung out. */
    private static final float NO_PASSING_TIME = 14f;
    /** A standing start: no passing moves for the first few seconds (cars hold their grid lane
     *  until they can merge, and follow the car ahead). */
    private static final float ROAD_NO_PASSING_TIME = 4f;
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
    private final float LATERAL_GAIN = tuning("aiLatGain", 0.0025f), LATERAL_DAMPING = tuning("aiLatDamp", 0.006f);
    /** Steering look-ahead: base (m) + per m/s of speed (short, to follow the line closely). */
    private final float LOOK_BASE = tuning("aiLookBase", 8f), LOOK_PER_SPEED = tuning("aiLookK", 0.40f);
    /** Closing speed (m/s) the tow must have built before pulling out to pass. */
    private final float SLINGSHOT_CLOSING = tuning("aiSling", 2.5f);
    /** A pass not yet alongside is given up after 1.5 s gaining less than this (m/s): only when
     *  it is losing ground; a slow pass may run on down the straight. */
    private final float STALL_GAIN = tuning("aiStallGain", 0f);
    /** Pull-out distance behind the car being passed: base + 8 m x aggression. */
    private final float PULL_OUT_BASE = tuning("aiPullOut", 12f);
    /** Following distance (m) on a draft run, closing up to slingshot past (+2 m behind the human). */
    private final float DRAFT_RUN_GAP = tuning("aiRunGap", 7f);
    /** Following distance (m) through the turns while setting up a run, less 4 m x aggression; at or
     *  under the usual following distance it does nothing. Off: hanging back cost the whole train
     *  its exit speed and let the leader escape. */
    private final float SETUP_GAP = tuning("aiSetupGap", 0f);
    /** Braking (m/s^2) planned behind the car we are running up on / passing (the brakes manage ~13). */
    private final float RUN_DECEL = tuning("aiRunDecel", 6f), PASS_DECEL = 8f;
    /** On a run, pull out once within DRAFT_RUN_GAP + this (m) with any closing speed. */
    private final float RUN_PULL_OUT = tuning("aiRunPull", 1.5f);
    /** Closing speed (m/s) assumed for a pass from the tow when judging if it fits before the turn. */
    private final float ROOM_CLOSING = tuning("aiRoomClose", 3f);
    /** Corner speed given up per unit of draft factor (dirty air). */
    private final float DIRTY_AIR_SLOWDOWN = tuning("aiDirtyAir", 0.08f);
    /** Road course, a pass down the inside on the brakes: the share of the grip the late braking
     *  plans on (the usual braking points plan on 0.82, {@code -DaiBrakeGrip=}), and how far behind
     *  (m, centre to centre) the passer may be as the braking zone arrives and still go for it:
     *  + 6 m x aggression (the usual following distance is 9-12 m). Tuning: {@code -DaiLateBrake=},
     *  {@code -DaiOutbrake=}; {@code -DaiPassLog=true} logs every pass as it starts and ends. */
    private final float LATE_BRAKE_GRIP = tuning("aiLateBrake", 0.95f);
    private final float OUTBRAKE_DS = tuning("aiOutbrake", 8f);
    private static final boolean PASS_LOG = Boolean.parseBoolean(System.getProperty("aiPassLog", "false"))
            || "".equals(System.getProperty("aiPassLog"));
    private final Quaternion rot = new Quaternion();

    /**
     * @param aggression driver type, 0 = rookie .. 1 = veteran (see {@link #typeName})
     */
    public AIDriver(Car car, RacingLine line, SpeedProfile profile, float aggression) {
        this.car = car;
        this.track = line.getTrack();
        this.line = line;
        this.profile = profile;
        this.aggression = FastMath.clamp(aggression, 0f, 1f);
        lateProfile = TrackSpline.isRoadCourse() ? profile.withBrakeGrip(car.getPhysics().getAeroModel(), LATE_BRAKE_GRIP) : profile;
        topSpeed = flatOutSpeed();
        // the lanes a car may use: the oval's road edges keep a car's width off the apron and the wall
        if (TrackSpline.isOval()) {
            dMin = -5.6f;
            dMax = 5.7f;
        } else {
            dMin = TrackSpline.ROAD_INNER + 1.5f;
            dMax = TrackSpline.ROAD_OUTER - 1.5f;
        }
        // Rookie (0): textbook passes only, from the tow with plenty of closing speed and done well
        // before the turn, given up as soon as they stall. Veteran (1): pulls out with less, will
        // pass in the short chutes and into the turns and go side by side through them.
        float a = this.aggression;
        slingClosing = SLINGSHOT_CLOSING + (0.5f - a) * 2f;   // 3.5 .. 1.5 m/s
        roomMargin = 120f - 80f * a;                           // 120 .. 40 m
        yieldDs = -6f + 6f * a;                                // well clear .. merely alongside
        stallLimit = STALL_GAIN + 0.4f * (1f - a);             // 0.4 .. 0 m/s
        runGap = DRAFT_RUN_GAP + 2f * (1f - a);                // 9 .. 7 m
        turnPasser = a >= 0.67f;
        outbrakeReach = OUTBRAKE_DS + 6f * a;                  // 8 .. 14 m
    }

    public Car getCar() { return car; }

    /** Driver type, 0 = rookie (textbook passes only) .. 1 = veteran (passes in the chutes and turns). */
    public float getAggression() { return aggression; }

    /** Driver type index: 0 rookie, 1 regular, 2 veteran. */
    public static int typeIndex(float aggression) {
        return aggression < 0.34f ? 0 : aggression < 0.67f ? 1 : 2;
    }

    public static String typeName(float aggression) {
        return switch (typeIndex(aggression)) {
            case 0 -> "rookie";
            case 1 -> "regular";
            default -> "veteran";
        };
    }

    /** Wing level (0..steps) this driver runs: rookies want a stable car, veterans a fast one. */
    public int preferredWing(int steps, int jitter) {
        // road course: downforce is lap time everywhere but the long straight, so everyone runs a
        // lot of it: rookies all of it (10), veterans trim a little off for the straights (8-9)
        if (TrackSpline.isRoadCourse()) return Math.clamp(Math.round((1f - 0.15f * aggression) * steps) + Math.min(jitter, 0), 0, steps);
        // rookies run more wing (stable, slower), veterans less (fast, loose): 5 .. 1 of 10, +-1. (A
        // rookie on 8-9 was ~1.5 s a lap off the pace: a full field swarmed them, three wide.)
        return Math.clamp(Math.round((0.5f - 0.4f * aggression) * steps) + jitter, 0, steps);
    }

    /** Distance to the exit of the turn we are in, or else of the next one. */
    private float turnExitDistance(float s) {
        return track.distanceToTurnExit(s);
    }

    /**
     * For passing: in a turn worth racing for. A road course's flat-out kinks and fast sweeps are
     * not: only where the road bends and the speed profile is under this car's flat-out speed (the
     * racing line runs straight through a kink in the road edges).
     */
    private boolean passTurn(float s) {
        if (TrackSpline.isOval()) return track.inTurn(s);
        return Math.abs(track.curvature(s)) > 1f / 250f && profile.at(s) < 0.97f * topSpeed;
    }

    /** Direction (+1 left, -1 right) of the turn a pass is racing for: on a road course the one at
     *  the next braking zone (not a kink on the way there). */
    private float passTurnDirection(float s) {
        if (TrackSpline.isOval() || passTurn(s)) return track.turnDirection(s);
        return track.turnDirection(s + passTurnDist(s) + 10f);
    }

    /** Where the engine's peak power meets the drag at this wing level (no tow), m/s. */
    private float flatOutSpeed() {
        com.racingsim.vehicle.VehicleSpec spec = car.getSpec();
        float power = 0f;
        for (float rpm = 6000f; rpm <= spec.redlineRpm; rpm += 250f) {
            power = Math.max(power, spec.engineTorque(rpm) * rpm * FastMath.TWO_PI / 60f);
        }
        power *= spec.drivetrainEfficiency;
        return (float) Math.cbrt(power / (0.5f * com.racingsim.physics.AeroModel.AIR_DENSITY
                * car.getPhysics().getAeroModel().dragArea));
    }

    private float cachedBrakeS = -1f, cachedBrakeDist;

    /** For passing: the distance to the next turn (a road course: to the next braking zone). */
    private float passTurnDist(float s) {
        if (TrackSpline.isOval()) return track.distanceToNextTurn(s);
        if (passTurn(s)) return 0f;
        if (s == cachedBrakeS) return cachedBrakeDist;
        // road course: the next braking zone (where this car has to slow down noticeably)
        float v = Math.max(car.getPhysics().getForwardSpeed(), profile.at(s));
        cachedBrakeS = s;
        cachedBrakeDist = 1500f;
        for (float x = 0f; x < 1500f; x += 10f) {
            if (profile.at(s + x) < v - 6f || passTurn(s + x)) {
                cachedBrakeDist = x;
                break;
            }
        }
        return cachedBrakeDist;
    }

    /** "The turn is near": 80 m on the oval (lifting, at most a dab of the brakes); on a road
     *  course the distance is to the braking zone already. */
    private float turnApproach(float v) {
        return TrackSpline.isOval() ? 80f : 60f;
    }

    private static float tuning(String name, float def) {
        return AiParams.get(name, def);
    }

    /**
     * Sets this car's wing level (0 = minimum downforce .. 1 = maximum) and re-plans the corner
     * speeds for the new grip.
     */
    public void setDownforceLevel(float level) {
        car.getPhysics().getAeroModel().setDownforceLevel(level);
        profile = profile.withAero(car.getPhysics().getAeroModel());
        if (TrackSpline.isRoadCourse()) lateProfile = profile.withBrakeGrip(car.getPhysics().getAeroModel(), LATE_BRAKE_GRIP);
        topSpeed = flatOutSpeed();
    }
    public boolean isPassing() { return passTarget != null; }

    /** Race-craft statistics over every AI car, racing laps only (simCheck reports them). */
    public static final class Stats {
        public long racingTicks;        // AI car-ticks racing (formation, pits and merging excluded)
        public long offLineTicks;       // ... more than 1.5 m off the racing line
        public long threeWideTicks;     // ... overlapped with two or more other cars
        public long lateTicks, lateThreeWide;   // the same after 100 s (the rolling start long gone)
        // on the straights: speed held below target by following, setting up a run, the run,
        // backing out of a pass, being squeezed
        public long straightTicks;
        public final long[] straightCapped = new long[5];
        public long turnTicks;                              // the same in and approaching the turns
        public final long[] turnCapped = new long[5];
        public float cleanAirTop;       // highest speed (m/s) with no tow at all
        // completed passes by driver type (rookie, regular, veteran), and those finished in a turn
        public final int[] passesByType = new int[3], tightPassesByType = new int[3];
        // ticks braking hard in a turn: speed profile, following, setting up, draft run, backing out, squeezed
        public final long[] turnBrakeTicks = new long[6];
        public int passStarts, draftPassStarts;
        public int passesDone, draftPassesDone;
        public int passesAborted, emergencyMoves;
        public int abortStall, abortLate, abortYield, abortBlocked;
        // road course: passes that went down the inside on the brakes, and how many of them stuck
        public int outbrakeStarts, outbrakesDone;
        public double closingAtStart;
        // why the off-line ticks were off the line
        public long offPassing, offEmergency, offHolding, offAvoiding, offReturning, offTracking;
        // line-following error (d - racing line, m) by 50 m of track, while nothing asks for another lane
        public final double[] trackErrSum = new double[160];
        public final long[] trackErrCount = new long[160];
    }

    /** Shared by every AI driver; replace it to start counting afresh. */
    public static Stats stats = new Stats();

    /** Called at the start of every race (grid placement). */
    public void resetForRace() {
        resetLane();
        raceTime = 0f;
        gridLane = car.trackD();
        // a standing start has no formation: everyone races from the green, merging into line as
        // soon as there is room
        formationDone = TrackSpline.isRoadCourse();
        readyToRelease = TrackSpline.isRoadCourse();
        mergeHold = false;
    }

    private boolean readyToRelease;
    private float releaseTime;

    /** True if no other car occupies the racing line around us (cars in our own lane ignored). */
    private boolean racingLineClear(List<Car> field, float s, float d) {
        return racingLineClear(field, s, d, 30f, 40f);
    }

    /** No car in the corridor between d and the racing line, from {@code behind} m back to {@code ahead} m ahead. */
    private boolean racingLineClear(List<Car> field, float s, float d, float behind, float ahead) {
        float lineD = line.offset(s);
        if (Math.abs(d - lineD) < 1.2f) return true;            // already on the line
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            if (ds <= -behind || ds >= ahead || Math.abs(c.trackD() - d) <= 1.2f) continue;   // own lane: fine
            // anything on the line, or in the corridor we would cross to reach it (e.g. another
            // car merging at the same moment), blocks the merge
            float lo = Math.min(d, lineD) - 1.8f, hi = Math.max(d, lineD) + 1.8f;
            if (c.trackD() > lo && c.trackD() < hi) return false;
        }
        return true;
    }

    /** Someone alongside that car already: passing it now would make it three wide. */
    private boolean hasCompany(Car other, List<Car> field) {
        for (Car c : field) {
            if (c == car || c == other || c.trackD() < dMin - 1.5f) continue;
            if (Math.abs(TrackSpline.deltaS(other.trackS(), c.trackS())) < 9f) return true;
        }
        return false;
    }

    /** True once the car has cleared the exit of turn 2 on the opening lap. */
    private boolean formationDone;

    /** Lateral position on the grid: held (three abreast) until the green flag at the line. */
    private float gridLane;

    /** Called after a reset/teleport. */
    public void resetLane() {
        passTarget = null;
        outbraking = outbrakeCounted = false;
        passYield = false;
        passCooldown = 0f;
        passStall = 0f;
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
        int capCause = -1;   // statistics: 0 following, 1 setting up a run, 2 draft run, 3 backing out, 4 squeezed
        // Rolling start, Indianapolis style: hold the grid lane (three abreast) through the green
        // flag and turns 1 and 2; the field races freely from the back straight onwards.
        float relS = com.racingsim.track.PitLane.rel(s);
        if (!readyToRelease && raceTime > 3f && relS > TURN2_EXIT_REL && relS < TURN2_EXIT_REL + 400f) {
            readyToRelease = true;
            releaseTime = raceTime;
        }
        // Past turn 2 the whole field races, still in its lanes until each car can merge into line
        // (the pole sitter used to escape while the rows beside and behind it waited at formation
        // pace); 20 s after that everyone is racing.
        if (!formationDone && (raceTime > 70f
                || (readyToRelease && (racingLineClear(field, s, d) || raceTime > releaseTime + 20f)))) {
            formationDone = true;
        }
        if (!formationDone) {
            desiredLane = gridLane;
            weightTarget = 1f;
        }
        // just out of the pits: run low on the apron until the racing line beside us is clear
        if (mergeHold) {
            boolean onTrack = d > dMin - 1.2f;
            if ((onTrack && racingLineClear(field, s, d)) || relS > com.racingsim.track.PitLane.LANE_END + 700f) {
                mergeHold = false;
            } else {
                desiredLane = dMin;
                weightTarget = 1f;
            }
        }

        // Passing, oval style: run nose to tail on the racing line, build speed in the leader's tow,
        // then pull out late and slingshot by on the straight. A pass starts only where it can be
        // finished before the next turn, never beside a car that already has company (no three
        // wide), and a car that is simply faster may go by without a tow.
        raceTime += dt;
        passCooldown = Math.max(0f, passCooldown - dt);
        boolean passingAllowed = formationDone && !mergeHold
                && raceTime > (TrackSpline.isOval() ? NO_PASSING_TIME : ROAD_NO_PASSING_TIME) && passCooldown <= 0f;
        boolean inTurn = track.inTurn(s);
        boolean passTurn = passTurn(s);
        Car draftRunTarget = null;
        Car setupTarget = null;
        // Every driver wants the place ahead. Through the turns, hang back a little (out of the dirty
        // air, still in the tow) to get a run at the car ahead on the next straight.
        if (passingAllowed && passTarget == null && blocker != null && blockerGap < 40f && passTurn) setupTarget = blocker;
        // (veterans will also start one on the exit of a turn)
        boolean turnExit = passTurn && turnExitDistance(s) < (TrackSpline.isOval() ? TrackSpline.TURN / 2f : 50f);
        if (passingAllowed && passTarget == null && blocker != null && (!passTurn || (turnPasser && turnExit))) {
            float closing = v - blocker.getPhysics().getForwardSpeed();
            boolean towing = phys.getDraftFactor() > DRAFT_FOR_PASS;
            // pull out late, from the tow, once the run has built real closing speed: out of the tow
            // that speed fades within seconds (12-20 m back; the human gets more room). Right on its
            // gearbox, any closing speed will do.
            float pullOut = PULL_OUT_BASE + (blocker.isPlayer() ? 4f : 0f) + 8f * aggression;
            boolean slingshot = towing && ((blockerGap < pullOut && closing > slingClosing)
                    || (blockerGap < runGap + RUN_PULL_OUT && closing > 0.5f));
            // a pass on the human player needs a clear speed advantage, not just a tow
            boolean faster = blockerGap < 30f && closing > (blocker.isPlayer() ? 2.5f : 1.5f);
            // Only start a pass that will be done in time (a run from the tow arrives at around
            // 3 m/s): before the next turn, with a margin a rookie makes generous; a veteran will
            // carry it side by side through the next turn instead (the short chutes, the turns).
            // Cars run different wing levels, and a low-downforce car stuck on the outside in a
            // turn runs out of grip.
            float closingSpeed = Math.max(closing, towing ? ROOM_CLOSING : 0.3f);
            float passDistance = (blockerGap + 10f) / closingSpeed * Math.max(v, 1f);
            float roomLeft = turnPasser ? turnExitDistance(s) - 40f
                    : (passTurn ? 0f : passTurnDist(s) - roomMargin);
            boolean room = passDistance < roomLeft;
            float lane = room && !hasCompany(blocker, field) ? choosePassLane(blocker, field) : Float.NaN;
            boolean outbrakeMove = false;
            if (TrackSpline.isRoadCourse() && !passTurn) {
                // Road course. With the braking zone about 1.5 s ahead and close behind: down the
                // inside, to brake later than the car ahead (no tow or speed advantage needed).
                // Otherwise no pass is started in the braking zone itself (the car ahead is already
                // slowing for it).
                float toBrake = passTurnDist(s);
                if (toBrake < Math.max(turnApproach(v), 1.5f * v)) {
                    lane = Float.NaN;
                    // (not a rookie's move: rookies pass clear of the turns, or not at all)
                    if (aggression >= 0.34f && blockerGap <= outbrakeReach && closing > -0.5f && !hasCompany(blocker, field)) {
                        float inside = choosePassLane(blocker, field);
                        if (!Float.isNaN(inside) && (inside - blocker.trackD()) * passTurnDirection(s) < 0f) {
                            lane = inside;
                            outbrakeMove = true;
                        }
                    }
                }
            }
            if (!Float.isNaN(lane)) {
                // A pass is on, but sitting at the usual following distance the tow builds no speed:
                // close right up in it (a "run"), and the slingshot follows once we are closing.
                if (towing && !slingshot && !faster && !outbrakeMove && !passTurn) {
                    draftRunTarget = blocker;
                } else if (slingshot || faster || outbrakeMove) {
                    outbraking = outbrakeMove;
                    passTarget = blocker;
                    passYield = false;
                    passLane = lane;
                    passFromDraft = towing;
                    emergencyMove = false;
                    stats.passStarts++;
                    if (PASS_LOG) {
                        System.out.printf("PASSLOG start %s on %s t=%.1f s=%.0f gap=%.1f closing=%.1f brakeDist=%.0f room=%.0f need=%.0f lane=%.1f tow=%d%n",
                                car.getName(), blocker.getName(), raceTime, s, blockerGap, closing, passTurnDist(s), roomLeft,
                                passDistance, lane, towing ? 1 : 0);
                    }
                    stats.closingAtStart += closing;
                    if (towing) stats.draftPassStarts++;
                }
            }
        }
        if (passTarget != null) {
            float ds = TrackSpline.deltaS(s, passTarget.trackS());
            // (on the brakes down the inside: which side we actually are, not the lane we aimed for -
            // the car being passed moves across for its turn-in)
            boolean outside = ((outbraking ? d : passLane) - passTarget.trackD()) * passTurnDirection(s) > 0f;
            boolean turnNear = passTurn || passTurnDist(s) < turnApproach(v);
            // not far enough ahead on the outside as the turn arrives (a rookie wants to be well
            // clear, a veteran stays out there once alongside): back out and tuck in behind. A
            // rookie does not go side by side into a turn on either side: clear ahead, or back out.
            boolean rookie = aggression < 0.34f;
            if (!emergencyMove && (outside || rookie) && turnNear && ds > yieldDs) passYield = true;
            // an avoiding move ends once we have dropped back behind that car (or cleared it)
            boolean avoided = emergencyMove && ds > 6f && v < passTarget.getPhysics().getForwardSpeed();
            // not alongside yet as the turn arrives: no dive-bomb on the inside, from anyone (a
            // veteran alongside by then may carry the pass side by side through the turn; only a
            // veteran keeps a pass going in a turn at all)
            boolean turnAhead = !passTurn && passTurnDist(s) < turnApproach(v);
            boolean tooLate = !emergencyMove && ds > 4f && (turnAhead || (passTurn && !turnPasser));
            if (TrackSpline.isRoadCourse()) {
                // road course: a pass is made on the brakes. On the inside, close enough as the braking
                // zone arrives (a veteran from further back), brake later than the car being passed
                // and draw alongside before the turn-in; on the outside, be ahead. In the turn itself
                // only a veteran keeps a pass going that is not yet ahead.
                boolean brakeZone = turnAhead || (outbraking && !passTurn);
                if (!emergencyMove && !outside && brakeZone && ds <= outbrakeReach) outbraking = true;
                if (outbraking && !outbrakeCounted) {
                    outbrakeCounted = true;
                    stats.outbrakeStarts++;
                }
                // once on the brakes, the nose must be level with its sidepods by the turn-in
                tooLate = !emergencyMove && ((turnAhead && !outbraking && ds > (outside ? 3f : 6f))
                        || (passTurn && !turnPasser && ds > (outbraking ? 3f : 2f)));
            }
            // out of the tow and not gaining before even drawing alongside: give up and tuck back in
            // (a rookie as soon as it stalls, a veteran only once losing ground)
            float gain = v - passTarget.getPhysics().getForwardSpeed();
            passStall = !emergencyMove && ds > 3f && gain < stallLimit ? passStall + dt : 0f;
            if (ds < -8f || ds > 70f || (passYield && ds > 10f) || tooLate || avoided || passStall > 1.5f
                    || laneBlocked(passLane, passTarget, field, -6f, 8f)) {
                if (ds >= -8f) passCooldown = 3f;   // abandoned: settle in behind before trying again
                if (!emergencyMove) {
                    if (ds < -8f) {
                        stats.passesDone++;
                        if (passFromDraft) stats.draftPassesDone++;
                        int type = typeIndex(aggression);
                        stats.passesByType[type]++;
                        // finished inside a turn: carried side by side through it
                        if (passTurn) stats.tightPassesByType[type]++;
                        if (outbrakeCounted) stats.outbrakesDone++;
                    } else {
                        stats.passesAborted++;
                        if (passStall > 1.5f) stats.abortStall++;
                        else if (tooLate) stats.abortLate++;
                        else if (passYield) stats.abortYield++;
                        else stats.abortBlocked++;
                    }
                }
                if (PASS_LOG && !emergencyMove) {
                    System.out.printf("PASSLOG end %s on %s t=%.1f s=%.0f ds=%.1f %s late=%d stall=%d yield=%d brakeDist=%.0f v=%.1f gain=%.1f lane=%.1f d=%.1f%n",
                            car.getName(), passTarget.getName(), raceTime, s, ds, outside ? "outside" : "inside", tooLate ? 1 : 0,
                            passStall > 1.5f ? 1 : 0, passYield ? 1 : 0, passTurnDist(s), v, gain, passLane, d);
                }
                passTarget = null;   // completed, abandoned or the lane closed
                outbraking = outbrakeCounted = false;
                passYield = false;
                passStall = 0f;
            } else {
                desiredLane = passLane;
                weightTarget = 1f;
                // backing out: lift (never more than 1.5 m/s under our speed) until it is ahead of
                // us, rather than braking hard in the turn
                float yieldCap = Math.max(passTarget.getPhysics().getForwardSpeed() - 2f, v - 1.5f);
                if (passYield && yieldCap < speedCap) {
                    speedCap = yieldCap;
                    capCause = 3;
                }
            }
        }

        // Back to the racing line after a pass or an avoiding move only once it is clear: never chop
        // across a car we are alongside or have only just passed. (Merging in behind a car further
        // up the road is fine: the following distance takes care of it.)
        boolean holding = false, avoiding = false;
        if (passTarget == null && formationDone && !mergeHold && laneWeight > 0.05f
                && !racingLineClear(field, s, d, 8f, 8f)) {
            holding = true;
            desiredLane = laneD;
            weightTarget = 1f;
        }

        // Never run into the back of a car that is in our path: either overlapping our lane now
        // or overlapping the lane we are steering into (merging back to the line was a blind
        // spot). The allowed closing speed shrinks with the gap (constant deceleration), the scan
        // range grows with the closing speed, and the human player gets a much wider berth
        // because people brake, lift and wander unpredictably.
        Car threat = null;
        float threatTtc = Float.MAX_VALUE;
        float threatDecel = 0f;
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
            // On a draft run, close right up on the car we mean to pass, planning firmer braking so
            // the tow can build closing speed; once pulling out to pass it, that car only limits us
            // as a collision risk (otherwise the run dies while we move across). Setting a run up
            // in the turn: hang back.
            boolean passing = c == passTarget && !emergencyMove;
            float gap = passing ? 2f
                    : c == draftRunTarget ? (human ? runGap + 2f : runGap)
                    : c == setupTarget ? Math.max(SETUP_GAP - 4f * aggression, human ? HUMAN_FOLLOW_GAP : 12f)
                    : (human ? HUMAN_FOLLOW_GAP : 12f) - 3f * aggression;
            float planDecel = passing ? PASS_DECEL : c == draftRunTarget ? RUN_DECEL : decel;
            float margin = Math.max(ds - gap, 0f);
            float cap = vc + FastMath.sqrt(2f * planDecel * margin) - (human ? 1.5f : 0.5f);
            if (cap < speedCap) {
                speedCap = cap;
                capCause = c == setupTarget ? 1 : c == draftRunTarget ? 2 : 0;
            }
            if (closing > 0.5f) {
                float ttc = Math.max(ds - 5f, 0f) / closing;
                if (ttc < threatTtc) {
                    threatTtc = ttc;
                    threat = c;
                    threatDecel = closing * closing / (2f * Math.max(ds - 5f, 0.5f));
                }
            }
        }
        // emergency: about to hit a car we are still lined up with, faster than braking can fix ->
        // take any free lane, even during the opening no-passing period. Otherwise lift and brake
        // behind it (the speed cap above). Beside a car that already has company the only free
        // lane makes it three wide: brake.
        if (threat != null && threatTtc < EMERGENCY_TTC && threatDecel > EMERGENCY_DECEL && passTarget != threat
                && !hasCompany(threat, field)) {
            float lane = choosePassLane(threat, field);
            if (!Float.isNaN(lane)) {
                passTarget = threat;
                passYield = false;
                passLane = lane;
                desiredLane = lane;
                weightTarget = 1f;
                emergencyMove = true;
                if (formationDone) stats.emergencyMoves++;
            }
        }

        // side-by-side avoidance
        for (Car c : field) {
            if (c == car) continue;
            float ds = TrackSpline.deltaS(s, c.trackS());
            float dd = c.trackD() - d;
            float clearance = c.isPlayer() ? CAR_WIDTH_CLEARANCE + 0.6f : CAR_WIDTH_CLEARANCE;
            if (Math.abs(ds) < 7.5f && Math.abs(dd) < clearance + 0.4f) {
                avoiding = true;
                if (dd > 0) desiredLane = Math.min(desiredLane, c.trackD() - clearance);
                else desiredLane = Math.max(desiredLane, c.trackD() + clearance);
                weightTarget = 1f;
                if (desiredLane < dMin + 0.4f || desiredLane > dMax - 0.4f) {
                    // squeezed: back out of it
                    if (v - 1f < speedCap) {
                        speedCap = v - 1f;   // lift, don't brake
                        capCause = 4;
                    }
                }
            }
        }
        // inside the oval's turns stay off the wall: understeer in traffic pushes cars up the banking
        float dMaxHere = TrackSpline.isOval() && (inTurn || track.distanceToNextTurn(s) < 60f) ? dMax - 0.8f : dMax;
        desiredLane = FastMath.clamp(desiredLane, dMin, dMaxHere);

        // smooth lateral target
        float maxStep = LANE_RATE * dt * (weightTarget > 0 ? 1.5f : 1f);
        if (laneWeight < 0.01f) laneD = line.offset(s);   // keep lane state glued to the line
        laneD += FastMath.clamp(desiredLane - laneD, -maxStep, maxStep);
        laneWeight += FastMath.clamp(weightTarget - laneWeight, -WEIGHT_RATE * dt, WEIGHT_RATE * dt);

        // ---------------- steering: pure pursuit ----------------
        float lookahead = FastMath.clamp(LOOK_BASE + LOOK_PER_SPEED * Math.abs(v), 12f, 75f);
        float sAhead = s + lookahead;
        float dTarget = FastMath.interpolateLinear(laneWeight, line.offset(sAhead), laneD);
        float intendedNow = FastMath.interpolateLinear(laneWeight, line.offset(s), laneD);
        steer(dt, d, v, sAhead, dTarget, intendedNow);

        // ---------------- speed control ----------------
        float vTarget = profile.at(s + 4f);
        if (TrackSpline.isRoadCourse()) {
            // look a little further ahead for the braking points; off the racing line (passing,
            // defending, side by side) the turn is tighter, so slower
            // (going down the inside on the brakes: the later braking points)
            SpeedProfile p = outbraking && passTarget != null ? lateProfile : profile;
            float ahead = s + 4f + ROAD_SPEED_LEAD * Math.max(v, 0f);
            vTarget = p.at(ahead);
            if (laneWeight > 0.05f) {
                vTarget = FastMath.interpolateLinear(Math.min(1f, laneWeight), vTarget, Math.min(vTarget, p.laneAt(ahead, laneD)));
            }
        }
        boolean cornering = inTurn || track.distanceToNextTurn(s) < 150f;
        if (cornering) {
            // dirty air costs front downforce (the aero model: up to 14% front, 5% rear, about 3%
            // of corner speed in a full tow), and an off-line lane is a tighter radius
            vTarget *= 1f - DIRTY_AIR_SLOWDOWN * phys.getDraftFactor();
            if (laneWeight > 0.5f) vTarget *= 0.985f;
            if (!readyToRelease) vTarget *= 0.90f;     // formation pace through turns 1 and 2
        }
        vTarget = Math.min(vTarget, speedCap);
        applySpeed(vTarget, s, d, intendedNow);
        // statistics: hard braking in a turn, by what asked for it (0 the speed profile, 1+ capCause)
        if (formationDone && TrackSpline.isOval() && inTurn && car.getInput().brake > 0.3f) {
            stats.turnBrakeTicks[vTarget < speedCap - 0.01f || capCause < 0 ? 0 : capCause + 1]++;
        }

        // stuck detection (crashed / facing the wall)
        if (Math.abs(v) < 3f) stuckTime += dt; else stuckTime = 0f;

        // statistics (racing laps only: the rolling start runs three abreast on purpose)
        if (formationDone && !mergeHold) {
            stats.racingTicks++;
            if (phys.getDraftFactor() < 0.01f) stats.cleanAirTop = Math.max(stats.cleanAirTop, v);
            float offLine = d - line.offset(s);
            if (passTarget == null && !avoiding && !holding && laneWeight <= 0.05f) {
                int bin = Math.clamp((int) (TrackSpline.wrapS(s) / 50f), 0, stats.trackErrSum.length - 1);
                stats.trackErrSum[bin] += offLine;
                stats.trackErrCount[bin]++;
            }
            if (Math.abs(offLine) > 1.5f) {
                stats.offLineTicks++;
                if (passTarget != null && emergencyMove) stats.offEmergency++;
                else if (passTarget != null) stats.offPassing++;
                else if (avoiding) stats.offAvoiding++;
                else if (holding) stats.offHolding++;
                else if (laneWeight > 0.05f) stats.offReturning++;
                else stats.offTracking++;
            }
            int alongside = 0;
            for (Car c : field) {
                if (c != car && Math.abs(TrackSpline.deltaS(s, c.trackS())) < 5f && c.trackD() > dMin - 1.5f) alongside++;
            }
            if (alongside >= 2) stats.threeWideTicks++;
            boolean capped = capCause >= 0 && speedCap < profile.at(s + 4f) - 0.5f;
            if (!inTurn && track.distanceToNextTurn(s) > 100f) {
                stats.straightTicks++;
                if (capped) stats.straightCapped[capCause]++;
            } else {
                stats.turnTicks++;
                if (capped) stats.turnCapped[capCause]++;
            }
            if (raceTime > 100f) {
                stats.lateTicks++;
                if (alongside >= 2) stats.lateThreeWide++;
            }
        }
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
            // (a road course's braking zones want the brakes on firmly at once)
            brake = FastMath.clamp(-err * (TrackSpline.isOval() ? 0.12f : 0.3f), 0f, 1f);
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
        float low = blocker.trackD() - PASS_OFFSET;
        float high = blocker.trackD() + PASS_OFFSET;
        // a car moving across the track: go behind it, i.e. to the side it is leaving
        float drift = blocker.lateralRate();
        if (Math.abs(drift) > 1.0f) {
            if (drift > 0) low = Math.min(low, blocker.trackD() - PASS_OFFSET - 1f);
            else high = Math.max(high, blocker.trackD() + PASS_OFFSET + 1f);
            float first = drift > 0 ? low : high, second = drift > 0 ? high : low;
            if (usableLane(first, blocker, field)) return first;
            if (usableLane(second, blocker, field)) return second;
            return Float.NaN;
        }
        // the inside of the coming turn (the oval's are all left-handers: low)
        float s = car.trackS();
        boolean left = passTurnDirection(s) > 0f;
        float inside = left ? low : high, outside = left ? high : low;
        boolean insideOk = usableLane(inside, blocker, field);
        boolean outsideOk = usableLane(outside, blocker, field);
        if (insideOk && outsideOk) {
            boolean turnSoon = passTurnDist(s) < 350f || passTurn(s);
            if (turnSoon) return inside;
            return Math.abs(inside - car.trackD()) <= Math.abs(outside - car.trackD()) ? inside : outside;
        }
        if (insideOk) return inside;
        if (outsideOk) return outside;
        return Float.NaN;
    }

    private boolean usableLane(float lane, Car blocker, List<Car> field) {
        return lane >= dMin && lane <= dMax && !laneBlocked(lane, blocker, field, -12f, 20f);
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
