package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached response model; native support/swept-body geometry has separate native tests. */
class GroundRecenterNavigationTest {
    private static final Pos ANCHOR=new Pos(706,71,1594),TARGET=new Pos(703,71,1594);

    @Test void observedEdgePoseReachesActualQuietSupportBeforeAStarForSeveralResponses() {
        for(double response:new double[]{.06,.13,.30}) {
            Fixture f=new Fixture(response);f.proof=true;
            assertEquals(new Pos(707,71,1594),NavigationFeet.resolve(f,f.player()));
            for(int i=0;i<3;i++) {
                assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.movement,"Two initial stationary intervals precede all input");
                assertEquals(0,f.navigation.diagnostics().get("requestExpanded"));f.advance();
            }
            int ticks=f.finishRecenter();
            assertTrue(ticks<=60,"Response "+response+", ticks "+ticks);
            assertEquals("GROUND_RECENTER_COMPLETE",f.navigation.diagnosticStatus());
            assertEquals(ANCHOR,NavigationFeet.resolve(f,f.player()));assertTrue(f.distance()<=.06);
            assertTrue(Math.hypot(f.lastDx,f.lastDz)<=.002);assertNull(f.movement);
            assertEquals(0,f.navigation.diagnostics().get("requestExpanded"),"No fictional neighbouring A* origin");
            assertTrue(f.inputs.stream().anyMatch(m->m.inputScale()==.2f));
            assertEquals(0,f.submissions);
            for(int i=0;i<20 && ((Number)f.navigation.diagnostics().get("requestExpanded")).intValue()==0;i++) {
                f.advance();assertEquals(Navigation.Result.MOVING,f.move());
            }
            assertTrue(((Number)f.navigation.diagnostics().get("requestExpanded")).intValue()>0);
        }
    }

    @Test void unknownProofAndHarvestLookaheadNeverAuthorizeRecentring() {
        Fixture unknown=new Fixture(.13);
        assertFalse(new UnknownWorld().canRecenterOnSupport(ANCHOR));
        assertEquals(Navigation.Result.BLOCKED,unknown.move());assertEquals(Navigation.Failure.INVALID_START,unknown.navigation.failureKind());
        assertTrue(unknown.inputs.isEmpty());
        Fixture lookahead=new Fixture(.13);lookahead.proof=true;
        assertEquals(Navigation.Result.BLOCKED,lookahead.navigation.moveToWithoutInteraction(TARGET,.1,lookahead.context));
        assertTrue(lookahead.inputs.isEmpty());assertEquals(0,lookahead.proofQueries);
    }

    @Test void aNormallySupportedOriginDoesNotUseTheSpecialProof() {
        Fixture f=new Fixture(.13);f.x=ANCHOR.x()+.5;f.z=ANCHOR.z()+.5;f.proof=true;
        assertEquals(Navigation.Result.MOVING,f.move());assertEquals(0,f.proofQueries);
        assertFalse(f.navigation.diagnosticStatus().startsWith("GROUND_RECENTER"));
    }

    @Test void changedSupportAuthorityOrPlayerStateStopsBeforeAnyFurtherInput() {
        for(String change:List.of("proof","support","height","airborne","cursor","container","busy","fence","focus","profile","session","world")) {
            Fixture f=new Fixture(.13);f.proof=true;f.untilInput();int inputs=f.inputs.size();
            Context next=f.context;
            switch(change) {
                case "proof"->f.proof=false;
                case "support"->f.standing.remove(ANCHOR);
                case "height"->f.surface=71.125;
                case "airborne"->f.grounded=false;
                case "cursor"->f.cursor=new ItemData("minecraft:stone",1,0,null,false,Integer.MAX_VALUE);
                case "container"->f.container=true;
                case "busy"->f.busy=true;
                case "fence"->f.fence="unconfirmed";
                case "focus"->f.focused=false;
                case "profile"->{Profile replacement=new Profile();replacement.navigationMode=NavigationMode.TERRAIN;next=new Context(f,f,f.navigation,replacement,f.context.session());}
                case "session"->next=new Context(f,f,f.navigation,f.profile,new SessionState());
                case "world"->next=new Context(new UnknownWorld(),f,f.navigation,f.profile,f.context.session());
                default->throw new AssertionError(change);
            }
            if (change.equals("profile") || change.equals("session")) {
                // A new public request cancels the old controller, then must obtain its own initial quiet samples.
                assertEquals(Navigation.Result.MOVING,f.navigation.moveTo(TARGET,.1,next));
            } else assertEquals(Navigation.Result.BLOCKED,f.navigation.moveTo(TARGET,.1,next),change);
            assertNull(f.movement,change);assertEquals(inputs,f.inputs.size(),change);assertEquals(0,f.submissions);
        }
    }

    @Test void anActiveAlignmentCannotFinishThroughTheOrdinaryNearTargetShortcut() {
        Fixture f=new Fixture(.13);f.proof=true;f.untilInput();f.forceInteraction=true;
        assertEquals(Navigation.Result.MOVING,f.move());assertEquals("GROUND_RECENTER",f.navigation.diagnosticStatus());
        assertTrue(f.distance()>.06);assertEquals(0,f.submissions);
    }

    @Test void duplicateTicksDoNotRefuelQuietSamplesAndObservationGapsStopInput() {
        Fixture f=new Fixture(.13);f.proof=true;assertEquals(Navigation.Result.MOVING,f.move());
        for(int i=0;i<20;i++)assertEquals(Navigation.Result.MOVING,f.move());
        assertTrue(f.inputs.isEmpty());f.advance();f.move();assertNull(f.movement);
        f.advance();f.move();assertNull(f.movement);f.advance();f.move();assertNotNull(f.movement);
        int inputs=f.inputs.size();f.now+=3;
        assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.movement);assertEquals(inputs,f.inputs.size());
    }

    @Test void aStalledAlignmentIsBoundedAndCannotRestartWithoutReset() {
        Fixture f=new Fixture(.13);f.proof=true;Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<70 && result==Navigation.Result.MOVING;i++){result=f.move();f.now++;}
        assertEquals(Navigation.Result.BLOCKED,result);assertTrue(f.now<=61);assertNull(f.movement);int inputs=f.inputs.size();
        for(int i=0;i<5;i++){f.now++;assertEquals(Navigation.Result.BLOCKED,f.move());}
        assertEquals(inputs,f.inputs.size());f.navigation.reset();f.now++;
        assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.movement,"An explicit reset starts with fresh stationary observation");
    }

    @Test void resetAndSwitchingToLookaheadClearTheControllerAndItsInput() {
        Fixture f=new Fixture(.13);f.proof=true;f.untilInput();f.navigation.reset();assertNull(f.movement);
        assertNull(f.navigation.diagnostics().get("recenterAnchor"));
        f.now++;assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.movement);
        f.now++;assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToWithoutInteraction(TARGET,.1,f.context));assertNull(f.movement);
    }

    @Test void anUnloadedWrongHeightOrDistantAnchorNeverBecomesAnOrigin() {
        for(String bad:List.of("unloaded","wrong height","distance","nonflat")) {
            Fixture f=new Fixture(.13);f.proof=true;
            switch(bad){case "unloaded"->f.unloaded=ANCHOR;case "wrong height"->f.surface=72;case "distance"->f.x=708;case "nonflat"->f.flat=false;default->throw new AssertionError();}
            assertEquals(Navigation.Result.BLOCKED,f.move(),bad);assertTrue(f.inputs.isEmpty());assertEquals(0,f.submissions);
        }
    }

    private static class UnknownWorld implements WorldAccess {
        public long tick(){return 0;}public long dayTime(){return 0;}
        public PlayerState player(){return new PlayerState(707.2863493248783,71,1594.7950263,0,0,true,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return false;}
        public boolean canTraverse(Pos a,Pos b){return false;}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
    }
    private static final class Fixture extends UnknownWorld implements ActionPort {
        final Profile profile=new Profile();final LocalNavigator navigation=new LocalNavigator();final Context context=new Context(this,this,navigation,profile,new SessionState());
        final Set<Pos> standing=new HashSet<>();final List<Movement> inputs=new ArrayList<>();final double response;
        double x=707.2863493248783,y=71,z=1594.7950263,vx,vz,lastDx,lastDz,surface=71;
        long now;boolean proof,grounded=true,focused=true,busy,container,forceInteraction,flat=true;int proofQueries,submissions;
        String fence;ItemData cursor=ItemData.EMPTY;Pos unloaded;Movement movement;
        Fixture(double response){this.response=response;profile.navigationMode=NavigationMode.TERRAIN;profile.allowBackground=false;for(int px=703;px<=706;px++)standing.add(new Pos(px,71,1594));}
        public long tick(){return now;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,true,focused);}
        public boolean loaded(Pos p){return !p.equals(unloaded);}
        public boolean canStand(Pos p){return loaded(p)&&standing.contains(p);}
        public double standingY(Pos p){return canStand(p)?surface:Double.NaN;}
        public boolean fullFlatSupport(Pos p){return flat&&canStand(p);}
        public boolean canRecenterOnSupport(Pos p){proofQueries++;return proof&&p.equals(ANCHOR);}
        public boolean canTraverse(Pos a,Pos b){return canStand(a)&&canStand(b)&&Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1&&a.y()==b.y();}
        public boolean canInteract(Pos p,double reach){return forceInteraction || player().distance(p)<=reach;}
        public boolean canInteractFrom(Pos from,Pos target,double reach){return from.equals(target);}
        public MenuData menu(){return new MenuData(container?3:0,0,List.of(),cursor,container);}
        public boolean busy(){return busy;}public String pauseReason(){return fence;}
        public long submit(Action action){submissions++;throw new AssertionError("Recentring cannot perform game interactions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No native action receipt belongs to this movement");}
        public void move(Movement value){assertFalse(value.jump());assertFalse(value.sprint());assertFalse(value.sneak());assertTrue(grounded);
            if(navigation.diagnosticStatus().equals("GROUND_RECENTER"))assertEquals(distance()<=.20 ? .1f : .2f,value.inputScale());
            movement=value;inputs.add(value);}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
        Navigation.Result move(){return navigation.moveTo(TARGET,.1,context);}
        double distance(){return Math.hypot(x-ANCHOR.x()-.5,z-ANCHOR.z()-.5);}
        void advance(){double dx=0,dz=0;if(movement!=null&&movement.forward()){double yaw=Math.toRadians(movement.yaw());dx=-Math.sin(yaw)*response*movement.inputScale();dz=Math.cos(yaw)*response*movement.inputScale();}
            vx+=dx;vz+=dz;lastDx=vx;lastDz=vz;x+=vx;z+=vz;vx*=.546;vz*=.546;now++;}
        void untilInput(){for(int i=0;i<15&&inputs.isEmpty();i++){assertEquals(Navigation.Result.MOVING,move());if(inputs.isEmpty())advance();}assertNotNull(movement);}
        int finishRecenter(){for(int i=0;i<65;i++){assertEquals(Navigation.Result.MOVING,move());if(navigation.diagnosticStatus().equals("GROUND_RECENTER_COMPLETE"))return (int)now;advance();}
            fail("No bounded completion; "+navigation.failureReason()+", distance="+distance());return -1;}
    }
}
