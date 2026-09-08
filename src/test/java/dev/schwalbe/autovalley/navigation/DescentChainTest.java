package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DescentChainTest {
    @Test void sixStraightStepsKeepTheirFinitePathAndEachActualLandingWithoutSixFullStops() {
        Fixture flow=new Fixture(6);flow.run();
        Fixture stopped=new Fixture(6);stopped.flowProof=false;stopped.run();
        assertEquals(Navigation.Result.ARRIVED,flow.result,flow.debug());
        assertEquals(Navigation.Result.ARRIVED,stopped.result,stopped.debug());
        assertEquals(6,flow.actualLandings.size());
        assertEquals(List.of(5,4,3,2,1,0),flow.actualLandings);
        assertEquals(1,flow.preparations);assertEquals(1,flow.fullLandings);
        assertEquals(6,stopped.preparations);assertEquals(6,stopped.fullLandings);
        assertTrue(flow.now<stopped.now,"Flow="+flow.now+" stopped="+stopped.now);
        assertEquals(0,flow.airInputs);assertEquals(0,flow.submissions);
        assertTrue(flow.maxPreview<=4);assertTrue(flow.proofStarts.size()>3,"Preview rolls forward, never authorizes a whole unbounded staircase");
        assertTrue(flow.x>=.5 && flow.x<=.60001,"The final landing still settles before its center boundary");
    }

    @Test void nativeProofCannotReplaceTheGroundedObservationOfAnIntermediateLanding() {
        Fixture f=new Fixture(3);DescentController controller=f.controller();
        f.prepare(controller);
        f.x=1.6;f.y=1.8;f.ground=false;f.now++;
        assertEquals(Navigation.Result.BLOCKED,controller.tick(f.context,f.path));
        assertEquals(0,controller.completedEdges());assertNull(f.movement);
    }

    @Test void proofLossDuringFlightSettlesTheCurrentStepWithoutBorrowingTheNextEdge() {
        Fixture f=new Fixture(3);DescentController controller=f.controller();
        Navigation.Result result=Navigation.Result.MOVING;boolean sawFlight=false;
        for(int i=0;i<200 && result==Navigation.Result.MOVING;i++) {
            if(!f.ground){sawFlight=true;f.flowProof=false;}
            result=controller.tick(f.context,f.path);
            if(!f.ground)assertNull(f.movement);
            if(result==Navigation.Result.MOVING)f.physics();
        }
        assertTrue(sawFlight);assertEquals(Navigation.Result.ARRIVED,result,controller.failureReason());
        assertEquals(0,controller.completedEdges());assertEquals(2,f.y);assertNull(f.movement);
    }

    @Test void aTurnOrAnUnloadedFutureStepKeepsTheNormalCurrentLanding() {
        for(boolean turn:new boolean[]{false,true}) {
            Fixture f=new Fixture(3);DescentController controller=f.controller();
            List<Pos> preview=turn?List.of(f.path.get(0),f.path.get(1),new Pos(2,1,1)):f.path;
            if(!turn)f.unloaded=f.path.get(2);
            Navigation.Result result=Navigation.Result.MOVING;
            for(int i=0;i<200 && result==Navigation.Result.MOVING;i++) {
                result=controller.tick(f.context,preview);
                if(result==Navigation.Result.MOVING)f.physics();
            }
            assertEquals(Navigation.Result.ARRIVED,result,controller.failureReason());assertEquals(0,controller.completedEdges());
        }
    }

    @Test void aDoorClosingBeyondTheActiveEdgeCannotBeUsedAsFlowPermission() {
        Fixture f=new Fixture(3);DescentController controller=f.controller();
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<200 && result==Navigation.Result.MOVING;i++) {
            if(!f.ground)f.door=f.path.get(2);
            result=controller.tick(f.context,f.path);
            if(result==Navigation.Result.MOVING)f.physics();
        }
        assertEquals(Navigation.Result.ARRIVED,result,controller.failureReason());
        assertEquals(0,controller.completedEdges());assertEquals(0,f.submissions);
    }

    @Test void resetAfterAFlowHandoffStillSilencesAndFencesTheNextAirborneEdge() {
        Fixture f=new Fixture(4);
        for(int i=0;i<200;i++) {
            assertEquals(Navigation.Result.MOVING,f.nav.moveToPosition(f.goal,.1,f.context));
            if(f.actualLandings.size()>=1 && !f.ground)break;
            f.physics();
        }
        assertFalse(f.ground);assertTrue(f.actualLandings.size()>=1);
        f.nav.reset();assertNull(f.movement);f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.nav.moveToPosition(f.goal,.1,f.context));
        assertFalse(f.nav.retryableFailure());assertNull(f.movement);
    }

    @Test void allCardinalDirectionsAndMeasuredInputResponsesStillObserveEveryLanding() {
        for(int[] axis:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(double acceleration:new double[]{.06,.13,.30}) {
            Fixture f=new Fixture(6,axis[0],axis[1]);f.acceleration=acceleration;f.run();
            assertEquals(Navigation.Result.ARRIVED,f.result,f.debug()+" acceleration="+acceleration);
            assertEquals(List.of(5,4,3,2,1,0),f.actualLandings);
            assertEquals(1,f.fullLandings);assertEquals(0,f.airInputs);assertEquals(0,f.submissions);
        }
    }

    @Test void oneObservedLandingCannotBeCountedTwiceBySameTickPolling() {
        Fixture f=new Fixture(4);DescentController controller=f.controller();
        for(int i=0;i<150 && controller.completedEdges()==0;i++) {
            assertEquals(Navigation.Result.MOVING,controller.tick(f.context,f.path.subList(0,4)));
            if(controller.completedEdges()==0)f.physics();
        }
        assertEquals(1,controller.completedEdges());assertTrue(f.ground);
        Movement issued=f.movement;
        for(int i=0;i<10;i++)assertEquals(Navigation.Result.MOVING,controller.tick(f.context,f.path.subList(1,5)));
        assertEquals(1,controller.completedEdges());assertSame(issued,f.movement);
    }

    @Test void aVanishedActiveLandingFailsInsteadOfSkippingToTheNextKnownFloor() {
        Fixture f=new Fixture(3);DescentController controller=f.controller();
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<150 && result==Navigation.Result.MOVING;i++) {
            if(!f.ground)f.removed=f.path.get(1);
            result=controller.tick(f.context,f.path);
            if(result==Navigation.Result.MOVING)f.physics();
        }
        assertEquals(Navigation.Result.BLOCKED,result);assertTrue(controller.airborne());
        assertEquals(0,controller.completedEdges());assertNull(f.movement);
    }

    @Test void aMissingObservationAtLandingRequiresTheNormalStopInsteadOfGuessingItsSpeed() {
        Fixture f=new Fixture(3);DescentController controller=f.controller();
        for(int i=0;i<150 && f.actualLandings.isEmpty();i++) {
            assertEquals(Navigation.Result.MOVING,controller.tick(f.context,f.path));f.physics();
        }
        assertEquals(List.of(2),f.actualLandings);assertTrue(f.ground);f.now++;
        assertEquals(Navigation.Result.MOVING,controller.tick(f.context,f.path));
        assertEquals(DescentController.Phase.LAND,controller.phase());
        assertEquals(0,controller.completedEdges());assertNull(f.movement);
    }

    @Test void bottomStraightStairHalfTreadsNeverCountAsTheNextPlannedLanding() {
        for(int[] axis:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(double acceleration:new double[]{.06,.13,.30}) {
            Fixture f=new Fixture(6,axis[0],axis[1]);f.halfTreads=true;f.acceleration=acceleration;
            int halfTreadSamples=0;
            while(f.result==Navigation.Result.MOVING && f.now<1000) {
                boolean middle=f.ground && Math.abs(f.y-Math.floor(f.y)-.5)<.00001;
                f.result=f.nav.moveToPosition(f.goal,.1,f.context);
                if(middle) {
                    halfTreadSamples++;
                    assertEquals(Navigation.Result.MOVING,f.result,f.debug());
                    int completed=f.height-(int)Math.ceil(f.y);
                    assertEquals(completed,((Number)f.nav.diagnostics().get("descentHandoffs")).intValue(),"A half-height tread is not the next path-cell top");
                    assertEquals(completed+1,((Number)f.nav.diagnostics().get("nextIndex")).intValue());
                    assertEquals("DESCENT_DESCEND",f.nav.diagnosticStatus());
                }
                if(!f.ground)assertNull(f.movement,"Each of the two half-falls has no airborne input");
                if(f.result==Navigation.Result.MOVING)f.physics();
            }
            assertEquals(Navigation.Result.ARRIVED,f.result,f.debug()+" acceleration="+acceleration);
            assertTrue(halfTreadSamples>=6,"All six intermediate supports are actually observed");
            assertEquals(List.of(5.5,5.0,4.5,4.0,3.5,3.0,2.5,2.0,1.5,1.0,.5,0.0),f.physicalLandings);
            assertEquals(List.of(5,4,3,2,1,0),f.actualLandings);assertEquals(0,f.submissions);
            assertTrue(f.x>=.5 && f.x<=.60001);
        }
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final int height,axisX,axisZ;final List<Pos> path;final Pos goal;final LocalNavigator nav=new LocalNavigator();
        final Profile profile=new Profile();final Context context=new Context(this,this,nav,profile);
        final List<Integer> actualLandings=new ArrayList<>();final List<Double> physicalLandings=new ArrayList<>();final Set<Pos> proofStarts=new HashSet<>();
        long now;double x,y,vx,vy,acceleration=.13;boolean ground=true,flowProof=true,halfTreads;
        Pos unloaded,door,removed;Movement movement;int maxPreview,airInputs,submissions,preparations,fullLandings;
        String previousPhase="";Navigation.Result result=Navigation.Result.MOVING;
        Fixture(int height){this(height,1,0);}
        Fixture(int height,int axisX,int axisZ){this.height=height;this.axisX=axisX;this.axisZ=axisZ;x=height+.5;y=height;var steps=new ArrayList<Pos>();for(int i=height;i>=0;i--)steps.add(new Pos(i*axisX,i,i*axisZ));path=List.copyOf(steps);goal=path.get(path.size()-1);}
        DescentController controller(){return new DescentController(path.get(0),path.get(1),this);}
        void prepare(DescentController controller){for(int i=0;i<4;i++){controller.tick(context,path.subList(0,Math.min(4,path.size())));now++;}}
        String debug(){return nav.failureReason()+" tick="+now+" modelX="+x+" modelY="+y;}
        void run(){while(result==Navigation.Result.MOVING && now<1000){result=nav.moveToPosition(goal,.1,context);String phase=nav.diagnosticStatus();if(!phase.equals(previousPhase)){if(phase.equals("DESCENT_PREPARE"))preparations++;if(phase.equals("DESCENT_LAND"))fullLandings++;previousPhase=phase;}if(!ground&&movement!=null)airInputs++;if(result==Navigation.Result.MOVING)physics();}}
        void physics(){
            boolean wasGround=ground;double oldY=y;
            if(movement!=null&&movement.forward())vx+=(-Math.sin(Math.toRadians(movement.yaw()))*axisX+Math.cos(Math.toRadians(movement.yaw()))*axisZ)*acceleration*movement.inputScale();
            x+=vx;
            // Bottom/straight native stairs facing upstream: a whole bottom
            // half plus the upstream upper half. The 0.6-wide body's rear is
            // supported by each half until it clears that half's boundary.
            double support=halfTreads ? Math.floor(2*(x+.3))/2-.5 : Math.floor(x+.3);
            double floor=Math.max(0,Math.min(height,support));
            if(y+vy<=floor){y=floor;vy=0;ground=true;}else{y+=vy;ground=false;}
            if(ground&&y<oldY){physicalLandings.add(y);if(y==Math.floor(y))actualLandings.add((int)y);}
            vy=(vy-.08)*.98;vx*=wasGround?.546:.91;now++;
        }
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(axisX*(x-.5)+.5,y,axisZ*(x-.5)+.5,0,0,ground,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return !p.equals(unloaded);}public boolean canStand(Pos p){return path.contains(p)&&!p.equals(removed);}
        public double standingY(Pos p){return canStand(p)?p.y():Double.NaN;}
        public boolean canTraverse(Pos a,Pos b){return canStand(a)&&canStand(b)&&Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1;}
        public boolean standardDescentPhysics(){return true;}
        public boolean canChainDescent(List<Pos> steps,Profile p){maxPreview=Math.max(maxPreview,steps.size());proofStarts.add(steps.get(0));return flowProof;}
        public BlockData block(Pos p){return new BlockData(p,p.equals(door)?"minecraft:oak_door":"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action action){submissions++;throw new AssertionError("No interaction from descent flow");}
        public ActionOutcome outcome(long id){throw new AssertionError();}
        public void move(Movement intent){assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());movement=intent;}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
