package com.racingsim.bot;

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.racingsim.track.LapTracker;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.VehiclePhysics;
import com.racingsim.vehicle.VehicleSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the bot protocol's {@code track} and {@code state} messages (bots/PROTOCOL.md). Every value
 * comes from the track's geometry (s, d, curvature, walls) and the car's own state, never from a
 * physics ray cast, so the Java and Unreal games give the same numbers (the Unreal port is
 * Source/RacingSim/Bot/BotSensors.cpp). Sign conventions: d and every angle positive to the right,
 * steering positive to the right.
 */
public final class BotSensors {

    /** Distances ahead (m) of the {@code ahead} samples. */
    public static final float[] AHEAD = {10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            120, 140, 160, 180, 200, 220, 240, 260, 280, 300};
    /** Ray directions relative to the car's heading (degrees, + right). */
    public static final int RAYS = 19;
    public static final float RAY_SPREAD_DEG = 90f, RAY_MAX = 200f, RAY_STEP = 1f;
    public static final int OPPONENTS = 8;
    public static final float OPPONENT_RANGE = 200f;
    /** Spacing of the track message's samples (m). */
    public static final float TRACK_STEP = 5f;

    private BotSensors() {}

    /** The once-per-race track description. */
    public static String trackMessage(TrackSpline track) {
        Json.Out o = new Json.Out().obj().str("type", "track")
                .str("layout", TrackSpline.layout().key())
                .num("length", TrackSpline.LENGTH)
                .num("step", TRACK_STEP)
                .str("columns", "s,x,y,z,heading,curvature,minD,maxD,bank,grade")
                .key("samples").arr();
        Vector3f p = new Vector3f();
        for (float s = 0f; s < TrackSpline.LENGTH - 0.01f; s += TRACK_STEP) {
            track.point(s, 0f, p);
            o.arr().num(s).num(round(p.x, 100)).num(round(p.y, 100)).num(round(p.z, 100))
                    .num(round(track.heading(s), 1e5)).num(round(track.curvature(s), 1e6))
                    .num(round(track.minD(s), 100)).num(round(track.maxD(s), 100))
                    .num(round(track.bank(s), 1e4)).num(round(track.grade(s), 1e4)).endArr();
        }
        return o.endArr().endObj().toString();
    }

    private static double round(double v, double scale) {
        return Math.rint(v * scale) / scale;
    }

    /** The car's heading relative to the track at its position (rad, + = pointing right of the track direction). */
    public static float headingError(Car car, TrackSpline track) {
        Vector3f f = car.getBody().getPhysicsRotation().mult(Vector3f.UNIT_Z);
        return TrackSpline.wrapAngle(FastMath.atan2(f.z, f.x) - track.heading(car.trackS()));
    }

    /**
     * Distance (m) from (s, d) along a straight line at {@code angle} to the track direction (rad,
     * + right) to the first wall, marched in track coordinates: the track turns under the ray
     * (curvature + left), so a ray along a left-hander's direction runs into its outside wall.
     */
    public static float ray(TrackSpline track, float s, float d, float angle) {
        float theta = angle;
        for (float r = 0f; r < RAY_MAX; r += RAY_STEP) {
            float k = track.curvature(s);
            float scale = 1f + k * d;                    // path length at offset d per metre of centerline
            float ds = RAY_STEP * FastMath.cos(theta) / Math.max(scale, 0.2f);
            s += ds;
            d += RAY_STEP * FastMath.sin(theta);
            theta += k * ds;                             // the track direction turns left by k per metre
            if (d < track.minD(s) || d > track.maxD(s)) return r + RAY_STEP;
        }
        return RAY_MAX;
    }

    /** What the race is doing, for the state's {@code race.phase}. */
    public enum Phase { GRID, LIGHTS, GREEN, FINISHED }

    /** One control tick's {@code state} message for {@code car}. */
    public static String stateMessage(long tick, float time, Car car, TrackSpline track, List<Car> field,
                                      LapTracker laps, LapTracker.Entry entry, Phase phase, int lightsOn,
                                      java.util.function.Function<Car, Integer> idOf) {
        VehiclePhysics ph = car.getPhysics();
        float s = car.trackS(), d = car.trackD();
        float herr = headingError(car, track);
        Vector3f vel = car.getBody().getLinearVelocity(null);
        Vector3f f = car.getBody().getPhysicsRotation().mult(Vector3f.UNIT_Z);
        Vector3f right = new Vector3f(-f.z, 0f, f.x).normalizeLocal();
        Vector3f angVel = car.getBody().getAngularVelocity(null);
        int onTrack = 0;
        for (VehiclePhysics.Wheel w : ph.getWheels()) if (w.contact && w.onTrack) onTrack++;
        VehicleSpec spec = car.getSpec();
        Json.Out o = new Json.Out().obj().str("type", "state").num("tick", tick).num("t", round(time, 1000));

        o.key("car").obj()
                .num("s", round(s, 1000)).num("d", round(d, 1000))
                .num("speed", round(ph.getSpeed(), 1000)).num("vLong", round(ph.getForwardSpeed(), 1000))
                .num("vLat", round(vel.dot(right), 1000)).num("dRate", round(car.lateralRate(), 1000))
                .num("yawRate", round(-angVel.y, 1e4)).num("headingErr", round(herr, 1e4))
                .num("rpm", Math.round(ph.getEngineRpm())).num("gear", ph.getGearbox().getGear())
                .num("gLat", round(ph.getLateralG(), 1000)).num("gLon", round(ph.getLongitudinalG(), 1000))
                .num("slipFront", round(ph.getFrontSlipAngle(), 1e4)).num("slipRear", round(ph.getRearSlipAngle(), 1e4))
                .num("wheelsOnTrack", onTrack).num("draft", round(ph.getDraftFactor(), 1000))
                .num("steerAngle", round(-ph.getSteerAngle(), 1e4))
                .num("lap", entry.lap()).num("lapsCompleted", entry.lapsCompleted())
                .num("lapTime", round(entry.currentLapTime(laps.getTime()), 1000))
                .num("lastLap", entry.lastLapTime()).num("bestLap", entry.bestLapTime())
                .num("position", entry.position()).bool("finished", entry.finished())
                .num("redline", spec.redlineRpm)
                .endObj();

        o.key("ahead").arr();
        for (float x : AHEAD) {
            float sa = TrackSpline.wrapS(s + x);
            o.arr().num(x).num(round(track.curvature(sa), 1e6)).num(round(track.minD(sa), 100))
                    .num(round(track.maxD(sa), 100)).endArr();
        }
        o.endArr();

        o.key("rays").arr();
        for (int i = 0; i < RAYS; i++) {
            float a = (-RAY_SPREAD_DEG + 2f * RAY_SPREAD_DEG * i / (RAYS - 1)) * FastMath.DEG_TO_RAD;
            o.num(round(ray(track, s, d, herr + a), 100));
        }
        o.endArr();

        List<Car> near = new ArrayList<>();
        for (Car c : field) {
            if (c != car && Math.abs(TrackSpline.deltaS(s, c.trackS())) < OPPONENT_RANGE) near.add(c);
        }
        near.sort((a, b) -> Float.compare(Math.abs(TrackSpline.deltaS(s, a.trackS())), Math.abs(TrackSpline.deltaS(s, b.trackS()))));
        o.key("opponents").arr();
        for (int i = 0; i < Math.min(OPPONENTS, near.size()); i++) {
            Car c = near.get(i);
            float ds = TrackSpline.deltaS(s, c.trackS());
            float rel = c.getPhysics().getForwardSpeed() - ph.getForwardSpeed();
            // time to contact: only when closing (ahead and slower, or behind and faster)
            float closing = ds > 0 ? -rel : rel;
            float ttc = closing > 0.1f ? Math.abs(ds) / closing : -1f;
            o.obj().num("id", idOf.apply(c)).num("ds", round(ds, 100)).num("d", round(c.trackD(), 100))
                    .num("dd", round(c.trackD() - d, 100)).num("relSpeed", round(rel, 100))
                    .num("ttc", round(ttc, 100)).num("headingErr", round(headingError(c, track), 1e4))
                    .endObj();
        }
        o.endArr();

        o.key("race").obj().str("phase", phase.name().toLowerCase(java.util.Locale.ROOT))
                .num("lightsOn", lightsOn).num("lapsTotal", laps.getRaceLaps() == Integer.MAX_VALUE ? -1 : laps.getRaceLaps())
                .num("cars", field.size()).num("raceTime", round(Math.max(0f, laps.raceTime()), 1000))
                .endObj();
        return o.endObj().toString();
    }
}
