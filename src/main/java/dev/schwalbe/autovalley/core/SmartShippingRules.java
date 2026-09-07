package dev.schwalbe.autovalley.core;

import java.util.HashSet;
import java.util.Set;

/** Exact installed smart-bin metadata and menu layout; never a general KubeJS container rule. */
public final class SmartShippingRules {
    public static final String BLOCK_ID="shippingbin:smart_shipping_bin";
    public static final String ENTITY_CLASS="dev.latvian.mods.kubejs.block.entity.BlockEntityJS";
    public static final String INVENTORY_FIELD_TYPE="dev.latvian.mods.kubejs.core.InventoryKJS";
    public static final String ATTACHMENT_CLASS="dev.latvian.mods.kubejs.block.entity.InventoryAttachment";
    public static final int STORAGE_SLOTS=54;
    private SmartShippingRules() { }

    public static boolean supportedOwner(String blockId,String entityClass) {
        return BLOCK_ID.equals(blockId) && ENTITY_CLASS.equals(entityClass);
    }
    public static int expectedSlots(String blockId,String entityClass,String fieldType,String attachmentClass,
                                    boolean nativeContainer,boolean sameOwner,int capacity) {
        return supportedOwner(blockId,entityClass) && INVENTORY_FIELD_TYPE.equals(fieldType)
                && ATTACHMENT_CLASS.equals(attachmentClass) && nativeContainer && sameOwner && capacity==STORAGE_SLOTS
                ? STORAGE_SLOTS : 0;
    }
    /** The known 9x6 KubeJS menu exposes 54 storage slots followed by the 36 player slots. */
    public static boolean matchesMenu(MenuData menu) {
        if(menu==null || !menu.container() || menu.slots()==null || menu.slots().size()!=90) return false;
        Set<Integer> indices=new HashSet<>(),playerIndices=new HashSet<>();
        int storage=0;
        for(ItemSlot slot:menu.slots()) {
            if(slot==null || slot.item()==null || slot.index()<0 || slot.index()>=90 || !indices.add(slot.index())) return false;
            if(slot.player()) {
                if(slot.index()<STORAGE_SLOTS || slot.inventoryIndex()<0 || slot.inventoryIndex()>=36
                        || !playerIndices.add(slot.inventoryIndex())) return false;
            } else {
                if(slot.index()>=STORAGE_SLOTS) return false;
                storage++;
            }
        }
        return storage==STORAGE_SLOTS && playerIndices.size()==36;
    }
}
