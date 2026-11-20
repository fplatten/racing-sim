package com.racingsim.vehicle;

/**
 * Driver commands consumed by {@link VehiclePhysics}. Written by the player input handler
 * or by an AI driver - the physics treats both identically.
 */
public final class VehicleInput {
    /** 0..1 */
    public float throttle;
    /** 0..1 */
    public float brake;
    /** -1..1, positive = steer left (fraction of the speed-sensitive steering lock). */
    public float steer;
    /** One-shot requests consumed by the gearbox. */
    public boolean shiftUpRequest;
    public boolean shiftDownRequest;
    /**
     * Set by the race director while the car is being serviced in its pit box: the crew holds
     * the car (full brake unless the driver floors it to leave) and reverse is never engaged.
     */
    public boolean pitHold;

    public void clear() {
        throttle = brake = steer = 0f;
        shiftUpRequest = shiftDownRequest = false;
    }
}
