package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DescentControlTest {
    private static final Pos TOP=new Pos(1,1,0),LOW=new Pos(0,0,0);

    @Test void aFastWestwardArrivalBrakesBeforeTheDropAndSettlesWithoutPassingTheLandingCenter() {
        for(double acceleration:new double[]{.10,.13,.20,.30}) {
            Fixture f=new Fixture();f.x=1.7;f.vx=-.30;
            Navigation.Result result=Navigation.Result.MOVING;double entrySpeed=Double.NaN;
            for(int i=0;i<180 && result==Navigation.Result.MOVING;i++) {
                result=f.controller.tick(f.context);
                assertNotEquals(Navigation.Result.BLOCKED,result,f.controller.failureReason()+" acceleration="+acceleration+" x="+f.x);
                if(!f.grounded) assertNull(f.movement,"Never accelerate or steer during the fall");
                boolean ground=f.grounded;f.physics(acceleration);
                if(ground && !f.grounded) entrySpeed=Math.abs(f.vx);
            }
            assertEquals(Navigation.Result.ARRIVED,result,"Bounded model acceleration="+acceleration);
            assertTrue(Double.isFinite(entrySpeed));assertTrue(entrySpeed<.04);
            assertTrue(f.x>=.5 && f.x<=.6,"Do not widen the original one-edge corridor to accept overshoot");
            assertTrue(f.grounded);assertEquals(0,f.submissions);
        }
    }
    @Test void merelyReleasingForwardAfterBecomingAirborneReproducesTheOldOvershoot() {
        Fixture f=new Fixture();f.x=1.7;f.vx=-.30;
        for(int i=0;i<30 && f.y>0;i++) {
            f.movement=f.grounded ? new Movement(90,0,true,false,false,false) : null;
            f.physics(.13);
        }
        assertTrue(f.grounded);assertEquals(0,f.y);assertTrue(f.x<.5,"Stopping input after the fall starts does not erase native momentum");
    }
    @Test void theHistoricalOvershootShapeStillFailsInsteadOfBecomingARecoveryPermission() {
        Fixture f=new Fixture();f.prepare();
        f.x=-.1379444073;f.y=0;f.grounded=true;f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);
    }
    @Test void sameTickPollingCannotFinishPreparationOrClearItsMovementPulse() {
        Fixture f=new Fixture();assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));
        for(int i=0;i<20;i++) assertEquals(Navigation.Result.MOVING,f.controller.tick(f.context));
        assertEquals(DescentController.Phase.PREPARE,f.controller.phase());
        f.now++;f.controller.tick(f.context);f.now++;f.controller.tick(f.context);
        assertEquals(DescentController.Phase.DESCEND,f.controller.phase());
        f.now++;f.controller.tick(f.context);Movement first=f.movement;assertNotNull(first);
        f.controller.tick(f.context);assertSame(first,f.movement);
    }
    @Test void changedGeometrySideFallsAndUnknownSupportRemainFailures() {
        for(int invalid=0;invalid<3;invalid++) {
            Fixture f=new Fixture();f.prepare();
            if(invalid==0) f.blocked=true;
            else if(invalid==1) { f.x=.9;f.y=.8;f.z=1;f.grounded=false; }
            else f.knownHeight=false;
            f.now++;assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);
        }
    }
    @Test void noObservedProgressStillTimesOutAndDoesNotJumpOrInteract() {
        Fixture f=new Fixture();f.prepare();f.now+=61;
        assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);assertEquals(0,f.submissions);
    }
    @Test void anotherPendingActionCannotBorrowTheDescentMovementPermission() {
        Fixture f=new Fixture();f.prepare();f.pending=true;f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);assertEquals(0,f.submissions);
    }
    @Test void unknownMenuStateCannotAuthorizeEvenThePreparationMovement() {
        Fixture f=new Fixture();f.unknownMenu=true;
        assertEquals(Navigation.Result.BLOCKED,f.controller.tick(f.context));assertNull(f.movement);assertEquals(0,f.submissions);
    }
    @Test void nativePauseDuringAFallRetainsTheFailurePoseAfterReset() {
        Fixture f=new Fixture();Pos target=LOW;LocalNavigator nav=(LocalNavigator)f.context.navigation();
        for(int i=0;i<5;i++) { assertEquals(Navigation.Result.MOVING,nav.moveToPosition(target,.1,f.context));f.now++; }
        f.x=.65;f.y=.8;f.grounded=false;
        assertEquals(Navigation.Result.MOVING,nav.moveToPosition(target,.1,f.context));
        nav.reset();f.now++;
        assertEquals(Navigation.Result.BLOCKED,nav.moveToPosition(target,.1,f.context));
        var last=nav.diagnostics().get("lastFailure");assertTrue(last instanceof Map);
        assertEquals(false,((Map<?,?>)((Map<?,?>)last).get("player")).get("onGround"));
        assertFalse(nav.retryableFailure());nav.reset();assertEquals(last,nav.diagnostics().get("lastFailure"));
        assertFalse(nav.permitsTransit(TOP,f.context));assertNull(f.movement);
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final Context context=new Context(this,this,new LocalNavigator(),profile);
        long now;double x=1.5,y=1,z=.5,vx,vy;boolean grounded=true,blocked,pending,unknownMenu,knownHeight=true;Movement movement;int submissions;
        final DescentController controller=new DescentController(TOP,LOW,this);
        void prepare() { for(int i=0;i<4;i++) { assertEquals(Navigation.Result.MOVING,controller.tick(context));now++; } }
        void physics(double acceleration) {
            // Small native-order observation model, not a live-game performance claim:
            // normal ground drag .6*.91, air drag .91, gravity .08*.98,
            // and a 0.6-wide body supported until its rear clears the upper ledge.
            boolean wasGrounded=grounded;
            if(movement!=null && movement.forward()) vx-=Math.sin(Math.toRadians(movement.yaw()))*acceleration*movement.inputScale();
            x+=vx;double support=x+.3>=1 ? 1 : 0;
            double nextY=y+vy;
            if(nextY<=support) { y=support;vy=0;grounded=true; }
            else { y=nextY;grounded=false; }
            vy=(vy-.08)*.98;vx*=wasGrounded?.546:.91;now++;
        }
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}
        public boolean canStand(Pos p){return p.equals(TOP)||p.equals(LOW);}
        public double standingY(Pos p){return knownHeight && canStand(p)?p.y():Double.NaN;}
        public boolean canTraverse(Pos a,Pos b){return !blocked && canStand(a)&&canStand(b) && Math.abs(a.x()-b.x())==1;}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return unknownMenu ? null : new MenuData(0,0,List.of(),ItemData.EMPTY,false);}
        public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return pending;}
        public long submit(Action action){submissions++;throw new AssertionError("No interaction while descending");}
        public ActionOutcome outcome(long ticket){throw new AssertionError();}
        public void move(Movement input){assertFalse(input.sprint());assertFalse(input.jump());assertFalse(input.sneak());movement=input;}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
