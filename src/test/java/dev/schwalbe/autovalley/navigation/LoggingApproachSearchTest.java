package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingApproachSearchTest {
    @Test void blockedRegisteredCornerCanSelectAnotherExistingBaseOfTheSamePlot() {
        World world=new World(); Pos alternate=world.plot.corner().offset(1,0,0),feet=new Pos(3,63,0);
        world.standing.add(feet); world.visible.put(feet,Set.of(alternate));
        LoggingApproachSearch search=world.search();
        assertEquals(LoggingApproachSearch.Status.FOUND,finish(search,world));
        assertEquals(alternate,search.target()); assertEquals(feet,search.stance());
        assertTrue(search.endpointValid(world));
    }
    @Test void fullNativeGoalEnvelopeIncludesSixBlockFeetOffsetsAndVerticalCandidates() {
        World world=new World(); Pos feet=world.plot.corner().offset(0,-6,0);
        world.standing.add(feet); world.visible.put(feet,Set.of(world.plot.corner()));
        LoggingApproachSearch search=world.search();
        assertEquals(LoggingApproachSearch.Status.FOUND,finish(search,world));
        assertEquals(feet,search.stance());
        assertTrue(search.candidateCount()<2000,"No world-wide A* for proving that any goal exists");
    }
    @Test void noVisibleStanceFinishesWithinTheFiniteEnvelopeWithoutInventingAnEndpoint() {
        World world=new World(); world.allStanding=true;
        LoggingApproachSearch search=world.search();
        assertEquals(LoggingApproachSearch.Status.NO_VISIBLE_STANCE,finish(search,world));
        assertEquals(search.candidateCount(),search.checkedStances());
        assertTrue(search.checkedQueries()<=4*search.candidateCount());
        assertNull(search.target()); assertNull(search.stance());
    }
    @Test void repeatedSameTickPollingCannotRefuelStanceOrRayBudgets() {
        World world=new World(); world.allStanding=true;
        LoggingApproachSearch search=world.search();
        search.advance(world,64,3,Long.MAX_VALUE);
        assertEquals(3,search.checkedQueries()); int stances=search.checkedStances();
        for(int i=0;i<100;i++) search.advance(world,64,3,Long.MAX_VALUE);
        assertEquals(3,search.checkedQueries()); assertEquals(stances,search.checkedStances());
        world.tick++;
        search.advance(world,64,3,Long.MAX_VALUE); assertEquals(6,search.checkedQueries());
    }
    @Test void unsupportedCellsSpendOnlyTheBoundedStanceBudgetAndNeverRaycast() {
        World world=new World(); LoggingApproachSearch search=world.search();
        search.advance(world,7,16,Long.MAX_VALUE);
        assertEquals(7,search.checkedStances()); assertEquals(0,world.rays);
    }
    @Test void unloadedSupportOrRayBoundsAreUnknownNotProofOfNoVisibleGoal() {
        World world=new World(); Pos feet=new Pos(3,63,0);
        world.standing.add(feet); world.visible.put(feet,Set.of(world.plot.corner()));
        world.unloaded.add(new Pos(2,64,0));
        LoggingApproachSearch search=world.search();
        assertEquals(LoggingApproachSearch.Status.UNLOADED,finish(search,world));
        assertNull(search.target()); assertNotNull(search.missing());
        assertEquals(0,world.rays,"Native query never sees the unloaded ray region");
    }
    @Test void passableLeavesNeedNativeStandingAndOutlineProofRatherThanABlockIdException() {
        World world=new World(); Pos feet=new Pos(3,63,0);
        world.blocks.put(feet,new BlockData(feet,"minecraft:spruce_leaves",Map.of()));
        world.standing.add(feet);
        LoggingApproachSearch blocked=world.search();
        assertEquals(LoggingApproachSearch.Status.NO_VISIBLE_STANCE,finish(blocked,world));
        world.visible.put(feet,Set.of(world.plot.corner())); world.tick++;
        assertEquals(LoggingApproachSearch.Status.FOUND,finish(world.search(),world));
        world.standing.clear(); world.tick++;
        assertEquals(LoggingApproachSearch.Status.NO_VISIBLE_STANCE,finish(world.search(),world));
    }
    @Test void changedNativeStumpStateInvalidatesAnUnfinishedPreflight() {
        World world=new World(); LoggingApproachSearch search=world.search();
        search.advance(world,1,1,Long.MAX_VALUE);
        Pos target=world.plot.corner(); world.blocks.put(target,new BlockData(target,LoggingRules.CHOPPED_LOG,Map.of("chops","1")));
        world.tick++;
        assertEquals(LoggingApproachSearch.Status.CHANGED,search.advance(world));
        assertFalse(search.matches(world,world.plot.plantingPositions()));
    }
    @Test void aNonTargetBaseChangingAlsoInvalidatesTheWholePlotSnapshot() {
        World world=new World(); Pos target=world.plot.corner();
        LoggingApproachSearch search=new LoggingApproachSearch(world,world.plot,List.of(target));
        search.advance(world,1,1,Long.MAX_VALUE);
        Pos other=target.offset(1,0,1); world.blocks.put(other,new BlockData(other,"minecraft:air",Map.of())); world.tick++;
        assertFalse(search.matches(world,List.of(target)));
        assertEquals(LoggingApproachSearch.Status.CHANGED,search.advance(world));
    }
    @Test void endpointRevalidationRejectsChangedSupportOutlineAndWorldIdentity() {
        World world=new World(); Pos feet=new Pos(3,63,0);
        world.standing.add(feet); world.visible.put(feet,Set.of(world.plot.corner()));
        LoggingApproachSearch search=world.search(); finish(search,world);
        assertTrue(search.endpointValid(world));
        world.visible.clear(); assertFalse(search.endpointValid(world));
        world.visible.put(feet,Set.of(world.plot.corner())); world.standing.clear(); assertFalse(search.endpointValid(world));
        assertFalse(search.endpointValid(new World()));
    }
    @Test void arbitraryOrDuplicateTargetsAndAnotherWorldAreNeverAccepted() {
        World world=new World(); Pos outside=world.plot.corner().offset(2,0,0);
        assertThrows(IllegalArgumentException.class,() -> new LoggingApproachSearch(world,world.plot,List.of(outside)));
        assertThrows(IllegalArgumentException.class,() -> new LoggingApproachSearch(world,world.plot,List.of(world.plot.corner(),world.plot.corner())));
        LoggingPlot overflow=new LoggingPlot("invalid",new Pos(Integer.MAX_VALUE,64,0));
        assertThrows(IllegalArgumentException.class,() -> new LoggingApproachSearch(world,overflow,overflow.plantingPositions()));
        LoggingApproachSearch search=world.search();
        assertEquals(LoggingApproachSearch.Status.CHANGED,search.advance(new World()));
    }
    @Test void aRewoundWorldTickCannotRestartTheSamePreflightBudget() {
        World world=new World(); world.tick=10; LoggingApproachSearch search=world.search();
        search.advance(world,1,1,Long.MAX_VALUE); world.tick=9;
        assertEquals(LoggingApproachSearch.Status.CHANGED,search.advance(world));
    }
    private static LoggingApproachSearch.Status finish(LoggingApproachSearch search,World world) {
        for(int i=0;i<2000 && search.status()==LoggingApproachSearch.Status.SEARCHING;i++,world.tick++)
            search.advance(world,64,16,Long.MAX_VALUE);
        assertNotEquals(LoggingApproachSearch.Status.SEARCHING,search.status()); return search.status();
    }
    private static final class World implements WorldAccess {
        final LoggingPlot plot=new LoggingPlot("test",new Pos(0,64,0));
        final Set<Pos> standing=new HashSet<>(),unloaded=new HashSet<>();
        final Map<Pos,Set<Pos>> visible=new HashMap<>(); final Map<Pos,BlockData> blocks=new HashMap<>();
        long tick; int rays; boolean allStanding;
        World() { for(Pos p:plot.plantingPositions()) blocks.put(p,new BlockData(p,LoggingRules.LOG,Map.of())); }
        LoggingApproachSearch search() { return new LoggingApproachSearch(this,plot,plot.plantingPositions()); }
        public long tick() { return tick; }
        public long dayTime() { return 0; }
        public PlayerState player() { return new PlayerState(3.5,63,.5,0,0,true,false,20,20,2,true,true); }
        public BlockData block(Pos p) { assertTrue(loaded(p)); return blocks.getOrDefault(p,new BlockData(p,"minecraft:air",Map.of())); }
        public boolean loaded(Pos p) { return !unloaded.contains(p); }
        public boolean canStand(Pos p) { assertTrue(loaded(p)); return allStanding || standing.contains(p); }
        public boolean canInteractFrom(Pos feet,Pos target,double reach) {
            assertEquals(4,reach); assertTrue(canStand(feet)); assertTrue(loaded(target)); assertTrue(plot.plantingPositions().contains(target));
            rays++; return visible.getOrDefault(feet,Set.of()).contains(target);
        }
        public boolean canTraverse(Pos from,Pos to) { throw new AssertionError("Preflight must not search or execute movement"); }
        public List<BlockData> scan(Pos center,int horizontal,int vertical) { throw new AssertionError("No unbounded scanning API"); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
    }
}
