package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class InventoryConsolidationTest {
    private static InventoryConsolidation.Stack wine(int n) { return stack("wine{Year:8,Quality:1}",n); }
    private static InventoryConsolidation.Stack tomato(int n) { return stack("tomato{Quality:2}",n); }
    private static InventoryConsolidation.Stack stack(String key,int n) {
        return n==0 ? InventoryConsolidation.Stack.EMPTY : new InventoryConsolidation.Stack(key,n,64);
    }
    private static List<InventoryConsolidation.Stack> items() {
        var items=new ArrayList<>(Collections.nCopies(36,InventoryConsolidation.Stack.EMPTY));
        items.set(4,new InventoryConsolidation.Stack("gold_hoe",1,1));
        return items;
    }
    private static InventoryConsolidation.Snapshot snapshot(List<InventoryConsolidation.Stack> items) {
        return new InventoryConsolidation.Snapshot(items);
    }
    private static InventoryConsolidation transaction(List<InventoryConsolidation.Stack> items,int source,int scratch,String id) {
        var visible=items.stream().map(s -> s.empty() ? ItemData.EMPTY : new ItemData(
            s.identity().startsWith("tomato") ? ItemData.TOMATO : ItemData.WINE,s.count(),0,8,false,999)).toList();
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,id,source,scratch,List.of(10),visible),snapshot(items));
    }
    private static List<InventoryConsolidation.Stack> initialProduct() {
        var items=items(); items.set(5,tomato(32)); items.set(9,wine(1)); items.set(10,wine(60)); return items;
    }
    @Test void productThreeClickSequenceConservesRawIdentitiesAndRestoresTomato() {
        var items=initialProduct(); var operation=transaction(items,9,5,ItemData.WINE);
        assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.SWAP,9,5),operation.click());
        Collections.swap(items,9,5); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.QUICK_MOVE,5,-1),operation.click());
        items.set(5,wine(0)); items.set(10,wine(61)); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        Collections.swap(items,9,5); assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(tomato(32),operation.expectedLive().items().get(5)); assertEquals(1,operation.freedSlots());
        assertThrows(IllegalStateException.class,operation::click);
    }
    @Test void emptyScratchNeedsOnlyTwoClicks() {
        var items=initialProduct(); items.set(5,tomato(0)); var operation=transaction(items,9,5,ItemData.WINE);
        Collections.swap(items,9,5); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        items.set(5,wine(0)); items.set(10,wine(61)); assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(1,operation.freedSlots());
    }
    @Test void nativeEmptyFallbackIsNotClaimedAsARealMerge() {
        var items=initialProduct(); items.set(5,tomato(0)); var operation=transaction(items,9,5,ItemData.WINE);
        Collections.swap(items,9,5); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        Collections.swap(items,9,5); assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(0,operation.freedSlots());
    }
    @Test void tomatoScratchMayReceiveMoreTomatoesBeforeRestoration() {
        var items=items(); items.set(5,tomato(60)); items.set(9,tomato(3)); items.set(10,tomato(2));
        var operation=transaction(items,9,5,ItemData.TOMATO);
        Collections.swap(items,9,5); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        items.set(9,tomato(63)); items.set(5,tomato(0)); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        Collections.swap(items,9,5); assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(tomato(63),items.get(5)); assertEquals(1,operation.freedSlots());
    }
    @Test void partialNativeMergeCanRestoreOnlyTheExactlyAcknowledgedRemainder() {
        var items=initialProduct(); items.set(9,wine(8)); var operation=transaction(items,9,5,ItemData.WINE);
        Collections.swap(items,9,5); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        items.set(5,wine(4)); items.set(10,wine(64)); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        var unexpected=new ArrayList<>(items); unexpected.set(9,tomato(33));
        Collections.swap(unexpected,9,5); assertEquals(WAIT,operation.acknowledge(snapshot(unexpected)));
        Collections.swap(items,9,5); assertEquals(COMPLETE,operation.acknowledge(snapshot(items)));
        assertEquals(wine(4),items.get(9)); assertEquals(tomato(32),items.get(5)); assertEquals(0,operation.freedSlots());
    }
    @Test void unchangedRefreshNeverAdvancesOrRepeatsAPrimitive() {
        var items=initialProduct(); var operation=transaction(items,9,5,ItemData.WINE);
        var click=operation.click();
        for(int i=0;i<10;i++) assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        assertEquals(click,operation.click());
        Collections.swap(items,9,5); assertEquals(NEXT,operation.acknowledge(snapshot(items)));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
    }
    @Test void samePublicWineButDifferentNativeTagCannotProveMerge() {
        var items=items(); items.set(5,wine(1)); items.set(10,wine(60)); var operation=transaction(items,5,-1,ItemData.WINE);
        items.set(5,wine(0)); items.set(10,stack("wine{Year:8,Quality:3}",61));
        assertEquals(WAIT,operation.acknowledge(snapshot(items))); assertFalse(operation.complete());
    }
    @Test void unrelatedChangeCannotAcknowledgeSwap() {
        var items=initialProduct(); var operation=transaction(items,9,5,ItemData.WINE);
        Collections.swap(items,9,5); items.set(11,tomato(1));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
    }
    @Test void mergeRejectsCountInflationLossAndUnrelatedMagnetPickup() {
        for (int count:new int[]{60,62}) {
            var items=items(); items.set(5,wine(1)); items.set(10,wine(60)); var operation=transaction(items,5,-1,ItemData.WINE);
            items.set(5,wine(0)); items.set(10,wine(count)); assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        }
        var items=items(); items.set(5,wine(1)); items.set(10,wine(60)); var operation=transaction(items,5,-1,ItemData.WINE);
        items.set(5,wine(0)); items.set(10,wine(61)); items.set(11,tomato(1));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
    }
    @Test void quickMoveCannotMutateSameInventoryRegionOrTools() {
        var items=items(); items.set(5,wine(1)); items.set(6,wine(60)); var operation=transaction(items,5,-1,ItemData.WINE);
        items.set(5,wine(0)); items.set(6,wine(61)); assertEquals(WAIT,operation.acknowledge(snapshot(items)));
        items.set(6,wine(60)); items.set(10,wine(1)); items.set(4,wine(0));
        assertEquals(WAIT,operation.acknowledge(snapshot(items)));
    }
    @Test void directMergeCanDistributeAcrossMultiplePartials() {
        var items=items(); items.set(5,wine(5)); items.set(10,wine(62)); items.set(11,wine(61));
        var operation=transaction(items,5,-1,ItemData.WINE);
        items.set(5,wine(0)); items.set(10,wine(64)); items.set(11,wine(64));
        assertEquals(COMPLETE,operation.acknowledge(snapshot(items))); assertEquals(1,operation.freedSlots());
    }
    @Test void snapshotsAreDetachedAndStackLimitsValidated() {
        var items=initialProduct(); var frozen=snapshot(items); items.clear(); assertEquals(36,frozen.items().size());
        assertThrows(UnsupportedOperationException.class,() -> frozen.items().clear());
        assertThrows(IllegalArgumentException.class,() -> new InventoryConsolidation.Stack("wine",17,16));
        assertThrows(IllegalArgumentException.class,() -> new InventoryConsolidation.Stack("",1,64));
        assertThrows(IllegalArgumentException.class,() -> new InventoryConsolidation.Snapshot(List.of()));
    }
}
