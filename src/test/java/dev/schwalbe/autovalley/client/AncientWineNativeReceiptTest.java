package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AncientWineNativeReceiptTest {
    private static final Pos POS=new Pos(1,64,2);
    private static final Map<String,String> EXTRA=Map.of("facing","west","upgraded","false");
    private static BlockData before(boolean mature) {
        Map<String,String> values=new HashMap<>(EXTRA);values.put("mature",String.valueOf(mature));values.put("working",String.valueOf(mature));
        return new BlockData(POS,"society:wine_keg",values);
    }
    private static NativeWineFeedReceipt.SelectedProof selected(String before,String after,int count,boolean identity) {
        return new NativeWineFeedReceipt.SelectedProof(12,2,true,true,before,64,after,count,identity);
    }
    private static int confirmed(boolean mature,String expected,NativeWineFeedReceipt.SelectedProof selected) {
        return NativeWineFeedReceipt.confirmedCount(POS,before(mature),2,10,1,1,
            List.of(new NativeWineFeedReceipt.StateProof(11,POS,"society:wine_keg","false","true",EXTRA,true)),selected,expected);
    }
    @Test void ancientCollectAndRefillRequiresItsOwnExactThreeIngredientLoss() {
        var proof=selected(ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_FRUIT,61,true);
        assertEquals(3,confirmed(true,ItemData.ANCIENT_FRUIT,proof));
        assertEquals(0,confirmed(true,ItemData.TOMATO,proof));
        assertEquals(0,confirmed(true,ItemData.ANCIENT_FRUIT,selected(ItemData.TOMATO,ItemData.TOMATO,61,true)));
        assertEquals(0,confirmed(true,ItemData.ANCIENT_FRUIT,selected(ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_FRUIT,61,false)));
    }
    @Test void partialIdleFeedNeverCountsAsMatureCollectAndRefill() {
        for(int consumed:new int[]{1,2}) {
            var proof=selected(ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_FRUIT,64-consumed,true);
            assertEquals(consumed,confirmed(false,ItemData.ANCIENT_FRUIT,proof));
            assertEquals(0,confirmed(true,ItemData.ANCIENT_FRUIT,proof));
        }
    }
    @Test void configuredIngredientCannotAuthorizeArbitraryFruitOrAProductInTheHand() {
        for(String id:List.of("minecraft:apple",ItemData.ANCIENT_WINE,"other:ancient_fruit"))
            assertEquals(0,confirmed(true,id,selected(id,id,61,true)));
        assertEquals(0,confirmed(true,ItemData.ANCIENT_FRUIT,selected(ItemData.ANCIENT_FRUIT,ItemData.TOMATO,61,true)));
    }
    @Test void exhaustionRetainsEmptySlotAndCountRequirementsForTheAncientRecipe() {
        assertEquals(3,confirmed(true,ItemData.ANCIENT_FRUIT,new NativeWineFeedReceipt.SelectedProof(12,2,true,true,
            ItemData.ANCIENT_FRUIT,3,"minecraft:air",0,false)));
        assertEquals(0,confirmed(true,ItemData.ANCIENT_FRUIT,new NativeWineFeedReceipt.SelectedProof(12,2,true,true,
            ItemData.ANCIENT_FRUIT,3,ItemData.ANCIENT_WINE,1,false)));
    }
}
