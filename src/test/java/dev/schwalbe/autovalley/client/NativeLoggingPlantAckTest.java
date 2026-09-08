package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoggingPlantAckTest {
    private static final Pos TARGET=new Pos(0,75,1);
    private static NativeLoggingActions.PlantBlockAck ack(long seq,Pos pos,boolean planted) {
        return new NativeLoggingActions.PlantBlockAck(seq,pos,planted);
    }
    private static boolean confirmed(List<NativeLoggingActions.PlantBlockAck> replies) {
        return NativeLoggingActions.plantingConfirmed(true,true,true,1,1,520,TARGET,replies);
    }

    @Test void rawPlacementConfirmsEvenWhenPickupBurstNeverReportsTheConsumedOneCount() {
        // The actual failure: held 5 at dispatch, raw target replies 522/523, then
        // pickup counts 8..15 and no post-dispatch full menu. No inventory proof
        // belongs in this predicate: the target's authoritative placement is enough.
        assertTrue(confirmed(List.of(ack(522,TARGET,true),ack(523,TARGET,true))));
    }

    @Test void rapidGrowthToSpruceLogStillSatisfiesTheSameRegisteredPlantingCell() {
        // The native adapter maps both SPRUCE_SAPLING and SPRUCE_LOG to true.
        assertTrue(confirmed(List.of(ack(522,TARGET,true),ack(560,TARGET,true))));
    }

    @Test void clientPredictedOccupancyAndUnrelatedInventoryRepliesAreNotRawBlockEvidence() {
        assertFalse(confirmed(List.of()));
        assertFalse(confirmed(List.of(ack(522,TARGET.offset(1,0,0),true))));
    }

    @Test void preDispatchAndEqualSequenceRepliesCannotConfirmPlacement() {
        assertFalse(confirmed(List.of(ack(519,TARGET,true),ack(520,TARGET,true))));
        assertTrue(confirmed(List.of(ack(520,TARGET,false),ack(521,TARGET,true))));
    }

    @Test void newestTargetAirOrForeignBlockInvalidatesAnEarlierSaplingReply() {
        assertFalse(confirmed(List.of(ack(522,TARGET,true),ack(524,TARGET,false))));
        assertFalse(confirmed(List.of(ack(522,TARGET,true),ack(524,TARGET,false),ack(525,TARGET.offset(1,0,0),true))));
    }

    @Test void latestSequenceWinsEvenIfTheSuppliedHistoryIsNotSorted() {
        assertFalse(confirmed(List.of(ack(524,TARGET,false),ack(522,TARGET,true))));
        assertTrue(confirmed(List.of(ack(524,TARGET,true),ack(522,TARGET,false))));
    }

    @Test void connectionChangeCannotBorrowAnOlderSessionsPlacement() {
        assertFalse(NativeLoggingActions.plantingConfirmed(true,true,true,1,2,520,TARGET,List.of(ack(522,TARGET,true))));
    }

    @Test void requestMustHaveBeenDispatchedFromAirWithTheAuthorizedSapling() {
        var replies=List.of(ack(522,TARGET,true));
        assertFalse(NativeLoggingActions.plantingConfirmed(false,true,true,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,true,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,true,false,1,1,520,TARGET,replies));
    }

    @Test void absentOrMalformedEvidenceFailsClosed() {
        assertFalse(confirmed(null));
        assertFalse(confirmed(Arrays.asList((NativeLoggingActions.PlantBlockAck)null)));
        assertFalse(confirmed(List.of(ack(522,null,true))));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,true,true,1,1,520,null,List.of(ack(522,TARGET,true))));
    }
}
