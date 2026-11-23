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
 * Worn racing slick for the lathe-mapped Blender tire ({@code blender/make_car.py}): u runs around
 * the circumference, v across the profile from the inner bead (0) to the outer bead (1):
 * <pre>
 *   0.00-0.30 inner sidewall   0.30-0.38 shoulder   0.38-0.62 tread   0.62-0.70 shoulder   0.70-1.00 outer sidewall
 * </pre>
 * Produces three maps that share one height field: albedo (scrubbed gray tread, satin black
 * sidewalls with pale molded lettering, rubber pick-up near the shoulders), a tangent-space normal
 * map (raised lettering, graining, pick-up blobs) and a packed AO / roughness / metallic map.
 */
public final class TireTexture {

    private static final int W = 2048, H = 512;
    private static Maps cached;

    /** The three tire maps (shared by every car). */
    public record Maps(Texture2D albedo, Texture2D normal, Texture2D aoRoughMetal) { }

    private TireTexture() { }

    public static synchronized Maps create() {
        if (cached != null) return cached;
        Random rnd = new Random(11);
        float[] height = new float[W * H];
        float[] rough = new float[W * H];
        int[] rgb = new int[W * H];

        // fine graining: streaks running around the circumference (long in u, short in v)
        float[] streak = new float[H];
        for (int y = 0; y < H; y++) streak[y] = rnd.nextFloat();
        float[] grainNoise = smoothNoise(W, H, 512, 64, rnd);
        float[] blotch = smoothNoise(W, H, 48, 12, rnd);

        for (int y = 0; y < H; y++) {
            float v = 1f - (y + 0.5f) / H;
            float treadW = band(v, 0.38f, 0.62f, 0.035f);           // 1 on the tread, 0 on the sidewalls
            float shoulder = Math.max(band(v, 0.28f, 0.40f, 0.02f), band(v, 0.60f, 0.72f, 0.02f));
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                float g = grainNoise[i];
                float b = blotch[i];
                // tread: scrubbed, slightly lighter gray with circumferential grain
                float tread = 0.115f + 0.03f * (streak[y] - 0.5f) + 0.035f * (g - 0.5f) + 0.02f * (b - 0.5f);
                // sidewall: satin black, faint mottling
                float side = 0.075f + 0.012f * (b - 0.5f) + 0.006f * rnd.nextFloat();
                float c = side + (tread - side) * treadW;
                float r = 0.58f + (0.86f - 0.58f) * treadW;
                float h = 0.05f * (g - 0.5f) * treadW + 0.04f * (streak[y] - 0.5f) * treadW;
                // rubber pick-up ("marbles") stuck near the shoulders
                if (shoulder > 0f && rnd.nextFloat() < 0.0007f * shoulder) {
                    splat(height, rgb, rough, x, y, 1 + rnd.nextInt(3), rnd);
                }
                int cur = rgb[i];
                if (cur == 0) {
                    int n = Math.round(Math.min(1f, c) * 255f);
                    rgb[i] = (0xFF << 24) | (n << 16) | (n << 8) | (n + 1 > 255 ? 255 : n + 1);
                    rough[i] = r;
                }
                height[i] += h;
            }
        }

        // molded sidewall lettering (pale gray paint over raised rubber), both sidewalls
        BufferedImage text = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = text.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        drawSidewall(g, 0.735f, 0.885f, true);
        drawSidewall(g, 0.115f, 0.265f, false);
        g.dispose();
        Random wear = new Random(5);
        float[] scuff = smoothNoise(W, H, 256, 32, wear);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                float t = (text.getRaster().getSample(x, y, 0)) / 255f;
                if (t <= 0f) continue;
                height[i] += 0.9f * t;
                // the paint is worn and patchy; the raised rubber stays
                float paint = t * clamp01(0.55f + 0.9f * (scuff[i] - 0.45f));
                float base = (rgb[i] & 0xFF) / 255f;
                float c = base + (0.55f - base) * paint;
                int n = Math.round(c * 255f);
                rgb[i] = (0xFF << 24) | (n << 16) | (n << 8) | n;
                rough[i] = rough[i] + (0.5f - rough[i]) * paint;
            }
        }

        BufferedImage albedo = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        albedo.setRGB(0, 0, W, H, rgb, 0, W);
        BufferedImage mr = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < W * H; i++) {
            int r8 = Math.round(clamp01(rough[i]) * 255f);
            mr.setRGB(i % W, i / W, (0xFF << 24) | (0xFF << 16) | (r8 << 8));
        }
        cached = new Maps(texture(albedo), linear(texture(normalMap(height, W, H, 2.2f))), linear(texture(mr)));
        return cached;
    }

    private static void splat(float[] height, int[] rgb, float[] rough, int cx, int cy, int r, Random rnd) {
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r * 2; dx <= r * 2; dx++) {               // u is compressed: stretch blobs
                float d = (dx * dx) / (4f * r * r) + (dy * dy) / (float) (r * r);
                if (d > 1f) continue;
                int x = Math.floorMod(cx + dx, W), y = cy + dy;
                if (y < 0 || y >= H) continue;
                int i = y * W + x;
                height[i] += 0.35f * (1f - d);
                int n = 14 + rnd.nextInt(6);
                rgb[i] = (0xFF << 24) | (n << 16) | (n << 8) | n;
                rough[i] = 0.92f;
            }
        }
    }

    private static void drawSidewall(Graphics2D g, float v0, float v1, boolean flip) {
        double top = (1 - v1) * H, bottom = (1 - v0) * H;
        double bandH = bottom - top;
        // texels per meter are ~2.4x denser across the sidewall than around it: squash the glyphs
        double squash = 0.42;
        Font big = new Font("Arial", Font.BOLD, 10).deriveFont((float) (bandH * 0.95));
        Font small = new Font("Arial", Font.BOLD, 10).deriveFont((float) (bandH * 0.42));
        String[][] words = {{"APEX", "big"}, {"RACING SLICK", "big"}, {"R15  275/40  ON TRACK USE ONLY", "small"}};
        double[] at = {0.04, 0.17, 0.36};
        for (int rep = 0; rep < 2; rep++) {
            for (int k = 0; k < words.length; k++) {
                Font f = "big".equals(words[k][1]) ? big : small;
                GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), words[k][0]);
                Rectangle2D b = gv.getVisualBounds();
                double cx = W * (rep * 0.5 + at[k]) + b.getWidth() * squash / 2;
                double cy = (top + bottom) / 2;
                AffineTransform t = new AffineTransform();
                t.translate(cx, cy);
                // Seen from outside the lathe u runs counter-clockwise and v grows toward the bead,
                // so upright text on the outer sidewall lands rotated by 180 degrees: pre-rotate it.
                if (flip) t.rotate(Math.PI);
                t.scale(squash, 1.0);
                t.translate(-b.getCenterX(), -b.getCenterY());
                g.fill(t.createTransformedShape(gv.getOutline()));
            }
        }
    }

    // ------------------------------------------------------------------ helpers (shared with the body maps)

    static float band(float v, float a, float b, float soft) {
        return smooth(clamp01((v - a) / soft + 0.5f)) * smooth(clamp01((b - v) / soft + 0.5f));
    }

    static float smooth(float t) {
        return t * t * (3 - 2 * t);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Tileable value noise in [0, 1] with cellsX x cellsY lattice cells. */
    static float[] smoothNoise(int w, int h, int cellsX, int cellsY, Random rnd) {
        float[] grid = new float[cellsX * cellsY];
        for (int i = 0; i < grid.length; i++) grid[i] = rnd.nextFloat();
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            float ty = (float) y * cellsY / h;
            int y0 = (int) ty;
            float fy = smooth(ty - y0);
            int y1 = (y0 + 1) % cellsY;
            y0 %= cellsY;
            for (int x = 0; x < w; x++) {
                float tx = (float) x * cellsX / w;
                int x0 = (int) tx;
                float fx = smooth(tx - x0);
                int x1 = (x0 + 1) % cellsX;
                x0 %= cellsX;
                float a = grid[y0 * cellsX + x0], b = grid[y0 * cellsX + x1];
                float c = grid[y1 * cellsX + x0], d = grid[y1 * cellsX + x1];
                out[y * w + x] = (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
            }
        }
        return out;
    }

    /**
     * Tangent-space normal map (OpenGL / Y+ convention) from a height field; image row 0 is the
     * top of the texture (v = 1), so +v is toward smaller rows.
     */
    static BufferedImage normalMap(float[] h, int w, int hgt, float strength) {
        BufferedImage img = new BufferedImage(w, hgt, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < hgt; y++) {
            int yu = Math.max(0, y - 1), yd = Math.min(hgt - 1, y + 1);
            for (int x = 0; x < w; x++) {
                int xl = (x - 1 + w) % w, xr = (x + 1) % w;
                float dx = (h[y * w + xr] - h[y * w + xl]) * 0.5f;
                float dv = (h[yu * w + x] - h[yd * w + x]) * 0.5f;     // d/dv (v grows upward)
                float nx = -dx * strength, ny = -dv * strength, nz = 1f;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int r = Math.round((nx / len * 0.5f + 0.5f) * 255f);
                int gg = Math.round((ny / len * 0.5f + 0.5f) * 255f);
                int b = Math.round((nz / len * 0.5f + 0.5f) * 255f);
                img.setRGB(x, y, (0xFF << 24) | (r << 16) | (gg << 8) | b);
            }
        }
        return img;
    }

    /** Data textures (normal / roughness maps) must not be sRGB-decoded. */
    static Texture2D linear(Texture2D t) {
        t.getImage().setColorSpace(com.jme3.texture.image.ColorSpace.Linear);
        return t;
    }

    static Texture2D texture(BufferedImage img) {
        Image image = new AWTLoader().load(img, false);
        Texture2D tex = new Texture2D(image);
        tex.setMinFilter(Texture.MinFilter.Trilinear);
        tex.setMagFilter(Texture.MagFilter.Bilinear);
        tex.setAnisotropicFilter(8);
        tex.setWrap(Texture.WrapMode.Repeat);
        return tex;
    }
}
