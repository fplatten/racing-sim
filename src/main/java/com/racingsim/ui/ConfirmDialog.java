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

/** Modal yes / no prompt drawn over everything: a title and the two key choices (Enter / Esc). */
public final class ConfirmDialog {

    private static final ColorRGBA PANEL = new ColorRGBA(0.02f, 0.03f, 0.06f, 0.97f);
    private static final ColorRGBA ACCENT = new ColorRGBA(0.85f, 0.18f, 0.15f, 1f);
    private static final ColorRGBA DIM = new ColorRGBA(0.75f, 0.77f, 0.80f, 1f);

    private final Node root = new Node("Confirm");
    private final AssetManager assets;
    private final float width;
    private final BitmapText title, message, enterLine, escLine;

    public ConfirmDialog(AssetManager assets, BitmapFont font, float width, float height) {
        this.assets = assets;
        this.width = width;
        float base = font.getCharSet().getRenderedSize();
        rect(0, 0, width, height, new ColorRGBA(0, 0, 0, 0.6f), 5f);        // dims the screen
        float pw = 640, ph = 250, px = (width - pw) / 2, py = (height - ph) / 2;
        rect(px, py, pw, ph, PANEL, 5.1f);
        rect(px, py + ph - 6, pw, 6, ACCENT, 5.2f);
        title = text(font, base * 2.0f, ColorRGBA.White, py + ph - 30);
        message = text(font, base * 1.0f, DIM, py + ph - 90);
        enterLine = text(font, base * 1.15f, ColorRGBA.White, py + 95);
        escLine = text(font, base * 1.15f, ColorRGBA.White, py + 60);
    }

    public Node getNode() { return root; }

    public void show(String titleText, String messageText, String enterText, String escText) {
        set(title, titleText);
        set(message, messageText);
        set(enterLine, "ENTER   " + enterText);
        set(escLine, "ESC   " + escText);
    }

    private void set(BitmapText t, String s) {
        t.setText(s);
        t.setLocalTranslation(width / 2 - t.getLineWidth() / 2, t.getLocalTranslation().y, 6f);
    }

    private BitmapText text(BitmapFont font, float size, ColorRGBA color, float top) {
        BitmapText t = new BitmapText(font);
        t.setSize(size);
        t.setColor(color);
        t.setLocalTranslation(0, top, 6f);
        root.attachChild(t);
        return t;
    }

    private void rect(float x, float y, float w, float h, ColorRGBA color, float z) {
        Geometry g = new Geometry("confirmRect", new Quad(1, 1));
        Material m = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color.clone());
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        g.setMaterial(m);
        g.setQueueBucket(RenderQueue.Bucket.Gui);
        g.setLocalTranslation(x, y, z);
        g.setLocalScale(w, h, 1);
        root.attachChild(g);
    }
}
