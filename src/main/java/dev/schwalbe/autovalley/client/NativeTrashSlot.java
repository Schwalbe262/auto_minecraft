package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
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
    private final Set<Integer> inventoryMenuSlots;
    private final boolean logging;

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
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return null; }
    }
    NativeTrashSlot(LocalPlayer player,Action.TrashRotten action,ServerObservations observations) {
        this(player,action.inventoryIndex(),action.expected(),observations,false);
    }
    NativeTrashSlot(LocalPlayer player,Action.TrashLogging action,ServerObservations observations) {
        this(player,action.inventoryIndex(),action.expected(),observations,true);
    }
    private NativeTrashSlot(LocalPlayer player,int inventoryIndex,ItemData expected,ServerObservations observations,boolean logging) {
        this.logging=logging;
        if (!available() || player==null || player.containerMenu!=player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()
            || inventoryIndex<0 || inventoryIndex>=36 || expected==null || !(logging ? LoggingRules.waste(expected) : expected.is(ItemData.ROTTEN))
            || !MinecraftWorld.item(player.getInventory().getItem(inventoryIndex)).equals(expected))
            throw new IllegalArgumentException("Inventory TrashSlot preconditions changed");
        List<Slot> matching=player.inventoryMenu.slots.stream().filter(s -> s.container==player.getInventory()
            && s.getContainerSlot()==inventoryIndex).toList();
        if (matching.size()!=1 || player.inventoryMenu.slots.size()>128) throw new IllegalArgumentException("Ambiguous inventory trash slot");
        try {
            Object buffer=HOOKS.trashSlot().invoke(null);
            if (!(buffer instanceof Slot slot)) throw new IllegalStateException("TrashSlot buffer is unavailable");
            ItemStack retained=slot.getItem();
            if (!retained.isEmpty() && !disposableBuffer(MinecraftWorld.item(retained)))
                throw new IllegalStateException("Recover the other item retained in TrashSlot before disposing tomatoes");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("TrashSlot buffer could not be checked",failure); }
        sourceMenuSlot=matching.get(0).index;
        menuId=player.inventoryMenu.containerId; quantity=expected.count();
        before=stacks(player.inventoryMenu.slots.stream().map(Slot::getItem).toList());
        List<Slot> inventorySlots=player.inventoryMenu.slots.stream().filter(s -> s.container==player.getInventory()
            && s.getContainerSlot()>=0 && s.getContainerSlot()<36).toList();
        inventoryMenuSlots=Set.copyOf(inventorySlots.stream().map(s -> s.index).toList());
        if (inventorySlots.size()!=36 || inventoryMenuSlots.size()!=36
            || inventorySlots.stream().map(Slot::getContainerSlot).distinct().count()!=36)
            throw new IllegalArgumentException("Incomplete normal inventory mapping");
        generation=observations.generation(); beforeSequence=observations.sequence();
    }
    static boolean disposableBuffer(ItemData item) {
        return item!=null && (item.empty() || item.is(ItemData.ROTTEN) || LoggingRules.waste(item));
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
        var full=observations.fullNativeMenuSnapshotsSince(menuId,beforeSequence);
        var slots=observations.nativeSlotSnapshotsSince(menuId,beforeSequence);
        List<InventoryTrashAcknowledgement.SlotProof> proofs=new ArrayList<>();
        for (var ack:slots) proofs.add(new InventoryTrashAcknowledgement.SlotProof(ack.seq(),ack.menuId(),ack.slot(),stacks(List.of(ack.packetItem())).get(0)));
        // Prior source changes invalidate the original quantity, including changes
        // authored by a full packet. Other full-menu slots never masquerade as raw-slot proof.
        for (var ack:full) {
            List<ItemStack> items=ack.items();
            if (sourceMenuSlot<items.size()) proofs.add(new InventoryTrashAcknowledgement.SlotProof(ack.seq(),menuId,sourceMenuSlot,
                stacks(List.of(items.get(sourceMenuSlot))).get(0)));
        }
        for (var ack:full) {
            var proven=proofsAt(observations,ack.seq(),proofs);
            if (proven!=null && confirmed(ack,proven,true)) return true;
        }
        for (var ack:slots) {
            if (ack.slot()!=sourceMenuSlot || !ack.packetItem().isEmpty() || ack.appliedMenu().seq()!=ack.seq()) continue;
            var proven=proofsAt(observations,ack.seq(),proofs);
            if (proven!=null && confirmed(ack.appliedMenu(),proven,false)) return true;
        }
        return false;
    }
    private Map<Integer,InventoryConsolidation.Stack> proofsAt(ServerObservations observations,long sequence,List<InventoryTrashAcknowledgement.SlotProof> proofs) {
        return InventoryTrashAcknowledgement.precedingSlotProofs(generation,observations.generation(),menuId,beforeSequence,sequence,
            sourceMenuSlot,before.get(sourceMenuSlot),proofs);
    }
    private boolean confirmed(ServerObservations.NativeMenuSnapshot ack,Map<Integer,InventoryConsolidation.Stack> proven,boolean fullServerPacket) {
        List<InventoryConsolidation.Stack> after=stacks(ack.items());
        if (after.size()!=before.size()) return false;
        Set<Integer> additions=new HashSet<>();
        for (int slot=0;slot<before.size();slot++) {
            if (slot==sourceMenuSlot || before.get(slot).equals(after.get(slot))) continue;
            // ROTTEN, gear, cursor, replacements and count losses are never exceptions.
            // For a single-slot reply only its raw packetItem is authoritative;
            // the surrounding applied-client menu needs independent earlier raw proofs.
            if (!inventoryMenuSlots.contains(slot) || !(productionAddition(before.get(slot),after.get(slot))
                    || logging && loggingAddition(before.get(slot),after.get(slot)))
                || !fullServerPacket && !after.get(slot).equals(proven.get(slot))) return false;
            additions.add(slot);
        }
        return InventoryTrashAcknowledgement.confirmed(before,after,sourceMenuSlot,true,ack.carried().isEmpty(),additions,inventoryMenuSlots)==quantity;
    }
    private static boolean productionAddition(InventoryConsolidation.Stack old,InventoryConsolidation.Stack now) {
        if (NativeInventoryConsolidation.productionAddition(old,now)) return true;
        // Pine tar is also a user-authorized harvested/shipped product. This
        // addition exception does not expand the inventory consolidation planner.
        if (now.empty() || now.count()<=old.count()) return false;
        try {
            return ItemData.PINE_TAR.equals(TagParser.parseTag(now.identity()).getString("id"))
                && (old.empty() || old.limit()==now.limit() && old.identity().equals(now.identity()));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { return false; }
    }
    static boolean loggingAddition(InventoryConsolidation.Stack old,InventoryConsolidation.Stack now) {
        if (now.empty() || now.count()<=old.count() || !old.empty() && (old.limit()!=now.limit() || !old.identity().equals(now.identity()))) return false;
        try {
            String id=TagParser.parseTag(now.identity()).getString("id");
            return Set.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.SAPLING,LoggingRules.TWIG,LoggingRules.BERRY).contains(id);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { return false; }
    }
    private static List<InventoryConsolidation.Stack> stacks(List<ItemStack> items) {
        return items.stream().map(stack -> {
            if (stack.isEmpty()) return InventoryConsolidation.Stack.EMPTY;
            ItemStack one=stack.copy(); one.setCount(1);
            return new InventoryConsolidation.Stack(one.save(new CompoundTag()).toString(),stack.getCount(),stack.getMaxStackSize());
        }).toList();
    }
}
