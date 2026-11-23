package com.racingsim.track;

/**
 * Pit-stop state of one car. A stop is made in the car's own pit box: once the car is stationary
 * inside the box an {@link #SERVICE_TIME} countdown starts; it only counts if the car is still
 * in the box when the countdown reaches zero. Leaving early aborts the stop. Laps on which the
 * AI plans to stop are stored here too.
 */
public final class PitStop {

    /** Duration of every pit stop (s). */
    public static final float SERVICE_TIME = 8f;
    /** Speed below which a car counts as stopped (m/s). */
    public static final float STOPPED_SPEED = 0.5f;

    public enum Status { NONE, SERVICING, COMPLETE, ABORTED }

    private final int stall;
    private Status status = Status.NONE;
    private float countdown;
    private float statusTime;
    /** Set once a stop has been completed; cleared when the car leaves the box (re-arms). */
    private boolean served;
    private int[] plannedLaps = new int[0];

    public PitStop(int stall) {
        this.stall = stall;
    }

    public int stall() { return stall; }
    public Status status() { return status; }
    /** Seconds left in the current stop (only meaningful while {@link Status#SERVICING}). */
    public float countdown() { return countdown; }
    /** Seconds since the status last changed (for HUD messages). */
    public float statusTime() { return statusTime; }
    public boolean isServicing() { return status == Status.SERVICING; }

    public void setPlannedLaps(int[] laps) { plannedLaps = laps.clone(); }

    /** Lap on which stop number {@code done + 1} is planned (Integer.MAX_VALUE if none). */
    public int nextPlannedLap(int done) {
        return done < plannedLaps.length ? plannedLaps[done] : Integer.MAX_VALUE;
    }

    /** New race: forget any stop in progress. */
    public void reset() {
        status = Status.NONE;
        countdown = 0f;
        statusTime = 0f;
        served = false;
    }

    /** Box test: center of the car inside the painted box of this car's stall. */
    public static boolean inBox(int stall, float s, float d) {
        float r = PitLane.rel(s), start = PitLane.stallStart(stall);
        return r > start + 0.5f && r < start + PitLane.STALL_LENGTH - 0.5f
                && d < PitLane.FAST_LANE_INNER - 0.5f && d > PitLane.BOX_INNER + 1f;
    }

    /**
     * Advances the stop logic by one physics step.
     * @return true exactly when a stop has just been completed
     */
    public boolean update(float dt, float s, float d, float speed) {
        statusTime += dt;
        boolean in = inBox(stall, s, d);
        if (!in) served = false;
        if (status == Status.SERVICING) {
            if (!in) {
                setStatus(Status.ABORTED);
                return false;
            }
            countdown -= dt;
            if (countdown <= 0f) {
                countdown = 0f;
                served = true;
                setStatus(Status.COMPLETE);
                return true;
            }
            return false;
        }
        if (in && !served && Math.abs(speed) < STOPPED_SPEED) {
            countdown = SERVICE_TIME;
            setStatus(Status.SERVICING);
        }
        return false;
    }

    private void setStatus(Status s) {
        status = s;
        statusTime = 0f;
    }
}
