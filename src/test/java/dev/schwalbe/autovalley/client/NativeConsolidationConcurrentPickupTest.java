package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class NativeConsolidationConcurrentPickupTest {
    private static Stack item(String id,int count,String tags) {
        return new Stack("{Count:1b,id:\""+id+"\",tag:"+tags+"}",count,64);
    }
    private static Stack tomato(int count) { return item(ItemData.TOMATO,count,"{quality:1}"); }
    private static Stack wine(int count) { return item(ItemData.WINE,count,"{Year:10}"); }
    private static List<Stack> initial() {
        var items=new ArrayList<>(Collections.nCopies(36,Stack.EMPTY));
        items.set(6,item("minecraft:torch",55,"{}"));
        items.set(18,tomato(2));items.set(19,tomato(1));
        return items;
    }
    private static InventoryConsolidation transaction(List<Stack> items) {
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("minecraft:torch",55,0,null,false,0));
        return new InventoryConsolidation(new ProductionMergePlanner.Plan(
            Feature.WINE,ItemData.TOMATO,18,6,List.of(19),visible),new Snapshot(items));
    }
    private static Set<Integer> classify(InventoryConsolidation transaction,List<Stack> after) {
        return NativeInventoryConsolidation.concurrentProductionAdditions(transaction,new Snapshot(after),4);
    }
    private static void swapOut(InventoryConsolidation transaction,List<Stack> items) {
        Collections.swap(items,18,6);
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
    }
    @Test void actualFullMergeReplyClassifiesOnlyTheUnrelatedWineAndRequiresRestore() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        items.set(6,Stack.EMPTY);items.set(19,tomato(3));items.set(5,wine(1));
        assertEquals(Set.of(5),classify(transaction,items),"The receiver increase belongs to the primitive, not the pickup exception");
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items)));
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
        assertEquals(new Click(Type.SWAP,18,6),transaction.click());
        Collections.swap(items,18,6);
        assertEquals(COMPLETE,transaction.acknowledge(new Snapshot(items)));
    }
    @Test void sameFullPacketStillCannotAcknowledgeAnUnperformedPrimitive() {
        var items=initial();var transaction=transaction(items);swapOut(transaction,items);
        var before=transaction.expectedLive();var click=transaction.click();
        items.set(5,wine(1));
        assertEquals(Set.of(5),classify(transaction,items));
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items),Set.of(),classify(transaction,items)));
        assertEquals(before,transaction.expectedLive());assertEquals(click,transaction.click());
    }
    @Test void everyImplicitReceiverAndProtectedHoeSlotStayOutsideTheException() {
        for (int index:new int[]{4,6,9,18,19,35}) {
            var items=initial();var transaction=transaction(items);swapOut(transaction,items);
            items.set(index,wine(1));
            assertFalse(classify(transaction,items).contains(index));
        }
    }
    @Test void nativeItemWhitelistIsNotAnyPositiveInventoryChange() {
        for (String id:List.of(ItemData.TOMATO,ItemData.WINE,ItemData.PRESERVES,ItemData.ROTTEN,ItemData.PINE_TAR,"minecraft:torch")) {
            var items=initial();var transaction=transaction(items);swapOut(transaction,items);
            items.set(5,item(id,1,"{}"));
            boolean allowed=Set.of(ItemData.TOMATO,ItemData.WINE,ItemData.PRESERVES).contains(id);
            assertEquals(allowed,classify(transaction,items).contains(5),id);
        }
    }
    @Test void replacementsCountLossAndDifferentNativeIdentityAreNotClassifiedAsPickups() {
        for (Stack changed:List.of(Stack.EMPTY,wine(1),item(ItemData.WINE,3,"{Year:9}"),
            item(ItemData.WINE,3,"{Year:10,quality:1}"),item(ItemData.PRESERVES,3,"{}"))) {
            var items=initial();items.set(5,wine(2));var transaction=transaction(items);swapOut(transaction,items);
            items.set(5,changed);assertFalse(classify(transaction,items).contains(5));
        }
        var items=initial();items.set(5,wine(2));var transaction=transaction(items);swapOut(transaction,items);
        items.set(5,wine(3));assertEquals(Set.of(5),classify(transaction,items));
    }
}
