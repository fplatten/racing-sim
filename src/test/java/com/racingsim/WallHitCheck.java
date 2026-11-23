package com.racingsim;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.system.NativeLibraryLoader;
import com.racingsim.physics.FixedStepPhysics;
import com.racingsim.track.TrackBuilder;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.CarModelFactory;
import com.racingsim.vehicle.Livery;
import com.racingsim.vehicle.VehicleSpec;

/**
 * Wall-impact test: fires a car into the outer wall (straight and banked turn) at several speeds,
 * impact angles and body yaws (nose first, sideways, backward) and reports how far it gets
 * thrown: peak height of the center of mass above its resting height, peak speed away from the track surface, peak
 * roll / pitch, and whether it ended up over the wall or upside down.
 * <pre>  ./gradlew wallCheck</pre>
 */
public final class WallHitCheck {

    public static void main(String[] args) {
        NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        DesktopAssetManager assets = new DesktopAssetManager(true);
        PhysicsSpace space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        space.setGravity(new Vector3f(0, -9.81f, 0));
        FixedStepPhysics loop = FixedStepPhysics.install(space);
        TrackSpline track = new TrackSpline();
        TrackBuilder.BuiltTrack built = new TrackBuilder(track, assets).build(space);
        Car car = new Car("TEST", Livery.forIndex(0), true, new VehicleSpec(), new CarModelFactory(assets),
                space, built.collider(), built.groundCollider());
        loop.addListener(dt -> car.getPhysics().step(dt));

        float turn = track.turnStarts()[0];
        float[][] places = {{turn - 400f, 0}, {turn + 180f, 1}};
        float[] speeds = {40f, 70f, 100f};
        float[] angles = {5f, 15f, 30f};
        float[] yaws = {0f, 90f, 180f};
        float worstH = 0f, worstV = 0f;
        int flips = 0, overWall = 0, runs = 0;
        System.out.println("place  v(m/s) angle yaw | maxRise(m) maxVup(m/s) maxRoll maxPitch  end");
        for (float[] place : places) {
            for (float v : speeds) {
                for (float ang : angles) {
                    for (float yaw : yaws) {
                        float[] r = run(space, track, car, place[0], v, ang, yaw);
                        runs++;
                        worstH = Math.max(worstH, r[0]);
                        worstV = Math.max(worstV, r[1]);
                        String end = r[4] > 0 ? "OVER WALL" : r[5] > 0 ? "UPSIDE DOWN" : "ok";
                        if (r[4] > 0) overWall++;
                        if (r[5] > 0) flips++;
                        System.out.printf("%-6s %5.0f %5.0f %4.0f | %8.2f %9.2f %7.0f %7.0f   %s%n",
                                place[1] == 0 ? "str" : "turn", v, ang, yaw, r[0], r[1], r[2], r[3], end);
                    }
                }
            }
        }
        System.out.printf("WALLCHECK runs %d  worst rise %.2f m  worst upward speed %.2f m/s  over wall %d  upside down %d%n",
                runs, worstH, worstV, overWall, flips);
    }

    /** @return {maxRise, maxVy, maxRollDeg, maxPitchDeg, overWall, upsideDown} */
    private static float[] run(PhysicsSpace space, TrackSpline track, Car car, float s, float v, float angDeg, float yawDeg) {
        float d = TrackSpline.ROAD_OUTER - 4.5f;
        car.placeOnTrack(track, s, d, 0f);
        Vector3f n = track.surfaceNormal(s, d, null);
        Quaternion toWall = new Quaternion().fromAngleAxis(-angDeg * FastMath.DEG_TO_RAD, n);   // + = to the right
        Vector3f dir = toWall.mult(track.forward(s, null));
        dir.subtractLocal(n.mult(dir.dot(n))).normalizeLocal();
        Quaternion body = new Quaternion().fromAngleAxis(yawDeg * FastMath.DEG_TO_RAD, n).mult(toWall)
                .mult(car.getBody().getPhysicsRotation());
        car.getBody().setPhysicsRotation(body);
        car.getBody().setLinearVelocity(dir.mult(v));
        car.getPhysics().resetState(yawDeg == 0f ? v : 0f);
        car.getInput().clear();
        car.getInput().throttle = yawDeg == 0f ? 0.3f : 0f;

        TrackSpline.TrackPos tp = new TrackSpline.TrackPos();
        float base = Float.NaN, maxRise = 0, maxVy = 0, maxRoll = 0, maxPitch = 0, over = 0, flip = 0;
        Vector3f up = new Vector3f(), fwd = new Vector3f();
        for (int i = 0; i < 240 * 4; i++) {
            space.update(FixedStepPhysics.DT, 0);
            Vector3f p = car.getBody().getPhysicsLocation();
            track.project(p, tp);
            float h = p.y - track.height(tp.s, Math.min(tp.d, TrackSpline.ROAD_OUTER));
            if (i == 0) base = h;
            maxRise = Math.max(maxRise, h - base);
            Quaternion q = car.getBody().getPhysicsRotation();
            q.mult(Vector3f.UNIT_Y, up);
            q.mult(Vector3f.UNIT_Z, fwd);
            Vector3f sn = track.surfaceNormal(tp.s, Math.min(tp.d, TrackSpline.ROAD_OUTER), null);
            maxVy = Math.max(maxVy, car.getBody().getLinearVelocity().dot(sn));   // away from the track surface
            maxRoll = Math.max(maxRoll, FastMath.acos(FastMath.clamp(up.dot(sn), -1, 1)) * FastMath.RAD_TO_DEG);
            maxPitch = Math.max(maxPitch, Math.abs(FastMath.asin(FastMath.clamp(fwd.dot(sn), -1, 1))) * FastMath.RAD_TO_DEG);
            if (tp.d > TrackSpline.ROAD_OUTER + 0.5f) over = 1;
            if (i == 240 * 4 - 1 && up.dot(sn) < 0f) flip = 1;
        }
        return new float[]{maxRise, maxVy, maxRoll, maxPitch, over, flip};
    }
}
