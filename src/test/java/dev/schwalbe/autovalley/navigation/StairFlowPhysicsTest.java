package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic input/collision model, not a replacement for native in-game verification. */
class StairFlowPhysicsTest {
    @Test void sixHalfTreadStepsMeasureTheOptInAgainstTheExistingController() {
        Fixture baseline=new Fixture(6,1,0,false);
        Fixture flow=new Fixture(6,1,0,true);
        baseline.run();flow.run();
        System.out.println("Stair half-tread benchmark: baseline="+baseline.descentTicks()+" ticks, flow="+flow.descentTicks()
            +" ticks, ground="+flow.groundSamples+", air="+flow.airSamples+", input="+flow.inputSamples
            +", nearGoal="+flow.nearGoalSamples+", maxSpeed="+flow.maximumSpeed);
        if(Boolean.getBoolean("autovalley.stairBenchmarkTrace"))System.out.println(flow.trail);
        assertSafeArrival(baseline);assertSafeArrival(flow);
        assertTrue(flow.flowProofCalls>0,"The new opt-in must actually be consulted");
        assertTrue(flow.descentTicks()<baseline.descentTicks()*.85,"A continuous run must improve this model by at least 15%; "+flow.debug()+" baseline="+baseline.descentTicks());
        assertTrue(flow.maximumPreview<=4,"Flow permission remains a bounded local preview");
        assertTrue(flow.proofStarts.size()>1,"The preview must roll forward during a six-step run");
    }

    @Test void groundInputAndHalfTreadCollisionRemainSafeForEveryCardinalHeadingAndResponse() {
        for(int[] axis:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(double acceleration:new double[]{.06,.13,.30}) {
            Fixture flow=new Fixture(6,axis[0],axis[1],true);flow.acceleration=acceleration;flow.run();
            assertSafeArrival(flow);
            assertTrue(flow.airSamples>0,"The model must exercise actual falling, not snap positions to waypoints");
            assertTrue(flow.halfTreadLandings>0,"The body's collision with intermediate half treads must be observed");
            assertTrue(flow.maximumSpeed<=.25,"Walking-only descent must not acquire an unchecked speed: "+flow.debug());
        }
    }

    @Test void finalLandingRemainsStoppedOnAFinitePlatformWithAnExposedEdge() {
        Fixture flow=new Fixture(8,1,0,true,false);flow.run();assertSafeArrival(flow);
        assertFalse(flow.acceptedPreviews.isEmpty(),"The preceding native stair run must exercise flow");
        assertTrue(flow.acceptedPreviews.stream().noneMatch(preview->preview.contains(flow.goal)),
            "A solid final platform cannot borrow the native bottom-stair proof");
        double arrivalX=flow.x,arrivalZ=flow.z;
        for(int tick=0;tick<30;tick++) {
            flow.physics();
            assertTrue(flow.ground,"Passive residual momentum must not carry the body over the final platform edge");
            assertEquals(0,flow.y,1e-8);
        }
        assertTrue(Math.hypot(flow.x-arrivalX,flow.z-arrivalZ)<.01,"Final arrival includes braking, not just proximity");
        assertTrue(flow.x>=.4 && flow.x<=.6);
    }

    @Test void finalNativeStairStopsBeforeItsLastLowerHalfTread() {
        Fixture flow=new Fixture(8,1,0,true,true);flow.run();assertSafeArrival(flow);
        assertTrue(flow.acceptedPreviews.stream().anyMatch(preview->preview.contains(flow.goal)),
            "The actual final stair may be included in native flow permission");
        for(int tick=0;tick<30;tick++) {
            flow.physics();
            assertTrue(flow.ground);assertEquals(0,flow.y,1e-8,"Residual motion must not fall onto the lower half at y=-0.5");
        }
        assertTrue(flow.x>=.4 && flow.x<=.6);
    }

    @Test void aTurnStartsOnlyAfterTheBottomLandingHasActuallyStopped() {
        Fixture flow=new Fixture(5,1,0,true);flow.addFlatTurn();
        for(int tick=0;tick<1000 && !flow.turnInput;tick++) {
            flow.control();
            assertNotEquals(Navigation.Result.BLOCKED,flow.result,flow.debug());
            if(flow.turnInput)break;
            flow.physics();
        }
        assertTrue(flow.turnInput,"The route must reach the turn after completing the descending run");
        assertEquals(0,flow.y,1e-8);assertTrue(flow.ground);
        assertTrue(Math.hypot(flow.vx,flow.vz)<=.002,"No diagonal turning input may borrow descent momentum");
        for(List<Pos> preview:flow.previews)assertTrue(straightDescending(preview),"A turn cannot be included in the flow proof");
        assertEquals(0,flow.airInputs);assertEquals(0,flow.submissions);
    }

    @Test void aMissingFutureSupportCannotBecomeAnAirborneShortcut() {
        Fixture flow=new Fixture(6,1,0,true);
        boolean removed=false;
        for(int tick=0;tick<1000 && flow.result==Navigation.Result.MOVING;tick++) {
            if(!removed && !flow.ground) {
                removed=true;
                // Remove the last landing from both the routing observation and
                // the actual collision geometry while the first drop is active.
                flow.removed=flow.path.get(flow.path.size()-1);
                flow.surfaces.removeIf(surface->surface.cell().equals(flow.removed));
            }
            flow.control();
            if(flow.result==Navigation.Result.MOVING)flow.physics();
        }
        assertTrue(removed);assertEquals(Navigation.Result.BLOCKED,flow.result,"A missing goal floor must end the attempt");
        assertNull(flow.movement);assertEquals(0,flow.airInputs);assertEquals(0,flow.submissions);
        assertTrue(flow.y>=0,"The controller must stop before falling through the vanished endpoint");
    }

    private static void assertSafeArrival(Fixture fixture) {
        assertEquals(Navigation.Result.ARRIVED,fixture.result,fixture.debug());
        assertTrue(fixture.ground,fixture.debug());assertEquals(0,fixture.y,1e-8,fixture.debug());
        assertTrue(fixture.player().distance(fixture.goal)<=.10001,fixture.debug());
        assertTrue(Math.hypot(fixture.vx,fixture.vz)<=.002,fixture.debug());
        assertNull(fixture.movement);assertEquals(0,fixture.airInputs);assertEquals(0,fixture.submissions);
    }

    private static boolean straightDescending(List<Pos> preview) {
        if(preview.size()<2 || preview.size()>4)return false;
        int dx=preview.get(1).x()-preview.get(0).x(),dz=preview.get(1).z()-preview.get(0).z();
        if(Math.abs(dx)+Math.abs(dz)!=1)return false;
        for(int index=1;index<preview.size();index++) {
            Pos from=preview.get(index-1),to=preview.get(index);
            if(to.x()-from.x()!=dx || to.z()-from.z()!=dz || from.y()-to.y()!=1)return false;
        }
        return true;
    }

    record Surface(Pos cell,double minX,double maxX,double minZ,double maxZ,double top) {
        boolean intersects(double x,double z) {
            return x+.3>minX+1e-9 && x-.3<maxX-1e-9 && z+.3>minZ+1e-9 && z-.3<maxZ-1e-9;
        }
    }

    static class Fixture implements WorldAccess,ActionPort {
        final LocalNavigator nav=new LocalNavigator();final Profile profile=new Profile();
        final Context context=new Context(this,this,nav,profile);
        final List<Pos> path=new ArrayList<>();final List<Surface> surfaces=new ArrayList<>();
        final Set<Pos> proofStarts=new HashSet<>();final List<List<Pos>> previews=new ArrayList<>();
        final Set<Pos> stairCells=new HashSet<>();final List<List<Pos>> acceptedPreviews=new ArrayList<>();
        final Deque<String> trail=new ArrayDeque<>();
        final int axisX,axisZ;final boolean flow;
        Pos goal,removed;long now,descentStarted=-1;double x,y,z,vx,vy,vz,acceleration=.13,maximumSpeed;
        boolean ground=true,turnInput;Movement movement;Navigation.Result result=Navigation.Result.MOVING;
        int airSamples,airInputs,groundSamples,inputSamples,nearGoalSamples,submissions,halfTreadLandings,flowProofCalls,maximumPreview;

        Fixture(int height,int axisX,int axisZ,boolean flow) {
            this(height,axisX,axisZ,flow,true);
        }
        Fixture(int height,int axisX,int axisZ,boolean flow,boolean finalStair) {
            this.axisX=axisX;this.axisZ=axisZ;this.flow=flow;
            for(int index=height;index>=0;index--) {
                Pos cell=new Pos(index*axisX,index,index*axisZ);path.add(cell);
                if(index==0 && !finalStair)surfaces.add(new Surface(cell,cell.x(),cell.x()+1,cell.z(),cell.z()+1,0));
                else {
                    stairCells.add(cell);
                    // Bottom straight stair: a full lower half, plus the upper
                    // half on the upstream side. The 0.6-wide body overlaps the
                    // previous support until its trailing face clears the edge.
                    surfaces.add(new Surface(cell,cell.x(),cell.x()+1,cell.z(),cell.z()+1,index-.5));
                    surfaces.add(new Surface(cell,cell.x()+(axisX>0?.5:0),cell.x()+1-(axisX<0?.5:0),
                        cell.z()+(axisZ>0?.5:0),cell.z()+1-(axisZ<0?.5:0),index));
                }
            }
            goal=path.get(path.size()-1);Pos start=path.get(0);x=start.x()+.5;y=height;z=start.z()+.5;
        }

        void addFlatTurn() {
            goal=new Pos(0,0,2);
            for(int index=1;index<=2;index++) {
                Pos floor=new Pos(0,0,index);path.add(floor);surfaces.add(new Surface(floor,0,1,index,index+1,0));
            }
        }
        void run() {while(result==Navigation.Result.MOVING && now<1500) {control();if(result==Navigation.Result.MOVING)physics();}}
        long descentTicks() {return now-descentStarted;}
        void control() {
            result=nav.moveToPosition(goal,.1,context);
            // Exclude an A* scheduling slice on a cold JVM: the physical input
            // benchmark starts when the actual descent controller is entered.
            if(descentStarted<0 && nav.diagnosticStatus().startsWith("DESCENT_"))descentStarted=now;
            trail.add(String.format(Locale.ROOT,"%d x=%.3f y=%.3f z=%.3f ground=%s v=%.3f input=%.3f edge=%s",
                now,x,y,z,ground,Math.hypot(vx,vz),movement==null?0:movement.inputScale(),nav.diagnostics().get("descentHandoffs")));
            if(trail.size()>14)trail.removeFirst();
            if(ground)groundSamples++;
            if(movement!=null && movement.inputScale()>0)inputSamples++;
            if(player().distance(goal)<=.25)nearGoalSamples++;
            if(!ground) {airSamples++;if(movement!=null && movement.inputScale()>0)airInputs++;}
            assertEquals(0,airInputs,"No airborne acceleration is allowed: "+debug());
        }
        void physics() {
            boolean wasGround=ground;double oldY=y;
            if(movement!=null && movement.forward()) {
                double response=wasGround?acceleration:.02;
                vx-=Math.sin(Math.toRadians(movement.yaw()))*response*movement.inputScale();
                vz+=Math.cos(Math.toRadians(movement.yaw()))*response*movement.inputScale();
            }
            // Vertical collision uses the OLD horizontal AABB, as Y-first native
            // movement does. A departing tread therefore keeps onGround for one
            // observed tick. No waypoint sets position, velocity, or ground state.
            double floor=Double.NEGATIVE_INFINITY;
            for(Surface surface:surfaces)if(surface.top()<=y+1e-8 && surface.intersects(x,z))floor=Math.max(floor,surface.top());
            if(y+vy<=floor) {y=floor;vy=0;ground=true;} else {y+=vy;ground=false;}
            x+=vx;z+=vz;
            if(ground && y<oldY && Math.abs(y-Math.floor(y)-.5)<1e-8)halfTreadLandings++;
            maximumSpeed=Math.max(maximumSpeed,Math.hypot(vx,vz));
            vy=(vy-.08)*.98;vx*=wasGround?.546:.91;vz*=wasGround?.546:.91;now++;
        }
        String debug() {return "ticks="+now+" xyz="+x+","+y+","+z+" speed="+Math.hypot(vx,vz)+" phase="+nav.diagnosticStatus()+" reason="+nav.failureReason()+" trail="+trail;}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,ground,false,20,20,0,true,true);}
        public boolean loaded(Pos pos){return true;}
        public boolean canStand(Pos pos){return path.contains(pos) && !pos.equals(removed);}
        public double standingY(Pos pos){return canStand(pos)?pos.y():Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return canStand(from)&&canStand(to)&&Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1&&Math.abs(from.y()-to.y())<=1;}
        public boolean standardDescentPhysics(){return true;}
        public boolean straightDescentStair(Pos from,Pos to,Profile ignored){return canTraverse(from,to)&&from.y()-to.y()==1&&stairCells.contains(from)&&stairCells.contains(to);}
        public boolean canChainDescent(List<Pos> preview,Profile ignored){return straightDescending(preview)&&preview.stream().allMatch(this::canStand);}
        // Intentionally no @Override until the production opt-in lands; the
        // fixture also compiles against the unchanged baseline WorldAccess API.
        public boolean canFlowDescent(List<Pos> preview,Profile ignored) {
            flowProofCalls++;maximumPreview=Math.max(maximumPreview,preview.size());
            proofStarts.add(preview.get(0));previews.add(List.copyOf(preview));
            boolean accepted=flow&&straightDescending(preview)&&preview.stream().allMatch(this::canStand)
                &&preview.stream().allMatch(stairCells::contains);
            if(accepted)acceptedPreviews.add(List.copyOf(preview));
            return accepted;
        }
        public BlockData block(Pos pos){return new BlockData(pos,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos center,int horizontal,int vertical){return List.of();}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}
        public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return false;}
        public long submit(Action action){submissions++;throw new AssertionError("A stair benchmark cannot interact");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No action receipt is needed");}
        public void move(Movement intent) {
            assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());
            if(goal.z()==2 && intent.forward() && Math.cos(Math.toRadians(intent.yaw()))>.5)turnInput=true;
            movement=intent;
        }
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
