package com.racingsim.ai;

import com.racingsim.track.TrackSpline;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * The built-in AI's tuning parameters (the {@code ai*} names in {@link AIDriver} and
 * {@link SpeedProfile}, and {@code aiGrip} / {@code aiGripSpread}, the grip the drivers plan with).
 * A value is looked up, first match wins:
 * <ol>
 *   <li>a system property ({@code -DaiSling=2}, gradle {@code -PaiSling=2});</li>
 *   <li>the file named by {@code -DaiParams=<file.properties>} (the trainer, tools/train/tune_ai.py,
 *       writes one per candidate);</li>
 *   <li>the selected track's {@code AI/<track>.properties} on the class path (the tuned values the
 *       game ships, one file for the oval and one for the road course);</li>
 *   <li>the default in the code.</li>
 * </ol>
 * The Unreal version reads the same files (Content/AI/&lt;track&gt;.properties).
 */
public final class AiParams {

    private static final Map<com.racingsim.track.TrackLayout, Properties> TRACK_FILES =
            new EnumMap<>(com.racingsim.track.TrackLayout.class);
    private static Properties overrideFile;
    private static String overridePath;

    private AiParams() {}

    /** The value of {@code name} for the selected track, else {@code def}. */
    public static synchronized float get(String name, float def) {
        String v = System.getProperty(name);
        if (v == null) v = override().getProperty(name);
        if (v == null) v = trackFile(TrackSpline.layout()).getProperty(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            System.err.println("AiParams: bad value for " + name + ": " + v);
            return def;
        }
    }

    private static Properties override() {
        String path = System.getProperty("aiParams");
        if (path == null || path.isBlank()) return new Properties();
        if (!path.equals(overridePath)) {
            overridePath = path;
            overrideFile = new Properties();
            try (Reader r = Files.newBufferedReader(Path.of(path))) {
                overrideFile.load(r);
            } catch (IOException e) {
                System.err.println("AiParams: cannot read " + path + ": " + e.getMessage());
            }
        }
        return overrideFile;
    }

    private static Properties trackFile(com.racingsim.track.TrackLayout layout) {
        return TRACK_FILES.computeIfAbsent(layout, l -> {
            Properties p = new Properties();
            try (InputStream in = AiParams.class.getResourceAsStream("/AI/" + l.key() + ".properties")) {
                if (in != null) p.load(in);
            } catch (IOException e) {
                System.err.println("AiParams: cannot read AI/" + l.key() + ".properties: " + e.getMessage());
            }
            return p;
        });
    }
}
