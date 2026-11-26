package com.racingsim;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.system.NativeLibraryLoader;
import com.racingsim.physics.FixedStepPhysics;
import com.racingsim.track.TrackBuilder;
import com.racingsim.track.TrackLayout;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.CarModelFactory;
import com.racingsim.vehicle.Livery;
import com.racingsim.vehicle.VehiclePhysics;
import com.racingsim.vehicle.VehicleSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Car performance check: one car alone on the oval's front straight (flat, 1 km), driven by script
 * at minimum and at maximum wing: a standing start at full throttle (0-60 and 0-100 mph), a stop
 * from 200 mph on the brakes (distance, peak and average g), and full throttle from 200 and from
 * 225 mph for 950 m (the top speed lies between where the two end up). The scripted driver feeds
 * the throttle in against wheelspin and keeps the brakes just short of locking.
 * <pre>  ./gradlew carCheck [-Pcar=indy|f1]</pre>
 * The car is the oval's (the Indy car) unless {@code -Pcar=} picks one; the Unreal version's
 * {@code -CarCheck} prints the same lines.
 */
public final class CarCheck {

    private static final float MPH = 0.44704f;
    /** Kind: 0 launch, 1 brake, 2 flat out. */
    private record Test(float wing, int kind, float v0) { }

    public static void main(String[] args) {
        TrackSpline.selectLayout(TrackLayout.OVAL);
        NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        DesktopAssetManager assets = new DesktopAssetManager(true);
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        space.setGravity(new Vector3f(0, -9.81f, 0));
        FixedStepPhysics loop = FixedStepPhysics.install(space);
        TrackSpline track = new TrackSpline();
        TrackBuilder.BuiltTrack built = new TrackBuilder(track, assets).build(space);
        VehicleSpec spec = VehicleSpec.forTrack();
        Car car = new Car("TEST", Livery.forIndex(0), true, spec, new CarModelFactory(assets),
                space, built.collider(), built.groundCollider());
        System.out.println("CARCHECK car: " + spec.type + " (" + spec.mass + " kg, final drive " + spec.finalDrive + ")");

        Test[] tests = {
                new Test(0f, 0, 0f), new Test(0f, 1, 200f * MPH), new Test(0f, 2, 200f * MPH), new Test(0f, 2, 225f * MPH),
                new Test(1f, 0, 0f), new Test(1f, 1, 200f * MPH), new Test(1f, 2, 200f * MPH), new Test(1f, 2, 225f * MPH)};
        float startS = TrackSpline.wrapS(-495f);
        boolean trace = System.getProperty("carTrace") != null;   // -PcarTrace: speed, gear and pedals every 0.2 s
        // the scripted driver's pedals (integral control on the slip)
        float[] pedals = new float[2];
        float[] wanted = new float[2];
        loop.addListener(dt -> {
            VehiclePhysics p = car.getPhysics();
            float lock = 0f;
            for (VehiclePhysics.Wheel w : p.getWheels()) lock = Math.min(lock, w.slipRatio);
            // hold the slip just under the tires' peak (about 10%)
            pedals[0] = FastMath.clamp(pedals[0] + (0.08f - p.getRearSlipRatio()) * 40f * dt, 0.15f, Math.max(wanted[0], 0.15f));
            pedals[1] = FastMath.clamp(pedals[1] + (lock + 0.085f) * 60f * dt, 0.05f, Math.max(wanted[1], 0.05f));
            car.getInput().throttle = wanted[0] > 0f ? pedals[0] : 0f;
            car.getInput().brake = wanted[1] > 0f ? pedals[1] : 0f;
            car.getInput().steer = 0f;
            p.step(dt);
        });

        List<String> results = new ArrayList<>();
        for (int run = 0; run < tests.length; run++) {
            Test t = tests[run];
            car.placeOnTrack(track, startS, 0f, t.v0());
            car.getPhysics().getAeroModel().setDownforceLevel(t.wing());
            wanted[0] = t.kind() == 1 ? 0f : 1f;
            wanted[1] = t.kind() == 1 ? 1f : 0f;
            pedals[0] = pedals[1] = 0f;
            float time = 0f, t60 = Float.NaN, t100 = Float.NaN, peakG = 0f;
            String wingName = t.wing() > 0.5f ? "max wing" : "min wing";
            String line = null;
            float nextTrace = 0f;
            while (line == null) {
                space.update(FixedStepPhysics.DT, 0);
                time += FixedStepPhysics.DT;
                car.updateTrackPosition(track);
                VehiclePhysics p = car.getPhysics();
                float dist = TrackSpline.deltaS(startS, car.trackS());
                float v = p.getForwardSpeed();
                if (trace && time >= nextTrace) {
                    nextTrace = time + 0.2f;
                    System.out.printf("CARTRACE run %d t=%.2f v=%.1f mph gear %d rpm %.0f thr %.2f brk %.2f slip %.3f longG %.2f%n", run, time,
                            v / MPH, p.getGearbox().getGear(), p.getEngineRpm(), car.getInput().throttle, car.getInput().brake,
                            p.getRearSlipRatio(), p.getLongitudinalG());
                }
                if (t.kind() == 0) {
                    if (Float.isNaN(t60) && v >= 60f * MPH) t60 = time;
                    if (Float.isNaN(t100) && v >= 100f * MPH) t100 = time;
                    if (!Float.isNaN(t100) || time > 15f) {
                        line = String.format("%s: 0-60 mph %.2f s, 0-100 mph %.2f s (%.0f m)", wingName, t60, t100, dist);
                    }
                } else if (t.kind() == 1) {
                    peakG = Math.min(peakG, p.getLongitudinalG());
                    if (v < 0.5f || time > 15f) {
                        line = String.format("%s: 200-0 mph in %.1f m, %.2f s, peak %.1f g, average %.1f g", wingName, dist, time,
                                -peakG, 200f * MPH / Math.max(time, 0.01f) / 9.81f);
                    }
                } else if (dist > 950f || time > 20f) {
                    line = String.format("%s: flat out from %.0f mph -> %.1f mph after %.0f m (gear %d, %.0f rpm)", wingName,
                            t.v0() / MPH, v / MPH, dist, p.getGearbox().getGear(), p.getEngineRpm());
                }
            }
            System.out.println("CARCHECK " + line);
            results.add(line);
        }
        for (String l : results) System.out.println("CARCHECK summary  " + l);
    }
}
