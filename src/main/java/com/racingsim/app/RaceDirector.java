package com.racingsim.app;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.racingsim.ai.AIDriver;
import com.racingsim.ai.RacingLine;
import com.racingsim.physics.DraftingSystem;
import com.racingsim.physics.FixedStepPhysics;
import com.racingsim.track.LapTracker;
import com.racingsim.track.PitLane;
import com.racingsim.track.PitStop;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-physics-step orchestration (240 Hz):
 * <ol>
 *   <li>project every car onto the track (s, d)</li>
 *   <li>evaluate slipstreams (draft factors) - before any aero force is computed</li>
 *   <li>update drivers (player keyboard / AI)</li>
 *   <li>step each car's {@code VehiclePhysics}</li>
 *   <li>lap / position bookkeeping and AI recovery</li>
 * </ol>
 * The oval has a rolling start, three abreast; the road course a standing start: the field lines
 * up two by two, staggered, and waits on the brakes while five red lights come on, one a second;
 * the race (and its clock) starts when they go out ({@link #startLights()}, {@link #greenTime()}).
 */
public final class RaceDirector implements FixedStepPhysics.Listener {

    /** Every oval race starts on the front straight, just off the last turn (rolling start). */
    public static final float STRAIGHT_START_S = TrackSpline.OVAL_LENGTH - TrackSpline.STRAIGHT / 2f;
    /** Indianapolis-style grid: rows of three, pole position on the inside. */
    public static final int GRID_COLUMNS = 3;
    public static final float GRID_ROW_GAP = 18f;
    private static final float[] GRID_LANES = {-4.6f, -0.4f, 3.8f};
    public static final float ROLLING_START_SPEED = 58f;   // m/s (~130 mph)
    /** Standing start (road course): two by two, staggered, the pole 8 m behind the line. */
    private static final float POLE_GAP = 8f, STAGGER_GAP = 7.5f, STAGGER_LANE = 3.0f;
    /** Standing start: a red light comes on every LIGHT_INTERVAL s; all five go out together. */
    public static final float LIGHT_INTERVAL = 1f, LIGHTS_OUT_AT = 6.2f;

    /** Inside line used when the player resets - well clear of the AI groove on the straights. */
    private static float resetLane() {
        return TrackSpline.isOval() ? -6.0f : -4.0f;
    }

    private final TrackSpline track;
    private final RacingLine racingLine;
    private final List<Car> cars = new ArrayList<>();
    private final List<AIDriver> drivers = new ArrayList<>();
    private final Map<Car, LapTracker.Entry> entries = new HashMap<>();
    private final Map<Car, AIDriver> driverOf = new HashMap<>();
    private final Map<Car, PitStop> pits = new HashMap<>();
    private final DraftingSystem drafting = new DraftingSystem();
    /** AI wing levels: each driver type's own, +-1 at random for every race, in the menu slider's steps. */
    private final java.util.Random setupRandom = new java.util.Random(2024);
    public static final int DOWNFORCE_STEPS = 10;
    /** Highest wing level an AI car runs (of DOWNFORCE_STEPS). */
    public static final int AI_MAX_DOWNFORCE = DOWNFORCE_STEPS;
    private LapTracker lapTracker = new LapTracker();
    private final PlayerInput playerInput;
    private Car player;
    private volatile boolean resetRequested;
    private Runnable onPlayerReset = () -> { };
    private int recoveries;
    private volatile boolean running;
    /** Standing start: time since the field lined up (-1: the lights are out), and since they went out. */
    private volatile float lightsTime = -1f, greenTime = -1f;
    /** Cars driven by external bots (bots/PROTOCOL.md); a lost bot's car goes back to its AI driver. */
    private final Map<Car, com.racingsim.bot.ExternalDriver> bots = new HashMap<>();
    private final java.util.Set<Car> botsHandedBack = new java.util.HashSet<>();
    private final Map<Car, Integer> botLaps = new HashMap<>();
    private final Map<Car, Float> botStuckTime = new HashMap<>();
    /** Optional replacement for the keyboard (headless tests drive the player car with it). */
    private java.util.function.BiConsumer<Car, Float> playerOverride;

    public RaceDirector(TrackSpline track, RacingLine racingLine, PlayerInput playerInput) {
        this.track = track;
        this.racingLine = racingLine;
        this.playerInput = playerInput;
    }

    public void addCar(Car car, AIDriver driver) {
        cars.add(car);
        if (driver != null) {
            drivers.add(driver);
            driverOf.put(car, driver);
        }
        if (car.isPlayer()) player = car;
        entries.put(car, lapTracker.addEntry(car.getName(), car.isPlayer(), car.trackS()));
        PitStop pit = new PitStop(car.isPlayer() ? PitLane.PLAYER_STALL : nextAiStall());
        pits.put(car, pit);
        if (driver != null) driver.setPit(pit);
    }

    /**
     * Pit box for the next AI car: every other box first (an empty box on each side makes it
     * easy to get in and out), then the ones in between; the middle box is the player's.
     */
    private int nextAiStall() {
        int used = 0;
        for (PitStop p : pits.values()) if (p.stall() != PitLane.PLAYER_STALL) used++;
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int st = pass; st < PitLane.STALLS; st += 2) {
                if (st == PitLane.PLAYER_STALL) continue;
                if (k++ == used) return st;
            }
        }
        return used % PitLane.STALLS;
    }

    /** Pit-stop state of a car (its box, the stop in progress). */
    public PitStop pitOf(Car car) { return pits.get(car); }

    public List<Car> getCars() { return cars; }
    public Car getPlayer() { return player; }
    public LapTracker getLapTracker() { return lapTracker; }
    public LapTracker.Entry entryOf(Car car) { return entries.get(car); }
    /** The car behind a standings entry (null if it is not in the field). */
    public Car carOf(LapTracker.Entry e) {
        for (Map.Entry<Car, LapTracker.Entry> m : entries.entrySet()) if (m.getValue() == e) return m.getKey();
        return null;
    }
    /** The AI driving a car, or null for the human player. */
    public AIDriver driverOf(Car car) { return driverOf.get(car); }
    public void requestPlayerReset() { resetRequested = true; }
    /** Number of AI recovery resets so far (diagnostics). */
    public int getRecoveries() { return recoveries; }
    public void setOnPlayerReset(Runnable r) { onPlayerReset = r; }
    public boolean isRunning() { return running; }

    /** Standing start: red lights lit (0..5) while the field waits; -1 once they are out (or a rolling start). */
    public int startLights() {
        float t = lightsTime;
        return t >= 0f ? Math.clamp((int) Math.floor(t / LIGHT_INTERVAL), 0, 5) : -1;
    }

    /** Seconds since the lights went out (for a "GO"), -1 otherwise (and 3 s after). */
    public float greenTime() { return greenTime; }

    /** True while the field waits on the grid for the lights to go out. */
    public boolean waitingForLights() { return running && lightsTime >= 0f; }
    public void setPlayerOverride(java.util.function.BiConsumer<Car, Float> override) { playerOverride = override; }

    /**
     * Hands {@code car} to an external bot for the race about to start (call after {@link #setField}
     * and before {@link #startRace}); {@code mode} is told to the bot (race, practice, training).
     */
    public void attachBot(Car car, com.racingsim.bot.ExternalDriver bot) {
        bots.put(car, bot);
    }

    /** The bots in this race (car to driver). */
    public Map<Car, com.racingsim.bot.ExternalDriver> getBots() { return bots; }

    /** The car's number in the bot protocol (1-based, grid order). */
    public int carId(Car c) { return cars.indexOf(c) + 1; }

    private com.racingsim.bot.BotSensors.Phase phaseOf(Car c) {
        if (lightsTime >= 0f) return com.racingsim.bot.BotSensors.Phase.LIGHTS;
        if (!running) return com.racingsim.bot.BotSensors.Phase.GRID;
        return entries.get(c).finished() ? com.racingsim.bot.BotSensors.Phase.FINISHED : com.racingsim.bot.BotSensors.Phase.GREEN;
    }

    private void updateBot(com.racingsim.bot.ExternalDriver bot, float dt, boolean drive) {
        Car c = bot.car();
        bot.update(dt, track, cars, lapTracker, entries.get(c), phaseOf(c), Math.max(0, startLights()), this::carId, drive);
    }

    /** False if the car's bot drives it this step (else its AI driver / the player input does). */
    private boolean botDrives(Car c, float dt) {
        com.racingsim.bot.ExternalDriver bot = bots.get(c);
        if (bot == null) return false;
        updateBot(bot, dt, true);
        if (!bot.isLost()) return true;
        if (botsHandedBack.add(c)) {
            AIDriver ai = driverOf.get(c);
            if (ai != null) ai.resetLane();
        }
        return false;
    }

    /**
     * Grid position (s, d) of a slot (0 = pole). The oval: rows of three abreast, pole on the
     * inside; the last row sits at the exit of turn 4 so the whole field is on the front straight.
     * The road course: two by two, staggered, behind the start line.
     */
    public static float[] gridSlot(int slot, int carCount) {
        if (TrackSpline.isRoadCourse()) {
            // pole on the left (the outside line into the first turn, a right-hander), each car
            // half a row behind the one before it on the other side
            return new float[]{TrackSpline.wrapS(-POLE_GAP - slot * STAGGER_GAP), slot % 2 == 0 ? -STAGGER_LANE : STAGGER_LANE};
        }
        int rows = (carCount + GRID_COLUMNS - 1) / GRID_COLUMNS;
        float frontS = STRAIGHT_START_S + (rows - 1) * GRID_ROW_GAP + 5f;
        int row = slot / GRID_COLUMNS, col = slot % GRID_COLUMNS;
        return new float[]{frontS - row * GRID_ROW_GAP, GRID_LANES[col]};
    }

    /**
     * Replaces the field for the next race. {@code field} is in grid order (index 0 = pole) and
     * must contain the player's car; {@code aiDrivers} maps every AI car to its driver.
     */
    public void setField(List<Car> field, Map<Car, AIDriver> aiDrivers) {
        running = false;
        cars.clear();
        drivers.clear();
        driverOf.clear();
        entries.clear();
        pits.clear();
        lapTracker = new LapTracker();
        player = null;
        bots.clear();
        botsHandedBack.clear();
        botLaps.clear();
        for (Car c : field) addCar(c, aiDrivers.get(c));
    }

    /** Lines every car up on the grid (in the order they were added): rolling on the oval, stopped on the road course. */
    public void placeGrid() {
        float speed = TrackSpline.isOval() ? ROLLING_START_SPEED : 0f;
        for (int i = 0; i < cars.size(); i++) {
            float[] g = gridSlot(i, cars.size());
            Car c = cars.get(i);
            c.placeOnTrack(track, g[0], g[1], speed);
            c.updateTrackPosition(track);
        }
    }

    /** Resets the field to the grid and starts a race of {@code laps} laps. */
    public void startRace(int laps) {
        startRace(laps, 0);
    }

    /** Resets the field to the grid and starts a race of {@code laps} laps with {@code stops} mandatory pit stops. */
    public void startRace(int laps, int stops) {
        if (!TrackSpline.layout().hasPits()) stops = 0;      // the road course has no pit lane
        placeGrid();
        // the oval: a rolling start; the road course: a standing start behind the lights
        lightsTime = TrackSpline.isRoadCourse() ? 0f : -1f;
        greenTime = -1f;
        lapTracker.newRace(laps, stops);
        for (int i = 0; i < cars.size(); i++) {
            Car c = cars.get(i);
            PitStop pit = pits.get(c);
            pit.reset();
            pit.setPlannedLaps(planPitLaps(laps, stops, i));
            lapTracker.reset(entries.get(c), c.trackS());
            AIDriver ai = driverOf.get(c);
            if (ai != null) {
                // Each driver's own wing level: rookies run a heavy wing (stable, slower), veterans a
                // light one (fast, loose), +-1 at random. Tuning: -DaiDownforce=<0..10> gives every AI
                // that level, -DaiWingMax=<0..10> caps it.
                int wingMax = Integer.getInteger("aiWingMax", AI_MAX_DOWNFORCE);
                int wing = Math.min(ai.preferredWing(DOWNFORCE_STEPS, setupRandom.nextInt(3) - 1),
                        Math.clamp(wingMax, 0, DOWNFORCE_STEPS));
                int forced = Integer.getInteger("aiDownforce", -1);
                if (forced >= 0) wing = Math.min(forced, DOWNFORCE_STEPS);
                ai.setDownforceLevel(wing / (float) DOWNFORCE_STEPS);
                System.out.printf("AI driver: %-9s %-7s (aggression %.2f) wing %d/%d%n", c.getName(),
                        AIDriver.typeName(ai.getAggression()), ai.getAggression(), wing, DOWNFORCE_STEPS);
                ai.resetForRace();
            }
        }
        lapTracker.updatePositions();
        playerInput.clearCommands();
        recoveries = 0;
        running = true;
    }

    /**
     * Laps on which car {@code index} plans its stops: spread evenly over the race, staggered by
     * one lap either way between cars so the pit lane is not swamped.
     */
    static int[] planPitLaps(int laps, int stops, int index) {
        int[] plan = new int[stops];
        int offset = stops > 0 && laps >= 2 * (stops + 1) ? index % 3 - 1 : 0;
        for (int k = 0; k < stops; k++) {
            int lap = Math.round((k + 1) * laps / (float) (stops + 1)) + offset;
            lap = Math.max(k > 0 ? plan[k - 1] + 1 : 1, Math.min(laps, lap));
            plan[k] = lap;
        }
        return plan;
    }

    /** Freezes race logic (menu / results screen). */
    public void stopRace() {
        running = false;
    }

    @Override
    public void fixedStep(float dt) {
        if (!running) return;
        // standing start: the field waits on the grid while the five red lights come on, one a
        // second, and goes when they go out
        boolean waiting = lightsTime >= 0f;
        if (waiting) {
            lightsTime += dt;
            if (lightsTime >= LIGHTS_OUT_AT) {
                lightsTime = -1f;
                greenTime = 0f;
                lapTracker.startClock();
            }
        } else if (greenTime >= 0f) {
            greenTime += dt;
            if (greenTime > 3f) greenTime = -1f;
        }
        if (resetRequested && !waiting) {
            resetRequested = false;
            resetPlayer();
        }

        for (Car c : cars) c.updateTrackPosition(track, dt);

        drafting.update(cars, dt);

        if (waiting) {
            // lined up on the grid, waiting for the lights: held on the brakes (not hard enough to
            // engage reverse)
            for (Car c : cars) {
                c.getInput().clear();
                c.getInput().brake = 0.45f;
            }
            for (com.racingsim.bot.ExternalDriver bot : bots.values()) updateBot(bot, dt, false);
        } else {
            if (player != null && !botDrives(player, dt)) {
                AIDriver pilot = driverOf.get(player);
                if (playerOverride != null) playerOverride.accept(player, dt);
                else if (pilot != null) pilot.update(dt, cars);      // a lost bot's player car
                else playerInput.apply(player.getInput(), dt, player.getPhysics().getForwardSpeed());
            }
            for (AIDriver ai : drivers) {
                if (ai.getCar() != player && !botDrives(ai.getCar(), dt)) ai.update(dt, cars);
            }
        }

        for (Car c : cars) {
            c.getInput().pitHold = pits.get(c).isServicing();
            c.getPhysics().step(dt);
        }

        lapTracker.advanceTime(dt);
        for (Car c : cars) lapTracker.update(entries.get(c), c.trackS());
        updatePitStops(dt);
        lapTracker.updatePositions();
        for (Map.Entry<Car, com.racingsim.bot.ExternalDriver> b : bots.entrySet()) {
            LapTracker.Entry e = entries.get(b.getKey());
            int done = e.lapsCompleted();
            Integer before = botLaps.put(b.getKey(), done);
            if (before != null && done > before) {
                b.getValue().event("lapComplete", new com.racingsim.bot.Json.Out().obj().num("lap", done)
                        .num("lapTime", e.lastLapTime()).num("position", e.position()).bool("finished", e.finished()).endObj());
            }
        }

        if (player != null) checkPlayerRecovery(dt);

        for (AIDriver ai : drivers) {
            Car c = ai.getCar();
            Vector3f up = c.getBody().getPhysicsRotation().mult(Vector3f.UNIT_Y);
            boolean offTrack = c.trackD() < track.minD(c.trackS()) - 1f || c.trackD() > track.maxD(c.trackS()) + 2f
                    || c.getBody().getPhysicsLocation().y < track.elevation(c.trackS()) - 3f;
            // a bot's car: stuck (under 2 m/s) for 5 s of racing is towed back like an AI car
            boolean botStuck = false;
            boolean botDriven = bots.containsKey(c) && !bots.get(c).isLost();
            if (botDriven && !waiting && !entries.get(c).finished()) {
                float t = c.speed() < 2f ? botStuckTime.merge(c, FixedStepPhysics.DT, Float::sum) : 0f;
                if (t == 0f) botStuckTime.put(c, 0f);
                botStuck = t > 5f;
                if (botStuck) botStuckTime.put(c, 0f);
            }
            if ((ai.needsRecovery() && !botDriven) || botStuck || up.y < 0.3f || offTrack) recover(ai);
        }
    }

    /** Pit-box countdowns for every car, and pit calls for the AI (made in turn 3). */
    private void updatePitStops(float dt) {
        for (Car c : cars) {
            LapTracker.Entry e = entries.get(c);
            PitStop pit = pits.get(c);
            if (pit.update(dt, c.trackS(), c.trackD(), c.speed())) lapTracker.pitStopCompleted(e);
            AIDriver ai = driverOf.get(c);
            if (ai == null || ai.isPitting() || e.finished() || lapTracker.stopsMet(e)) continue;
            float rel = PitLane.rel(c.trackS());
            if (rel > AIDriver.PIT_COMMIT_REL - 60f && rel < AIDriver.PIT_COMMIT_REL
                    && e.lap() >= pit.nextPlannedLap(e.pitStops())
                    && lapTracker.raceTime() > 0f) {
                ai.requestPit();
            }
        }
    }

    private void resetPlayer() {
        if (player == null) return;
        // restart on the inside lane, clear of the AI groove (which runs high on the straights);
        // move over if an AI car happens to be right there
        float d = resetLane();
        for (Car c : cars) {
            if (c != player && Math.abs(TrackSpline.deltaS(0f, c.trackS())) < 12f && Math.abs(c.trackD() - d) < 3f) {
                d = c.trackD() + 3.5f;
            }
        }
        float edge = TrackSpline.isOval() ? 6f : TrackSpline.ROAD_OUTER - 1.5f;
        player.placeOnTrack(track, 0f, FastMath.clamp(d, -edge, edge), 0f);
        playerInput.clearCommands();
        lapTracker.resetToLine(entries.get(player));
        onPlayerReset.run();
    }

    private float playerUpsideDown, playerOutside;

    /**
     * Safety net for the player: a car below the world (should never happen now that the
     * ground is solid), upside down for 2 s, or stranded outside the retaining walls for 3 s
     * (there is no way to drive back over them) is put back on the track where it left it.
     */
    private void checkPlayerRecovery(float dt) {
        Vector3f pos = player.getBody().getPhysicsLocation();
        Vector3f up = player.getBody().getPhysicsRotation().mult(Vector3f.UNIT_Y);
        playerUpsideDown = up.y < 0.25f ? playerUpsideDown + dt : 0f;
        float ps = player.trackS();
        boolean outside = player.trackD() > track.maxD(ps) + (TrackSpline.isOval() ? TrackSpline.OUTER_WALL_THICKNESS : 0f) + 0.5f
                || player.trackD() < track.minD(ps) - 0.5f;
        playerOutside = outside ? playerOutside + dt : 0f;
        if (pos.y > track.elevation(ps) - 3f && playerUpsideDown < 2.0f && playerOutside < 3.0f) return;
        playerUpsideDown = 0f;
        playerOutside = 0f;
        float s = TrackSpline.wrapS(player.trackS());
        float d = resetLane();
        if (!rejoinClear(player, s, d)) d = TrackSpline.ROAD_OUTER - 1.5f;
        player.placeOnTrack(track, s, d, 25f);
        lapTracker.teleport(entries.get(player), s);
        playerInput.clearCommands();
        onPlayerReset.run();
    }

    private boolean rejoinClear(Car self, float s, float d) {
        for (Car o : cars) {
            if (o == self) continue;
            float ds = TrackSpline.deltaS(s, o.trackS());
            if (ds > -120f && ds < 25f && Math.abs(o.trackD() - d) < 3.5f) return false;
        }
        return true;
    }

    /** Puts a crashed / stuck AI car back on the racing line (like a tow back onto the track). */
    private void recover(AIDriver ai) {
        Car c = ai.getCar();
        float s = TrackSpline.wrapS(c.trackS() + 5f);
        // rejoin on the racing line, or on the inside if that would drop the car in front of
        // someone; if both are occupied wait for the traffic to pass
        float d = Float.NaN;
        for (float candidate : new float[]{racingLine.offset(s), TrackSpline.isOval() ? -5.5f : 0f}) {
            if (rejoinClear(c, s, candidate)) {
                d = candidate;
                break;
            }
        }
        if (Float.isNaN(d)) return;
        recoveries++;
        com.racingsim.bot.ExternalDriver bot = bots.get(c);
        if (bot != null) bot.event("recovered", new com.racingsim.bot.Json.Out().obj().num("s", s).num("d", d).endObj());
        c.placeOnTrack(track, s, d, 35f);
        lapTracker.teleport(entries.get(c), s);
        ai.resetLane();
    }
}
