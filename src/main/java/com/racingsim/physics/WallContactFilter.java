package com.racingsim.physics;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.ContactListener;
import com.jme3.bullet.collision.ManifoldPoints;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.math.Vector3f;

/**
 * Keeps the retaining walls from launching cars. The walls are vertical, but a box hitting a
 * triangle mesh at speed often gets contact normals from triangle edges / corners (the wall's
 * bottom and top edges, the seams between wall segments) that point well up or down; at 100 m/s
 * the wall stops ~20,000 N*s of sideways momentum, so even a 10 degree tilt throws the car several
 * meters into the air. Contacts with the side faces of the walls are made horizontal here, so the
 * wall only pushes the car sideways (as a real concrete / SAFER barrier does).
 */
public final class WallContactFilter implements ContactListener {

    private final PhysicsCollisionObject collider;
    private final int firstWallPart;
    private final Vector3f n = new Vector3f();

    /** @param firstWallPart sub-mesh index of the first wall part; all parts from it on are walls / fences */
    private WallContactFilter(PhysicsCollisionObject collider, int firstWallPart) {
        this.collider = collider;
        this.firstWallPart = firstWallPart;
    }

    public static void install(PhysicsSpace space, PhysicsCollisionObject collider, int firstWallPart) {
        space.addContactListener(new WallContactFilter(collider, firstWallPart), false, true, false);
    }

    @Override
    public void onContactProcessed(PhysicsCollisionObject a, PhysicsCollisionObject b, long point) {
        int part;
        if (a == collider) part = ManifoldPoints.getPartId0(point);
        else if (b == collider) part = ManifoldPoints.getPartId1(point);
        else return;
        ManifoldPoints.getNormalWorldOnB(point, n);
        if (part < firstWallPart) return;
        if (Math.abs(n.y) > 0.85f) return;                 // the top of a wall: a real surface
        n.y = 0f;
        float len = n.length();
        if (len < 1e-4f) return;
        n.divideLocal(len);
        ManifoldPoints.setNormalWorldOnB(point, n);
    }

    @Override public void onContactEnded(long manifoldId) { }
    @Override public void onContactStarted(long manifoldId) { }
}
