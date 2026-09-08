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

    /** Cleanup is a distinct second pass, never a prediction that its crops were harvested. */
    public record Route(List<Pos> primary,List<Pos> cleanup,boolean alongX) {
        public Route { primary=List.copyOf(primary); cleanup=List.copyOf(cleanup); }
        public List<Pos> ordered() {
            List<Pos> result=new ArrayList<>(primary); result.addAll(cleanup); return List.copyOf(result);
        }
    }

    /**
     * Put well-spaced existing mature centers first, sweeping alternating strips.
     * Every original position remains in the returned queue, including positions
     * predicted to be covered: only a later observed age change may skip them.
     * Radius is the local half-span hint, not the conservative safety radius.
     */
    public static List<Pos> order(List<Pos> mature,int radius) {
        return plan(mature,mature,radius).ordered();
    }

    /**
     * Anchor strips to the observed crop layout, including unripe/harvested plants.
     * A changing maturity pattern must not shift the next strip sideways. Each
     * strip has one fixed row; mature plants off that row remain in cleanup.
     * All clicks are existing mature positions, including every upper-vine fallback.
     */
    public static Route plan(List<Pos> mature,List<Pos> layout,int radius) {
        LinkedHashSet<Pos> unique = new LinkedHashSet<>(mature);
        if (unique.isEmpty() || radius < 1 || radius > 4) return new Route(List.copyOf(unique),List.of(),true);
        Set<Pos> geometry=new HashSet<>(layout); geometry.addAll(unique);
        int minX=geometry.stream().mapToInt(Pos::x).min().orElseThrow();
        int maxX=geometry.stream().mapToInt(Pos::x).max().orElseThrow();
        int minZ=geometry.stream().mapToInt(Pos::z).min().orElseThrow();
        int maxZ=geometry.stream().mapToInt(Pos::z).max().orElseThrow();
        boolean alongX=(long)maxX-minX >= (long)maxZ-minZ;
        int minAlong=alongX ? minX : minZ, minCross=alongX ? minZ : minX;
        int width = radius*2+1;
        Comparator<Tile> snake = Comparator.comparingInt(Tile::y).thenComparingLong(Tile::cross)
            .thenComparingLong(t -> (t.cross()&1)==0 ? t.along() : -t.along());
        Map<Tile,List<Pos>> tiles = new TreeMap<>(snake);
        for (Pos p:unique) {
            Tile tile = new Tile(p.y(),Math.floorDiv((long)along(p,alongX)-minAlong,width),Math.floorDiv((long)cross(p,alongX)-minCross,width));
            tiles.computeIfAbsent(tile,ignored -> new ArrayList<>()).add(p);
        }
        Map<Row,Integer> rows=new java.util.HashMap<>();
        for (Pos p:geometry) {
            Row row=new Row(p.y(),Math.floorDiv((long)cross(p,alongX)-minCross,width));
            long ideal=minCross+row.strip()*width+radius;
            int candidate=cross(p,alongX);
            rows.merge(row,candidate,(old,next) -> Math.abs((long)next-ideal)<Math.abs((long)old-ideal)
                || Math.abs((long)next-ideal)==Math.abs((long)old-ideal) && next<old ? next : old);
        }
        Set<Pos> predicted = new HashSet<>();
        LinkedHashSet<Pos> ordered = new LinkedHashSet<>();
        for (var entry:tiles.entrySet()) {
            Tile tile = entry.getKey();
            long idealAlong = minAlong+tile.along()*width+radius;
            int fixedRow=rows.get(new Row(tile.y(),tile.cross()));
            Pos best = null;
            int bestCoverage = 0;
            long bestDistance = Long.MAX_VALUE;
            for (Pos candidate:entry.getValue()) {
                if (predicted.contains(candidate) || cross(candidate,alongX)!=fixedRow) continue;
                int coverage = 0;
                for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) for (int dy=0;dy<=1;dy++) {
                    Pos p = candidate.offset(dx,dy,dz);
                    if (unique.contains(p) && !predicted.contains(p)) coverage++;
                }
                long distance = Math.abs(along(candidate,alongX)-idealAlong);
                if (coverage>bestCoverage || coverage==bestCoverage && (distance<bestDistance
                        || distance==bestDistance && (best==null || along(candidate,alongX)<along(best,alongX)))) {
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
        // Finish the regular strips before returning to observed leftovers. Sort
        // leftovers spatially, not by caller iteration order or nearest-neighbour hops.
        Comparator<Pos> cleanupOrder=Comparator.comparingInt(Pos::y)
            .thenComparingInt(p -> cross(p,alongX))
            .thenComparingLong(p -> (((long)cross(p,alongX)-minCross)&1)==0 ? along(p,alongX) : -(long)along(p,alongX));
        List<Pos> cleanup=unique.stream().filter(p -> !ordered.contains(p)).sorted(cleanupOrder).toList();
        return new Route(List.copyOf(ordered),cleanup,alongX);
    }

    private static int along(Pos p,boolean alongX) { return alongX ? p.x() : p.z(); }
    private static int cross(Pos p,boolean alongX) { return alongX ? p.z() : p.x(); }

    /** Native upper fallback makes this a coverage hint, never an output proof. */
    public static boolean withinFootprint(Pos center,Pos candidate,int radius) {
        return radius>=0 && radius<=4 && Math.abs((long)center.x()-candidate.x())<=radius
            && Math.abs((long)center.z()-candidate.z())<=radius
            && candidate.y()>=center.y() && (long)candidate.y()<=((long)center.y()+1);
    }
    private record Tile(int y,long along,long cross) { }
    private record Row(int y,long strip) { }
}
