package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DescentAdaptiveSpeedTest {
    @Test void aSmallVerifiedDipDoesNotSpendTheWholeCellAtTheOldFixedInput() {
        Fixture f=new Fixture(.375,.546);int ticks=f.run(.13,0);
        assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());
        assertTrue(ticks<=40,"Small dip model ticks="+ticks);
        assertTrue(f.maxInput>.08f,"Only available braking distance may authorize stronger ground input");
    }
    @Test void aFullBlockDescentStillBrakesAndOutperformsTheFixedCrawl() {
        Fixture f=new Fixture(1,.546);int ticks=f.run(.13,0);
        assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());
        assertTrue(ticks<=44,"Full descent model ticks="+ticks);
        assertTrue(f.maxInput>.08f);assertEquals(0,f.airInputs);
    }
    @Test void velocityDropHeightAndSlipperyGroundDoNotGrantAnOvershootException() {
        for(double drop:new double[]{.125,.375,.5,1}) for(double drag:new double[]{.546,.8918}) for(double acceleration:new double[]{.06,.13,.30}) {
            Fixture f=new Fixture(drop,drag);f.run(acceleration,0);
            if(drag>.8) {
                assertEquals(Navigation.Result.BLOCKED,f.result,f.debug());
                assertTrue(f.ground && f.y==1 && f.x>.7,"Unverified slippery descent stops on its upper support");
            } else {
                assertEquals(Navigation.Result.ARRIVED,f.result,"drop="+drop+" drag="+drag+" acceleration="+acceleration+" "+f.debug());
                assertTrue(f.x>=.5 && f.x<=.60001);
            }
            assertEquals(0,f.airInputs);assertEquals(0,f.interactions);
        }
    }
    @Test void anUnbrakedSlipperyArrivalFailsBeforeLeavingItsVerifiedPreparationArea() {
        Fixture f=new Fixture(1,.8918);f.x=1.7;f.run(.13,-.3);
        assertEquals(Navigation.Result.BLOCKED,f.result);assertEquals(0,f.airInputs);assertEquals(0,f.interactions);
        assertNull(f.movement);
    }
    @Test void unknownOrSlowGravityUsesTheUnlimitedPassiveCoastBound() {
        for(double gravity:new double[]{.08,.01,.001}) {
            Fixture f=new Fixture(1,.546);f.standardPhysics=false;f.gravity=gravity;f.run(.13,0);
            assertEquals(Navigation.Result.ARRIVED,f.result,"Unknown gravity="+gravity+" "+f.debug());
            assertTrue(f.x>=.5 && f.x<=.60001);assertEquals(0,f.airInputs);assertEquals(0,f.interactions);
        }
    }
    @Test void aMidFlightPhysicsChangeStopsInsteadOfRetainingTheShortFallAssumption() {
        Fixture f=new Fixture(1,.546);
        f.beforeTick=()->{if(!f.ground) {f.standardPhysics=false;f.gravity=.001;}};
        f.run(.13,0);
        assertFalse(f.ground,"The new condition is observed during flight, not fabricated after landing");
        assertEquals(Navigation.Result.BLOCKED,f.result);
        assertTrue(f.controller.airborne());assertNull(f.movement);
        assertEquals(0,f.airInputs);assertEquals(0,f.interactions);
    }
    private static final class Fixture implements WorldAccess,ActionPort {
        final Pos top=new Pos(1,1,0),low;
        final double drop,drag;final DescentController controller;
        final Profile profile=new Profile();final Context context=new Context(this,this,new LocalNavigator(),profile);
        long now;double x=1.5,y=1,vx,vy,gravity=.08;boolean ground=true,standardPhysics=true;Movement movement;float maxInput;int airInputs,interactions;
        Navigation.Result result=Navigation.Result.MOVING;
        Runnable beforeTick=()->{};
        final Deque<String> trail=new ArrayDeque<>();
        String debug(){return controller.failureReason()+" "+trail;}
        Fixture(double drop,double drag) { this.drop=drop;this.drag=drag;low=new Pos(0,drop==1?0:1,0);controller=new DescentController(top,low,this); }
        int run(double acceleration,double initialVelocity) {
            vx=initialVelocity;int ticks=0;
            while(result==Navigation.Result.MOVING && ticks++<220) {
                beforeTick.run();
                result=controller.tick(context);
                trail.add(now+":"+controller.phase()+" x="+x+" v="+vx+" input="+(movement==null?0:movement.inputScale()));if(trail.size()>8)trail.remove();
                if(!ground && movement!=null) airInputs++;
                if(result!=Navigation.Result.MOVING) break;
                boolean wasGround=ground;
                if(movement!=null && movement.forward()) vx-=Math.sin(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
                x+=vx;double floor=x+.3>=1 ? 1 : 1-drop;
                if(y+vy<=floor) {y=floor;vy=0;ground=true;} else {y+=vy;ground=false;}
                vy=(vy-gravity)*.98;vx*=wasGround?drag:.91;now++;
            }
            return ticks;
        }
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,.5,0,0,ground,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return p.equals(top)||p.equals(low);}
        public double standingY(Pos p){return p.equals(top)?1:p.equals(low)?1-drop:Double.NaN;}
        public boolean canTraverse(Pos a,Pos b){return a.equals(top)&&b.equals(low);}
        public boolean standardDescentPhysics(){return standardPhysics;}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action action){interactions++;throw new AssertionError("No descent interactions");}
        public ActionOutcome outcome(long id){throw new AssertionError();}
        public void move(Movement intent){assertFalse(intent.jump());assertFalse(intent.sprint());assertFalse(intent.sneak());movement=intent;maxInput=Math.max(maxInput,intent.inputScale());}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
