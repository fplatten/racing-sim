package com.racingsim.bot;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.math.Vector3f;
import com.jme3.system.NativeLibraryLoader;
import com.racingsim.ai.AIDriver;
import com.racingsim.ai.RacingLine;
import com.racingsim.ai.SpeedProfile;
import com.racingsim.app.PlayerInput;
import com.racingsim.app.RaceDirector;
import com.racingsim.physics.FixedStepPhysics;
import com.racingsim.track.LapTracker;
import com.racingsim.track.TrackBuilder;
import com.racingsim.track.TrackLayout;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.CarModelFactory;
import com.racingsim.vehicle.Livery;
import com.racingsim.vehicle.VehicleSpec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A race for bots without a window (bots/PROTOCOL.md):
 * <pre>
 *   ./gradlew botRace -Ptrack=road -Plaps=3 -Popponents=7 -PbotCount=1 [-Plockstep=true] [-Presults=out.json]
 * </pre>
 * Waits for {@code botCount} bots on {@code -Pport} (4510), lines them up with the built-in AI
 * (the bots from grid slot {@code -Pstart}, default the back), races, prints the classification
 * and writes it as JSON with {@code -Presults=}. Lockstep: the simulation waits for every bot's
 * answer, so a race with the same bots and seed is repeatable and runs as fast as the CPU allows;
 * otherwise it runs in real time, like the game.
 */
public final class BotRace {

    private BotRace() {}

    public static void main(String[] args) throws Exception {
        TrackSpline.selectLayout(TrackLayout.fromSystemProperty());
        int laps = Integer.getInteger("laps", 3);
        int opponents = Integer.getInteger("opponents", 7);
        int botCount = Integer.getInteger("botCount", 1);
        int port = Integer.getInteger("port", BotServer.DEFAULT_PORT);
        boolean lockstep = Boolean.parseBoolean(System.getProperty("lockstep", "true"));
        int controlHz = Integer.getInteger("botHz", 30);
        long seed = Long.getLong("seed", 42L);
        float maxSeconds = Float.parseFloat(System.getProperty("seconds", String.valueOf(200 * laps + 60)));
        String results = System.getProperty("results");
        int cars = opponents + botCount;
        int firstBotSlot = Math.clamp(Integer.getInteger("start", cars - botCount + 1) - 1, 0, cars - botCount);

        BotServer server = new BotServer(port);
        System.out.println("Waiting for " + botCount + " bot(s)...");
        List<BotConnection> conns = server.awaitBots(botCount, Long.getLong("waitMs", 120_000L));
        if (conns.size() < botCount) {
            System.out.println("Only " + conns.size() + " bot(s) connected; giving up.");
            server.close();
            System.exit(2);
        }
        conns = conns.subList(0, botCount);

        NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        DesktopAssetManager assets = new DesktopAssetManager(true);
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        space.setGravity(new Vector3f(0, -9.81f, 0));
        FixedStepPhysics loop = FixedStepPhysics.install(space);
        TrackSpline track = new TrackSpline();
        TrackBuilder.BuiltTrack built = new TrackBuilder(track, assets).build(space);
        RacingLine line = new RacingLine(track);
        RaceDirector director = new RaceDirector(track, line, new PlayerInput());
        CarModelFactory factory = new CarModelFactory(assets);
        Random rnd = new Random(seed);

        List<Car> field = new ArrayList<>();
        Map<Car, AIDriver> ai = new HashMap<>();
        Map<Car, BotConnection> botOf = new HashMap<>();
        Map<PhysicsCollisionObject, Car> bodyToCar = new HashMap<>();
        int aiIndex = 1;
        for (int slot = 0; slot < cars; slot++) {
            boolean isBot = slot >= firstBotSlot && slot < firstBotSlot + botCount;
            VehicleSpec spec = VehicleSpec.forTrack();
            Livery liv = Livery.forIndex(isBot ? slot - firstBotSlot + 20 : aiIndex++);
            BotConnection bc = isBot ? conns.get(slot - firstBotSlot) : null;
            Car car = new Car(isBot ? bc.name() : liv.name(), liv, false, spec, factory, space, built.collider(), built.groundCollider());
            float grip = spec.aiGripBase() + rnd.nextFloat() * com.racingsim.ai.AiParams.get("aiGripSpread", 0.10f);
            SpeedProfile profile = new SpeedProfile(track, line, spec, car.getPhysics().getAeroModel(), grip, 13f);
            ai.put(car, new AIDriver(car, line, profile, rnd.nextFloat()));   // the bot car's fallback driver
            if (isBot) botOf.put(car, bc);
            field.add(car);
            bodyToCar.put(car.getBody(), car);
        }
        director.setField(field, ai);
        Map<Car, ExternalDriver> drivers = new HashMap<>();
        for (Map.Entry<Car, BotConnection> e : botOf.entrySet()) {
            ExternalDriver d = new ExternalDriver(e.getValue(), e.getKey(), controlHz, lockstep);
            director.attachBot(e.getKey(), d);
            drivers.put(e.getKey(), d);
        }
        loop.addListener(director);
        director.startRace(laps, 0);
        // every bot's car runs the same wing: 8 of 10 on the road course, none on the oval
        boolean training = Boolean.getBoolean("training");
        for (Map.Entry<Car, ExternalDriver> e : drivers.entrySet()) {
            Car car = e.getKey();
            if (training) {
                // {"type":"reset","s":1200,"d":0,"speed":40}: the car is put there (s wraps; d is
                // clamped inside the walls), and the lap tracker follows it
                e.getValue().setResetHandler(r -> {
                    float s = TrackSpline.wrapS((float) Json.num(r, "s", 0));
                    // not on top of another car: move on until 40 m behind / 20 m ahead are clear
                    for (int tries = 0; tries < 50; tries++) {
                        boolean clear = true;
                        for (Car o : field) {
                            float ds = TrackSpline.deltaS(s, o.trackS());
                            if (o != car && ds > -20f && ds < 40f) clear = false;
                        }
                        if (clear) break;
                        s = TrackSpline.wrapS(s + 60f);
                    }
                    float d = (float) Math.clamp(Json.num(r, "d", 0), track.minD(s) + 1.5, track.maxD(s) - 1.5);
                    float v = (float) Math.clamp(Json.num(r, "speed", 0), 0, 100);
                    car.placeOnTrack(track, s, d, v);
                    car.updateTrackPosition(track);
                    director.getLapTracker().teleport(director.entryOf(car), s);
                    director.driverOf(car).resetLane();
                });
            }
            director.driverOf(e.getKey()).setDownforceLevel(TrackSpline.isRoadCourse() ? 0.8f : 0.0f);
            e.getValue().welcome(director.carId(e.getKey()), lockstep ? "training" : "race", track);
        }

        // contacts involving a bot's car (episodes: the same pair again within 1 s counts once)
        Map<String, Float> lastPair = new HashMap<>();
        Map<Car, Integer> contacts = new HashMap<>();
        space.addCollisionListener(ev -> {
            Car a = bodyToCar.get(ev.getObjectA()), b = bodyToCar.get(ev.getObjectB());
            if (a == null || b == null || ev.getAppliedImpulse() < 50f) return;
            String key = a.hashCode() < b.hashCode() ? a.hashCode() + ":" + b.hashCode() : b.hashCode() + ":" + a.hashCode();
            float now = director.getLapTracker().getTime();
            Float lp = lastPair.put(key, now);
            if (lp != null && now - lp <= 1f) return;
            for (Car c : new Car[]{a, b}) {
                contacts.merge(c, 1, Integer::sum);
                ExternalDriver d = drivers.get(c);
                Car other = c == a ? b : a;
                if (d != null) d.event("contact", new Json.Out().obj().num("other", director.carId(other))
                        .num("impulse", Math.round(ev.getAppliedImpulse())).endObj());
            }
        });

        float frame = 1f / 60f;
        long wallStart = System.nanoTime();
        for (int i = 0; i * frame < maxSeconds; i++) {
            long frameStart = System.nanoTime();
            space.update(frame, FixedStepPhysics.MAX_SUB_STEPS);
            space.distributeEvents();
            LapTracker lt = director.getLapTracker();
            if (lt.raceTime() > 0f && lt.getOrder().stream().allMatch(LapTracker.Entry::finished)) break;
            if (drivers.values().stream().allMatch(ExternalDriver::isLost) && lt.raceTime() > 0f) {
                System.out.println("Every bot is gone; stopping.");
                break;
            }
            boolean botsDone = true;
            for (Car c : drivers.keySet()) botsDone &= director.entryOf(c).finished();
            if (botsDone && lt.raceTime() > 0f && Boolean.parseBoolean(System.getProperty("stopWhenBotsFinish", "true"))) {
                // the rest of the field is no longer timed against anyone
                break;
            }
            if (!lockstep) {
                long wait = (long) (frame * 1e9) - (System.nanoTime() - frameStart);
                if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            }
            if (i % 600 == 0) {
                StringBuilder sb = new StringBuilder();
                for (Car c : drivers.keySet()) {
                    LapTracker.Entry e = director.entryOf(c);
                    sb.append(String.format("  %s P%d lap %d s=%.0f v=%.0f mph%s", c.getName(), e.position(), e.lap(),
                            c.trackS(), c.speed() * 2.23694f, drivers.get(c).isLost() ? " (lost)" : ""));
                }
                System.out.printf("t=%6.1f%s%n", lt.getTime(), sb);
            }
        }

        // classification
        LapTracker lt = director.getLapTracker();
        Json.Out o = new Json.Out().obj().str("type", "bye").str("track", TrackSpline.layout().key()).num("laps", laps)
                .num("seed", seed).num("wallSeconds", Math.round((System.nanoTime() - wallStart) / 1e7) / 100.0)
                .key("order").arr();
        for (LapTracker.Entry e : lt.getOrder()) {
            Car c = director.carOf(e);
            boolean bot = drivers.containsKey(c);
            System.out.printf("  P%-2d %-12s %s laps %d %s best %s%s%n", e.position(), c.getName(), bot ? "BOT" : "ai ",
                    e.lapsCompleted(), e.finished() ? String.format("FINISHED %.2fs", e.finishTime()) : "",
                    Float.isNaN(e.bestLapTime()) ? "-" : String.format("%.2f", e.bestLapTime()),
                    bot && drivers.get(c).isLost() ? " (bot lost)" : "");
            o.obj().num("carId", director.carId(c)).str("name", c.getName()).bool("bot", bot)
                    .num("position", e.position()).num("laps", e.lapsCompleted()).bool("finished", e.finished())
                    .num("finishTime", e.finishTime()).num("bestLap", e.bestLapTime())
                    .num("distance", e.totalDistance()).num("contacts", contacts.getOrDefault(c, 0))
                    .num("trackLimits", director.trackLimitsCount(c))
                    .bool("lost", bot && drivers.get(c).isLost()).endObj();
        }
        o.endArr().num("recoveries", director.getRecoveries()).endObj();
        String bye = o.toString();
        for (ExternalDriver d : drivers.values()) d.connection().send(bye);
        if (results != null && !results.isBlank()) Files.writeString(Path.of(results), bye);
        Thread.sleep(200);
        server.close();
        System.exit(0);
    }
}
