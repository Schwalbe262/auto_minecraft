package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationDescentTest {
    private static final Pos TOP=new Pos(0,2,0), STEP=new Pos(1,1,0), LOWER=new Pos(1,0,-1), END=new Pos(1,0,-2);

    @Test void bodyStillSupportedByPreviousStepMayCrossTheVerifiedDescendingEdge() {
        Fixture f=new Fixture();
        assertEquals(Navigation.Result.MOVING,f.tick());
        // The center has crossed the block boundary, but the 0.6-wide body
        // still overlaps the higher floor. This is a normal grounded descent.
        f.pose(1.05,2,.5,true);
        assertFalse(f.world.canStand(f.world.player().feet()));
        assertEquals(Navigation.Result.MOVING,f.tick());
        assertNotNull(f.actions.movement);
        assertEquals(-90,f.actions.movement.yaw(),.01);
        assertFalse(f.actions.movement.jump());
    }

    @Test void fallingAtStepCenterWaitsForLandingBeforeTurningToTheNextStep() {
        Fixture f=new Fixture();
        f.tick();
        f.pose(1.35,1.9,.5,false);
        assertEquals(Navigation.Result.MOVING,f.tick());
        assertNull(f.actions.movement,"Do not turn north while still falling onto the eastward step");
        f.pose(1.35,1,.5,true);
        assertEquals(Navigation.Result.MOVING,f.tick());
        assertNotNull(f.actions.movement);
        assertTrue(Math.abs(f.actions.movement.yaw())>150,"The next, northward step is selected only after landing");
        assertFalse(f.actions.movement.jump());
    }

    @Test void aLandingThatNeverArrivesTimesOutWithoutAJumpOrInteraction() {
        Fixture f=new Fixture();
        f.tick();
        f.pose(1.35,1.9,.5,false);
        assertEquals(Navigation.Result.MOVING,f.tick());
        f.world.now+=61;
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertNull(f.actions.movement);
        assertTrue(f.navigation.failureReason().contains("3초"));
        assertEquals(0,f.actions.submissions);
    }

    @Test void unplannedFallAndChangedDescendingEdgeRemainBlocked() {
        Fixture f=new Fixture();
        f.tick();
        f.pose(1.05,2,1.15,false);
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertNull(f.actions.movement);
        Fixture changed=new Fixture();
        changed.tick();
        changed.pose(1.05,2,.5,true);
        changed.world.blocked=true;
        assertEquals(Navigation.Result.BLOCKED,changed.tick());
        assertNull(changed.actions.movement);
    }

    @Test void unknownSupportHeightCannotAuthorizeDescendingBoundaryMovement() {
        Fixture f=new Fixture();
        f.tick();
        f.pose(1.05,2,.5,true);
        f.world.knownHeight=false;
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertNull(f.actions.movement);
    }

    @Test void partialHeightLandingUsesTheObservedSupportNotTheIntegerNodeY() {
        Fixture f=new Fixture();
        f.world.heights.put(TOP,1.5);
        f.world.heights.put(STEP,.5);
        f.pose(.5,1.5,.5,true);
        assertEquals(Navigation.Result.MOVING,f.tick());
        f.pose(1.35,.9,.5,false);
        assertEquals(Navigation.Result.MOVING,f.tick());
        assertNull(f.actions.movement);
        f.pose(1.35,.5,.5,true);
        assertEquals(Navigation.Result.MOVING,f.tick());
        assertTrue(Math.abs(f.actions.movement.yaw())>150);
        assertFalse(f.actions.movement.jump());
    }

    private static final class Fixture {
        final TestWorld world=new TestWorld();
        final TestActions actions=new TestActions();
        final LocalNavigator navigation=new LocalNavigator();
        final Context context;
        Fixture() {
            Profile profile=new Profile();
            profile.farms.add(new Farm("stairs",new Pos(-1,-1,-3),new Pos(2,3,1)));
            context=new Context(world,actions,navigation,profile);
        }
        void pose(double x,double y,double z,boolean onGround) { world.x=x; world.y=y; world.z=z; world.onGround=onGround; world.now++; }
        Navigation.Result tick() { return navigation.moveTo(END,.15,context); }
    }

    private static final class TestWorld implements WorldAccess {
        long now; double x=.5,y=2,z=.5;
        boolean onGround=true,blocked,knownHeight=true;
        final List<Pos> cells=List.of(TOP,STEP,LOWER,END);
        final Map<Pos,Double> heights=new HashMap<>();
        public long tick() { return now; }
        public long dayTime() { return 5000; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,onGround,false,20,20,0,true,true); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return cells.contains(p); }
        public double standingY(Pos p) { return knownHeight && canStand(p)?heights.getOrDefault(p,(double)p.y()):Double.NaN; }
        public boolean canTraverse(Pos from,Pos to) {
            return !blocked && canStand(from) && canStand(to)
                && Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1 && Math.abs(from.y()-to.y())<=1;
        }
        public BlockData block(Pos p) { return new BlockData(p,"minecraft:air",Map.of()); }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int index,ItemData item) { return false; }
    }

    private static final class TestActions implements ActionPort {
        Movement movement; int submissions;
        public long submit(Action action) { submissions++; throw new AssertionError("Descent must not interact"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public void move(Movement movement) { this.movement=movement; }
        public void stopMovement() { movement=null; }
        public boolean busy() { return false; }
        public void cancel() { stopMovement(); }
    }
}
