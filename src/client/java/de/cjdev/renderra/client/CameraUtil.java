package de.cjdev.renderra.client;

import net.minecraft.world.phys.Vec3;

public class CameraUtil {
    public static boolean isMouseOverPoint(Vec3 point, Vec3 origin, Vec3 dir, double radius) {
        // Vector from ray origin → point
        Vec3 toPoint = point.subtract(origin);

        // Projection of that vector onto the ray direction
        double t = toPoint.dot(dir);

        if (t < 0) return false; // point is behind the camera

        // Closest point on ray
        Vec3 closest = origin.add(dir.scale(t));

        // Distance squared from the point to the ray
        double distSq = point.distanceToSqr(closest);

        return distSq <= radius * radius;
    }
}
