package com.racingsim;

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
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.CarModelFactory;
import com.racingsim.vehicle.Livery;
import com.racingsim.vehicle.VehiclePhysics;
import com.racingsim.vehicle.VehicleSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Runs the full race simulation (track collider, 8 cars, drafting, 240 Hz vehicle physics)
 * without a window, starting from the real grid via {@link RaceDirector#startRace(int)}.
 * <pre>
 *   ./gradlew simCheck -Pseconds=150 -Pplayer=passive|erratic|ai -Plaps=25 [-Pverbose=verbose]
 * </pre>
 * Player modes: {@code ai} = the player's car is AI driven, {@code passive} = no input at all
 * (coasts to a stop from the rolling start), {@code erratic} = a human-like driver that keeps
 * lifting and braking at random. AI-into-player contacts are counted from Bullet collisions.
 */
public final class HeadlessSimCheck {

    public static void main(String[] args) {
        float seconds = args.length > 0 ? Float.parseFloat(args[0]) : 150f;
        boolean verbose = args.length > 1 && args[1].equals("verbose");
        int carCount = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        String playerMode = args.length > 3 ? args[3] : "ai";
        int laps = args.length > 4 ? Integer.parseInt(args[4]) : 25;
        int stops = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        float downforce = args.length > 6 ? Float.parseFloat(args[6]) : 0f;   // player car wing level 0..1

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
        Random rnd = new Random(42);

        int playerSlot = Math.min(5, carCount - 1);
        boolean humanPlayer = !playerMode.equals("ai");
        Car player = null;
        AIDriver playerPilot = null;
        Map<PhysicsCollisionObject, Car> bodyToCar = new HashMap<>();
        for (int slot = 0; slot < carCount; slot++) {
            VehicleSpec spec = new VehicleSpec();
            Livery liv = Livery.forIndex(slot);
            boolean isPlayer = slot == playerSlot && humanPlayer;
            Car car = new Car(isPlayer ? "YOU" : liv.name(), liv, isPlayer, spec, factory, space, built.collider(), built.groundCollider());
            if (slot == playerSlot) car.getPhysics().getAeroModel().setDownforceLevel(downforce);
            float grip = 1.22f + rnd.nextFloat() * 0.10f;
            SpeedProfile profile = new SpeedProfile(track, line, spec, car.getPhysics().getAeroModel(), grip, 13f);
            AIDriver ai = new AIDriver(car, line, profile, 0.3f + rnd.nextFloat() * 0.7f);
            director.addCar(car, isPlayer ? null : ai);
            if (isPlayer) playerPilot = ai;
            if (slot == playerSlot) player = car;
            bodyToCar.put(car.getBody(), car);
        }
        loop.addListener(director);

        if (playerMode.equals("erratic")) {
            AIDriver pilot = playerPilot;
            Random r = new Random(7);
            float[] phase = {0f, 3f};   // time in phase, phase length
            int[] mode = {0};           // 0 = race, 1 = lift, 2 = brake hard
            director.setPlayerOverride((car, dt) -> {
                pilot.update(dt, director.getCars());
                phase[0] += dt;
                if (phase[0] > phase[1]) {
                    phase[0] = 0f;
                    mode[0] = r.nextInt(10) < 6 ? 0 : (r.nextBoolean() ? 1 : 2);
                    phase[1] = mode[0] == 0 ? 2f + r.nextFloat() * 5f : 0.8f + r.nextFloat() * 1.5f;
                }
                if (mode[0] == 1) { car.getInput().throttle = 0f; car.getInput().brake = 0f; }
                if (mode[0] == 2) { car.getInput().throttle = 0f; car.getInput().brake = 0.9f; }
            });
        } else if (playerMode.equals("reset")) {
            // races normally but presses R (reset to the start/finish line) every 23 s
            AIDriver pilot = playerPilot;
            float[] clock = {0f};
            director.setPlayerOverride((car, dt) -> {
                clock[0] += dt;
                if (clock[0] > 23f) {
                    clock[0] = 0f;
                    director.requestPlayerReset();
                }
                pilot.update(dt, director.getCars());
                if (car.speed() < 30f && clock[0] < 2f) {   // a human pulls away gently after a reset
                    car.getInput().throttle = Math.min(car.getInput().throttle, 0.6f);
                }
            });
        } else if (playerMode.equals("pitplayer")) {
            // the player's car is driven by an AI pilot that also makes the mandatory stops in box 17
            AIDriver pilot = playerPilot;
            director.setPlayerOverride((car, dt) -> {
                LapTracker.Entry e = director.entryOf(car);
                float rel = com.racingsim.track.PitLane.rel(car.trackS());
                if (pilot.getPit() == null) pilot.setPit(director.pitOf(car));
                if (!pilot.isPitting() && !director.getLapTracker().stopsMet(e)
                        && rel > AIDriver.PIT_COMMIT_REL - 60f && rel < AIDriver.PIT_COMMIT_REL
                        && director.getLapTracker().raceTime() > 0f) {
                    pilot.requestPit();
                }
                pilot.update(dt, director.getCars());
            });
        } else if (playerMode.equals("passive")) {
            director.setPlayerOverride((car, dt) -> car.getInput().clear());
        }

        director.startRace(laps, stops);
        if (playerMode.equals("exitlane")) {
            // pit exit lane test: coasting along the separate lane through turn 1
            director.setPlayerOverride((car, dt) -> car.getInput().clear());
            player.placeOnTrack(track, 700f, -21.5f, 22f);
        }
        if (playerMode.equals("pit")) {
            // pit-lane test: rolling down the fast lane from the pit entry with no input
            director.setPlayerOverride((car, dt) -> car.getInput().clear());
            player.placeOnTrack(track, TrackSpline.wrapS(-430f), -22f, 25f);
        }
        if (playerMode.equals("grass")) {
            // off-track test: no input, dropped on the infield grass, later launched past the wall
            director.setPlayerOverride((car, dt) -> car.getInput().clear());
            player.placeOnTrack(track, 700f, -80f, 40f);
        }

        // contact episodes: (AI car, player) pairs separated by more than 1 s
        Car playerCar = player;
        Map<Car, Float> lastContact = new HashMap<>();
        int[] playerContacts = {0};
        int[] anyContacts = {0};
        Map<String, Float> lastPair = new HashMap<>();
        space.addCollisionListener(event -> {
            Car a = bodyToCar.get(event.getObjectA()), b = bodyToCar.get(event.getObjectB());
            if (a == null || b == null || event.getAppliedImpulse() < 50f) return;
            String key = a.getName().compareTo(b.getName()) < 0 ? a.getName() + b.getName() : b.getName() + a.getName();
            float tNow = director.getLapTracker().getTime();
            Float lp = lastPair.get(key);
            if (lp == null || tNow - lp > 1f) {
                anyContacts[0]++;
                if (verbose) {
                    float ds = TrackSpline.deltaS(a.trackS(), b.trackS());
                    System.out.printf("  CARCONTACT t=%.2f %s(s=%.0f d=%.2f v=%.0f) x %s(ds=%+.1f d=%.2f v=%.0f) impulse=%.0f%n",
                            tNow, a.getName(), a.trackS(), a.trackD(), a.speed(), b.getName(), ds, b.trackD(), b.speed(),
                            event.getAppliedImpulse());
                }
            }
            lastPair.put(key, tNow);
            Car other = a == playerCar ? b : b == playerCar ? a : null;
            if (other == null) return;
            float now = director.getLapTracker().getTime();
            Float last = lastContact.get(other);
            if (last == null || now - last > 1f) {
                playerContacts[0]++;
                float ds = TrackSpline.deltaS(playerCar.trackS(), other.trackS());
                System.out.printf("  CONTACT t=%.2f %s -> player  s=%.0f  player d=%.2f v=%.0f | other ds=%.1f d=%.2f v=%.0f  impulse=%.0f%n",
                        now, other.getName(), playerCar.trackS(), playerCar.trackD(), playerCar.speed(),
                        ds, other.trackD(), other.speed(), event.getAppliedImpulse());
            }
            lastContact.put(other, now);
        });

        float frame = 1f / 60f;
        int frames = (int) (seconds / frame);
        float maxSpeed = 0f, maxSqueal = 0f;
        int squealFrames = 0;
        Map<Car, Boolean> incident = new HashMap<>();
        Map<Car, com.racingsim.track.PitStop.Status> pitStatus = new HashMap<>();
        for (int i = 0; i < frames; i++) {
            space.update(frame, FixedStepPhysics.MAX_SUB_STEPS);
            space.distributeEvents();
            if (verbose) {
                for (Car c : director.getCars()) {
                    VehiclePhysics cp = c.getPhysics();
                    boolean spinning = cp.getRearSlipAngle() > 0.2f && cp.getSpeed() > 15f;
                    Boolean was = incident.get(c);
                    if (spinning && (was == null || !was)) {
                        System.out.printf("  INCIDENT t=%.2f %s s=%.0f d=%.2f v=%.0f%n",
                                director.getLapTracker().getTime(), c.getName(), c.trackS(), c.trackD(), c.speed());
                    }
                    incident.put(c, spinning);
                }
            }
            String traceCar = System.getenv("TRACE_CAR");
            if (traceCar != null && i % 3 == 0) {
                float tt = director.getLapTracker().getTime();
                float t0 = Float.parseFloat(System.getenv().getOrDefault("TRACE", "0"));
                for (Car c : director.getCars()) {
                    if (!c.getName().equals(traceCar) || tt < t0 || tt > t0 + 6f) continue;
                    Vector3f pos = c.getBody().getPhysicsLocation();
                    Vector3f upv = c.getBody().getPhysicsRotation().mult(Vector3f.UNIT_Y);
                    StringBuilder near = new StringBuilder();
                    for (Car o : director.getCars()) {
                        float ds = TrackSpline.deltaS(c.trackS(), o.trackS());
                        if (o != c && Math.abs(ds) < 12f) near.append(String.format(" %s(%+.1f,%+.1f)", o.getName(), ds, o.trackD() - c.trackD()));
                    }
                    System.out.printf("   TC t=%.2f s=%.1f d=%.2f y=%.2f up.y=%.2f v=%.1f thr=%.2f brk=%.2f steer=%.2f aR=%.3f near:%s%n",
                            tt, c.trackS(), c.trackD(), pos.y, upv.y, c.speed(), c.getInput().throttle, c.getInput().brake,
                            c.getInput().steer, c.getPhysics().getRearSlipAngle(), near);
                }
            }
            for (Car c : director.getCars()) {
                com.racingsim.track.PitStop ps = director.pitOf(c);
                if (ps.status() != pitStatus.get(c)) {
                    if (pitStatus.containsKey(c) || ps.status() != com.racingsim.track.PitStop.Status.NONE) {
                        System.out.printf("  PITSTOP t=%.2f %-9s box %2d %s rel=%.1f d=%.2f stops=%d%n",
                                director.getLapTracker().getTime(), c.getName(), ps.stall() + 1, ps.status(),
                                com.racingsim.track.PitLane.rel(c.trackS()), c.trackD(), director.entryOf(c).pitStops());
                    }
                    pitStatus.put(c, ps.status());
                }
            }
            VehiclePhysics p = player.getPhysics();
            maxSpeed = Math.max(maxSpeed, p.getSpeed());
            maxSqueal = Math.max(maxSqueal, p.getSqueal());
            if (p.getSqueal() > 0.2f) squealFrames++;
            if ((playerMode.equals("pit") || playerMode.equals("exitlane")) && i % 30 == 0) {
                Vector3f pos = player.getBody().getPhysicsLocation();
                int contacts = 0, asphalt = 0;
                for (VehiclePhysics.Wheel w : p.getWheels()) {
                    if (w.contact) contacts++;
                    if (w.contact && w.onTrack) asphalt++;
                }
                System.out.printf("  PIT t=%5.1f rel=%7.1f d=%6.2f y=%5.2f v=%5.1f wheelsDown=%d onPitRoad=%d recoveries=%d%n",
                        director.getLapTracker().getTime(), com.racingsim.track.PitLane.rel(player.trackS()), player.trackD(),
                        pos.y, player.speed(), contacts, asphalt, director.getRecoveries());
            }
            if (playerMode.equals("grass")) {
                float t = director.getLapTracker().getTime();
                if (i == 60 * 12) {
                    // beyond the outer wall at speed, heading outwards
                    Vector3f out = track.right(1000f, null);
                    player.placeOnTrack(track, 1000f, 30f, 0f);
                    player.getBody().setLinearVelocity(out.mult(50f));
                }
                if (i % 30 == 0) {
                    Vector3f pos = player.getBody().getPhysicsLocation();
                    int contacts = 0;
                    boolean grass = false;
                    for (VehiclePhysics.Wheel w : p.getWheels()) {
                        if (w.contact) contacts++;
                        if (w.contact && !w.onTrack) grass = true;
                    }
                    System.out.printf("  GRASS t=%5.1f y=%6.2f d=%6.1f v=%5.1f wheelsDown=%d onGrass=%b%n",
                            t, pos.y, player.trackD(), player.speed(), contacts, grass);
                }
            }
            if (i % 300 == 0) {
                LapTracker.Entry e = director.entryOf(player);
                System.out.printf("t=%6.1f s=%7.1f d=%5.2f v=%6.1fmph P%d lap %d/%d race=%.1f rec=%d%n",
                        director.getLapTracker().getTime(), player.trackS(), player.trackD(), p.getSpeed() * 2.23694f,
                        e.position(), e.lap(), laps, director.getLapTracker().raceTime(), director.getRecoveries());
            }
        }
        System.out.printf("Player mode %s: AI->player contacts %d (all car-car contacts %d), recoveries %d, max player speed %.1f mph%n",
                playerMode, playerContacts[0], anyContacts[0], director.getRecoveries(), maxSpeed * 2.23694f);
        System.out.printf("Tire squeal: max %.2f, audible (>0.2) for %.1f s%n", maxSqueal, squealFrames / 60f);
        for (LapTracker.Entry e : director.getLapTracker().getOrder()) {
            System.out.printf("  P%d %-9s laps %d pits %d %s best %s%n", e.position(), e.name, e.lapsCompleted(), e.pitStops(),
                    e.finished() ? String.format("FINISHED %.2fs", e.finishTime()) : "",
                    Float.isNaN(e.bestLapTime()) ? "-" : String.format("%.2f", e.bestLapTime()));
        }
    }
}
