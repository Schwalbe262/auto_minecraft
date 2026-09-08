package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** One finite travel segment. A distant destination never enlarges this envelope. */
public final class TravelDomain {
    public static final int HORIZONTAL_RADIUS=256, VERTICAL_RADIUS=64;
    private final ProfileBounds legacy;
    private final Pos origin;
    private final boolean terrain;
    private final List<Pos> hints;

    public TravelDomain(Profile profile,Pos origin,Pos destination) {
        Objects.requireNonNull(profile);this.origin=Objects.requireNonNull(origin);
        Objects.requireNonNull(destination);legacy=new ProfileBounds(profile);
        terrain=profile.navigationMode==NavigationMode.TERRAIN;
        hints=profile.useWaypointHints ? profile.pois(PoiKind.WAYPOINT).stream().map(Poi::pos)
            .filter(this::contains).sorted(Comparator.comparingDouble(p -> distanceSquared(origin,p)))
            .limit(32).toList() : List.of();
    }
    public boolean terrain() { return terrain; }
    public Pos origin() { return origin; }
    public boolean contains(Pos p) {
        if (p==null) return false;
        return terrain ? Math.abs((long)p.x()-origin.x())<=HORIZONTAL_RADIUS
            && Math.abs((long)p.z()-origin.z())<=HORIZONTAL_RADIUS
            && Math.abs((long)p.y()-origin.y())<=VERTICAL_RADIUS : legacy.contains(p);
    }
    /** Tie breaker only: hints do not change A* costs, goals, collision checks or permissions. */
    public double hintDistance(Pos p) {
        double best=Double.POSITIVE_INFINITY;
        for (Pos hint:hints) best=Math.min(best,distanceSquared(p,hint));
        return hints.isEmpty() ? 0 : best;
    }
    static double distanceSquared(Pos a,Pos b) {
        double dx=(double)a.x()-b.x(),dy=(double)a.y()-b.y(),dz=(double)a.z()-b.z();
        return dx*dx+dy*dy+dz*dz;
    }
}
