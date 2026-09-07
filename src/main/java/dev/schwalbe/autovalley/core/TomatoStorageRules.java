package dev.schwalbe.autovalley.core;

/** Commodity-only warehouse permission; grade remains relevant to native stacking and recipes. */
public final class TomatoStorageRules {
    private TomatoStorageRules() { }

    public static boolean permitsTransfer(ItemData item,MenuData menu) {
        if (item==null || item.id()==null || !item.is(ItemData.TOMATO) || menu==null || !menu.container()
                || menu.carried()==null || !menu.carried().empty() || menu.slots()==null) return false;
        boolean storagePresent=false;
        for (ItemSlot slot:menu.slots()) {
            if (slot==null || slot.item()==null || slot.item().id()==null || slot.item().count()<0) return false;
            if (slot.player()) continue;
            storagePresent=true;
            if (!slot.item().empty() && !slot.item().is(ItemData.TOMATO)) return false;
        }
        return storagePresent;
    }
}
