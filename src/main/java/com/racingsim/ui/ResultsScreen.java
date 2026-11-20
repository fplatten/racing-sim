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
import com.racingsim.track.LapTracker;

import java.util.List;

/** Chequered-flag classification, updated live while the rest of the field finishes. */
public final class ResultsScreen {

    private static final ColorRGBA PANEL = new ColorRGBA(0.02f, 0.03f, 0.06f, 0.78f);
    private static final ColorRGBA DIM = new ColorRGBA(0.70f, 0.72f, 0.76f, 1f);
    private static final int ROWS = 11;

    private final Node root = new Node("Results");
    private final BitmapText title;
    private final BitmapText[] pos = new BitmapText[ROWS], name = new BitmapText[ROWS],
            laps = new BitmapText[ROWS], pits = new BitmapText[ROWS], time = new BitmapText[ROWS], best = new BitmapText[ROWS];
    private final Geometry highlight;
    private final float rowTop, rowH, panelX, panelW;

    public ResultsScreen(AssetManager assets, BitmapFont font, float width, float height) {
        float base = font.getCharSet().getRenderedSize();
        panelW = 760;
        float ph = 560;
        panelX = (width - panelW) / 2;
        float py = (height - ph) / 2;
        rect(assets, panelX, py, panelW, ph, PANEL, 0);
        // chequered strip
        for (int i = 0; i < 38; i++) {
            for (int j = 0; j < 2; j++) {
                ColorRGBA c = (i + j) % 2 == 0 ? ColorRGBA.White : ColorRGBA.Black;
                rect(assets, panelX + i * 20, py + ph - 20 * (j + 1), 20, 20, c, 0.1f);
            }
        }
        title = text(font, base * 2.4f, ColorRGBA.White, panelX + 30, py + ph - 55);
        rowH = base * 1.35f;
        rowTop = py + ph - 170;
        BitmapText[] headers = {
                text(font, base, DIM, panelX + 30, rowTop + rowH), text(font, base, DIM, panelX + 100, rowTop + rowH),
                text(font, base, DIM, panelX + 290, rowTop + rowH), text(font, base, DIM, panelX + 365, rowTop + rowH),
                text(font, base, DIM, panelX + 440, rowTop + rowH), text(font, base, DIM, panelX + 615, rowTop + rowH)};
        String[] labels = {"POS", "DRIVER", "LAPS", "PITS", "TIME / GAP", "BEST LAP"};
        for (int i = 0; i < headers.length; i++) headers[i].setText(labels[i]);
        highlight = rect(assets, panelX + 15, rowTop, panelW - 30, rowH, new ColorRGBA(0.10f, 0.32f, 0.85f, 0.8f), 0.2f);
        for (int r = 0; r < ROWS; r++) {
            float y = rowTop - r * rowH;
            pos[r] = text(font, base * 1.05f, ColorRGBA.White, panelX + 30, y);
            name[r] = text(font, base * 1.05f, ColorRGBA.White, panelX + 100, y);
            laps[r] = text(font, base * 1.05f, ColorRGBA.White, panelX + 290, y);
            pits[r] = text(font, base * 1.05f, ColorRGBA.White, panelX + 365, y);
            time[r] = text(font, base * 1.05f, ColorRGBA.White, panelX + 440, y);
            best[r] = text(font, base * 1.05f, ColorRGBA.White, panelX + 615, y);
        }
        BitmapText hint = text(font, base, DIM, panelX + 30, py + 45);
        hint.setText("ENTER / ESC: back to the menu");
    }

    public Node getNode() { return root; }

    public void update(LapTracker tracker, LapTracker.Entry player) {
        boolean penalised = player.finished() && !tracker.stopsMet(player);
        title.setText(!player.finished() ? "CHEQUERED FLAG"
                : penalised ? String.format("MISSED PIT STOP  -  CLASSIFIED P%d", player.position())
                : String.format("CHEQUERED FLAG  -  YOU FINISHED P%d", player.position()));
        List<LapTracker.Entry> order = tracker.getOrder();
        LapTracker.Entry winner = order.get(0);
        List<LapTracker.Entry> shown = StandingsView.rows(order, player, ROWS);
        for (int r = 0; r < ROWS; r++) {
            if (r >= shown.size() || shown.get(r) == null) {
                pos[r].setText(r < shown.size() ? "..." : ""); name[r].setText(""); laps[r].setText(""); pits[r].setText("");
                time[r].setText(""); best[r].setText("");
                continue;
            }
            LapTracker.Entry e = shown.get(r);
            pos[r].setText("P" + e.position());
            name[r].setText(e.player ? "YOU" : e.name);
            laps[r].setText(Integer.toString(Math.min(e.lapsCompleted(), tracker.getRaceLaps())));
            int required = tracker.getRequiredStops();
            pits[r].setText(required > 0 ? e.pitStops() + "/" + required : Integer.toString(e.pitStops()));
            pits[r].setColor(tracker.stopsMet(e) ? ColorRGBA.White : new ColorRGBA(1f, 0.35f, 0.3f, 1f));
            String t;
            if (!e.finished()) t = "running";
            else if (!tracker.stopsMet(e)) t = "PIT PENALTY";
            else if (e == winner) t = Hud.formatTime(e.finishTime());
            else if (e.lapsCompleted() < winner.lapsCompleted()) t = "+" + (winner.lapsCompleted() - e.lapsCompleted()) + " lap";
            else t = String.format("+%.3fs", e.finishTime() - winner.finishTime());
            time[r].setText(t);
            best[r].setText(Hud.formatTime(e.bestLapTime()));
            if (e == player) highlight.setLocalTranslation(panelX + 15, rowTop - r * rowH - rowH + 4, 0.2f);
        }
    }

    private BitmapText text(BitmapFont font, float size, ColorRGBA color, float x, float y) {
        BitmapText t = new BitmapText(font);
        t.setSize(size);
        t.setColor(color);
        t.setLocalTranslation(x, y, 1);
        root.attachChild(t);
        return t;
    }

    private Geometry rect(AssetManager assets, float x, float y, float w, float h, ColorRGBA color, float z) {
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
}
