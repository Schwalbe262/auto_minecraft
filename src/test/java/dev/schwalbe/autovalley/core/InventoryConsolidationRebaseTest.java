package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class InventoryConsolidationRebaseTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;

    private static InventoryConsolidation.Stack stack(String identity,int count) {
        return new InventoryConsolidation.Stack(identity,count,64);
    }

    private static InventoryConsolidation.Snapshot snapshot(List<InventoryConsolidation.Stack> items) {
        return new InventoryConsolidation.Snapshot(items);
    }

    private static List<InventoryConsolidation.Stack> initial() {
        var items=new ArrayList<>(Collections.nCopies(36,EMPTY));
        items.set(4,new InventoryConsolidation.Stack("protected_hoe",1,1));
        items.set(6,stack("torch",55));
        items.set(18,stack("tomato",2));
        items.set(19,stack("tomato",1));
        return items;
    }

    private static InventoryConsolidation transaction(List<InventoryConsolidation.Stack> items,boolean direct) {
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("minecraft:torch",55,0,null,false,0));
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.TOMATO,
            direct ? 6 : 18,direct ? -1 : 6,List.of(19),visible),snapshot(items));
    }

    private static void swapOut(InventoryConsolidation operation,List<InventoryConsolidation.Stack> items) {
        Collections.swap(items,18,6);
        assertEquals(NEXT,operation.acknowledge(snapshot(items)));
    }

    private static void merge(InventoryConsolidation operation,List<InventoryConsolidation.Stack> items) {
        items.set(6,EMPTY); items.set(19,stack("tomato",3));
        assertEquals(NEXT,operation.acknowledge(snapshot(items)));
    }

    private static void unchanged(InventoryConsolidation operation,InventoryConsolidation.Snapshot before,
                                  InventoryConsolidation.Click next) {
        assertEquals(before,operation.expectedLive());
        assertEquals(next,operation.click());
        assertFalse(operation.complete());
    }

    @Test void provenPickupInMaterialSlotPreservesPendingExactRestore() {
        var items=initial(); var operation=transaction(items,false);
        swapOut(operation,items); merge(operation,items);
        var restore=operation.click();
        items.set(5,stack("wine{Year:9}",1));
        assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(5)));
        assertEquals(snapshot(items),operation.expectedLive());
        assertEquals(restore,operation.click());
        assertFalse(operation.complete());
        assertEquals(WAIT,operation.acknowledge(snapshot(items)),"A rebase is not a restore ACK");
        Collections.swap(items,18,6);
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(stack("torch",55),operation.expectedLive().items().get(6));
        assertEquals(stack("wine{Year:9}",1),operation.expectedLive().items().get(5));
    }

    @Test void missingProofOrAnAdditionalUnprovedCountChangeIsAtomic() {
        var items=initial(); var operation=transaction(items,false);
        swapOut(operation,items); merge(operation,items);
        var before=operation.expectedLive(); var next=operation.click();
        items.set(5,stack("wine{Year:9}",1));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of()));
        items.set(19,stack("tomato",4));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(5)));
        unchanged(operation,before,next);
    }

    @Test void nullAndMalformedProofNeverMutateTheBaseline() {
        var items=initial(); var operation=transaction(items,false);
        swapOut(operation,items); merge(operation,items);
        var before=operation.expectedLive(); var next=operation.click();
        items.set(5,stack("wine{Year:9}",1));
        assertFalse(operation.rebaseVerifiedUpdates(null,Set.of(5)));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),null));
        for(int invalid:new int[]{-1,36,Integer.MAX_VALUE})
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(5,invalid)));
        Set<Integer> nullIndex=new HashSet<>(); nullIndex.add(5); nullIndex.add(null);
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),nullIndex));
        unchanged(operation,before,next);
    }

    @Test void originalSourceAndScratchAreNeverRebasedEvenWithProof() {
        for(boolean beforeMerge:new boolean[]{true,false}) {
            for(int protectedIndex:new int[]{18,6}) {
                var items=initial(); var operation=transaction(items,false);
                swapOut(operation,items); if(!beforeMerge) merge(operation,items);
                var before=operation.expectedLive(); var next=operation.click();
                assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(protectedIndex)),
                    "Even an unchanged participating slot cannot be exempted");
                items.set(protectedIndex,stack("replacement",1));
                assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(protectedIndex)));
                unchanged(operation,before,next);
            }
        }
    }

    @Test void beforeMergeEveryImplicitReceiverIsProtectedNotOnlyAdvertisedDestinations() {
        for(int receiver:new int[]{9,19,35}) {
            var items=initial(); var operation=transaction(items,false); swapOut(operation,items);
            var before=operation.expectedLive(); var next=operation.click();
            items.set(receiver,stack("wine{Year:9}",1));
            assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(receiver)));
            unchanged(operation,before,next);
        }
    }

    @Test void unrelatedHotbarRefreshBeforeMergeStillRequiresConservedMoveAndExactRestore() {
        var items=initial(); var operation=transaction(items,false); swapOut(operation,items);
        items.set(5,stack("wine{Year:9}",1));
        assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(5)));
        assertEquals(InventoryConsolidation.Type.QUICK_MOVE,operation.click().type());
        var wrong=new ArrayList<>(items); wrong.set(6,EMPTY); wrong.set(19,stack("tomato",4));
        assertEquals(WAIT,operation.acknowledge(snapshot(wrong)),"Rebase cannot excuse count inflation");
        merge(operation,items);
        Collections.swap(items,18,6);
        var stale=new ArrayList<>(items); stale.set(5,EMPTY);
        assertEquals(WAIT,operation.acknowledge(snapshot(stale)),"The refreshed identity is the next exact baseline");
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
    }

    @Test void initialSwapDirectFirstMergeAndDoneNeverPermitRebase() {
        var items=initial(); var operation=transaction(items,false);
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of()));
        items.set(5,stack("wine{Year:9}",1));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        assertFalse(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(5)),"WAIT is not a primitive ACK");

        var directItems=initial(); directItems.set(6,stack("tomato",2)); directItems.set(18,EMPTY);
        var direct=transaction(directItems,true);
        directItems.set(5,stack("wine{Year:9}",1));
        assertFalse(direct.rebaseVerifiedUpdates(snapshot(directItems),Set.of(5)),
            "A direct first MERGE has no completed primitive to refresh");
        directItems.set(5,EMPTY); directItems.set(6,EMPTY); directItems.set(19,stack("tomato",3));
        assertEquals(COMPLETE,direct.acknowledge(snapshot(directItems)));
        var completed=direct.expectedLive(); directItems.set(5,stack("wine{Year:9}",1));
        assertFalse(direct.rebaseVerifiedUpdates(snapshot(directItems),Set.of(5)));
        assertEquals(completed,direct.expectedLive());
        assertTrue(direct.complete());
    }

    @Test void identicalSnapshotCanRefreshButCannotAdvanceOrAuthorizeAnUnperformedClick() {
        var items=initial(); var operation=transaction(items,false); swapOut(operation,items);
        var merge=operation.click();
        assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of()));
        assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(5)));
        assertEquals(merge,operation.click());
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        merge(operation,items);
        var restore=operation.click();
        assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of()));
        assertEquals(restore,operation.click());
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        assertFalse(operation.complete());
    }
}
