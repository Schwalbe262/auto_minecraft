package dev.schwalbe.autovalley.client;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingActionReceiptTest {
    private static final class Fixture {
        boolean planting,started=true,stopped,aborted=true,dispatched=true,forwarded=true;
        long generation=5,currentGeneration=5,before=100,abortBefore=110;
        int abortPacket=17;
        LoggingActionReceipt.Processed packet=new LoggingActionReceipt.Processed(111,17);
        boolean cancelled() {
            return LoggingActionReceipt.cancelledBeforeStop(planting,started,stopped,aborted,dispatched,forwarded,
                generation,currentGeneration,before,abortBefore,abortPacket,packet);
        }
    }

    @Test void actualExactOrCumulativeProcessingAckSettlesOnlyTheCancelledPreStopStroke() {
        Fixture f=new Fixture();assertTrue(f.cancelled());
        f.packet=new LoggingActionReceipt.Processed(112,20);assertTrue(f.cancelled());
        var success=new LoggingActionReceipt(5);assertFalse(success.confirmed(5,()->false),"Cancellation is not successful logging");
    }
    @Test void sentAbortWithoutItsRealProcessingAckNeverReleasesTheFence() {
        Fixture f=new Fixture();f.packet=null;assertFalse(f.cancelled());
        for(int processed:List.of(-1,0,1,16)) {
            f.packet=new LoggingActionReceipt.Processed(111,processed);assertFalse(f.cancelled());
        }
    }
    @Test void noStartNoAbortAndDispatchFailureCannotBorrowALaterUnrelatedAck() {
        for(int missing=0;missing<4;missing++) {
            Fixture f=new Fixture();f.packet=new LoggingActionReceipt.Processed(999,999);
            switch(missing) { case 0 -> f.started=false;case 1 -> f.aborted=false;case 2 -> f.dispatched=false;case 3 -> f.forwarded=false; }
            assertFalse(f.cancelled(),"dispatch boundary "+missing);
        }
        Fixture f=new Fixture();
        for(int packet:List.of(-1,0)) {f.abortPacket=packet;assertFalse(f.cancelled());}
    }
    @Test void postStopAndInstantStartMayStillHaveDelayedDestructionAndCannotUseAbortSettlement() {
        Fixture f=new Fixture();f.stopped=true;
        assertFalse(f.cancelled());
        f.packet=new LoggingActionReceipt.Processed(999,999);assertFalse(f.cancelled());
        // begin() marks instant START as stopped before dispatch, including a
        // subsequent throwing send, so it takes this same conservative branch.
    }
    @Test void plantingHasNoMiningAbortAndCannotUseAnyProcessedAckAsPlacementProof() {
        Fixture f=new Fixture();f.planting=true;assertFalse(f.cancelled());
        f.packet=new LoggingActionReceipt.Processed(999,999);assertFalse(f.cancelled());
    }
    @Test void ackMustFollowTheActualAbortObservationBoundaryInTheSameConnection() {
        Fixture f=new Fixture();
        for(long sequence:List.of(-1L,0L,100L,109L,110L)) {
            f.packet=new LoggingActionReceipt.Processed(sequence,999);assertFalse(f.cancelled());
        }
        f=new Fixture();f.currentGeneration=6;assertFalse(f.cancelled());
        f=new Fixture();f.before=-1;assertFalse(f.cancelled());
        f=new Fixture();f.abortBefore=99;assertFalse(f.cancelled());
        f=new Fixture();f.generation=f.currentGeneration=-1;assertFalse(f.cancelled());
    }
    @Test void passiveSuccessReceiptSurvivesEvictionWithoutQueryingOrInventingNewEvidence() {
        var receipt=new LoggingActionReceipt(5);int[] reads={0};
        assertFalse(receipt.confirmed(5,()->{reads[0]++;return false;}));
        assertTrue(receipt.confirmed(5,()->{reads[0]++;return true;}));
        assertTrue(receipt.confirmed(5,()->{throw new AssertionError("The actual proof was evicted");}));
        assertEquals(2,reads[0]);
    }
    @Test void historicalSuccessNeverCrossesAReconnectOrReadsAnOldConnectionAsNewProof() {
        var receipt=new LoggingActionReceipt(5);assertTrue(receipt.confirmed(5,()->true));
        assertFalse(receipt.confirmed(6,()->{throw new AssertionError("Cannot inspect old success after reconnect");}));
        var fresh=new LoggingActionReceipt(6);assertFalse(fresh.confirmed(6,()->false));
        assertTrue(fresh.confirmed(6,()->true));
    }
    @Test void failedPassiveObservationCannotLatchProofOrInterruptTheFollowingAbortPath() {
        var receipt=new LoggingActionReceipt(5);boolean[] abortAttempted={false};
        assertDoesNotThrow(()->{
            assertFalse(receipt.confirmed(5,()->{throw new IllegalStateException("unavailable native metadata");}));
            abortAttempted[0]=true;
        });
        assertTrue(abortAttempted[0]);assertFalse(receipt.confirmed(5,()->false));
        assertTrue(receipt.confirmed(5,()->true));
        assertTrue(receipt.confirmed(5,()->{throw new IllegalStateException("later metadata unavailable");}));
    }
}
