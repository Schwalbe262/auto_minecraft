package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.BiPredicate;
import static org.junit.jupiter.api.Assertions.*;

class ProductionVisitOrderTest {
    private static final Pos START=new Pos(0,64,0);
    private static Poi keg(int x,int y,int z) { return new Poi(new Pos(x,y,z),PoiKind.WINE_KEG,"Machine",null); }
    private static Profile bounded(int x1,int x2,int z1,int z2) {
        Profile p=new Profile();p.farms.add(new Farm("Registered work area",new Pos(x1,63,z1),new Pos(x2,66,z2)));return p;
    }
    @Test void currentlyNativeInteractableMachineWinsWithoutAnyPathSearch() {
        World world=new World();List<Poi> machines=List.of(keg(1,64,1),keg(4,64,0));
        world.actual.add(machines.get(1).pos());
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-2,10,-2,3),machines,0,4));
        assertEquals(0,world.predictedChecks);assertEquals(0,world.traverseChecks);
    }
    @Test void nearestCurrentInteractableIsRecomputedAfterEarlierTargets() {
        World world=new World();List<Poi> machines=List.of(keg(0,64,0),keg(4,64,0),keg(1,64,0));
        machines.forEach(p -> world.actual.add(p.pos()));
        assertEquals(2,ProductionVisitOrder.nextIndex(world,bounded(-2,10,-2,3),machines,1,4));
        assertEquals(1,world.actualChecks);assertEquals(-1,ProductionVisitOrder.nextIndex(world,new Profile(),machines,3,4));
    }
    @Test void slightlyFartherSameAisleBeatsVisuallyNearMachineBehindWall() {
        World world=new World();List<Poi> machines=List.of(keg(1,64,2),keg(6,64,0));
        for (int x=0;x<=20;x++) { world.standing.add(new Pos(x,64,0));world.standing.add(new Pos(x,64,2)); }
        world.standing.add(new Pos(20,64,1));
        world.predicted=(feet,target) -> feet.equals(target);
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-1,21,-1,3),machines,0,1));
        assertTrue(world.traverseChecks>0);assertTrue(world.traverseChecks<400);
    }
    @Test void validatedStepCostsUseExistingGeometryAndNoDiagonalShortcut() {
        World world=new World();List<Poi> machines=List.of(keg(2,66,0),keg(0,64,3));
        world.standing.addAll(List.of(START,new Pos(1,65,0),new Pos(2,66,0),new Pos(0,64,1),new Pos(0,64,2),new Pos(0,64,3)));
        world.predicted=(feet,target) -> feet.equals(target);
        assertEquals(0,ProductionVisitOrder.nextIndex(world,bounded(-1,3,-1,4),machines,0,1));
        assertTrue(world.edges.stream().allMatch(edge -> Math.abs(edge.get(0).x()-edge.get(1).x())+Math.abs(edge.get(0).z()-edge.get(1).z())==1));
        assertTrue(world.edges.stream().anyMatch(edge -> edge.get(0).y()!=edge.get(1).y()));
    }
    @Test void unloadedGoalAndUnloadedSupportCannotBeChosenBySearch() {
        World world=new World();List<Poi> machines=List.of(keg(1,64,0),keg(0,64,3));
        world.unloaded.add(machines.get(0).pos());
        for (int z=0;z<=3;z++) world.standing.add(new Pos(0,64,z));
        world.predicted=(feet,target) -> feet.equals(target);
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-2,4,-2,4),machines,0,1));
        world.unloaded.add(new Pos(0,63,1));
        assertEquals(0,ProductionVisitOrder.nextIndex(world,bounded(-2,4,-2,4),machines,0,1),"No reachable goal falls back without hiding the unloaded pending target");
    }
    @Test void registeredBoundsAndTraverseVetoRemainAuthoritative() {
        World world=new World();List<Poi> machines=List.of(keg(2,64,0),keg(0,64,4));
        for (int x=0;x<=2;x++) world.standing.add(new Pos(x,64,0));
        for (int z=0;z<=4;z++) world.standing.add(new Pos(0,64,z));
        world.predicted=(feet,target) -> feet.equals(target);world.blockedEdges.add(List.of(START,new Pos(1,64,0)));
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-1,3,-1,5),machines,0,1));
        assertEquals(0,ProductionVisitOrder.nextIndex(world,bounded(-1,3,-1,0),machines,0,1));
    }
    @Test void slabSupportCellAdjustmentMatchesNavigator() {
        World world=new World();world.player=new PlayerState(.5,63.5,.5,0,0,true,false,20,20,0,true,true);
        List<Poi> machines=List.of(keg(2,64,0),keg(0,64,3));
        world.standing.addAll(List.of(START,new Pos(0,64,1),new Pos(0,64,2),new Pos(0,64,3)));
        world.predicted=(feet,target) -> feet.equals(target);
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-1,3,-1,4),machines,0,1));
    }
    @Test void interactionBudgetAndNodeBudgetNeverDropPendingMembers() {
        World world=new World();world.infiniteFloor=true;world.predicted=(feet,target) -> false;
        List<Poi> machines=new ArrayList<>();for (int i=0;i<384;i++) machines.add(keg(i%16,64,1+i/16));
        List<Poi> original=List.copyOf(machines);
        int choice=ProductionVisitOrder.nextIndex(world,bounded(-20,80,-20,80),machines,0,4);
        assertEquals(0,choice);assertEquals(original,machines);
        assertTrue(world.actualChecks+world.predictedChecks<=ProductionVisitOrder.MAX_INTERACTION_CHECKS);
        assertTrue(world.traverseChecks<=ProductionVisitOrder.MAX_VISITED*12);
    }
    @Test void missingStartBoundsOrUnsupportedOriginFallsBackWithoutInventingAPath() {
        World world=new World();List<Poi> machines=List.of(keg(4,64,0),keg(2,64,0));
        assertEquals(1,ProductionVisitOrder.nextIndex(world,new Profile(),machines,0,4));
        assertEquals(0,world.predictedChecks);assertEquals(0,world.traverseChecks);
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-1,5,-1,1),machines,0,4));
        assertEquals(0,world.traverseChecks);
    }

    private static final class World implements WorldAccess {
        PlayerState player=new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true);
        final Set<Pos> standing=new HashSet<>(),unloaded=new HashSet<>(),actual=new HashSet<>();
        final Set<List<Pos>> blockedEdges=new HashSet<>();final List<List<Pos>> edges=new ArrayList<>();
        BiPredicate<Pos,Pos> predicted=(feet,target) -> false;
        int actualChecks,predictedChecks,traverseChecks;boolean infiniteFloor;
        public long tick(){return 0;}public long dayTime(){return 0;}public PlayerState player(){return player;}
        public BlockData block(Pos pos){throw new AssertionError("Visit selection must not inspect or use machine state");}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}
        public boolean canStand(Pos pos){return standing.contains(pos) || infiniteFloor && pos.y()==64;}
        public boolean canTraverse(Pos from,Pos to){traverseChecks++;edges.add(List.of(from,to));return !blockedEdges.contains(List.of(from,to));}
        public boolean canInteract(Pos target,double reach){actualChecks++;return actual.contains(target);}
        public boolean canInteractFrom(Pos from,Pos target,double reach){predictedChecks++;return predicted.test(from,target);}
        public List<BlockData> scan(Pos center,int horizontalRadius,int verticalRadius){throw new AssertionError("No scans");}
        public List<ItemSlot> inventory(){return List.of();}public MenuData menu(){return null;}public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
