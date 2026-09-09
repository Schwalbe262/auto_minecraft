package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached landing observations plus a one-axis ground-response model, not native ascent physics. */
class StepUpLandingOscillationTest {
    private static final Pos FROM=new Pos(0,0,0),TO=new Pos(1,1,0);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);
    private static final double LATE_REST_X=1.5-.1411549464;

    @Test void formerFullInputPolicyReproducesTheObservedBounceAndTimesOutWithoutAnotherLaunch() {
        for(double response:new double[]{.1296481671,.13}) {
            Fixture f=new Fixture(true); f.lateGroundedRest();
            assertEquals(1f,f.movement.inputScale());
            assertEquals(Navigation.Result.MOVING,f.physics(response));
            assertEquals(LATE_REST_X+response,f.x,1e-8);
            assertNotNull(f.movement); assertEquals(90,f.movement.yaw(),.001,"Full opposite input reverses the near-center arrival");
            assertEquals(Navigation.Result.MOVING,f.physics(response));
            assertTrue(f.x<LATE_REST_X+response,"The counterpulse moves away from the center again");
            Navigation.Result result=Navigation.Result.MOVING;
            while(result==Navigation.Result.MOVING && f.age()<=41)result=f.physics(response);
            assertEquals(Navigation.Result.BLOCKED,result); assertEquals(41,f.age());
            assertTrue(f.ground); assertEquals(1,f.y); assertTrue(f.controller.failureReason().contains("착지"));
            f.assertStoppedAfterOneLaunch();
            assertEquals(Navigation.Result.BLOCKED,f.observe(42,1.5,1,true));
            f.assertStoppedAfterOneLaunch();
        }
    }

    @Test void reducedNearCenterInputSettlesBeforeTheUnchangedDeadlineAcrossGroundResponses() {
        double[] responses={.06,.1296481671,.13,.30}; int[] completedAt={38,39,39,40};
        for(int index=0;index<responses.length;index++) {
            Fixture f=new Fixture(false); f.lateGroundedRest();
            assertEquals(.2f,f.movement.inputScale());
            Navigation.Result result=Navigation.Result.MOVING;
            while(result==Navigation.Result.MOVING && f.age()<=41)result=f.physics(responses[index]);
            assertEquals(Navigation.Result.ARRIVED,result,"response="+responses[index]+" "+f.debug());
            assertEquals(completedAt[index],f.age(),f.debug());
            assertTrue(LoggingJumpRules.centered(f.player(),TO,.12));
            assertEquals(0,f.previousDisplacement); assertEquals(0,f.lastDisplacement);
            assertEquals(LoggingJumpController.Phase.COMPLETE,f.controller.phase());
            f.assertStoppedAfterOneLaunch();
        }
    }

    @Test void ordinaryLandingCorrectionNeverExtendsTheSeparatelyAuthorizedAirborneInput() {
        Fixture f=new Fixture(false); f.launch();
        assertEquals(Navigation.Result.MOVING,f.observe(1,f.x,.419999986887,false));
        assertEquals(Navigation.Result.MOVING,f.observe(8,.778050,1.024424088214,false));
        assertEquals(2,f.flightCalls); assertEquals(0,f.moves); assertEquals(0,f.airMoves);
        f.finishLateGroundedRest(); assertNotNull(f.movement); int flightCalls=f.flightCalls;
        assertEquals(Navigation.Result.BLOCKED,f.observe(34,f.x,1.01,false));
        assertEquals(flightCalls,f.flightCalls,"Loss of ground after LAND cannot regain airborne authority");
        assertEquals(0,f.airMoves); f.assertStoppedAfterOneLaunch();
    }

    @Test void nearCenterCorrectionRetainsSupportAuthorityHeightAndCancellationGuards() {
        for(int invalid=0;invalid<8;invalid++) {
            Fixture f=new Fixture(false); f.lateGroundedRest(); assertNotNull(f.movement);
            switch(invalid) {
                case 0 -> f.nativeProof=false;
                case 1 -> f.supported=false;
                case 2 -> f.loaded=false;
                case 3 -> f.landingHeight=1.0625;
                case 4 -> f.landingHeight=Double.NaN;
                case 5 -> f.profile.navigationMode=NavigationMode.WAYPOINTS;
                case 6 -> f.cursor=new ItemData("minecraft:stone",1,0,null,false,0);
                case 7 -> f.controller.cancel();
            }
            assertEquals(Navigation.Result.BLOCKED,f.observe(34,f.x,1,true),"invalid="+invalid);
            f.assertStoppedAfterOneLaunch();
            f.nativeProof=true; f.supported=true; f.loaded=true; f.landingHeight=1;
            f.profile.navigationMode=NavigationMode.TERRAIN; f.cursor=ItemData.EMPTY;
            assertEquals(Navigation.Result.BLOCKED,f.observe(35,1.5,1,true));
            f.assertStoppedAfterOneLaunch();
        }
    }

    @Test void missingAndDuplicateTicksCannotProvideBlindCorrectionOrTwoQuietObservations() {
        Fixture f=new Fixture(false); f.lateGroundedRest();
        assertEquals(Navigation.Result.MOVING,f.observe(35,f.x,1,true)); assertNull(f.movement);
        assertEquals(Navigation.Result.MOVING,f.observe(36,1.5,1,true),"Crossing the center with visible motion is not quiet");
        assertEquals(Navigation.Result.MOVING,f.observe(37,1.5,1,true));
        for(int duplicate=0;duplicate<10;duplicate++)assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));
        assertEquals(Navigation.Result.ARRIVED,f.observe(38,1.5,1,true));
        f.assertStoppedAfterOneLaunch();
    }

    @Test void theOriginalTimeoutStillRejectsAQuietSupportedCenterThatArrivesTooLate() {
        Fixture f=new Fixture(false); f.lateGroundedRest();
        assertEquals(Navigation.Result.BLOCKED,f.observe(41,1.5,1,true));
        assertEquals(LoggingJumpController.Phase.FAILED,f.controller.phase()); f.assertStoppedAfterOneLaunch();
        assertEquals(Navigation.Result.BLOCKED,f.observe(42,1.5,1,true)); f.assertStoppedAfterOneLaunch();
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile(); final StepUpController controller=new StepUpController(EDGE);
        final Context context=new Context(this,this,new LocalNavigator(),profile);
        final boolean formerFullInput; long now,launchTick; double x=.5170337724174,y,z=.5,vx,landingHeight=1;
        double lastDisplacement,previousDisplacement;
        boolean ground=true,nativeProof=true,supported=true,loaded=true;
        ItemData cursor=ItemData.EMPTY; Movement movement; int launches,flightCalls,moves,airMoves,submissions;
        Fixture(boolean formerFullInput) { this.formerFullInput=formerFullInput; profile.navigationMode=NavigationMode.TERRAIN; }
        void launch() {
            assertEquals(Navigation.Result.MOVING,controller.tick(context)); now++;
            assertEquals(Navigation.Result.MOVING,controller.tick(context)); assertEquals(1,launches);
        }
        void lateGroundedRest() {
            launch();
            assertEquals(Navigation.Result.MOVING,observe(1,x,.419999986887,false));
            assertEquals(Navigation.Result.MOVING,observe(8,.778050,1.024424088214,false));
            finishLateGroundedRest();
        }
        void finishLateGroundedRest() {
            // Reflected/translated longitudinal observations: launch+9 first native ground,
            // launch+32/+33 the same stopped position. The intervening motion is NOT invented.
            assertEquals(Navigation.Result.MOVING,observe(9,.8721581714337,1,true));
            assertEquals(LoggingJumpController.Phase.LAND,controller.phase());
            assertEquals(Navigation.Result.MOVING,observe(32,LATE_REST_X,1,true)); assertNull(movement);
            assertEquals(Navigation.Result.MOVING,observe(33,LATE_REST_X,1,true));
            assertNotNull(movement); vx=0;
        }
        Navigation.Result observe(long age,double px,double py,boolean grounded) {
            now=launchTick+age; x=px; y=py; ground=grounded; return controller.tick(context);
        }
        Navigation.Result physics(double response) {
            assertTrue(ground); assertEquals(1,y);
            // Detached normal-ground model: small stored velocity is zeroed before acceleration,
            // then displacement is observed and .546 drag retained for the following tick.
            if(Math.abs(vx)<.003)vx=0;
            if(movement!=null && movement.forward())vx-=Math.sin(Math.toRadians(movement.yaw()))*response*movement.inputScale();
            previousDisplacement=lastDisplacement; lastDisplacement=vx;
            double nextX=x+vx; vx*=.546;
            assertTrue(Double.isFinite(nextX)); assertTrue(Math.abs(nextX-1.5)<=.45,"Ground correction stays on the same landing cell");
            return observe(age()+1,nextX,1,true);
        }
        long age(){return now-launchTick;}
        String debug(){return "age="+age()+" x="+x+" velocity="+vx+" phase="+controller.phase()+" failure="+controller.failureReason();}
        void assertStoppedAfterOneLaunch(){assertEquals(1,launches);assertTrue(controller.attempted());assertNull(movement);assertEquals(0,airMoves);assertEquals(0,submissions);}
        public long tick(){return now;} public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,ground,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return loaded;}
        public boolean canStand(Pos p){return supported && (p.equals(FROM)||p.equals(TO));}
        public double standingY(Pos p){return p.equals(FROM)?0:p.equals(TO)?landingHeight:Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return false;}
        public boolean canStepUp(LoggingJumpEdge edge,Profile ignored){return nativeProof && EDGE.equals(edge);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int horizontal,int vertical){return List.of();}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),cursor,false);}
        public boolean mayPlace(int index,ItemData item){return false;} public boolean busy(){return false;}
        public long submit(Action action){submissions++;throw new AssertionError("Landing does not interact with the world");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("Landing has no action receipt");}
        public void move(Movement intent) {
            if(!ground)airMoves++;
            assertTrue(ground,"Only the existing native ascent port may own airborne movement");
            assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());moves++;
            // Test-only counterfactual: this restores the former binary LAND actuator, without
            // copying the controller, loosening proofs or changing its observed-completion rules.
            movement=formerFullInput && controller.phase()==LoggingJumpController.Phase.LAND
                ? new Movement(intent.yaw(),intent.pitch(),true,false,false,false,1f) : intent;
        }
        public boolean moveStepUp(LoggingJumpEdge edge,boolean launch) {
            assertEquals(EDGE,edge); assertTrue(StepUpRules.permitted(edge,context));
            if(launch){assertEquals(0,launches);assertTrue(ground);assertTrue(LoggingJumpRules.centered(player(),FROM,.06));launches++;launchTick=now;}
            else flightCalls++;
            movement=null;return true;
        }
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch){throw new AssertionError("Transit cannot borrow logging authority");}
        public void stopMovement(){movement=null;} public void cancel(){stopMovement();}
    }
}
