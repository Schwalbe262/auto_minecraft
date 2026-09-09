package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import dev.schwalbe.autovalley.core.WorldAccess;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeStairRecenterTest {
    private static final Pos ANCHOR=new Pos(0,67,0),FLOOR=ANCHOR.offset(0,-1,0);
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    private static final NativeLoggingJump.Cell AIR=cell(true,false,true,List.of());
    private static NativeLoggingJump.Cell cell(boolean loaded,boolean forbidden,boolean normal,List<AABB> boxes) {
        return new NativeLoggingJump.Cell(loaded,true,forbidden,normal,boxes);
    }
    private static AABB body(double x,double y,double z) { return new AABB(x-.3,y,z-.3,x+.3,y+1.8,z+.3); }
    private static List<AABB> shape(int fx,int fz) {
        return List.of(new AABB(0,0,0,1,.5,1),new AABB(fx>0?.5:0,.5,fz>0?.5:0,fx<0?.5:1,1,fz<0?.5:1));
    }
    private static StairRecenterGeometry.Proof plan(double x,double y,double z,int fx,int fz) {
        return StairRecenterGeometry.inspect(x,y,z,.6,1.8,body(x,y,z),ANCHOR,.6,true,fx,fz,shape(fx,fz),shape(fx,fz));
    }
    private static boolean clear(StairRecenterGeometry.Proof proof,Map<Pos,NativeLoggingJump.Cell> extra) {
        return StairRecenterGeometry.clear(proof,p -> extra.getOrDefault(p,p.equals(FLOOR)
            ? cell(true,false,true,proof.shape()) : AIR));
    }

    @Test void incidentLowerTreadAllowsOnlyInwardOrdinaryHalfStepAndRemainsValidAtTop() {
        var proof=plan(.7,66.5,.048,0,1);
        assertNotNull(proof); assertTrue(clear(proof,Map.of()));
        assertEquals(66.5+StairRecenterGeometry.EPS,proof.envelope().minY);
        assertEquals(68.8-StairRecenterGeometry.EPS,proof.envelope().maxY);
        assertEquals(67+StairRecenterGeometry.EPS,proof.raisedSweep().minY);
        for(double z:new double[]{.048,.1,.19,.2}) assertNotNull(plan(.7,66.5,z,0,1));
        for(double z:new double[]{.20001,.3,.4,.5}) assertNotNull(plan(.7-(z-.2)/1.5,67,z,0,1));
        assertNotNull(plan(.5,67,.5,0,1));
    }
    @Test void allFourFacingsHaveTheSameLowerAndUpperSupportContract() {
        for(int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
            double x=.5-.452*d[0],z=.5-.452*d[1];
            var proof=plan(x,66.5,z,d[0],d[1]);
            assertNotNull(proof,Arrays.toString(d)); assertTrue(clear(proof,Map.of()));
            assertNotNull(plan(.5,67,.5,d[0],d[1]));
            assertNull(plan(.5+.452*d[0],66.5,.5+.452*d[1],d[0],d[1]));
        }
    }
    @Test void airTouchingUpperLedgeNeighbouringStartAndLateralOverhangCannotBorrowSupport() {
        assertNull(plan(.5,67,.2,0,1)); // Body only touches the upper-tread edge.
        assertNull(plan(.5,67,.1,0,1));
        assertNull(plan(.5,66.5,.20001,0,1)); // Lower body would penetrate the riser.
        assertNull(plan(.5,66.75,.1,0,1));
        assertNull(plan(.5,66.4999,.1,0,1)); assertNull(plan(.5,67.0001,.5,0,1));
        assertNull(plan(.5,66.5,-.01,0,1)); // Even positive toe overlap is not a neighbouring-start permission.
        assertNull(plan(.15,66.5,.1,0,1)); assertNull(plan(.85,66.5,.1,0,1));
        assertNull(plan(.5,66,.1,0,1)); assertNull(plan(.5,67.5,.5,0,1));
    }
    @Test void nativeDefaultAndPlayerShapesMustBeExactlyTheVerifiedStraightBottomStair() {
        var good=shape(0,1);
        for(var bad:List.of(List.<AABB>of(),List.of(CUBE),List.of(new AABB(0,0,0,1,.5,1)),shape(0,-1),
                List.of(new AABB(0,.5,0,1,1,1),new AABB(0,0,.5,1,.5,1)),
                List.of(new AABB(0,0,0,1,.5,1),new AABB(.5,.5,.5,1,1,1)))) {
            assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),ANCHOR,.6,true,0,1,bad,bad));
            assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),ANCHOR,.6,true,0,1,good,bad));
        }
        assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),ANCHOR,.6,false,0,1,good,good));
        assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),ANCHOR,.6,true,1,1,good,good));
    }
    @Test void currentActualBodyAndFiniteStandardDimensionsAndStepCapabilityAreMandatory() {
        var good=shape(0,1);
        for(double step:new double[]{.499999,0,Double.NaN,Double.POSITIVE_INFINITY})
            assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),ANCHOR,step,true,0,1,good,good));
        assertNotNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),ANCHOR,.5,true,0,1,good,good));
        assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.7,1.8,body(.5,66.5,.1),ANCHOR,.6,true,0,1,good,good));
        assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.5,body(.5,66.5,.1),ANCHOR,.6,true,0,1,good,good));
        assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.6,66.5,.1),ANCHOR,.6,true,0,1,good,good));
        assertNull(plan(Double.NaN,66.5,.1,0,1)); assertNull(plan(.5,Double.NaN,.1,0,1));
        assertNull(StairRecenterGeometry.inspect(.5,66.5,.1,.6,1.8,body(.5,66.5,.1),new Pos(Integer.MAX_VALUE,67,0),.6,true,0,1,good,good));
    }
    @Test void wholeWidthAndLiftEnvelopeRejectEveryOtherObstacleAndDangerousSupport() {
        var proof=plan(.7,66.5,.048,0,1);
        for(Pos p:List.of(ANCHOR,ANCHOR.offset(0,1,0),FLOOR.offset(0,0,-1))) {
            assertFalse(clear(proof,Map.of(p,cell(true,false,true,List.of(CUBE)))),p.toString());
            assertFalse(clear(proof,Map.of(p,cell(true,true,true,List.of()))),p.toString());
            assertFalse(clear(proof,Map.of(p,cell(true,false,false,List.of()))),p.toString());
        }
        assertFalse(clear(proof,Map.of(FLOOR,cell(true,true,true,proof.shape()))));
        assertFalse(clear(proof,Map.of(FLOOR,cell(true,false,false,proof.shape()))));
        assertFalse(clear(proof,Map.of(FLOOR,AIR))); // Changed, missing or rotated support is never the exception.
        assertFalse(clear(proof,Map.of(FLOOR,cell(true,false,true,shape(0,-1)))));
    }
    @Test void exactNativeWidthMayTouchAnOrdinarySideWallWhileMovingInwardButNeverOverlapIt() {
        double width=.6000000238418579,x=1-width/2,z=.048,y=66.5;
        AABB actual=new AABB(x-width/2,y,z-width/2,x+width/2,y+1.8,z+width/2);
        var good=shape(0,1);
        var proof=StairRecenterGeometry.inspect(x,y,z,width,1.8,actual,ANCHOR,.6,true,0,1,good,good);
        assertNotNull(proof); assertEquals(1,proof.envelope().maxX);
        var walls=Map.of(ANCHOR.offset(1,0,0),cell(true,false,true,List.of(CUBE)),
            ANCHOR.offset(1,1,0),cell(true,false,true,List.of(CUBE)));
        assertTrue(clear(proof,walls));
        double shifted=x+.000001;
        var overlap=StairRecenterGeometry.inspect(shifted,y,z,width,1.8,actual.move(.000001,0,0),ANCHOR,.6,true,0,1,good,good);
        assertNotNull(overlap); assertFalse(clear(overlap,walls));
    }
    @Test void loadedCollisionHaloIsFiniteAndUnknownOrMalformedShapesFailClosed() {
        var proof=plan(.7,66.5,.048,0,1); Set<Pos> read=new HashSet<>(); AtomicInteger count=new AtomicInteger();
        assertTrue(StairRecenterGeometry.clear(proof,p -> {
            read.add(p); count.incrementAndGet(); return p.equals(FLOOR)?cell(true,false,true,proof.shape()):AIR;
        }));
        assertEquals(read.size(),count.get()); assertTrue(count.get()<=StairRecenterGeometry.MAX_CELLS);
        for(Pos p:read) assertFalse(clear(proof,Map.of(p,cell(false,false,true,List.of()))));
        for(AABB malformed:List.of(new AABB(0,0,0,1,Double.NaN,1),new AABB(0,0,0,1,1.5,1),new AABB(0,0,0,0,1,1)))
            assertFalse(clear(proof,Map.of(ANCHOR,cell(true,false,true,List.of(malformed)))));
        assertFalse(StairRecenterGeometry.clear(null,p -> { fail("No query without a proof"); return AIR; }));
    }
    @Test void unknownWorldAdaptersRemainOptOut() {
        WorldAccess unknown=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class<?>[]{WorldAccess.class},
            (proxy,method,args) -> InvocationHandler.invokeDefault(proxy,method,args));
        assertFalse(unknown.canRecenterOnStair(ANCHOR));
    }
}
