package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** Detached full-reply regression: a distinct wine arrives beside a conserved wine merge. */
class NativeConsolidationReceiverPickupTest {
    private static Stack item(String id,int count,String tags) {
        return new Stack("{Count:1b,id:\""+id+"\",tag:"+tags+"}",count,64);
    }
    private static Stack wine(int count) { return item(ItemData.WINE,count,"{Year:10,quality:1}"); }
    private static Stack pickup(int count) { return item(ItemData.WINE,count,"{Year:10,quality:2}"); }
    private static final Stack SALAD=item("farmersdelight:fruit_salad",1,"{}");
    private static List<Stack> initial() {
        var items=new ArrayList<>(Collections.nCopies(36,Stack.EMPTY));
        items.set(4,new Stack("{Count:1b,id:\"minecraft:golden_hoe\"}",1,1));
        items.set(6,SALAD); items.set(13,wine(2)); items.set(14,wine(1));
        return items;
    }
    private static InventoryConsolidation transaction(List<Stack> items) {
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("farmersdelight:fruit_salad",1,0,null,false,0));
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(
            Feature.WINE,ItemData.WINE,13,6,List.of(14),visible),new Snapshot(items));
    }
    private static void swapOut(InventoryConsolidation transaction,List<Stack> items) {
        Collections.swap(items,13,6);
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
    }
    private static Set<Integer> classify(InventoryConsolidation transaction,List<Stack> after) {
        return NativeInventoryConsolidation.concurrentProductionAdditions(transaction,new Snapshot(after),4);
    }
    private static void merge(List<Stack> items) {
        items.set(6,Stack.EMPTY); items.set(14,wine(3));
    }
    private static boolean rebase(InventoryConsolidation transaction,List<Stack> live,Map<Integer,Stack> rawItems) {
        var before=transaction.expectedLive();
        var changed=NativeInventoryConsolidation.matchingServerSlotUpdates(before.items(),live,rawItems);
        if (changed==null || changed.isEmpty()) return false;
        for (int index:changed)
            if (index==4 || !NativeInventoryConsolidation.productionAddition(before.items().get(index),live.get(index))) return false;
        return transaction.rebaseVerifiedUpdates(new Snapshot(live),changed,changed);
    }

    @Test void exactTwoWineMergeAndDistinctDestinationPickupPreserveBorrowedSaladOnRestore() {
        var items=initial();var transaction=transaction(items);
        assertFalse(transaction.requiresRestoration());
        swapOut(transaction,items);assertTrue(transaction.requiresRestoration());
        // Normal inventory packet slots 9/14/42 map to inventory indices 9/14/6.
        merge(items);items.set(9,pickup(1));
        assertEquals(Set.of(9),classify(transaction,items),"The 1-to-3 receiver belongs to the two-item move");
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items)),"The legacy overload remains strict");
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
        assertTrue(transaction.requiresRestoration());
        assertEquals(new Click(Type.SWAP,13,6),transaction.click());
        Collections.swap(items,13,6);
        assertEquals(COMPLETE,transaction.acknowledge(new Snapshot(items)));
        assertFalse(transaction.requiresRestoration());
        assertEquals(SALAD,transaction.expectedLive().items().get(6));
        assertEquals(Stack.EMPTY,transaction.expectedLive().items().get(13));
        assertEquals(wine(3),transaction.expectedLive().items().get(14));
        assertEquals(pickup(1),transaction.expectedLive().items().get(9));
        assertEquals(0,transaction.freedSlots(),"The concurrent pickup occupied the freed slot capacity");
    }

    @Test void sourceIdentityPickupCannotHideExtraMovedWine() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        var before=transaction.expectedLive();merge(items);items.set(9,wine(1));
        assertTrue(classify(transaction,items).isEmpty());
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),Set.of(9)),"Forged pickup classification stays rejected in the core");
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
        assertEquals(before,transaction.expectedLive());
    }

    @Test void distinctPickupNeverAcknowledgesARefusedOrMissingMerge() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        var before=transaction.expectedLive();var click=transaction.click();
        items.set(9,pickup(1));assertEquals(Set.of(9),classify(transaction,items));
        for (int i=0;i<3;i++) {
            assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
            assertEquals(before,transaction.expectedLive());assertEquals(click,transaction.click());
        }
    }

    @Test void unrelatedPickupCannotHideAChangedParticipantOrProtectedHoe() {
        for (int index:new int[]{4,6,13,14}) {
            var items=initial();var transaction=transaction(items);swapOut(transaction,items);
            var before=transaction.expectedLive();merge(items);items.set(9,pickup(1));items.set(index,pickup(1));
            assertFalse(classify(transaction,items).contains(index));
            assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
            assertEquals(before,transaction.expectedLive());
        }
    }

    @Test void receiverCountLossOrInflationStillRejectsTheWholeReply() {
        for (int received:new int[]{1,2,4}) {
            var items=initial();var transaction=transaction(items);swapOut(transaction,items);
            var before=transaction.expectedLive();merge(items);items.set(14,wine(received));items.set(9,pickup(1));
            assertEquals(Set.of(9),classify(transaction,items));
            assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
            assertEquals(before,transaction.expectedLive());
        }
    }

    @Test void destinationAdditionsStillRequireWhitelistedItemsAndExactExistingIdentity() {
        for (Stack added:List.of(item("minecraft:torch",1,"{}"),item(ItemData.ROTTEN,1,"{}"))) {
            var items=initial();var transaction=transaction(items);swapOut(transaction,items);
            merge(items);items.set(9,added);assertTrue(classify(transaction,items).isEmpty());
            assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
        }
        for (Stack after:List.of(Stack.EMPTY,pickup(1),wine(3),item(ItemData.WINE,3,"{Year:11,quality:2}"))) {
            var items=initial();items.set(9,pickup(2));var transaction=transaction(items);swapOut(transaction,items);
            merge(items);items.set(9,after);assertTrue(classify(transaction,items).isEmpty());
            assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
        }
        var items=initial();items.set(9,pickup(2));var transaction=transaction(items);swapOut(transaction,items);
        merge(items);items.set(9,pickup(3));assertEquals(Set.of(9),classify(transaction,items));
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
    }

    @Test void identityAwareEligibilityRejectsMalformedEvidenceAndFinishedTransactions() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        for (int index:new int[]{-1,36,Integer.MAX_VALUE,6,13})
            assertFalse(transaction.allowsConcurrentAddition(index,pickup(1)));
        assertFalse(transaction.allowsConcurrentAddition(9,null));
        assertFalse(transaction.allowsConcurrentAddition(9,Stack.EMPTY));
        merge(items);assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
        Collections.swap(items,13,6);assertEquals(COMPLETE,transaction.acknowledge(new Snapshot(items)));
        assertFalse(transaction.allowsConcurrentAddition(9,pickup(1)));
    }

    @Test void rawProvenDistinctReceiverPickupBeforeMergeRebasesWithoutAdvancingTheClick() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        var click=transaction.click();items.set(9,pickup(1));
        assertTrue(rebase(transaction,items,Map.of(9,pickup(1))));
        assertEquals(click,transaction.click());assertTrue(transaction.requiresRestoration());
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items)),"The pickup cannot stand in for the next move");
        merge(items);assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
        Collections.swap(items,13,6);assertEquals(COMPLETE,transaction.acknowledge(new Snapshot(items)));
        assertEquals(pickup(1),transaction.expectedLive().items().get(9));
        assertEquals(SALAD,transaction.expectedLive().items().get(6));
    }

    @Test void receiverRebaseNeedsThePositiveAdditionOverloadAndExactRawSlotEvidence() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        var before=transaction.expectedLive();items.set(9,pickup(1));
        assertFalse(transaction.rebaseVerifiedUpdates(new Snapshot(items),Set.of(9)));
        assertFalse(transaction.rebaseVerifiedUpdates(new Snapshot(items),Set.of(9),Set.of()));
        for (var raw:List.of(Map.<Integer,Stack>of(),Map.of(9,pickup(2)),Map.of(10,pickup(1))))
            assertFalse(rebase(transaction,items,raw));
        assertEquals(before,transaction.expectedLive());
    }

    @Test void receiverRebaseCannotChangeParticipantsSameIdentityOrExistingNativeMetadata() {
        for (int index:new int[]{4,6,9,13,14}) {
            var items=initial();var transaction=transaction(items);swapOut(transaction,items);
            var before=transaction.expectedLive();var added=index==9 ? wine(1) : pickup(1);
            items.set(index,added);assertFalse(rebase(transaction,items,Map.of(index,added)));
            assertEquals(before,transaction.expectedLive());
        }
        var items=initial();var transaction=transaction(items);items.set(9,pickup(1));
        assertFalse(rebase(transaction,items,Map.of(9,pickup(1))),"The first outward SWAP must already be confirmed");
    }

    @Test void cancelledLoanCanOnlyBeResolvedByAnExactObservedInverseSwap() {
        for (boolean merged:new boolean[]{false,true}) {
            var items=initial();var transaction=transaction(items);
            assertFalse(transaction.acknowledgeCancelledRestoration(new Snapshot(items)));
            swapOut(transaction,items);
            if (merged) { merge(items);assertEquals(NEXT,transaction.acknowledge(new Snapshot(items))); }
            var before=transaction.expectedLive();var click=transaction.click();
            assertFalse(transaction.acknowledgeCancelledRestoration(null));
            assertFalse(transaction.acknowledgeCancelledRestoration(new Snapshot(items)));
            var changed=new ArrayList<>(items);Collections.swap(changed,13,6);changed.set(9,pickup(1));
            assertFalse(transaction.acknowledgeCancelledRestoration(new Snapshot(changed)),"Other inventory changes cannot borrow the restore proof");
            assertEquals(before,transaction.expectedLive());assertEquals(click,transaction.click());
            Collections.swap(items,13,6);
            assertTrue(transaction.acknowledgeCancelledRestoration(new Snapshot(items)));
            assertFalse(transaction.requiresRestoration());assertTrue(transaction.complete());
            assertEquals(SALAD,transaction.expectedLive().items().get(6));
            assertEquals(merged ? Stack.EMPTY : wine(2),transaction.expectedLive().items().get(13));
            assertFalse(transaction.acknowledgeCancelledRestoration(new Snapshot(items)));
        }
    }
}
