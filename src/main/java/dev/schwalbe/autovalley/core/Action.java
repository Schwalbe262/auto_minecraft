package dev.schwalbe.autovalley.core;
public sealed interface Action {
    enum Use { HARVEST, MACHINE, ARTISAN, FRUIT, OPEN_CONTAINER, OPEN_CRAFTING, SLEEP, DOOR }
    record UseBlock(Pos pos, Use purpose) implements Action { }
    record SelectHotbar(int slot) implements Action { }
    record SwapHotbar(int inventoryIndex, int hotbarSlot) implements Action { }
    /** One non-mutating native inventory synchronization request for logging custody recovery. */
    record RefreshInventory() implements Action { }
    /** Cursor-free, owned-inventory-only native stack consolidation. */
    record ConsolidateInventory(ProductionMergePlanner.Plan plan, boolean optionalOutput) implements Action {
        public ConsolidateInventory(ProductionMergePlanner.Plan plan) { this(plan,false); }
    }
    record QuickMove(int containerId, int slot) implements Action { }
    record ThrowRotten(int containerId, int slot, Pos disposal) implements Action { }
    /** One cursor-free TrashSlot deletion of this exact normal inventory stack. */
    record TrashRotten(int inventoryIndex, ItemData expected) implements Action { }
    /** One normal mining operation at a verified registered spruce-tree base. */
    record ChopTree(Pos pos) implements Action { }
    /** One explicitly permitted spruce leaf obstructing this registered tree base. */
    record ClearLoggingLeaf(Pos pos,Pos stump) implements Action { }
    record PlantSapling(Pos pos) implements Action { }
    /** One validated six-spruce-log recipe batch in an automation-owned crafting table. */
    record CraftFireLogs(Pos table) implements Action { }
    record TrashLogging(int inventoryIndex, ItemData expected) implements Action { }
    record CloseContainer(int containerId) implements Action { }
}
