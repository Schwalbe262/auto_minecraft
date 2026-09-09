package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoggingLeafAckTest {
    private static final Pos LEAF=new Pos(1,65,0),STUMP=new Pos(0,64,0);
    private static NativeLoggingActions.LeafBlockAck ack(long sequence,Pos pos,boolean air) {
        return new NativeLoggingActions.LeafBlockAck(sequence,pos,air);
    }
    private static boolean confirmed(List<NativeLoggingActions.LeafBlockAck> replies) {
        return NativeLoggingActions.leafClearedConfirmed(true,true,true,3,3,520,LEAF,replies);
    }

    @Test void theExactDispatchedSpruceLeafRequiresItsLatestRawServerAirReply() {
        assertTrue(confirmed(List.of(ack(521,LEAF,false),ack(522,LEAF,true))));
        assertTrue(confirmed(List.of(ack(522,LEAF,true),ack(523,LEAF,true))));
        assertFalse(confirmed(List.of(ack(521,LEAF,false))));
    }

    @Test void clientAirWithoutRawEvidenceAndUnrelatedTreeOrInventoryRepliesCannotConfirm() {
        assertFalse(confirmed(List.of()));
        assertFalse(confirmed(List.of(ack(521,STUMP,true))));
        assertFalse(confirmed(List.of(ack(521,LEAF.offset(1,0,0),true))));
    }

    @Test void aNewerLeafOrForeignBlockInvalidatesAnEarlierAirReply() {
        assertFalse(confirmed(List.of(ack(521,LEAF,true),ack(522,LEAF,false))));
        assertFalse(confirmed(List.of(ack(521,LEAF,true),ack(522,LEAF,false),ack(523,STUMP,true))));
    }

    @Test void latestSequenceWinsEvenWhenTheDetachedListIsNotOrdered() {
        assertFalse(confirmed(List.of(ack(522,LEAF,false),ack(521,LEAF,true))));
        assertTrue(confirmed(List.of(ack(522,LEAF,true),ack(521,LEAF,false))));
    }

    @Test void oldOrEqualSequenceAirDoesNotProveThisAction() {
        assertFalse(confirmed(List.of(ack(519,LEAF,true),ack(520,LEAF,true))));
        assertTrue(confirmed(List.of(ack(520,LEAF,false),ack(521,LEAF,true))));
    }

    @Test void aNewConnectionCannotBorrowTheOldLeafReply() {
        assertFalse(NativeLoggingActions.leafClearedConfirmed(true,true,true,3,4,520,LEAF,List.of(ack(521,LEAF,true))));
    }

    @Test void dispatchOriginalSpruceLeafAndTheCapturedAxeAreAllRequired() {
        var replies=List.of(ack(521,LEAF,true));
        assertFalse(NativeLoggingActions.leafClearedConfirmed(false,true,true,3,3,520,LEAF,replies));
        assertFalse(NativeLoggingActions.leafClearedConfirmed(true,false,true,3,3,520,LEAF,replies));
        assertFalse(NativeLoggingActions.leafClearedConfirmed(true,true,false,3,3,520,LEAF,replies));
    }

    @Test void malformedMissingOrDuplicateTargetEvidenceFailsClosed() {
        assertFalse(confirmed(null));
        assertFalse(confirmed(Arrays.asList((NativeLoggingActions.LeafBlockAck)null)));
        assertFalse(confirmed(List.of(ack(521,null,true))));
        assertFalse(confirmed(List.of(ack(521,LEAF,true),ack(521,LEAF,false))));
        assertFalse(confirmed(List.of(ack(521,LEAF,true),ack(521,LEAF,true))));
        assertFalse(NativeLoggingActions.leafClearedConfirmed(true,true,true,3,3,520,null,List.of(ack(521,LEAF,true))));
    }
}
