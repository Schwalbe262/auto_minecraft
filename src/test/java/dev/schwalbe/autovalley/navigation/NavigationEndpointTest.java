package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationEndpointTest {
    private static final Pos TARGET=new Pos(3,0,1), FIRST=new Pos(0,0,0), SECOND=new Pos(1,0,0);

    @Test void nearCenterRayFailureStopsThenWaitsForResidualMotionBeforeArrival() {
        Fixture f=new Fixture();
        assertEquals(Navigation.Result.MOVING,f.step());
        assertNull(f.movement,"The failed predicted center must first stop, not fail or keep pressing forward");
        for(int n=0;n<10;n++) assertEquals(Navigation.Result.MOVING,f.step(),"Repeated polls are not elapsed game ticks");
        f.x=.82; f.actualReach=true; f.now++;
        assertEquals(Navigation.Result.MOVING,f.step(),"Do not hand off an unstable ray during residual drift");
        f.now++; assertEquals(Navigation.Result.MOVING,f.step());
        f.now++; assertEquals(Navigation.Result.ARRIVED,f.step());
        assertNull(f.movement); assertEquals(0,f.submissions);
    }

    @Test void failedCenterIsExcludedOnlyAsGoalAndAlternativeUsesNormalApprovedEdges() {
        Fixture f=new Fixture(); f.goals.add(SECOND);
        f.step(); f.now++; f.step(); f.now++;
        assertEquals(Navigation.Result.MOVING,f.step());
        assertNull(f.movement,"The rejection tick remains stopped");
        f.now++; assertEquals(Navigation.Result.MOVING,f.step());
        assertNotNull(f.movement); assertEquals(-90,f.movement.yaw(),.01);
        assertFalse(f.movement.jump()); assertFalse(f.movement.sprint());
        f.x=1.5; f.actualReach=true; f.now++;
        assertEquals(Navigation.Result.ARRIVED,f.step()); assertEquals(0,f.submissions);
        List<Pos> path=new LocalPathfinder().find(FIRST,TARGET,2.5,f,f.profile,Set.of(FIRST));
        assertEquals(List.of(FIRST,SECOND),path,"The rejected goal remains a usable start/traversal cell");
    }

    @Test void fourFalsePredictedCentersExhaustTheBoundAndNeverDispatchAnInteraction() {
        Fixture f=new Fixture(); for(int n=1;n<4;n++) f.goals.add(new Pos(n,0,0));
        for(int n=0;n<4;n++) {
            f.x=n+.5;
            assertEquals(Navigation.Result.MOVING,f.step());
            f.now++; assertEquals(Navigation.Result.MOVING,f.step());
            f.now++;
            assertEquals(n==3 ? Navigation.Result.BLOCKED : Navigation.Result.MOVING,f.step());
            f.now++;
            if(n<3) assertEquals(Navigation.Result.MOVING,f.step());
        }
        assertTrue(f.navigation.failureReason().contains("4곳"));
        assertNull(f.movement); assertEquals(0,f.submissions);
        f.now++; assertEquals(Navigation.Result.BLOCKED,f.step(),"Exhaustion is not an automatic new four-attempt cycle");
    }

    @Test void continuedResidualMotionHasATenTickStopDeadlineEvenOutsideCenterTolerance() {
        Fixture f=new Fixture(); f.step();
        for(int n=1;n<=10;n++) {
            f.now++; f.x=n%2==0 ? .5 : .55;
            assertEquals(n==10 ? Navigation.Result.BLOCKED : Navigation.Result.MOVING,f.step());
            assertNull(f.movement);
        }
        assertEquals(0,f.submissions);
    }

    @Test void alternativeCannotCrossChangedOrUnloadedTerrainAndResetClearsRejectedGoals() {
        for(boolean unloaded:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.goals.add(SECOND);
            f.step(); f.now++; f.step(); f.now++; f.step();
            if(unloaded) f.unloaded.add(SECOND); else f.blocked.add(SECOND);
            f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
            assertNull(f.movement); assertEquals(0,f.submissions);
            f.navigation.reset(); f.now++;
            assertEquals(Navigation.Result.MOVING,f.step(),"An explicit reset may evaluate the original center afresh");
            assertNull(f.movement);
        }
    }

    @Test void goalExclusionDoesNotWidenReachOrApprovedBounds() {
        Fixture f=new Fixture(); Pos outside=new Pos(20,0,0);
        f.goals.add(outside);
        assertTrue(new LocalPathfinder().find(FIRST,TARGET,2.5,f,f.profile,Set.of(FIRST)).isEmpty());
        assertTrue(f.requestedReaches.stream().allMatch(r -> r==2.5));
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final LocalNavigator navigation=new LocalNavigator();
        final Profile profile=new Profile(); final Context context;
        final Set<Pos> goals=new HashSet<>(Set.of(FIRST)),blocked=new HashSet<>(),unloaded=new HashSet<>();
        final List<Double> requestedReaches=new ArrayList<>();
        long now; double x=.5; boolean actualReach; Movement movement; int submissions;
        Fixture() {
            profile.farms.add(new Farm("bounded",new Pos(0,0,0),new Pos(3,0,0)));
            context=new Context(this,this,navigation,profile);
        }
        Navigation.Result step() { return navigation.moveTo(TARGET,2.5,context); }
        public long tick() { return now; }
        public long dayTime() { return 5000; }
        public PlayerState player() { return new PlayerState(x,0,.5,0,0,true,false,20,20,0,true,true); }
        public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        public boolean canStand(Pos pos) { return pos.y()==0 && pos.z()==0 && !blocked.contains(pos); }
        public boolean canTraverse(Pos from,Pos to) { return canStand(from) && canStand(to) && Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1; }
        public double standingY(Pos pos) { return canStand(pos) ? 0 : Double.NaN; }
        public boolean canInteract(Pos pos,double reach) { requestedReaches.add(reach); return actualReach; }
        public boolean canInteractFrom(Pos pos,Pos target,double reach) { requestedReaches.add(reach); return goals.contains(pos); }
        public BlockData block(Pos pos) { return new BlockData(pos,"minecraft:air",Map.of()); }
        public List<BlockData> scan(Pos center,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
        public long submit(Action action) { submissions++; throw new AssertionError("Endpoint repair must not interact"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public boolean busy() { return false; }
        public void move(Movement movement) { this.movement=movement; }
        public void stopMovement() { movement=null; }
        public void cancel() { stopMovement(); }
    }
}
