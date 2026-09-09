package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native-order walking/collision observations; not a live-game timing claim. */
class FractionalDescentTest {
    @Test void aOneCellOneAndOneSixteenthDropHasAnOrdinaryTerrainPathAndDescent() {
        Fixture f=new Fixture(1.0625,1,false);
        assertTrue(DescentController.descending(f.top,f.low,f));
        TerrainPathSearch search=f.search();
        for(int tick=0;tick<20 && search.status()==TerrainPathSearch.Status.SEARCHING;tick++)
            search.advance(f,128,32,Long.MAX_VALUE);
        assertEquals(TerrainPathSearch.Status.FOUND,search.status());assertEquals(List.of(f.top,f.low),search.path());
        assertEquals(0,f.interactions);assertNull(f.movement);
    }

    @Test void largerActualDropsAndTwoCellEdgesCannotUseTheNewAllowance() {
        for(Fixture f:List.of(new Fixture(1.125,1,false),new Fixture(2,1,false),new Fixture(1.0625,2,false))) {
            assertFalse(DescentController.descending(f.top,f.low,f));
            assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);
            TerrainPathSearch search=f.search();
            for(int tick=0;tick<20 && search.status()==TerrainPathSearch.Status.SEARCHING;tick++)
                search.advance(f,128,32,Long.MAX_VALUE);
            assertEquals(TerrainPathSearch.Status.NO_PATH,search.status());assertEquals(0,f.interactions);
        }
    }

    @Test void cropLandingStillRejectsTheFractionalDropBeforeAnyMovement() {
        Fixture f=new Fixture(1.0625,1,true);
        assertFalse(f.canTraverse(f.top,f.low));
        assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);assertEquals(0,f.interactions);
    }

    @Test void fractionalLandingRequiresActualGroundAQuietStopAndNoAirborneInput() {
        for(double acceleration:new double[]{.06,.13,.30}) {
            Fixture f=new Fixture(1.0625,1,false);f.run(acceleration);
            assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());assertEquals(DescentController.Phase.COMPLETE,f.controller.phase());
            assertTrue(f.ground);assertEquals(.9375,f.y,1e-9);assertEquals(f.low,NavigationFeet.resolve(f,f.player()));
            assertTrue(f.x>=.5 && f.x<=.60001,f.debug());assertTrue(Math.abs(f.vx)<=.002,f.debug());
            assertTrue(f.airSamples>0,"the actual fractional-height fall must be simulated");
            assertEquals(0,f.airInputs);assertEquals(0,f.interactions);assertNull(f.movement);assertFalse(f.controller.flowing());
            double arrival=f.x;
            for(int tick=0;tick<20;tick++) {f.physics(acceleration);assertTrue(f.ground);assertEquals(.9375,f.y,1e-9);}
            assertTrue(Math.abs(f.x-arrival)<.01,"a finite endpoint must remain supported after arrival");
        }
    }

    @Test void fractionalGeometryCannotBorrowAnIntegerStairsHalfTreadBound() {
        Fixture ordinary=new Fixture(1.0625,1,false),claimedStair=new Fixture(1.0625,1,false);
        claimedStair.claimedStair=true;ordinary.run(.13);claimedStair.run(.13);
        assertEquals(Navigation.Result.ARRIVED,ordinary.result,ordinary.debug());assertEquals(ordinary.result,claimedStair.result,claimedStair.debug());
        assertEquals(ordinary.now,claimedStair.now);assertEquals(ordinary.inputs,claimedStair.inputs);
        assertFalse(claimedStair.controller.flowing());assertEquals(0,claimedStair.stairProofCalls,
            "the exact one-block height check must reject the half-tread shortcut before consulting its opt-in");
    }

    @Test void ordinaryFallHorizonUsesTheActualFractionalHeightRatherThanGridDelta() throws ReflectiveOperationException {
        var method=DescentController.class.getDeclaredMethod("fallCoastFactor",double.class,boolean.class);method.setAccessible(true);
        double full=(double)method.invoke(null,1.0,true),fractional=(double)method.invoke(null,1.0625,true);
        assertTrue(fractional>full,"1.0625 plus the retained landing margin needs another conservative native fall tick");
        assertEquals(1/(1-.91),(double)method.invoke(null,1.0625,false),1e-9,
            "unknown gravity keeps the full passive-coast series instead of the standard finite fall bound");
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Pos top=new Pos(1,2,0),low;
        final double drop;final boolean farmland;final Profile profile=new Profile();
        final LocalNavigator navigator=new LocalNavigator();final Context context=new Context(this,this,navigator,profile);
        final DescentController controller;final List<Float> inputs=new ArrayList<>();
        long now;double x=1.5,y=2,vx,vy;boolean ground=true,claimedStair;Movement movement;
        int airSamples,airInputs,interactions,stairProofCalls;Navigation.Result result=Navigation.Result.MOVING;
        Fixture(double drop,int gridDrop,boolean farmland) {
            this.drop=drop;this.farmland=farmland;low=new Pos(0,2-gridDrop,0);controller=new DescentController(top,low,this);
        }
        TerrainPathSearch search() {
            return new TerrainPathSearch(top,low,.1,this,profile,new TravelDomain(profile,top,low),Set.of(),Set.of(),false,
                TerrainPathSearch.Goal.POSITION,List.of(),false,false);
        }
        void run(double acceleration) {
            while(result==Navigation.Result.MOVING && now<220) {
                result=controller.tick(context);
                if(!ground) {airSamples++;if(movement!=null)airInputs++;}
                if(result==Navigation.Result.MOVING)physics(acceleration);
            }
        }
        void physics(double acceleration) {
            boolean wasGround=ground;
            if(movement!=null && movement.forward())vx-=Math.sin(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
            // Y collision samples the old horizontal 0.6-wide body. Both finite
            // platforms have native observed tops; no waypoint snaps Y or speed.
            double floor=x+.3>1 && x-.3<2?2:x+.3>0 && x-.3<1?2-drop:Double.NEGATIVE_INFINITY;
            if(y+vy<=floor) {y=floor;vy=0;ground=true;} else {y+=vy;ground=false;}
            x+=vx;vy=(vy-.08)*.98;vx*=wasGround?.546:.91;now++;
        }
        String debug(){return "tick="+now+" x="+x+" y="+y+" vx="+vx+" phase="+controller.phase()+" failure="+controller.failureReason();}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,.5,0,0,ground,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return p.equals(top)||p.equals(low);}
        public double standingY(Pos p){return p.equals(top)?2:p.equals(low)?2-drop:Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return from.equals(top)&&to.equals(low)&&Math.abs(from.y()-to.y())<=1
            &&WalkingSurfaceRules.canStep(standingY(from),standingY(to),.6,farmland);}
        public boolean standardDescentPhysics(){return true;}
        public boolean straightDescentStair(Pos from,Pos to,Profile ignored){stairProofCalls++;return claimedStair;}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int horizontal,int vertical){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action action){interactions++;throw new AssertionError("No descent interaction");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No descent action acknowledgement");}
        public void move(Movement intent){assertTrue(ground,"never introduce airborne input");assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());movement=intent;inputs.add(intent.inputScale());}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
