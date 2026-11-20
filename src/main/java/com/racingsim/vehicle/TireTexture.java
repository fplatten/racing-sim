package com.racingsim.vehicle;

import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Rubber albedo with raised yellow sidewall lettering for the lathe-mapped Blender tire
 * (u = around the circumference, v = across the profile from the inner to the outer bead).
 */
public final class TireTexture {

    private TireTexture() { }

    public static Texture2D create() {
        int w = 2048, h = 256;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Random rnd = new Random(11);
        for (int y = 0; y < h; y++) {
            float v = 1f - (y + 0.5f) / h;
            boolean tread = v > 0.36f && v < 0.64f;
            for (int x = 0; x < w; x++) {
                int base = tread ? 34 : 22;
                int n = base + rnd.nextInt(5);
                img.setRGB(x, y, new Color(n, n, n + 1).getRGB());
            }
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color yellow = new Color(236, 196, 40);
        // outer sidewall band v in [0.78, 0.96]  -> image rows (1 - v) * h
        drawBand(g, w, h, 0.78f, 0.95f, yellow, true);
        drawBand(g, w, h, 0.05f, 0.22f, yellow, false);
        g.dispose();
        Image image = new AWTLoader().load(img, false);
        Texture2D tex = new Texture2D(image);
        tex.setMinFilter(Texture.MinFilter.Trilinear);
        tex.setAnisotropicFilter(8);
        tex.setWrap(Texture.WrapMode.Repeat);
        return tex;
    }

    private static void drawBand(Graphics2D g, int w, int h, float v0, float v1, Color color, boolean flip) {
        double top = (1 - v1) * h, bottom = (1 - v0) * h;
        double bandH = bottom - top;
        Font font = new Font("Arial", Font.BOLD, 10).deriveFont((float) (bandH * 0.9));
        String[] words = {"APEX", "RACING SLICK"};
        g.setColor(color);
        for (int rep = 0; rep < 2; rep++) {
            for (int k = 0; k < words.length; k++) {
                GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), words[k]);
                Rectangle2D b = gv.getVisualBounds();
                double cx = w * (rep * 0.5 + 0.12 + k * 0.25);
                double cy = (top + bottom) / 2;
                AffineTransform at = new AffineTransform();
                at.translate(cx, cy);
                // Seen from outside the lathe u runs counter-clockwise and v grows towards the bead,
                // so upright text would land rotated by 180 degrees: pre-rotate it.
                if (flip) at.rotate(Math.PI);
                at.translate(-b.getCenterX(), -b.getCenterY());
                g.fill(at.createTransformedShape(gv.getOutline()));
            }
        }
    }
}
