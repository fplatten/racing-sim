package com.racingsim.vehicle;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.CompoundCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.control.AbstractControl;
import com.racingsim.physics.DraftingSystem;
import com.racingsim.track.TrackSpline;

/**
 * One race car: scene node + dynamic rigid body + {@link VehiclePhysics} + driver input +
 * track-relative position. Used identically for the player and AI opponents.
 */
public final class Car implements DraftingSystem.Participant {

    private final String name;
    private final Livery livery;
    private final boolean player;
    private final Node node;
    private final RigidBodyControl body;
    private final VehicleSpec spec;
    private final VehicleInput input = new VehicleInput();
    private final VehiclePhysics physics;
    private final CarModelFactory.CarModel model;
    private final TrackSpline.TrackPos trackPos = new TrackSpline.TrackPos();
    private final float rideHeight;
    private float lateralRate;

    public Car(String name, Livery livery, boolean player, VehicleSpec spec, CarModelFactory factory,
               PhysicsSpace space, PhysicsCollisionObject... grounds) {
        this.name = name;
        this.livery = livery;
        this.player = player;
        this.spec = spec;
        float staticComp = spec.mass * 9.81f / 4f / spec.springFront;
        this.rideHeight = spec.wheelRadius + spec.restLength - staticComp - spec.mountHeight;

        node = new Node("Car-" + name);
        model = factory.build(spec, livery, rideHeight, player);
        node.attachChild(model.root());

        CompoundCollisionShape shape = new CompoundCollisionShape();
        shape.addChildShape(new BoxCollisionShape(1.0f, 0.25f, 2.5f), new Vector3f(0, 0.12f, -0.03f));
        body = new RigidBodyControl(shape, spec.mass);
        node.addControl(body);
        body.setFriction(0.35f);
        body.setRestitution(0.15f);
        body.setLinearDamping(0f);
        body.setAngularDamping(0.05f);
        body.setEnableSleep(false);
        body.setCcdMotionThreshold(0.25f);
        body.setCcdSweptSphereRadius(0.4f);
        space.add(body);

        physics = new VehiclePhysics(spec, body, space, input, grounds);
        node.addControl(new WheelVisualControl());
    }

    public String getName() { return name; }
    public Livery getLivery() { return livery; }
    public boolean isPlayer() { return player; }
    public Node getNode() { return node; }
    public RigidBodyControl getBody() { return body; }
    public VehicleInput getInput() { return input; }
    public VehiclePhysics getPhysics() { return physics; }
    public VehicleSpec getSpec() { return spec; }
    public float getRideHeight() { return rideHeight; }

    /** Refreshes the track-relative position (call once per physics tick). */
    public void updateTrackPosition(TrackSpline track) {
        track.project(body.getPhysicsLocation(), trackPos);
    }

    /** Refreshes the track position and the filtered lateral velocity (m/s, + = outwards). */
    public void updateTrackPosition(TrackSpline track, float dt) {
        float prevD = trackPos.d;
        updateTrackPosition(track);
        float raw = (trackPos.d - prevD) / dt;
        if (Math.abs(raw) > 30f) raw = 0f;                 // teleport / reset
        lateralRate += (raw - lateralRate) * Math.min(1f, dt / 0.2f);
    }

    /** Filtered rate of change of the lateral offset d (m/s, positive = moving outwards). */
    public float lateralRate() { return lateralRate; }

    @Override public float trackS() { return trackPos.s; }
    @Override public float trackD() { return trackPos.d; }
    @Override public float speed() { return physics.getSpeed(); }
    @Override public float getDraftFactor() { return physics.getDraftFactor(); }
    @Override public void setDraftFactor(float f) { physics.setDraftFactor(f); }

    /** Teleports the car onto the track at (s, d) moving forward at {@code speed}. */
    public void placeOnTrack(TrackSpline track, float s, float d, float speed) {
        Vector3f normal = track.surfaceNormal(s, d, null);
        Vector3f fwd = track.forward(s, null);
        Vector3f p = track.point(s, d, null).addLocal(normal.mult(rideHeight + 0.03f));
        Quaternion q = new Quaternion();
        q.lookAt(fwd, normal);
        body.setPhysicsLocation(p);
        body.setPhysicsRotation(q);
        body.setLinearVelocity(fwd.mult(speed));
        body.setAngularVelocity(Vector3f.ZERO);
        physics.resetState(speed);
        physics.setDraftFactor(0f);
        input.clear();
        trackPos.s = TrackSpline.wrapS(s);
        trackPos.d = d;
        lateralRate = 0f;
    }

    /** Moves wheels (steer, spin, suspension travel) to match the physics state each frame. */
    private final class WheelVisualControl extends AbstractControl {
        private final Quaternion qSteer = new Quaternion();
        private final Quaternion qSpin = new Quaternion();

        @Override
        protected void controlUpdate(float tpf) {
            VehiclePhysics.Wheel[] wheels = physics.getWheels();
            for (int i = 0; i < 4; i++) {
                VehiclePhysics.Wheel w = wheels[i];
                Node pivot = model.wheelPivots()[i];
                pivot.setLocalTranslation(w.mountLocal.x, w.mountLocal.y - w.suspensionLength, w.mountLocal.z);
                qSteer.fromAngleAxis(w.steer, Vector3f.UNIT_Y);
                pivot.setLocalRotation(qSteer);
                qSpin.fromAngleAxis(w.spinAngle % FastMath.TWO_PI, Vector3f.UNIT_X);
                model.wheelSpinners()[i].setLocalRotation(qSpin);
            }
        }

        @Override
        protected void controlRender(RenderManager rm, ViewPort vp) { }
    }
}
