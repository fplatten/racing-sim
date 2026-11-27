package com.racingsim.bot;

import com.racingsim.track.LapTracker;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.VehicleInput;

import java.util.List;
import java.util.function.Function;

/**
 * Drives a car from a bot's commands. Called on every 240 Hz physics step; every
 * {@code 240 / controlHz} steps it sends the bot a {@code state} and between those it holds the
 * bot's newest command.
 * <ul>
 *   <li>Realtime (racing): never waits; a bot that has answered nothing for 2 s of race time (or
 *       has gone) is "lost", and the race director hands its car back to the built-in AI.</li>
 *   <li>Lockstep (training, headless): waits for the answer to every state, so the race is
 *       deterministic and runs as fast as the bot and the CPU allow.</li>
 * </ul>
 */
public final class ExternalDriver {

    public static final int PHYSICS_HZ = 240;
    public static final long LOCKSTEP_TIMEOUT_MS = 30_000;

    private final BotConnection conn;
    private final Car car;
    private final boolean lockstep;
    private final int controlHz, stepsPerControl;
    private long tick;
    private int steps;
    private boolean lost;
    private long appliedShiftTick = -1;
    private BotConnection.Control held;
    /** Training: carries out a bot's {@code reset} (null: resets are ignored). */
    private java.util.function.Consumer<java.util.Map<String, Object>> resetHandler;

    public ExternalDriver(BotConnection conn, Car car, int controlHz, boolean lockstep) {
        this.conn = conn;
        this.car = car;
        this.lockstep = lockstep;
        this.controlHz = Math.clamp(controlHz, 10, PHYSICS_HZ);
        this.stepsPerControl = Math.max(1, Math.round(PHYSICS_HZ / (float) this.controlHz));
    }

    /** Training only: lets the bot move its car with {@code reset} messages (see PROTOCOL.md). */
    public void setResetHandler(java.util.function.Consumer<java.util.Map<String, Object>> handler) { resetHandler = handler; }

    public BotConnection connection() { return conn; }
    public Car car() { return car; }
    public boolean isLost() { return lost; }
    /** The bot's newest debug drawing ({@code [[s1,d1,s2,d2,"#rrggbb"], ...]}), or null. */
    public List<Object> debugLines() { return held != null ? held.debug() : null; }

    /** Sends {@code welcome} and {@code track} (at the start of every race). */
    public void welcome(int carId, String mode, TrackSpline track) {
        tick = 0;
        steps = 0;
        lost = false;
        held = null;
        var spec = car.getSpec();
        var aero = car.getPhysics().getAeroModel();
        Json.Out o = new Json.Out().obj().str("type", "welcome").num("protocol", BotServer.PROTOCOL)
                .num("carId", carId).str("name", conn.name()).str("mode", mode)
                .num("controlHz", controlHz).num("physicsHz", PHYSICS_HZ)
                .key("car").obj().str("type", spec.type.name().toLowerCase(java.util.Locale.ROOT))
                .num("mass", spec.mass).num("redline", spec.redlineRpm).num("gears", spec.gearRatios.length)
                .num("wing", aero.getDownforceLevel()).num("maxBrakeDecel", spec.maxBrakeDecel)
                .num("tireMu", spec.tireMu).endObj()
                .endObj();
        conn.send(o.toString());
        conn.send(BotSensors.trackMessage(track));
    }

    /**
     * One physics step. {@code drive} = false while the field waits on the grid (the director holds
     * the brakes): the bot still gets its states.
     */
    public void update(float dt, TrackSpline track, List<Car> field, LapTracker laps, LapTracker.Entry entry,
                       BotSensors.Phase phase, int lightsOn, Function<Car, Integer> idOf, boolean drive) {
        if (lost) return;
        if (steps++ % stepsPerControl == 0) {
            tick++;
            conn.send(BotSensors.stateMessage(tick, laps.getTime(), car, track, field, laps, entry, phase, lightsOn, idOf));
            while (lockstep) {
                BotConnection.Control c = conn.awaitControl(tick, LOCKSTEP_TIMEOUT_MS);
                java.util.Map<String, Object> reset = conn.takeReset();
                if (reset != null && resetHandler != null) {
                    // training: the car is moved; the bot gets the new state straight away
                    resetHandler.accept(reset);
                    held = null;
                    tick++;
                    conn.send(BotSensors.stateMessage(tick, laps.getTime(), car, track, field, laps, entry, phase, lightsOn, idOf));
                    continue;
                }
                if (c == null || c.tick() < tick) {
                    lose("no answer within " + LOCKSTEP_TIMEOUT_MS / 1000 + " s");
                    return;
                }
                break;
            }
        }
        BotConnection.Control c = conn.latest();
        if (c != null) held = c;
        if (!conn.isOpen()) {
            lose("disconnected");
            return;
        }
        long answered = held != null ? held.tick() : 0;
        if (!lockstep && tick - answered > 2L * controlHz) {
            lose("no answer for 2 s");
            return;
        }
        if (!drive) return;
        VehicleInput in = car.getInput();
        if (held == null) {
            in.clear();
            in.brake = 0.3f;
            return;
        }
        in.steer = held.steer();
        in.throttle = held.throttle();
        in.brake = held.brake();
        if (held.shift() != 0 && held.tick() != appliedShiftTick) {
            appliedShiftTick = held.tick();
            if (held.shift() > 0) in.shiftUpRequest = true;
            else in.shiftDownRequest = true;
        }
    }

    private void lose(String why) {
        lost = true;
        System.out.println("Bot " + conn.name() + " lost (" + why + "): the built-in AI takes over " + car.getName());
        car.getInput().clear();
        car.getInput().brake = 0.3f;
    }

    /** Sends an {@code event} ({@code lapComplete}, {@code contact}, {@code offTrack}, {@code recovered}, {@code finished}). */
    public void event(String kind, Json.Out fields) {
        String extra = fields == null ? "" : fields.toString();
        String line = "{\"type\":\"event\",\"event\":\"" + kind + "\",\"tick\":" + tick
                + (extra.length() > 2 ? "," + extra.substring(1, extra.length() - 1) : "") + "}";
        conn.send(line);
    }
}
