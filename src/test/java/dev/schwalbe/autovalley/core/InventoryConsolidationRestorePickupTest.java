package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class InventoryConsolidationRestorePickupTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static final InventoryConsolidation.Stack TORCH=new InventoryConsolidation.Stack("torch",55,64);

    private static InventoryConsolidation.Stack wine(int count) {
        return new InventoryConsolidation.Stack("wine{Year:10}",count,64);
    }

    private static InventoryConsolidation.Snapshot snapshot(List<InventoryConsolidation.Stack> items) {
        return new InventoryConsolidation.Snapshot(items);
    }

    private static List<InventoryConsolidation.Stack> initial() {
        var items=new ArrayList<>(Collections.nCopies(36,EMPTY));
        items.set(4,new InventoryConsolidation.Stack("protected_hoe",1,1));
        items.set(6,TORCH); items.set(9,wine(1)); items.set(10,wine(1));
        return items;
    }

    private static InventoryConsolidation transaction(List<InventoryConsolidation.Stack> items) {
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("minecraft:torch",55,0,null,false,0));
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,
            9,6,List.of(10),visible),snapshot(items));
    }

    private static void swapOut(InventoryConsolidation operation,List<InventoryConsolidation.Stack> items) {
        Collections.swap(items,9,6);
        assertEquals(NEXT,operation.acknowledge(snapshot(items)));
    }

    private static void merged(InventoryConsolidation operation,List<InventoryConsolidation.Stack> items) {
        swapOut(operation,items); items.set(6,EMPTY); items.set(10,wine(2));
        assertEquals(NEXT,operation.acknowledge(snapshot(items)));
    }

    @Test void actualAcknowledgedMergeThenScratchPickupPreservesWineAndRestoresAllBorrowedTorches() {
        var items=initial(); var operation=transaction(items); merged(operation,items);
        var restore=operation.click(); items.set(6,wine(1));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6)),"Legacy overload stays strict");
        assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));
        assertEquals(restore,operation.click()); assertFalse(operation.complete());
        assertEquals(WAIT,operation.acknowledge(snapshot(items)),"Pickup alone cannot complete the inverse swap");
        Collections.swap(items,9,6);
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(TORCH,operation.expectedLive().items().get(6));
        assertEquals(wine(1),operation.expectedLive().items().get(9));
        assertEquals(wine(2),operation.expectedLive().items().get(10));
        assertEquals(0,operation.freedSlots(),"The new pickup filled the slot freed by the merge");
    }

    @Test void scratchExceptionNeverAppliesBeforeBothPrimitivesAreAcknowledgedOrAfterDone() {
        for(int phase=0;phase<2;phase++) {
            var items=initial(); var operation=transaction(items);
            if(phase==1) swapOut(operation,items);
            var before=operation.expectedLive(); var next=operation.click();
            items.set(6,wine(2));
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));
            assertEquals(before,operation.expectedLive()); assertEquals(next,operation.click());
        }
        var items=initial(); var operation=transaction(items); merged(operation,items);
        Collections.swap(items,9,6); assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(),Set.of()));
    }

    @Test void unacknowledgedMergeCannotBeReplacedByACompositeMergeAndPickupSnapshot() {
        var items=initial(); var operation=transaction(items); swapOut(operation,items);
        var before=operation.expectedLive(); var next=operation.click();
        items.set(6,wine(1)); items.set(10,wine(2));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6,10),Set.of(6,10)));
        assertEquals(before,operation.expectedLive()); assertEquals(next,operation.click());
    }

    @Test void sourceChangesAreRejectedEvenWhenTheNewScratchHasProductionProof() {
        for(var changedSource:List.of(EMPTY,wine(1),new InventoryConsolidation.Stack("torch",54,64),
                new InventoryConsolidation.Stack("torch{changed:true}",55,64))) {
            var items=initial(); var operation=transaction(items); merged(operation,items);
            var before=operation.expectedLive(); items.set(6,wine(1)); items.set(9,changedSource);
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6,9),Set.of(6)));
            assertEquals(before,operation.expectedLive());
        }
    }

    @Test void scratchMustPreviouslyBeEmptyAndItsNewStackMustFitAnOrdinaryInventorySlot() {
        var items=initial(); items.set(9,wine(2)); items.set(10,wine(63));
        var partial=transaction(items); swapOut(partial,items);
        items.set(6,wine(1)); items.set(10,wine(64));
        assertEquals(NEXT,partial.acknowledge(snapshot(items)));
        var before=partial.expectedLive(); items.set(6,wine(2));
        assertFalse(partial.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));
        assertEquals(before,partial.expectedLive());

        var fullItems=initial(); var full=transaction(fullItems); merged(full,fullItems);
        var completedMerge=full.expectedLive();
        assertFalse(full.rebaseVerifiedUpdates(snapshot(fullItems),Set.of(6),Set.of(6)),"EMPTY to EMPTY is not a pickup");
        fullItems.set(6,new InventoryConsolidation.Stack("wine{Year:10}",1,128));
        assertFalse(full.rebaseVerifiedUpdates(snapshot(fullItems),Set.of(6),Set.of(6)));
        assertEquals(completedMerge,full.expectedLive());
        fullItems.set(6,wine(1)); assertTrue(full.rebaseVerifiedUpdates(snapshot(fullItems),Set.of(6),Set.of(6)));
        var afterPickup=full.expectedLive(); fullItems.set(6,wine(2));
        assertFalse(full.rebaseVerifiedUpdates(snapshot(fullItems),Set.of(6),Set.of(6)),"One EMPTY-slot exception cannot become a general scratch rebase");
        assertEquals(afterPickup,full.expectedLive());
    }

    @Test void missingProofInvalidIndicesAndUnprovenOtherChangesLeaveEverythingUntouched() {
        var items=initial(); var operation=transaction(items); merged(operation,items);
        var before=operation.expectedLive(); var next=operation.click(); items.set(6,wine(1));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of()));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(),Set.of(6)));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),null,Set.of(6)));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),null));
        assertFalse(operation.rebaseVerifiedUpdates(null,Set.of(6),Set.of(6)));
        for(int invalid:new int[]{-1,36,Integer.MAX_VALUE})
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6,invalid),Set.of(6,invalid)));
        var nullIndex=new HashSet<Integer>(); nullIndex.add(6); nullIndex.add(null);
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),nullIndex,nullIndex));
        items.set(10,wine(3));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));
        assertEquals(before,operation.expectedLive()); assertEquals(next,operation.click());
    }

    @Test void newOverloadDoesNotRelaxMergeReceiverOrSourceGuards() {
        for(int index:new int[]{6,9,10,35}) {
            var items=initial(); var operation=transaction(items); swapOut(operation,items);
            var before=operation.expectedLive(); items.set(index,wine(3));
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(index),Set.of(index)));
            assertEquals(before,operation.expectedLive());
        }
    }

    @Test void successfulScratchRefreshStillRequiresExactPartnerCountIdentityAndPlacementInRestoreAck() {
        var items=initial(); var operation=transaction(items); merged(operation,items);
        items.set(6,wine(1)); assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));
        var before=operation.expectedLive(); var next=operation.click(); Collections.swap(items,9,6);
        for(int partner:new int[]{6,9}) {
            var wrong=new ArrayList<>(items); wrong.set(partner,EMPTY);
            assertEquals(WAIT,operation.acknowledge(snapshot(wrong)));
            assertEquals(before,operation.expectedLive()); assertEquals(next,operation.click());
        }
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
    }
}
