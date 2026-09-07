package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation;
import dev.schwalbe.autovalley.core.ItemData;
import dev.schwalbe.autovalley.core.ProductionMergePlanner;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
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
    boolean matchesLive(LocalPlayer player) {
        return player!=null && player.containerMenu==player.inventoryMenu && player.inventoryMenu.containerId==menuId
            && player.inventoryMenu.getCarried().isEmpty()
            && expectedMenu.equals(stacks(player.inventoryMenu.slots.stream().map(slot -> slot.getItem()).toList()));
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
        var confirmation=transaction.acknowledge(inventory(after),passiveUpdates);
        if (confirmation!=InventoryConsolidation.Confirmation.WAIT) expectedMenu=after;
        return confirmation;
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
