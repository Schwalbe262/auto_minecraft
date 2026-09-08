package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation;
import dev.schwalbe.autovalley.core.ItemData;
import dev.schwalbe.autovalley.core.ProductionMergePlanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.util.*;

/** Native snapshots are kept in RAM only, and never placed in diagnostics or recordings. */
final class NativeInventoryConsolidation {
    final InventoryConsolidation transaction;
    final int menuId;
    final long generation;
    final ProductionMergePlanner.Plan plan;
    final int protectedHotbar;
    long beforeSequence;
    private final int[] menuSlots;
    private final Level level;
    private final Integer wineYear;
    private List<InventoryConsolidation.Stack> expectedMenu;
    private long acknowledgedSequence=-1;

    private NativeInventoryConsolidation(LocalPlayer player,ProductionMergePlanner.Plan plan,ServerObservations observations,int protectedHotbar) {
        this.plan=plan; this.protectedHotbar=protectedHotbar;
        level=player.level(); wineYear=VineryClock.year(level);
        menuId=player.inventoryMenu.containerId; generation=observations.generation(); beforeSequence=observations.sequence();
        menuSlots=new int[36]; Arrays.fill(menuSlots,-1);
        for (var slot:player.inventoryMenu.slots) {
            int index=slot.getContainerSlot();
            if (slot.container!=player.getInventory() || index<0 || index>=36) continue;
            if (menuSlots[index]!=-1) throw new IllegalArgumentException("Ambiguous inventory slot mapping");
            menuSlots[index]=slot.index;
        }
        if (Arrays.stream(menuSlots).anyMatch(index -> index<0)) throw new IllegalArgumentException("Incomplete inventory menu");
        expectedMenu=stacks(player.inventoryMenu.slots.stream().map(slot -> slot.getItem()).toList());
        transaction=new InventoryConsolidation(plan,inventory(expectedMenu));
    }

    /** Null means client-visible native tags/caps cannot support the candidate: send no packet. */
    static NativeInventoryConsolidation create(LocalPlayer player,ProductionMergePlanner.Plan plan,ServerObservations observations,int protectedHotbar) {
        if (player.containerMenu!=player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty())
            throw new IllegalArgumentException("Close menus and empty the cursor first");
        if (!ProductionMergePlanner.protectsProductionSlots(plan,protectedHotbar)) return null;
        ItemStack source=player.getInventory().getItem(plan.sourceIndex());
        if (!plan.direct()) {
            ItemStack scratch=player.getInventory().getItem(plan.scratchHotbar());
            // Prefer EMPTY, but an unrelated item may use the exact inverse-SWAP
            // protocol. Never displace an ingredient, any hoe, or the same product.
            if (!scratch.isEmpty() && (scratch.getItem() instanceof HoeItem
                || ItemData.TOMATO.equals(BuiltInRegistries.ITEM.getKey(scratch.getItem()).toString())
                || scratch.getItem()==source.getItem())) return null;
        }
        ItemStack protectedItem=player.getInventory().getItem(protectedHotbar);
        if (plan.direct() && plan.sourceIndex()>=9 && (protectedItem.isEmpty()
            || ItemStack.isSameItemSameTags(source,protectedItem) && protectedItem.getCount()<protectedItem.getMaxStackSize())) return null;
        ItemStack material=player.getInventory().getItem((protectedHotbar+1)%9);
        if (!ItemData.TOMATO.equals(plan.itemId()) && plan.direct() && plan.sourceIndex()>=9
            && (material.isEmpty() || ItemStack.isSameItemSameTags(source,material)
                && material.getCount()<material.getMaxStackSize())) return null;
        int capacity=0;
        for (int destination:plan.destinations()) {
            ItemStack stack=player.getInventory().getItem(destination);
            if (plan.reposition() && stack.isEmpty()) capacity+=source.getMaxStackSize();
            if (!stack.isEmpty() && ItemStack.isSameItemSameTags(source,stack))
                capacity+=Math.max(0,Math.min(source.getMaxStackSize(),stack.getMaxStackSize())-stack.getCount());
        }
        if (capacity<source.getCount()) return null;
        return new NativeInventoryConsolidation(player,plan,observations,protectedHotbar);
    }
    int sourceMenuSlot() { return menuSlots[transaction.click().sourceIndex()]; }
    boolean matchesLive(LocalPlayer player,ServerObservations observations) {
        if (player==null || player.containerMenu!=player.inventoryMenu || player.inventoryMenu.containerId!=menuId
            || !player.inventoryMenu.getCarried().isEmpty() || observations.generation()!=generation) return false;
        List<InventoryConsolidation.Stack> live=stacks(player.inventoryMenu.slots.stream().map(slot -> slot.getItem()).toList());
        if (expectedMenu.equals(live)) return true;
        if (acknowledgedSequence<0) return false;

        // A pickup can arrive after a complete click ACK but before its inverse
        // SWAP. Only the actual subsequent per-slot packet item is evidence;
        // appliedMenu is client state and must never stand in for a full ACK.
        Map<Integer,InventoryConsolidation.Stack> latestServerItems=new HashMap<>();
        for (var update:observations.nativeSlotSnapshotsSince(menuId,acknowledgedSequence))
            latestServerItems.put(update.slot(),stacks(List.of(update.packetItem())).get(0));
        Set<Integer> changed=matchingServerSlotUpdates(expectedMenu,live,latestServerItems);
        if (changed==null || changed.isEmpty()) return false;
        Set<Integer> verifiedInventoryIndices=new HashSet<>();
        Set<Integer> verifiedProductionAdditions=new HashSet<>();
        for (int slot:changed) {
            int index=-1;
            for (int candidate=0;candidate<menuSlots.length;candidate++) if (menuSlots[candidate]==slot) { index=candidate; break; }
            if (index<0 || index==protectedHotbar) return false;
            var old=expectedMenu.get(slot); var now=live.get(slot);
            if (productionAddition(old,now)) verifiedProductionAdditions.add(index);
            else if (!NativeWineMetadata.passiveChange(old,now,level,wineYear)) return false;
            verifiedInventoryIndices.add(index);
        }
        // This cannot acknowledge a click or change the next primitive. The
        // core excludes participants and same-identity implicit receivers. Only after an exact
        // MERGE may a verified pickup fill the EMPTY borrowed scratch: the next
        // exact inverse SWAP preserves that pickup and restores the borrowed item.
        if (!transaction.rebaseVerifiedUpdates(inventory(live),verifiedInventoryIndices,verifiedProductionAdditions)) return false;
        expectedMenu=live;
        return true;
    }

    /** Detached exact comparison; callers supply only post-ACK server packet items. */
    static Set<Integer> matchingServerSlotUpdates(List<InventoryConsolidation.Stack> expected,
            List<InventoryConsolidation.Stack> live,Map<Integer,InventoryConsolidation.Stack> latestServerItems) {
        if (expected==null || live==null || latestServerItems==null || expected.size()!=live.size()) return null;
        Set<Integer> changed=new HashSet<>();
        for (int slot=0;slot<expected.size();slot++) {
            if (Objects.equals(expected.get(slot),live.get(slot))) continue;
            if (!Objects.equals(live.get(slot),latestServerItems.get(slot))) return null;
            changed.add(slot);
        }
        return Set.copyOf(changed);
    }

    /** No deletion, replacement, quality change, or arbitrary item movement. */
    static boolean productionAddition(InventoryConsolidation.Stack before,InventoryConsolidation.Stack after) {
        if (after.empty() || after.count()<=before.count()) return false;
        try {
            String id=TagParser.parseTag(after.identity()).getString("id");
            if (!Set.of(ItemData.TOMATO,ItemData.WINE,ItemData.PRESERVES).contains(id)) return false;
            return before.empty() || before.limit()==after.limit() && before.identity().equals(after.identity());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { return false; }
    }
    InventoryConsolidation.Confirmation acknowledge(ServerObservations.NativeMenuSnapshot acknowledgement) {
        if (!acknowledgement.carried().isEmpty()) return InventoryConsolidation.Confirmation.WAIT;
        List<InventoryConsolidation.Stack> after=stacks(acknowledgement.items());
        if (after.size()!=expectedMenu.size()) return InventoryConsolidation.Confirmation.WAIT;
        // Crafting, armor, offhand and any unknown slots cannot be affected by this action.
        for (int slot=0; slot<after.size(); slot++) {
            boolean inventorySlot=false;
            for (int index:menuSlots) if (index==slot) { inventorySlot=true; break; }
            if (!inventorySlot && !expectedMenu.get(slot).equals(after.get(slot))) return InventoryConsolidation.Confirmation.WAIT;
        }
        Set<Integer> passiveUpdates=new HashSet<>();
        for (int index=0;index<menuSlots.length;index++) {
            int slot=menuSlots[index];
            if (NativeWineMetadata.passiveChange(expectedMenu.get(slot),after.get(slot),level,wineYear)) passiveUpdates.add(index);
        }
        var inventoryAfter=inventory(after);
        // This candidate is an actual full-menu server packet, not a live menu
        // or applied single-slot snapshot. An unrelated pickup may be included
        // in the same packet as the native primitive's result. The core still
        // verifies that exact primitive and rejects its participants and additions
        // sharing the moved item's native identity. A distinct product in the
        // destination region cannot be part of that native QUICK_MOVE.
        var additions=concurrentProductionAdditions(transaction,inventoryAfter,protectedHotbar);
        var confirmation=transaction.acknowledge(inventoryAfter,passiveUpdates,additions);
        if (confirmation!=InventoryConsolidation.Confirmation.WAIT) {
            expectedMenu=after; acknowledgedSequence=acknowledgement.seq();
        }
        return confirmation;
    }
    /** A real full reply can prove manual inverse restoration, never a resend or successful merge. */
    boolean acknowledgeCancelledRestoration(ServerObservations.NativeMenuSnapshot acknowledgement) {
        if (acknowledgement==null || !acknowledgement.carried().isEmpty()) return false;
        List<InventoryConsolidation.Stack> after=stacks(acknowledgement.items());
        if (after.size()!=expectedMenu.size()) return false;
        for (int slot=0;slot<after.size();slot++) {
            boolean inventorySlot=false;
            for (int index:menuSlots) if (index==slot) { inventorySlot=true; break; }
            if (!inventorySlot && !expectedMenu.get(slot).equals(after.get(slot))) return false;
        }
        if (!transaction.acknowledgeCancelledRestoration(inventory(after))) return false;
        expectedMenu=after;acknowledgedSequence=acknowledgement.seq();
        return true;
    }
    static Set<Integer> concurrentProductionAdditions(InventoryConsolidation transaction,
            InventoryConsolidation.Snapshot after,int protectedHotbar) {
        Set<Integer> additions=new HashSet<>();
        var before=transaction.expectedLive();
        for (int index=0;index<36;index++) {
            if (index!=protectedHotbar && transaction.allowsConcurrentAddition(index,after.items().get(index))
                && productionAddition(before.items().get(index),after.items().get(index))) additions.add(index);
        }
        return Set.copyOf(additions);
    }
    private InventoryConsolidation.Snapshot inventory(List<InventoryConsolidation.Stack> menu) {
        return new InventoryConsolidation.Snapshot(Arrays.stream(menuSlots).mapToObj(menu::get).toList());
    }
    private static List<InventoryConsolidation.Stack> stacks(List<ItemStack> items) {
        return items.stream().map(stack -> {
            if (stack.isEmpty()) return InventoryConsolidation.Stack.EMPTY;
            ItemStack one=stack.copy(); one.setCount(1);
            // Includes client-visible native tags/capabilities, not just quality/year.
            // A server mod may hide tags in its share tag; the native server click is
            // still authoritative and a refused merge cannot be reported as progress.
            return new InventoryConsolidation.Stack(one.save(new CompoundTag()).toString(),stack.getCount(),stack.getMaxStackSize());
        }).toList();
    }
}
