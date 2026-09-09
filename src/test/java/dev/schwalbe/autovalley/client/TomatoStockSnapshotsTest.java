package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TomatoStockSnapshotsTest {
    @Test void usesDetachedPacketSlotsNotPredictedMenuItemsOrPlayerInventory() {
        MenuData menu=menu(27);List<ItemData> packet=new ArrayList<>(Collections.nCopies(63,ItemData.EMPTY));
        packet.set(0,tomato(14));packet.set(26,tomato(64));packet.set(27,tomato(32));
        List<ItemData> result=TomatoStockSnapshots.storage(menu,packet);
        assertNotNull(result);assertEquals(27,result.size());assertEquals(14,result.get(0).count());assertEquals(64,result.get(26).count());
        packet.set(0,tomato(1));assertEquals(14,result.get(0).count());
        assertThrows(UnsupportedOperationException.class,()->result.set(0,ItemData.EMPTY));
    }
    @Test void requiresFullPacketNotAMarkerPartialListOrDifferentMenuShape() {
        assertNull(TomatoStockSnapshots.storage(menu(27),null));
        for(int size:new int[]{0,27,62,64})assertNull(TomatoStockSnapshots.storage(menu(27),Collections.nCopies(size,ItemData.EMPTY)));
        assertNull(TomatoStockSnapshots.storage(menu(54),Collections.nCopies(90,ItemData.EMPTY)));
        assertNull(TomatoStockSnapshots.storage(new MenuData(0,0,menu(27).slots(),ItemData.EMPTY,false),Collections.nCopies(63,ItemData.EMPTY)));
    }
    @Test void duplicateMissingOutOfRangeAndNullStorageSlotsCannotBecomeSnapshot() {
        for(int index:new int[]{-1,1,27,99}) {
            List<ItemSlot> slots=new ArrayList<>(menu(27).slots());slots.set(0,new ItemSlot(index,-1,false,ItemData.EMPTY));
            assertNull(TomatoStockSnapshots.storage(new MenuData(1,0,slots,ItemData.EMPTY,true),Collections.nCopies(63,ItemData.EMPTY)));
        }
        List<ItemData> packet=new ArrayList<>(Collections.nCopies(63,ItemData.EMPTY));packet.set(0,null);
        assertNull(TomatoStockSnapshots.storage(menu(27),packet));
    }
    static MenuData menu(int storageSlots) {
        List<ItemSlot> slots=new ArrayList<>();
        for(int i=0;i<storageSlots+36;i++)slots.add(new ItemSlot(i,i-storageSlots,i>=storageSlots,ItemData.EMPTY));
        return new MenuData(1,0,slots,ItemData.EMPTY,true);
    }
    private static ItemData tomato(int count){return new ItemData(ItemData.TOMATO,count,0,null,false,100);}
}
