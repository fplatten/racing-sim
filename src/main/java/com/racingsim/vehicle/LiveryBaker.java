package com.racingsim.vehicle;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Bakes the livery and helmet textures of every car at build time ({@code ./gradlew bakeLiveries},
 * run automatically before the resources are packaged), because painting one takes ~1.5 s of CPU.
 * The game loads the baked PNGs from {@code Textures/Liveries/} and only paints a car itself when
 * its file is missing.
 * <p>
 * Baked: the player's car (livery 0) at 2048 px, and every opponent (liveries 1..32) at 1024 px.
 */
public final class LiveryBaker {

    private static final Logger LOG = Logger.getLogger(LiveryBaker.class.getName());
    static final String DIR = "Textures/Liveries/";
    /** Liveries of the largest field: the player (0) and up to 32 opponents. */
    static final int CARS = Livery.MAX_CARS;

    private LiveryBaker() { }

    /** Livery and helmet sheets for one car. */
    record Sheets(BufferedImage livery, BufferedImage helmet) { }

    /** The player's car is painted in high detail, the opponents in normal detail. */
    static boolean highDetail(int index) { return index == 0; }

    static int size(boolean highDetail) { return highDetail ? 2048 : 1024; }

    static String resource(Livery livery, boolean highDetail, String kind) {
        String name = livery.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return DIR + livery.number() + "-" + name + "-" + kind + (highDetail ? "-" + size(true) : "") + ".png";
    }

    /** Paints both sheets now. */
    static Sheets render(Livery livery, boolean highDetail) {
        return new Sheets(LiveryPainter.render(livery, size(highDetail)), HelmetPainter.render(livery));
    }

    /** The baked sheets from the classpath, or null when this car was not baked (or can't be read). */
    static Sheets load(Livery livery, boolean highDetail) {
        BufferedImage body = read(resource(livery, highDetail, "livery"));
        BufferedImage helmet = body == null ? null : read(resource(livery, highDetail, "helmet"));
        return helmet == null ? null : new Sheets(body, helmet);
    }

    private static BufferedImage read(String resource) {
        try (InputStream in = LiveryBaker.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Could not read baked texture " + resource + "; painting it instead", e);
            return null;
        }
    }

    /** Writes every car's sheets under {@code args[0]} (a resources root). */
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path root = Path.of(args.length > 0 ? args[0] : "build/generated/liveries");
        Path dir = root.resolve(DIR);
        Files.createDirectories(dir);
        try (var old = Files.list(dir)) {                        // drop sheets of renamed liveries
            for (Path p : old.toList()) Files.delete(p);
        }
        long t0 = System.nanoTime();
        IntStream.range(0, CARS).parallel().forEach(i -> {
            Livery livery = Livery.forIndex(i);
            boolean hi = highDetail(i);
            Sheets s = render(livery, hi);
            write(s.livery(), root.resolve(resource(livery, hi, "livery")));
            write(s.helmet(), root.resolve(resource(livery, hi, "helmet")));
        });
        System.out.printf("Baked %d liveries into %s in %.1f s%n", CARS, dir, (System.nanoTime() - t0) / 1e9);
    }

    private static void write(BufferedImage img, Path file) {
        try {
            if (!ImageIO.write(img, "png", file.toFile())) throw new IOException("no PNG writer");
        } catch (IOException e) {
            throw new UncheckedIOException(file.toString(), e);
        }
    }
}
