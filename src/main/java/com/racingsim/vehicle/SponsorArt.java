package com.racingsim.vehicle;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Loud die-cut sponsor stickers for the liveries: each fictional brand has a garish colour scheme,
 * its own cartoon mascot and a lettering layout (straight, arched or growing). There is no plate:
 * black and neon contours are grown around the letters and the mascot, so the sticker's edge
 * follows their shapes. Everything is drawn with Java2D shapes (no image files).
 */
final class SponsorArt {

    enum Mascot { TIRE, FLAME, BOLT, DROP, SUN, FISH, ROBOT, MOUNTAIN }

    /** bg = plate colour, text = lettering, accent = stripe / mascot body, mascot = character. */
    record Brand(String name, Color bg, Color text, Color accent, Mascot mascot) { }

    private static Color c(int r, int g, int b) {
        return new Color(r, g, b);
    }

    static final Brand[] BRANDS = {
            new Brand("APEX", c(255, 20, 147), c(255, 240, 0), c(30, 30, 34), Mascot.TIRE),
            new Brand("VELOCE", c(120, 255, 0), c(235, 0, 200), c(255, 230, 0), Mascot.BOLT),
            new Brand("TORQUE", c(255, 110, 0), c(255, 255, 255), c(30, 30, 34), Mascot.TIRE),
            new Brand("NOVA FUEL", c(140, 0, 255), c(255, 236, 0), c(255, 120, 0), Mascot.FLAME),
            new Brand("STRATOS", c(0, 225, 255), c(255, 20, 147), c(255, 220, 0), Mascot.SUN),
            new Brand("KINETIC", c(255, 235, 0), c(230, 0, 30), c(255, 245, 120), Mascot.BOLT),
            new Brand("HALCYON", c(0, 215, 170), c(255, 240, 0), c(255, 200, 0), Mascot.SUN),
            new Brand("MERIDIAN", c(255, 0, 170), c(150, 255, 0), c(120, 70, 220), Mascot.MOUNTAIN),
            new Brand("VORTEX OIL", c(57, 255, 20), c(18, 18, 20), c(25, 25, 28), Mascot.DROP),
            new Brand("ZENITH", c(235, 0, 40), c(255, 236, 0), c(255, 200, 0), Mascot.SUN),
            new Brand("IRONCLAD", c(0, 80, 255), c(255, 140, 0), c(175, 180, 190), Mascot.ROBOT),
            new Brand("BLUEFIN", c(200, 255, 0), c(0, 70, 255), c(0, 120, 255), Mascot.FISH),
            new Brand("SUMMIT", c(255, 140, 0), c(110, 0, 220), c(120, 70, 220), Mascot.MOUNTAIN),
            new Brand("CASCADE", c(0, 170, 255), c(255, 240, 0), c(40, 120, 255), Mascot.DROP),
            new Brand("RADIANT", c(255, 50, 0), c(0, 240, 255), c(255, 150, 0), Mascot.FLAME),
            new Brand("TITAN TOOLS", c(255, 210, 0), c(20, 20, 22), c(175, 180, 190), Mascot.ROBOT)};

    private static final Color INK = new Color(12, 12, 14);

    private SponsorArt() { }

    /**
     * Draws an ad panel centred at pixel (cx, cy), w x h pixels, rotated by {@code rotation}
     * (the panel's "up" follows the rotation, like text).
     */
    static void panel(Graphics2D g0, Brand brand, double cx, double cy, double w, double h, double rotation,
                      boolean mascot) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(cx, cy);
        g.rotate(rotation);
        g.translate(-w / 2, -h / 2);
        double pad = h * 0.18;                                          // room for the contours

        // ---- mascot square on the left, overlapping the start of the word
        double ms = mascot ? h * 1.10 : 0;
        double mx = pad * 0.6, my = h / 2 - ms / 2;
        double textX0 = mascot ? mx + ms * 0.97 : pad;
        double textW = w - textX0 - pad, textH = h - pad;

        // ---- lettering laid out per brand, then fitted into the free space
        Font font = new Font("Arial Black", Font.ITALIC, 10).deriveFont(100f);
        if (!"Arial Black".equals(font.getFamily())) font = new Font("Arial", Font.BOLD | Font.ITALIC, 10).deriveFont(100f);
        GlyphVector gv = font.createGlyphVector(g.getFontRenderContext(), brand.name());
        Rectangle2D vb = gv.getVisualBounds();
        Layout layout = Layout.values()[Math.floorMod(brand.name().hashCode(), 3)];
        Area word = new Area();
        for (int i = 0; i < gv.getNumGlyphs(); i++) {
            Shape glyph = gv.getGlyphOutline(i);
            Rectangle2D gb = glyph.getBounds2D();
            if (gb.isEmpty()) continue;                                  // spaces
            double u = (gb.getCenterX() - vb.getCenterX()) / vb.getWidth();   // -0.5 .. 0.5 along the word
            AffineTransform t = new AffineTransform();
            switch (layout) {
                case ARC -> {                                            // bowed up in the middle
                    double bend = vb.getHeight() * 0.55;
                    double slope = bend * 8 * u / vb.getWidth();
                    t.translate(gb.getCenterX(), -bend * (1 - 4 * u * u));
                    t.rotate(Math.atan(slope));
                    t.translate(-gb.getCenterX(), 0);
                }
                case RISING -> {                                         // letters grow towards the end
                    double sc = 0.78 + 0.44 * (u + 0.5);
                    t.translate(gb.getCenterX(), 0);
                    t.scale(sc, sc);
                    t.translate(-gb.getCenterX(), 0);
                }
                default -> { }
            }
            word.add(new Area(t.createTransformedShape(glyph)));
        }
        Rectangle2D wb = word.getBounds2D();
        double fit = Math.min(textW / wb.getWidth(), textH / wb.getHeight());
        // the word starts right after the mascot; a short word leaves room, so centre the pair
        double shift = (textW - wb.getWidth() * fit) / 2;
        mx += shift;
        AffineTransform place = new AffineTransform();
        place.translate(textX0 + shift, h / 2);
        place.scale(fit, fit);
        place.translate(-wb.getMinX(), -wb.getCenterY());
        word.transform(place);

        // ---- die-cut contours around word + mascot: the sticker takes their shape
        Area shape = new Area(word);
        if (mascot) {
            AffineTransform mt = new AffineTransform(ms, 0, 0, ms, mx, my);
            shape.add(new Area(mt.createTransformedShape(silhouette(brand.mascot()))));
        }
        Area outer = grow(shape, h * 0.17), inner = grow(shape, h * 0.11);
        g.setColor(new Color(0, 0, 0, 90));                               // soft drop shadow
        g.fill(AffineTransform.getTranslateInstance(h * 0.05, h * 0.06).createTransformedShape(outer));
        g.setColor(INK);
        g.fill(outer);
        Rectangle2D ib = inner.getBounds2D();
        g.setPaint(new GradientPaint(0, (float) ib.getMinY(), brand.bg().brighter(), 0, (float) ib.getMaxY(), brand.bg()));
        g.fill(inner);
        g.setColor(new Color(255, 255, 255, 110));                        // thin highlight inside the cut line
        g.setStroke(new BasicStroke((float) Math.max(1.0, h * 0.02)));
        g.draw(inner);

        // ---- lettering: thick black outline, then the fill (dark letters get a white rim)
        float edge = (float) Math.max(2.0, h * 0.10);
        g.setStroke(new BasicStroke(edge, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(INK);
        g.draw(word);
        Color tc = brand.text();
        if (0.2126 * tc.getRed() + 0.7152 * tc.getGreen() + 0.0722 * tc.getBlue() < 80) {
            g.setStroke(new BasicStroke(edge * 0.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.WHITE);
            g.draw(word);
        }
        g.setColor(tc);
        g.fill(word);
        if (mascot) drawMascot(g, brand, mx, my, ms);
        g.dispose();
    }

    /** How the lettering is laid out: straight, bowed up on an arc, or growing letter by letter. */
    private enum Layout { STRAIGHT, ARC, RISING }

    /** The area grown outwards by r (round corners). */
    private static Area grow(Area a, double r) {
        Area out = new Area(new BasicStroke((float) (2 * r), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(a));
        out.add(a);
        return out;
    }

    /** Outline of each mascot in its unit square (for the sticker contour). */
    private static Shape silhouette(Mascot m) {
        switch (m) {
            case TIRE:
                return new Ellipse2D.Double(0.06, 0.06, 0.88, 0.88);
            case SUN: {
                Path2D.Double star = new Path2D.Double();
                for (int k = 0; k < 24; k++) {
                    double a = k * Math.PI / 12, r = k % 2 == 0 ? 0.49 : 0.33;
                    if (k == 0) star.moveTo(0.5 + Math.cos(a) * r, 0.5 + Math.sin(a) * r);
                    else star.lineTo(0.5 + Math.cos(a) * r, 0.5 + Math.sin(a) * r);
                }
                star.closePath();
                return star;
            }
            case FISH: {
                Area a = new Area(new Ellipse2D.Double(0.04, 0.24, 0.74, 0.52));
                Path2D.Double tail = new Path2D.Double();
                tail.moveTo(0.70, 0.50);
                tail.lineTo(0.98, 0.25);
                tail.lineTo(0.92, 0.50);
                tail.lineTo(0.98, 0.75);
                tail.closePath();
                Path2D.Double fin = new Path2D.Double();
                fin.moveTo(0.36, 0.30);
                fin.lineTo(0.52, 0.08);
                fin.lineTo(0.60, 0.32);
                fin.closePath();
                a.add(new Area(tail));
                a.add(new Area(fin));
                return a;
            }
            case ROBOT: {
                Area a = new Area(new RoundRectangle2D.Double(0.14, 0.18, 0.72, 0.66, 0.18, 0.18));
                a.add(new Area(new RoundRectangle2D.Double(0.02, 0.40, 0.96, 0.22, 0.05, 0.05)));
                a.add(new Area(new Ellipse2D.Double(0.44, 0.0, 0.12, 0.12)));
                a.add(new Area(new Rectangle2D.Double(0.48, 0.06, 0.04, 0.14)));
                return a;
            }
            case FLAME:
                return flame();
            case BOLT:
                return bolt();
            case DROP:
                return drop();
            default:
                return mountain();
        }
    }

    private static Path2D.Double flame() {
        Path2D.Double f = new Path2D.Double();
        f.moveTo(0.5, 0.02);
        f.curveTo(0.62, 0.22, 0.95, 0.35, 0.86, 0.68);
        f.curveTo(0.80, 0.92, 0.62, 0.98, 0.5, 0.98);
        f.curveTo(0.30, 0.98, 0.12, 0.88, 0.14, 0.62);
        f.curveTo(0.16, 0.42, 0.34, 0.38, 0.30, 0.14);
        f.curveTo(0.42, 0.24, 0.44, 0.10, 0.5, 0.02);
        f.closePath();
        return f;
    }

    private static Path2D.Double bolt() {
        Path2D.Double b = new Path2D.Double();
        b.moveTo(0.56, 0.0);
        b.lineTo(0.06, 0.60);
        b.lineTo(0.42, 0.60);
        b.lineTo(0.26, 1.0);
        b.lineTo(0.96, 0.36);
        b.lineTo(0.58, 0.36);
        b.lineTo(0.84, 0.0);
        b.closePath();
        return b;
    }

    private static Path2D.Double drop() {
        Path2D.Double d = new Path2D.Double();
        d.moveTo(0.5, 0.02);
        d.curveTo(0.62, 0.25, 0.88, 0.45, 0.88, 0.66);
        d.curveTo(0.88, 0.88, 0.70, 0.98, 0.5, 0.98);
        d.curveTo(0.30, 0.98, 0.12, 0.88, 0.12, 0.66);
        d.curveTo(0.12, 0.45, 0.38, 0.25, 0.5, 0.02);
        d.closePath();
        return d;
    }

    private static Path2D.Double mountain() {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(0.02, 0.95);
        p.lineTo(0.50, 0.06);
        p.lineTo(0.98, 0.95);
        p.closePath();
        return p;
    }

    // ------------------------------------------------------------------ mascots

    /** Draws the brand's mascot in the square (x, y, s, s). */
    static void drawMascot(Graphics2D g0, Brand brand, double x, double y, double s) {
        Graphics2D g = (Graphics2D) g0.create();
        g.translate(x, y);
        g.scale(s, s);                                                  // unit square from here on
        float line = (float) Math.max(0.045, 1.6 / s);
        g.setStroke(new BasicStroke(line, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Color body = brand.accent();
        switch (brand.mascot()) {
            case TIRE -> {
                outlined(g, new Ellipse2D.Double(0.06, 0.06, 0.88, 0.88), new Color(28, 28, 30));
                outlined(g, new Ellipse2D.Double(0.27, 0.27, 0.46, 0.46), new Color(190, 192, 198));
                for (int k = 0; k < 10; k++) {                         // tread blocks
                    double a = k * Math.PI / 5;
                    g.setColor(new Color(60, 60, 64));
                    g.fill(new Ellipse2D.Double(0.5 + Math.cos(a) * 0.40 - 0.03, 0.5 + Math.sin(a) * 0.40 - 0.03, 0.06, 0.06));
                }
                eyes(g, 0.38, 0.62, 0.43, 0.11);
                grin(g, 0.5, 0.58, 0.16);
            }
            case FLAME -> {
                Path2D.Double f = new Path2D.Double();
                f.moveTo(0.5, 0.02);
                f.curveTo(0.62, 0.22, 0.95, 0.35, 0.86, 0.68);
                f.curveTo(0.80, 0.92, 0.62, 0.98, 0.5, 0.98);
                f.curveTo(0.30, 0.98, 0.12, 0.88, 0.14, 0.62);
                f.curveTo(0.16, 0.42, 0.34, 0.38, 0.30, 0.14);
                f.curveTo(0.42, 0.24, 0.44, 0.10, 0.5, 0.02);
                f.closePath();
                outlined(g, f, body);
                Path2D.Double inner = new Path2D.Double();
                inner.moveTo(0.5, 0.42);
                inner.curveTo(0.66, 0.58, 0.74, 0.70, 0.66, 0.84);
                inner.curveTo(0.58, 0.95, 0.40, 0.95, 0.34, 0.84);
                inner.curveTo(0.28, 0.70, 0.42, 0.62, 0.5, 0.42);
                g.setColor(new Color(255, 240, 80));
                g.fill(inner);
                eyes(g, 0.40, 0.60, 0.55, 0.10);
                grin(g, 0.5, 0.70, 0.12);
            }
            case BOLT -> {
                Path2D.Double b = new Path2D.Double();
                b.moveTo(0.56, 0.0);
                b.lineTo(0.06, 0.60);
                b.lineTo(0.42, 0.60);
                b.lineTo(0.26, 1.0);
                b.lineTo(0.96, 0.36);
                b.lineTo(0.58, 0.36);
                b.lineTo(0.84, 0.0);
                b.closePath();
                outlined(g, b, body);
                eyes(g, 0.36, 0.56, 0.46, 0.075);
                g.setColor(INK);                                         // determined eyebrows
                g.draw(new java.awt.geom.Line2D.Double(0.29, 0.35, 0.41, 0.38));
                g.draw(new java.awt.geom.Line2D.Double(0.63, 0.35, 0.51, 0.38));
                grin(g, 0.44, 0.57, 0.09);
            }
            case DROP -> {
                Path2D.Double d = new Path2D.Double();
                d.moveTo(0.5, 0.02);
                d.curveTo(0.62, 0.25, 0.88, 0.45, 0.88, 0.66);
                d.curveTo(0.88, 0.88, 0.70, 0.98, 0.5, 0.98);
                d.curveTo(0.30, 0.98, 0.12, 0.88, 0.12, 0.66);
                d.curveTo(0.12, 0.45, 0.38, 0.25, 0.5, 0.02);
                d.closePath();
                outlined(g, d, body);
                g.setColor(new Color(255, 255, 255, 170));                // shine
                g.fill(new Ellipse2D.Double(0.24, 0.48, 0.10, 0.20));
                eyes(g, 0.40, 0.62, 0.60, 0.10);
                grin(g, 0.51, 0.74, 0.13);
            }
            case SUN -> {
                g.setColor(body);
                for (int k = 0; k < 12; k++) {
                    double a = k * Math.PI / 6;
                    Path2D.Double ray = new Path2D.Double();
                    ray.moveTo(0.5 + Math.cos(a - 0.17) * 0.32, 0.5 + Math.sin(a - 0.17) * 0.32);
                    ray.lineTo(0.5 + Math.cos(a) * 0.49, 0.5 + Math.sin(a) * 0.49);
                    ray.lineTo(0.5 + Math.cos(a + 0.17) * 0.32, 0.5 + Math.sin(a + 0.17) * 0.32);
                    ray.closePath();
                    outlined(g, ray, new Color(255, 150, 0));
                }
                outlined(g, new Ellipse2D.Double(0.18, 0.18, 0.64, 0.64), body);
                // cool shades
                Path2D.Double shades = new Path2D.Double();
                shades.moveTo(0.24, 0.40);
                shades.lineTo(0.76, 0.40);
                shades.lineTo(0.72, 0.52);
                shades.lineTo(0.56, 0.52);
                shades.lineTo(0.5, 0.45);
                shades.lineTo(0.44, 0.52);
                shades.lineTo(0.28, 0.52);
                shades.closePath();
                g.setColor(INK);
                g.fill(shades);
                g.setColor(new Color(120, 200, 255));
                g.fill(new Rectangle2D.Double(0.30, 0.42, 0.06, 0.03));
                g.fill(new Rectangle2D.Double(0.60, 0.42, 0.06, 0.03));
                grin(g, 0.5, 0.60, 0.13);
            }
            case FISH -> {
                Path2D.Double tail = new Path2D.Double();
                tail.moveTo(0.70, 0.50);
                tail.lineTo(0.98, 0.25);
                tail.lineTo(0.92, 0.50);
                tail.lineTo(0.98, 0.75);
                tail.closePath();
                outlined(g, tail, body.darker());
                Path2D.Double fin = new Path2D.Double();
                fin.moveTo(0.36, 0.30);
                fin.lineTo(0.52, 0.08);
                fin.lineTo(0.60, 0.32);
                fin.closePath();
                outlined(g, fin, body.darker());
                outlined(g, new Ellipse2D.Double(0.04, 0.24, 0.74, 0.52), body);
                g.setColor(new Color(235, 245, 255));                    // belly
                g.fill(new Arc2D.Double(0.10, 0.40, 0.62, 0.32, 200, 140, Arc2D.CHORD));
                eyes(g, 0.24, 0.24, 0.42, 0.10);
                // toothy grin
                g.setColor(INK);
                g.fill(new Arc2D.Double(0.06, 0.48, 0.30, 0.16, 180, 180, Arc2D.CHORD));
                g.setColor(Color.WHITE);
                for (int k = 0; k < 4; k++) {
                    Path2D.Double t = new Path2D.Double();
                    double tx = 0.09 + k * 0.06;
                    t.moveTo(tx, 0.56);
                    t.lineTo(tx + 0.05, 0.56);
                    t.lineTo(tx + 0.025, 0.61);
                    t.closePath();
                    g.fill(t);
                }
            }
            case ROBOT -> {
                g.setColor(INK);
                g.draw(new java.awt.geom.Line2D.Double(0.5, 0.18, 0.5, 0.05));
                outlined(g, new Ellipse2D.Double(0.44, 0.0, 0.12, 0.12), new Color(255, 40, 40));
                outlined(g, new RoundRectangle2D.Double(0.14, 0.18, 0.72, 0.66, 0.18, 0.18), body);
                outlined(g, new RoundRectangle2D.Double(0.02, 0.40, 0.12, 0.22, 0.05, 0.05), body.darker());
                outlined(g, new RoundRectangle2D.Double(0.86, 0.40, 0.12, 0.22, 0.05, 0.05), body.darker());
                outlined(g, new RoundRectangle2D.Double(0.24, 0.32, 0.20, 0.16, 0.05, 0.05), new Color(0, 240, 255));
                outlined(g, new RoundRectangle2D.Double(0.56, 0.32, 0.20, 0.16, 0.05, 0.05), new Color(0, 240, 255));
                outlined(g, new RoundRectangle2D.Double(0.28, 0.60, 0.44, 0.13, 0.04, 0.04), Color.WHITE);
                g.setColor(INK);
                for (int k = 1; k < 4; k++) g.draw(new java.awt.geom.Line2D.Double(0.28 + k * 0.11, 0.60, 0.28 + k * 0.11, 0.73));
            }
            case MOUNTAIN -> {
                Path2D.Double m = new Path2D.Double();
                m.moveTo(0.02, 0.95);
                m.lineTo(0.50, 0.06);
                m.lineTo(0.98, 0.95);
                m.closePath();
                outlined(g, m, body);
                Path2D.Double snow = new Path2D.Double();
                snow.moveTo(0.36, 0.32);
                snow.lineTo(0.50, 0.06);
                snow.lineTo(0.64, 0.32);
                snow.lineTo(0.57, 0.27);
                snow.lineTo(0.50, 0.34);
                snow.lineTo(0.43, 0.27);
                snow.closePath();
                outlined(g, snow, Color.WHITE);
                eyes(g, 0.41, 0.59, 0.56, 0.10);
                grin(g, 0.5, 0.70, 0.14);
            }
        }
        g.dispose();
    }

    private static void outlined(Graphics2D g, Shape s, Color fill) {
        g.setColor(fill);
        g.fill(s);
        g.setColor(INK);
        g.draw(s);
    }

    /** Two big cartoon eyes (white, black pupil looking slightly up and to the right, glint). */
    private static void eyes(Graphics2D g, double lx, double rx, double y, double r) {
        for (double x : new double[]{lx, rx}) {
            outlined(g, new Ellipse2D.Double(x - r, y - r * 1.15, 2 * r, 2.3 * r), Color.WHITE);
            g.setColor(INK);
            g.fill(new Ellipse2D.Double(x - r * 0.35, y - r * 0.75, r * 0.9, r * 1.1));
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - r * 0.05, y - r * 0.6, r * 0.3, r * 0.3));
        }
    }

    /** A wide open grin with a tongue. */
    private static void grin(Graphics2D g, double cx, double y, double w) {
        Arc2D.Double mouth = new Arc2D.Double(cx - w, y - w * 0.6, 2 * w, w * 1.6, 180, 180, Arc2D.CHORD);
        outlined(g, mouth, new Color(120, 0, 20));
        g.setColor(new Color(255, 90, 110));
        g.fill(new Ellipse2D.Double(cx - w * 0.45, y + w * 0.35, w * 0.9, w * 0.45));
        g.setColor(INK);
        g.draw(mouth);
    }

    static Brand brand(String name) {
        for (Brand b : BRANDS) if (b.name().equals(name)) return b;
        return BRANDS[0];
    }
}
