package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Resumable terrain A*: read-only, bounded, and never an interaction permission. */
public final class TerrainPathSearch {
    public static final int NODES_PER_TICK=128, LOS_PER_TICK=32, INITIAL_NODE_LIMIT=8192, MAX_VISITED=65536;
    public static final long SLICE_NANOS=2_000_000L;
    public enum Goal { INTERACTION, POSITION, OBSERVE }
    public enum Status { SEARCHING, FOUND, FRONTIER, NO_PATH, SEARCH_LIMIT, INVALID_START }
    /** Direction and complete XYZ matter; another floor is not the same failed frontier. */
    public record Frontier(Pos standing,Pos crossing,boolean domainEdge) { }
    private record Node(Pos pos,double cost,double score,double hint,long order) { }
    private static final int[][] EDGES={{1,0,0},{0,0,1},{-1,0,0},{0,0,-1},
        {1,1,0},{0,1,1},{-1,1,0},{0,1,-1},{1,-1,0},{0,-1,1},{-1,-1,0},{0,-1,-1},
        {1,0,1},{-1,0,1},{-1,0,-1},{1,0,-1}};
    private final Pos start,target;
    private final double reach;
    private final Profile profile;
    private final TravelDomain domain;
    private final Set<Pos> excluded;
    private final Set<Frontier> rejected;
    private final boolean logging,frontiers,allowStepUp;
    private final Goal goal;
    private final List<Pos> planting;
    private final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::score)
        .thenComparingDouble(Node::hint).thenComparingLong(Node::order));
    private final Map<Pos,Double> costs=new HashMap<>();
    private final Map<Pos,Pos> parent=new HashMap<>();
    private final Set<Pos> closed=new HashSet<>();
    private Status status=Status.SEARCHING;
    private Node current;
    private int edgeIndex,nodeLimit=INITIAL_NODE_LIMIT,lastNodes,lastLos;
    private boolean goalChecked;
    private long order;
    private Frontier frontier;
    private double frontierScore=Double.POSITIVE_INFINITY;
    private List<Pos> path=List.of();

    public TerrainPathSearch(Pos start,Pos target,double reach,WorldAccess world,Profile profile,TravelDomain domain,
            Set<Pos> excluded,Set<Frontier> rejected,boolean logging,Goal goal,List<Pos> planting,boolean frontiers) {
        this(start,target,reach,world,profile,domain,excluded,rejected,logging,goal,planting,frontiers,true);
    }
    public TerrainPathSearch(Pos start,Pos target,double reach,WorldAccess world,Profile profile,TravelDomain domain,
            Set<Pos> excluded,Set<Frontier> rejected,boolean logging,Goal goal,List<Pos> planting,boolean frontiers,boolean allowStepUp) {
        this.start=start;this.target=target;this.reach=reach;this.profile=profile;this.domain=domain;
        this.excluded=Set.copyOf(excluded);this.rejected=Set.copyOf(rejected);this.logging=logging;
        this.goal=goal;this.planting=List.copyOf(planting);this.frontiers=frontiers && domain.terrain();this.allowStepUp=allowStepUp;
        if (!Double.isFinite(reach) || reach<0 || !domain.contains(start) || !loadedStance(world,start) || !world.canStand(start)) {
            status=Status.INVALID_START;return;
        }
        costs.put(start,0.0);open.add(new Node(start,0,heuristic(start,target,reach),domain.hintDistance(start),order++));
    }
    public Status status() { return status; }
    public List<Pos> path() { return path; }
    public Frontier frontier() { return frontier; }
    public int expanded() { return closed.size(); }
    public int nodeLimit() { return nodeLimit; }
    public int lastExpanded() { return lastNodes; }
    public int lastLosChecks() { return lastLos; }

    /** Soft elapsed limit is checked between native queries; a single native call is not interrupted. */
    public Status advance(WorldAccess world,int nodeBudget,int losBudget,long nanosBudget) {
        lastNodes=0;lastLos=0;
        if (status!=Status.SEARCHING || nodeBudget<=0 || losBudget<0 || nanosBudget<=0) return status;
        long began=System.nanoTime();boolean didWork=false;
        while (status==Status.SEARCHING) {
            if (didWork && System.nanoTime()-began>=nanosBudget) break;
            if (current==null) {
                if (lastNodes>=nodeBudget) break;
                if (closed.size()>=nodeLimit) {
                    if (nodeLimit>=MAX_VISITED) { finishBoundary(Status.SEARCH_LIMIT);break; }
                    nodeLimit=Math.min(MAX_VISITED,nodeLimit*2);
                    break; // Increase the allowance next slice without restarting the same search.
                }
                while (!open.isEmpty() && (closed.contains(open.peek().pos())
                    || open.peek().cost()>costs.getOrDefault(open.peek().pos(),Double.POSITIVE_INFINITY))) open.remove();
                if (frontiers && frontier!=null && (!world.loaded(target) || !domain.contains(target))
                    && (open.isEmpty() || open.peek().score()>=frontierScore)) { finishBoundary(Status.NO_PATH);break; }
                if (open.isEmpty()) { finishBoundary(Status.NO_PATH);break; }
                current=open.remove();closed.add(current.pos());lastNodes++;
                edgeIndex=0;goalChecked=false;didWork=true;
                if (!loadedStance(world,current.pos()) || !world.canStand(current.pos())) { current=null;continue; }
            }
            if (!goalChecked) {
                boolean nearby=TravelDomain.distanceSquared(current.pos(),target)<=Math.pow(reach+2.5,2);
                int needed=!excluded.contains(current.pos()) && nearby && world.loaded(target) && goal==Goal.INTERACTION ? Math.max(1,planting.size()) : 0;
                if (lastLos+needed>losBudget) break;
                goalChecked=true;
                if (!excluded.contains(current.pos()) && nearby && goalAt(world,current.pos())) {
                    status=Status.FOUND;path=unwind(current.pos(),parent);break;
                }
            }
            if (edgeIndex>=EDGES.length) { current=null;continue; }
            int[] offset=EDGES[edgeIndex++];didWork=true;
            Pos next=offset(current.pos(),offset[0],offset[1],offset[2]);
            if (next==null || closed.contains(next)) continue;
            if (!domain.contains(next)) { considerFrontier(current,next,true);continue; }
            Pos missing=missingStance(world,next);
            if (missing!=null) { considerFrontier(current,missing,false);continue; }
            if (!world.canStand(next)) continue;
            boolean diagonal=offset[0]!=0 && offset[2]!=0;
            boolean traversable=diagonal ? DiagonalTraversal.canTraverse(current.pos(),next,world,domain)
                : world.canTraverse(current.pos(),next);
            boolean jump=false;
            if (!traversable && !diagonal && allowStepUp) {
                LoggingJumpEdge edge=new LoggingJumpEdge(current.pos(),next);
                jump=logging ? LoggingJumpRules.verifiedSupports(edge,world,profile) && world.canLoggingJump(edge,profile)
                    : domain.terrain() && StepUpRules.verifiedSupports(edge,world,profile) && world.canStepUp(edge,profile);
            }
            if (!traversable && !jump) continue;
            double cost=current.cost()+(jump ? 3 : diagonal ? Math.sqrt(2) : offset[1]==0 ? 1 : 1.5);
            if (cost>=costs.getOrDefault(next,Double.POSITIVE_INFINITY)) continue;
            costs.put(next,cost);parent.put(next,current.pos());
            open.add(new Node(next,cost,cost+heuristic(next,target,reach),domain.hintDistance(next),order++));
        }
        return status;
    }
    private boolean goalAt(WorldAccess world,Pos feet) {
        if (!world.loaded(target)) return false;
        if (!planting.isEmpty()) {
            for (Pos p:planting) { lastLos++;if (!world.canPlantLoggingSaplingFrom(feet,p,reach)) return false; }
            return true;
        }
        if (goal==Goal.INTERACTION) { lastLos++;return world.canInteractFrom(feet,target,reach); }
        if (goal==Goal.OBSERVE) return TravelDomain.distanceSquared(feet,target)<=reach*reach;
        if (!loadedStance(world,target) || !world.canStand(target)) return false;
        double fromY=world.standingY(feet),toY=world.standingY(target);
        if (!Double.isFinite(fromY) || !Double.isFinite(toY)) return false;
        return Math.pow((double)feet.x()-target.x(),2)+Math.pow((double)feet.z()-target.z(),2)
            +Math.pow(fromY-toY,2)<=reach*reach;
    }
    private void considerFrontier(Node node,Pos crossing,boolean domainEdge) {
        if (!frontiers) return;
        Frontier candidate=new Frontier(node.pos(),crossing,domainEdge);
        if (rejected.contains(candidate)) return;
        double score=node.cost()+heuristic(crossing,target,reach);
        if (score<frontierScore) { frontier=candidate;frontierScore=score; }
    }
    private void finishBoundary(Status otherwise) {
        if (frontiers && frontier!=null) { status=Status.FRONTIER;path=unwind(frontier.standing(),parent); }
        else status=otherwise;
    }
    static boolean loadedStance(WorldAccess world,Pos feet) { return missingStance(world,feet)==null; }
    private static Pos missingStance(WorldAccess world,Pos feet) {
        for (int dy:new int[]{0,-1,1}) { Pos p=offset(feet,0,dy,0);if (p==null || !world.loaded(p)) return p==null ? feet : p; }
        return null;
    }
    private static Pos offset(Pos p,int dx,int dy,int dz) {
        long x=(long)p.x()+dx,y=(long)p.y()+dy,z=(long)p.z()+dz;
        return x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || y<Integer.MIN_VALUE || y>Integer.MAX_VALUE
            || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE ? null : new Pos((int)x,(int)y,(int)z);
    }
    private static double heuristic(Pos from,Pos target,double reach) { return Math.max(0,Math.sqrt(TravelDomain.distanceSquared(from,target))-reach-2); }
    private static List<Pos> unwind(Pos last,Map<Pos,Pos> parent) {
        LinkedList<Pos> path=new LinkedList<>();for (Pos p=last;p!=null;p=parent.get(p)) path.addFirst(p);return List.copyOf(path);
    }
}
