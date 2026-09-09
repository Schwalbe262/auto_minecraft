package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public navigation regression for displacement during descent, not a cleared private latch. */
class InterruptedDescentRecoveryTest {
    static final Pos TOP=new Pos(0,2,1),STAIR=new Pos(0,1,0),TARGET=new Pos(0,1,-2);

    @Test void interruptedHalfTreadRecoversBeforeAnyPathOrInteractionAndRetiresLatchOnlyAtQuietCenter() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();
        assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
        f.proof=true;assertEquals(Navigation.Result.MOVING,f.move());
        assertEquals("STAIR_RECENTER",f.nav.diagnosticStatus());
        assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
        assertEquals(0,f.nav.diagnostics().get("pathLength"));assertNull(f.input);
        f.forceInteraction=true;
        for(int i=0;i<2;i++){f.now++;assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.input);}
        f.now++;assertEquals(Navigation.Result.MOVING,f.move());assertNotNull(f.input);
        assertEquals(0,f.submissions);assertFalse(f.nav.canYieldTravel(f.context));
    }

    @Test void aFreshRunAtTheSameHalfTreadAlsoRequiresActualRecoveryBeforeAStar() {
        Fixture f=new Fixture();f.halfTread();f.proof=true;
        assertEquals(Navigation.Result.MOVING,f.move());assertEquals("STAIR_RECENTER",f.nav.diagnosticStatus());
        assertEquals(0,f.nav.diagnostics().get("requestExpanded"));assertNull(f.input);
        assertEquals(Boolean.FALSE,f.nav.diagnostics().get("interruptedDescent"));
    }

    @Test void completedObservedRecoveryClearsInterruptedLatchAndPlansFromActualCell() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();f.proof=true;
        for(int i=0;i<4;i++){assertEquals(Navigation.Result.MOVING,f.move());f.now++;}
        // Small observed horizontal steps, with one normal native half-step.
        for(int i=1;i<=6;i++) {
            f.x=.7-(.2*i/6);f.z=.05+(.45*i/6);f.y=i>=3?1:.5;
            assertEquals(Navigation.Result.MOVING,f.move());f.now++;
            assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
        }
        for(int i=0;i<5&&!f.nav.diagnosticStatus().equals("STAIR_RECENTER_COMPLETE");i++) {
            assertEquals(Navigation.Result.MOVING,f.move());f.now++;
        }
        assertEquals("STAIR_RECENTER_COMPLETE",f.nav.diagnosticStatus());
        assertEquals(Boolean.FALSE,f.nav.diagnostics().get("interruptedDescent"));assertNull(f.input);
        assertEquals(0,f.nav.diagnostics().get("requestExpanded"),"No fictional A* origin before actual recovery");
        for(int i=0;i<20&&((Number)f.nav.diagnostics().get("requestExpanded")).intValue()==0;i++) {
            assertEquals(Navigation.Result.MOVING,f.move());f.now++;
        }
        assertTrue(((Number)f.nav.diagnostics().get("requestExpanded")).intValue()>0);assertEquals(0,f.submissions);
    }

    @Test void missingNativeProofCannotTurnHalfTreadIntoAConfirmedLanding() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();
        for(int i=0;i<4;i++) {
            assertEquals(Navigation.Result.BLOCKED,f.move());assertNull(f.input);f.now++;
            assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
        }
        Fixture fresh=new Fixture();fresh.halfTread();
        assertEquals(Navigation.Result.BLOCKED,fresh.move());assertNull(fresh.input);
        assertEquals(0,fresh.nav.diagnostics().get("requestExpanded"));
    }

    @Test void activeReceiptFenceAirborneOrHarvestLookaheadCannotBorrowRecovery() {
        for(String guard:List.of("fence","busy","cursor","airborne","lookahead")) {
            Fixture f=new Fixture();f.interruptDescent();f.halfTread();f.proof=true;
            switch(guard) {
                case "fence"->f.fence="unconfirmed native action";
                case "busy"->f.busy=true;
                case "cursor"->f.cursor=new ItemData("minecraft:stone",1,0,null,false,Integer.MAX_VALUE);
                case "airborne"->f.grounded=false;
                case "lookahead"->{}
                default->throw new AssertionError();
            }
            assertEquals(Navigation.Result.BLOCKED,guard.equals("lookahead")
                ?f.nav.moveToWithoutInteraction(TARGET,.1,f.context):f.move(),guard);
            assertNull(f.input,guard);assertEquals(0,f.submissions);
            assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
        }
    }

    @Test void resetOrDestinationChangeCannotReusePriorMovementOrQuietSamples() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();f.proof=true;
        for(int i=0;i<4;i++){assertEquals(Navigation.Result.MOVING,f.move());f.now++;}
        assertNotNull(f.input);f.nav.reset();assertNull(f.input);
        assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
        assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.input);
        assertEquals(Navigation.Result.MOVING,f.nav.moveTo(TARGET.offset(1,0,0),.1,f.context));assertNull(f.input);
        assertEquals(0,f.submissions);
    }

    @Test void repeatedDisplacementDuringRecoveryGetsFreshProofAndQuietSamplesButHasABoundedRetryBudget() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();f.proof=true;
        for(int attempt=1;attempt<=3;attempt++) {
            for(int i=0;i<4;i++){assertEquals(Navigation.Result.MOVING,f.move());f.now++;}
            assertNotNull(f.input);assertEquals(attempt,f.nav.diagnostics().get("stairRecoveryAttempts"));
            f.proof=false;
            assertEquals(attempt<3?Navigation.Result.MOVING:Navigation.Result.BLOCKED,f.move());assertNull(f.input);
            assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
            if(attempt==3)break;
            f.proof=true;
            for(int i=0;i<10;i++) {
                f.now++;assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.input);
                if(i<9)assertEquals(attempt,f.nav.diagnostics().get("stairRecoveryAttempts"));
            }
            assertEquals(attempt+1,f.nav.diagnostics().get("stairRecoveryAttempts"));
        }
        f.proof=true;f.now++;assertEquals(Navigation.Result.BLOCKED,f.move());assertNull(f.input);
        assertEquals(3,f.nav.diagnostics().get("stairRecoveryAttempts"));assertEquals(0,f.submissions);
    }

    @Test void aNativeFenceAppearingDuringAlignmentSurvivesStoppedRetryAndBlocksAllFurtherInput() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();f.proof=true;
        for(int i=0;i<4;i++){assertEquals(Navigation.Result.MOVING,f.move());f.now++;}
        assertNotNull(f.input);f.fence="preserve this unconfirmed receipt";
        assertEquals(Navigation.Result.MOVING,f.move());assertNull(f.input);
        f.now+=10;assertEquals(Navigation.Result.BLOCKED,f.move());assertNull(f.input);
        assertEquals("preserve this unconfirmed receipt",f.fence);assertEquals(0,f.submissions);
        assertEquals(Boolean.TRUE,f.nav.diagnostics().get("interruptedDescent"));
    }

    @Test void independentlyProvenFlatOverlapCanRecoverAnInterruptedGridOriginBeforeTheOldLatch() {
        Fixture f=new Fixture();f.interruptDescent();f.halfTread();f.x=.75;
        f.flat=new Pos(1,1,0);f.cells.add(f.flat);f.heights.put(f.flat,.5);
        for(int i=0;i<4;i++){assertEquals(Navigation.Result.MOVING,f.move());f.now++;}
        assertEquals("GROUND_RECENTER",f.nav.diagnosticStatus());assertNotNull(f.input);
        for(int i=1;i<=8;i++) {
            f.x=.75+.75*i/8;f.z=.05+.45*i/8;
            assertEquals(Navigation.Result.MOVING,f.move());f.now++;
        }
        for(int i=0;i<5&&!f.nav.diagnosticStatus().equals("GROUND_RECENTER_COMPLETE");i++) {
            assertEquals(Navigation.Result.MOVING,f.move());f.now++;
        }
        assertEquals("GROUND_RECENTER_COMPLETE",f.nav.diagnosticStatus());assertNull(f.input);
        assertEquals(Boolean.FALSE,f.nav.diagnostics().get("interruptedDescent"));assertEquals(0,f.submissions);
    }

    static final class Fixture implements WorldAccess,ActionPort {
        final LocalNavigator nav=new LocalNavigator();final Profile profile=new Profile();
        final Context context=new Context(this,this,nav,profile,new SessionState());
        final Set<Pos> cells=new HashSet<>(Set.of(TOP,STAIR,STAIR.offset(0,0,-1),TARGET));
        final Map<Pos,Double> heights=new HashMap<>();Pos flat;
        long now;double x=.5,y=2,z=1.5;boolean grounded=true,proof,forceInteraction,busy;
        String fence;ItemData cursor=ItemData.EMPTY;Movement input;int submissions;
        Fixture(){profile.navigationMode=NavigationMode.TERRAIN;}
        Navigation.Result move(){return nav.moveTo(TARGET,.1,context);}
        void interruptDescent() {
            for(int i=0;i<15&&input==null;i++){assertEquals(Navigation.Result.MOVING,move());now++;}
            assertNotNull(input,"Actually enter the public descent controller first");
            x=1.2;y=1.8;z=.7;grounded=false;now++;
            assertEquals(Navigation.Result.BLOCKED,move());assertNull(input);
            assertEquals(Boolean.TRUE,nav.diagnostics().get("interruptedDescent"));
        }
        void halfTread(){x=.7;y=.5;z=.05;grounded=true;now++;}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return cells.contains(p);}
        public double standingY(Pos p){return canStand(p)?heights.getOrDefault(p,(double)p.y()):Double.NaN;}
        public boolean fullFlatSupport(Pos p){return p.equals(flat);}
        public boolean canRecenterOnSupport(Pos p){return p.equals(flat);}
        public boolean standardDescentPhysics(){return true;}
        public boolean canRecenterOnStair(Pos p){return proof&&p.equals(STAIR);}
        public boolean canTraverse(Pos a,Pos b){return canStand(a)&&canStand(b)&&Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1&&Math.abs(a.y()-b.y())<=1;}
        public boolean canInteract(Pos p,double r){return forceInteraction;}
        public boolean canInteractFrom(Pos a,Pos b,double r){return a.equals(b);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),cursor,false);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return busy;}public String pauseReason(){return fence;}
        public long submit(Action a){submissions++;throw new AssertionError("Recovery is movement only");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No action receipt is borrowed");}
        public void move(Movement m){assertFalse(m.jump());assertFalse(m.sprint());assertFalse(m.sneak());input=m;}
        public void stopMovement(){input=null;}public void cancel(){stopMovement();}
    }
}
