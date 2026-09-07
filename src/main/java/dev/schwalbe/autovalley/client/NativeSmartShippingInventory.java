package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.SmartShippingRules;
import net.minecraft.world.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Optional installed-KubeJS bridge. Reads only public inventory ownership/type and capacity,
 * never stacks, NBT, filter contents, or a closed container snapshot. No KubeJS hard dependency.
 */
public final class NativeSmartShippingInventory {
    private NativeSmartShippingInventory() { }

    /** Zero means unsupported/unverified, not an unconstrained container. */
    public static int expectedSlots(String blockId,Object blockEntity) {
        if(blockEntity==null || !SmartShippingRules.supportedOwner(blockId,blockEntity.getClass().getName())) return 0;
        try {
            Field inventoryField=blockEntity.getClass().getField("inventory");
            if(Modifier.isStatic(inventoryField.getModifiers())
                    || !SmartShippingRules.ENTITY_CLASS.equals(inventoryField.getDeclaringClass().getName())
                    || !SmartShippingRules.INVENTORY_FIELD_TYPE.equals(inventoryField.getType().getName())) return 0;
            Object inventory=inventoryField.get(blockEntity);
            if(inventory==null || !SmartShippingRules.ATTACHMENT_CLASS.equals(inventory.getClass().getName())
                    || !(inventory instanceof Container container)) return 0;
            Field ownerField=inventory.getClass().getField("blockEntity");
            if(Modifier.isStatic(ownerField.getModifiers())) return 0;
            boolean sameOwner=ownerField.get(inventory)==blockEntity;
            return SmartShippingRules.expectedSlots(blockId,blockEntity.getClass().getName(),inventoryField.getType().getName(),
                    inventory.getClass().getName(),true,sameOwner,container.getContainerSize());
        } catch(ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return 0;
        }
    }
}
