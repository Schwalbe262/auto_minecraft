package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Extracts exact ordinary storage slots from an already received full server menu packet. */
final class TomatoStockSnapshots {
    private TomatoStockSnapshots() { }
    static List<ItemData> storage(MenuData shape,List<ItemData> packetItems) {
        if(shape==null || !shape.container() || packetItems==null || packetItems.size()!=shape.slots().size())return null;
        List<ItemSlot> slots=shape.slots().stream().filter(slot->!slot.player()).toList();
        if(slots.size()!=TomatoStockCache.STORAGE_SLOTS)return null;
        ItemData[] storage=new ItemData[TomatoStockCache.STORAGE_SLOTS];
        for(ItemSlot slot:slots) {
            int index=slot.index();
            if(index<0 || index>=storage.length || storage[index]!=null || packetItems.get(index)==null)return null;
            storage[index]=packetItems.get(index);
        }
        return List.copyOf(Arrays.asList(storage));
    }
}
