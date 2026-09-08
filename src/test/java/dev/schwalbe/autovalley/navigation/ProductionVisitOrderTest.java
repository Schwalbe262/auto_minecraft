package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import java.util.*;
import java.util.function.BiPredicate;
import static org.junit.jupiter.api.Assertions.*;

class ProductionVisitOrderTest {
    private static final Pos START=new Pos(0,64,0);
    private static Poi keg(int x,int y,int z) { return new Poi(new Pos(x,y,z),PoiKind.WINE_KEG,"Machine",null); }
    private static Profile bounded(int x1,int x2,int z1,int z2) {
        Profile p=new Profile();p.navigationMode=NavigationMode.WAYPOINTS;p.farms.add(new Farm("Registered work area",new Pos(x1,63,z1),new Pos(x2,66,z2)));return p;
    }
    @Test void currentlyNativeInteractableMachineWinsWithoutAnyPathSearch(TestReporter reporter) {
        World world=new World();List<Poi> machines=List.of(keg(1,64,1),keg(4,64,0));
        world.actual.add(machines.get(1).pos());
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-2,10,-2,3),machines,0,4));
        assertEquals(0,world.predictedChecks);assertEquals(0,world.traverseChecks);
        reporter.publishEntry("currently reachable",metrics(world));
    }
    @Test void nearestCurrentInteractableIsRecomputedAfterEarlierTargets() {
        World world=new World();List<Poi> machines=List.of(keg(0,64,0),keg(4,64,0),keg(1,64,0));
        machines.forEach(p -> world.actual.add(p.pos()));
        assertEquals(2,ProductionVisitOrder.nextIndex(world,bounded(-2,10,-2,3),machines,1,4));
        assertEquals(1,world.actualChecks);assertEquals(-1,ProductionVisitOrder.nextIndex(world,new Profile(),machines,3,4));
    }
    @Test void slightlyFartherSameAisleBeatsVisuallyNearMachineBehindWall(TestReporter reporter) {
        World world=new World();List<Poi> machines=List.of(keg(1,64,2),keg(6,64,0));
        for (int x=0;x<=20;x++) { world.standing.add(new Pos(x,64,0));world.standing.add(new Pos(x,64,2)); }
        world.standing.add(new Pos(20,64,1));
        world.predicted=(feet,target) -> feet.equals(target);
        assertEquals(1,ProductionVisitOrder.nextIndex(world,bounded(-1,21,-1,3),machines,0,1));
        assertTrue(world.traverseChecks>0);assertTrue(world.traverseChecks<400);
        reporter.publishEntry("two-target wall",metrics(world));
    }
    @Test void sixtyFourOccludedRackMachinesCannotHideTheNextSameAisleMachine(TestReporter reporter) {
        World world=denseRackWorld();List<Poi> machines=denseRackMachines();
        Poi sameAisle=machines.get(64);List<Poi> original=List.copyOf(machines);
        assertTrue(machines.subList(0,64).stream().allMatch(p -> world.player.distance(p.pos())<world.player.distance(sameAisle.pos())));
        assertTrue(machines.stream().noneMatch(p -> denseRackCanInteract(START,p.pos())));
        assertTrue(denseRackCanInteract(new Pos(6,64,0),sameAisle.pos()));
        assertFalse(denseRackCanInteract(new Pos(0,64,0),machines.get(8).pos()),"The nearer rack is behind the wall, not merely farther from the eye");
        int choice=ProductionVisitOrder.nextIndex(world,bounded(-9,21,-2,4),machines,0,4);
        assertEquals(64,choice,"A 16-wide, four-high occluded rack must not crowd out the same aisle's next machine; actual="
            +world.actualChecks+", predicted="+world.predictedChecks+", traversals="+world.traverseChecks);
        assertEquals(original,machines,"Preference must preserve every pending machine");
        assertTrue(world.actualChecks+world.predictedChecks<=ProductionVisitOrder.MAX_INTERACTION_CHECKS);
        assertTrue(world.edges.stream().allMatch(edge -> edge.get(0).y()==edge.get(1).y()
            && Math.abs(edge.get(0).x()-edge.get(1).x())+Math.abs(edge.get(0).z()-edge.get(1).z())==1));
        assertTrue(world.checksPerFeet.values().stream().allMatch(count -> count<=8));
        assertEquals(world.predictedChecks,world.checkedPairs.size(),"No repeated target/standing-cell ray checks");
        reporter.publishEntry("64 occluded + same aisle",metrics(world));
    }
    @Test void denseRackSelectionSurvivesReflectionAndRegistrationPermutation(TestReporter reporter) {
        for (int mirror:new int[]{1,-1}) for (boolean shuffled:new boolean[]{false,true}) {
            World world=denseRackWorld();
            Set<Pos> standing=new HashSet<>();
            for (Pos cell:world.standing) standing.add(new Pos(mirror*cell.x(),cell.y(),cell.z()));
            world.standing.clear();world.standing.addAll(standing);
            world.predicted=(feet,target) -> denseRackCanInteract(new Pos(mirror*feet.x(),feet.y(),feet.z()),
                new Pos(mirror*target.x(),target.y(),target.z()));
            List<Poi> machines=new ArrayList<>();
            for (Poi machine:denseRackMachines()) machines.add(keg(mirror*machine.pos().x(),machine.pos().y(),machine.pos().z()));
            if (shuffled) Collections.shuffle(machines,new Random(284));
            List<Poi> original=List.copyOf(machines);
            int choice=ProductionVisitOrder.nextIndex(world,bounded(-21,21,-2,4),machines,0,4);
            assertEquals(new Pos(mirror*10,64,-1),machines.get(choice).pos());
            assertEquals(original,machines);
            assertTrue(world.actualChecks+world.predictedChecks<=ProductionVisitOrder.MAX_INTERACTION_CHECKS);
            reporter.publishEntry("mirror="+mirror+", shuffled="+shuffled,metrics(world));
        }
    }
    @Test void denseRackUpperLevelUsesModeledFourBlockEyeReach(TestReporter reporter) {
        World world=denseRackWorld();List<Poi> machines=denseRackMachines();machines.set(64,keg(10,67,-1));
        assertFalse(denseRackCanInteract(new Pos(5,64,0),machines.get(64).pos()));
        assertTrue(denseRackCanInteract(new Pos(7,64,0),machines.get(64).pos()));
        assertEquals(64,ProductionVisitOrder.nextIndex(world,bounded(-9,21,-2,4),machines,0,4));
        assertNotNull(world.successfulFeet);
        assertTrue(denseRackCanInteract(world.successfulFeet,machines.get(64).pos()));
        assertTrue(world.actualChecks+world.predictedChecks<=ProductionVisitOrder.MAX_INTERACTION_CHECKS);
        reporter.publishEntry("upper level",metrics(world));
    }
    @Test void currentlyReachableDenseRackTargetStillAvoidsAllSearch() {
        World world=denseRackWorld();List<Poi> machines=denseRackMachines();
        world.player=new PlayerState(6.5,64,.5,0,0,true,false,20,20,0,true,true);
        assertTrue(denseRackCanInteract(world.player.feet(),machines.get(64).pos()));
        world.actual.add(machines.get(64).pos());
        assertEquals(64,ProductionVisitOrder.nextIndex(world,bounded(-9,21,-2,4),machines,0,4));
        assertEquals(0,world.predictedChecks);assertEquals(0,world.traverseChecks);
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

    private static List<Poi> denseRackMachines() {
        List<Poi> machines=new ArrayList<>();
        for (int y=64;y<68;y++) for (int x=-8;x<8;x++) machines.add(keg(x,y,2));
        machines.add(keg(10,64,-1));return machines;
    }
    private static World denseRackWorld() {
        World world=new World();
        // Two cardinal aisles, joined only beyond the end of the solid wall.
        // Neither a rack block nor the wall itself is a standing cell.
        for (int x=-8;x<=20;x++) { world.standing.add(new Pos(x,64,0));world.standing.add(new Pos(x,64,3)); }
        world.standing.add(new Pos(20,64,1));world.standing.add(new Pos(20,64,2));
        world.predicted=ProductionVisitOrderTest::denseRackCanInteract;return world;
    }
    private static boolean denseRackCanInteract(Pos feet,Pos target) {
        double ex=feet.x()+.5,ey=feet.y()+1.62,ez=feet.z()+.5;
        // Synthetic visibility model: 27 interior points, endpoint reach and a
        // solid wall plane spanning x[-8,20), y[64,70], z=1.5. Native targeting
        // has different sample heights and measures the first outline hit;
        // this tests visit preference, not native clipping equivalence.
        for (double dx:new double[]{.25,.5,.75}) for (double dy:new double[]{.25,.5,.75}) for (double dz:new double[]{.25,.5,.75}) {
            double tx=target.x()+dx,ty=target.y()+dy,tz=target.z()+dz;
            if ((tx-ex)*(tx-ex)+(ty-ey)*(ty-ey)+(tz-ez)*(tz-ez)>16) continue;
            double crossing=(1.5-ez)/(tz-ez);
            if (crossing>0 && crossing<1) {
                double wx=ex+(tx-ex)*crossing,wy=ey+(ty-ey)*crossing;
                if (wx>=-8 && wx<20 && wy>=64 && wy<=70) continue;
            }
            return true;
        }
        return false;
    }
    private static String metrics(World world) {
        return "actual="+world.actualChecks+", predicted="+world.predictedChecks+", traversals="+world.traverseChecks
            +", checked standing cells="+world.checksPerFeet.size()+", chosen standing distance="
            +(world.successfulFeet==null ? "none" : Math.sqrt(START.distanceSquared(world.successfulFeet)));
    }
    private static final class World implements WorldAccess {
        PlayerState player=new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true);
        final Set<Pos> standing=new HashSet<>(),unloaded=new HashSet<>(),actual=new HashSet<>();
        final Set<List<Pos>> blockedEdges=new HashSet<>();final List<List<Pos>> edges=new ArrayList<>();
        final Map<Pos,Integer> checksPerFeet=new HashMap<>();final Set<List<Pos>> checkedPairs=new HashSet<>();Pos successfulFeet;
        BiPredicate<Pos,Pos> predicted=(feet,target) -> false;
        int actualChecks,predictedChecks,traverseChecks;boolean infiniteFloor;
        public long tick(){return 0;}public long dayTime(){return 0;}public PlayerState player(){return player;}
        public BlockData block(Pos pos){throw new AssertionError("Visit selection must not inspect or use machine state");}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}
        public boolean canStand(Pos pos){return standing.contains(pos) || infiniteFloor && pos.y()==64;}
        public boolean canTraverse(Pos from,Pos to){traverseChecks++;edges.add(List.of(from,to));return !blockedEdges.contains(List.of(from,to));}
        public boolean canInteract(Pos target,double reach){actualChecks++;return actual.contains(target);}
        public boolean canInteractFrom(Pos from,Pos target,double reach){
            predictedChecks++;checksPerFeet.merge(from,1,Integer::sum);
            checkedPairs.add(List.of(from,target));
            boolean success=predicted.test(from,target);if (success) successfulFeet=from;return success;
        }
        public List<BlockData> scan(Pos center,int horizontalRadius,int verticalRadius){throw new AssertionError("No scans");}
        public List<ItemSlot> inventory(){return List.of();}public MenuData menu(){return null;}public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
