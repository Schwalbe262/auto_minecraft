package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/**
 * Read-only next-machine preference, evaluated once at a completed visit boundary.
 * It neither moves the player nor grants interaction permission. The normal
 * navigator and action adapter recheck every selected destination. Unvisited
 * machines are never removed, including unloaded or locally unreachable ones.
 */
public final class ProductionVisitOrder {
    static final int MAX_CHECKS_PER_STANDING_CELL=8;
    static final int MAX_VISITED=512;
    static final int MAX_INTERACTION_CHECKS=512;
    private static final int[][] DIRECTIONS={{1,0},{0,1},{-1,0},{0,-1}};
    private static final int[] HEIGHTS={0,1,-1};
    private record Candidate(int index,Pos pos,double distance) { }
    private record Node(Pos pos,double cost,long order) { }
    private ProductionVisitOrder() { }

    /**
     * Returns an index in [firstPending, machines.size()), or -1 for no remaining
     * item. Swap that entry into firstPending only when no action/visit is active.
     * Search exhaustion falls back to current-distance order, never "all done".
     */
    public static int nextIndex(WorldAccess world,Profile profile,List<Poi> machines,int firstPending,double reach) {
        Objects.requireNonNull(world);Objects.requireNonNull(profile);Objects.requireNonNull(machines);
        if (firstPending<0 || firstPending>=machines.size()) return -1;
        PlayerState player=world.player();
        if (player==null || !player.connected() || !Double.isFinite(reach) || reach<=0) return firstPending;
        List<Candidate> remaining=new ArrayList<>();
        for (int index=firstPending;index<machines.size();index++) {
            Pos pos=machines.get(index).pos();remaining.add(new Candidate(index,pos,player.distance(pos)));
        }
        remaining.sort(Comparator.comparingDouble(Candidate::distance).thenComparingInt(Candidate::index));
        int fallback=remaining.get(0).index();
        Pos feet=player.feet();
        if (!world.loaded(feet)) return fallback;
        // Match LocalNavigator's slab/farmland/stair support-cell adjustment.
        if (!world.canStand(feet) && world.loaded(feet.offset(0,1,0)) && world.canStand(feet.offset(0,1,0))) feet=feet.offset(0,1,0);
        TravelDomain bounds=new TravelDomain(profile,feet,remaining.get(0).pos());
        if (!bounds.contains(feet)) return fallback;
        int interactionChecks=0;
        for (Candidate candidate:remaining) {
            if (candidate.distance()>reach+2.5) break;
            if (!world.loaded(candidate.pos())) continue;
            if (interactionChecks++>=MAX_INTERACTION_CHECKS) return fallback;
            if (world.canInteract(candidate.pos(),reach)) return candidate.index();
        }
        if (!world.canStand(feet) || !world.loaded(feet.offset(0,1,0)) || !world.loaded(feet.offset(0,-1,0))) return fallback;
        List<Candidate> goals=remaining.stream().filter(candidate -> world.loaded(candidate.pos())).toList();
        if (goals.isEmpty()) return fallback;
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::cost).thenComparingLong(Node::order));
        Map<Pos,Double> costs=new HashMap<>();Set<Pos> closed=new HashSet<>();
        long order=0;costs.put(feet,0.0);open.add(new Node(feet,0,order++));
        double goalRadiusSquared=(reach+2.5)*(reach+2.5);
        while (!open.isEmpty() && closed.size()<MAX_VISITED) {
            Node node=open.remove();if (!closed.add(node.pos())) continue;
            // Consider all remaining loaded targets from each standing cell,
            // rather than permanently excluding those outside the origin's
            // nearest 64. A dense occluded rack must not spend the whole ray
            // budget before the search can advance along the current aisle.
            // This bounded preference is not a shortest-route guarantee: each
            // cell checks only its nearest few targets, then explores onward.
            List<Candidate> nearby=goals.stream()
                .filter(goal -> node.pos().distanceSquared(goal.pos())<=goalRadiusSquared)
                .sorted(Comparator.comparingDouble((Candidate goal) -> node.pos().distanceSquared(goal.pos()))
                    .thenComparingInt(Candidate::index))
                .limit(MAX_CHECKS_PER_STANDING_CELL).toList();
            for (Candidate goal:nearby) {
                if (interactionChecks++>=MAX_INTERACTION_CHECKS) return fallback;
                if (world.canInteractFrom(node.pos(),goal.pos(),reach)) return goal.index();
            }
            for (int[] direction:DIRECTIONS) for (int dy:HEIGHTS) {
                Pos next=node.pos().offset(direction[0],dy,direction[1]);
                if (closed.contains(next) || !bounds.contains(next) || !world.loaded(next)
                    || !world.loaded(next.offset(0,1,0)) || !world.loaded(next.offset(0,-1,0))
                    || !world.canStand(next) || !world.canTraverse(node.pos(),next)) continue;
                double cost=node.cost()+(dy==0 ? 1 : 1.5);
                if (cost>=costs.getOrDefault(next,Double.POSITIVE_INFINITY)) continue;
                costs.put(next,cost);open.add(new Node(next,cost,order++));
            }
        }
        return fallback;
    }
}
