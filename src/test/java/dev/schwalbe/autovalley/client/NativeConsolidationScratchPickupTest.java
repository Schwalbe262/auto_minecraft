package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** Detached packet identities only; never creates a client or sends a click. */
class NativeConsolidationScratchPickupTest {
    private static Stack item(String id,int count,String tag) {
        return new Stack("{Count:1b,id:\""+id+"\",tag:"+tag+"}",count,64);
    }
    private static Stack wine(int count) { return item(ItemData.WINE,count,"{Year:10}"); }
    private static final Stack TORCH=item("minecraft:torch",55,"{}");
    private static List<Stack> initial() {
        var items=new ArrayList<>(Collections.nCopies(36,Stack.EMPTY));
        items.set(6,TORCH);items.set(9,wine(1));items.set(10,wine(1));return items;
    }
    private static InventoryConsolidation transaction(List<Stack> items) {
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("minecraft:torch",55,0,null,false,0));
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(
            Feature.WINE,ItemData.WINE,9,6,List.of(10),visible),new Snapshot(items));
    }
    private static void merge(InventoryConsolidation transaction,List<Stack> items) {
        Collections.swap(items,9,6);assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
        items.set(6,Stack.EMPTY);items.set(10,wine(2));
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
    }
    private static boolean rebase(InventoryConsolidation transaction,List<Stack> items,Map<Integer,Stack> raw) {
        var old=transaction.expectedLive();
        var changed=NativeInventoryConsolidation.matchingServerSlotUpdates(old.items(),items,raw);
        if (changed==null || changed.isEmpty()) return false;
        var additions=new HashSet<Integer>();
        for (int index:changed) {
            if (index==4 || !NativeInventoryConsolidation.productionAddition(old.items().get(index),items.get(index))) return false;
            additions.add(index);
        }
        return transaction.rebaseVerifiedUpdates(new Snapshot(items),changed,additions);
    }
    @Test void actualPostMergeScratchPickupSurvivesExactInverseSwapWithoutReplayingMerge() {
        var items=initial();var transaction=transaction(items);merge(transaction,items);
        items.set(6,wine(1));assertTrue(rebase(transaction,items,Map.of(6,wine(1))));
        assertEquals(new Click(Type.SWAP,9,6),transaction.click());
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items)),"A pickup is not the restore ACK");
        Collections.swap(items,9,6);
        assertEquals(COMPLETE,transaction.acknowledge(new Snapshot(items)));
        assertEquals(TORCH,transaction.expectedLive().items().get(6));
        assertEquals(wine(1),transaction.expectedLive().items().get(9));
        assertEquals(wine(2),transaction.expectedLive().items().get(10));
        assertEquals(0,transaction.freedSlots(),"A refilled free slot is not reported as freed");
    }
    @Test void missingOrStaleRawPacketCannotAuthorizeLiveScratchPickup() {
        for (var raw:List.of(Map.<Integer,Stack>of(),Map.of(6,item(ItemData.WINE,1,"{}")),Map.of(6,wine(2)))) {
            var items=initial();var transaction=transaction(items);merge(transaction,items);
            var before=transaction.expectedLive();items.set(6,wine(1));
            assertFalse(rebase(transaction,items,raw));assertEquals(before,transaction.expectedLive());
        }
    }
    @Test void scratchPickupRequiresWhitelistedProductionItem() {
        for (String id:List.of(ItemData.WINE,ItemData.TOMATO,ItemData.PRESERVES,ItemData.ROTTEN,ItemData.PINE_TAR,"minecraft:torch")) {
            var items=initial();var transaction=transaction(items);merge(transaction,items);
            var added=item(id,1,"{}");items.set(6,added);
            assertEquals(Set.of(ItemData.WINE,ItemData.TOMATO,ItemData.PRESERVES).contains(id),
                rebase(transaction,items,Map.of(6,added)),id);
        }
    }
    @Test void sourceReplacementStillFailsDespiteARealPickupPacket() {
        var items=initial();var transaction=transaction(items);merge(transaction,items);
        var before=transaction.expectedLive();items.set(6,wine(1));items.set(9,wine(55));
        assertFalse(rebase(transaction,items,Map.of(6,wine(1),9,wine(55))));
        assertEquals(before,transaction.expectedLive());
    }
    @Test void rawPositivePickupInScratchBeforeMergeIsNotAllowed() {
        var items=initial();var transaction=transaction(items);
        Collections.swap(items,9,6);assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
        items.set(6,wine(2));assertFalse(rebase(transaction,items,Map.of(6,wine(2))));
        assertEquals(new Click(Type.QUICK_MOVE,6,-1),transaction.click());
    }
}
