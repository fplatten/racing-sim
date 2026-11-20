package com.racingsim.track;

import java.util.ArrayList;
import java.util.List;

/**
 * Lap counting, lap timing, race order and the chequered flag, from continuous track
 * progress {@code s}. Each entry accumulates total distance; crossing s = 0 forward starts a
 * new lap. Cars start behind the line (lap 0); the first crossing starts lap 1 and an entry
 * finishes when it completes {@link #getRaceLaps()} laps.
 */
public final class LapTracker {

    /** Progress state for one competitor. */
    public static final class Entry {
        public final String name;
        public final boolean player;
        float totalDistance;
        float lastS;
        int lapIndex;          // -1 until the start/finish line is first crossed
        float lapStartTime = Float.NaN;
        float lastLapTime = Float.NaN;
        float bestLapTime = Float.NaN;
        int position;
        boolean finished;
        int finishOrder;
        float finishTime = Float.NaN;
        int pitStops;

        Entry(String name, boolean player) {
            this.name = name;
            this.player = player;
        }

        public float totalDistance() { return totalDistance; }
        /** Lap currently being driven (1-based, minimum 1). */
        public int lap() { return Math.max(1, lapIndex + 1); }
        public float lastLapTime() { return lastLapTime; }
        public float bestLapTime() { return bestLapTime; }
        public int position() { return position; }
        public boolean finished() { return finished; }
        public float finishTime() { return finishTime; }
        /** Laps completed so far. */
        public int lapsCompleted() { return Math.max(0, lapIndex); }
        /** Completed pit stops this race. */
        public int pitStops() { return pitStops; }
        public float currentLapTime(float now) {
            return Float.isNaN(lapStartTime) ? 0f : now - lapStartTime;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> order = new ArrayList<>();
    private float time;
    private int raceLaps = Integer.MAX_VALUE;
    private int finishedCount;
    private float raceStartTime = Float.NaN;
    private int requiredStops;

    /** Starts a new race of the given length; every entry must then be {@link #reset}. */
    public void newRace(int laps) {
        newRace(laps, 0);
    }

    /** Starts a new race of {@code laps} laps in which every car must make {@code stops} pit stops. */
    public void newRace(int laps, int stops) {
        requiredStops = stops;
        raceLaps = laps;
        finishedCount = 0;
        raceStartTime = Float.NaN;
    }

    public int getRaceLaps() { return raceLaps; }
    public int getRequiredStops() { return requiredStops; }

    /** True if the entry has made every mandatory pit stop. */
    public boolean stopsMet(Entry e) { return e.pitStops >= requiredStops; }

    /** Records a completed pit stop. */
    public void pitStopCompleted(Entry e) { e.pitStops++; }

    /** Race clock: time since the leader took the green flag (first crossing of the line). */
    public float raceTime() {
        return Float.isNaN(raceStartTime) ? 0f : time - raceStartTime;
    }

    public boolean leaderFinished() { return finishedCount > 0; }

    /** Re-initialises an entry for a car placed at startS (new race). */
    public void reset(Entry e, float startS) {
        resetEntry(e, startS);
        e.lapStartTime = Float.NaN;
        e.lastLapTime = Float.NaN;
        e.bestLapTime = Float.NaN;
        e.finished = false;
        e.finishOrder = 0;
        e.finishTime = Float.NaN;
        e.pitStops = 0;
    }

    public Entry addEntry(String name, boolean player, float startS) {
        Entry e = new Entry(name, player);
        resetEntry(e, startS);
        entries.add(e);
        order.add(e);
        return e;
    }

    /** Initialise progress for a car placed at startS (cars behind the line are on lap 0). */
    private void resetEntry(Entry e, float startS) {
        float s = TrackSpline.wrapS(startS);
        e.lastS = s;
        // positions in the second half of the lap are treated as "approaching the line"
        e.totalDistance = s > TrackSpline.LENGTH / 2 ? s - TrackSpline.LENGTH : s;
        e.lapIndex = (int) Math.floor(e.totalDistance / TrackSpline.LENGTH);
    }

    /** Teleport an entry to s = startS (player reset): keeps the lap count, restarts the lap timer. */
    public void resetToLine(Entry e) {
        e.lapIndex = Math.max(e.lapIndex, 0);
        e.totalDistance = e.lapIndex * TrackSpline.LENGTH + 0.01f;
        e.lastS = 0.01f;
        e.lapStartTime = time;
    }

    /** Teleport an entry to an arbitrary s on the same lap (AI recovery). */
    public void teleport(Entry e, float newS) {
        float delta = TrackSpline.deltaS(e.lastS, newS);
        e.totalDistance += delta;
        e.lastS = TrackSpline.wrapS(newS);
    }

    public void update(Entry e, float s) {
        float delta = TrackSpline.deltaS(e.lastS, s);
        e.lastS = s;
        e.totalDistance += delta;
        int newLap = (int) Math.floor(e.totalDistance / TrackSpline.LENGTH);
        if (newLap > e.lapIndex) {
            if (e.lapIndex >= 0 && !Float.isNaN(e.lapStartTime)) {
                e.lastLapTime = time - e.lapStartTime;
                if (Float.isNaN(e.bestLapTime) || e.lastLapTime < e.bestLapTime) e.bestLapTime = e.lastLapTime;
            }
            if (newLap == 0 && Float.isNaN(raceStartTime)) raceStartTime = time;   // green flag
            e.lapStartTime = time;
            e.lapIndex = newLap;
            if (!e.finished && newLap >= raceLaps) {                                // chequered flag
                e.finished = true;
                e.finishOrder = ++finishedCount;
                e.finishTime = raceTime();
            }
        } else if (newLap < e.lapIndex) {
            e.lapIndex = newLap; // driving backwards over the line
        }
    }

    public void advanceTime(float dt) {
        time += dt;
    }

    /**
     * Re-sorts race order: finishers who made their mandatory pit stops in finishing order, then
     * the cars still running by distance, then finishers who missed a mandatory stop (penalised
     * to the back of the classification).
     */
    public void updatePositions() {
        order.sort((a, b) -> {
            int ga = group(a), gb = group(b);
            if (ga != gb) return Integer.compare(ga, gb);
            if (a.finished) return Integer.compare(a.finishOrder, b.finishOrder);
            return Float.compare(b.totalDistance, a.totalDistance);
        });
        for (int i = 0; i < order.size(); i++) order.get(i).position = i + 1;
    }

    private int group(Entry e) {
        if (!e.finished) return 1;
        return stopsMet(e) ? 0 : 2;
    }

    public float getTime() { return time; }
    public List<Entry> getOrder() { return order; }
    public int size() { return entries.size(); }
}
