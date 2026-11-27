package com.racingsim.ui;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.racingsim.track.TrackLayout;

/**
 * Opening screen drawn over the live 3D scene: the track (OVAL / ROAD: changing it rebuilds the
 * world, see RaceApp), race length (5 / 10 laps on the oval, 3 / 5 on the road course), number of opponents
 * (1..32, i.e. a field of up to 33 cars), the player's starting position (P1 .. last) and the
 * number of mandatory pit stops (0..3, the oval only) every car must make, and the downforce (wing) setting of the
 * player's car on a slider from minimum downforce (max speed) to maximum downforce (max control).
 * Up/Down selects a setting, Left/Right changes it, Enter starts the race.
 */
public final class MenuScreen {

    public static final int[] RACE_LENGTHS = {5, 10};
    /** The road course's laps are almost three times as long as the oval's. */
    public static final int[] ROAD_RACE_LENGTHS = {3, 5};
    public static final int MIN_OPPONENTS = 1, MAX_OPPONENTS = 32;
    public static final int MAX_PIT_STOPS = 3;
    /** Downforce slider: 0 = minimum downforce (max speed) .. DOWNFORCE_STEPS = maximum (max control). */
    public static final int DOWNFORCE_STEPS = 10;
    private static final int ROWS = 6, TRACK_ROW = 0, PIT_ROW = 4, DOWNFORCE_ROW = 5;
    private static final float SLIDER_W = 180f;

    private static final ColorRGBA PANEL = new ColorRGBA(0.02f, 0.03f, 0.06f, 0.72f);
    private static final ColorRGBA ACCENT = new ColorRGBA(0.10f, 0.32f, 0.85f, 0.9f);
    private static final ColorRGBA IDLE = new ColorRGBA(1, 1, 1, 0.07f);
    private static final ColorRGBA DIM = new ColorRGBA(0.70f, 0.72f, 0.76f, 1f);
    private static final ColorRGBA GOLD = new ColorRGBA(1f, 0.85f, 0.3f, 1f);

    private final Node root = new Node("Menu");
    private final AssetManager assets;
    private final BitmapFont font;
    private final float width;
    private final String[] labels = {"TRACK", "RACE LENGTH", "OPPONENTS", "STARTING POSITION", "PIT STOPS", "DOWNFORCE"};
    private final BitmapText[] labelTexts = new BitmapText[ROWS], valueTexts = new BitmapText[ROWS];
    private final Geometry[] rowBoxes = new Geometry[ROWS];
    private final float[] valueCenters = new float[ROWS];

    private int row;
    private TrackLayout track;
    private final BitmapText subtitle, trackHint, botStatus;
    private int lapIndex = 0;
    private int opponents = 7;
    private int startPosition = 6;
    private int pitStops = 0;
    private int downforce = 0;
    private Geometry sliderTrack, sliderFill, sliderKnob;
    private BitmapText sliderMin, sliderMax;
    private float sliderX, sliderY;

    public MenuScreen(AssetManager assets, BitmapFont font, float width, float height) {
        this.assets = assets;
        this.font = font;
        this.width = width;
        float base = font.getCharSet().getRenderedSize();
        track = com.racingsim.track.TrackSpline.layout();
        float pw = 660, ph = 732, px = (width - pw) / 2, py = (height - ph) / 2;
        rect(px, py, pw, ph, PANEL, 0);
        rect(px, py + ph - 6, pw, 6, ACCENT, 0.1f);

        centered("RACING SIM", base * 3.2f, ColorRGBA.White, width / 2, py + ph - 30);
        subtitle = centered("", base, DIM, width / 2, py + ph - 110);
        centered("RACE SETUP", base * 1.2f, GOLD, width / 2, py + ph - 160);

        float boxW = 560, boxH = 50, x = width / 2 - boxW / 2;
        for (int i = 0; i < ROWS; i++) {
            float y = py + ph - 250 - i * (boxH + 10);
            rowBoxes[i] = rect(x, y, boxW, boxH, IDLE, 0.2f);
            labelTexts[i] = text(labels[i], base * 1.15f, ColorRGBA.White, x + 22, y + boxH - 14);
            valueCenters[i] = x + boxW - 130;
            valueTexts[i] = text("", base * 1.4f, ColorRGBA.White, 0, y + boxH - 10);
            if (i == DOWNFORCE_ROW) {
                // slider: SPEED (min downforce) .. CONTROL (max downforce)
                sliderX = x + 285;
                sliderY = y + boxH / 2f;
                sliderMin = text("SPEED", base * 0.85f, DIM, 0, sliderY + base * 0.45f);
                sliderMin.setLocalTranslation(sliderX - 14 - sliderMin.getLineWidth(), sliderY + base * 0.45f, 1);
                sliderMax = text("CONTROL", base * 0.85f, DIM, sliderX + SLIDER_W + 14, sliderY + base * 0.45f);
                sliderTrack = rect(sliderX, sliderY - 3, SLIDER_W, 6, new ColorRGBA(1, 1, 1, 0.25f), 0.3f);
                for (int k = 0; k <= DOWNFORCE_STEPS; k++) {
                    rect(sliderX + SLIDER_W * k / DOWNFORCE_STEPS - 1, sliderY - 7, 2, 14, new ColorRGBA(1, 1, 1, 0.3f), 0.3f);
                }
                sliderFill = rect(sliderX, sliderY - 3, 1, 6, GOLD, 0.35f);
                sliderKnob = rect(sliderX, sliderY - 13, 12, 26, ColorRGBA.White, 0.4f);
            }
        }
        centered("UP / DOWN choose a setting  -  LEFT / RIGHT change it  -  ENTER start the race",
                base, ColorRGBA.White, width / 2, py + 160);
        centered("W/Up throttle   S/Down brake   A/D steer (or click mouse wheel: mouse steering)   E/Q shift",
                base * 0.85f, DIM, width / 2, py + 115);
        centered("T auto gearbox   R reset to start line   C camera   G graphics quality   H help   Esc quit game",
                base * 0.85f, DIM, width / 2, py + 87);
        trackHint = centered("", base * 0.85f, DIM, width / 2, py + 59);
        botStatus = centered("", base * 0.85f, GOLD, width / 2, py + 34);
        centered("Race in the draft: tuck in behind a car to cut drag and slingshot past.",
                base * 0.85f, DIM, width / 2, py + 31);
        refresh();
    }

    public Node getNode() { return root; }

    public int selectedLaps() {
        return customLaps > 0 ? customLaps : (track == TrackLayout.OVAL ? RACE_LENGTHS : ROAD_RACE_LENGTHS)[lapIndex];
    }

    /** The track chosen on the TRACK row. */
    public TrackLayout track() { return track; }

    /** True while the TRACK row is selected (a change there means a different world). */
    public boolean onTrackRow() { return row == TRACK_ROW; }

    public int opponents() { return opponents; }

    /** Total cars in the race including the player. */
    public int fieldSize() { return opponents + 1; }

    /** Player's grid position, 1 = pole. */
    public int startPosition() { return startPosition; }

    /** Mandatory pit stops per car. */
    public int pitStops() { return pitStops; }

    /** Player's wing level: 0 = minimum downforce (max speed) .. 1 = maximum downforce (max control). */
    public float downforceLevel() { return downforce / (float) DOWNFORCE_STEPS; }

    /** Sets the downforce slider (0..DOWNFORCE_STEPS). */
    public void setDownforce(int steps) {
        downforce = Math.max(0, Math.min(DOWNFORCE_STEPS, steps));
        refresh();
    }

    /** Sets all values at once (automated runs; laps need not be one of the menu options). */
    public void configure(int laps, int opponentCount, int start, int stops) {
        pitStops = track.hasPits() ? Math.max(0, Math.min(MAX_PIT_STOPS, stops)) : 0;
        customLaps = laps;
        opponents = Math.max(MIN_OPPONENTS, Math.min(MAX_OPPONENTS, opponentCount));
        startPosition = Math.max(1, Math.min(fieldSize(), start));
        refresh();
    }

    private int customLaps = -1;

    /** Up / Down: select the previous / next setting. */
    public void moveRow(int delta) {
        row = Math.floorMod(row + delta, ROWS);
        refresh();
    }

    /** Left / Right: change the selected setting. @return true if the field (cars / grid) changed */
    public boolean change(int delta) {
        switch (row) {
            case TRACK_ROW -> {
                // the other track starts on its own wing; the road course has no pit lane
                track = track.next();
                downforce = track.defaultDownforce();
                customLaps = -1;
                if (!track.hasPits()) pitStops = 0;
            }
            case 1 -> {
                customLaps = -1;
                lapIndex = Math.floorMod(lapIndex + delta, RACE_LENGTHS.length);
            }
            case 2 -> {
                opponents = Math.max(MIN_OPPONENTS, Math.min(MAX_OPPONENTS, opponents + delta));
                startPosition = Math.min(startPosition, fieldSize());
            }
            case 3 -> startPosition = Math.max(1, Math.min(fieldSize(), startPosition + delta));
            case PIT_ROW -> pitStops = track.hasPits() ? Math.max(0, Math.min(MAX_PIT_STOPS, pitStops + delta)) : 0;
            default -> downforce = Math.max(0, Math.min(DOWNFORCE_STEPS, downforce + delta));
        }
        refresh();
        return row == 2 || row == 3;
    }

    private void refresh() {
        boolean oval = track == TrackLayout.OVAL;
        setCentered(subtitle, oval ? "2.5-mile oval  -  open-wheel cars  -  rolling start, three abreast"
                : "4.352-mile road course  -  102 m of hills  -  standing start");
        setCentered(trackHint, oval ? "Pit stops: stop in your yellow box (pit lane off turn 4) and stay put for the 8 s countdown."
                : "Standing start: wait on the grid until all five red lights go out, then go.");
        String[] values = {
                track.label(),
                selectedLaps() + " LAPS",
                opponents + "   (" + fieldSize() + " cars)",
                "P" + startPosition + " of " + fieldSize(),
                !track.hasPits() ? "N/A" : pitStops == 0 ? "NONE" : pitStops + (pitStops == 1 ? " STOP" : " STOPS"),
                ""};
        for (int i = 0; i < ROWS; i++) {
            boolean on = i == row;
            rowBoxes[i].getMaterial().setColor("Color", on ? ACCENT : IDLE);
            labelTexts[i].setColor(on ? ColorRGBA.White : DIM);
            valueTexts[i].setText(i == DOWNFORCE_ROW ? "" : (on ? "<  " : "") + values[i] + (on ? "  >" : ""));
            valueTexts[i].setColor(on ? ColorRGBA.White : DIM);
            valueTexts[i].setLocalTranslation(valueCenters[i] - valueTexts[i].getLineWidth() / 2,
                    valueTexts[i].getLocalTranslation().y, 1);
        }
        boolean on = row == DOWNFORCE_ROW;
        float frac = downforceLevel();
        sliderFill.setLocalScale(Math.max(1f, SLIDER_W * frac), 6, 1);
        sliderKnob.setLocalTranslation(sliderX + SLIDER_W * frac - 6, sliderY - 13, 0.4f);
        sliderKnob.getMaterial().setColor("Color", on ? ColorRGBA.White : DIM);
        sliderMin.setColor(downforce == 0 ? GOLD : on ? ColorRGBA.White : DIM);
        sliderMax.setColor(downforce == DOWNFORCE_STEPS ? GOLD : on ? ColorRGBA.White : DIM);
    }

    private BitmapText text(String s, float size, ColorRGBA color, float x, float top) {
        BitmapText t = new BitmapText(font);
        t.setSize(size);
        t.setColor(color);
        t.setText(s);
        t.setLocalTranslation(x, top, 1);
        root.attachChild(t);
        return t;
    }

    private void setCentered(BitmapText t, String s) {
        t.setText(s);
        t.setLocalTranslation(width / 2 - t.getLineWidth() / 2, t.getLocalTranslation().y, 1);
    }

    private BitmapText centered(String s, float size, ColorRGBA color, float cx, float top) {
        BitmapText t = text(s, size, color, 0, top);
        t.setLocalTranslation(cx - t.getLineWidth() / 2, top, 1);
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

    /** The bots connected to the bot server (bots/PROTOCOL.md), or "" when there is none. */
    public void setBotStatus(String s) {
        if (!s.equals(botStatus.getText())) setCentered(botStatus, s);
    }
}
