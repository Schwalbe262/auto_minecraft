package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeConsolidationPreMergePickupTest {
    private static InventoryConsolidation.Stack wine(int n) {
        return new InventoryConsolidation.Stack("{Count:1b,id:\"vinery:stal_wine\",tag:{Year:13}}",n,64);
    }
    private static InventoryConsolidation operation() {
        var items=new ArrayList<>(Collections.nCopies(36,InventoryConsolidation.Stack.EMPTY));
        items.set(6,new InventoryConsolidation.Stack("torch",62,64));
        items.set(9,wine(1));items.set(10,wine(30));
        var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        visible.set(6,new ItemData("minecraft:torch",62,0,null,false,0));
        var operation=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,
                9,6,List.of(10),visible),new InventoryConsolidation.Snapshot(items));
        Collections.swap(items,6,9);
        assertEquals(InventoryConsolidation.Confirmation.NEXT,operation.acknowledge(new InventoryConsolidation.Snapshot(items)));
        return operation;
    }
    @Test void actualReceiverPacketCanRefreshBeforeTheUnsentMoveButCannotClassifyAsConcurrentAckAddition() {
        var operation=operation();var before=operation.expectedLive();
        assertTrue(NativeInventoryConsolidation.preMergeReceiverAddition(operation,13,wine(1)));
        var items=new ArrayList<>(before.items());items.set(13,wine(1));
        assertTrue(NativeInventoryConsolidation.productionAddition(before.items().get(13),wine(1)));
        assertTrue(NativeInventoryConsolidation.concurrentProductionAdditions(operation,
                new InventoryConsolidation.Snapshot(items),4).isEmpty(),"Same-kind pickup must not be excused in a move ACK");
        assertFalse(operation.rebaseVerifiedUpdates(new InventoryConsolidation.Snapshot(items),Set.of(13),Set.of(13)));
        assertTrue(operation.rebaseVerifiedUpdates(new InventoryConsolidation.Snapshot(items),Set.of(13),Set.of(13),Set.of(13)));
        assertEquals(InventoryConsolidation.Confirmation.WAIT,operation.acknowledge(new InventoryConsolidation.Snapshot(items)));
        items.set(6,InventoryConsolidation.Stack.EMPTY);items.set(10,wine(31));
        assertEquals(InventoryConsolidation.Confirmation.NEXT,operation.acknowledge(new InventoryConsolidation.Snapshot(items)));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,13,wine(2)),"RESTORE is not a pending merge");
        Collections.swap(items,6,9);
        assertEquals(InventoryConsolidation.Confirmation.COMPLETE,operation.acknowledge(new InventoryConsolidation.Snapshot(items)));
    }
    @Test void packetMustExactlyMatchTheCurrentCountIdentityAndSlot() {
        var before=List.of(InventoryConsolidation.Stack.EMPTY);var live=List.of(wine(1));
        assertEquals(Set.of(0),NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,wine(1))));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of()));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,wine(2))));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(1,wine(1))));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,
                Map.of(0,new InventoryConsolidation.Stack("different year",1,64))));
    }
    @Test void receiverCandidateRequiresSameNativeSourceKindAndOppositeRegion() {
        var operation=operation();
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,5,wine(1)));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,6,wine(1)));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,13,new InventoryConsolidation.Stack("other",1,64)));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,13,new InventoryConsolidation.Stack(wine(1).identity(),1,16)));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,13,InventoryConsolidation.Stack.EMPTY));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,13,null));
        assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(null,13,wine(1)));
        for(int index:new int[]{-1,36,Integer.MAX_VALUE})
            assertFalse(NativeInventoryConsolidation.preMergeReceiverAddition(operation,index,wine(1)));
    }
}
