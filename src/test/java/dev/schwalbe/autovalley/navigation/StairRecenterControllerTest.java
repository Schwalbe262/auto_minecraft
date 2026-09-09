package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached input/auto-step fixture, not a substitute for the native stair collision proof. */
class StairRecenterControllerTest {
    private static final Pos ANCHOR=new Pos(0,1,0);
    @Test void halfTreadRecentersWithOrdinaryAutoStepAcrossDirectionsAndInputResponses() {
        for(double response:List.of(.06,.13,.30))for(int[] axis:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
            Fixture f=new Fixture(response,axis[0],axis[1]);int ticks=f.finish();
            assertTrue(ticks<=60);assertEquals(1,f.y,1e-5);assertTrue(f.distance()<=.06);
            assertTrue(f.lastMotion<=.002);assertEquals(1,f.stepUps);assertEquals(ANCHOR,f.controller.anchor());
            assertFalse(f.inputs.isEmpty());assertEquals(0,f.actions);assertNull(f.movement);
            assertTrue(f.proofCalls>=ticks,"Every movement sample needs fresh native stair proof");
        }
    }
    @Test void initialTwoQuietTicksCannotCountResidualCoastOrDuplicatePolls() {
        Fixture f=new Fixture(.13);assertEquals(Navigation.Result.MOVING,f.step());
        f.x+=.01;f.now++;f.step();assertTrue(f.inputs.isEmpty());
        for(int i=0;i<8;i++)f.step();assertTrue(f.inputs.isEmpty());
        f.now++;f.step();assertTrue(f.inputs.isEmpty());f.now++;f.step();assertTrue(f.inputs.isEmpty());
        f.now++;f.step();assertFalse(f.inputs.isEmpty());
    }
    @Test void autoStepVerticalDisplacementIsNotAQuietLandingAndNeedsTwoLaterSamples() {
        Fixture f=new Fixture(.13);f.x=.45;f.z=.5;
        // A tiny horizontal change isolates the vertical-motion accounting; native geometry has separate tests.
        for(int i=0;i<3;i++){assertEquals(Navigation.Result.MOVING,f.step());f.now++;}
        f.x=.5;f.y=1;assertEquals(Navigation.Result.MOVING,f.step());assertNull(f.movement);
        for(int i=0;i<8;i++)assertEquals(Navigation.Result.MOVING,f.step());
        f.now++;assertEquals(Navigation.Result.MOVING,f.step());
        f.now++;assertEquals(Navigation.Result.ARRIVED,f.step());assertNull(f.movement);
    }
    @Test void centeredHalfTreadCannotImpersonateFullHeightCompletion() {
        Fixture f=new Fixture(.13);f.x=.5;f.z=.5;
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<25 && result==Navigation.Result.MOVING;i++){result=f.step();f.now++;}
        assertEquals(Navigation.Result.BLOCKED,result);assertEquals(.5,f.y);assertTrue(f.inputs.isEmpty());
    }
    @Test void airborneHeightDropUnexpectedMotionAndChangedGeometryStopWithoutJumpOrInteraction() {
        for(String changed:List.of("airborne","drop","motion","proof","surface","unknownphysics","unloaded","unsupported","wrongheight")) {
            Fixture f=new Fixture(.13);f.untilInput();f.now++;
            switch(changed) {
                case "airborne" -> f.grounded=false;
                case "drop" -> {f.y=1;f.step();f.now++;f.y=.5;}
                case "motion" -> f.x+=.21;
                case "proof" -> f.proof=false;
                case "surface" -> f.surface=1.01;
                case "unknownphysics" -> f.physics=false;
                case "unloaded" -> f.loaded=false;
                case "unsupported" -> f.stand=false;
                case "wrongheight" -> f.y=.75;
            }
            assertEquals(Navigation.Result.BLOCKED,f.step(),changed);assertNull(f.movement,changed);assertEquals(0,f.actions);
        }
    }
    @Test void liveContextMenuCursorFenceDomainAndManualControlChangesRevokePermission() {
        for(String changed:List.of("world","actions","profile","session","menu","cursor","busy","fence","domain","focus","disconnect","sleep","mode")) {
            Fixture f=new Fixture(.13);f.untilInput();f.now++;Context c=f.context;
            switch(changed) {
                case "world" -> c=new Context(new Fixture(.13),f,f,f.profile,f.session);
                case "actions" -> c=new Context(f,new Fixture(.13),f,f.profile,f.session);
                case "profile" -> c=new Context(f,f,f,new Profile(),f.session);
                case "session" -> c=new Context(f,f,f,f.profile,new SessionState());
                case "menu" -> f.container=true;
                case "cursor" -> f.cursor=new ItemData("minecraft:stone",1,0,null,false,99);
                case "busy" -> f.busy=true;
                case "fence" -> f.fence="unconfirmed native action";
                case "domain" -> f.domain=false;
                case "focus" -> f.focused=false;
                case "disconnect" -> f.connected=false;
                case "sleep" -> f.sleeping=true;
                case "mode" -> f.profile.navigationMode=NavigationMode.WAYPOINTS;
            }
            assertEquals(Navigation.Result.BLOCKED,f.controller.tick(c),changed);assertNull(f.movement,changed);assertEquals(0,f.actions);
        }
    }
    @Test void timeGapRequiresNewQuietObservationAndBackwardClockOrCancellationCannotResume() {
        Fixture f=new Fixture(.13);f.untilInput();int inputs=f.inputs.size();f.now+=2;f.step();assertNull(f.movement);
        f.now++;f.step();f.now++;f.step();assertEquals(inputs,f.inputs.size());f.now++;f.step();assertTrue(f.inputs.size()>inputs);
        f.now--;assertEquals(Navigation.Result.BLOCKED,f.step());assertNull(f.movement);
        Fixture cancelled=new Fixture(.13);cancelled.untilInput();cancelled.controller.cancel();assertNull(cancelled.movement);
        cancelled.now++;assertEquals(Navigation.Result.BLOCKED,cancelled.step());assertEquals(0,cancelled.actions);
    }
    @Test void noProgressAndTotalTimeLimitsAreFiniteWithoutNativeActions() {
        Fixture stalled=new Fixture(.13);Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<30 && result==Navigation.Result.MOVING;i++){result=stalled.step();stalled.now++;}
        assertEquals(Navigation.Result.BLOCKED,result);assertNull(stalled.movement);assertEquals(0,stalled.actions);
        Fixture expired=new Fixture(.13);expired.step();expired.now=61;
        assertEquals(Navigation.Result.BLOCKED,expired.step());assertNull(expired.movement);
    }
    @Test void unknownWorldCannotGrantStairRecovery() {
        assertFalse(new UnknownWorld().canRecenterOnStair(ANCHOR));
    }
    private static class UnknownWorld implements WorldAccess {
        public long tick(){return 0;}public long dayTime(){return 0;}
        public PlayerState player(){return new PlayerState(.15,.5,.5,0,0,true,false,20,20,0,true,true);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return p.equals(ANCHOR);}
        public boolean canTraverse(Pos a,Pos b){return false;}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
    }
    private static final class Fixture extends UnknownWorld implements ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();final Context context=new Context(this,this,this,profile,session);
        final StairRecenterController controller;final List<Movement> inputs=new ArrayList<>();final double response;final int dx,dz;
        double x,y=.5,z,vx,vz,surface=1,lastMotion;long now;int proofCalls,actions,stepUps;
        boolean grounded=true,focused=true,connected=true,sleeping,proof=true,physics=true,loaded=true,stand=true,domain=true,container,busy;
        ItemData cursor=ItemData.EMPTY;String fence;Movement movement;
        Fixture(double response){this(response,1,0);}
        Fixture(double response,int dx,int dz){this.response=response;this.dx=dx;this.dz=dz;
            x=.5-.35*dx-.2*dz;z=.5-.35*dz+.2*dx;profile.navigationMode=NavigationMode.TERRAIN;profile.allowBackground=false;
            controller=new StairRecenterController(ANCHOR,context);}
        public long tick(){return now;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,sleeping,20,20,0,connected,focused);}
        public boolean loaded(Pos p){return loaded;}public boolean canStand(Pos p){return stand&&p.equals(ANCHOR);}
        public double standingY(Pos p){return surface;}public boolean standardDescentPhysics(){return physics;}
        public boolean canRecenterOnStair(Pos p){proofCalls++;return proof&&p.equals(ANCHOR);}
        public MenuData menu(){return new MenuData(container?1:0,0,List.of(),cursor,container);}
        public boolean busy(){return busy;}public String pauseReason(){return fence;}
        public long submit(Action action){actions++;throw new AssertionError("Recovery cannot send interactions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No action receipt belongs to recentering");}
        public void move(Movement value){assertTrue(grounded);assertTrue(value.forward());assertFalse(value.jump());assertFalse(value.sprint());assertFalse(value.sneak());
            assertTrue(value.inputScale()==.1f || value.inputScale()==.2f);movement=value;inputs.add(value);}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
        public Result moveTo(Pos target,double reach,Context c){throw new AssertionError("Controller does not launch a new navigation request");}
        public boolean permitsTransit(Pos p,Context c){return domain;}public void reset(){}
        Navigation.Result step(){return controller.tick(context);}
        double distance(){return Math.hypot(x-.5,z-.5);}
        void untilInput(){for(int i=0;i<10&&inputs.isEmpty();i++){assertEquals(Navigation.Result.MOVING,step());if(inputs.isEmpty())now++;}assertNotNull(movement);}
        void advance(){double previousY=y;
            if(movement!=null){double yaw=Math.toRadians(movement.yaw());vx-=Math.sin(yaw)*response*movement.inputScale();vz+=Math.cos(yaw)*response*movement.inputScale();}
            x+=vx;z+=vz;double along=(x-.5)*dx+(z-.5)*dz+.5;
            if(y==.5 && along>.2){y=1;stepUps++;}
            lastMotion=Math.sqrt(vx*vx+vz*vz+(y-previousY)*(y-previousY));vx*=.546;vz*=.546;now++;
        }
        int finish(){for(int i=0;i<65;i++){Navigation.Result r=step();assertNotEquals(Navigation.Result.BLOCKED,r,controller.failureReason()+" at "+distance());
            if(r==Navigation.Result.ARRIVED)return (int)now;advance();}throw new AssertionError("Recovery did not finish");}
    }
}
