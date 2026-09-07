package dev.schwalbe.autovalley.core;

import java.util.List;
import java.util.Objects;

/** Measures the authoritative reply, not the live inventory a magnet may already have refilled. */
public final class InventoryAcknowledgements {
    private InventoryAcknowledgements() { }
    public static boolean same(ItemData a,ItemData b) {
        return a.id().equals(b.id()) && a.quality()==b.quality() && Objects.equals(a.year(),b.year());
    }
    public static int removed(MenuData before,List<ItemData> after,int index) {
        ItemSlot source=before.slot(index);
        if (source==null || index<0 || index>=after.size() || source.item().empty()) return 0;
        ItemData remaining=after.get(index);
        return Math.max(0,source.item().count()-(same(source.item(),remaining) ? remaining.count() : 0));
    }
    public static int destinationIncrease(MenuData before,List<ItemData> after,ItemData item) {
        int previous=0,current=0;
        for (ItemSlot slot:before.slots()) if (!slot.player()) {
            if (slot.index()<0 || slot.index()>=after.size()) return 0;
            if (same(slot.item(),item)) previous+=slot.item().count();
            if (same(after.get(slot.index()),item)) current+=after.get(slot.index()).count();
        }
        return Math.max(0,current-previous);
    }
    public static boolean changed(MenuData before,List<ItemData> after) {
        if (before.slots().size()!=after.size()) return false;
        return before.slots().stream().anyMatch(s -> s.index()>=0 && s.index()<after.size() && !s.item().equals(after.get(s.index())));
    }
}
