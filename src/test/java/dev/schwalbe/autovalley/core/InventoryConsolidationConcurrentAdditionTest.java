package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class InventoryConsolidationConcurrentAdditionTest {
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
        items.set(18,stack("tomato",2)); items.set(19,stack("tomato",1));
        return items;
    }

    private static InventoryConsolidation transaction(List<InventoryConsolidation.Stack> items) {
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("minecraft:torch",55,0,null,false,0));
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.TOMATO,
            18,6,List.of(19),visible),snapshot(items));
    }

    private static void swapOut(InventoryConsolidation operation,List<InventoryConsolidation.Stack> items) {
        Collections.swap(items,18,6);
        assertEquals(NEXT,operation.acknowledge(snapshot(items)));
    }

    private static void moved(List<InventoryConsolidation.Stack> items) {
        items.set(6,EMPTY); items.set(19,stack("tomato",3));
    }

    @Test void actualTwoPlusOneMergeWithNewWineInUntouchedMaterialSlotKeepsExactRestore() {
        var items=initial(); var operation=transaction(items); swapOut(operation,items);
        moved(items); items.set(5,stack("wine{}",1));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)),"The original overload remains strict");
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of()),"Passive-only overload remains strict");
        assertEquals(NEXT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
        assertEquals(snapshot(items),operation.expectedLive());
        assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.SWAP,18,6),operation.click());
        var wrong=new ArrayList<>(items); Collections.swap(wrong,18,6); wrong.set(6,stack("torch",54));
        assertEquals(WAIT,operation.acknowledge(snapshot(wrong)),"Pickup proof does not weaken the remaining swap");
        Collections.swap(items,18,6);
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(stack("torch",55),operation.expectedLive().items().get(6));
        assertEquals(stack("wine{}",1),operation.expectedLive().items().get(5));
    }

    @Test void swapOutAndRestoreMayContainProvenUntouchedAdditions() {
        var items=initial(); var operation=transaction(items);
        Collections.swap(items,18,6); items.set(5,stack("wine{Year:10}",1));
        assertEquals(NEXT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
        moved(items); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        Collections.swap(items,18,6); items.set(5,stack("wine{Year:10}",2));
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
        assertEquals(stack("wine{Year:10}",2),operation.expectedLive().items().get(5));
    }

    @Test void pickupAloneOrRefusedPrimitiveNeverAdvancesOrRebases() {
        for(int phase=0;phase<3;phase++) {
            var items=initial(); var operation=transaction(items);
            if(phase>0) swapOut(operation,items);
            if(phase>1) { moved(items); assertEquals(NEXT,operation.acknowledge(snapshot(items))); }
            var before=operation.expectedLive(); var next=operation.click();
            items.set(5,stack("wine{Year:10}",1));
            assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
            assertEquals(before,operation.expectedLive()); assertEquals(next,operation.click());
            assertFalse(operation.complete());
        }
    }

    @Test void pickupCannotTurnIdenticalPartnersIntoASuccessfulNoopSwap() {
        var items=initial(); items.set(6,items.get(18)); var operation=transaction(items);
        var before=operation.expectedLive(); items.set(5,stack("wine{Year:10}",1));
        Collections.swap(items,18,6);
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
        assertEquals(before,operation.expectedLive());
    }

    @Test void sourceAndScratchStayExcludedAndReceiverRegionRequiresDistinctIdentityEvidence() {
        var items=initial(); var operation=transaction(items);
        for(int index:new int[]{-1,36,18,6}) assertFalse(operation.allowsConcurrentAddition(index));
        assertTrue(operation.allowsConcurrentAddition(19),"An untouched main slot is eligible for a SWAP only");
        swapOut(operation,items);
        for(int index=9;index<36;index++) assertFalse(operation.allowsConcurrentAddition(index));
        assertFalse(operation.allowsConcurrentAddition(6)); assertTrue(operation.allowsConcurrentAddition(5));
        assertTrue(operation.allowsConcurrentAddition(35,stack("wine{Year:10}",1)));
        assertFalse(operation.allowsConcurrentAddition(35,stack("tomato",1)));
        for(int index:new int[]{6,18,19}) {
            var after=new ArrayList<>(items); moved(after); after.set(index,stack("wine{Year:10}",1));
            var before=operation.expectedLive();
            assertEquals(WAIT,operation.acknowledge(snapshot(after),Set.of(),Set.of(index)));
            assertEquals(before,operation.expectedLive());
        }
        moved(items); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        assertTrue(operation.allowsConcurrentAddition(19));
        assertFalse(operation.allowsConcurrentAddition(18)); assertFalse(operation.allowsConcurrentAddition(6));
    }

    @Test void countLossReplacementLimitChangeAndOriginalSourceIdentityAreNotAdditions() {
        for(var replacement:List.of(EMPTY,stack("wine{Year:10}",1),stack("wine{Year:9}",3),
                new InventoryConsolidation.Stack("wine{Year:10}",3,16))) {
            var items=initial(); items.set(5,stack("wine{Year:10}",2));
            var operation=transaction(items); swapOut(operation,items);
            var before=operation.expectedLive(); moved(items); items.set(5,replacement);
            assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
            assertEquals(before,operation.expectedLive());
        }
        var items=initial(); var operation=transaction(items); swapOut(operation,items);
        moved(items); items.set(5,stack("tomato",1));
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)),
            "A movement of the original source identity cannot be hidden as pickup");
    }

    @Test void unprovedDifferencesInflatedReceiversAndMalformedProofsStayAtomic() {
        var items=initial(); var operation=transaction(items); swapOut(operation,items);
        var before=operation.expectedLive(); var next=operation.click();
        moved(items); items.set(5,stack("wine{Year:10}",1));
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of()));
        assertEquals(WAIT,operation.acknowledge(snapshot(items),null,Set.of(5)));
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),null));
        assertEquals(WAIT,operation.acknowledge(null,Set.of(),Set.of(5)));
        for(int invalid:new int[]{-1,36,Integer.MAX_VALUE})
            assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5,invalid)));
        var nullIndex=new HashSet<Integer>(); nullIndex.add(5); nullIndex.add(null);
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),nullIndex));
        items.set(19,stack("tomato",4));
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
        assertEquals(before,operation.expectedLive()); assertEquals(next,operation.click());
    }

    @Test void passiveAndAdditionProofsAreDisjointAndMustBothRetainTheActualPrimitive() {
        var items=initial(); items.set(7,stack("wine{Year:10,Cache:old}",2));
        var operation=transaction(items); swapOut(operation,items);
        moved(items); items.set(5,stack("wine{Year:10}",1)); items.set(7,stack("wine{Year:10,Cache:new}",2));
        var before=operation.expectedLive();
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(7),Set.of(5,7)));
        assertEquals(before,operation.expectedLive());
        assertEquals(NEXT,operation.acknowledge(snapshot(items),Set.of(7),Set.of(5)));
        assertEquals(snapshot(items),operation.expectedLive());
        Collections.swap(items,18,6);
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertFalse(operation.allowsConcurrentAddition(5));
        assertEquals(WAIT,operation.acknowledge(snapshot(items),Set.of(),Set.of(5)));
    }
}
