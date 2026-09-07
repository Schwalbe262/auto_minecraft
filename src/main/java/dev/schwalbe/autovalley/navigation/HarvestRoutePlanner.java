package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.Pos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Orders clicks for square native hoe coverage; it never declares a crop harvested. */
public final class HarvestRoutePlanner {
    private HarvestRoutePlanner() { }

    /**
     * Put well-spaced existing mature centers first, sweeping alternating strips.
     * Every original position remains in the returned queue, including positions
     * predicted to be covered: only a later observed age change may skip them.
     * Radius is the local half-span hint, not the conservative safety radius.
     */
    public static List<Pos> order(List<Pos> mature,int radius) {
        LinkedHashSet<Pos> unique = new LinkedHashSet<>(mature);
        if (unique.isEmpty() || radius < 1 || radius > 4) return List.copyOf(unique);
        int minX = unique.stream().mapToInt(Pos::x).min().orElseThrow();
        int minZ = unique.stream().mapToInt(Pos::z).min().orElseThrow();
        int width = radius*2+1;
        Comparator<Tile> snake = Comparator.comparingInt(Tile::y).thenComparingLong(Tile::z)
            .thenComparingLong(t -> (t.z()&1)==0 ? t.x() : -t.x());
        Map<Tile,List<Pos>> tiles = new TreeMap<>(snake);
        for (Pos p:unique) {
            Tile tile = new Tile(p.y(),Math.floorDiv((long)p.x()-minX,width),Math.floorDiv((long)p.z()-minZ,width));
            tiles.computeIfAbsent(tile,ignored -> new ArrayList<>()).add(p);
        }
        Set<Pos> predicted = new HashSet<>();
        LinkedHashSet<Pos> ordered = new LinkedHashSet<>();
        for (var entry:tiles.entrySet()) {
            Tile tile = entry.getKey();
            long idealX = minX+tile.x()*width+radius, idealZ = minZ+tile.z()*width+radius;
            Pos best = null;
            int bestCoverage = 0;
            long bestDistance = Long.MAX_VALUE;
            for (Pos candidate:entry.getValue()) {
                if (predicted.contains(candidate)) continue;
                int coverage = 0;
                for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) for (int dy=0;dy<=1;dy++) {
                    Pos p = candidate.offset(dx,dy,dz);
                    if (unique.contains(p) && !predicted.contains(p)) coverage++;
                }
                long distance = Math.abs(candidate.x()-idealX)+Math.abs(candidate.z()-idealZ);
                if (coverage>bestCoverage || coverage==bestCoverage && distance<bestDistance) {
                    best = candidate;
                    bestCoverage = coverage;
                    bestDistance = distance;
                }
            }
            if (best == null) continue;
            ordered.add(best);
            for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) for (int dy=0;dy<=1;dy++) {
                Pos p = best.offset(dx,dy,dz);
                if (unique.contains(p)) predicted.add(p);
            }
        }
        ordered.addAll(unique);
        return List.copyOf(ordered);
    }

    /** Native upper fallback makes this a coverage hint, never an output proof. */
    public static boolean withinFootprint(Pos center,Pos candidate,int radius) {
        return radius>=0 && radius<=4 && Math.abs((long)center.x()-candidate.x())<=radius
            && Math.abs((long)center.z()-candidate.z())<=radius
            && candidate.y()>=center.y() && (long)candidate.y()<=((long)center.y()+1);
    }
    private record Tile(int y,long x,long z) { }
}
