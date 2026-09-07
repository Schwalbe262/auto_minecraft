package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/**
 * Pinned TrashSlot 15.1.3 public network API. No cursor prediction, world drop,
 * delete-all, trash-buffer clearing, GUI input or packet resend is performed.
 * Native snapshots remain RAM-only. A single-slot packet is never represented
 * as a complete authoritative server-menu snapshot.
 */
final class NativeTrashSlot {
    private record Hooks(Field installed,Constructor<?> message,Method networking,Method send,Method trashSlot) { }
    private static final Hooks HOOKS=hooks();
    final long generation,beforeSequence;
    final int menuId,sourceMenuSlot,quantity;
    private final List<InventoryConsolidation.Stack> before;

    static boolean available() {
        try { return HOOKS!=null && HOOKS.installed().getBoolean(null); }
        catch (ReflectiveOperationException | LinkageError failure) { return false; }
    }
    private static Hooks hooks() {
        try {
            if (!ModList.get().getModContainerById("trashslot").map(mod -> mod.getModInfo().getVersion().toString().equals("15.1.3")).orElse(false)) return null;
            ClassLoader loader=NativeTrashSlot.class.getClassLoader();
            Class<?> mod=Class.forName("net.blay09.mods.trashslot.TrashSlot",false,loader);
            Class<?> message=Class.forName("net.blay09.mods.trashslot.network.MessageDeleteFromSlot",false,loader);
            Class<?> balm=Class.forName("net.blay09.mods.balm.api.Balm",false,loader);
            Class<?> networking=Class.forName("net.blay09.mods.balm.api.network.BalmNetworking",false,loader);
            Class<?> gui=Class.forName("net.blay09.mods.trashslot.client.TrashSlotGuiHandler",false,loader);
            return new Hooks(mod.getField("isServerSideInstalled"),message.getConstructor(int.class,boolean.class),
                balm.getMethod("getNetworking"),networking.getMethod("sendToServer",Object.class),gui.getMethod("getTrashSlot"));
        } catch (ReflectiveOperationException | LinkageError failure) { return null; }
    }
    NativeTrashSlot(LocalPlayer player,Action.TrashRotten action,ServerObservations observations) {
        if (!available() || player==null || player.containerMenu!=player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
            || action.inventoryIndex()<0 || action.inventoryIndex()>=36 || action.expected()==null || !action.expected().is(ItemData.ROTTEN)
            || !MinecraftWorld.item(player.getInventory().getItem(action.inventoryIndex())).equals(action.expected()))
            throw new IllegalArgumentException("Inventory TrashSlot preconditions changed");
        List<Slot> matching=player.inventoryMenu.slots.stream().filter(s -> s.container==player.getInventory()
            && s.getContainerSlot()==action.inventoryIndex()).toList();
        if (matching.size()!=1 || player.inventoryMenu.slots.size()>128) throw new IllegalArgumentException("Ambiguous inventory trash slot");
        try {
            Object buffer=HOOKS.trashSlot().invoke(null);
            if (!(buffer instanceof Slot slot)) throw new IllegalStateException("TrashSlot buffer is unavailable");
            ItemStack retained=slot.getItem();
            if (!retained.isEmpty() && !MinecraftWorld.item(retained).is(ItemData.ROTTEN))
                throw new IllegalStateException("Recover the other item retained in TrashSlot before disposing tomatoes");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("TrashSlot buffer could not be checked",failure); }
        sourceMenuSlot=matching.get(0).index;
        menuId=player.inventoryMenu.containerId; quantity=action.expected().count();
        before=stacks(player.inventoryMenu.slots.stream().map(Slot::getItem).toList());
        generation=observations.generation(); beforeSequence=observations.sequence();
    }
    void send() {
        try {
            // false selects exactly this single stack. Never -1 and never delete-all.
            Object message=HOOKS.message().newInstance(sourceMenuSlot,false);
            HOOKS.send().invoke(HOOKS.networking().invoke(null),message);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("TrashSlot single-slot request failed",failure); }
    }
    boolean confirmed(ServerObservations observations) {
        if (generation!=observations.generation()) return false;
        for (var ack:observations.fullNativeMenuSnapshotsSince(menuId,beforeSequence))
            if (confirmed(ack,true)) return true;
        for (var ack:observations.nativeSlotSnapshotsSince(menuId,beforeSequence))
            if (ack.slot()==sourceMenuSlot && ack.packetItem().isEmpty() && confirmed(ack.appliedMenu(),true)) return true;
        return false;
    }
    private boolean confirmed(ServerObservations.NativeMenuSnapshot ack,boolean serverEmpty) {
        return InventoryTrashAcknowledgement.confirmed(before,stacks(ack.items()),sourceMenuSlot,serverEmpty,ack.carried().isEmpty())==quantity;
    }
    private static List<InventoryConsolidation.Stack> stacks(List<ItemStack> items) {
        return items.stream().map(stack -> {
            if (stack.isEmpty()) return InventoryConsolidation.Stack.EMPTY;
            ItemStack one=stack.copy(); one.setCount(1);
            return new InventoryConsolidation.Stack(one.save(new CompoundTag()).toString(),stack.getCount(),stack.getMaxStackSize());
        }).toList();
    }
}
