package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.List;

/** The union of registered work sites and corridors between consecutive waypoints. */
public final class ProfileBounds {
    private final Profile profile;
    private final List<Poi> waypoints;

    public ProfileBounds(Profile profile) {
        this.profile = profile;
        this.waypoints = profile.pois(PoiKind.WAYPOINT);
    }

    public static boolean contains(Profile profile, Pos feet) {
        return new ProfileBounds(profile).contains(feet);
    }

    public boolean contains(Pos p) {
        for (Farm farm : profile.farms) {
            if (farm.volume() > 32768) continue;
            if (between(p.x(), farm.first().x(), farm.second().x(), 1)
                && between(p.y(), farm.first().y(), farm.second().y(), 1)
                && between(p.z(), farm.first().z(), farm.second().z(), 1)) return true;
        }
        double radius = Math.max(1, Math.min(8, profile.corridorRadius));
        double squared = radius * radius;
        for (Poi poi : profile.pois) if (poi.pos().distanceSquared(p) <= squared) return true;
        for (int i = 1; i < waypoints.size(); i++) {
            if (segmentDistanceSquared(p, waypoints.get(i - 1).pos(), waypoints.get(i).pos()) <= squared) return true;
        }
        return false;
    }

    private static boolean between(int value, int a, int b, int margin) {
        return value >= (long) Math.min(a, b) - margin && value <= (long) Math.max(a, b) + margin;
    }

    private static double segmentDistanceSquared(Pos p, Pos a, Pos b) {
        double dx = (double)b.x() - a.x(), dy = (double)b.y() - a.y(), dz = (double)b.z() - a.z();
        double length = dx * dx + dy * dy + dz * dz;
        if (length == 0) return p.distanceSquared(a);
        double t = ((p.x() - (double)a.x()) * dx + (p.y() - (double)a.y()) * dy + (p.z() - (double)a.z()) * dz) / length;
        t = Math.max(0, Math.min(1, t));
        return Math.pow(p.x() - a.x() - t * dx, 2)
            + Math.pow(p.y() - a.y() - t * dy, 2) + Math.pow(p.z() - a.z() - t * dz, 2);
    }
}
