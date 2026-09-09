package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached input-response coverage, not a native-game timing benchmark. */
class StepUpInnerCenteringTest {
    private static final Pos SOURCE=new Pos(0,64,0),TARGET=new Pos(0,65,-1);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(SOURCE,TARGET);

    @Test void innerOffsetsAndDiagonalApproachesSettleWithSmallInputBeforeOneLaunch() {
        for(double distance:new double[]{.061,.12,.30,.44})
            for(double angle:new double[]{0,Math.PI/4,Math.PI,5*Math.PI/4})
                for(double acceleration:new double[]{.06,.13,.30}) {
                    Fixture f=new Fixture();
                    f.x=.5+distance*Math.cos(angle);f.z=.5+distance*Math.sin(angle);
                    assertTrue(LoggingJumpRules.centered(f.player(),SOURCE,.45));
                    assertFalse(LoggingJumpRules.centered(f.player(),SOURCE,.06));
                    f.centerUntilLaunch(acceleration);
                    assertEquals(1,f.launches,f.debug());assertTrue(f.now<=60,f.debug());
                    assertEquals(distance<=.20 ? .1f : .2f,f.maximumInput);assertEquals(0,f.fullFlatQueries);
                    assertEquals(0,f.loggingCalls);assertEquals(0,f.submissions);
                }
    }

    @Test void inwardOutwardAndCrosswiseInitialInertiaMustSettleRatherThanTriggerALaunch() {
        for(double[] velocity:new double[][]{{-.035,0},{.035,0},{-.08,.02},{.08,-.02},{0,.06}})
            for(double acceleration:new double[]{.06,.13,.30}) {
                Fixture f=new Fixture();f.x=.62;f.vx=velocity[0];f.vz=velocity[1];
                assertEquals(Navigation.Result.MOVING,f.step());assertNull(f.movement);
                assertEquals(0,f.launches,"unobserved inherited momentum is not a launch proof");
                f.physics(acceleration);f.centerUntilLaunch(acceleration);
                assertEquals(1,f.launches);assertTrue(f.quietObservations>=2,f.debug());
                assertTrue(LoggingJumpRules.centered(f.player(),SOURCE,.06));
            }
    }

    @Test void movingSamplesInsideTheLaunchRadiusAreNotCountedAsQuietObservations() {
        Fixture f=new Fixture();assertEquals(Navigation.Result.MOVING,f.step());
        assertEquals(Navigation.Result.MOVING,f.pose(.53,64,.5,true));assertEquals(0,f.launches);
        assertEquals(Navigation.Result.MOVING,f.pose(.525,64,.5,true));assertEquals(0,f.launches);
        assertEquals(Navigation.Result.MOVING,f.pose(.525,64,.5,true));assertEquals(0,f.launches);
        for(int repeat=0;repeat<8;repeat++)assertEquals(Navigation.Result.MOVING,f.step());
        assertEquals(0,f.launches,"duplicate polling cannot supply the second quiet tick");
        assertEquals(Navigation.Result.MOVING,f.pose(.525,64,.5,true));assertEquals(1,f.launches);
        assertEquals(2,f.quietObservations);
    }

    @Test void firstSampleAndOffCenterObservationGapStopInsteadOfAssumingZeroVelocity() {
        Fixture f=new Fixture();assertEquals(Navigation.Result.MOVING,f.step());assertNull(f.movement);
        for(int repeat=0;repeat<8;repeat++)f.step();
        assertEquals(0,f.moves);assertEquals(0,f.launches);
        f.now++;assertEquals(Navigation.Result.MOVING,f.step());assertNotNull(f.movement);
        assertEquals(.2f,f.movement.inputScale());
        f.now+=2;assertEquals(Navigation.Result.MOVING,f.step());assertNull(f.movement);
        assertEquals(0,f.launches);assertEquals(0,f.quietObservations);
        f.now++;assertEquals(Navigation.Result.MOVING,f.step());assertNotNull(f.movement);
    }

    @Test void aGapInsideTheLaunchRadiusInvalidatesThePreviousQuietStreak() {
        Fixture f=new Fixture();f.step();
        f.pose(.53,64,.5,true);f.pose(.53,64,.5,true);
        assertEquals(1,f.quietObservations);assertEquals(0,f.launches);
        f.now+=2;assertEquals(Navigation.Result.MOVING,f.step());
        assertNull(f.movement);assertEquals(0,f.launches);assertEquals(0,f.quietObservations);
        for(int repeat=0;repeat<8;repeat++)f.step();
        assertEquals(0,f.launches);
        assertEquals(Navigation.Result.MOVING,f.pose(.53,64,.5,true));assertEquals(0,f.launches);
        assertEquals(Navigation.Result.MOVING,f.pose(.53,64,.5,true));assertEquals(1,f.launches);
    }

    @Test void unsafeInnerStartsNeverIssueCenteringOrJumpInput() {
        for(int invalid=0;invalid<10;invalid++) {
            Fixture f=new Fixture();
            switch(invalid) {
                case 0 -> f.ground=false;
                case 1 -> f.nativeProof=false;
                case 2 -> f.height=Double.NaN;
                case 3 -> f.y=64.2;
                case 4 -> f.x=Double.NaN;
                case 5 -> f.cursor=new ItemData("minecraft:stone",1,0,null,false,0);
                case 6 -> f.busy=true;
                case 7 -> f.connected=false;
                case 8 -> f.profile.navigationMode=NavigationMode.WAYPOINTS;
                case 9 -> f.loaded=false;
            }
            assertEquals(Navigation.Result.BLOCKED,f.step(),"invalid="+invalid);
            assertEquals(0,f.moves);assertEquals(0,f.launches);assertFalse(f.controller.attempted());
        }
    }

    @Test void losingGroundAuthorityOrSupportWhileCenteringStopsBeforeAnotherInput() {
        for(int changed=0;changed<6;changed++) {
            Fixture f=new Fixture();f.step();f.now++;f.step();assertNotNull(f.movement);
            int moves=f.moves;
            switch(changed) {
                case 0 -> f.ground=false;
                case 1 -> f.nativeProof=false;
                case 2 -> f.height+=.0625;
                case 3 -> f.cursor=new ItemData("minecraft:stone",1,0,null,false,0);
                case 4 -> f.busy=true;
                case 5 -> f.profile.navigationMode=NavigationMode.WAYPOINTS;
            }
            f.now++;assertEquals(Navigation.Result.BLOCKED,f.step());
            assertNull(f.movement);assertEquals(moves,f.moves);assertEquals(0,f.launches);
        }
    }

    @Test void leavingTheInnerRegionDoesNotWaiveTheExistingFullSupportException() {
        Fixture f=new Fixture();f.step();f.now++;f.step();assertNotNull(f.movement);
        // The legacy inner path has no extra flat-proof requirement; an outward
        // disturbance must still pass the separate, fail-closed outer gate.
        assertEquals(Navigation.Result.BLOCKED,f.pose(.96,64,.5,true));
        assertNull(f.movement);assertEquals(0,f.launches);assertTrue(f.fullFlatQueries>0);
    }

    @Test void cancellationTimeoutAndTimeRewindRemainTerminalWithoutAJump() {
        for(int stop=0;stop<3;stop++) {
            Fixture f=new Fixture();f.step();f.now++;f.step();assertNotNull(f.movement);
            if(stop==0)f.controller.cancel();else if(stop==1)f.now=61;else f.now=0;
            assertEquals(Navigation.Result.BLOCKED,f.step());assertNull(f.movement);
            for(int i=0;i<3;i++)assertEquals(Navigation.Result.BLOCKED,f.pose(.5,64,.5,true));
            assertEquals(0,f.launches);assertFalse(f.controller.attempted());
        }
    }

    @Test void centeredPreparationStillIssuesOneAuthorizedPulseAndNeverAnInteraction() {
        Fixture f=new Fixture();f.centerUntilLaunch(.13);
        for(int i=0;i<8;i++)assertEquals(Navigation.Result.MOVING,f.step());
        assertEquals(1,f.launches);assertEquals(0,f.flightCalls);
        assertEquals(Navigation.Result.MOVING,f.pose(.5,64.42,.48,false));
        assertEquals(Navigation.Result.MOVING,f.pose(.5,65.1,-.2,false));
        assertEquals(Navigation.Result.MOVING,f.pose(.5,65,-.5,true));
        assertEquals(Navigation.Result.MOVING,f.pose(.5,65,-.5,true));
        assertEquals(Navigation.Result.ARRIVED,f.pose(.5,65,-.5,true));
        assertEquals(Navigation.Result.ARRIVED,f.step());assertEquals(1,f.launches);
        assertEquals(0,f.loggingCalls);assertEquals(0,f.submissions);assertNull(f.movement);
    }

    @Test void nativeLaunchRejectionIsNotRetriedAfterSettling() {
        Fixture f=new Fixture();f.acceptLaunch=false;f.step();
        f.pose(.53,64,.5,true);f.pose(.53,64,.5,true);
        assertEquals(Navigation.Result.BLOCKED,f.pose(.53,64,.5,true));
        assertEquals(1,f.launches);assertTrue(f.controller.attempted());assertNull(f.movement);
        for(int i=0;i<4;i++)assertEquals(Navigation.Result.BLOCKED,f.pose(.53,64,.5,true));
        assertEquals(1,f.launches);assertEquals(0,f.submissions);
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final StepUpController controller=new StepUpController(EDGE);
        final Context context=new Context(this,this,new LocalNavigator(),profile);
        long now,lastObservation=Long.MIN_VALUE;double x=.8,y=64,z=.5,vx,vz,height=64,observedX,observedZ;
        boolean ground=true,nativeProof=true,connected=true,loaded=true,busy,acceptLaunch=true;
        ItemData cursor=ItemData.EMPTY;Movement movement;
        int launches,flightCalls,loggingCalls,submissions,moves,fullFlatQueries,quietObservations;float maximumInput;

        Navigation.Result step() {
            if(now!=lastObservation) {
                boolean consecutive=lastObservation!=Long.MIN_VALUE && now-lastObservation==1;
                double speed=Math.hypot(x-observedX,z-observedZ);
                if(consecutive && ground && LoggingJumpRules.centered(player(),SOURCE,.06) && speed<=.002)quietObservations++;
                else quietObservations=0;
                lastObservation=now;observedX=x;observedZ=z;
            }
            return controller.tick(context);
        }
        Navigation.Result pose(double px,double py,double pz,boolean grounded) {
            x=px;y=py;z=pz;ground=grounded;now++;return step();
        }
        void centerUntilLaunch(double acceleration) {
            while(launches==0 && now<=61) {
                assertEquals(Navigation.Result.MOVING,step(),debug());
                if(launches>0)break;
                physics(acceleration);
                assertEquals(SOURCE,NavigationFeet.resolve(this,player()),debug());
                assertTrue(ground);assertEquals(64,y);
            }
            assertEquals(1,launches,debug());assertTrue(quietObservations>=2,debug());
        }
        void physics(double acceleration) {
            if(movement!=null && movement.forward()) {
                vx-=Math.sin(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
                vz+=Math.cos(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
            }
            x+=vx;z+=vz;vx*=.546;vz*=.546;now++;
        }
        String debug(){return "tick="+now+" offset="+(x-.5)+","+(z-.5)+" quiet="+quietObservations+" phase="+controller.phase()+" reason="+controller.failureReason();}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,ground,false,20,20,0,connected,true);}
        public boolean loaded(Pos p){return loaded;}public boolean canStand(Pos p){return p.equals(SOURCE)||p.equals(TARGET);}
        public double standingY(Pos p){return p.equals(SOURCE)?height:p.equals(TARGET)?65:Double.NaN;}
        public boolean fullFlatSupport(Pos p){fullFlatQueries++;return false;}
        public boolean canTraverse(Pos from,Pos to){return false;}
        public boolean canStepUp(LoggingJumpEdge edge,Profile ignored){return nativeProof && EDGE.equals(edge);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int horizontal,int vertical){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),cursor,false);}public boolean mayPlace(int index,ItemData item){return false;}
        public boolean busy(){return busy;}public long submit(Action action){submissions++;throw new AssertionError("No interaction belongs to centering");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("Centering cannot read an action receipt");}
        public void move(Movement intent) {
            assertTrue(ground);assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());
            if(controller.phase()==LoggingJumpController.Phase.PREPARE)
                assertEquals(Math.hypot(x-.5,z-.5)<=.20 ? .1f : .2f,intent.inputScale(),
                    "PREPARE uses a smaller pulse near its narrow launch radius");
            movement=intent;moves++;maximumInput=Math.max(maximumInput,intent.inputScale());
        }
        public boolean moveStepUp(LoggingJumpEdge edge,boolean launch) {
            assertEquals(EDGE,edge);assertTrue(StepUpRules.permitted(edge,context));
            if(launch) {
                assertEquals(0,launches);assertTrue(ground);assertTrue(LoggingJumpRules.centered(player(),SOURCE,.06));
                assertTrue(quietObservations>=2,"Two real low-motion observations are required");launches++;
            } else flightCalls++;
            movement=null;return !launch || acceptLaunch;
        }
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch){loggingCalls++;throw new AssertionError("Transit cannot borrow logging authority");}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
