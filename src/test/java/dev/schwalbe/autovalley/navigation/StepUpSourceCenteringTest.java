package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-only input physics and detached ascent observations, never a live-game motion claim. */
class StepUpSourceCenteringTest {
    private static final Pos SOURCE=new Pos(706,71,1595),TARGET=new Pos(705,72,1595);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(SOURCE,TARGET);

    @Test void theObservedSameCellEdgePoseCentersBeforeOneAuthorizedStepUpPulse() {
        Fixture f=new Fixture();
        assertFalse(LoggingJumpRules.centered(f.player(),SOURCE,.45));assertEquals(SOURCE,NavigationFeet.resolve(f,f.player()));
        f.centerUntilLaunch(.13);
        assertEquals(1,f.launches);assertTrue(f.controller.attempted());assertEquals(LoggingJumpController.Phase.LIFT,f.controller.phase());
        assertTrue(LoggingJumpRules.centered(f.launchPose,SOURCE,.06));assertEquals(0,f.loggingCalls);assertEquals(0,f.submissions);
        for(int repeat=0;repeat<10;repeat++)assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));
        assertEquals(1,f.launches);assertEquals(0,f.flightCalls,"same-tick polling must not consume the launch pulse");
        assertEquals(Navigation.Result.MOVING,f.pose(f.x,71.42,f.z,false));
        assertEquals(Navigation.Result.MOVING,f.pose(706.2,72.1,1595.5,false));
        assertEquals(Navigation.Result.MOVING,f.pose(705.6,72,1595.5,true));
        assertEquals(Navigation.Result.MOVING,f.pose(705.6,72,1595.5,true));
        assertEquals(Navigation.Result.ARRIVED,f.pose(705.6,72,1595.5,true));
        assertEquals(1,f.launches);assertNull(f.movement);assertEquals(0,f.submissions);
    }

    @Test void sameCellCornersAndDifferentGroundResponsesSettleWithoutLeavingTheSourceCell() {
        for(double[] point:new double[][]{{706.9955,1595.511},{706.9955,1595.9955},{706.0045,1595.0045}})
            for(double acceleration:new double[]{.06,.13,.30}) {
                Fixture f=new Fixture();f.x=point[0];f.z=point[1];f.centerUntilLaunch(acceleration);
                assertEquals(1,f.launches,f.debug());assertTrue(f.now<=60,f.debug());
                assertTrue(f.maximumInput>0 && f.maximumInput<1,"the added outer path uses reduced ordinary walking input");
                assertTrue(LoggingJumpRules.centered(f.launchPose,SOURCE,.06));assertTrue(Math.hypot(f.vx,f.vz)<=.002,f.debug());
                assertEquals(0,f.airMoves);assertEquals(0,f.loggingCalls);assertEquals(0,f.submissions);
            }
    }

    @Test void outerCenteringRequiresTwoDistinctQuietSamplesNotJustPassingThroughTheCenter() {
        Fixture f=new Fixture();assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));
        assertEquals(Navigation.Result.MOVING,f.pose(706.5,71,1595.5,true));assertEquals(0,f.launches);
        assertEquals(Navigation.Result.MOVING,f.pose(706.505,71,1595.5,true));assertEquals(0,f.launches);
        assertEquals(Navigation.Result.MOVING,f.pose(706.505,71,1595.5,true));assertEquals(0,f.launches);
        for(int repeat=0;repeat<10;repeat++)f.controller.tick(f.context);
        assertEquals(0,f.launches,"duplicate polling is not the second quiet observation");
        assertEquals(Navigation.Result.MOVING,f.pose(706.505,71,1595.5,true));assertEquals(1,f.launches);
        assertTrue(f.controller.attempted());assertEquals(0,f.loggingCalls);
    }

    @Test void anUnobservedEntryOrTickGapCannotIssueBlindOuterCenteringInput() {
        Fixture f=new Fixture();assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));assertNull(f.movement);
        f.now++;assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));assertNotNull(f.movement);
        f.now+=2;assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));assertNull(f.movement);
        assertEquals(0,f.launches);assertEquals(LoggingJumpController.Phase.PREPARE,f.controller.phase());
    }

    @Test void outsideCellAirUnknownFlatSupportOrLostAuthorityNeverGainTheOuterException() {
        for(int invalid=0;invalid<9;invalid++) {
            Fixture f=new Fixture();
            switch(invalid) {
                case 0 -> f.x=707.00001;
                case 1 -> f.x=705.99999;
                case 2 -> f.z=1596.00001;
                case 3 -> f.ground=false;
                case 4 -> f.flat=false;
                case 5 -> f.nativeProof=false;
                case 6 -> f.y=71.2;
                case 7 -> f.height=Double.NaN;
                case 8 -> f.cursor=new ItemData("minecraft:stone",1,0,null,false,0);
            }
            assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context),"invalid="+invalid);
            assertEquals(0,f.launches);assertEquals(0,f.moves);assertNull(f.movement);assertFalse(f.controller.attempted());
        }
    }

    @Test void flatProofSupportHeightOrTerrainAuthorityLossDuringCenteringStopsWithoutLaunching() {
        for(int changed=0;changed<3;changed++) {
            Fixture f=new Fixture();f.controller.tick(f.context);f.now++;f.controller.tick(f.context);assertNotNull(f.movement);
            if(changed==0)f.flat=false;
            if(changed==1)f.height+=.0625;
            if(changed==2)f.profile.navigationMode=NavigationMode.WAYPOINTS;
            f.now++;assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));
            assertNull(f.movement);assertEquals(0,f.launches);assertFalse(f.controller.attempted());
        }
    }

    @Test void cancellationAndTheOriginalPreparationTimeoutRemainLatched() {
        for(boolean cancel:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.controller.tick(f.context);f.now++;f.controller.tick(f.context);assertNotNull(f.movement);
            if(cancel)f.controller.cancel();else f.now=61;
            assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);
            f.x=706.5;f.z=1595.5;
            for(int tick=0;tick<5;tick++){f.now++;assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));}
            assertEquals(0,f.launches);assertFalse(f.controller.attempted());assertEquals(0,f.submissions);
        }
    }

    @Test void anAlreadyInnerPreparationDoesNotAcquireANewFlatProofRequirement() {
        Fixture f=new Fixture();f.x=706.8;f.z=1595.5;f.flat=false;
        assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));assertNull(f.movement);
        f.now++;assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));assertNotNull(f.movement);
        assertEquals(.2f,f.movement.inputScale());
        assertEquals(0,f.launches);assertFalse(f.controller.attempted());
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final StepUpController controller=new StepUpController(EDGE);
        final Context context=new Context(this,this,new LocalNavigator(),profile);
        long now;double x=706.9955,y=71,z=1595.511,vx,vz,height=71;
        boolean ground=true,flat=true,nativeProof=true;ItemData cursor=ItemData.EMPTY;Movement movement;PlayerState launchPose;
        int launches,flightCalls,loggingCalls,submissions,moves,airMoves;float maximumInput;
        void centerUntilLaunch(double acceleration) {
            while(launches==0 && now<=61) {
                assertEquals(Navigation.Result.MOVING,controller.tick(context),debug());
                if(launches>0)break;
                physics(acceleration);
                assertEquals(SOURCE,NavigationFeet.resolve(this,player()),"outer centering never leaves its one source cell: "+debug());
                assertTrue(ground);assertEquals(71,y);
            }
            assertEquals(1,launches,debug());
        }
        void physics(double acceleration) {
            if(movement!=null && movement.forward()) {
                vx-=Math.sin(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
                vz+=Math.cos(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
            }
            x+=vx;z+=vz;vx*=.546;vz*=.546;now++;
        }
        Navigation.Result pose(double px,double py,double pz,boolean grounded) {
            x=px;y=py;z=pz;ground=grounded;now++;return controller.tick(context);
        }
        String debug(){return "tick="+now+" xyz="+x+","+y+","+z+" velocity="+vx+","+vz+" phase="+controller.phase()+" failure="+controller.failureReason();}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,ground,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return p.equals(SOURCE)||p.equals(TARGET);}
        public double standingY(Pos p){return p.equals(SOURCE)?height:p.equals(TARGET)?72:Double.NaN;}
        public boolean fullFlatSupport(Pos p){return flat && p.equals(SOURCE);}
        public boolean canTraverse(Pos from,Pos to){return false;}
        public boolean canStepUp(LoggingJumpEdge edge,Profile ignored){return nativeProof && EDGE.equals(edge);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int horizontal,int vertical){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),cursor,false);}public boolean mayPlace(int index,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action action){submissions++;throw new AssertionError("Centering cannot submit an interaction");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("Centering has no action receipt");}
        public void move(Movement intent) {
            if(!ground)airMoves++;
            assertTrue(ground,"ordinary centering/landing input is never issued while airborne");
            assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());
            movement=intent;moves++;maximumInput=Math.max(maximumInput,intent.inputScale());
        }
        public boolean moveStepUp(LoggingJumpEdge edge,boolean launch) {
            assertEquals(EDGE,edge);assertTrue(StepUpRules.permitted(edge,context));
            if(launch){assertEquals(0,launches);assertTrue(ground);assertTrue(LoggingJumpRules.centered(player(),SOURCE,.06));launches++;launchPose=player();}
            else flightCalls++;
            movement=null;return true;
        }
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch){loggingCalls++;throw new AssertionError("Ordinary ascent never borrows logging authority");}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
