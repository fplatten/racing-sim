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
 */
public final class RaceDirector implements FixedStepPhysics.Listener {

    /** Every race starts on the front straight, just off the last turn (rolling start). */
    public static final float STRAIGHT_START_S = TrackSpline.LENGTH - TrackSpline.STRAIGHT / 2f;
    /** Indianapolis-style grid: rows of three, pole position on the inside. */
    public static final int GRID_COLUMNS = 3;
    public static final float GRID_ROW_GAP = 18f;
    private static final float[] GRID_LANES = {-4.6f, -0.4f, 3.8f};
    public static final float ROLLING_START_SPEED = 58f;   // m/s (~130 mph)
    /** Inside line used when the player resets - well clear of the AI groove on the straights. */
    private static final float RESET_LANE = -6.0f;

    private final TrackSpline track;
    private final RacingLine racingLine;
    private final List<Car> cars = new ArrayList<>();
    private final List<AIDriver> drivers = new ArrayList<>();
    private final Map<Car, LapTracker.Entry> entries = new HashMap<>();
    private final Map<Car, AIDriver> driverOf = new HashMap<>();
    private final Map<Car, PitStop> pits = new HashMap<>();
    private final DraftingSystem drafting = new DraftingSystem();
    /** AI wing levels are drawn at random for every race, in the menu slider's steps. */
    private final java.util.Random setupRandom = new java.util.Random(2024);
    public static final int DOWNFORCE_STEPS = 10;
    private LapTracker lapTracker = new LapTracker();
    private final PlayerInput playerInput;
    private Car player;
    private volatile boolean resetRequested;
    private Runnable onPlayerReset = () -> { };
    private int recoveries;
    private volatile boolean running;
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
    public void requestPlayerReset() { resetRequested = true; }
    /** Number of AI recovery resets so far (diagnostics). */
    public int getRecoveries() { return recoveries; }
    public void setOnPlayerReset(Runnable r) { onPlayerReset = r; }
    public boolean isRunning() { return running; }
    public void setPlayerOverride(java.util.function.BiConsumer<Car, Float> override) { playerOverride = override; }

    /**
     * Grid position (s, d) of a slot (0 = pole): rows of three abreast, pole on the inside; the
     * last row sits at the exit of turn 4 so the whole field is on the front straight.
     */
    public static float[] gridSlot(int slot, int carCount) {
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
        for (Car c : field) addCar(c, aiDrivers.get(c));
    }

    /** Lines every car up on the rolling-start grid (in the order they were added). */
    public void placeGrid() {
        for (int i = 0; i < cars.size(); i++) {
            float[] g = gridSlot(i, cars.size());
            Car c = cars.get(i);
            c.placeOnTrack(track, g[0], g[1], ROLLING_START_SPEED);
            c.updateTrackPosition(track);
        }
    }

    /** Resets the field to the grid and starts a race of {@code laps} laps. */
    public void startRace(int laps) {
        startRace(laps, 0);
    }

    /** Resets the field to the grid and starts a race of {@code laps} laps with {@code stops} mandatory pit stops. */
    public void startRace(int laps, int stops) {
        placeGrid();
        lapTracker.newRace(laps, stops);
        for (int i = 0; i < cars.size(); i++) {
            Car c = cars.get(i);
            PitStop pit = pits.get(c);
            pit.reset();
            pit.setPlannedLaps(planPitLaps(laps, stops, i));
            lapTracker.reset(entries.get(c), c.trackS());
            AIDriver ai = driverOf.get(c);
            if (ai != null) {
                ai.setDownforceLevel(setupRandom.nextInt(DOWNFORCE_STEPS + 1) / (float) DOWNFORCE_STEPS);
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
        if (resetRequested) {
            resetRequested = false;
            resetPlayer();
        }

        for (Car c : cars) c.updateTrackPosition(track, dt);

        drafting.update(cars, dt);

        if (player != null) {
            if (playerOverride != null) playerOverride.accept(player, dt);
            else playerInput.apply(player.getInput(), dt, player.getPhysics().getForwardSpeed());
        }
        for (AIDriver ai : drivers) ai.update(dt, cars);

        for (Car c : cars) {
            c.getInput().pitHold = pits.get(c).isServicing();
            c.getPhysics().step(dt);
        }

        lapTracker.advanceTime(dt);
        for (Car c : cars) lapTracker.update(entries.get(c), c.trackS());
        updatePitStops(dt);
        lapTracker.updatePositions();

        if (player != null) checkPlayerRecovery(dt);

        for (AIDriver ai : drivers) {
            Car c = ai.getCar();
            Vector3f up = c.getBody().getPhysicsRotation().mult(Vector3f.UNIT_Y);
            boolean offTrack = c.trackD() < PitLane.innerLimit(c.trackS()) - 1f || c.trackD() > TrackSpline.ROAD_OUTER + 2f
                    || c.getBody().getPhysicsLocation().y < -3f;
            if (ai.needsRecovery() || up.y < 0.3f || offTrack) recover(ai);
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
        float d = RESET_LANE;
        for (Car c : cars) {
            if (c != player && Math.abs(TrackSpline.deltaS(0f, c.trackS())) < 12f && Math.abs(c.trackD() - d) < 3f) {
                d = c.trackD() + 3.5f;
            }
        }
        player.placeOnTrack(track, 0f, FastMath.clamp(d, -6.0f, 6f), 0f);
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
        boolean outside = player.trackD() > TrackSpline.ROAD_OUTER + TrackSpline.OUTER_WALL_THICKNESS + 0.5f
                || player.trackD() < PitLane.innerLimit(player.trackS()) - 0.5f;
        playerOutside = outside ? playerOutside + dt : 0f;
        if (pos.y > -3f && playerUpsideDown < 2.0f && playerOutside < 3.0f) return;
        playerUpsideDown = 0f;
        playerOutside = 0f;
        float s = TrackSpline.wrapS(player.trackS());
        float d = RESET_LANE;
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
        for (float candidate : new float[]{racingLine.offset(s), -5.5f}) {
            if (rejoinClear(c, s, candidate)) {
                d = candidate;
                break;
            }
        }
        if (Float.isNaN(d)) return;
        recoveries++;
        c.placeOnTrack(track, s, d, 35f);
        lapTracker.teleport(entries.get(c), s);
        ai.resetLane();
    }
}
