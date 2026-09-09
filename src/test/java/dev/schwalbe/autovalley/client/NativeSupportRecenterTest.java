package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSupportRecenterTest {
    private static final Pos FEET=new Pos(0,64,0),FLOOR=FEET.offset(0,-1,0);
    private static final SupportRecenterGeometry.Box CUBE=new SupportRecenterGeometry.Box(0,0,0,1,1,1);
    private static final SupportRecenterGeometry.Cell AIR=new SupportRecenterGeometry.Cell(true,false,List.of());
    private static SupportRecenterGeometry.Box body(double x,double y,double z) {
        return new SupportRecenterGeometry.Box(x-.3,y,z-.3,x+.3,y+1.8,z+.3);
    }
    private static SupportRecenterGeometry.Box plan(double x,double y,double z) {
        return SupportRecenterGeometry.sweep(x,y,z,.6,1.8,body(x,y,z),FEET,List.of(CUBE),List.of(CUBE));
    }
    private static boolean clear(SupportRecenterGeometry.Box sweep,Map<Pos,SupportRecenterGeometry.Cell> extra) {
        return SupportRecenterGeometry.clear(sweep,p -> extra.getOrDefault(p,p.equals(FLOOR)
            ? new SupportRecenterGeometry.Cell(true,false,List.of(CUBE)) : AIR));
    }
    @Test void tinyExistingToeOverlapAllowsOnlyATowardSupportSameHeightSweep() {
        var sweep=plan(1.2863493,64,.795);
        assertNotNull(sweep); assertTrue(clear(sweep,Map.of()));
        assertTrue(sweep.minX()<.2); assertTrue(sweep.maxX()>1.5863493);
        assertEquals(64+SupportRecenterGeometry.EPS,sweep.minY());
        // Fresh moving positions remain provable: native proof is not a stationary gate.
        for(double x:new double[]{1.28,1.1,.9,.7,.5}) assertNotNull(plan(x,64,.795));
    }
    @Test void touchingSeparatedOrOnlyOtherAxisOverlappingBodiesAreNotSupported() {
        assertNull(plan(1.3,64,.5)); assertNull(plan(1.30001,64,.5));
        assertNull(plan(.5,64,1.3)); assertNull(plan(.5,64,-.30001));
        assertNull(plan(2,64,.5));
    }
    @Test void defaultAndPlayerShapesMustBeTheSameSingleFullFootprintAtActualHeight() {
        var actual=body(1.28,64,.5);
        for(var shape:List.of(List.<SupportRecenterGeometry.Box>of(),
            List.of(new SupportRecenterGeometry.Box(0,0,0,.5,1,1)),
            List.of(new SupportRecenterGeometry.Box(.25,0,.25,.75,1,.75)),
            List.of(new SupportRecenterGeometry.Box(0,0,0,1,.5,1)),List.of(CUBE,CUBE))) {
            assertNull(SupportRecenterGeometry.sweep(1.28,64,.5,.6,1.8,actual,FEET,shape,shape));
        }
        assertNull(SupportRecenterGeometry.sweep(1.28,64,.5,.6,1.8,actual,FEET,List.of(CUBE),List.of()));
        assertNull(plan(1.28,64.0001,.5)); assertNull(plan(1.28,63.9999,.5));
    }
    @Test void malformedOrScaledActualBodyCannotBorrowAValidAnchorProof() {
        assertNull(plan(Double.NaN,64,.5)); assertNull(plan(Double.POSITIVE_INFINITY,64,.5));
        assertNull(SupportRecenterGeometry.sweep(1.28,64,.5,.7,1.8,body(1.28,64,.5),FEET,List.of(CUBE),List.of(CUBE)));
        assertNull(SupportRecenterGeometry.sweep(1.28,64,.5,.6,1.5,body(1.28,64,.5),FEET,List.of(CUBE),List.of(CUBE)));
        assertNull(SupportRecenterGeometry.sweep(1.28,64,.5,.6,1.8,body(1.1,64,.5),FEET,List.of(CUBE),List.of(CUBE)));
        assertNull(SupportRecenterGeometry.sweep(1.28,64,.5,.6,1.8,body(1.28,64,.5),new Pos(Integer.MAX_VALUE,64,0),List.of(CUBE),List.of(CUBE)));
    }
    @Test void wholeSweptBodyRejectsWallsCeilingsAndEvenEmptyHazardOrDoorCells() {
        var sweep=plan(1.2863493,64,.795);
        for(Pos p:List.of(new Pos(1,64,0),new Pos(0,65,0),new Pos(1,64,1))) {
            assertFalse(clear(sweep,Map.of(p,new SupportRecenterGeometry.Cell(true,false,List.of(CUBE)))),p.toString());
            assertFalse(clear(sweep,Map.of(p,new SupportRecenterGeometry.Cell(true,true,List.of()))),p.toString());
        }
        // Dangerous support-band blocks may not be skipped just because they are below the body.
        assertFalse(clear(sweep,Map.of(FLOOR,new SupportRecenterGeometry.Cell(true,true,List.of(CUBE)))));
    }
    @Test void everySweepAndSupportCellMustBeLoadedAndTheQueryHasAHardReadBound() {
        var sweep=plan(1.2863493,64,.795); AtomicInteger reads=new AtomicInteger();
        Set<Pos> cells=new HashSet<>();
        assertTrue(SupportRecenterGeometry.clear(sweep,p -> { reads.incrementAndGet(); cells.add(p); return AIR; }));
        assertTrue(reads.get()<=SupportRecenterGeometry.MAX_CELLS); assertTrue(cells.contains(FLOOR));
        for(Pos p:cells) assertFalse(clear(sweep,Map.of(p,new SupportRecenterGeometry.Cell(false,false,List.of()))));
        AtomicInteger rejectedReads=new AtomicInteger();
        assertFalse(SupportRecenterGeometry.clear(new SupportRecenterGeometry.Box(-29_000_000,-2000,-29_000_000,29_000_000,1970,29_000_000),
            p -> { rejectedReads.incrementAndGet(); return AIR; }));
        assertEquals(0,rejectedReads.get());
    }
    @Test void malformedCollisionBoxesFailClosedInsteadOfBecomingEmptySpace() {
        var sweep=plan(1.28,64,.5);
        for(var box:List.of(new SupportRecenterGeometry.Box(0,0,0,1,Double.NaN,1),
            new SupportRecenterGeometry.Box(0,0,0,1,1.5,1),new SupportRecenterGeometry.Box(0,0,0,0,1,1)))
            assertFalse(clear(sweep,Map.of(FEET,new SupportRecenterGeometry.Cell(true,false,List.of(box)))));
    }
}
