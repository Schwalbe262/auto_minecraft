package dev.schwalbe.autovalley.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeInventoryRefreshTest {
    @Test void onlyTheOrdinaryCompleteEmptyCursorInventoryFullCanConfirmTheRefresh(){
        assertTrue(NativeInventoryRefresh.validFull(0,46,true));
        assertFalse(NativeInventoryRefresh.validFull(1,46,true));
        assertFalse(NativeInventoryRefresh.validFull(0,45,true));
        assertFalse(NativeInventoryRefresh.validFull(0,63,true));
        assertFalse(NativeInventoryRefresh.validFull(0,46,false));
    }
    @Test void refreshProofRequiresANewerSameConnectionFullAndSurvivesEviction(){
        var latch=new NativeLoggingSwap.ReceiptLatch(1,200);
        assertFalse(latch.confirmed(1,()->200));assertFalse(latch.confirmed(1,()->-1));
        assertFalse(latch.confirmed(2,()->201));assertTrue(latch.confirmed(1,()->201));
        assertTrue(latch.confirmed(1,()->{throw new AssertionError("receipt evicted");}));
        assertFalse(latch.confirmed(2,()->201));
    }
}
