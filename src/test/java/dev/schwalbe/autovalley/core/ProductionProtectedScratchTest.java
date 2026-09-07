package dev.schwalbe.autovalley.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class ProductionProtectedScratchTest {
    @Test void actualFullHotbarPlanRestoresAlternateSlotAndNeverMovesMaterialAcrossThreeAcks() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            String productId=feature==Feature.WINE ? ItemData.WINE : ItemData.PRESERVES;
            Integer year=feature==Feature.WINE ? 8 : null;
            ItemData stone=new ItemData("minecraft:stone",64,0,null,false,999);
            ItemData hoe=new ItemData("minecraft:golden_hoe",1,0,null,true,100);
            ItemData tomatoes=new ItemData(ItemData.TOMATO,52,2,null,false,999);
            List<ItemSlot> visible=new ArrayList<>();
            for (int index=0;index<36;index++) visible.add(new ItemSlot(index,index,true,stone));
            visible.set(4,new ItemSlot(4,4,true,hoe)); visible.set(5,new ItemSlot(5,5,true,tomatoes));
            visible.set(9,new ItemSlot(9,9,true,new ItemData(productId,1,0,year,false,999)));
            visible.set(10,new ItemSlot(10,10,true,new ItemData(productId,60,0,year,false,999)));
            ProductionMergePlanner.Plan plan=ProductionMergePlanner.plan(visible,feature,4,9).orElseThrow();
            assertEquals(6,plan.scratchHotbar()); assertTrue(plan.requiresRestore());
            assertTrue(ProductionMergePlanner.protectsProductionSlots(plan,4));

            var items=new ArrayList<>(visible.stream().map(slot -> nativeStack(slot.item())).toList());
            var material=items.get(5); var protectedHoe=items.get(4); var displaced=items.get(6);
            String productIdentity=items.get(9).identity();
            InventoryConsolidation transaction=new InventoryConsolidation(plan,new InventoryConsolidation.Snapshot(items));
            assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.SWAP,9,6),transaction.click());
            Collections.swap(items,9,6);
            assertEquals(NEXT,transaction.acknowledge(new InventoryConsolidation.Snapshot(items)));
            assertEquals(material,items.get(5)); assertEquals(protectedHoe,items.get(4));
            assertEquals(displaced,items.get(9));

            assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.QUICK_MOVE,6,-1),transaction.click());
            items.set(6,InventoryConsolidation.Stack.EMPTY);
            items.set(10,new InventoryConsolidation.Stack(productIdentity,61,64));
            assertEquals(NEXT,transaction.acknowledge(new InventoryConsolidation.Snapshot(items)));
            assertEquals(material,items.get(5)); assertEquals(protectedHoe,items.get(4));
            assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.SWAP,9,6),transaction.click());

            var wrongRestore=new ArrayList<>(items);
            wrongRestore.set(9,new InventoryConsolidation.Stack(displaced.identity(),63,64));
            Collections.swap(wrongRestore,9,6);
            assertEquals(WAIT,transaction.acknowledge(new InventoryConsolidation.Snapshot(wrongRestore)));
            Collections.swap(items,9,6);
            assertEquals(COMPLETE,transaction.acknowledge(new InventoryConsolidation.Snapshot(items)));
            assertEquals(material,items.get(5)); assertEquals(protectedHoe,items.get(4));
            assertEquals(displaced,items.get(6)); assertTrue(items.get(9).empty());
            assertEquals(61,items.stream().filter(item -> item.identity().equals(productIdentity)).mapToInt(InventoryConsolidation.Stack::count).sum());
            assertEquals(1,transaction.freedSlots());
        }
    }

    private static InventoryConsolidation.Stack nativeStack(ItemData item) {
        return new InventoryConsolidation.Stack(item.id()+"|"+item.quality()+"|"+item.year()+"|opaque-native-tags",item.count(),item.hoe()?1:64);
    }
}
