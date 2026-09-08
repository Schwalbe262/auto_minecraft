package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Bounded A*: native-verified flat diagonals; no unloaded chunks, block changes or unregistered shortcuts. */
public final class LocalPathfinder {
    private static final int MAX_VISITED = 8192;
    private static final int[][] DIRECTIONS = {{1,0},{0,1},{-1,0},{0,-1},{1,1},{-1,1},{-1,-1},{1,-1}};
    private record Node(Pos pos, double cost, double score) { }

    public List<Pos> find(Pos start, Pos target, double reach, WorldAccess world, Profile profile) {
        return find(start,target,reach,world,profile,Set.of());
    }

    /** Rejected interaction centers remain traversable; only their goal eligibility is excluded. */
    public List<Pos> find(Pos start, Pos target, double reach, WorldAccess world, Profile profile, Set<Pos> excludedGoals) {
        return find(start,target,reach,world,profile,excludedGoals,false);
    }

    /** Read-only geometry preflight; movement still requires an active authorised logging run. */
    public List<Pos> findLogging(Pos start,Pos target,double reach,WorldAccess world,Profile profile) {
        return findLogging(start,target,reach,world,profile,Set.of());
    }

    public List<Pos> findLogging(Pos start,Pos target,double reach,WorldAccess world,Profile profile,Set<Pos> excludedGoals) {
        return find(start,target,reach,world,profile,excludedGoals,true);
    }

    private List<Pos> find(Pos start,Pos target,double reach,WorldAccess world,Profile profile,Set<Pos> excludedGoals,boolean logging) {
        return find(start,target,reach,world,profile,excludedGoals,logging,List.of());
    }

    /** Every remaining soil UP face must be plantable from the same candidate stance. */
    public List<Pos> findLoggingPlanting(Pos start,List<Pos> targets,double reach,WorldAccess world,Profile profile,Set<Pos> excludedGoals) {
        if (!LoggingRules.plantingTargets(profile,targets)) return List.of();
        return find(start,targets.get(0).offset(0,-1,0),reach,world,profile,excludedGoals,true,List.copyOf(targets));
    }

    private List<Pos> find(Pos start,Pos target,double reach,WorldAccess world,Profile profile,Set<Pos> excludedGoals,boolean logging,List<Pos> plantingTargets) {
        TravelDomain bounds = new TravelDomain(profile,start,target);
        if (!world.loaded(start) || !bounds.contains(start)) return List.of();
        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<Pos, Double> cost = new HashMap<>();
        Map<Pos, Pos> parent = new HashMap<>();
        Set<Pos> closed = new HashSet<>();
        cost.put(start, 0.0);
        open.add(new Node(start, 0, heuristic(start, target, reach)));
        while (!open.isEmpty() && closed.size() < MAX_VISITED) {
            Node node = open.remove();
            if (!closed.add(node.pos())) continue;
            // LOS checks can trace many blocks; only candidate cells near the interaction volume need them.
            if (!excludedGoals.contains(node.pos()) && node.pos().distanceSquared(target) <= Math.pow(reach + 2.5,2)
                && (plantingTargets.isEmpty() ? world.canInteractFrom(node.pos(),target,reach)
                    : plantingTargets.stream().allMatch(p -> world.canPlantLoggingSaplingFrom(node.pos(),p,reach)))) return unwind(node.pos(), parent);
            for (int[] direction : DIRECTIONS) {
                boolean diagonal=direction[0]!=0 && direction[1]!=0;
                for (int dy : new int[]{0, 1, -1}) {
                    if (diagonal && dy!=0) continue;
                    Pos next = node.pos().offset(direction[0], dy, direction[1]);
                    if (closed.contains(next) || !bounds.contains(next) || !world.loaded(next)
                        || !world.loaded(next.offset(0,1,0)) || !world.loaded(next.offset(0,-1,0))
                        || !world.canStand(next)) continue;
                    boolean traversable=diagonal ? DiagonalTraversal.canTraverse(node.pos(),next,world,bounds)
                        : world.canTraverse(node.pos(),next);
                    boolean jump=false;
                    if (!traversable && logging && !diagonal) {
                        LoggingJumpEdge edge=new LoggingJumpEdge(node.pos(),next);
                        jump=LoggingJumpRules.verifiedSupports(edge,world,profile) && world.canLoggingJump(edge,profile);
                    }
                    if (!traversable && !logging && !diagonal && bounds.terrain()) {
                        LoggingJumpEdge edge=new LoggingJumpEdge(node.pos(),next);
                        jump=StepUpRules.verifiedSupports(edge,world,profile) && world.canStepUp(edge,profile);
                    }
                    if (!traversable && !jump) continue;
                    // The world adapter validates collision geometry and step height, including modded supports.
                    double nextCost = node.cost() + (jump ? 3 : diagonal ? Math.sqrt(2) : dy == 0 ? 1 : 1.5);
                    if (nextCost >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                    cost.put(next, nextCost);
                    parent.put(next, node.pos());
                    open.add(new Node(next, nextCost, nextCost + heuristic(next, target, reach)));
                }
            }
        }
        return List.of();
    }

    private static double heuristic(Pos from, Pos target, double reach) {
        return Math.max(0, Math.sqrt(from.distanceSquared(target)) - reach - 2);
    }

    private static List<Pos> unwind(Pos last, Map<Pos, Pos> parent) {
        LinkedList<Pos> path = new LinkedList<>();
        for (Pos pos = last; pos != null; pos = parent.get(pos)) path.addFirst(pos);
        return List.copyOf(path);
    }
}
