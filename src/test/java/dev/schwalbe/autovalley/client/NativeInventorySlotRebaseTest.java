package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import dev.schwalbe.autovalley.core.CrystalCollection;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeInventorySlotRebaseTest {
    private static Stack wine(int count) { return item(ItemData.WINE,count,"{Year:9}"); }
    private static Stack item(String id,int count,String tag) {
        return new Stack("{Count:1b,id:\""+id+"\",tag:"+tag+"}",count,64);
    }
    @Test void onlyExactChangedSlotsRequireTheirOwnServerPacketEvidence() {
        var before=List.of(Stack.EMPTY,wine(4),Stack.EMPTY);
        var live=List.of(wine(1),wine(4),Stack.EMPTY);
        assertEquals(Set.of(0),NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,wine(1))));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of()));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(1,wine(1))));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,wine(2))));
    }
    @Test void aClientMenuChangeCannotBorrowAnotherSlotsPacketAsProof() {
        var before=List.of(Stack.EMPTY,Stack.EMPTY);
        var live=List.of(wine(1),wine(2));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,wine(1))));
        assertEquals(Set.of(0,1),NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,wine(1),1,wine(2))));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,List.of(wine(1)),Map.of(0,wine(1))));
    }
    @Test void yearInitializationNeedsTheNewestExactPacketNotTheEarlierPickup() {
        Stack unknown=item(ItemData.WINE,1,"{}");
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(List.of(Stack.EMPTY),List.of(wine(1)),Map.of(0,unknown)));
        assertEquals(Set.of(0),NativeInventoryConsolidation.matchingServerSlotUpdates(List.of(Stack.EMPTY),List.of(wine(1)),Map.of(0,wine(1))));
    }
    @Test void additionsAreRestrictedToProductionItemsAndExactExistingIdentity() {
        for(String id:List.of(ItemData.TOMATO,ItemData.WINE,ItemData.PRESERVES)) {
            assertTrue(NativeInventoryConsolidation.productionAddition(Stack.EMPTY,item(id,1,"{}")));
            assertTrue(NativeInventoryConsolidation.productionAddition(item(id,2,"{}"),item(id,3,"{}")));
        }
        assertFalse(NativeInventoryConsolidation.productionAddition(Stack.EMPTY,item("minecraft:torch",1,"{}")));
        assertFalse(NativeInventoryConsolidation.productionAddition(wine(1),wine(1)));
        assertFalse(NativeInventoryConsolidation.productionAddition(wine(2),wine(1)));
        assertFalse(NativeInventoryConsolidation.productionAddition(wine(1),Stack.EMPTY));
        assertFalse(NativeInventoryConsolidation.productionAddition(wine(1),item(ItemData.WINE,2,"{Year:8}")));
        assertFalse(NativeInventoryConsolidation.productionAddition(wine(1),item(ItemData.WINE,2,"{Year:9,quality:1}")));
        assertFalse(NativeInventoryConsolidation.productionAddition(wine(1),new Stack(wine(1).identity(),2,16)));
        assertFalse(NativeInventoryConsolidation.productionAddition(Stack.EMPTY,new Stack("malformed",1,64)));
    }

    @Test void everyCrystalOutputAdditionStillRequiresPositiveCountsAndFullExistingNativeIdentity() {
        assertEquals(112,CrystalCollection.OUTPUT_IDS.size());
        for(String id:CrystalCollection.OUTPUT_IDS) {
            Stack before=item(id,2,"{quality:1,custom:{marker:3}}"),after=item(id,3,"{quality:1,custom:{marker:3}}");
            assertTrue(NativeInventoryConsolidation.productionAddition(Stack.EMPTY,after),id);
            assertTrue(NativeInventoryConsolidation.productionAddition(before,after),id);
            assertFalse(NativeInventoryConsolidation.productionAddition(before,before),id);
            assertFalse(NativeInventoryConsolidation.productionAddition(after,before),id);
            assertFalse(NativeInventoryConsolidation.productionAddition(before,item(id,3,"{quality:2,custom:{marker:3}}")),id);
            assertFalse(NativeInventoryConsolidation.productionAddition(before,item(id,3,"{quality:1,custom:{marker:4}}")),id);
            assertFalse(NativeInventoryConsolidation.productionAddition(before,new Stack(after.identity(),3,16)),id);
            assertFalse(NativeInventoryConsolidation.productionAddition(before,item("society:jade".equals(id)?"society:ruby":"society:jade",3,"{quality:1,custom:{marker:3}}")),id);
            Stack differentCaps=new Stack(after.identity().substring(0,after.identity().length()-1)+",ForgeCaps:{extra:1}}",3,64);
            assertFalse(NativeInventoryConsolidation.productionAddition(before,differentCaps),id);
        }
        for(String id:List.of("other:ruby","society:ruby_extra","society:pristine_torch","minecraft:bread"))
            assertFalse(NativeInventoryConsolidation.productionAddition(Stack.EMPTY,item(id,1,"{}")),id);
    }

    @Test void recognizingNonJadeCrystalsDoesNotSupplyMissingOrWrongServerSlotProof() {
        Stack ruby=item("society:ruby",2,"{quality:1}");
        var before=List.of(Stack.EMPTY,Stack.EMPTY);var live=List.of(ruby,Stack.EMPTY);
        assertTrue(NativeInventoryConsolidation.productionAddition(Stack.EMPTY,ruby));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of()));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(1,ruby)));
        assertNull(NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,item("society:ruby",2,"{quality:2}"))));
        assertEquals(Set.of(0),NativeInventoryConsolidation.matchingServerSlotUpdates(before,live,Map.of(0,ruby)));
    }
}
