package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Bounded native-visibility preflight for the existing bases of one registered 2x2 tree. */
public final class LoggingApproachSearch {
    // Each visibility query may test several outline samples. This is a query
    // limit, not a count of native clip calls; the time slice is intentionally soft.
    public static final int STANCES_PER_TICK=64, QUERIES_PER_TICK=16;
    public static final long SLICE_NANOS=2_000_000L;
    private static final double REACH=4, GOAL_RADIUS=REACH+2.5;
    public enum Status { SEARCHING, FOUND, NO_VISIBLE_STANCE, UNLOADED, CHANGED }
    private final WorldAccess source;
    private final LoggingPlot plot;
    private final boolean leaves;
    private final List<Pos> targets,stances,plotCells;
    private final List<BlockData> plotStates;
    private int nextStance,nextTarget,checkedStances,checkedQueries;
    private Pos current,chosenTarget,chosenBase,chosenStance,missing,pendingLeaf,pendingBase;
    private long budgetTick=Long.MIN_VALUE;
    private Status status=Status.SEARCHING;

    public LoggingApproachSearch(WorldAccess world,LoggingPlot plot,List<Pos> targets) {
        this(world,plot,targets,false);
    }
    /** Same finite stance envelope; the returned leaf is not itself action authority. */
    public static LoggingApproachSearch forLeafObstructions(WorldAccess world,LoggingPlot plot,List<Pos> targets) {
        return new LoggingApproachSearch(world,plot,targets,true);
    }
    private LoggingApproachSearch(WorldAccess world,LoggingPlot plot,List<Pos> targets,boolean leaves) {
        if (world==null || plot==null || plot.corner()==null
            || Math.abs((long)plot.corner().x())>29_999_984 || Math.abs((long)plot.corner().z())>29_999_984
            || plot.corner().y() < -2032 || plot.corner().y()>1967
            || targets==null || targets.isEmpty() || targets.size()>4
            || targets.stream().anyMatch(Objects::isNull) || new HashSet<>(targets).size()!=targets.size()
            || !plot.plantingPositions().containsAll(targets))
            throw new IllegalArgumentException("Logging approaches must stay inside one registered 2x2 base");
        source=world; this.plot=plot; this.leaves=leaves; this.targets=List.copyOf(targets);
        plotCells=plot.plantingPositions();
        if (plotCells.stream().anyMatch(p -> !world.loaded(p))
            || targets.stream().anyMatch(p -> !LoggingRules.stump(world.block(p))))
            throw new IllegalArgumentException("Logging approaches require loaded existing stumps");
        plotStates=plotCells.stream().map(world::block).toList();
        Set<Pos> candidates=new HashSet<>();
        // Exactly the union of TerrainPathSearch's interaction-goal envelopes.
        // This cannot establish reachability, authorize a cut, or ignore an outline ray.
        int radius=(int)Math.floor(GOAL_RADIUS);
        for (Pos target:targets) for (int dx=-radius;dx<=radius;dx++)
            for (int dy=-radius;dy<=radius;dy++) for (int dz=-radius;dz<=radius;dz++) {
                if (dx*dx+dy*dy+dz*dz>GOAL_RADIUS*GOAL_RADIUS) continue;
                long x=(long)target.x()+dx,y=(long)target.y()+dy,z=(long)target.z()+dz;
                if (x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || y<Integer.MIN_VALUE+1L || y>Integer.MAX_VALUE-2L
                    || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE) continue;
                candidates.add(new Pos((int)x,(int)y,(int)z));
            }
        PlayerState origin=world.player();
        stances=candidates.stream().sorted(Comparator.comparingDouble(origin::distance)
            .thenComparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).toList();
    }
    public Status status() { return status; }
    public Pos target() { return chosenTarget; }
    public Pos chosenBase() { return chosenBase; }
    public Pos stance() { return chosenStance; }
    public Pos missing() { return missing; }
    public int candidateCount() { return stances.size(); }
    public int checkedStances() { return checkedStances; }
    public int checkedQueries() { return checkedQueries; }
    public boolean matches(WorldAccess world,List<Pos> currentTargets) {
        return source==world && targets.equals(currentTargets)
            && plotCells.stream().allMatch(world::loaded)
            && plotStates.equals(plotCells.stream().map(world::block).toList());
    }
    /** A changed endpoint must be replanned, never promoted to movement/action permission. */
    public boolean endpointValid(WorldAccess world) {
        if (!(status==Status.FOUND && matches(world,targets) && loadedStance(world,chosenStance)
            && world.canStand(chosenStance) && loadedRayBounds(world,chosenStance,chosenTarget)
            && world.canInteractFrom(chosenStance,chosenTarget,REACH))) return false;
        return !leaves || validLeaf(world,chosenTarget) && loadedRayBounds(world,chosenStance,chosenBase)
            && chosenTarget.equals(world.loggingLeafObstructionFrom(chosenStance,chosenBase,REACH));
    }
    public Status advance(WorldAccess world) {
        return advance(world,STANCES_PER_TICK,QUERIES_PER_TICK,SLICE_NANOS);
    }
    /** A repeated poll in one world tick cannot refuel either native-query budget. */
    Status advance(WorldAccess world,int stanceBudget,int queryBudget,long nanosBudget) {
        if (status!=Status.SEARCHING || stanceBudget<=0 || queryBudget<=0 || nanosBudget<=0) return status;
        if (!matches(world,targets)) return status=Status.CHANGED;
        if (budgetTick==world.tick()) return status;
        if (budgetTick>world.tick()) return status=Status.CHANGED;
        budgetTick=world.tick();
        int positions=0,queries=0; long began=System.nanoTime(); boolean worked=false;
        while (status==Status.SEARCHING && (!worked || System.nanoTime()-began<nanosBudget)) {
            if (pendingLeaf!=null) {
                if (queries>=queryBudget) break;
                Pos leaf=pendingLeaf,base=pendingBase;pendingLeaf=pendingBase=null;
                if (!validLeaf(world,leaf)) { if (!world.loaded(leaf)) rememberMissing(leaf);continue; }
                if (!loadedStance(world,current)) { rememberMissing(current);current=null;continue; }
                if (!world.canStand(current)) { current=null;continue; }
                if (!loadedRayBounds(world,current,leaf)) { rememberMissing(current);continue; }
                queries++;checkedQueries++;worked=true;
                if (world.canInteractFrom(current,leaf,REACH)) {
                    chosenTarget=leaf;chosenBase=base;chosenStance=current;return status=Status.FOUND;
                }
                continue;
            }
            if (current==null) {
                if (nextStance>=stances.size()) return status=missing==null ? Status.NO_VISIBLE_STANCE : Status.UNLOADED;
                if (positions>=stanceBudget) break;
                current=stances.get(nextStance++); nextTarget=0; positions++; checkedStances++; worked=true;
                if (!loadedStance(world,current)) { rememberMissing(current); current=null; continue; }
                if (!world.canStand(current)) { current=null; continue; }
            }
            if (nextTarget>=targets.size()) { current=null; continue; }
            Pos target=targets.get(nextTarget);
            if (current.distanceSquared(target)>GOAL_RADIUS*GOAL_RADIUS) { nextTarget++; continue; }
            if (queries>=queryBudget) break;
            nextTarget++; worked=true;
            if (!loadedRayBounds(world,current,target)) { rememberMissing(current); continue; }
            queries++; checkedQueries++;
            if (leaves) {
                Pos leaf=world.loggingLeafObstructionFrom(current,target,REACH);
                if (leaf!=null && LoggingLeafRules.inShell(plot,leaf)) { pendingLeaf=leaf;pendingBase=target; }
            } else if (world.canInteractFrom(current,target,REACH)) {
                chosenTarget=target; chosenBase=target; chosenStance=current; return status=Status.FOUND;
            }
        }
        return status;
    }
    private boolean validLeaf(WorldAccess world,Pos leaf) {
        return LoggingLeafRules.inShell(plot,leaf) && world.loaded(leaf) && world.block(leaf).id().equals(LoggingLeafRules.LEAVES);
    }
    private void rememberMissing(Pos pos) { if (missing==null) missing=pos; }
    private static boolean loadedStance(WorldAccess world,Pos feet) {
        return world.loaded(feet) && world.loaded(feet.offset(0,-1,0)) && world.loaded(feet.offset(0,1,0))
            && world.loaded(feet.offset(0,2,0));
    }
    private static boolean loadedRayBounds(WorldAccess world,Pos feet,Pos target) {
        // Native standing eye height lies within these ordinary player cells.
        // Query only loaded geometry; an unseen chunk is not evidence of no approach.
        for (int x=Math.min(feet.x(),target.x());x<=Math.max(feet.x(),target.x());x++)
            for (int z=Math.min(feet.z(),target.z());z<=Math.max(feet.z(),target.z());z++)
                for (int y=Math.min(feet.y(),target.y());y<=Math.max(feet.y()+2,target.y()+1);y++)
                    if (!world.loaded(new Pos(x,y,z))) return false;
        return true;
    }
}
