package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingJumpLandingTest {
    private static final Pos FROM=new Pos(0,0,0),TO=new Pos(1,1,0);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);

    @Test void observedEarlyLandingAndGroundedOvershootRemainOnTheSameVerifiedHighFloor() {
        Fixture f=new Fixture(); f.x=.478221848706; f.z=.486505868084;
        f.launch();
        // Translated/reflected relative trajectory: no private world coordinates.
        double[][] observed={
            {.478221848706,.419999986887,.486505868084,0},
            {.478221848706,.753199980521,.486505868084,0},
            {.478221848706,1.001335979112,.486505868084,0},
            {.497820135971,1.166109260938,.486764926564,0},
            {.535252846121,1.249187078745,.487025480575,0},
            {.588914824540,1.252203340254,.487290283079,0},
            {.657345305215,1.176759275064,.487564717973,0},
            {.739214907813,1.024424088214,.487854098267,0},
            {.833313729291,1,.488168345263,1},
            {1.048577190693,1,.490472599838,1},
            {1.295735956929,1,.493219621215,1},
            {1.560267113398,1,.497526643312,1},
            {1.699999988079,1,.497526643312,1}
        };
        for(double[] sample:observed) assertEquals(Navigation.Result.MOVING,f.pose(sample[0],sample[1],sample[2],sample[3]==1));
        assertEquals(LoggingJumpController.Phase.LAND,f.controller.phase());
        assertFalse(LoggingJumpRules.insideFlight(EDGE,f.player(),0),"The old air-only guard rejected this grounded pose");
        assertTrue(LoggingJumpRules.centered(f.player(),TO,.45));
        assertNotNull(f.movement); assertEquals(90,f.movement.yaw(),.1,"Brake the observed eastward overshoot using ordinary westward input");
        assertEquals(1,f.launches); assertEquals(0,f.submissions);
    }

    @Test void twoCenteredGroundedSamplesWithVisibleResidualMotionDoNotFinish() {
        Fixture f=new Fixture(); f.launch();
        assertEquals(Navigation.Result.MOVING,f.pose(1.5,1,.5,true));
        assertEquals(Navigation.Result.MOVING,f.pose(1.505,1,.5,true));
        assertEquals(Navigation.Result.MOVING,f.pose(1.51,1,.5,true));
        assertEquals(LoggingJumpController.Phase.LAND,f.controller.phase());
        assertEquals(Navigation.Result.MOVING,f.pose(1.51,1,.5,true));
        assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context),"Same-tick duplicate cannot supply another quiet sample");
        assertEquals(Navigation.Result.ARRIVED,f.pose(1.51,1,.5,true));
        assertEquals(1,f.launches);
    }

    @Test void ordinaryBrakeInputSettlesAnObservedHighSpeedCenterOvershootInASmallForwardModel() {
        Fixture f=new Fixture(); f.launch(); f.pose(1.3,1.2,.5,false);
        assertEquals(Navigation.Result.MOVING,f.pose(1.56,1,.5,true));
        assertNotNull(f.movement); assertEquals(90,f.movement.yaw(),.001);
        Navigation.Result result=runGroundModel(f,.26,0,.13,30);
        assertEquals(Navigation.Result.ARRIVED,result);
        assertTrue(LoggingJumpRules.centered(f.player(),TO,.12));
        assertEquals(1,f.launches); assertEquals(0,f.submissions);
    }

    @Test void nearEdgeLandingUsesPulseAndCoastInsteadOfRepeatedFullSpeedCenterChasing() {
        for(double acceleration:new double[]{.10,.13}) {
            Fixture f=new Fixture(); f.launch(); f.pose(.76,1.02,.5,false);
            assertEquals(Navigation.Result.MOVING,f.pose(.84,1,.5,true));
            Navigation.Result result=runGroundModel(f,.08,0,acceleration,30);
            assertEquals(Navigation.Result.ARRIVED,result,"Bounded normal-surface model acceleration="+acceleration);
            assertTrue(f.moves<10,"Use a few measured ground pulses, not a full-speed command each tick");
            assertEquals(1,f.launches);
        }
    }

    @Test void theGroundedExceptionDoesNotWidenFlightOrSurviveLossOfGround() {
        Fixture air=new Fixture(); air.launch();
        assertEquals(Navigation.Result.BLOCKED,air.pose(1.7,1.1,.5,false));
        Fixture lost=new Fixture(); lost.launch(); lost.pose(1.5,1,.5,true);
        assertEquals(Navigation.Result.BLOCKED,lost.pose(1.7,1.01,.5,false));
        Fixture far=new Fixture(); far.launch(); far.pose(1.5,1,.5,true);
        assertEquals(Navigation.Result.BLOCKED,far.pose(1.96,1,.5,true));
        Fixture low=new Fixture(); low.launch(); low.pose(1.5,1,.5,true);
        assertEquals(Navigation.Result.BLOCKED,low.pose(1.7,.8,.5,true));
        for(Fixture f:List.of(air,lost,far,low)) { assertEquals(1,f.launches); assertNull(f.movement); }
    }

    @Test void changedNativeProofOrAuthorityStillRejectsGroundedCentering() {
        Fixture proof=new Fixture(); proof.launch(); proof.pose(1.5,1,.5,true); proof.nativeProof=false;
        assertEquals(Navigation.Result.BLOCKED,proof.pose(1.7,1,.5,true));
        Fixture permission=new Fixture(); permission.launch(); permission.pose(1.5,1,.5,true); permission.profile.loggingRunActive=false;
        assertEquals(Navigation.Result.BLOCKED,permission.pose(1.7,1,.5,true));
    }

    @Test void unobservedTickGapDoesNotCountAsAQuietLandingOrTriggerBlindAcceleration() {
        Fixture f=new Fixture(); f.launch(); f.pose(1.5,1,.5,true); f.now+=2;
        assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context)); assertNull(f.movement);
        assertEquals(Navigation.Result.MOVING,f.pose(1.5,1,.5,true));
        assertEquals(Navigation.Result.ARRIVED,f.pose(1.5,1,.5,true));
    }

    private static Navigation.Result runGroundModel(Fixture f,double vx,double vz,double acceleration,int maxTicks) {
        Navigation.Result result=Navigation.Result.MOVING;
        // This small observation-driven regression is not a native-physics claim.
        // Ground drag and binary walking acceleration expose the prior overshoot.
        for(int i=0;i<maxTicks && result==Navigation.Result.MOVING;i++) {
            Movement input=f.movement; double ax=0,az=0;
            if(input!=null && input.forward()) {
                assertFalse(input.jump()); assertFalse(input.sprint()); assertFalse(input.sneak());
                ax=-Math.sin(Math.toRadians(input.yaw()))*acceleration;
                az=Math.cos(Math.toRadians(input.yaw()))*acceleration;
            }
            vx=vx*.546+ax; vz=vz*.546+az;
            result=f.pose(f.x+vx,1,f.z+vz,true);
            assertNotEquals(Navigation.Result.BLOCKED,result,f.controller.failureReason());
        }
        return result;
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final LoggingJumpController controller=new LoggingJumpController(EDGE);
        final Context context; long now; double x=.5,y=0,z=.5; boolean grounded=true,nativeProof=true;
        int launches,steers,moves,submissions; Movement movement;
        Fixture() {
            profile.farms.add(new Farm("test corridor",new Pos(-1,-1,-1),new Pos(2,3,1)));
            profile.loggingRunActive=true; session.oneShotFeature=Feature.LOGGING;
            context=new Context(this,this,new LocalNavigator(),profile,session);
        }
        void launch() { assertEquals(Navigation.Result.MOVING,controller.tick(context)); now++; assertEquals(Navigation.Result.MOVING,controller.tick(context)); assertEquals(1,launches); }
        Navigation.Result pose(double px,double py,double pz,boolean ground) { x=px;y=py;z=pz;grounded=ground;now++;return controller.tick(context); }
        public long tick() { return now; } public long dayTime() { return 1000; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,true,true); }
        public BlockData block(Pos p) { return new BlockData(p,"minecraft:air",Map.of()); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return p.equals(FROM)||p.equals(TO); }
        public double standingY(Pos p) { return p.equals(FROM)?0:p.equals(TO)?1:Double.NaN; }
        public boolean canTraverse(Pos a,Pos b) { return false; }
        public boolean canLoggingJump(LoggingJumpEdge edge,Profile profile) { return nativeProof && edge.equals(EDGE); }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int index,ItemData item) { return false; }
        public boolean busy() { return false; }
        public long submit(Action action) { submissions++; throw new AssertionError("No interaction is permitted during jump alignment"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public void move(Movement input) { assertFalse(input.jump());assertFalse(input.sprint());assertFalse(input.sneak());movement=input;moves++; }
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch) { if(launch) launches++; else steers++; movement=null; return true; }
        public void stopMovement() { movement=null; }
        public void cancel() { stopMovement(); }
    }
}
