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
     * Primary centers stay on fixed layout rows. The sweep inserts nearby repairs
     * before leaving each tile; cleanup retains the other observed crop targets.
     * Neither list is evidence that a crop was actually harvested.
     */
    public record Route(List<Pos> primary,List<Pos> cleanup,boolean alongX,List<Pos> sweep) {
        public Route {
            primary=List.copyOf(primary); cleanup=List.copyOf(cleanup); sweep=List.copyOf(sweep);
        }
        public Route(List<Pos> primary,List<Pos> cleanup,boolean alongX) {
            this(primary,cleanup,alongX,primary);
        }
        public List<Pos> ordered() {
            List<Pos> result=new ArrayList<>(sweep); result.addAll(cleanup); return List.copyOf(result);
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
     * strip has one fixed row. If its available mature centers leave coverage
     * gaps, schedule corrective clicks locally before advancing to the next tile.
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
        List<Pos> primary = new ArrayList<>();
        LinkedHashSet<Pos> sweep = new LinkedHashSet<>();
        for (var entry:tiles.entrySet()) {
            Tile tile = entry.getKey();
            long idealAlong = minAlong+tile.along()*width+radius;
            int fixedRow=rows.get(new Row(tile.y(),tile.cross()));
            Pos best = null;
            int bestCoverage = 0;
            long bestDistance = Long.MAX_VALUE;
            for (Pos candidate:entry.getValue()) {
                if (predicted.contains(candidate) || cross(candidate,alongX)!=fixedRow) continue;
                int coverage = coverage(candidate,unique,predicted,radius);
                long distance = Math.abs(along(candidate,alongX)-idealAlong);
                if (coverage>bestCoverage || coverage==bestCoverage && (distance<bestDistance
                        || distance==bestDistance && (best==null || along(candidate,alongX)<along(best,alongX)))) {
                    best = candidate;
                    bestCoverage = coverage;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                primary.add(best);
                sweep.add(best);
                predict(best,unique,predicted,radius);
            }
            // An unripe anchor or sprinkler can shift the only usable row center
            // and leave a sliver behind. Cover it while still in the same tile.
            // Every repair is itself unpredicted and mature in the input, so
            // each iteration makes progress without inventing a click target.
            Set<Pos> local=new HashSet<>(entry.getValue());
            while (true) {
                Pos repair=null;
                int bestLocal=0, bestTotal=0;
                long bestRowDistance=Long.MAX_VALUE, bestAlongDistance=Long.MAX_VALUE;
                for (Pos candidate:entry.getValue()) {
                    if (predicted.contains(candidate)) continue;
                    int localCoverage=coverage(candidate,local,predicted,radius);
                    int totalCoverage=coverage(candidate,unique,predicted,radius);
                    long rowDistance=Math.abs((long)cross(candidate,alongX)-fixedRow);
                    long alongDistance=Math.abs(along(candidate,alongX)-idealAlong);
                    if (localCoverage>bestLocal || localCoverage==bestLocal && (totalCoverage>bestTotal
                            || totalCoverage==bestTotal && (rowDistance<bestRowDistance
                            || rowDistance==bestRowDistance && (alongDistance<bestAlongDistance
                            || alongDistance==bestAlongDistance && before(candidate,repair,alongX,tile.cross()))))) {
                        repair=candidate;
                        bestLocal=localCoverage;
                        bestTotal=totalCoverage;
                        bestRowDistance=rowDistance;
                        bestAlongDistance=alongDistance;
                    }
                }
                if (repair==null) break;
                if (cross(repair,alongX)==fixedRow) primary.add(repair);
                sweep.add(repair);
                predict(repair,unique,predicted,radius);
            }
        }
        // Retain every original observation, including predicted upper fallback
        // cells. Only the caller's observed age change can actually skip one.
        Comparator<Pos> cleanupOrder=Comparator.comparingInt(Pos::y)
            .thenComparingInt(p -> cross(p,alongX))
            .thenComparingLong(p -> (((long)cross(p,alongX)-minCross)&1)==0 ? along(p,alongX) : -(long)along(p,alongX));
        List<Pos> cleanup=unique.stream().filter(p -> !sweep.contains(p)).sorted(cleanupOrder).toList();
        return new Route(primary,cleanup,alongX,List.copyOf(sweep));
    }

    private static int coverage(Pos center,Set<Pos> crops,Set<Pos> predicted,int radius) {
        int count=0;
        for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) for (int dy=0;dy<=1;dy++) {
            Pos p=center.offset(dx,dy,dz);
            if (crops.contains(p) && !predicted.contains(p) && withinFootprint(center,p,radius)) count++;
        }
        return count;
    }

    private static void predict(Pos center,Set<Pos> crops,Set<Pos> predicted,int radius) {
        for (int dx=-radius;dx<=radius;dx++) for (int dz=-radius;dz<=radius;dz++) for (int dy=0;dy<=1;dy++) {
            Pos p=center.offset(dx,dy,dz);
            if (crops.contains(p) && withinFootprint(center,p,radius)) predicted.add(p);
        }
    }

    private static boolean before(Pos candidate,Pos previous,boolean alongX,long strip) {
        if (previous==null) return true;
        int direction=(strip&1)==0 ? 1 : -1;
        int comparison=Integer.compare(along(candidate,alongX),along(previous,alongX));
        return comparison!=0 ? comparison*direction<0 : cross(candidate,alongX)<cross(previous,alongX);
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
