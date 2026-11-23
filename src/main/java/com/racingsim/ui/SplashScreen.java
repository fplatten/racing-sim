package com.racingsim.ui;

import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
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
import com.jme3.texture.Texture;

/**
 * Startup splash: a full-screen key art image (dimmed), the title and a loading bar with the
 * name of the step being loaded. Fades out once loading has finished.
 */
public final class SplashScreen {

    private static final ColorRGBA BAR = new ColorRGBA(1f, 0.80f, 0.20f, 1f);
    private static final ColorRGBA DIM = new ColorRGBA(0.80f, 0.82f, 0.86f, 1f);
    private static final float BAR_W = 640f, BAR_H = 12f;

    private final Node root = new Node("Splash");
    private final AssetManager assets;
    private final float width;
    private final Geometry bar;
    private final BitmapText status, percent;
    private final java.util.List<Material> fadeMaterials = new java.util.ArrayList<>();
    private final java.util.List<BitmapText> fadeTexts = new java.util.ArrayList<>();
    private final float barX;
    private float shown;     // displayed progress (eases toward the real value)

    public SplashScreen(AssetManager assets, BitmapFont font, float width, float height) {
        this.assets = assets;
        this.width = width;
        float base = font.getCharSet().getRenderedSize();

        rect(0, 0, width, height, ColorRGBA.Black, 0f, null);
        try {
            Texture art = assets.loadTexture("Textures/UI/splash.png");
            // cover the screen, keeping the image aspect ratio
            float iw = art.getImage().getWidth(), ih = art.getImage().getHeight();
            float scale = Math.max(width / iw, height / ih);
            rect((width - iw * scale) / 2, (height - ih * scale) / 2, iw * scale, ih * scale,
                    new ColorRGBA(0.62f, 0.62f, 0.62f, 1f), 0.1f, art);
        } catch (AssetNotFoundException e) {
            // plain black splash
        }
        // darker band at the bottom for the loading bar and a gradient-ish band behind the title
        rect(0, 0, width, 230, new ColorRGBA(0f, 0f, 0f, 0.72f), 0.2f, null);
        rect(0, height * 0.58f, width, 200, new ColorRGBA(0f, 0f, 0f, 0.35f), 0.2f, null);

        centered(font, "OVAL RACING SIM", base * 4.4f, ColorRGBA.White, height * 0.58f + 160);
        centered(font, "2.5-MILE OVAL   -   OPEN-WHEEL RACING   -   33-CAR FIELD", base * 1.1f,
                new ColorRGBA(1f, 0.85f, 0.35f, 1f), height * 0.58f + 62);

        barX = (width - BAR_W) / 2;
        float barY = 120;
        rect(barX - 3, barY - 3, BAR_W + 6, BAR_H + 6, new ColorRGBA(1, 1, 1, 0.18f), 0.3f, null);
        bar = rect(barX, barY, 1, BAR_H, BAR, 0.4f, null);
        status = text(font, "Starting...", base * 1.0f, DIM, barX, barY + BAR_H + 34);
        percent = text(font, "0%", base * 1.0f, ColorRGBA.White, barX + BAR_W - 40, barY + BAR_H + 34);
        BitmapText hint = text(font, "jMonkeyEngine  -  Minie physics  -  assets made in Blender", base * 0.8f,
                new ColorRGBA(0.6f, 0.62f, 0.66f, 1f), 0, 52);
        hint.setLocalTranslation(width / 2 - hint.getLineWidth() / 2, 52, 1);
    }

    public Node getNode() { return root; }

    /** @param progress 0..1  @param step what is being loaded right now */
    public void setProgress(float progress, String step) {
        status.setText(step);
        target = FastMath.clamp(progress, 0f, 1f);
    }

    private float target;

    /** Animates the bar (call every frame). @return the displayed progress */
    public float update(float tpf) {
        shown += (target - shown) * Math.min(1f, tpf * 12f);
        if (target - shown < 0.002f) shown = target;
        bar.setLocalScale(Math.max(1f, BAR_W * shown), BAR_H, 1);
        percent.setText(Math.round(shown * 100) + "%");
        percent.setLocalTranslation(barX + BAR_W - percent.getLineWidth(), percent.getLocalTranslation().y, 1);
        return shown;
    }

    /** Fade the whole splash: 1 = opaque, 0 = gone. */
    public void setOpacity(float a) {
        for (Material m : fadeMaterials) {
            ColorRGBA c = (ColorRGBA) m.getParamValue("Color");
            c.a = alphaOf(m) * a;
            m.setColor("Color", c);
        }
        for (BitmapText t : fadeTexts) t.setAlpha(a);
    }

    private final java.util.Map<Material, Float> baseAlpha = new java.util.HashMap<>();

    private float alphaOf(Material m) {
        return baseAlpha.getOrDefault(m, 1f);
    }

    private Geometry rect(float x, float y, float w, float h, ColorRGBA color, float z, Texture tex) {
        Geometry g = new Geometry("splashRect", new Quad(1, 1));
        Material m = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color.clone());
        if (tex != null) m.setTexture("ColorMap", tex);
        m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        g.setMaterial(m);
        g.setQueueBucket(RenderQueue.Bucket.Gui);
        g.setLocalTranslation(x, y, z);
        g.setLocalScale(w, h, 1);
        root.attachChild(g);
        fadeMaterials.add(m);
        baseAlpha.put(m, color.a);
        return g;
    }

    private BitmapText text(BitmapFont font, String s, float size, ColorRGBA color, float x, float top) {
        BitmapText t = new BitmapText(font);
        t.setSize(size);
        t.setColor(color);
        t.setText(s);
        t.setLocalTranslation(x, top, 1);
        root.attachChild(t);
        fadeTexts.add(t);
        return t;
    }

    private void centered(BitmapFont font, String s, float size, ColorRGBA color, float top) {
        BitmapText t = text(font, s, size, color, 0, top);
        t.setLocalTranslation(width / 2 - t.getLineWidth() / 2, top, 1);
    }
}
