package com.racingsim.vehicle;

import com.jme3.math.ColorRGBA;
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

/**
 * Paints a driver's helmet in the car's colors. The Blender helmet ({@code blender/make_car.py})
 * is UV mapped by azimuth and polar angle:
 * <pre>
 *   u = 0.5 + a / 2pi   (a = 0 straight ahead, +pi/2 toward the car's left)
 *   v = 1 - p / 2.4     (p = 0 at the crown)  ->  image row = p / 2.4 * height
 * </pre>
 * Every texel is mapped back to a direction on the shell, so the design is laid out in 3D (stripes
 * keep their width over the crown). Three designs are rotated through by race number.
 */
public final class HelmetPainter {

    private static final int W = 1024, H = 512, SS = 2;       // texture size, supersampling
    private static final String[] BROW = {"APEX", "VELOCE", "TORQUE", "NOVA", "STRATOS", "KINETIC"};

    private HelmetPainter() { }

    public static Texture2D paint(Livery livery) {
        Color base = awt(livery.secondary()), main = awt(livery.primary()), accent = awt(livery.accent());
        // a dark helmet on a dark car disappears: lift the base color to white
        if (lum(base) < 0.25f && lum(main) < 0.25f) base = new Color(235, 235, 235);
        int style = Math.floorMod(livery.number(), 3);
        Color gasket = new Color(22, 22, 25), brow = new Color(12, 12, 14), vent = new Color(40, 40, 44);

        int w = W * SS, h = H * SS;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            double p = (y + 0.5) / h * 2.4;
            for (int x = 0; x < w; x++) {
                double a = (x + 0.5) / w * 2 * Math.PI - Math.PI;
                double sx = Math.sin(p) * Math.sin(a);           // + = driver's left
                double fw = Math.sin(p) * Math.cos(a);           // + = forward
                double z = Math.cos(p);                          // + = up
                Color c = base;
                switch (style) {
                    case 0 -> {                                   // center stripe + lower band
                        double ax = Math.abs(sx);
                        if (z > -0.25 && ax < 0.16) c = main;
                        else if (z > -0.25 && ax < 0.20) c = accent;
                        if (z < -0.34) c = main;
                        else if (z < -0.29) c = accent;
                    }
                    case 1 -> {                                   // crown cap with a ring
                        if (z > 0.62) c = main;
                        else if (z > 0.52) c = accent;
                        if (z < -0.36 && fw < 0.4) c = accent;
                    }
                    default -> {                                  // swept side flash
                        double line = 0.05 - 0.55 * fw;
                        double d = Math.abs(z - line);
                        if (d < 0.11 && fw < 0.75) c = main;
                        else if (d < 0.15 && fw < 0.75) c = accent;
                        if (z > 0.80) c = main;
                    }
                }
                // eye port gasket (the visor sits on it), brow strip above it, chin vents
                double ea = Math.abs(a);
                double corner = Math.pow(ea / 1.24, 6) * 0.10;
                if (ea < 1.24 && p > 1.24 + corner && p < 1.80 - corner) c = gasket;
                if (ea < 0.98 && p > 1.115 && p < 1.235) c = brow;
                if (ea < 0.30 && p > 1.98 && p < 2.04 && Math.floorMod((int) (a * 40), 2) == 0) c = vent;
                px[y * w + x] = c.getRGB();
            }
        }
        BufferedImage big = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        big.setRGB(0, 0, w, h, px, 0, w);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(big, 0, 0, W, H, null);

        // sponsor on the brow strip; texels are ~1.3x denser vertically than around the shell
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        String word = BROW[Math.floorMod(livery.number() * 7 + livery.name().length(), BROW.length)];
        double rowTop = 1.115 / 2.4 * H, rowBottom = 1.235 / 2.4 * H;
        Font font = new Font("Arial", Font.BOLD, 10).deriveFont((float) ((rowBottom - rowTop) * 0.95));
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), word);
        Rectangle2D b = gv.getVisualBounds();
        AffineTransform t = new AffineTransform();
        t.translate(W / 2.0, (rowTop + rowBottom) / 2);
        t.scale(0.8, 1.0);
        t.translate(-b.getCenterX(), -b.getCenterY());
        g.setColor(new Color(240, 240, 240));
        g.fill(t.createTransformedShape(gv.getOutline()));
        // race number on the back of the helmet
        Font nf = new Font("Arial", Font.BOLD | Font.ITALIC, 10).deriveFont(H * 0.075f);
        GlyphVector ng = nf.createGlyphVector(g.getFontRenderContext(), Integer.toString(livery.number()));
        Rectangle2D nb = ng.getVisualBounds();
        for (double u : new double[]{0.0, 1.0}) {                     // a = +-pi wraps across the seam
            AffineTransform nt = new AffineTransform();
            nt.translate(u * W, 1.35 / 2.4 * H);
            nt.scale(0.8, 1.0);
            nt.translate(-nb.getCenterX(), -nb.getCenterY());
            g.setColor(lum(base) > 0.5f ? new Color(20, 20, 24) : Color.WHITE);
            g.fill(nt.createTransformedShape(ng.getOutline()));
        }
        g.dispose();

        Image image = new AWTLoader().load(img, false);
        Texture2D tex = new Texture2D(image);
        tex.setMinFilter(Texture.MinFilter.Trilinear);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setAnisotropicFilter(8);
        tex.setWrap(Texture.WrapAxis.S, Texture.WrapMode.Repeat);
        tex.setWrap(Texture.WrapAxis.T, Texture.WrapMode.EdgeClamp);
        return tex;
    }

    private static Color awt(ColorRGBA c) {
        return new Color(Math.min(1f, Math.max(0f, c.r)), Math.min(1f, Math.max(0f, c.g)), Math.min(1f, Math.max(0f, c.b)));
    }

    private static float lum(Color c) {
        return (0.2126f * c.getRed() + 0.7152f * c.getGreen() + 0.0722f * c.getBlue()) / 255f;
    }
}
