package com.racingsim.track;

import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The sponsor boards on the grandstand front wall: one 40 m module (0.95 m tall) split into five
 * panels for fictional sponsors, each in its own colour scheme with a subtle sheen and slight
 * weathering. Drawn at start-up (the banner quad in {@code make_props.py} maps u = 0..1 along it).
 */
public final class BannerTexture {

    private static final String[] SPONSORS = {"APEX", "VELOCE", "TORQUE", "NOVA FUEL", "STRATOS"};
    private static final Color[][] SCHEMES = {
            {new Color(14, 40, 110), Color.WHITE},
            {new Color(240, 240, 236), new Color(200, 24, 30)},
            {new Color(20, 20, 22), new Color(250, 200, 20)},
            {new Color(196, 26, 30), Color.WHITE},
            {new Color(250, 196, 0), new Color(15, 15, 18)}};

    private BannerTexture() { }

    public static Texture2D create() {
        int w = 4096, h = 128;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int panels = SPONSORS.length;
        int pw = w / panels;
        // texels are ~0.73x as dense along the board as across it: widen the letters to match
        double stretch = (h / 0.95) / (w / 40.0);
        for (int i = 0; i < panels; i++) {
            int x0 = i * pw;
            Color bg = SCHEMES[i][0], fg = SCHEMES[i][1];
            g.setPaint(new GradientPaint(0, 0, bg.brighter(), 0, h, bg.darker()));
            g.fillRect(x0, 0, pw, h);
            g.setColor(new Color(0, 0, 0, 90));                     // panel joint
            g.fillRect(x0, 0, 3, h);
            Font f = new Font("Arial", Font.BOLD | Font.ITALIC, 10).deriveFont(h * 0.62f);
            GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), SPONSORS[i]);
            Rectangle2D b = gv.getVisualBounds();
            double sx = Math.min(1.0 / stretch, (pw * 0.8) / b.getWidth());
            AffineTransform t = new AffineTransform();
            t.translate(x0 + pw / 2.0, h / 2.0);
            t.scale(sx, 1.0);
            t.translate(-b.getCenterX(), -b.getCenterY());
            g.setColor(fg);
            g.fill(t.createTransformedShape(gv.getOutline()));
        }
        // weathering: faint streaks and grime towards the bottom edge
        Random rnd = new Random(4);
        for (int k = 0; k < 900; k++) {
            int x = rnd.nextInt(w), y = h / 2 + rnd.nextInt(h / 2);
            g.setColor(new Color(40, 36, 30, 10 + rnd.nextInt(25)));
            g.fillRect(x, y, 1 + rnd.nextInt(3), h - y);
        }
        g.setPaint(new GradientPaint(0, h * 0.75f, new Color(30, 28, 24, 0), 0, h, new Color(30, 28, 24, 90)));
        g.fillRect(0, 0, w, h);
        g.dispose();
        Texture2D tex = new Texture2D(new AWTLoader().load(img, false));
        tex.setMinFilter(Texture.MinFilter.Trilinear);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setAnisotropicFilter(8);
        tex.setWrap(Texture.WrapMode.EdgeClamp);
        return tex;
    }
}
