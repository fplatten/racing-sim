package com.racingsim.vehicle;

import com.jme3.math.ColorRGBA;

/**
 * The Formula car's paint scheme ({@link FormulaPaint}, {@code MatDefs/Livery.frag}): four colors
 * and a pattern for the body, three colors and a pattern for the driver's helmet. Colors are
 * linear.
 * <p>
 * The schemes follow the many-colored liveries of 1995. Body patterns: 0 split, 1 sweep, 2 arrow,
 * 3 blocks, 4 stripes, 5 wave; helmet patterns: 0 crown and band, 1 swept side stripes, 2 blades,
 * 3 swirl, 4 chevron, 5 halves.
 */
public record CarPaint(ColorRGBA[] body, int pattern, ColorRGBA[] helmet, int helmetPattern) {

    /** Picks the player's scheme: {@code -DplayerPaint=N} (gradle {@code -PplayerPaint=N}), another team's. */
    public static final String PLAYER_PAINT_PROPERTY = "playerPaint";

    private record Scheme(int pattern, int[][] body, int helmetPattern, int[][] helmet) { }

    // the paint colors (sRGB)
    private static final int[] WHITE = {242, 242, 238}, RED = {200, 16, 18}, BLACK = {14, 14, 16}, NAVY = {14, 30, 92},
            ROYAL = {18, 62, 178}, SKY = {70, 150, 230}, YELLOW = {250, 204, 20}, GREEN = {10, 112, 52},
            ORANGE = {250, 115, 12}, PURPLE = {84, 30, 140}, SILVER = {160, 164, 170}, TEAL = {0, 118, 132},
            MAROON = {110, 14, 28}, GOLD = {214, 164, 52}, MAGENTA = {200, 18, 120}, LIME = {150, 200, 30};

    /** The hand-made schemes, one for each of {@link Livery#PALETTE}'s teams (the player's car is white and red). */
    private static final Scheme[] SCHEMES = {
            new Scheme(0, new int[][]{WHITE, RED, BLACK, BLACK}, 4, new int[][]{WHITE, RED, NAVY}),           // Player: white over red
            new Scheme(3, new int[][]{RED, WHITE, BLACK, YELLOW}, 1, new int[][]{YELLOW, GREEN, ROYAL}),      // Crimson
            new Scheme(1, new int[][]{ORANGE, NAVY, WHITE, SKY}, 3, new int[][]{WHITE, ORANGE, NAVY}),        // Papaya
            new Scheme(5, new int[][]{GREEN, WHITE, YELLOW, BLACK}, 2, new int[][]{GREEN, YELLOW, WHITE}),    // Viper
            new Scheme(4, new int[][]{BLACK, GOLD, WHITE, GOLD}, 0, new int[][]{BLACK, GOLD, WHITE}),         // Midnight
            new Scheme(2, new int[][]{YELLOW, ROYAL, GREEN, RED}, 5, new int[][]{YELLOW, ROYAL, RED}),        // Sunburst
            new Scheme(1, new int[][]{PURPLE, MAGENTA, WHITE, YELLOW}, 3, new int[][]{MAGENTA, WHITE, YELLOW}), // Magenta
            new Scheme(0, new int[][]{WHITE, TEAL, ORANGE, NAVY}, 4, new int[][]{TEAL, ORANGE, WHITE}),       // Teal
    };

    /** The rest of the field draws from these. */
    private static final int[][] SET = {ROYAL, RED, YELLOW, GREEN, ORANGE, NAVY, PURPLE, TEAL, MAROON, SKY, SILVER,
            MAGENTA, BLACK, LIME, GOLD, WHITE};

    /**
     * The scheme of car {@code index} in the field ({@link Livery#forIndex}): the first eight are
     * hand-made for {@link Livery#PALETTE}'s teams, the rest take distinct colors from a set of
     * real paint colors, a different pattern and helmet each.
     */
    public static CarPaint forIndex(int index) {
        index = Math.max(index, 0);
        if (index < SCHEMES.length) {
            Scheme s = SCHEMES[index];
            return new CarPaint(colors(s.body()), s.pattern(), colors(s.helmet()), s.helmetPattern());
        }
        int n = SET.length, k = index - SCHEMES.length;
        int a = (k * 5) % n, b = (a + 3 + k % 4) % n, c = (a + 7 + k % 3) % n;
        int[] fourth = k % 2 != 0 ? WHITE : YELLOW;
        int[][] body = {SET[a], SET[b], SET[c] == SET[a] ? WHITE : SET[c], fourth};
        int[][] helmet = {SET[b], SET[a], fourth};
        return new CarPaint(colors(body), k % 6, colors(helmet), (k + 2) % 6);
    }

    /** {@link #forIndex}, with {@code -DplayerPaint=N} giving the player's car (index 0) another scheme. */
    public static CarPaint forField(int index) {
        if (index == 0) {
            String p = System.getProperty(PLAYER_PAINT_PROPERTY);
            if (p != null && !p.isBlank()) {
                try {
                    return forIndex(Integer.parseInt(p.trim()));
                } catch (NumberFormatException e) {
                    System.err.println("playerPaint: not a number: " + p);
                }
            }
        }
        return forIndex(index);
    }

    private static ColorRGBA[] colors(int[][] srgb) {
        ColorRGBA[] out = new ColorRGBA[srgb.length];
        for (int i = 0; i < srgb.length; i++) {
            out[i] = new ColorRGBA(linear(srgb[i][0]), linear(srgb[i][1]), linear(srgb[i][2]), 1f);
        }
        return out;
    }

    /** The exact sRGB curve (as Unreal's FLinearColor::FromSRGBColor). */
    private static float linear(int c8) {
        float c = c8 / 255f;
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }
}
