package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AncientWineMergePlannerTest {
    private static ItemData item(String id,int count,int quality){return new ItemData(id,count,quality,null,false,99);}
    private static List<ItemSlot> inventory() {
        List<ItemSlot> items=new ArrayList<>();for(int i=0;i<36;i++)items.add(new ItemSlot(i,i,true,item("minecraft:stone",64,0)));
        items.set(4,new ItemSlot(4,4,true,new ItemData("minecraft:golden_hoe",1,0,null,true,500)));return items;
    }
    private static void put(List<ItemSlot> inventory,int at,ItemData item){inventory.set(at,new ItemSlot(at,at,true,item));}
    @Test void plainAncientWineWithoutVineryYearCanMergeWithoutMovingTheIngredientHand() {
        var inv=inventory();put(inv,5,item(ItemData.ANCIENT_FRUIT,40,2));
        put(inv,0,item(ItemData.ANCIENT_WINE,10,0));put(inv,10,item(ItemData.ANCIENT_WINE,50,0));
        var plan=ProductionMergePlanner.planProduct(inv,Feature.WINE,4,ItemData.ANCIENT_WINE,0).orElseThrow();
        assertTrue(plan.direct());assertEquals(List.of(10),plan.destinations());assertTrue(ProductionMergePlanner.protectsProductionSlots(plan,4));
        assertTrue(ProductionMergePlanner.planProduct(inv,Feature.WINE,4,"other:wine",null).isEmpty());
    }
    @Test void ancientIngredientMergeKeepsItsAllocatedGradeAndCannotRunAsTomatoPreserves() {
        var inv=inventory();put(inv,0,item(ItemData.ANCIENT_FRUIT,2,2));put(inv,10,item(ItemData.ANCIENT_FRUIT,61,2));
        assertTrue(ProductionMergePlanner.planIngredient(inv,Feature.WINE,4,ItemData.ANCIENT_FRUIT,2,0).isPresent());
        assertTrue(ProductionMergePlanner.planIngredient(inv,Feature.WINE,4,ItemData.ANCIENT_FRUIT,1,0).isEmpty());
        assertTrue(ProductionMergePlanner.planIngredient(inv,Feature.PRESERVES,4,ItemData.ANCIENT_FRUIT,2,0).isEmpty());
    }
    @Test void ancientFruitCannotBeBorrowedAsAnOutputMergeScratchSlot() {
        var inv=inventory();for(int i=0;i<9;i++)if(i!=4)put(inv,i,item(ItemData.ANCIENT_FRUIT,64,0));
        put(inv,9,item(ItemData.ANCIENT_WINE,10,0));put(inv,10,item(ItemData.ANCIENT_WINE,50,0));
        assertTrue(ProductionMergePlanner.planProduct(inv,Feature.WINE,4,ItemData.ANCIENT_WINE,9).isEmpty());
    }
}
