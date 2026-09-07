package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HarvestAreaTest {
    private static final Pos CENTER=new Pos(0,64,0);
    @Test void goldenHoeDoesNotIncludeUnrelatedCropsOutsideItsThreeByThreeArea() {
        int radius=HarvestArea.halfSpan(2,true);
        var cells=HarvestArea.cells(CENTER,radius);
        assertEquals(1,radius); assertEquals(18,cells.size());
        assertTrue(cells.contains(CENTER.offset(-1,0,1)));
        assertTrue(cells.contains(CENTER.offset(1,1,-1)));
        assertFalse(cells.contains(CENTER.offset(2,0,0)));
        assertFalse(cells.contains(CENTER.offset(4,0,0)));
    }
    @Test void highTierAndMaximumRangesKeepTheirOwnGeometry() {
        assertEquals(50,HarvestArea.cells(CENTER,HarvestArea.halfSpan(3,true)).size());
        assertEquals(162,HarvestArea.cells(CENTER,HarvestArea.halfSpan(5,true)).size());
    }
    @Test void disabledAreaAndPointOnlyRangeDoNotExpandToTheMaximum() {
        assertEquals(0,HarvestArea.halfSpan(3,false));
        assertEquals(0,HarvestArea.halfSpan(1,true));
        assertEquals(2,HarvestArea.cells(CENTER,0).size());
    }
    @Test void unknownRangesAndInvalidGeometryAreRejected() {
        for (int range:new int[]{-1,0,6,Integer.MAX_VALUE}) {
            assertEquals(-1,HarvestArea.halfSpan(range,true));
            assertEquals(-1,HarvestArea.halfSpan(range,false));
        }
        assertThrows(IllegalArgumentException.class,() -> HarvestArea.cells(CENTER,-1));
        assertThrows(IllegalArgumentException.class,() -> HarvestArea.cells(CENTER,5));
        assertThrows(IllegalArgumentException.class,() -> HarvestArea.cells(null,1));
    }
}
