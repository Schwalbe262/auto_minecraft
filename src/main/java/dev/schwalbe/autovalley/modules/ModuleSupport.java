package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.function.Predicate;

/** Shared read-only inventory rules. Every mutation still passes through ActionPort. */
final class ModuleSupport {
    private ModuleSupport() { }
    static String navigationFailure(Context c, String summary) {
        String detail=c.navigation().failureReason();
        return detail==null || detail.isBlank() ? summary : summary+": "+detail;
    }
    static int count(Context c, Predicate<ItemData> predicate) {
        return c.world().inventory().stream().map(ItemSlot::item).filter(predicate).mapToInt(ItemData::count).sum();
    }
    static boolean same(ItemData a, ItemData b) {
        return a.id().equals(b.id()) && a.quality() == b.quality() && Objects.equals(a.year(), b.year());
    }
    static int count(Context c, ItemData item) { return count(c, i -> same(i,item)); }
    static boolean tomatoGrade(ItemData item, int grade) { return item.is(ItemData.TOMATO) && item.quality() == grade; }
    static List<Poi> nearest(Context c, List<Poi> pois) {
        return pois.stream().sorted(Comparator.comparingDouble(p -> c.world().player().distance(p.pos()))).toList();
    }
    static boolean canReceive(Context c, ItemData item) {
        return c.world().menu().slots().stream().anyMatch(s -> !s.player() && c.world().mayPlace(s.index(),item)
            && (s.item().empty() || same(s.item(),item) && s.item().count() < 64));
    }
    static boolean hasEmptyInventorySlot(Context c) {
        return c.world().inventory().stream().filter(s -> !s.item().empty()).count() < 36;
    }
    static ItemSlot inventoryItem(Context c, Predicate<ItemData> predicate) {
        return c.world().inventory().stream().filter(s -> predicate.test(s.item())).findFirst().orElse(null);
    }
    static ItemSlot menuPlayerItem(Context c, Predicate<ItemData> predicate) {
        return c.world().menu().slots().stream().filter(ItemSlot::player)
            .filter(s -> s.inventoryIndex() >= 0 && s.inventoryIndex() < 36)
            .filter(s -> predicate.test(s.item())).findFirst().orElse(null);
    }
    static WorkResult busy(String message) { return WorkResult.busy(message); }
}
