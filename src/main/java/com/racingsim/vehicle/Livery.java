package com.racingsim.vehicle;

import com.jme3.math.ColorRGBA;

/**
 * Car color scheme (sRGB colors) and race number. Painted onto the Blender car's livery
 * UV layout by {@link LiveryPainter}: {@code primary} = lower body / nose band / wings,
 * {@code secondary} = upper body, {@code accent} = swoosh, pinstripes, nose tip and helmet.
 */
public record Livery(String name, ColorRGBA primary, ColorRGBA secondary, ColorRGBA accent, int number) {

    /** Largest field supported (the Indianapolis 500 grid). */
    public static final int MAX_CARS = 33;

    private static ColorRGBA c(float r, float g, float b) {
        return new ColorRGBA(r, g, b, 1f);
    }

    /** Hand-picked schemes: index 0 is the player, the rest are the first AI opponents. */
    public static final Livery[] PALETTE = {
            new Livery("Player", c(0.05f, 0.22f, 0.72f), c(0.96f, 0.96f, 0.97f), c(0.86f, 0.08f, 0.10f), 5),
            new Livery("Crimson", c(0.78f, 0.03f, 0.05f), c(0.97f, 0.97f, 0.97f), c(0.08f, 0.08f, 0.09f), 12),
            new Livery("Papaya", c(1.00f, 0.45f, 0.03f), c(0.07f, 0.17f, 0.45f), c(0.96f, 0.96f, 0.96f), 7),
            new Livery("Viper", c(0.04f, 0.45f, 0.16f), c(0.96f, 0.96f, 0.96f), c(0.98f, 0.80f, 0.08f), 9),
            new Livery("Midnight", c(0.03f, 0.03f, 0.04f), c(0.10f, 0.10f, 0.12f), c(0.90f, 0.70f, 0.18f), 3),
            new Livery("Sunburst", c(0.98f, 0.78f, 0.04f), c(0.98f, 0.84f, 0.10f), c(0.08f, 0.08f, 0.10f), 22),
            new Livery("Magenta", c(0.80f, 0.05f, 0.50f), c(0.97f, 0.97f, 0.97f), c(0.10f, 0.10f, 0.12f), 14),
            new Livery("Teal", c(0.00f, 0.48f, 0.54f), c(0.96f, 0.96f, 0.96f), c(1.00f, 0.50f, 0.05f), 31),
    };

    private static final String[] MORE_NAMES = {
            "Cobalt", "Lime", "Scarlet", "Indigo", "Amber", "Jade", "Ruby", "Onyx", "Saffron", "Azure",
            "Coral", "Emerald", "Copper", "Violet", "Silver", "Tangerine", "Navy", "Mint", "Garnet", "Bronze",
            "Sky", "Plum", "Gold", "Forest", "Rose"};
    private static final int[] MORE_NUMBERS = {
            2, 4, 6, 8, 10, 11, 15, 18, 20, 21, 23, 24, 26, 27, 28, 29, 30, 33, 45, 48, 51, 60, 66, 77, 98};

    /**
     * Livery for car {@code index} (0 = player, 1..32 = opponents): the hand-picked palette
     * first, then generated schemes with well-spread hues and unique numbers.
     */
    public static Livery forIndex(int index) {
        if (index < PALETTE.length) return PALETTE[index];
        int k = index - PALETTE.length;
        float hue = (k * 0.618034f + 0.11f) % 1f;                 // golden-ratio hue spacing
        ColorRGBA primary = hsv(hue, 0.85f, k % 3 == 2 ? 0.55f : 0.85f);
        ColorRGBA secondary = switch (k % 3) {
            case 0 -> c(0.96f, 0.96f, 0.96f);
            case 1 -> c(0.07f, 0.07f, 0.09f);
            default -> hsv((hue + 0.5f) % 1f, 0.25f, 0.95f);
        };
        ColorRGBA accent = hsv((hue + 0.45f) % 1f, 0.9f, 0.95f);
        return new Livery(MORE_NAMES[k % MORE_NAMES.length], primary, secondary, accent, MORE_NUMBERS[k % MORE_NUMBERS.length]);
    }

    private static ColorRGBA hsv(float h, float s, float v) {
        float r, g, b;
        int i = (int) (h * 6) % 6;
        float f = h * 6 - (int) (h * 6), p = v * (1 - s), q = v * (1 - f * s), t = v * (1 - (1 - f) * s);
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return c(r, g, b);
    }
}
