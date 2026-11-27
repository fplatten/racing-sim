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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * At the end it prints the AI's race craft ({@link AIDriver.Stats}): time off the racing line
 * (and why), time three wide, passes by driver type, lead changes and what holds cars back.
 * Tuning overrides are passed on as system properties: {@code -PaiSling=2 -PpowerScale=1.1 ...}
 * (see build.gradle.kts).
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

        // the track: -Ptrack=road races on the road course (a standing start)
        TrackSpline.selectLayout(com.racingsim.track.TrackLayout.fromSystemProperty());
        System.out.println("Track: " + TrackSpline.layout().title() + ", car: " + VehicleSpec.selectedType());
        TrackSpline track = new TrackSpline();
        TrackBuilder.BuiltTrack built = new TrackBuilder(track, assets).build(space);
        RacingLine line = new RacingLine(track);
        RaceDirector director = new RaceDirector(track, line, new PlayerInput());
        CarModelFactory factory = new CarModelFactory(assets);
        // -Pseed=N: another mix of drivers (grip, type) on the same grid; -Presults=<file.json>
        // writes the outcome as JSON for the trainer (tools/train/tune_ai.py)
        Random rnd = new Random(Long.getLong("seed", 42L));
        AIDriver.stats = new AIDriver.Stats();
        // tuning: -PaiGrip=<lowest planned grip> -PaiGripSpread=<range above it>
        float gripBase = VehicleSpec.forTrack().aiGripBase(), gripSpread = com.racingsim.ai.AiParams.get("aiGripSpread", 0.10f);

        int playerSlot = Math.min(5, carCount - 1);
        boolean humanPlayer = !playerMode.equals("ai");
        Car player = null;
        AIDriver playerPilot = null;
        Map<PhysicsCollisionObject, Car> bodyToCar = new HashMap<>();
        for (int slot = 0; slot < carCount; slot++) {
            VehicleSpec spec = VehicleSpec.forTrack();
            Livery liv = Livery.forIndex(slot);
            boolean isPlayer = slot == playerSlot && humanPlayer;
            Car car = new Car(isPlayer ? "YOU" : liv.name(), liv, isPlayer, spec, factory, space, built.collider(), built.groundCollider());
            if (slot == playerSlot) car.getPhysics().getAeroModel().setDownforceLevel(downforce);
            float grip = gripBase + rnd.nextFloat() * gripSpread;
            SpeedProfile profile = new SpeedProfile(track, line, spec, car.getPhysics().getAeroModel(), grip, 13f);
            AIDriver ai = new AIDriver(car, line, profile, rnd.nextFloat());   // driver type: 0 rookie .. 1 veteran
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

        // racing at the front: lead changes and changes in the top 3, once the start has settled
        List<String> lastTop3 = new ArrayList<>(), pendingTop3 = new ArrayList<>();
        float pendingSince = 0f, nextFrontLog = 0f;
        int leadChanges = 0, top3Changes = 0;

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
            LapTracker laps3 = director.getLapTracker();
            List<LapTracker.Entry> st = laps3.getOrder();
            float now = laps3.getTime();
            // the gaps at the front
            if (st.size() >= 3 && now >= nextFrontLog) {
                nextFrontLog = now + (laps3.raceTime() < 100f ? 5f : 15f);
                System.out.printf("front t=%.0f  P1 %s  %.0f m  P2 %s  %.0f m  P3 %s%n", now,
                        st.get(0).name, st.get(0).totalDistance() - st.get(1).totalDistance(), st.get(1).name,
                        st.get(1).totalDistance() - st.get(2).totalDistance(), st.get(2).name);
            }
            if (laps3.raceTime() > 100f && st.size() >= 3) {
                // a change counts once the new order has held for 3 s (cars side by side swap back and forth)
                List<String> top3 = new ArrayList<>();
                for (int k = 0; k < 3; k++) top3.add(st.get(k).finished() ? "" : st.get(k).name);
                if (!top3.contains("")) {
                    if (!top3.equals(pendingTop3)) {
                        pendingTop3 = top3;
                        pendingSince = now;
                    } else if (now - pendingSince >= 3f && !top3.equals(lastTop3)) {
                        if (lastTop3.size() == 3) {
                            if (!top3.get(0).equals(lastTop3.get(0))) leadChanges++;
                            top3Changes++;
                        }
                        lastTop3 = top3;
                    }
                }
            }
            // with -Presults=: stop once the whole field has finished (the trainer's races)
            if (System.getProperty("results") != null && laps3.raceTime() > 0f && st.stream().allMatch(LapTracker.Entry::finished)) break;
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
        float bestSum = 0f, fastest = Float.MAX_VALUE;
        int bestCount = 0;
        for (LapTracker.Entry e : director.getLapTracker().getOrder()) {
            System.out.printf("  P%d %-9s laps %d pits %d %s best %s%n", e.position(), e.name, e.lapsCompleted(), e.pitStops(),
                    e.finished() ? String.format("FINISHED %.2fs", e.finishTime()) : "",
                    Float.isNaN(e.bestLapTime()) ? "-" : String.format("%.2f", e.bestLapTime()));
            if (!Float.isNaN(e.bestLapTime())) {
                bestSum += e.bestLapTime();
                bestCount++;
                fastest = Math.min(fastest, e.bestLapTime());
            }
        }
        AIDriver.Stats a = AIDriver.stats;
        double ticks = Math.max(a.racingTicks, 1);
        System.out.printf("AI pace: fastest lap %s, average best lap %s, top speed with no tow %.1f mph%n",
                bestCount > 0 ? String.format("%.2f", fastest) : "-", bestCount > 0 ? String.format("%.2f", bestSum / bestCount) : "-",
                a.cleanAirTop * 2.23694f);
        System.out.printf("AI racecraft: off line %.1f%%, 3-wide %.2f%% | passes started %d (%d from the draft), completed %d (%d from the draft), aborted %d | emergency moves %d%n",
                100.0 * a.offLineTicks / ticks, 100.0 * a.threeWideTicks / ticks, a.passStarts, a.draftPassStarts, a.passesDone,
                a.draftPassesDone, a.passesAborted, a.emergencyMoves);
        double stT = Math.max(a.straightTicks, 1);
        System.out.printf("AI held back on the straights: following %.1f%%, setting up a run %.1f%%, draft run %.1f%%, backing out %.1f%%, squeezed %.1f%%%n",
                100.0 * a.straightCapped[0] / stT, 100.0 * a.straightCapped[1] / stT, 100.0 * a.straightCapped[2] / stT,
                100.0 * a.straightCapped[3] / stT, 100.0 * a.straightCapped[4] / stT);
        double tuT = Math.max(a.turnTicks, 1);
        System.out.printf("AI held back in the turns: following %.1f%%, setting up a run %.1f%%, draft run %.1f%%, backing out %.1f%%, squeezed %.1f%%%n",
                100.0 * a.turnCapped[0] / tuT, 100.0 * a.turnCapped[1] / tuT, 100.0 * a.turnCapped[2] / tuT,
                100.0 * a.turnCapped[3] / tuT, 100.0 * a.turnCapped[4] / tuT);
        System.out.printf("AI passes completed: rookies %d (%d finished inside a turn), regulars %d (%d), veterans %d (%d)%n",
                a.passesByType[0], a.tightPassesByType[0], a.passesByType[1], a.tightPassesByType[1], a.passesByType[2], a.tightPassesByType[2]);
        double hz = FixedStepPhysics.HZ;
        System.out.printf("AI hard braking in the turns (car-seconds): speed profile %.1f, following %.1f, draft run %.1f, backing out %.1f, squeezed %.1f%n",
                a.turnBrakeTicks[0] / hz, a.turnBrakeTicks[1] / hz, a.turnBrakeTicks[3] / hz, a.turnBrakeTicks[4] / hz, a.turnBrakeTicks[5] / hz);
        System.out.printf("Racing at the front after 100 s: %d lead changes, %d top-3 changes%n", leadChanges, top3Changes);
        System.out.printf("AI 3-wide after 100 s: %.2f%%%n", 100.0 * a.lateThreeWide / Math.max(a.lateTicks, 1));
        double off = Math.max(a.offLineTicks, 1);
        System.out.printf("AI off-line causes: passing %.0f%%, emergency %.0f%%, avoiding %.0f%%, waiting to merge %.0f%%, returning %.0f%%, steering error %.0f%%%n",
                100.0 * a.offPassing / off, 100.0 * a.offEmergency / off, 100.0 * a.offAvoiding / off, 100.0 * a.offHolding / off,
                100.0 * a.offReturning / off, 100.0 * a.offTracking / off);
        System.out.printf("AI passing: closing speed at pull-out %.1f m/s; abandoned: not gaining %d, too late for the turn %d, yielded on the outside %d, lane closed %d%n",
                a.passStarts > 0 ? a.closingAtStart / a.passStarts : 0.0, a.abortStall, a.abortLate, a.abortYield, a.abortBlocked);
        if (TrackSpline.isRoadCourse()) {
            System.out.printf("AI late braking down the inside: %d tried, %d made the pass%n", a.outbrakeStarts, a.outbrakesDone);
        }
        StringBuilder err = new StringBuilder();
        for (int i = 0; i < a.trackErrCount.length; i++) {
            if (a.trackErrCount[i] > 0) err.append(String.format(" %d:%+.1f", i * 50, a.trackErrSum[i] / a.trackErrCount[i]));
        }
        System.out.println("AI line error by s:" + err);

        String results = System.getProperty("results");
        if (results != null && !results.isBlank()) {
            StringBuilder j = new StringBuilder("{");
            j.append(String.format(java.util.Locale.ROOT,
                    "\"track\":\"%s\",\"cars\":%d,\"laps\":%d,\"seconds\":%.1f,\"raceTime\":%.2f,\"contacts\":%d,\"playerContacts\":%d,"
                    + "\"recoveries\":%d,\"passStarts\":%d,\"passesDone\":%d,\"passesAborted\":%d,\"abortStall\":%d,\"abortLate\":%d,"
                    + "\"abortYield\":%d,\"abortBlocked\":%d,\"outbrakeStarts\":%d,\"outbrakesDone\":%d,\"emergencyMoves\":%d,"
                    + "\"offLinePct\":%.3f,\"threeWidePct\":%.3f,\"fastestLap\":%s,\"avgBestLap\":%s,\"cleanAirTopMph\":%.1f,\"order\":[",
                    TrackSpline.layout().key(), carCount, laps, seconds, director.getLapTracker().raceTime(), anyContacts[0],
                    playerContacts[0], director.getRecoveries(), a.passStarts, a.passesDone, a.passesAborted, a.abortStall,
                    a.abortLate, a.abortYield, a.abortBlocked, a.outbrakeStarts, a.outbrakesDone, a.emergencyMoves,
                    100.0 * a.offLineTicks / ticks, 100.0 * a.threeWideTicks / ticks,
                    bestCount > 0 ? String.format(java.util.Locale.ROOT, "%.3f", fastest) : "null",
                    bestCount > 0 ? String.format(java.util.Locale.ROOT, "%.3f", bestSum / bestCount) : "null",
                    a.cleanAirTop * 2.23694f));
            boolean first = true;
            for (LapTracker.Entry e : director.getLapTracker().getOrder()) {
                if (!first) j.append(',');
                first = false;
                j.append(String.format(java.util.Locale.ROOT,
                        "{\"name\":\"%s\",\"laps\":%d,\"finished\":%b,\"finishTime\":%s,\"best\":%s,\"distance\":%.1f}",
                        e.name, e.lapsCompleted(), e.finished(),
                        e.finished() ? String.format(java.util.Locale.ROOT, "%.3f", e.finishTime()) : "null",
                        Float.isNaN(e.bestLapTime()) ? "null" : String.format(java.util.Locale.ROOT, "%.3f", e.bestLapTime()),
                        e.totalDistance()));
            }
            j.append("]}");
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(results), j.toString());
            } catch (java.io.IOException ex) {
                System.err.println("cannot write " + results + ": " + ex.getMessage());
            }
        }
    }
}
