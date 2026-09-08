package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Action;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import net.minecraft.client.player.LocalPlayer;

/** Logging's borrowed hotbar gear needs an exact two-endpoint full server ACK. */
final class NativeLoggingSwap {
    final long generation,beforeSequence;
    final int menuId,source,destination;
    private final List<Stack> before;
    NativeLoggingSwap(LocalPlayer player,Action.SwapHotbar action,ServerObservations observations) {
        if (player.containerMenu!=player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) throw new IllegalArgumentException("Inventory changed");
        generation=observations.generation(); beforeSequence=observations.sequence(); menuId=player.inventoryMenu.containerId;
        source=slot(player,action.inventoryIndex()); destination=slot(player,action.hotbarSlot());
        before=NativeLoggingRecipe.stacks(player.inventoryMenu.slots.stream().map(s -> s.getItem()).toList());
    }
    private static int slot(LocalPlayer p,int index) {
        var slots=p.inventoryMenu.slots.stream().filter(s -> s.container==p.getInventory() && s.getContainerSlot()==index).toList();
        if (slots.size()!=1) throw new IllegalArgumentException("Ambiguous swap slot");
        return slots.get(0).index;
    }
    boolean confirmed(ServerObservations observations) {
        return generation==observations.generation() && observations.fullNativeMenuSnapshotsSince(menuId,beforeSequence).stream()
            .anyMatch(s -> s.carried().isEmpty() && swapped(before,NativeLoggingRecipe.stacks(s.items()),source,destination));
    }
    static boolean swapped(List<Stack> before,List<Stack> after,int source,int destination) {
        return before!=null && after!=null && before.size()==after.size() && source>=0 && destination>=0
            && source<before.size() && destination<before.size() && source!=destination
            && !before.get(source).equals(before.get(destination))
            && before.get(source).equals(after.get(destination)) && before.get(destination).equals(after.get(source));
    }
}
