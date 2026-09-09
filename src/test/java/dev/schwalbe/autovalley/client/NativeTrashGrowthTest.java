package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached native identities and actual sequence proof; no game bootstrap or packet sends. */
class NativeTrashGrowthTest {
    private static final int SOURCE=2;
    private static final Set<Integer> NORMAL=Set.of(1,2,3);
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static InventoryConsolidation.Stack item(String id,int count) {
        return new InventoryConsolidation.Stack("{id:\""+id+"\",Count:1b,tag:{quality:0}}",count,64);
    }
    private static List<InventoryConsolidation.Stack> before() {
        return List.of(item("minecraft:iron_hoe",1),item(ItemData.TOMATO,4),item(ItemData.ROTTEN,2),EMPTY);
    }
    private static List<InventoryConsolidation.Stack> after() {
        return List.of(before().get(0),item(ItemData.TOMATO,14),EMPTY,EMPTY);
    }
    private static InventoryTrashAcknowledgement.SlotProof raw(long seq,int slot,InventoryConsolidation.Stack item) {
        return new InventoryTrashAcknowledgement.SlotProof(seq,0,slot,item);
    }
    private static InventoryTrashAcknowledgement.SourceDeletionProof proof(List<InventoryTrashAcknowledgement.SlotProof> observations) {
        return InventoryTrashAcknowledgement.growingSourceDeletionProof(1,1,0,100,103,SOURCE,before().get(SOURCE),observations);
    }
    private static List<InventoryTrashAcknowledgement.SlotProof> race() {
        return List.of(raw(101,1,item(ItemData.TOMATO,14)),raw(102,SOURCE,item(ItemData.ROTTEN,4)),raw(103,SOURCE,EMPTY));
    }
    private static int confirmed(List<InventoryConsolidation.Stack> after,boolean full,boolean cursor,
            InventoryTrashAcknowledgement.SourceDeletionProof proof) {
        return NativeTrashSlot.confirmedSnapshot(before(),after,SOURCE,false,full,cursor,NORMAL,proof);
    }
    @Test void exactOrderedPickupAndSourceGrowthReportsFourDeletedInsteadOfDispatchTwo() {
        var proof=proof(race());assertNotNull(proof);assertEquals(4,proof.removedCount());
        assertEquals(4,confirmed(after(),false,true,proof));assertEquals(4,confirmed(after(),true,true,proof));
        assertNull(InventoryTrashAcknowledgement.precedingSlotProofs(1,1,0,100,103,SOURCE,before().get(SOURCE),race()),"Strict old API remains unchanged");
    }
    @Test void appliedClientNeighbourStillNeedsItsOwnEarlierRawProof() {
        var proof=proof(List.of(raw(102,SOURCE,item(ItemData.ROTTEN,4)),raw(103,SOURCE,EMPTY)));
        assertEquals(0,confirmed(after(),false,true,proof));
        assertEquals(4,confirmed(after(),true,true,proof),"An actual full packet owns all its slots");
    }
    @Test void laterNeighbourProofCannotAuthorizeAnOlderAppliedClientDeletion() {
        var proof=proof(List.of(raw(102,SOURCE,item(ItemData.ROTTEN,4)),raw(103,SOURCE,EMPTY),raw(104,1,item(ItemData.TOMATO,14))));
        assertEquals(0,confirmed(after(),false,true,proof));
    }
    @Test void aLaterSourcePickupDoesNotInvalidateTheAlreadyProvenFirstDeletion() {
        var observations=new ArrayList<>(race());observations.add(raw(104,SOURCE,item(ItemData.ROTTEN,2)));
        assertEquals(4,confirmed(after(),false,true,proof(observations)));
    }
    @Test void originalStrictLoggingNeverOptsIntoIncreasedSourceQuantity() {
        assertEquals(0,NativeTrashSlot.confirmedSnapshot(before(),after(),SOURCE,true,true,true,NORMAL,proof(race())));
        var old=new InventoryTrashAcknowledgement.SourceDeletionProof(2,Map.of(SOURCE,EMPTY));
        List<InventoryConsolidation.Stack> loggingBefore=List.of(before().get(0),EMPTY,item(LoggingRules.TWIG,2),EMPTY);
        List<InventoryConsolidation.Stack> loggingAfter=List.of(before().get(0),EMPTY,EMPTY,EMPTY);
        assertEquals(2,NativeTrashSlot.confirmedSnapshot(loggingBefore,loggingAfter,SOURCE,true,true,true,NORMAL,old));
    }
    @Test void growthOptInCannotApplyToAnotherItemEvenWithAnOpaqueCoreGrowthProof() {
        for(String id:List.of(LoggingRules.SAPLING,ItemData.TOMATO,"minecraft:diamond")) {
            List<InventoryConsolidation.Stack> changed=new ArrayList<>(before());changed.set(SOURCE,item(id,2));
            assertEquals(0,NativeTrashSlot.confirmedSnapshot(changed,after(),SOURCE,false,true,true,NORMAL,proof(race())));
        }
    }
    @Test void cursorMissingProofSourceRefillAndWrongNativeMenuShapeStillReject() {
        assertEquals(0,confirmed(after(),true,false,proof(race())));assertEquals(0,confirmed(after(),true,true,null));
        var refilled=new ArrayList<>(after());refilled.set(SOURCE,item(ItemData.ROTTEN,1));
        assertEquals(0,confirmed(refilled,true,true,proof(race())));
        assertEquals(0,confirmed(after().subList(0,3),true,true,proof(race())));
    }
    @Test void otherWasteGearReplacementsAndCountLossesRemainForbiddenEvenInFullPackets() {
        for(int variant=0;variant<4;variant++) {
            var changed=new ArrayList<>(after());
            if(variant==0)changed.set(3,item(ItemData.ROTTEN,2));
            if(variant==1)changed.set(0,item("minecraft:diamond_hoe",1));
            if(variant==2)changed.set(1,item(ItemData.TOMATO,3));
            if(variant==3)changed.set(1,new InventoryConsolidation.Stack("{id:\""+ItemData.TOMATO+"\",Count:1b,tag:{quality:1}}",14,64));
            assertEquals(0,confirmed(changed,true,true,proof(race())),"variant="+variant);
        }
    }
}
