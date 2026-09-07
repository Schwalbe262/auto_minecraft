package dev.schwalbe.autovalley.core;
import java.util.List;
public record MenuData(int id, int revision, List<ItemSlot> slots, ItemData carried, boolean container) {
    public ItemSlot slot(int index) { return slots.stream().filter(s -> s.index() == index).findFirst().orElse(null); }
}
