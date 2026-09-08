package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransitSafetyTest {
    private static final Pos FROM=new Pos(0,70,0),TO=new Pos(1,71,0);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);
    private static final class World implements WorldAccess {
        boolean connected=true,focused=true,nativeProof=true,loaded=true,container;
        float health=20; int food=20; double height=71;
        String blockId="minecraft:barrel";
        ItemData cursor=ItemData.EMPTY;
        public long tick() { return 100; } public long dayTime() { return 1000; }
        public PlayerState player() { return new PlayerState(.5,70,.5,0,0,true,false,health,food,0,connected,focused); }
        public BlockData block(Pos p) { return new BlockData(p,blockId,blockId.equals("minecraft:barrel") ? Map.of("container","true") : Map.of()); }
        public boolean loaded(Pos p) { return loaded; }
        public boolean canStand(Pos p) { return FROM.equals(p)||TO.equals(p); }
        public double standingY(Pos p) { return FROM.equals(p)?70:TO.equals(p)?height:Double.NaN; }
        public boolean canTraverse(Pos a,Pos b) { return false; }
        public boolean canStepUp(LoggingJumpEdge edge,Profile profile) { return nativeProof; }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),cursor,container); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
    }
    private static final class Actions implements ActionPort {
        int stops,submissions; boolean busy; String fence;
        public boolean busy() { return busy; }
        public String pauseReason() { return fence; }
        public long submit(Action a) { submissions++; throw new AssertionError("Coordinate travel never submits work"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public void move(Movement m) { }
        public void stopMovement() { stops++; }
        public void cancel() { stopMovement(); }
    }
    private static final class Nav implements Navigation {
        Result next=Result.MOVING; int positions,observations;
        public Result moveTo(Pos p,double reach,Context c) { throw new AssertionError("A coordinate is not an interaction request"); }
        public Result moveToPosition(Pos p,double reach,Context c) { positions++; return next; }
        public Result moveToObserve(Pos p,double reach,Context c) { observations++; return next; }
        public String diagnosticStatus() { return "SEARCHING"; }
        public String failureReason() { return "NO_PATH"; }
        public void reset() { }
    }
    private static final class Fixture {
        final World world=new World(); final Actions actions=new Actions(); final Nav nav=new Nav();
        final Profile profile=new Profile(); final Context c=new Context(world,actions,nav,profile);
    }

    @Test void generalStepUpNeedsNoWaypointsOrLoggingPermission() {
        Fixture f=new Fixture(); f.profile.enabled.put(Feature.LOGGING,false);
        assertTrue(f.profile.pois.isEmpty()); assertFalse(f.profile.loggingRunActive);
        assertTrue(StepUpRules.permitted(EDGE,f.c));
        assertFalse(LoggingJumpRules.permitted(EDGE,f.c));
        assertNotNull(SafetyPolicy.rejection(new Action.ChopTree(TO),f.c),"General travel never enables tree chopping");
        assertFalse(f.nav.permitsStepUp(EDGE,f.c),"Native execution still requires an active exact edge permit");
        assertFalse(f.actions.moveStepUp(EDGE,true),"Unknown action adapters never gain jump capability");
    }
    @Test void generalAscentRejectsUnknownHeightsGeometryLegacyModeAndProtectedPlanting() {
        Fixture f=new Fixture(); f.world.height=Double.NaN; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.world.height=70.5; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.world.height=71; f.world.nativeProof=false; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.world.nativeProof=true; f.world.loaded=false; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.world.loaded=true; f.profile.navigationMode=NavigationMode.WAYPOINTS; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.profile.navigationMode=NavigationMode.TERRAIN;
        f.profile.farms.add(new Farm("protected",TO,TO)); assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.profile.farms.clear(); f.profile.loggingPlots.add(new LoggingPlot("protected",TO));
        assertFalse(StepUpRules.permitted(EDGE,f.c));
    }
    @Test void backgroundTravelIsAllowedButPendingActionsAndLostConnectionRejectJump() {
        Fixture f=new Fixture(); f.world.focused=false; assertTrue(StepUpRules.permitted(EDGE,f.c));
        f.profile.allowBackground=false; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.profile.allowBackground=true; f.actions.busy=true; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.actions.busy=false; f.world.container=true; assertFalse(StepUpRules.permitted(EDGE,f.c));
        f.world.container=false; f.world.connected=false; assertFalse(StepUpRules.permitted(EDGE,f.c));
    }
    @Test void movementOnlyDoesNotRunWorkAndFinishesOnlyAfterArrival() {
        Fixture f=new Fixture(); CoordinateTravel travel=CoordinateTravel.position(TO);
        assertEquals(CoordinateTravel.Result.MOVING,travel.tick(f.c));
        assertTrue(travel.status().contains("SEARCHING")); assertEquals(1,f.nav.positions);
        f.nav.next=Navigation.Result.ARRIVED; assertEquals(CoordinateTravel.Result.COMPLETE,travel.tick(f.c));
        assertEquals(0,f.actions.submissions); assertTrue(f.profile.pois.isEmpty());
    }
    @Test void observingFacilityNeverRegistersOrOpensItAndChecksActualType() {
        Fixture f=new Fixture(); var draft=new CoordinateDestination("warehouse",TO,PoiKind.TOMATO_CHEST,null,true);
        CoordinateTravel travel=CoordinateTravel.observe(draft); f.nav.next=Navigation.Result.ARRIVED;
        assertEquals(CoordinateTravel.Result.COMPLETE,travel.tick(f.c));
        assertEquals(1,f.nav.observations); assertTrue(f.profile.pois.isEmpty()); assertEquals(0,f.actions.submissions);
        f.world.blockId="minecraft:stone";
        assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
        f.world.loaded=false; assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
    }
    @Test void coordinateTravelCannotBypassLoggingObligationsFencesOrHunger() {
        Fixture f=new Fixture(); CoordinateTravel travel=CoordinateTravel.position(TO);
        f.profile.loggingRunActive=true; assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
        f.profile.loggingRunActive=false; f.actions.fence="uncertain click"; assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
        f.actions.fence=null; f.world.food=4; assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
        f.world.food=20; f.world.connected=false; assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
        assertEquals(0,f.nav.positions);
    }
    @Test void failedCoordinateMoveIsNotClaimedCompleteAndDoesNotSubmitWork() {
        Fixture f=new Fixture(); f.nav.next=Navigation.Result.BLOCKED;
        var travel=CoordinateTravel.position(TO);
        assertEquals(CoordinateTravel.Result.BLOCKED,travel.tick(f.c));
        assertTrue(travel.status().contains("NO_PATH")); assertEquals(0,f.actions.submissions);
    }
}
