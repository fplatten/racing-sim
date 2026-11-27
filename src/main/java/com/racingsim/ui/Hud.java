package com.racingsim.ui;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.racingsim.physics.DraftingSystem;
import com.racingsim.track.LapTracker;
import com.racingsim.track.PitLane;
import com.racingsim.track.PitStop;
import com.racingsim.track.TrackSpline;
import com.racingsim.vehicle.Car;
import com.racingsim.vehicle.VehiclePhysics;

import java.util.List;
import java.util.function.Function;

/**
 * Telemetry & race HUD built from BitmapText: speed (MPH), RPM bar, gear, race position,
 * lap / lap times, lateral G, tire slip, live standings, the DRAFT % indicator and the pit-stop
 * count / pit-box countdown.
 */
public final class Hud {

    private static final float MS_TO_MPH = 2.23694f;
    private static final ColorRGBA PANEL = new ColorRGBA(0.02f, 0.03f, 0.06f, 0.62f);
    private static final ColorRGBA DRAFT_ON = new ColorRGBA(0.2f, 0.95f, 1.0f, 1f);
    private static final ColorRGBA DIM = new ColorRGBA(0.6f, 0.62f, 0.66f, 1f);

    /** Snapshot of race-level information the HUD needs from the app. */
    public record RaceInfo(LapTracker tracker, LapTracker.Entry playerEntry, String cameraName, boolean showHelp,
                           boolean mouseSteering, PitStop pit, Function<LapTracker.Entry, DriverTag> driverTags) { }

    /** What the standings show about a car: AI driver type (0 rookie, 1 regular, 2 veteran; -1 for
     *  the human player) and wing level (0..DOWNFORCE_STEPS). */
    public record DriverTag(int type, int wing) { }

    private static final String[] DRIVER_TYPES = {"ROOKIE", "REGULAR", "VETERAN"};
    private static final ColorRGBA[] DRIVER_TYPE_COLORS = {
            new ColorRGBA(0.55f, 0.85f, 1f, 1f), new ColorRGBA(0.80f, 0.80f, 0.80f, 1f), new ColorRGBA(1f, 0.62f, 0.25f, 1f)};
    private static final ColorRGBA WING_COLOR = new ColorRGBA(0.80f, 0.80f, 0.80f, 1f);
    private static final int STANDINGS_ROWS = 10;

    private final Node root = new Node("HUD");
    private final AssetManager assets;
    private final BitmapFont font;
    private final float width, height;

    private BitmapText speedText, speedUnit, gearText, gearInfo, rpmText, gText, slipText;
    private BitmapText posText, lapText, timeText, lastText, bestText, standingsText;
    private BitmapText draftText, helpText, camText, pitText, pitMsg, pitSub, standingsGap, limitsMsg, limitsSub;
    /** Per standings row: driver type and wing level (each row has its own color). */
    private final BitmapText[] standingsType = new BitmapText[STANDINGS_ROWS];
    private final BitmapText[] standingsWing = new BitmapText[STANDINGS_ROWS];
    private Geometry pitBar, pitBarBg;
    private static final float PIT_BAR_W = 360f;
    private static final ColorRGBA GO = new ColorRGBA(0.3f, 1f, 0.35f, 1f);
    private static final ColorRGBA STOP = new ColorRGBA(1f, 0.25f, 0.2f, 1f);
    private static final ColorRGBA PIT_YELLOW = new ColorRGBA(1f, 0.86f, 0.15f, 1f);
    private SteeringWheelWidget steeringWheel;
    private Geometry rpmBar, rpmBarBg, draftBar, draftBarBg, playerRow;
    private float standingsTop, standingsLeft, standingsWidth;
    private static final float RPM_BAR_W = 300f, DRAFT_BAR_W = 260f;

    public Hud(AssetManager assets, BitmapFont font, float width, float height) {
        this.assets = assets;
        this.font = font;
        this.width = width;
        this.height = height;
        build();
    }

    public Node getNode() { return root; }

    private BitmapText text(float size, ColorRGBA color, float x, float y) {
        BitmapText t = new BitmapText(font);
        t.setSize(size);
        t.setColor(color);
        t.setLocalTranslation(x, y, 1);
        root.attachChild(t);
        return t;
    }

    private Geometry rect(float x, float y, float w, float h, ColorRGBA color, float z) {
        Geometry g = new Geometry("rect", new Quad(1, 1));
        Material m = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color.clone());
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        g.setMaterial(m);
        g.setQueueBucket(RenderQueue.Bucket.Gui);
        g.setLocalTranslation(x, y, z);
        g.setLocalScale(w, h, 1);
        root.attachChild(g);
        return g;
    }

    private void build() {
        float base = font.getCharSet().getRenderedSize();
        // ---- telemetry panel (bottom-left) ----
        float px = 20, py = 20, pw = 340, ph = 250;
        rect(px, py, pw, ph, PANEL, 0);
        speedText = text(base * 3.0f, ColorRGBA.White, px + 16, py + ph - 8);
        speedUnit = text(base * 1.1f, DIM, px + 170, py + ph - 40);
        speedUnit.setText("MPH");
        gearText = text(base * 3.0f, ColorRGBA.Yellow, px + 250, py + ph - 8);
        gearInfo = text(base * 0.85f, DIM, px + 232, py + ph - 70);
        rpmText = text(base * 1.0f, ColorRGBA.White, px + 16, py + ph - 88);
        rpmBarBg = rect(px + 16, py + ph - 128, RPM_BAR_W, 12, new ColorRGBA(1, 1, 1, 0.12f), 0.5f);
        rpmBar = rect(px + 16, py + ph - 128, RPM_BAR_W, 12, ColorRGBA.Green, 0.6f);
        gText = text(base * 1.0f, ColorRGBA.White, px + 16, py + ph - 140);
        slipText = text(base * 1.0f, ColorRGBA.White, px + 16, py + ph - 172);
        camText = text(base * 0.85f, DIM, px + 16, py + ph - 208);

        // ---- race panel (top-right) ----
        float rw = 400, rh = 400, rx = width - rw - 20, ry = height - rh - 20;
        rect(rx, ry, rw, rh, PANEL, 0);
        posText = text(base * 2.6f, ColorRGBA.White, rx + 16, ry + rh - 8);
        lapText = text(base * 1.3f, ColorRGBA.White, rx + 190, ry + rh - 18);
        timeText = text(base * 1.0f, ColorRGBA.White, rx + 16, ry + rh - 76);
        pitText = text(base * 1.0f, ColorRGBA.White, rx + 200, ry + rh - 76);
        lastText = text(base * 1.0f, DIM, rx + 16, ry + rh - 102);
        bestText = text(base * 1.0f, DIM, rx + 16, ry + rh - 128);
        standingsTop = ry + rh - 164;
        standingsLeft = rx + 8;
        standingsWidth = rw - 16;
        playerRow = rect(standingsLeft, standingsTop, standingsWidth, 10, new ColorRGBA(0.10f, 0.32f, 0.85f, 0.75f), 0.5f);
        standingsText = text(base * 0.95f, ColorRGBA.White, rx + 16, standingsTop);
        // driver type and wing level: rookie / regular / veteran, W0 (light) .. W10 (heavy)
        float lineH = standingsText.getLineHeight();
        for (int i = 0; i < STANDINGS_ROWS; i++) {
            standingsType[i] = text(base * 0.75f, ColorRGBA.White, rx + 158, standingsTop - i * lineH - 2);
            standingsWing[i] = text(base * 0.75f, WING_COLOR, rx + 248, standingsTop - i * lineH - 2);
        }
        standingsGap = text(base * 0.95f, ColorRGBA.White, rx + 300, standingsTop);

        // ---- draft indicator (top-center) ----
        float dx = width / 2 - DRAFT_BAR_W / 2, dy = height - 70;
        rect(dx - 14, dy - 22, DRAFT_BAR_W + 28, 70, PANEL, 0);
        draftText = text(base * 1.4f, DIM, dx, dy + 42);
        draftBarBg = rect(dx, dy - 10, DRAFT_BAR_W, 12, new ColorRGBA(1, 1, 1, 0.12f), 0.5f);
        draftBar = rect(dx, dy - 10, DRAFT_BAR_W, 12, DRAFT_ON, 0.6f);

        // ---- pit-stop messages / countdown (center) ----
        pitMsg = text(base * 2.2f, PIT_YELLOW, 0, height * 0.72f);
        pitSub = text(base * 1.0f, ColorRGBA.White, 0, height * 0.72f - base * 2.6f);
        float bx = width / 2 - PIT_BAR_W / 2, by = height * 0.72f - base * 4.6f;
        pitBarBg = rect(bx, by, PIT_BAR_W, 10, new ColorRGBA(1, 1, 1, 0.15f), 0.5f);
        pitBar = rect(bx, by, PIT_BAR_W, 10, PIT_YELLOW, 0.6f);

        // ---- track limits (the road course): where the pit messages go on the oval ----
        limitsMsg = text(base * 2.2f, PIT_YELLOW, 0, height * 0.72f);
        limitsSub = text(base * 1.0f, ColorRGBA.White, 0, height * 0.72f - base * 2.6f);

        // ---- steering wheel (bottom center, 10 to 2 o'clock visible) ----
        steeringWheel = new SteeringWheelWidget(assets, font, width, 300f);
        root.attachChild(steeringWheel.getNode());

        // ---- help (top center, under the draft meter) ----
        helpText = text(base * 0.85f, DIM, 0, height - 104);
        setController(null);
        buildStartLights(base);
    }

    private static final String KEY_HELP =
            "W/Up throttle  S/Down brake  A/D or mouse steer  E/Q shift  T auto  R reset  C camera  G quality  H help  Esc menu";
    private String controllerName;                 // null until the help line is first set

    /** Help line for the keyboard, or for the racing wheel / gamepad when one is connected (name, or null). */
    public void setController(String name) {
        String n = name == null ? "" : name;
        if (n.equals(controllerName)) return;
        controllerName = n;
        helpText.setText(n.isEmpty() ? KEY_HELP
                : n + ":  wheel steer  pedals gas/brake  RB/LB paddles shift  X auto  Back reset  Y camera  Start menu"
                  + "   (keys also work, H hides help)");
        helpText.setLocalTranslation(width / 2 - helpText.getLineWidth() / 2, height - 104, 1);
    }

    /** Track limits: the seconds of power cut left (0 hides the message). */
    public void setTrackLimits(float left) {
        String msg = left > 0f ? "TRACK LIMITS" : "";
        String sub = left > 0f ? String.format("all four wheels off the road: power cut for %.1f s", left) : "";
        if (!msg.equals(limitsMsg.getText())) {
            limitsMsg.setText(msg);
            limitsMsg.setLocalTranslation(width / 2 - limitsMsg.getLineWidth() / 2, limitsMsg.getLocalTranslation().y, 1);
        }
        if (!sub.equals(limitsSub.getText())) {
            limitsSub.setText(sub);
            limitsSub.setLocalTranslation(width / 2 - limitsSub.getLineWidth() / 2, limitsSub.getLocalTranslation().y, 1);
        }
    }

    public void update(Car player, RaceInfo info) {
        VehiclePhysics p = player.getPhysics();
        float mph = Math.abs(p.getForwardSpeed()) * MS_TO_MPH;
        speedText.setText(String.format("%3d", Math.round(mph)));

        int gear = p.getGearbox().getGear();
        gearText.setText(gear == 0 ? "R" : Integer.toString(gear));
        gearInfo.setText(p.getGearbox().isAutomatic() ? "AUTO" : "MANUAL");

        float rpm = p.getEngineRpm();
        float redline = player.getSpec().redlineRpm;
        rpmText.setText(String.format("RPM %,6d", Math.round(rpm)));
        float frac = FastMath.clamp(rpm / player.getSpec().limiterRpm, 0f, 1f);
        rpmBar.setLocalScale(Math.max(1f, RPM_BAR_W * frac), 12, 1);
        ColorRGBA rc = rpm > redline * 0.97f ? ColorRGBA.Red : rpm > redline * 0.85f ? ColorRGBA.Orange : ColorRGBA.Green;
        rpmBar.getMaterial().setColor("Color", rc);

        gText.setText(String.format("LAT G %+5.2f    LONG G %+5.2f", p.getLateralG(), p.getLongitudinalG()));
        slipText.setText(String.format("TIRE SLIP  long %4.1f%%   lat %4.1f deg",
                p.getRearSlipRatio() * 100f, p.getFrontSlipAngle() * FastMath.RAD_TO_DEG));
        camText.setText(String.format("CAM: %s     DOWNFORCE %d/%d", info.cameraName(),
                Math.round(p.getAeroModel().getDownforceLevel() * MenuScreen.DOWNFORCE_STEPS), MenuScreen.DOWNFORCE_STEPS));

        LapTracker tracker = info.tracker();
        LapTracker.Entry me = info.playerEntry();
        float now = tracker.getTime();
        posText.setText(String.format("P%d / %d", me.position(), tracker.size()));
        int raceLaps = tracker.getRaceLaps();
        if (me.finished()) {
            lapText.setText("FINISHED");
            lapText.setColor(ColorRGBA.Yellow);
        } else if (me.lap() >= raceLaps) {
            lapText.setText("FINAL LAP");
            lapText.setColor(((int) (now * 2)) % 2 == 0 ? ColorRGBA.White : ColorRGBA.Yellow);
        } else {
            lapText.setText("LAP " + me.lap() + "/" + raceLaps);
            lapText.setColor(ColorRGBA.White);
        }
        timeText.setText("TIME  " + formatTime(me.currentLapTime(now)));
        lastText.setText("LAST  " + formatTime(me.lastLapTime()));
        bestText.setText("BEST  " + formatTime(me.bestLapTime()));

        StringBuilder sb = new StringBuilder(), gaps = new StringBuilder();
        List<LapTracker.Entry> order = tracker.getOrder();
        float refSpeed = Math.max(Math.abs(p.getForwardSpeed()), 20f);
        List<LapTracker.Entry> rows = StandingsView.rows(order, me, STANDINGS_ROWS);
        int myLine = 0;
        for (int i = 0; i < STANDINGS_ROWS; i++) {
            LapTracker.Entry e = i < rows.size() ? rows.get(i) : null;
            standingsType[i].setText("");
            standingsWing[i].setText("");
            if (e == null) {
                if (i < rows.size()) {
                    sb.append("   ...%n".formatted());
                    gaps.append('\n');
                }
                continue;
            }
            if (e == me) myLine = i;
            String gap;
            if (e == me) gap = "  ----";
            else gap = String.format("%+6.2fs", (me.totalDistance() - e.totalDistance()) / refSpeed);
            sb.append(String.format("P%d %s %s%n", e.position(), e == me ? ">" : " ", e.name));
            gaps.append(gap).append('\n');
            DriverTag tag = info.driverTags() == null ? null : info.driverTags().apply(e);
            if (tag != null) {
                if (tag.type() >= 0) {
                    standingsType[i].setText(DRIVER_TYPES[tag.type()]);
                    standingsType[i].setColor(e == me ? ColorRGBA.White : DRIVER_TYPE_COLORS[tag.type()]);
                }
                standingsWing[i].setText("W" + tag.wing());
                standingsWing[i].setColor(e == me ? ColorRGBA.White : WING_COLOR);
            }
        }
        standingsText.setText(sb.toString());
        standingsGap.setText(gaps.toString());
        float lineH = standingsText.getLineHeight();
        playerRow.setLocalTranslation(standingsLeft, standingsTop - (myLine + 1) * lineH - 2, 0.5f);
        playerRow.setLocalScale(standingsWidth, lineH + 1, 1);

        float draft = p.getDraftFactor();
        float pct = p.getDragReduction() * 100f;
        boolean active = draft > 0.01f;
        draftText.setText(String.format("DRAFT %2d%%", Math.round(pct)));
        draftText.setColor(active ? DRAFT_ON : DIM);
        float dfrac = FastMath.clamp(draft / DraftingSystem.MAX_DRAFT, 0f, 1f);
        draftBar.setLocalScale(Math.max(1f, DRAFT_BAR_W * dfrac), 12, 1);
        draftBar.setCullHint(active ? Node.CullHint.Inherit : Node.CullHint.Always);

        helpText.setCullHint(info.showHelp() ? Node.CullHint.Inherit : Node.CullHint.Always);
        updatePit(player, info);
        steeringWheel.update(player.getInput().steer);
        steeringWheel.setMouseSteering(info.mouseSteering(), width);
    }

    // ---- standing start: the five red lights (top center, under the draft meter) ----
    private static final float LIGHT_SPACING = 70f, LIGHT_RADIUS = 24f, LIGHTS_FROM_TOP = 200f;
    private Node lightsPanel;
    private final Geometry[] lightsLit = new Geometry[5], lightsDark = new Geometry[5];
    private BitmapText lightsGo, lightsReady;

    private void buildStartLights(float base) {
        lightsPanel = new Node("StartLights");
        root.attachChild(lightsPanel);
        float cy = height - LIGHTS_FROM_TOP;
        float w = LIGHT_SPACING * 5 + 20f, x0 = width / 2 - w / 2;
        Geometry bg = rect(x0, cy - LIGHT_RADIUS - 14f, w, 2 * LIGHT_RADIUS + 28f, new ColorRGBA(0.02f, 0.02f, 0.025f, 0.9f), 0);
        lightsPanel.attachChild(bg);
        for (int k = 0; k < 5; k++) {
            float cx = x0 + 10f + LIGHT_SPACING * (k + 0.5f);
            lightsLit[k] = disc(cx, cy, new ColorRGBA(1f, 0.10f, 0.05f, 1f), new ColorRGBA(0.75f, 0.02f, 0.02f, 1f));
            lightsDark[k] = disc(cx, cy, new ColorRGBA(0.16f, 0.03f, 0.03f, 1f), new ColorRGBA(0.10f, 0.02f, 0.02f, 1f));
        }
        // under the panel: GO! when they go out, a word of warning before the first one comes on
        float below = cy - LIGHT_RADIUS - 30f;
        lightsGo = text(base * 3.2f, GO, 0, below);
        lightsGo.setText("GO!");
        lightsGo.setLocalTranslation(width / 2 - lightsGo.getLineWidth() / 2, below, 1);
        lightsReady = text(base, ColorRGBA.White, 0, below);
        lightsReady.setText("STANDING START  -  GET READY");
        lightsReady.setLocalTranslation(width / 2 - lightsReady.getLineWidth() / 2, below, 1);
        lightsPanel.attachChild(lightsGo);
        lightsPanel.attachChild(lightsReady);
        setStartLights(-1, -1f);
    }

    /** A round lamp: a fan of triangles from the core color in the middle to the rim color. */
    private Geometry disc(float cx, float cy, ColorRGBA core, ColorRGBA rim) {
        int n = 40;
        float[] pos = new float[(n + 2) * 3], col = new float[(n + 2) * 4];
        int[] idx = new int[n * 3];
        for (int i = 0; i <= n; i++) {
            float a = FastMath.TWO_PI * i / n;
            pos[(i + 1) * 3] = LIGHT_RADIUS * FastMath.cos(a);
            pos[(i + 1) * 3 + 1] = LIGHT_RADIUS * FastMath.sin(a);
            System.arraycopy(new float[]{rim.r, rim.g, rim.b, 1f}, 0, col, (i + 1) * 4, 4);
            if (i < n) {
                idx[i * 3] = 0;
                idx[i * 3 + 1] = i + 1;
                idx[i * 3 + 2] = i + 2;
            }
        }
        System.arraycopy(new float[]{core.r, core.g, core.b, 1f}, 0, col, 0, 4);
        com.jme3.scene.Mesh mesh = new com.jme3.scene.Mesh();
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3, pos);
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Color, 4, col);
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3, idx);
        mesh.updateBound();
        Geometry g = new Geometry("lamp", mesh);
        Material m = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setBoolean("VertexColor", true);
        g.setMaterial(m);
        g.setQueueBucket(RenderQueue.Bucket.Gui);
        g.setLocalTranslation(cx, cy, 0.5f);
        lightsPanel.attachChild(g);
        return g;
    }

    /**
     * Standing start (from {@code RaceDirector.startLights()} / {@code greenTime()}): five red
     * lights come on one by one while the field waits on the grid; when they all go out, GO!
     * @param lights red lights lit 0..5, -1 once they are out
     * @param green seconds since they went out (-1 when not racing from a standing start)
     */
    public void setStartLights(int lights, float green) {
        boolean on = lights >= 0;
        boolean go = !on && green >= 0f && green < 1.6f;
        lightsPanel.setCullHint(on || go ? Node.CullHint.Inherit : Node.CullHint.Always);
        if (!on && !go) return;
        for (int k = 0; k < 5; k++) {
            boolean lit = on && k < lights;
            lightsLit[k].setCullHint(lit ? Node.CullHint.Inherit : Node.CullHint.Always);
            lightsDark[k].setCullHint(lit ? Node.CullHint.Always : Node.CullHint.Inherit);
        }
        lightsGo.setCullHint(go ? Node.CullHint.Inherit : Node.CullHint.Always);
        lightsReady.setCullHint(lights == 0 ? Node.CullHint.Inherit : Node.CullHint.Always);
    }

    private void updatePit(Car player, RaceInfo info) {
        LapTracker tracker = info.tracker();
        LapTracker.Entry me = info.playerEntry();
        int required = tracker.getRequiredStops();
        if (required > 0) {
            pitText.setText("PITS " + me.pitStops() + "/" + required);
            pitText.setColor(tracker.stopsMet(me) ? GO : PIT_YELLOW);
        } else {
            pitText.setText(me.pitStops() > 0 ? "PITS " + me.pitStops() : "");
            pitText.setColor(DIM);
        }

        PitStop pit = info.pit();
        String msg = null, sub = null;
        ColorRGBA color = PIT_YELLOW;
        float barFrac = -1f;
        if (pit != null) {
            switch (pit.status()) {
                case SERVICING -> {
                    msg = String.format("PIT STOP  %.1f", pit.countdown());
                    sub = "Stay in your box until the countdown reaches zero";
                    barFrac = pit.countdown() / PitStop.SERVICE_TIME;
                }
                case COMPLETE -> {
                    if (pit.statusTime() < 3f) {
                        msg = "PIT STOP COMPLETE  -  GO GO GO!";
                        color = GO;
                        sub = required > 0 ? "Pit stops " + me.pitStops() + " of " + required : null;
                    }
                }
                case ABORTED -> {
                    if (pit.statusTime() < 4f) {
                        msg = "PIT STOP DOES NOT COUNT";
                        color = STOP;
                        sub = "You left the box before the countdown reached zero";
                    }
                }
                default -> { }
            }
            float rel = PitLane.rel(player.trackS());
            boolean inPitLane = PitLane.inPitRoad(player.trackS()) && player.trackD() < TrackSpline.INNER_WALL;
            float boxEnd = PitLane.stallStart(pit.stall()) + PitLane.STALL_LENGTH;
            if (msg == null && inPitLane && rel < boxEnd && !me.finished()) {
                msg = "PIT LANE";
                sub = "Your box is the yellow one, number " + (pit.stall() + 1) + " on the start/finish line - stop inside it";
            }
        }
        boolean show = msg != null;
        pitMsg.setCullHint(show ? Node.CullHint.Inherit : Node.CullHint.Always);
        pitSub.setCullHint(show && sub != null ? Node.CullHint.Inherit : Node.CullHint.Always);
        boolean bar = barFrac >= 0f;
        pitBar.setCullHint(bar ? Node.CullHint.Inherit : Node.CullHint.Always);
        pitBarBg.setCullHint(bar ? Node.CullHint.Inherit : Node.CullHint.Always);
        if (!show) return;
        pitMsg.setText(msg);
        pitMsg.setColor(color);
        pitMsg.setLocalTranslation(width / 2 - pitMsg.getLineWidth() / 2, pitMsg.getLocalTranslation().y, 1);
        if (sub != null) {
            pitSub.setText(sub);
            pitSub.setLocalTranslation(width / 2 - pitSub.getLineWidth() / 2, pitSub.getLocalTranslation().y, 1);
        }
        if (bar) pitBar.setLocalScale(Math.max(1f, PIT_BAR_W * barFrac), 10, 1);
    }

    public static String formatTime(float t) {
        if (Float.isNaN(t)) return "--:--.---";
        int min = (int) (t / 60);
        float sec = t - min * 60;
        return String.format("%d:%06.3f", min, sec);
    }
}
