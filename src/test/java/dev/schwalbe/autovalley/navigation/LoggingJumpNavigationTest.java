package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingJumpNavigationTest {
    private static final Pos FROM=new Pos(0,0,0),TO=new Pos(1,1,0);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);

    @Test void onlyOneCardinalBlockAndExactlyOneHigherIntegerCellIsAnEdge() {
        assertTrue(LoggingJumpRules.validShape(EDGE));
        assertTrue(LoggingJumpRules.validShape(new LoggingJumpEdge(FROM,new Pos(0,1,-1))));
        for(Pos to:List.of(new Pos(1,0,0),new Pos(1,2,0),new Pos(1,-1,0),new Pos(1,1,1),new Pos(2,1,0),new Pos(0,1,0)))
            assertFalse(LoggingJumpRules.validShape(new LoggingJumpEdge(FROM,to)));
        assertFalse(LoggingJumpRules.validShape(null));
        assertFalse(LoggingJumpRules.validShape(new LoggingJumpEdge(FROM,null)));
    }

    @Test void geometryOnlyPlannerSupportsOfflinePreflightWithoutGrantingMovement() {
        Fixture f=new Fixture(); f.profile.loggingRunActive=false; f.session.oneShotFeature=null;
        f.profile.enabled.put(Feature.LOGGING,false);
        assertEquals(List.of(FROM,TO),new LocalPathfinder().findLogging(FROM,TO,.1,f.world,f.profile));
        assertThrows(UnsupportedOperationException.class,()->new LocalPathfinder().findLogging(FROM,TO,.1,f.world,f.profile).add(FROM));
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(0,f.actions.launches); assertEquals(0,f.actions.moves);
    }

    @Test void ordinaryHarvestPathfinderAndNavigatorNeverUseLoggingJumpEdges() {
        Fixture f=new Fixture(); f.session.oneShotFeature=Feature.HARVEST;
        assertTrue(new LocalPathfinder().find(FROM,TO,.1,f.world,f.profile).isEmpty());
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveTo(TO,.1,f.context));
        assertEquals(0,f.actions.launches); assertEquals(0,f.actions.submissions);
    }

    @Test void nativeFalseOrUnknownSupportHeightNeverLaunches() {
        Fixture f=new Fixture(); f.world.nativeProof=false;
        assertEquals(Navigation.Result.BLOCKED,f.tick()); assertEquals(0,f.actions.launches);
        Fixture unknown=new Fixture(); unknown.world.toHeight=Double.NaN;
        assertEquals(Navigation.Result.BLOCKED,unknown.tick()); assertEquals(0,unknown.actions.launches);
        Fixture halfStep=new Fixture(); halfStep.world.toHeight=.5;
        assertEquals(Navigation.Result.BLOCKED,halfStep.tick()); assertEquals(0,halfStep.actions.launches);
    }

    @Test void aSourceOrLandingOutsideRegisteredBoundsIsNeverAJump() {
        Fixture f=new Fixture(); f.profile.farms.clear();
        assertFalse(LoggingJumpRules.verifiedSupports(EDGE,f.world,f.profile));
        assertEquals(Navigation.Result.BLOCKED,f.tick()); assertEquals(0,f.actions.launches);
    }

    @Test void sourceMustBeQuietGroundedAndCenteredBeforeOneLaunchPulse() {
        Fixture f=new Fixture(); f.pose(.7,0,.5,true);
        assertEquals(Navigation.Result.MOVING,f.tick()); assertEquals(0,f.actions.launches);
        assertFalse(f.actions.lastMove.jump()); assertFalse(f.actions.lastMove.sprint());
        f.pose(.55,0,.5,true); f.tick();
        f.pose(.45,0,.5,true); f.tick();
        assertEquals(0,f.actions.launches,"Crossing the center with inertia is not a quiet launch");
        f.pose(.45,0,.5,true); f.tick(); assertEquals(1,f.actions.launches);
        f.tick(); f.tick();
        assertEquals(1,f.actions.launches); assertEquals(0,f.actions.flightSteers,"Same-tick calls must not clear the launch pulse");
    }

    @Test void airborneInteractionProximityCannotArriveOrSkipTheLandingConfirmation() {
        Fixture f=new Fixture(); f.launch(); f.world.forceInteraction=true;
        f.pose(1.3,1.2,.5,false);
        assertEquals(Navigation.Result.MOVING,f.tick()); assertEquals(1,f.actions.flightSteers);
        f.pose(1.5,1,.5,false);
        assertEquals(Navigation.Result.MOVING,f.tick()); assertEquals(0,f.actions.submissions);
        f.pose(1.5,1,.5,true);
        assertEquals(Navigation.Result.MOVING,f.tick());
        assertEquals(Navigation.Result.MOVING,f.tick(),"A second call in one tick is not a second grounded observation");
        f.pose(1.5,1,.5,true); assertEquals(Navigation.Result.MOVING,f.tick(),"Finishing an edge does not perform an interaction");
        f.pose(1.5,1,.5,true); assertEquals(Navigation.Result.ARRIVED,f.tick());
        assertEquals(1,f.actions.launches); assertEquals(0,f.actions.submissions);
    }

    @Test void smallResidualMotionStillWaitsBeforeLaunchingAtTheNarrowRiser() {
        Fixture f=new Fixture(); f.pose(.5,0,.5,true); f.tick();
        f.pose(.505,0,.5,true); f.tick();
        assertEquals(0,f.actions.launches,"Five millimeters per tick is not a settled launch");
        f.pose(.505,0,.5,true); f.tick();
        assertEquals(1,f.actions.launches);
    }

    @Test void normalFeetNormalizationNeverInterruptsTheVerifiedAirbornePhase() {
        Fixture f=new Fixture(); f.launch();
        f.pose(1.1,.85,.5,false); assertFalse(f.world.canStand(f.world.player().feet()));
        assertEquals(Navigation.Result.MOVING,f.tick()); assertEquals(1,f.actions.flightSteers);
        assertEquals(1,f.actions.launches);
    }

    @Test void timeoutIsLatchedAcrossNavigatorResetWithoutASecondPulse() {
        Fixture f=new Fixture(); f.launch(); f.pose(.7,.4,.5,false); f.tick();
        f.world.now+=41; assertEquals(Navigation.Result.BLOCKED,f.tick());
        f.navigation.reset(); f.pose(.5,0,.5,true);
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(1,f.actions.launches); assertNull(f.actions.lastMove);
    }

    @Test void cancellingAStartedJumpStopsAndNeverAutomaticallyRelaunchesThatEdge() {
        Fixture f=new Fixture(); f.launch(); int stops=f.actions.stops;
        f.navigation.reset(); assertTrue(f.actions.stops>stops);
        f.pose(.5,0,.5,true); assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(1,f.actions.launches);
    }

    @Test void landingMayBeginAtTheNearEdgeAndWalkToTheCenterWithoutAnotherJump() {
        Fixture f=new Fixture(); f.launch(); f.pose(.9,1.2,.5,false); f.tick();
        f.pose(1.05,1,.5,true); assertEquals(Navigation.Result.MOVING,f.tick());
        assertNotNull(f.actions.lastMove); assertFalse(f.actions.lastMove.jump());
        f.pose(1.5,1,.5,true); assertEquals(Navigation.Result.MOVING,f.tick());
        f.pose(1.5,1,.5,true); assertEquals(Navigation.Result.MOVING,f.tick());
        assertEquals(1,f.actions.launches);
    }

    @Test void resetDuringFlightDoesNotTurnInteractionProximityIntoArrival() {
        Fixture f=new Fixture(); f.launch(); f.pose(1.5,1.1,.5,false);
        f.navigation.reset(); f.world.forceInteraction=true;
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(1,f.actions.launches); assertEquals(0,f.actions.submissions);
    }

    @Test void completedFinalJumpReplansAtLandingInsteadOfPreparingTheSameEdgeAgain() {
        Fixture f=new Fixture(); f.launch();
        f.pose(1.39,1,.5,true); assertEquals(Navigation.Result.MOVING,f.tick());
        f.pose(1.39,1,.5,true); assertEquals(Navigation.Result.MOVING,f.tick());
        assertFalse(f.world.canInteract(TO,.1),"Valid landing margin is wider than this interaction's reach");
        f.world.now++; assertEquals(Navigation.Result.MOVING,f.tick());
        f.world.now++; assertEquals(Navigation.Result.MOVING,f.tick());
        assertNotNull(f.actions.lastMove,"Finish the small grounded centering movement from the landing");
        assertEquals(1,f.actions.launches);
        f.pose(1.5,1,.5,true); assertEquals(Navigation.Result.ARRIVED,f.tick());
    }

    @Test void cancelledJumpDoesNotGrantAnOrdinaryModuleAnAirborneArrival() {
        Fixture f=new Fixture(); f.launch(); f.pose(1.3,1.2,.5,false);
        f.session.oneShotFeature=Feature.HARVEST;
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(1,f.actions.launches); assertNull(f.actions.lastMove);
    }

    @Test void failedLiftDoesNotRetryEvenWhenTheGroundNeverReleased() {
        Fixture f=new Fixture(); f.launch(); f.world.now+=5;
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        f.navigation.reset(); assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(1,f.actions.launches);
    }

    @Test void bodyLeavingTheVerifiedCorridorStopsBeforeAnotherFlightInput() {
        Fixture f=new Fixture(); f.launch(); f.pose(.8,.6,.7,false);
        assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(0,f.actions.flightSteers); assertNull(f.actions.lastMove);
    }

    @Test void geometryOrCursorOrConnectionChangeStopsAnActiveJump() {
        Fixture geometry=new Fixture(); geometry.launch(); geometry.world.nativeProof=false;
        geometry.pose(.7,.4,.5,false); assertEquals(Navigation.Result.BLOCKED,geometry.tick());
        Fixture cursor=new Fixture(); cursor.launch(); cursor.world.cursor=new ItemData("minecraft:stone",1,0,null,false,999);
        cursor.pose(.7,.4,.5,false); assertEquals(Navigation.Result.BLOCKED,cursor.tick());
        Fixture disconnected=new Fixture(); disconnected.launch(); disconnected.world.connected=false;
        disconnected.pose(.7,.4,.5,false); assertEquals(Navigation.Result.BLOCKED,disconnected.tick());
        for(Fixture f:List.of(geometry,cursor,disconnected)) { assertEquals(1,f.actions.launches); assertEquals(0,f.actions.flightSteers); }
    }

    @Test void tickRollbackAndLandingThatLosesGroundAreNotSuccessfulJumps() {
        Fixture rollback=new Fixture(); rollback.launch(); rollback.world.now--;
        assertEquals(Navigation.Result.BLOCKED,rollback.tick()); assertEquals(1,rollback.actions.launches);
        Fixture landing=new Fixture(); landing.launch(); landing.pose(1.5,1,.5,true); landing.tick();
        landing.pose(1.5,1.05,.5,false); assertEquals(Navigation.Result.BLOCKED,landing.tick());
        assertEquals(1,landing.actions.launches);
    }

    @Test void nativeLaunchRejectionIsStillAnAttemptAndIsNotResent() {
        Fixture f=new Fixture(); f.actions.acceptLaunch=false;
        f.tick(); f.world.now++; assertEquals(Navigation.Result.BLOCKED,f.tick());
        f.navigation.reset(); f.world.now++; assertEquals(Navigation.Result.BLOCKED,f.tick());
        assertEquals(1,f.actions.launches);
    }

    private static final class Fixture {
        final TestWorld world=new TestWorld(); final TestActions actions=new TestActions();
        final LocalNavigator navigation=new LocalNavigator(); final Profile profile=new Profile();
        final SessionState session=new SessionState(); final Context context;
        Fixture() {
            profile.farms.add(new Farm("verified corridor",new Pos(-1,-1,-1),new Pos(3,3,1)));
            profile.loggingRunActive=true; session.oneShotFeature=Feature.LOGGING;
            context=new Context(world,actions,navigation,profile,session);
        }
        Navigation.Result tick() { return navigation.moveToLogging(TO,.1,context); }
        void pose(double x,double y,double z,boolean grounded) { world.x=x; world.y=y; world.z=z; world.grounded=grounded; world.now++; }
        void launch() { assertEquals(Navigation.Result.MOVING,tick()); world.now++; assertEquals(Navigation.Result.MOVING,tick()); assertEquals(1,actions.launches); }
    }
    private static final class TestWorld implements WorldAccess {
        long now; double x=.5,y=0,z=.5,toHeight=1;
        boolean grounded=true,nativeProof=true,forceInteraction,connected=true;
        ItemData cursor=ItemData.EMPTY;
        public long tick() { return now; } public long dayTime() { return 1000; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,connected,true); }
        public BlockData block(Pos p) { return new BlockData(p,"minecraft:air",Map.of()); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return p.equals(FROM)||p.equals(TO); }
        public double standingY(Pos p) { return p.equals(FROM)?0:p.equals(TO)?toHeight:Double.NaN; }
        public boolean canTraverse(Pos a,Pos b) { return a.equals(TO)&&b.equals(FROM); }
        public boolean canLoggingJump(LoggingJumpEdge edge,Profile profile) { return nativeProof&&edge.equals(EDGE); }
        public boolean canInteract(Pos p,double reach) { return forceInteraction||player().distance(p)<=reach; }
        public boolean canInteractFrom(Pos feet,Pos p,double reach) { return feet.equals(p); }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),cursor,false); }
        public boolean mayPlace(int index,ItemData item) { return false; }
    }
    private static final class TestActions implements ActionPort {
        int launches,flightSteers,moves,stops,submissions; boolean acceptLaunch=true; Movement lastMove;
        public boolean busy() { return false; }
        public long submit(Action action) { submissions++; throw new AssertionError("Navigation must not submit gameplay interactions here"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public void move(Movement movement) { assertFalse(movement.jump()); assertFalse(movement.sprint()); moves++; lastMove=movement; }
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch) { assertEquals(EDGE,edge); if(launch) { launches++; return acceptLaunch; } flightSteers++; return true; }
        public void stopMovement() { stops++; lastMove=null; }
        public void cancel() { stopMovement(); }
    }
}
