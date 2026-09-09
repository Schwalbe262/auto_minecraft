package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StairFlowReentryTest {
    @Test void anObstructedHighEnvelopeDoesNotHideTheIndependentlySafeLowerStairs() {
        GapFixture f=new GapFixture(true),ordinary=new GapFixture(false);f.run();ordinary.run();
        System.out.println("Native-envelope gap benchmark: "+f.descentTicks()+" ticks; lower proofs="+f.lowerAccepted);
        assertSafe(f);assertSafe(ordinary);
        assertTrue(f.descentTicks()<ordinary.descentTicks(),"Rechecking the lower proof must improve the bounded gap model");
        assertTrue(f.rejectedGap>0,"The high borrowed envelope must encounter the ceiling");
        assertTrue(f.lowerAccepted>0,"A new full-support boundary may acquire the lower independent proof");
        assertTrue(f.acceptedPreviews.stream().noneMatch(p->p.contains(f.goal)),"The solid final platform stays ordinary");
        assertEquals(0,f.airInputs);assertEquals(0,f.submissions);
        for(int i=0;i<30;i++){f.physics();assertTrue(f.ground);assertEquals(0,f.y,1e-8);}
    }

    @Test void aNativeRejectedLowerCorridorKeepsTheOrdinaryFallback() {
        GapFixture f=new GapFixture(false);f.run();assertSafe(f);assertEquals(0,f.lowerAccepted);
        System.out.println("Native-envelope gap ordinary suffix: "+f.descentTicks()+" ticks");
    }

    @Test void theNewProofDoesNotRetireAnotherEdgeOrRelaxTheNormalHandoffSpeed() throws Exception {
        GapFixture f=boundary();int before=handoffs(f);double priorX=f.x,priorZ=f.z;
        f.physics();double speed=Math.hypot(f.x-priorX,f.z-priorZ);f.control();
        assertTrue(flow(f),f.debug());assertEquals(before,handoffs(f));
        assertTrue(f.ground);assertEquals(3,f.y,1e-8);assertTrue(speed<=.12);
        assertEquals(f.path.get(3),NavigationFeet.resolve(f,f.player()));
    }

    @Test void airHalfTreadMissingSamplesAndUnsafeMotionCannotReacquireFlow() throws Exception {
        List<Consumer<GapFixture>> changes=List.of(
            f->f.ground=false, f->f.y-=.5, f->f.y-=.0001, f->f.now++,
            f->f.x-=.13, f->f.x+=.08, f->f.z+=.02, f->f.z+=.11, f->f.x+=.6);
        for(Consumer<GapFixture> change:changes) {
            GapFixture f=boundary();f.physics();change.accept(f);f.control();
            assertFalse(flow(f),f.debug());assertEquals(0,f.lowerAccepted,f.debug());
            assertEquals(0,f.submissions);
        }
    }

    @Test void pendingActionsAndUnknownCalibrationCannotReacquireFlow() throws Exception {
        for(String condition:List.of("busy","dragUnknown","dragHigh","accelerationUnknown","noHandoff")) {
            GapFixture f=boundary();f.physics();Object controller=field(f.nav,"descent");
            switch(condition) {
                case "busy" -> f.pending=true;
                case "dragUnknown" -> set(controller,"observedDrag",Double.NaN);
                case "dragHigh" -> set(controller,"observedDrag",.81);
                case "accelerationUnknown" -> set(controller,"observedAcceleration",Double.NaN);
                case "noHandoff" -> set(controller,"completedEdges",0);
            }
            f.control();assertFalse(flow(f),condition+": "+f.debug());assertEquals(0,f.lowerAccepted,condition);
            assertEquals(0,f.submissions);
        }
    }

    private static GapFixture boundary() {
        GapFixture f=new GapFixture(true);
        while(f.result==Navigation.Result.MOVING && f.now<300) {
            f.control();
            if(handoffs(f)==3 && !flow(f)) {assertTrue(f.ground);assertEquals(3,f.y,1e-8);return f;}
            f.physics();
        }
        throw new AssertionError("No normal full-support handoff: "+f.debug());
    }
    private static int handoffs(GapFixture f){return ((Number)f.nav.diagnostics().get("descentHandoffs")).intValue();}
    private static boolean flow(GapFixture f){return Boolean.TRUE.equals(f.nav.diagnostics().get("descentFlow"));}
    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    private static void assertSafe(GapFixture f) {
        assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());assertTrue(f.ground);assertEquals(0,f.y,1e-8);
        assertTrue(f.player().distance(f.goal)<=.10001);assertTrue(Math.hypot(f.vx,f.vz)<=.002);assertNull(f.movement);
    }

    /** Six physical half-tread steps and a solid last support. A ceiling at
     * x=[2,2.5], y=[5,6] intersects a high borrowed envelope reaching node 4,
     * but neither the upper three-edge envelope nor the lower two-edge one.
     * Actual walking passes below it; no position/velocity is snapped to a node. */
    static final class GapFixture extends StairFlowPhysicsTest.Fixture {
        final boolean lowerAllowed;int lowerAccepted,rejectedGap;boolean pending;
        GapFixture(boolean lowerAllowed){super(6,1,0,true,false);this.lowerAllowed=lowerAllowed;}
        @Override public boolean canFlowDescent(List<Pos> preview,Profile profile) {
            double maxY=preview.get(0).y()+1.8;
            boolean ceiling=false;
            for(int i=1;i<preview.size();i++) {
                double minX=Math.min(preview.get(i-1).x(),preview.get(i).x())+.1;
                double maxX=Math.max(preview.get(i-1).x(),preview.get(i).x())+.9;
                if(minX<2.5 && maxX>2 && maxY>5 && preview.get(i).y()<6)ceiling=true;
            }
            if(ceiling){rejectedGap++;return false;}
            boolean lower=preview.get(0).equals(path.get(3));
            if(lower && !lowerAllowed)return false;
            boolean accepted=super.canFlowDescent(preview,profile);
            if(lower && accepted)lowerAccepted++;
            return accepted;
        }
        @Override public void physics() {
            super.physics();
            assertFalse(x+.3>2 && x-.3<2.5 && y+1.8>5 && y<6,"Actual body must remain below the ceiling");
        }
        @Override public boolean busy(){return pending;}
    }
}
