package com.racingsim.track;

/**
 * The circuits the game can race on. One is loaded at a time: pick it with
 * {@link TrackSpline#selectLayout} before the {@link TrackSpline} (and everything built on it:
 * track meshes, racing line, speed profiles, cars) is created.
 */
public enum TrackLayout {
    /** The 2.5-mile banked oval: rolling start three abreast, pit stops. */
    OVAL("oval", "OVAL", "2.5-MILE OVAL"),
    /** The 4.352-mile road course with 102 m of hills: standing start, no pit lane. */
    ROAD("road", "ROAD", "4.352-MILE ROAD COURSE");

    private final String key, label, title;

    TrackLayout(String key, String label, String title) {
        this.key = key;
        this.label = label;
        this.title = title;
    }

    /** "oval" / "road": the {@code -Dtrack=} / {@code -Ptrack=} value. */
    public String key() { return key; }

    /** Short name for a menu value ("OVAL", "ROAD"). */
    public String label() { return label; }

    /** Long name ("2.5-MILE OVAL", "4.352-MILE ROAD COURSE"). */
    public String title() { return title; }

    /** Whether this track has a pit lane (the oval only). */
    public boolean hasPits() { return this == OVAL; }

    /** Default wing (menu steps of 10) for the player's car: minimum on the oval, a lot on the road. */
    public int defaultDownforce() { return this == OVAL ? 0 : 8; }

    /** The other track (the menu's TRACK row toggles between the two). */
    public TrackLayout next() { return this == OVAL ? ROAD : OVAL; }

    /** "road" (any case) is the road course; anything else, or null, is the oval. */
    public static TrackLayout fromKey(String key) {
        return key != null && key.trim().equalsIgnoreCase("road") ? ROAD : OVAL;
    }

    /** The track asked for with {@code -Dtrack=road} (the oval if not set). */
    public static TrackLayout fromSystemProperty() {
        return fromKey(System.getProperty("track"));
    }
}
