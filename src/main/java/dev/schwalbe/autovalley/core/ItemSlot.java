package dev.schwalbe.autovalley.core;
/** index is the menu slot in menus, and inventory index (0..35) in world.inventory(). */
public record ItemSlot(int index, int inventoryIndex, boolean player, ItemData item) { }
