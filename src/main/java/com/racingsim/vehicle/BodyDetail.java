package com.racingsim.vehicle;

import com.jme3.texture.Texture2D;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Car-independent surface detail for the livery UV sheet, built once and shared by every car:
 * <ul>
 *   <li>a normal map with the bodywork's panel seams and Dzus fasteners,</li>
 *   <li>a packed AO / roughness / metallic map (glossy clear coat, rougher where the car is dirty),</li>
 *   <li>wear masks that {@link LiveryPainter} blends into each car's colours: road grime low on the
 *       sides, rubber "marbles" thrown up by the tyres, stone chips on the nose and front wing and
 *       exhaust / brake soot at the back.</li>
 * </ul>
 * Layout and coordinates as in {@link LiveryPainter}.
 */
public final class BodyDetail {

    static final float Y_MIN = -2.5f, Y_MAX = 2.6f, Z_MAX = 1.25f, X_MAX = 1.0f;
    static final float L = Y_MAX - Y_MIN;
    static final int SIZE = 2048;

    /** Wear masks in [0, 1], SIZE x SIZE, row 0 = top of the texture. */
    final float[] grime, marbles, chips, soot;
    final Texture2D normal, aoRoughMetal;

    private static BodyDetail instance;

    public static synchronized BodyDetail get() {
        if (instance == null) instance = new BodyDetail();
        return instance;
    }

    /** Island transforms: car metres (y, z) or (y, x) to texture pixels. side 0 = left, 1 = right. */
    static AffineTransform side(int side, int size) {
        return side == 0
                ? new AffineTransform(size / L, 0, 0, -0.25 * size / Z_MAX, -Y_MIN * size / L, 0.25 * size)
                : new AffineTransform(-size / L, 0, 0, -0.25 * size / Z_MAX, Y_MAX * size / L, 0.50 * size);
    }

    static AffineTransform top(int size) {
        return new AffineTransform(size / L, 0, 0, 0.2 * size / X_MAX, -Y_MIN * size / L, 0.7 * size);
    }

    private BodyDetail() {
        int n = SIZE;
        grime = new float[n * n];
        marbles = new float[n * n];
        chips = new float[n * n];
        soot = new float[n * n];
        Random rnd = new Random(1234);
        float[] noise = TireTexture.smoothNoise(n, n, 96, 96, rnd);
        float[] fine = TireTexture.smoothNoise(n, n, 512, 512, rnd);

        // ------------------------------------------------ wear masks from car coordinates
        for (int r = 0; r < n; r++) {
            float vb = 1f - (r + 0.5f) / n;
            for (int c = 0; c < n; c++) {
                float u = (c + 0.5f) / n;
                int i = r * n + c;
                float nz = noise[i], fz = fine[i];
                float g = 0f, s = 0f, marbleDensity = 0f, chipDensity = 0f;
                if (vb >= 0.5f) {                                          // side islands
                    float z = (vb - (vb >= 0.75f ? 0.75f : 0.5f)) * 4f * Z_MAX;
                    float y = vb >= 0.75f ? Y_MIN + u * L : Y_MAX - u * L;
                    float low = TireTexture.clamp01(1f - z / 0.24f);
                    // grime between the wheels, heaviest just behind the fronts and ahead of the rears
                    float between = TireTexture.band(y, -1.40f, 1.10f, 0.25f);
                    float hot = Math.max(TireTexture.band(y, -1.40f, -0.55f, 0.3f), TireTexture.band(y, 0.45f, 1.10f, 0.3f));
                    g = low * between * (0.45f + 0.45f * hot) * (0.55f + 0.9f * (nz - 0.5f));
                    marbleDensity = low * hot * 0.020f + TireTexture.band(y, 0.40f, 1.45f, 0.2f)
                            * TireTexture.clamp01(1f - z / 0.42f) * 0.010f;
                    // front wing endplates and nose: stone chips; dust on the endplate feet
                    if (y < -1.85f && z < 0.32f) {
                        chipDensity = 0.010f;
                        g = Math.max(g, TireTexture.clamp01(1f - z / 0.12f) * 0.5f * nz);
                    }
                    if (y < -2.05f) chipDensity = Math.max(chipDensity, 0.008f);
                    // gearbox / diffuser soot and dirty rear endplate feet
                    if (y > 1.75f) s = TireTexture.clamp01(1f - (z - 0.20f) / 0.35f) * (0.45f + 0.6f * (nz - 0.5f));
                } else if (vb >= 0.10f) {                                  // top island
                    float x = X_MAX - (vb - 0.10f) / 0.40f * 2f * X_MAX;
                    float y = Y_MIN + u * L;
                    float ax = Math.abs(x);
                    g = 0.10f * nz;                                        // light road dust everywhere
                    if (y < -1.90f && ax > 0.10f) {                        // front wing upper surfaces
                        g = Math.max(g, 0.22f * nz);
                        chipDensity = y < -2.28f ? 0.030f : 0.006f;      // leading edge takes the hits
                    }
                    if (y < -2.25f && ax < 0.10f) chipDensity = 0.020f;   // nose tip
                    if (ax > 0.42f && y > 0.55f && y < 1.45f) {            // sidepod tops ahead of the rears
                        marbleDensity = 0.010f * TireTexture.band(ax, 0.45f, 0.85f, 0.1f);
                        g = Math.max(g, 0.30f * nz);
                    }
                    if (y > 1.85f && ax < 0.16f) s = 0.4f * nz;           // gearbox cover
                } else {                                                   // underside
                    g = 0.7f;
                }
                grime[i] = TireTexture.clamp01(g + 0.1f * (fz - 0.5f) * (g > 0 ? 1 : 0));
                soot[i] = TireTexture.clamp01(s);
                if (marbleDensity > 0f && rnd.nextFloat() < marbleDensity * 0.12f) stamp(marbles, c, r, 1 + rnd.nextInt(2), rnd, 0.8f);
                if (chipDensity > 0f && rnd.nextFloat() < chipDensity * 0.25f) stamp(chips, c, r, 1 + rnd.nextInt(2), rnd, 0.8f);
            }
        }

        // ------------------------------------------------ panel seams + fasteners (height field)
        BufferedImage seams = new BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = seams.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(Color.WHITE);
        for (int sd = 0; sd < 2; sd++) {
            Graphics2D s = (Graphics2D) g.create();
            s.clip(new java.awt.Rectangle(0, sd == 0 ? 0 : n / 4, n, n / 4));
            s.transform(side(sd, n));
            s.setStroke(new BasicStroke(0.0045f));
            s.draw(new Line2D.Double(-1.20, 0.10, -1.20, 0.56));            // nose cone joint
            s.draw(new Line2D.Double(0.32, 0.40, 0.32, 0.86));              // engine cover front edge
            s.draw(poly(-0.66, 0.425, 0.0, 0.43, 0.60, 0.41, 1.05, 0.36));   // sidepod top seam
            s.draw(new Line2D.Double(0.98, 0.14, 0.98, 0.37));              // sidepod access panel
            s.draw(poly(0.32, 0.86, 0.9, 0.73, 1.35, 0.60, 1.90, 0.47));     // engine cover / gearbox split
            fasteners(s, -1.20, 0.16, -1.20, 0.50, 4);
            fasteners(s, -0.60, 0.40, 1.00, 0.35, 9);
            fasteners(s, 0.32, 0.46, 0.32, 0.80, 3);
            s.dispose();
        }
        Graphics2D t = (Graphics2D) g.create();
        t.clip(new java.awt.Rectangle(0, n / 2, n, (int) (0.4 * n)));
        t.transform(top(n));
        t.setStroke(new BasicStroke(0.0045f));
        t.draw(new Line2D.Double(-1.20, -0.26, -1.20, 0.26));                // nose cone joint
        t.draw(poly(0.30, -0.34, 1.40, -0.21, 1.95, -0.12, 1.95, 0.12, 1.40, 0.21, 0.30, 0.34, 0.30, -0.34));
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            t.draw(poly(-0.55, sgn * 0.44, 0.55, sgn * 0.44, 0.55, sgn * 0.74, -0.55, sgn * 0.74, -0.55, sgn * 0.44));
            fasteners(t, -0.50, sgn * 0.44, 0.50, sgn * 0.44, 6);
            fasteners(t, 0.35, sgn * 0.32, 1.85, sgn * 0.13, 8);
        }
        t.dispose();
        g.dispose();

        float[] height = new float[n * n];
        float[] rough = new float[n * n];
        float[] ao = new float[n * n];
        for (int i = 0; i < n * n; i++) {
            float seam = seams.getRaster().getSample(i % n, i / n, 0) / 255f;
            height[i] = -seam - 0.6f * chips[i] + 0.35f * marbles[i];
            ao[i] = 1f - 0.45f * seam;
            float rgh = 0.10f + 0.45f * grime[i] + 0.35f * soot[i];
            rgh = Math.max(rgh, 0.55f * chips[i]);
            rgh = Math.max(rgh, 0.85f * marbles[i]);
            rough[i] = TireTexture.clamp01(rgh);
        }
        normal = TireTexture.linear(TireTexture.texture(TireTexture.normalMap(height, n, n, 1.6f)));
        BufferedImage mr = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int i = r * n + c;
                int a8 = Math.round(ao[i] * 255f), r8 = Math.round(rough[i] * 255f);
                row[c] = (0xFF << 24) | (a8 << 16) | (r8 << 8);
            }
            mr.setRGB(0, r, n, 1, row, 0, n);
        }
        aoRoughMetal = TireTexture.linear(TireTexture.texture(mr));
        aoRoughMetal.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
        normal.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
    }

    private static void stamp(float[] mask, int cx, int cy, int r, Random rnd, float strength) {
        int n = SIZE;
        float ry = r * (0.6f + 0.8f * rnd.nextFloat());
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                float d = (dx * dx) / (float) (r * r) + (dy * dy) / (ry * ry);
                if (d > 1f) continue;
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= n || y >= n) continue;
                int i = y * n + x;
                mask[i] = Math.max(mask[i], strength * (1f - 0.3f * d));
            }
        }
    }

    private static void fasteners(Graphics2D g, double y0, double z0, double y1, double z1, int count) {
        double rad = 0.007;
        for (int k = 0; k < count; k++) {
            double f = count == 1 ? 0.5 : (double) k / (count - 1);
            double y = y0 + (y1 - y0) * f, z = z0 + (z1 - z0) * f - 0.018;
            g.draw(new Ellipse2D.Double(y - rad, z - rad, 2 * rad, 2 * rad));
            g.draw(new Line2D.Double(y - rad * 0.6, z, y + rad * 0.6, z));
        }
    }

    private static Shape poly(double... xy) {
        Path2D.Double p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        return p;
    }
}
