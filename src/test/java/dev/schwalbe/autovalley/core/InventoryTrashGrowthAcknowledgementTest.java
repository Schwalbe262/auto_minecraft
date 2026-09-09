package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTrashGrowthAcknowledgementTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static final int SOURCE=25;
    private static InventoryConsolidation.Stack rotten(int count) { return new InventoryConsolidation.Stack("rotten:exact-native-tags",count,64); }
    private static InventoryConsolidation.Stack tomato(int count) { return new InventoryConsolidation.Stack("tomato:exact-native-tags",count,64); }
    private static InventoryTrashAcknowledgement.SlotProof proof(long seq,int slot,InventoryConsolidation.Stack item) {
        return new InventoryTrashAcknowledgement.SlotProof(seq,0,slot,item);
    }
    private static List<InventoryTrashAcknowledgement.SlotProof> captured() {
        return List.of(proof(5216,26,tomato(14)),proof(5217,SOURCE,rotten(4)),proof(5218,SOURCE,EMPTY));
    }
    private static InventoryTrashAcknowledgement.SourceDeletionProof evidence(List<InventoryTrashAcknowledgement.SlotProof> proofs) {
        return InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,5215,5218,SOURCE,rotten(2),proofs);
    }
    private static List<InventoryConsolidation.Stack> menu() {
        var menu=new ArrayList<>(Collections.nCopies(46,EMPTY));menu.set(SOURCE,rotten(2));menu.set(26,tomato(4));return menu;
    }

    @Test void actualTwoToFourPickupThenDeletionReportsFourAndKeepsIndependentTomatoProof() {
        var result=evidence(captured());assertNotNull(result);assertEquals(4,result.removedCount());
        assertEquals(Map.of(26,tomato(14),SOURCE,EMPTY),result.precedingSlotProofs());
        var before=menu();before.set(SOURCE,rotten(result.removedCount()));
        var after=menu();after.set(SOURCE,EMPTY);after.set(26,tomato(14));
        assertEquals(4,InventoryTrashAcknowledgement.confirmed(before,after,SOURCE,true,true,Set.of(26),Set.of(SOURCE,26)));
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,5215,5218,SOURCE,rotten(2),captured()),
            "Logging and all existing strict callers retain their original quantity policy");
    }
    @Test void sourceUnchangedEchoStillConfirmsOriginalQuantity() {
        var result=evidence(List.of(proof(5217,SOURCE,rotten(2)),proof(5218,SOURCE,EMPTY)));
        assertNotNull(result);assertEquals(2,result.removedCount());
    }
    @Test void growthWithoutEmptyNeverConfirms() {
        assertNull(evidence(List.of(proof(5217,SOURCE,rotten(4)))));
        assertNull(evidence(List.of(proof(5217,SOURCE,rotten(4)),proof(5218,26,EMPTY))));
    }
    @Test void multipleIncreasingCountsAndEqualEchoesAreBoundedByNativeStackLimit() {
        var result=InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,1,6,SOURCE,rotten(2),
            List.of(proof(2,SOURCE,rotten(3)),proof(3,SOURCE,rotten(3)),proof(4,SOURCE,rotten(63)),proof(5,SOURCE,rotten(64)),proof(6,SOURCE,EMPTY)));
        assertNotNull(result);assertEquals(64,result.removedCount());
        assertThrows(IllegalArgumentException.class,()->rotten(65));
        assertThrows(IllegalArgumentException.class,()->rotten(Integer.MAX_VALUE));
    }
    @Test void sameCountDifferentIdentityOrLimitIsRejected() {
        for (var changed:List.of(tomato(4),new InventoryConsolidation.Stack("rotten:different-native-tags",4,64),
                new InventoryConsolidation.Stack("rotten:exact-native-tags",4,32)))
            assertNull(evidence(List.of(proof(5217,SOURCE,changed),proof(5218,SOURCE,EMPTY))));
    }
    @Test void sourceDecreaseIsRejectedEvenIfFinalCountRecoversOrExceedsOriginal() {
        assertNull(evidence(List.of(proof(5217,SOURCE,rotten(1)),proof(5218,SOURCE,EMPTY))));
        for(int finalCount:List.of(2,3))assertNull(evidence(List.of(proof(5216,SOURCE,rotten(4)),
            proof(5217,SOURCE,rotten(finalCount)),proof(5218,SOURCE,EMPTY))));
    }
    @Test void emptyThenRefillBeforeCandidateIsNotOneDeletion() {
        assertNull(evidence(List.of(proof(5216,SOURCE,EMPTY),proof(5217,SOURCE,rotten(4)),proof(5218,SOURCE,EMPTY))));
    }
    @Test void laterRefillCannotInvalidateEarlierExactDeletionOrJustifyItsOtherSlots() {
        var proofs=new ArrayList<>(captured());proofs.add(proof(5219,SOURCE,rotten(2)));proofs.add(proof(5220,27,tomato(10)));
        var result=evidence(proofs);assertNotNull(result);assertEquals(4,result.removedCount());assertFalse(result.precedingSlotProofs().containsKey(27));
        proofs.add(proof(5221,SOURCE,EMPTY));
        assertNull(InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,5215,5221,SOURCE,rotten(2),proofs));
    }
    @Test void preRequestGrowthAndLaterGrowthCannotRewriteDeletedQuantity() {
        var result=evidence(List.of(proof(5214,SOURCE,rotten(10)),proof(5215,SOURCE,rotten(12)),
            proof(5218,SOURCE,EMPTY),proof(5219,SOURCE,rotten(20))));
        assertNotNull(result);assertEquals(2,result.removedCount());
    }
    @Test void collectionOrderDoesNotHideEarlierSourceDepletion() {
        var valid=new ArrayList<>(captured());Collections.reverse(valid);assertEquals(4,evidence(valid).removedCount());
        var invalid=new ArrayList<>(List.of(proof(5216,SOURCE,rotten(4)),proof(5217,SOURCE,rotten(3)),proof(5218,SOURCE,EMPTY)));
        Collections.reverse(invalid);assertNull(evidence(invalid));
    }
    @Test void duplicatedOrAmbiguousPacketSequenceIsRejected() {
        var duplicate=new ArrayList<>(captured());duplicate.add(proof(5217,SOURCE,rotten(4)));assertNull(evidence(duplicate));
        var conflicting=new ArrayList<>(captured());conflicting.add(proof(5217,SOURCE,rotten(3)));assertNull(evidence(conflicting));
        var ambiguous=new ArrayList<>(captured());ambiguous.add(proof(5217,27,tomato(2)));assertNull(evidence(ambiguous));
    }
    @Test void wrongGenerationOrMissingExactCandidateSourcePacketIsRejected() {
        assertNull(InventoryTrashAcknowledgement.growingSourceDeletionProof(1,2,0,5215,5218,SOURCE,rotten(2),captured()));
        assertNull(InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,5218,5218,SOURCE,rotten(2),captured()));
        assertNull(InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,5219,5218,SOURCE,rotten(2),captured()));
        assertNull(evidence(List.of(proof(5217,SOURCE,EMPTY),proof(5218,26,tomato(14)))));
        assertNull(evidence(List.of(new InventoryTrashAcknowledgement.SlotProof(5218,12,SOURCE,EMPTY))));
    }
    @Test void wrongMenuGrowthDoesNotAuthorizeThisMenusLargerQuantity() {
        var result=evidence(List.of(new InventoryTrashAcknowledgement.SlotProof(5217,12,SOURCE,rotten(4)),proof(5218,SOURCE,EMPTY)));
        assertNotNull(result);assertEquals(2,result.removedCount());
    }
    @Test void oldOrLaterOtherSlotProofDoesNotAuthorizeAppliedClientPickup() {
        for(long sequence:List.of(5214L,5215L,5219L)) {
            var result=evidence(List.of(proof(sequence,26,tomato(14)),proof(5217,SOURCE,rotten(4)),proof(5218,SOURCE,EMPTY)));
            assertNotNull(result);assertFalse(result.precedingSlotProofs().containsKey(26));
            var before=menu();before.set(SOURCE,rotten(result.removedCount()));
            var after=menu();after.set(SOURCE,EMPTY);after.set(26,tomato(14));
            assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,SOURCE,true,true,Set.of(),Set.of(SOURCE,26)));
        }
    }
    @Test void latestEarlierOtherSlotProofWinsAndReturnedMapIsImmutable() {
        var result=evidence(List.of(proof(5216,26,tomato(13)),proof(5217,26,tomato(14)),proof(5218,SOURCE,EMPTY)));
        assertNotNull(result);assertEquals(tomato(14),result.precedingSlotProofs().get(26));
        assertThrows(UnsupportedOperationException.class,()->result.precedingSlotProofs().put(1,tomato(1)));
    }
    @Test void quantityProofNeverWaivesWholeMenuConservationCursorOrSourceEmpty() {
        var result=evidence(captured());var before=menu();before.set(SOURCE,rotten(result.removedCount()));
        var after=menu();after.set(SOURCE,EMPTY);after.set(26,tomato(14));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,SOURCE,true,false,Set.of(26),Set.of(SOURCE,26)));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,SOURCE,false,true,Set.of(26),Set.of(SOURCE,26)));
        after.set(27,rotten(4));assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,SOURCE,true,true,Set.of(26,27),Set.of(SOURCE,26,27)));
        after.set(27,EMPTY);after.set(26,tomato(3));assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,SOURCE,true,true,Set.of(26),Set.of(SOURCE,26)));
    }
    @Test void malformedProofAndHardBoundsFailClosed() {
        assertNull(evidence(null));assertNull(evidence(Arrays.asList((InventoryTrashAcknowledgement.SlotProof)null)));
        assertNull(evidence(List.of(new InventoryTrashAcknowledgement.SlotProof(5218,0,SOURCE,null))));
        assertNull(evidence(Collections.nCopies(73,proof(5218,SOURCE,EMPTY))));
        assertNull(evidence(List.of(proof(5217,-1,tomato(2)),proof(5218,SOURCE,EMPTY))));
        assertNull(evidence(List.of(proof(5217,128,tomato(2)),proof(5218,SOURCE,EMPTY))));
        for(int source:List.of(-1,128))assertNull(InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,5215,5218,source,rotten(2),captured()));
        assertNull(InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,5215,5218,SOURCE,EMPTY,captured()));
    }
}
