package dev.schwalbe.autovalley.core;
public sealed interface Action {
    enum Use { HARVEST, MACHINE, OPEN_CONTAINER, SLEEP, DOOR }
    record UseBlock(Pos pos, Use purpose) implements Action { }
    record SelectHotbar(int slot) implements Action { }
    record SwapHotbar(int inventoryIndex, int hotbarSlot) implements Action { }
    /** Cursor-free, owned-inventory-only native stack consolidation. */
    record ConsolidateInventory(ProductionMergePlanner.Plan plan) implements Action { }
    record QuickMove(int containerId, int slot) implements Action { }
    record ThrowRotten(int containerId, int slot, Pos disposal) implements Action { }
    record CloseContainer(int containerId) implements Action { }
}
