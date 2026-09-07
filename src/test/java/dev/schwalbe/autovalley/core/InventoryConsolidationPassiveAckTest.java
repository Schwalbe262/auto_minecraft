package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class InventoryConsolidationPassiveAckTest {
    private static InventoryConsolidation.Stack stack(String identity,int count) {
        return new InventoryConsolidation.Stack(identity,count,64);
    }
    private static InventoryConsolidation.Stack wine(int count) { return stack("wine{Year:9}",count); }
    private static List<InventoryConsolidation.Stack> initial() {
        var items=new ArrayList<>(Collections.nCopies(36,InventoryConsolidation.Stack.EMPTY));
        items.set(4,new InventoryConsolidation.Stack("protected_hoe",1,1));
        items.set(5,stack("tomato{Quality:2}",30));
        items.set(9,wine(1)); items.set(10,wine(60));
        items.set(11,stack("wine{Year:9,Cache:old}",2));
        return items;
    }
    private static InventoryConsolidation.Snapshot snapshot(List<InventoryConsolidation.Stack> items) {
        return new InventoryConsolidation.Snapshot(items);
    }
    private static InventoryConsolidation operation(List<InventoryConsolidation.Stack> items,boolean direct) {
        var visible=items.stream().map(s -> s.empty() ? ItemData.EMPTY : new ItemData(ItemData.WINE,s.count(),0,9,false,999)).toList();
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,
            direct ? 5 : 9,direct ? -1 : 5,List.of(10),visible),snapshot(items));
    }
    private static void passiveUpdate(List<InventoryConsolidation.Stack> items) {
        items.set(11,stack("wine{Year:9,Cache:new}",2));
    }

    @Test void defaultAndUnverifiedMetadataDifferencesRemainStrict() {
        var items=initial(); var transaction=operation(items,false); var before=snapshot(items);
        Collections.swap(items,9,5); passiveUpdate(items);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items)));
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of()));
        assertEquals(before,transaction.expectedLive());
        assertEquals(NEXT,transaction.acknowledge(snapshot(items),Set.of(11)));
        assertEquals(snapshot(items),transaction.expectedLive());
    }

    @Test void swapSourceAndScratchCannotBeExemptedEvenAtUnchangedCounts() {
        for(int touched:new int[]{5,9}) {
            var items=initial(); var transaction=operation(items,false);
            Collections.swap(items,9,5);
            var item=items.get(touched); items.set(touched,stack(item.identity()+"{Changed:true}",item.count()));
            assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(touched)));
            assertEquals(InventoryConsolidation.Type.SWAP,transaction.click().type());
        }
    }

    @Test void passiveProofCannotCoverCountLimitOrEmptySlotChanges() {
        for(var replacement:List.of(stack("wine{Year:9,Cache:new}",3),
                new InventoryConsolidation.Stack("wine{Year:9,Cache:new}",2,16),InventoryConsolidation.Stack.EMPTY)) {
            var items=initial(); var transaction=operation(items,false);
            Collections.swap(items,9,5); items.set(11,replacement);
            assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11)));
        }
        var items=initial(); items.set(11,InventoryConsolidation.Stack.EMPTY);
        var transaction=operation(items,false); Collections.swap(items,9,5); passiveUpdate(items);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11)));
    }

    @Test void malformedProofIndicesAndNullProofNeverAdvance() {
        var items=initial(); var transaction=operation(items,false); Collections.swap(items,9,5); passiveUpdate(items);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),null));
        for(int invalid:new int[]{-1,36,Integer.MAX_VALUE})
            assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(invalid)));
        Set<Integer> nullIndex=new HashSet<>(); nullIndex.add(null);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),nullIndex));
        assertEquals(NEXT,transaction.acknowledge(snapshot(items),Set.of(11)));
    }

    @Test void metadataAloneCannotAcknowledgeANoopSwapOrAnUnperformedMerge() {
        for(boolean identicalPartners:new boolean[]{false,true}) {
            var items=initial(); if(identicalPartners) items.set(5,items.get(9));
            var transaction=operation(items,false); var before=snapshot(items); passiveUpdate(items);
            assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11)));
            assertEquals(before,transaction.expectedLive());
        }
        var items=initial(); items.set(5,wine(1)); var transaction=operation(items,true); passiveUpdate(items);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11)));
        assertFalse(transaction.complete());
    }

    @Test void rawAcknowledgementBecomesTheNextExactBaselineIncludingRestoration() {
        var items=initial(); var transaction=operation(items,false);
        Collections.swap(items,9,5); passiveUpdate(items);
        assertEquals(NEXT,transaction.acknowledge(snapshot(items),Set.of(11)));
        assertEquals(snapshot(items),transaction.expectedLive());
        items.set(5,InventoryConsolidation.Stack.EMPTY); items.set(10,wine(61));
        var stale=new ArrayList<>(items); stale.set(11,stack("wine{Year:9,Cache:old}",2));
        assertEquals(WAIT,transaction.acknowledge(snapshot(stale)));
        assertEquals(NEXT,transaction.acknowledge(snapshot(items)));
        var wrong=new ArrayList<>(items); Collections.swap(wrong,9,5); wrong.set(5,stack("tomato{Quality:2}",31));
        assertEquals(WAIT,transaction.acknowledge(snapshot(wrong),Set.of(5)));
        Collections.swap(items,9,5);
        assertEquals(COMPLETE,transaction.acknowledge(snapshot(items)));
        assertEquals(stack("tomato{Quality:2}",30),transaction.expectedLive().items().get(5));
        assertEquals(1,transaction.freedSlots());
    }

    @Test void conservedMergeAllowsOnlyItsProvenUnchangedCountBystander() {
        var items=initial(); items.set(5,wine(1)); var transaction=operation(items,true);
        items.set(5,InventoryConsolidation.Stack.EMPTY); items.set(10,wine(61)); passiveUpdate(items);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items)));
        assertEquals(COMPLETE,transaction.acknowledge(snapshot(items),Set.of(11)));
        assertEquals(snapshot(items),transaction.expectedLive()); assertEquals(1,transaction.freedSlots());
    }

    @Test void mergeSourceReceiverAndUnprovedAdditionalChangesRemainRejected() {
        for(int touched:new int[]{5,10}) {
            var items=initial(); items.set(5,wine(1)); var transaction=operation(items,true);
            items.set(5,InventoryConsolidation.Stack.EMPTY); items.set(10,wine(61)); passiveUpdate(items);
            assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11,touched)));
        }
        var items=initial(); items.set(5,wine(1)); var transaction=operation(items,true);
        items.set(5,InventoryConsolidation.Stack.EMPTY); items.set(10,wine(62)); passiveUpdate(items);
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11)),"Passive proof cannot disguise count inflation");
        items.set(10,wine(61)); items.set(4,new InventoryConsolidation.Stack("changed_hoe",1,1));
        assertEquals(WAIT,transaction.acknowledge(snapshot(items),Set.of(11)),"An unproved protected tool change stays unsafe");
    }
}
