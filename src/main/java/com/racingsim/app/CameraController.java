package com.racingsim.app;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

/**
 * Camera that follows the player's car. Added to the car node after its
 * RigidBodyControl so it reads the freshly interpolated transform each frame.
 * <ul>
 *   <li>CHASE - smoothed third-person camera behind and above the car.</li>
 *   <li>T_CAM - locked to the chassis above the driver's head, with pitch and roll
 *       attenuated so the horizon stays readable.</li>
 * </ul>
 */
public final class CameraController extends AbstractControl {

    public enum Mode { CHASE, T_CAM, SHOWCASE }

    private final Camera cam;
    private Mode mode = Mode.CHASE;

    private final Vector3f smoothedOffset = new Vector3f(0, 2.6f, -8.5f);
    private boolean snap = true;

    private static final Vector3f T_CAM_POS = new Vector3f(0, 0.95f, -0.45f);
    /** Slight downward tilt so the nose and front wheels stay in view. */
    private static final Quaternion T_CAM_TILT = new Quaternion().fromAngleAxis(4f * FastMath.DEG_TO_RAD, Vector3f.UNIT_X);
    private static final float T_CAM_PITCH_ROLL = 0.45f;

    public CameraController(Camera cam) {
        this.cam = cam;
        applyFrustum();
    }

    public Mode getMode() { return mode; }

    private float showcaseDistance = 7.5f, showcaseSide = -1.2f, showcaseHeight = 1.0f;

    /** Screenshot helper: frames the car from the front (distance ahead, lateral offset, height). */
    public void setShowcase(float distance, float side, float height) {
        showcaseDistance = distance;
        showcaseSide = side;
        showcaseHeight = height;
        mode = Mode.SHOWCASE;
        cam.setFrustumPerspective(50f, (float) cam.getWidth() / cam.getHeight(), 0.3f, 4000f);
    }

    public void toggle() {
        mode = mode == Mode.CHASE ? Mode.T_CAM : Mode.CHASE;
        snap = true;
        applyFrustum();
    }

    /** Forces the chase camera to jump to its target (after teleports). */
    public void snap() { snap = true; }

    private void applyFrustum() {
        float aspect = (float) cam.getWidth() / cam.getHeight();
        if (mode == Mode.CHASE) cam.setFrustumPerspective(62f, aspect, 0.3f, 4000f);
        else cam.setFrustumPerspective(72f, aspect, 0.08f, 4000f);
    }

    @Override
    protected void controlUpdate(float tpf) {
        Vector3f carPos = spatial.getLocalTranslation();
        Quaternion carRot = spatial.getLocalRotation();
        Vector3f fwd = carRot.mult(Vector3f.UNIT_Z);
        Vector3f flatFwd = new Vector3f(fwd.x, 0, fwd.z);
        if (flatFwd.lengthSquared() < 1e-4f) flatFwd.set(0, 0, 1);
        flatFwd.normalizeLocal();

        if (mode == Mode.SHOWCASE) {
            // screenshot-only: low camera ahead of the car looking back at it (and the pack)
            Vector3f right = flatFwd.cross(Vector3f.UNIT_Y);
            Vector3f camPos = carPos.add(flatFwd.mult(showcaseDistance)).addLocal(right.mult(showcaseSide)).addLocal(0, showcaseHeight, 0);
            cam.setLocation(camPos);
            cam.lookAt(carPos.add(flatFwd.mult(-2.5f)).addLocal(0, 0.35f, 0), Vector3f.UNIT_Y);
        } else if (mode == Mode.CHASE) {
            Vector3f desired = flatFwd.mult(-8.5f).addLocal(0, 2.6f, 0);
            float a = snap ? 1f : 1f - FastMath.exp(-tpf * 7f);
            smoothedOffset.interpolateLocal(desired, a);
            Vector3f camPos = carPos.add(smoothedOffset);
            cam.setLocation(camPos);
            Vector3f lookAt = carPos.add(flatFwd.mult(4f)).addLocal(0, 0.9f, 0);
            cam.lookAt(lookAt, Vector3f.UNIT_Y);
        } else {
            Quaternion yawOnly = new Quaternion();
            yawOnly.lookAt(flatFwd, Vector3f.UNIT_Y);
            Quaternion camRot = new Quaternion();
            camRot.slerp(yawOnly, carRot, T_CAM_PITCH_ROLL);
            camRot.multLocal(T_CAM_TILT);
            cam.setLocation(carRot.mult(T_CAM_POS).addLocal(carPos));
            cam.setRotation(camRot);
        }
        snap = false;
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) { }
}
