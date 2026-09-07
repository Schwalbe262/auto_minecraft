package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Bounded A*: no diagonals, unloaded chunks, block changes, or unregistered shortcuts. */
public final class LocalPathfinder {
    private static final int MAX_VISITED = 8192;
    private static final int[][] DIRECTIONS = {{1,0},{0,1},{-1,0},{0,-1}};
    private record Node(Pos pos, double cost, double score) { }

    public List<Pos> find(Pos start, Pos target, double reach, WorldAccess world, Profile profile) {
        ProfileBounds bounds = new ProfileBounds(profile);
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
            if (node.pos().distanceSquared(target) <= Math.pow(reach + 2.5,2)
                && world.canInteractFrom(node.pos(), target, reach)) return unwind(node.pos(), parent);
            for (int[] direction : DIRECTIONS) {
                for (int dy : new int[]{0, 1, -1}) {
                    Pos next = node.pos().offset(direction[0], dy, direction[1]);
                    if (closed.contains(next) || !bounds.contains(next) || !world.loaded(next)
                        || !world.loaded(next.offset(0,1,0)) || !world.loaded(next.offset(0,-1,0))
                        || !world.canStand(next) || !world.canTraverse(node.pos(), next)) continue;
                    // The world adapter validates collision geometry and step height, including modded supports.
                    double nextCost = node.cost() + (dy == 0 ? 1 : 1.5);
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
