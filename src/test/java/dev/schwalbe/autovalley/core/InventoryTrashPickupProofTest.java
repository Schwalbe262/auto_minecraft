package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTrashPickupProofTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static final InventoryConsolidation.Stack ROTTEN=new InventoryConsolidation.Stack("rotten:q0",2,64);
    private static final InventoryConsolidation.Stack TOOL=new InventoryConsolidation.Stack("hoe:damage12",1,1);
    private static final Set<Integer> NORMAL=Set.of(1,2,3,4);
    private static InventoryConsolidation.Stack tomato(int grade,int count) {return new InventoryConsolidation.Stack("tomato:q"+grade,count,64);}
    private static List<InventoryConsolidation.Stack> before() {return List.of(TOOL,tomato(3,18),tomato(2,40),ROTTEN,EMPTY);}
    private static List<InventoryConsolidation.Stack> after() {return List.of(TOOL,tomato(3,20),tomato(2,42),EMPTY,EMPTY);}
    private static InventoryTrashAcknowledgement.SlotProof proof(long sequence,int slot,InventoryConsolidation.Stack item) {
        return new InventoryTrashAcknowledgement.SlotProof(sequence,0,slot,item);
    }
    private static List<InventoryTrashAcknowledgement.SlotProof> liveProofs() {
        return List.of(proof(24072,1,tomato(3,20)),proof(24073,2,tomato(2,42)),proof(24074,3,EMPTY));
    }
    private static Map<Integer,InventoryConsolidation.Stack> evidence(List<InventoryTrashAcknowledgement.SlotProof> proofs) {
        return InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,24071,24074,3,ROTTEN,proofs);
    }
    private static Set<Integer> matchingAdditions(List<InventoryConsolidation.Stack> after,Map<Integer,InventoryConsolidation.Stack> proofs) {
        Set<Integer> result=new HashSet<>();
        if(proofs!=null)for(int slot:List.of(1,2,4))if(after.get(slot).equals(proofs.get(slot)))result.add(slot);
        return result;
    }
    private static int confirmed(List<InventoryConsolidation.Stack> after,Set<Integer> additions) {
        return InventoryTrashAcknowledgement.confirmed(before(),after,3,true,true,additions,NORMAL);
    }

    @Test void actualTwoTomatoPickupsAndTwoRottenDeletionUseIndependentRawProofs() {
        var proof=evidence(liveProofs());assertNotNull(proof);
        assertEquals(2,confirmed(after(),matchingAdditions(after(),proof)));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before(),after(),3,true,true),"The original strict overload stays strict");
    }
    @Test void missingOneRawPickupProofDoesNotTrustItsAppliedClientSnapshot() {
        var proof=evidence(List.of(proof(24073,2,tomato(2,42)),proof(24074,3,EMPTY)));
        assertNotNull(proof);assertEquals(0,confirmed(after(),matchingAdditions(after(),proof)));
    }
    @Test void laterRawProofCannotJustifyTheEarlierDeletionSnapshot() {
        var proof=evidence(List.of(proof(24073,2,tomato(2,42)),proof(24074,3,EMPTY),proof(24075,1,tomato(3,20))));
        assertNotNull(proof);assertFalse(proof.containsKey(1));assertEquals(0,confirmed(after(),matchingAdditions(after(),proof)));
    }
    @Test void rawPacketMustExactlyMatchObservedAdditionNotJustItsItemOrCount() {
        var proof=evidence(List.of(proof(24072,1,tomato(3,19)),proof(24073,2,tomato(2,42)),proof(24074,3,EMPTY)));
        assertEquals(0,confirmed(after(),matchingAdditions(after(),proof)));
    }
    @Test void rawItemsAtOrBeforeRequestAreNotCurrentProof() {
        var proof=evidence(List.of(proof(24071,1,tomato(3,20)),proof(24070,2,tomato(2,42)),proof(24074,3,EMPTY)));
        assertEquals(Map.of(3,EMPTY),proof);assertEquals(0,confirmed(after(),matchingAdditions(after(),proof)));
    }
    @Test void wrongMenuProofCannotAuthorizeAnotherInventory() {
        var wrong=new InventoryTrashAcknowledgement.SlotProof(24072,12,1,tomato(3,20));
        var proof=evidence(List.of(wrong,proof(24073,2,tomato(2,42)),proof(24074,3,EMPTY)));
        assertEquals(0,confirmed(after(),matchingAdditions(after(),proof)));
        assertNull(evidence(List.of(new InventoryTrashAcknowledgement.SlotProof(24074,12,3,EMPTY))));
    }
    @Test void changedGenerationAndReversedSequenceCannotConfirm() {
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,2,0,24071,24074,3,ROTTEN,liveProofs()));
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,24074,24074,3,ROTTEN,liveProofs()));
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,24075,24074,3,ROTTEN,liveProofs()));
    }
    @Test void sourceEmptyNeedsItsOwnRawPacketOrAuthoritativeFullSourceEntry() {
        assertNull(evidence(List.of(proof(24072,1,tomato(3,20)),proof(24073,2,tomato(2,42)))));
        assertNull(evidence(List.of(proof(24073,3,EMPTY),proof(24074,2,tomato(2,42)))),"A different slot at the candidate sequence cannot confirm the source");
    }
    @Test void sourceDepletionPickupOrReplacementBeforeEmptyMakesOriginalQuantityUncertain() {
        for(var changed:List.of(new InventoryConsolidation.Stack("rotten:q0",1,64),new InventoryConsolidation.Stack("rotten:q0",3,64),tomato(2,2))) {
            assertNull(evidence(List.of(proof(24072,3,changed),proof(24074,3,EMPTY))));
        }
    }
    @Test void loggingSaplingsGrowingFromTwentyEightToThirtyFourBeforeDeletionCannotConfirmOriginalQuantity() {
        // Captured trash request 135: all three other changes have independent
        // server pickup proof. The source itself still changed before EMPTY, so
        // neither those proofs nor the empty cursor establish deletion of 28.
        var originalSource=new InventoryConsolidation.Stack(LoggingRules.SAPLING,28,64);
        var twig=new InventoryConsolidation.Stack(LoggingRules.TWIG,45,64);
        var fullLogs=new InventoryConsolidation.Stack(LoggingRules.LOG,64,64);
        var newLogs=new InventoryConsolidation.Stack(LoggingRules.LOG,21,64);
        var otherPickups=List.of(proof(1906,19,fullLogs),proof(1923,11,twig),proof(1929,20,newLogs));
        List<InventoryTrashAcknowledgement.SlotProof> captured=new ArrayList<>(otherPickups);
        long[] sequences={1870,1879,1880,1881,1920};
        int[] counts={30,31,32,33,34};
        for(int index=0;index<sequences.length;index++)
            captured.add(proof(sequences[index],36,new InventoryConsolidation.Stack(LoggingRules.SAPLING,counts[index],64)));
        captured.add(proof(1930,36,EMPTY));
        assertTrue(otherPickups.stream().allMatch(p -> p.sequence()>1848 && p.sequence()<1930));
        assertEquals(Set.of(11,19,20),otherPickups.stream().map(InventoryTrashAcknowledgement.SlotProof::slot).collect(java.util.stream.Collectors.toSet()));
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,1848,1930,36,originalSource,captured),
            "Source 28 -> 30 -> 31 -> 32 -> 33 -> 34 -> EMPTY must not be acknowledged as the original 28-item deletion");
        Collections.reverse(captured);
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,1848,1930,36,originalSource,captured),
            "Collection order must not hide earlier source pickups from the sequence-ordered proof");
    }
    @Test void sourceReappearingAfterAnEmptyObservationIsNotOriginalDeletion() {
        assertNull(evidence(List.of(proof(24072,3,EMPTY),proof(24073,3,ROTTEN),proof(24074,3,EMPTY))));
    }
    @Test void sourceReturningToOriginalQuantityDoesNotEraseAnEarlierUnexpectedChange() {
        var changed=new InventoryConsolidation.Stack("rotten:q0",3,64);
        assertNull(evidence(List.of(proof(24072,3,changed),proof(24073,3,ROTTEN),proof(24074,3,EMPTY))));
    }
    @Test void unchangedSourceEchoAndOutOfOrderCollectionStillUseActualSequence() {
        var proofs=new ArrayList<>(liveProofs());proofs.add(proof(24072,3,ROTTEN));Collections.reverse(proofs);
        assertNotNull(evidence(proofs));
    }
    @Test void latestProvenSlotValueWinsBeforeDeletion() {
        var proof=evidence(List.of(proof(24072,1,tomato(3,19)),proof(24073,1,tomato(3,20)),proof(24074,3,EMPTY)));
        assertEquals(tomato(3,20),proof.get(1));
    }
    @Test void sourceUpdatesAfterCandidateCannotRewriteAnEarlierConfirmedHistory() {
        var proofs=new ArrayList<>(liveProofs());proofs.add(proof(24075,3,tomato(1,2)));
        assertNotNull(evidence(proofs));
    }
    @Test void fullAuthoritativeSnapshotCanPermitKnownProductionAdditionsWithoutSeparatePackets() {
        assertEquals(2,confirmed(after(),Set.of(1,2)),"Native full-menu caller validates whitelist and scopes each addition");
    }
    @Test void emptyNormalSlotCanReceiveAProvenProductionStack() {
        var after=new ArrayList<>(before());after.set(3,EMPTY);after.set(4,tomato(0,2));
        assertEquals(2,confirmed(after,Set.of(4)));
    }
    @Test void separatelyProvenPineTarPickupUsesTheSameExactPositiveCountRules() {
        var tar=new InventoryConsolidation.Stack(ItemData.PINE_TAR+":native-tags",2,64);
        var after=new ArrayList<>(before());after.set(3,EMPTY);after.set(4,tar);
        var proof=evidence(List.of(proof(24073,4,tar),proof(24074,3,EMPTY)));
        assertEquals(2,confirmed(after,matchingAdditions(after,proof)));
        assertEquals(0,confirmed(after,matchingAdditions(after,evidence(List.of(proof(24074,3,EMPTY))))));
    }
    @Test void countLossReplacementAndNativeIdentityChangesRemainRejected() {
        for(var changed:List.of(tomato(3,17),tomato(2,20),new InventoryConsolidation.Stack("tomato:q3:custom",20,64),
            new InventoryConsolidation.Stack("tomato:q3",20,32),EMPTY)) {
            var after=new ArrayList<>(before());after.set(3,EMPTY);after.set(1,changed);
            assertEquals(0,confirmed(after,Set.of(1)));
        }
    }
    @Test void oversizedPickupAndMovingRottenElsewhereAreNeverExemptions() {
        assertThrows(IllegalArgumentException.class,()->tomato(0,65),"The native-fingerprint value rejects over-limit counts at construction");
        var after=new ArrayList<>(before());after.set(3,EMPTY);
        after.set(4,ROTTEN);assertEquals(0,confirmed(after,Set.of(4)));
    }
    @Test void sourceSlotGearAndCursorNeverBecomeAllowedAdditions() {
        assertEquals(0,confirmed(after(),Set.of(1,2,3)));
        var after=new ArrayList<>(before());after.set(3,EMPTY);after.set(0,new InventoryConsolidation.Stack("hoe:damage13",1,1));
        assertEquals(0,confirmed(after,Set.of(0)));
        after.set(0,tomato(3,2));assertEquals(0,confirmed(after,Set.of(0)),"Even otherwise positive production items cannot change gear/offhand/crafting slots");
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before(),after(),3,true,false,Set.of(1,2),NORMAL));
    }
    @Test void missingSourceEmptyOrDifferentMenuSizeRemainsRejected() {
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before(),after(),3,false,true,Set.of(1,2),NORMAL));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before(),before(),3,true,true,Set.of(1,2),NORMAL));
        assertEquals(0,confirmed(after().subList(0,4),Set.of(1,2)));
    }
    @Test void proofWindowAndAdditionIndicesHaveHardBounds() {
        assertNull(evidence(Collections.nCopies(73,proof(24074,3,EMPTY))));
        assertNull(evidence(List.of(proof(24073,129,tomato(3,2)),proof(24074,3,EMPTY))));
        assertEquals(0,confirmed(after(),Set.of(1,2,99)));
    }
}
