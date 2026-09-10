package dev.schwalbe.autovalley.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Inventory-only candidate plans; public item fields never prove native tag compatibility.
 * The action adapter must check native stacks before each click and verify authoritative
 * source/destination conservation plus an increased empty-slot count after QUICK_MOVE.
 * This planner neither grants action permission nor executes/restores an inventory layout.
 */
public final class ProductionMergePlanner {
    private static final int INVENTORY_SIZE = 36;
    private static final int HOTBAR_SIZE = 9;
    private static final int CANDIDATE_STACK_LIMIT = 64;

    /** All indices are player inventory indices, never container-menu slot numbers. */
    public record Plan(Feature feature, String itemId, int sourceIndex, int scratchHotbar,
                       List<Integer> destinations, List<ItemData> expectedItems) {
        public Plan {
            destinations = List.copyOf(destinations);
            expectedItems = List.copyOf(expectedItems);
        }

        public boolean direct() { return scratchHotbar == -1; }
        /** One bounded tomato-only bridge; it does not itself claim to free a slot. */
        public boolean reposition() { return destinations.stream().anyMatch(index -> expectedItems.get(index).empty()); }
        public int quickMoveIndex() { return direct() ? sourceIndex : scratchHotbar; }
        public boolean requiresRestore() { return !direct() && !expectedItems.get(scratchHotbar).empty(); }
        public int maximumClicks() { return direct() ? 1 : requiresRestore() ? 3 : 2; }
    }

    private ProductionMergePlanner() { }

    public static Optional<Plan> plan(List<ItemSlot> inventory, Feature feature, int hoeHotbar) {
        return plan(inventory, feature, hoeHotbar, null);
    }

    public static Optional<Plan> plan(List<ItemSlot> inventory, Feature feature, int hoeHotbar, Integer preferredSource) {
        String itemId = feature == Feature.WINE ? ItemData.WINE : ItemData.PRESERVES;
        return planItems(inventory, feature, itemId, hoeHotbar, null, preferredSource);
    }

    public static Optional<Plan> planTomatoes(List<ItemSlot> inventory, Feature feature, int hoeHotbar) {
        return planTomatoes(inventory, feature, hoeHotbar, null);
    }

    public static Optional<Plan> planTomatoes(List<ItemSlot> inventory, Feature feature, int hoeHotbar, Integer preferredSource) {
        return planItems(inventory, feature, ItemData.TOMATO, hoeHotbar, null, preferredSource);
    }

    /** Restricts candidates to the grade already allocated to the production batch. */
    public static Optional<Plan> planTomatoes(List<ItemSlot> inventory, Feature feature, int hoeHotbar, int grade, Integer preferredSource) {
        if (grade < 0 || grade > 3) return Optional.empty();
        return planItems(inventory, feature, ItemData.TOMATO, hoeHotbar, grade, preferredSource);
    }

    public static Optional<Plan> planIngredient(List<ItemSlot> inventory,Feature feature,int hoeHotbar,
                                                String itemId,int grade,Integer preferredSource) {
        if (!ItemData.isProductionIngredientId(itemId) || grade<0 || grade>3
                || feature==Feature.PRESERVES && !ItemData.TOMATO.equals(itemId)) return Optional.empty();
        return planItems(inventory,feature,itemId,hoeHotbar,grade,preferredSource);
    }

    public static Optional<Plan> planProduct(List<ItemSlot> inventory,Feature feature,int hoeHotbar,
                                            String itemId,Integer preferredSource) {
        if (feature==Feature.WINE ? !ItemData.isWineId(itemId)
                : feature!=Feature.PRESERVES || !ItemData.PRESERVES.equals(itemId)) return Optional.empty();
        return planItems(inventory,feature,itemId,hoeHotbar,null,preferredSource);
    }

    private static Optional<Plan> planItems(List<ItemSlot> inventory, Feature feature, String itemId,
                                          int hoeHotbar, Integer requiredGrade, Integer preferredSource) {
        if ((feature != Feature.WINE && feature != Feature.PRESERVES) || hoeHotbar < 0 || hoeHotbar >= HOTBAR_SIZE)
            return Optional.empty();
        List<ItemData> snapshot = snapshot(inventory);
        if (snapshot.isEmpty()) return Optional.empty();
        int materialHotbar=(hoeHotbar+1)%HOTBAR_SIZE;
        int protectedMaterial=ItemData.isProductionIngredientId(itemId) ? -1 : materialHotbar;
        List<Integer> sources = new ArrayList<>();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            if (index != hoeHotbar && index != protectedMaterial && candidate(snapshot.get(index), itemId)
                    && (requiredGrade == null || snapshot.get(index).quality() == requiredGrade)) sources.add(index);
        }
        // A preference only breaks ties between equally bounded plans. A one-click
        // direct merge always precedes moving any otherwise unrelated tomato stack.
        sources.sort(Comparator.comparingInt((Integer index) -> Objects.equals(index, preferredSource) ? 0 : 1)
                .thenComparingInt(Integer::intValue));
        for (int source : sources) {
            // Native QUICK_MOVE visits the whole opposite region, not just the
            // advertised candidate list. It could fill a compatible partial stack
            // in the protected hoe slot first, even if other destinations have room.
            if (source >= HOTBAR_SIZE && (snapshot.get(hoeHotbar).empty()
                    || visiblyCompatible(snapshot.get(source), snapshot.get(hoeHotbar)))) continue;
            if (source >= HOTBAR_SIZE && protectedMaterial>=0 && (snapshot.get(protectedMaterial).empty()
                    || visiblyCompatible(snapshot.get(source),snapshot.get(protectedMaterial)))) continue;
            List<Integer> destinations = destinations(snapshot, source, hoeHotbar, protectedMaterial, source < HOTBAR_SIZE);
            if (fullSourceFits(snapshot, source, destinations))
                return Optional.of(new Plan(feature, itemId, source, -1, destinations, snapshot));
        }
        int scratch = -1;
        for (int offset=2;offset<HOTBAR_SIZE;offset++) {
            int index=(hoeHotbar+offset)%HOTBAR_SIZE;
            if (snapshot.get(index).empty()) { scratch=index; break; }
        }
        if (scratch<0) for (int offset=2;offset<HOTBAR_SIZE;offset++) {
            int index=(hoeHotbar+offset)%HOTBAR_SIZE;
            if (safeScratch(snapshot.get(index),itemId)) { scratch=index; break; }
        }
        if (scratch>=0) {
            for (int source : sources) {
                if (source < HOTBAR_SIZE || source == scratch) continue;
                // Never borrow the ingredient hand, a tomato stack, or any hoe.
                // Prefer EMPTY; other items require the existing exact restore protocol.
                List<Integer> destinations = destinations(snapshot, source, hoeHotbar, protectedMaterial, true);
                if (fullSourceFits(snapshot, source, destinations))
                    return Optional.of(new Plan(feature, itemId, source, scratch, destinations, snapshot));
            }
        }
        // A bulk withdrawal can leave all of the final ingredient fragments in the
        // hotbar while main inventory is empty. One normal hotbar->main QUICK_MOVE
        // creates an opposite-region pair for a fresh, real merge on the next plan.
        // Product rearrangement and arbitrary reverse scratch swaps are not allowed.
        if (ItemData.isProductionIngredientId(itemId)) {
            int emptyMain = -1;
            for (int index = HOTBAR_SIZE; index < INVENTORY_SIZE; index++) {
                if (snapshot.get(index).empty()) { emptyMain = index; break; }
            }
            if (emptyMain >= 0) for (int source : sources) {
                if (source >= HOTBAR_SIZE) continue;
                ItemData item = snapshot.get(source);
                boolean pairFits = sources.stream().anyMatch(other -> other < HOTBAR_SIZE && other != source
                        && visiblyCompatible(item, snapshot.get(other))
                        && item.count() + snapshot.get(other).count() <= CANDIDATE_STACK_LIMIT);
                if (pairFits) return Optional.of(new Plan(feature, itemId, source, -1, List.of(emptyMain), snapshot));
            }
        }
        return Optional.empty();
    }

    /** A fresh visible-state check, not native NBT equality or a server acknowledgement. */
    public static boolean matchesSnapshot(Plan plan, List<ItemSlot> inventory) {
        return plan != null && plan.expectedItems().equals(snapshot(inventory));
    }

    /** Structural defense for public or forged plans; native tags still need adapter checks. */
    public static boolean protectsProductionSlots(Plan plan,int hoeHotbar) {
        if (plan==null || hoeHotbar<0 || hoeHotbar>=HOTBAR_SIZE || plan.expectedItems().size()!=INVENTORY_SIZE
                || plan.sourceIndex()<0 || plan.sourceIndex()>=INVENTORY_SIZE || plan.sourceIndex()==hoeHotbar
                || plan.destinations().contains(hoeHotbar)) return false;
        int materialHotbar=(hoeHotbar+1)%HOTBAR_SIZE;
        if (!plan.direct() && (plan.scratchHotbar()<0 || plan.scratchHotbar()>=HOTBAR_SIZE
                || plan.scratchHotbar()==hoeHotbar || plan.scratchHotbar()==materialHotbar
                || !safeScratch(plan.expectedItems().get(plan.scratchHotbar()),plan.itemId()))) return false;
        if (!ItemData.isProductionIngredientId(plan.itemId())) {
            if (plan.sourceIndex()==materialHotbar || plan.destinations().contains(materialHotbar)) return false;
            ItemData material=plan.expectedItems().get(materialHotbar);
            if (plan.direct() && plan.sourceIndex()>=HOTBAR_SIZE && (material.empty()
                    || visiblyCompatible(plan.expectedItems().get(plan.sourceIndex()),material))) return false;
        }
        return true;
    }

    private static List<ItemData> snapshot(List<ItemSlot> inventory) {
        if (inventory == null || inventory.size() != INVENTORY_SIZE) return List.of();
        ItemData[] items = new ItemData[INVENTORY_SIZE];
        for (ItemSlot slot : inventory) {
            if (slot == null || !slot.player() || slot.inventoryIndex() < 0 || slot.inventoryIndex() >= INVENTORY_SIZE
                    || items[slot.inventoryIndex()] != null || slot.item() == null || slot.item().id() == null
                    || slot.item().count() < 0 || slot.item().count() > CANDIDATE_STACK_LIMIT) return List.of();
            items[slot.inventoryIndex()] = slot.item();
        }
        return List.of(items);
    }

    private static boolean candidate(ItemData item, String itemId) {
        return item.is(itemId) && !item.hoe() && item.count() < CANDIDATE_STACK_LIMIT
                && item.quality() >= 0 && item.quality() <= 3
                // Stal wine has Vinery aging; the installed ancient drink is a plain KubeJS item.
                && (!ItemData.WINE.equals(itemId) || item.year() != null && item.year() >= 0);
    }

    private static boolean safeScratch(ItemData item,String itemId) {
        // Swapping the same product into the old source would make it an implicit
        // native QUICK_MOVE destination. Keep that displaced stack unrelated.
        return item.empty() || !item.hoe() && !ItemData.isProductionIngredientId(item.id()) && !item.is(itemId);
    }

    private static boolean visiblyCompatible(ItemData source, ItemData destination) {
        return !destination.empty() && !destination.hoe() && destination.count() < CANDIDATE_STACK_LIMIT
                && source.id().equals(destination.id()) && source.quality() == destination.quality()
                && Objects.equals(source.year(), destination.year());
    }

    /** mainDestination=true means native QUICK_MOVE's 9..35 destination region. */
    private static List<Integer> destinations(List<ItemData> snapshot, int source, int hoeHotbar, int protectedMaterial, boolean mainDestination) {
        List<Integer> destinations = new ArrayList<>();
        int from = mainDestination ? HOTBAR_SIZE : 0;
        int until = mainDestination ? INVENTORY_SIZE : HOTBAR_SIZE;
        for (int index = from; index < until; index++) {
            if (index != source && index != hoeHotbar && index != protectedMaterial && visiblyCompatible(snapshot.get(source), snapshot.get(index)))
                destinations.add(index);
        }
        return destinations;
    }

    private static boolean fullSourceFits(List<ItemData> snapshot, int source, List<Integer> destinations) {
        int capacity = 0;
        for (int index : destinations) capacity += CANDIDATE_STACK_LIMIT - snapshot.get(index).count();
        return !destinations.isEmpty() && capacity >= snapshot.get(source).count();
    }
}
