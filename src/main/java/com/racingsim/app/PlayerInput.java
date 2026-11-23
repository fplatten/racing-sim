package com.racingsim.app;

import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.math.FastMath;
import com.racingsim.vehicle.VehicleInput;

/**
 * Keyboard controls. Digital keys are converted into smooth analog commands with
 * speed-sensitive steering rates.
 * <pre>
 *  W / Up      throttle          S / Down   brake (hold at standstill = reverse)
 *  A / Left    steer left        D / Right  steer right
 *  E / Q       shift up / down   T          toggle automatic gearbox
 *  R           reset to start/finish line
 *  C           toggle camera (chase / T-cam)
 *  H           toggle help
 *  G           toggle high-quality post effects (SSAO, light shafts, bloom)
 *  Mouse wheel click   engage / release mouse steering (move the mouse left / right to turn)
 * </pre>
 * With mouse steering engaged the mouse behaves like a steering wheel: its horizontal travel
 * sets an absolute wheel position that stays where it is left.
 */
public final class PlayerInput implements ActionListener, AnalogListener {

    private static final String THROTTLE = "Throttle", BRAKE = "Brake", LEFT = "Left", RIGHT = "Right";
    private static final String SHIFT_UP = "ShiftUp", SHIFT_DOWN = "ShiftDown", AUTO = "ToggleAuto";
    private static final String RESET = "Reset", CAMERA = "Camera", HELP = "Help", QUALITY = "Quality";
    private static final String MOUSE_TOGGLE = "MouseSteerToggle", MOUSE_LEFT = "MouseSteerLeft", MOUSE_RIGHT = "MouseSteerRight";
    /** Steering travel per unit of jME mouse-axis input (~1/1024 per pixel): full lock in ~850 px. */
    private static final float MOUSE_SENSITIVITY = 1.2f;

    private boolean throttle, brake, left, right;
    private volatile boolean shiftUp, shiftDown, toggleAuto, reset, camera, help, quality, mouseToggle;
    private boolean mouseSteering;
    private float mouseSteer;

    private float throttleCmd, brakeCmd, steerCmd;

    public void register(InputManager im) {
        im.addMapping(THROTTLE, new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
        im.addMapping(BRAKE, new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
        im.addMapping(LEFT, new KeyTrigger(KeyInput.KEY_A), new KeyTrigger(KeyInput.KEY_LEFT));
        im.addMapping(RIGHT, new KeyTrigger(KeyInput.KEY_D), new KeyTrigger(KeyInput.KEY_RIGHT));
        im.addMapping(SHIFT_UP, new KeyTrigger(KeyInput.KEY_E));
        im.addMapping(SHIFT_DOWN, new KeyTrigger(KeyInput.KEY_Q));
        im.addMapping(AUTO, new KeyTrigger(KeyInput.KEY_T));
        im.addMapping(RESET, new KeyTrigger(KeyInput.KEY_R));
        im.addMapping(CAMERA, new KeyTrigger(KeyInput.KEY_C));
        im.addMapping(HELP, new KeyTrigger(KeyInput.KEY_H));
        im.addMapping(QUALITY, new KeyTrigger(KeyInput.KEY_G));
        im.addMapping(MOUSE_TOGGLE, new MouseButtonTrigger(MouseInput.BUTTON_MIDDLE));
        im.addMapping(MOUSE_LEFT, new MouseAxisTrigger(MouseInput.AXIS_X, true));
        im.addMapping(MOUSE_RIGHT, new MouseAxisTrigger(MouseInput.AXIS_X, false));
        im.addListener(this, THROTTLE, BRAKE, LEFT, RIGHT, SHIFT_UP, SHIFT_DOWN, AUTO, RESET, CAMERA, HELP, QUALITY,
                MOUSE_TOGGLE, MOUSE_LEFT, MOUSE_RIGHT);
    }

    @Override
    public void onAction(String name, boolean pressed, float tpf) {
        switch (name) {
            case THROTTLE -> throttle = pressed;
            case BRAKE -> brake = pressed;
            case LEFT -> left = pressed;
            case RIGHT -> right = pressed;
            case SHIFT_UP -> { if (pressed) shiftUp = true; }
            case SHIFT_DOWN -> { if (pressed) shiftDown = true; }
            case AUTO -> { if (pressed) toggleAuto = true; }
            case RESET -> { if (pressed) reset = true; }
            case CAMERA -> { if (pressed) camera = true; }
            case HELP -> { if (pressed) help = true; }
            case QUALITY -> { if (pressed) quality = true; }
            case MOUSE_TOGGLE -> { if (pressed) mouseToggle = true; }
            default -> { }
        }
    }

    @Override
    public void onAnalog(String name, float value, float tpf) {
        if (!mouseSteering) return;
        // mouse left turns the wheel left (positive steer), mouse right turns it right
        if (name.equals(MOUSE_LEFT)) mouseSteer = FastMath.clamp(mouseSteer + value * MOUSE_SENSITIVITY, -1f, 1f);
        else if (name.equals(MOUSE_RIGHT)) mouseSteer = FastMath.clamp(mouseSteer - value * MOUSE_SENSITIVITY, -1f, 1f);
    }

    /** Engages / releases mouse steering; the wheel picks up from its current position. */
    public void setMouseSteering(boolean on) {
        if (on && !mouseSteering) mouseSteer = steerCmd;
        mouseSteering = on;
    }

    public boolean isMouseSteering() { return mouseSteering; }

    /** Converts key state into analog vehicle commands (called every physics step). */
    public void apply(VehicleInput in, float dt, float speed) {
        throttleCmd = ramp(throttleCmd, throttle ? 1f : 0f, 6f * dt, 10f * dt);
        brakeCmd = ramp(brakeCmd, brake ? 1f : 0f, 7f * dt, 10f * dt);
        float steerTarget = (left ? 1f : 0f) - (right ? 1f : 0f);
        float speedFactor = 1f / (1f + Math.abs(speed) / 50f);
        float rate = (steerTarget != 0f ? 3.0f : 5.0f) * speedFactor * dt;
        // returning through center is quicker than winding on lock
        if (steerTarget != 0f && Math.signum(steerTarget) != Math.signum(steerCmd)) rate *= 1.8f;
        if (mouseSteering) {
            // follow the mouse wheel position with a tiny smoothing lag
            steerCmd += (mouseSteer - steerCmd) * Math.min(1f, dt / 0.03f);
        } else {
            steerCmd += FastMath.clamp(steerTarget - steerCmd, -rate, rate);
        }

        in.throttle = throttleCmd;
        in.brake = brakeCmd;
        in.steer = steerCmd;
        if (shiftUp) { in.shiftUpRequest = true; shiftUp = false; }
        if (shiftDown) { in.shiftDownRequest = true; shiftDown = false; }
    }

    private static float ramp(float cur, float target, float up, float down) {
        return cur < target ? Math.min(target, cur + up) : Math.max(target, cur - down);
    }

    public void clearCommands() {
        throttleCmd = brakeCmd = steerCmd = 0f;
        mouseSteer = 0f;
    }

    public boolean consumeReset() { boolean r = reset; reset = false; return r; }
    public boolean consumeCamera() { boolean r = camera; camera = false; return r; }
    public boolean consumeToggleAuto() { boolean r = toggleAuto; toggleAuto = false; return r; }
    public boolean consumeHelp() { boolean r = help; help = false; return r; }
    public boolean consumeQuality() { boolean r = quality; quality = false; return r; }
    public boolean consumeMouseToggle() { boolean r = mouseToggle; mouseToggle = false; return r; }
}
