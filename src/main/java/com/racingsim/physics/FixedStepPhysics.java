package com.racingsim.physics;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.PhysicsTickListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives all custom vehicle dynamics from Bullet's internal fixed-step loop.
 * <p>
 * The physics space is configured with an accuracy of 1/{@value #HZ} s, so Bullet runs as
 * many 240 Hz sub-steps as needed per rendered frame (rendering is decoupled from the
 * simulation rate). {@link #prePhysicsTick} is invoked before every sub-step and forwards
 * the fixed {@code dt} to the registered listeners in order.
 */
public final class FixedStepPhysics implements PhysicsTickListener {

    public static final int HZ = 240;
    public static final float DT = 1f / HZ;
    public static final int MAX_SUB_STEPS = 12;

    /** A system advanced once per fixed physics step. */
    public interface Listener {
        void fixedStep(float dt);
    }

    private final List<Listener> listeners = new ArrayList<>();
    private long tickCount;

    public static FixedStepPhysics install(PhysicsSpace space) {
        space.setAccuracy(DT);
        space.setMaxSubSteps(MAX_SUB_STEPS);
        FixedStepPhysics loop = new FixedStepPhysics();
        space.addTickListener(loop);
        return loop;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public long getTickCount() {
        return tickCount;
    }

    @Override
    public void prePhysicsTick(PhysicsSpace space, float timeStep) {
        for (Listener l : listeners) {
            l.fixedStep(timeStep);
        }
        tickCount++;
    }

    @Override
    public void physicsTick(PhysicsSpace space, float timeStep) {
        // nothing - all forces are applied as impulses before the step
    }
}
