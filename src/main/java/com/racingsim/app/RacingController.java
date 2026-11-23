package com.racingsim.app;

import static org.lwjgl.glfw.GLFW.*;

import com.jme3.math.FastMath;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;
import org.lwjgl.glfw.GLFWGamepadState;

/**
 * Racing wheel / gamepad support, read straight from GLFW once per frame on the render thread.
 * <p>
 * The first connected controller is used and can be plugged in or out at any time. When GLFW
 * knows the device's layout (every XInput device on Windows, and most popular gamepads) the
 * standard gamepad mapping is used:
 * <pre>
 *  Wheel / left stick X   steer             Right trigger (gas pedal)    throttle
 *  Left trigger (brake)   brake             RB / right paddle            shift up
 *  LB / left paddle       shift down        X                            toggle automatic gearbox
 *  Y                      toggle camera     Back / View                  reset to start/finish line
 *  Start / Menu           pause (Esc)       A                            OK (Enter)
 *  B                      back / cancel     D-pad                        menu navigation
 * </pre>
 * Other devices (for example DirectInput wheels) are read as raw axes: axis 0 steers, axis 1 is
 * the throttle and axis 2 the brake. A pedal's resting position (+1, 0 or -1) is taken from the
 * first reading, so pedals that run either way round work without setup.
 * <p>
 * Any of this can be overridden in an optional {@code controller.properties} file in the working
 * directory (next to the executable for the Windows build), for example:
 * <pre>
 *  mode=raw            # auto (default), gamepad or raw
 *  device=G29          # use the first controller whose name contains this text
 *  steer.axis=0        throttle.axis=2        brake.axis=3
 *  steer.invert=false  steer.gain=1.0         steer.deadzone=0.01   pedal.deadzone=0.03
 *  button.shiftUp=5    button.shiftDown=4     button.camera=3       button.auto=2
 *  button.reset=6      button.pause=7         button.ok=0           button.back=1
 * </pre>
 * Axis and button numbers are the gamepad numbers in gamepad mode (axes: 0 LX, 1 LY, 2 RX, 3 RY,
 * 4 LT, 5 RT; buttons: 0 A, 1 B, 2 X, 3 Y, 4 LB, 5 RB, 6 Back, 7 Start) and the device's own
 * numbers in raw mode. The controller and its axes are written to the log when it connects.
 */
public final class RacingController {

    private static final Logger LOG = Logger.getLogger(RacingController.class.getName());
    static final String CONFIG_FILE = "controller.properties";

    /** Digital controls a controller button can trigger. */
    public enum Button { SHIFT_UP, SHIFT_DOWN, CAMERA, AUTO, RESET, PAUSE, OK, BACK, UP, DOWN, LEFT, RIGHT }

    public interface Listener {
        void onButton(Button button, boolean pressed);
    }

    private enum Mode { AUTO, GAMEPAD, RAW }

    // ---- configuration ----
    private final Mode mode;
    private final String deviceFilter;
    private final Integer steerAxisCfg, throttleAxisCfg, brakeAxisCfg;
    private final boolean steerInvert;
    private final float steerGain, steerDeadzone, pedalDeadzone;
    private final int[] buttonCfg = new int[Button.values().length];   // -1: default

    // ---- live state ----
    private int joystick = -1;
    private String name;
    private boolean gamepad;
    private int steerAxis, throttleAxis, brakeAxis;
    private final float[] pedalRest = new float[2];               // throttle, brake
    private final int[] buttonIndex = new int[Button.values().length];
    private final boolean[] down = new boolean[Button.values().length];
    private GLFWGamepadState gamepadState;
    private float rescanTimer;

    private volatile boolean connected;
    private volatile float steer, throttle, brake;

    public RacingController() {
        Properties p = loadConfig();
        mode = switch (p.getProperty("mode", "auto").trim().toLowerCase(Locale.ROOT)) {
            case "gamepad" -> Mode.GAMEPAD;
            case "raw" -> Mode.RAW;
            default -> Mode.AUTO;
        };
        deviceFilter = p.getProperty("device", "").trim().toLowerCase(Locale.ROOT);
        steerAxisCfg = intOrNull(p, "steer.axis");
        throttleAxisCfg = intOrNull(p, "throttle.axis");
        brakeAxisCfg = intOrNull(p, "brake.axis");
        steerInvert = Boolean.parseBoolean(p.getProperty("steer.invert", "false").trim());
        steerGain = floatOr(p, "steer.gain", 1f);
        steerDeadzone = floatOr(p, "steer.deadzone", 0.01f);
        pedalDeadzone = floatOr(p, "pedal.deadzone", 0.03f);
        Arrays.fill(buttonCfg, -1);
        String[] keys = {"shiftUp", "shiftDown", "camera", "auto", "reset", "pause", "ok", "back"};
        for (int i = 0; i < keys.length; i++) {
            Integer v = intOrNull(p, "button." + keys[i]);
            if (v != null) buttonCfg[i] = v;
        }
    }

    public boolean isConnected() { return connected; }
    public String getName() { return name; }
    /** Steering, -1 (full right) .. +1 (full left), same sign as {@code VehicleInput.steer}. */
    public float steer() { return steer; }
    public float throttle() { return throttle; }
    public float brake() { return brake; }

    /** Reads the controller and reports button presses / releases. Call once per frame on the render thread. */
    public void poll(float tpf, Listener listener) {
        if (joystick < 0 || !glfwJoystickPresent(joystick)) {
            if (joystick >= 0) disconnect(listener);
            rescanTimer -= tpf;
            if (rescanTimer > 0f) return;
            rescanTimer = 1f;                                     // look for a new device once a second
            if (!connect()) return;
        }
        boolean[] now = new boolean[down.length];
        float rawSteer, rawThrottle, rawBrake;
        if (gamepad) {
            if (!glfwGetGamepadState(joystick, gamepadState)) return;
            rawSteer = gamepadState.axes(steerAxis);
            rawThrottle = gamepadState.axes(throttleAxis);
            rawBrake = gamepadState.axes(brakeAxis);
            for (int b = 0; b < buttonIndex.length; b++) {
                int i = buttonIndex[b];
                now[b] = i >= 0 && i <= GLFW_GAMEPAD_BUTTON_LAST && gamepadState.buttons(i) == GLFW_PRESS;
            }
        } else {
            FloatBuffer axes = glfwGetJoystickAxes(joystick);
            ByteBuffer buttons = glfwGetJoystickButtons(joystick);
            if (axes == null) return;
            rawSteer = axis(axes, steerAxis);
            rawThrottle = axis(axes, throttleAxis);
            rawBrake = axis(axes, brakeAxis);
            for (int b = 0; b < buttonIndex.length; b++) {
                int i = buttonIndex[b];
                now[b] = buttons != null && i >= 0 && i < buttons.limit() && buttons.get(i) == GLFW_PRESS;
            }
            ByteBuffer hats = glfwGetJoystickHats(joystick);         // first hat switch = D-pad
            if (hats != null && hats.limit() > 0) {
                int h = hats.get(0);
                now[Button.UP.ordinal()] |= (h & GLFW_HAT_UP) != 0;
                now[Button.DOWN.ordinal()] |= (h & GLFW_HAT_DOWN) != 0;
                now[Button.LEFT.ordinal()] |= (h & GLFW_HAT_LEFT) != 0;
                now[Button.RIGHT.ordinal()] |= (h & GLFW_HAT_RIGHT) != 0;
            }
        }

        // GLFW: right is positive; the car: left is positive
        float s = -rawSteer * (steerInvert ? -1f : 1f);
        s = Math.abs(s) < steerDeadzone ? 0f : Math.signum(s) * (Math.abs(s) - steerDeadzone) / (1f - steerDeadzone);
        steer = FastMath.clamp(s * steerGain, -1f, 1f);
        throttle = pedal(rawThrottle, pedalRest[0]);
        brake = pedal(rawBrake, pedalRest[1]);

        for (Button b : Button.values()) {
            int i = b.ordinal();
            if (now[i] != down[i]) {
                down[i] = now[i];
                listener.onButton(b, now[i]);
            }
        }
    }

    /** Pedal travel 0..1 measured from its resting position (+1, 0 or -1). */
    private float pedal(float raw, float rest) {
        float span = rest == 0f ? 1f : 2f;
        float v = Math.abs(raw - rest) / span;
        v = (v - pedalDeadzone) / (1f - 2f * pedalDeadzone);     // dead zone at both ends
        return FastMath.clamp(v, 0f, 1f);
    }

    private static float axis(FloatBuffer axes, int i) {
        return i >= 0 && i < axes.limit() ? axes.get(i) : 0f;
    }

    private boolean connect() {
        for (int j = GLFW_JOYSTICK_1; j <= GLFW_JOYSTICK_LAST; j++) {
            if (!glfwJoystickPresent(j)) continue;
            String n = glfwGetJoystickName(j);
            if (n == null) n = "Controller " + (j + 1);
            if (!deviceFilter.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(deviceFilter)) continue;
            joystick = j;
            name = n;
            gamepad = mode == Mode.GAMEPAD || (mode == Mode.AUTO && glfwJoystickIsGamepad(j));
            if (gamepad && gamepadState == null) gamepadState = GLFWGamepadState.create();
            steerAxis = steerAxisCfg != null ? steerAxisCfg : gamepad ? GLFW_GAMEPAD_AXIS_LEFT_X : 0;
            throttleAxis = throttleAxisCfg != null ? throttleAxisCfg : gamepad ? GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER : 1;
            brakeAxis = brakeAxisCfg != null ? brakeAxisCfg : gamepad ? GLFW_GAMEPAD_AXIS_LEFT_TRIGGER : 2;

            int[] defaults = gamepad
                    ? new int[]{GLFW_GAMEPAD_BUTTON_RIGHT_BUMPER, GLFW_GAMEPAD_BUTTON_LEFT_BUMPER, GLFW_GAMEPAD_BUTTON_Y,
                                GLFW_GAMEPAD_BUTTON_X, GLFW_GAMEPAD_BUTTON_BACK, GLFW_GAMEPAD_BUTTON_START,
                                GLFW_GAMEPAD_BUTTON_A, GLFW_GAMEPAD_BUTTON_B, GLFW_GAMEPAD_BUTTON_DPAD_UP,
                                GLFW_GAMEPAD_BUTTON_DPAD_DOWN, GLFW_GAMEPAD_BUTTON_DPAD_LEFT, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT}
                    // raw: XInput-style order; the D-pad comes from the hat switch
                    : new int[]{5, 4, 3, 2, 6, 7, 0, 1, -1, -1, -1, -1};
            for (int b = 0; b < buttonIndex.length; b++) buttonIndex[b] = buttonCfg[b] >= 0 ? buttonCfg[b] : defaults[b];

            // pedals rest at -1 on GLFW gamepads; raw pedals rest wherever the first reading says
            if (gamepad) {
                pedalRest[0] = restOf(throttleAxis);
                pedalRest[1] = restOf(brakeAxis);
            } else {
                FloatBuffer axes = glfwGetJoystickAxes(j);
                pedalRest[0] = axes == null ? 0f : Math.round(FastMath.clamp(axis(axes, throttleAxis), -1f, 1f));
                pedalRest[1] = axes == null ? 0f : Math.round(FastMath.clamp(axis(axes, brakeAxis), -1f, 1f));
            }
            Arrays.fill(down, false);
            connected = true;
            LOG.info(describe(j));
            return true;
        }
        return false;
    }

    private static float restOf(int gamepadAxis) {
        return gamepadAxis == GLFW_GAMEPAD_AXIS_LEFT_TRIGGER || gamepadAxis == GLFW_GAMEPAD_AXIS_RIGHT_TRIGGER ? -1f : 0f;
    }

    private String describe(int j) {
        FloatBuffer axes = glfwGetJoystickAxes(j);
        ByteBuffer buttons = glfwGetJoystickButtons(j);
        StringBuilder sb = new StringBuilder("Racing controller connected: '").append(name).append("' (")
                .append(gamepad ? "gamepad mapping " + glfwGetGamepadName(j) : "raw axes").append(")")
                .append(", steer axis ").append(steerAxis).append(", throttle axis ").append(throttleAxis)
                .append(" (rest ").append(pedalRest[0]).append("), brake axis ").append(brakeAxis)
                .append(" (rest ").append(pedalRest[1]).append("); raw axes now [");
        if (axes != null) while (axes.hasRemaining()) sb.append(String.format(Locale.ROOT, " %+.2f", axes.get()));
        sb.append(" ], ").append(buttons == null ? 0 : buttons.limit()).append(" buttons");
        return sb.toString();
    }

    private void disconnect(Listener listener) {
        LOG.info("Racing controller disconnected: '" + name + "'");
        for (Button b : Button.values()) {
            if (down[b.ordinal()]) listener.onButton(b, false);
        }
        Arrays.fill(down, false);
        joystick = -1;
        connected = false;
        steer = throttle = brake = 0f;
        rescanTimer = 0f;
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        Path file = Path.of(CONFIG_FILE);
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
                // allow "key=value   # comment" (plain Properties would keep the comment in the value)
                for (String key : p.stringPropertyNames()) {
                    String v = p.getProperty(key);
                    int hash = v.indexOf('#');
                    if (hash >= 0) p.setProperty(key, v.substring(0, hash).trim());
                }
                LOG.info("Controller settings read from " + file.toAbsolutePath());
            } catch (IOException e) {
                LOG.warning("Could not read " + file.toAbsolutePath() + ": " + e);
            }
        }
        return p;
    }

    private static Integer intOrNull(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return null;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            LOG.warning(CONFIG_FILE + ": " + key + " is not a number: " + v);
            return null;
        }
    }

    private static float floatOr(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            LOG.warning(CONFIG_FILE + ": " + key + " is not a number: " + v);
            return def;
        }
    }
}
