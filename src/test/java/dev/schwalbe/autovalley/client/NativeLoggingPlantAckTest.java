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
        return NativeLoggingActions.plantingConfirmed(true,true,false,true,1,1,520,TARGET,replies);
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
        assertFalse(NativeLoggingActions.plantingConfirmed(true,true,false,true,1,2,520,TARGET,List.of(ack(522,TARGET,true))));
    }

    @Test void requestMustHaveBeenDispatchedFromAnAdmittedCellWithTheAuthorizedSapling() {
        var replies=List.of(ack(522,TARGET,true));
        assertFalse(NativeLoggingActions.plantingConfirmed(false,true,false,true,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,false,true,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,true,false,false,1,1,520,TARGET,replies));
    }

    @Test void absentOrMalformedEvidenceFailsClosed() {
        assertFalse(confirmed(null));
        assertFalse(confirmed(Arrays.asList((NativeLoggingActions.PlantBlockAck)null)));
        assertFalse(confirmed(List.of(ack(522,null,true))));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,true,false,true,1,1,520,null,List.of(ack(522,TARGET,true))));
    }

    @Test void directlyReplacingOneSnowLayerUsesTheSameExactTargetRawReplyProof() {
        var replies=List.of(ack(522,TARGET,true));
        assertTrue(NativeLoggingActions.plantingConfirmed(true,false,true,true,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(false,false,true,true,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,true,false,1,1,520,TARGET,replies));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,true,true,1,2,520,TARGET,replies));
    }

    @Test void snowDisappearancePredictionOrAPlacementAboveSnowIsNotSuccess() {
        for(var replies:List.of(List.<NativeLoggingActions.PlantBlockAck>of(),
            List.of(ack(522,TARGET,false)),List.of(ack(522,TARGET.offset(0,1,0),true)),
            List.of(ack(522,TARGET.offset(0,-1,0),true)),List.of(ack(520,TARGET,true)),
            List.of(ack(522,TARGET,true),ack(523,TARGET,false))))
            assertFalse(NativeLoggingActions.plantingConfirmed(true,false,true,true,1,1,520,TARGET,replies));
    }

    private static NativeLoggingActions.PlantBlockAck wrapper(long seq) {
        return new NativeLoggingActions.PlantBlockAck(seq,TARGET,false,true);
    }
    private static NativeLoggingActions.PlantSnowAck snow(long seq,boolean planted) {
        return new NativeLoggingActions.PlantSnowAck(seq,TARGET,planted);
    }
    private static boolean snowConfirmed(List<NativeLoggingActions.PlantBlockAck> blocks,List<NativeLoggingActions.PlantSnowAck> entities) {
        return NativeLoggingActions.plantingConfirmed(true,false,true,true,1,1,520,TARGET,blocks,entities);
    }

    @Test void realSnowMagicWrapperNeedsFreshExactServerBlockAndContainedSaplingPacket() {
        assertTrue(snowConfirmed(List.of(wrapper(522)),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of()));
        assertFalse(snowConfirmed(List.of(),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of(snow(523,false))));
        assertFalse(snowConfirmed(List.of(wrapper(519)),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of(snow(520,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of(snow(522,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of(new NativeLoggingActions.PlantSnowAck(523,TARGET.offset(0,1,0),true))));
    }

    @Test void predictionSettlementMayRepeatAnIdenticalWrapperAfterItsFreshEntityPacket() {
        assertTrue(snowConfirmed(List.of(wrapper(522),wrapper(524)),List.of(snow(523,true))));
        assertTrue(snowConfirmed(List.of(wrapper(524),wrapper(522)),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522),wrapper(524)),List.of(snow(523,true),snow(525,false))));
    }

    @Test void anyInterveningAirForeignOrMultilayerBlockBreaksTheWrapperProof() {
        assertFalse(snowConfirmed(List.of(wrapper(522),ack(524,TARGET,false)),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522),ack(524,TARGET,false),wrapper(526)),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522),ack(524,TARGET,false),wrapper(526)),List.of(snow(525,true))));
        assertTrue(snowConfirmed(List.of(wrapper(522),ack(524,TARGET,false),wrapper(526)),List.of(snow(523,true),snow(527,true))));
        assertTrue(snowConfirmed(List.of(wrapper(522),ack(524,TARGET,true)),List.of(snow(523,false))));
        assertFalse(snowConfirmed(List.of(ack(524,TARGET,true),wrapper(526)),List.of(snow(523,true))));
    }

    @Test void nativeWrapperRepliesRetainDispatchConnectionAndLatestNegativeGuards() {
        var blocks=List.of(wrapper(522));var entities=List.of(snow(523,true));
        assertFalse(NativeLoggingActions.plantingConfirmed(false,false,true,true,1,1,520,TARGET,blocks,entities));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,false,true,1,1,520,TARGET,blocks,entities));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,true,false,1,1,520,TARGET,blocks,entities));
        assertFalse(NativeLoggingActions.plantingConfirmed(true,false,true,true,1,2,520,TARGET,blocks,entities));
        assertFalse(snowConfirmed(blocks,List.of(snow(525,false),snow(523,true))));
        assertTrue(snowConfirmed(blocks,List.of(snow(525,true),snow(523,false))));
    }

    @Test void malformedOrDuplicateExactTargetEvidenceCannotConfirmWrapperPlanting() {
        assertFalse(snowConfirmed(List.of(wrapper(522)),null));
        assertFalse(snowConfirmed(List.of(wrapper(522)),Arrays.asList((NativeLoggingActions.PlantSnowAck)null)));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of(new NativeLoggingActions.PlantSnowAck(523,null,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522),wrapper(522)),List.of(snow(523,true))));
        assertFalse(snowConfirmed(List.of(wrapper(522)),List.of(snow(523,true),snow(523,false))));
    }
}
