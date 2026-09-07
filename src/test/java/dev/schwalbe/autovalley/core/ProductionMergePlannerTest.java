package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProductionMergePlannerTest {
    private static final int HOE = 4, MATERIAL = 5, SCRATCH = 6;
    private static final ItemData STONE = new ItemData("minecraft:stone", 64, 0, null, false, Integer.MAX_VALUE);
    private static final ItemData PICKAXE = new ItemData("minecraft:diamond_pickaxe", 1, 0, null, false, 1500);
    private static final ItemData GOLDEN_HOE = new ItemData("minecraft:golden_hoe", 1, 0, null, true, 200);

    @Test void fullHotbarNeverBorrowsTheActiveFiftyTwoTomatoMaterialStack() {
        List<ItemSlot> inventory=inventory(); put(inventory,MATERIAL,tomato(52));
        put(inventory,9,wine(1,12)); put(inventory,10,wine(50,12));
        ProductionMergePlanner.Plan occupied=ProductionMergePlanner.plan(inventory,Feature.WINE,HOE,9).orElseThrow();
        assertEquals(SCRATCH,occupied.scratchHotbar()); assertTrue(occupied.requiresRestore()); assertEquals(3,occupied.maximumClicks());
        assertEquals(STONE,occupied.expectedItems().get(SCRATCH)); assertTrue(ProductionMergePlanner.protectsProductionSlots(occupied,HOE));
        assertEquals(tomato(52),inventory.get(MATERIAL).item());
        put(inventory,SCRATCH,ItemData.EMPTY);
        ProductionMergePlanner.Plan plan=ProductionMergePlanner.plan(inventory,Feature.WINE,HOE,9).orElseThrow();
        assertEquals(SCRATCH,plan.scratchHotbar()); assertFalse(plan.requiresRestore()); assertEquals(2,plan.maximumClicks());
        assertTrue(ProductionMergePlanner.protectsProductionSlots(plan,HOE));
        assertNotEquals(MATERIAL,plan.sourceIndex()); assertFalse(plan.destinations().contains(MATERIAL));
        assertEquals(tomato(52),plan.expectedItems().get(MATERIAL));
    }

    @Test void productAlreadyInTheMaterialSlotIsNeitherAMergeSourceNorDestination() {
        for (ItemData product:List.of(wine(1,12),preserves(1,0))) {
            Feature feature=product.is(ItemData.WINE) ? Feature.WINE : Feature.PRESERVES;
            List<ItemSlot> inventory=inventory(); put(inventory,MATERIAL,product); put(inventory,10,product);
            assertTrue(ProductionMergePlanner.plan(inventory,feature,HOE,MATERIAL).isEmpty());
        }
    }

    @Test void directNativeTransferCannotFallBackIntoAnEmptyOrCompatibleMaterialSlot() {
        List<ItemSlot> inventory=inventory(); put(inventory,20,wine(50,12));
        for (int index:List.of(0,1,2)) put(inventory,index,wine(40,12));
        assertEquals(20,ProductionMergePlanner.plan(inventory,Feature.WINE,HOE,20).orElseThrow().sourceIndex());
        for (ItemData material:List.of(ItemData.EMPTY,wine(10,12))) {
            put(inventory,MATERIAL,material);
            assertTrue(ProductionMergePlanner.plan(inventory,Feature.WINE,HOE,20).isEmpty());
        }
    }

    @Test void hotbarProductCanMergeDirectlyIntoMainInventory() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, wine(50, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 0).orElseThrow();
        assertTrue(plan.direct()); assertEquals(0, plan.sourceIndex()); assertEquals(-1, plan.scratchHotbar());
        assertEquals(0, plan.quickMoveIndex()); assertEquals(List.of(10), plan.destinations());
        assertEquals(1, plan.maximumClicks()); assertFalse(plan.requiresRestore());
        assertEquals(Feature.WINE, plan.feature()); assertEquals(ItemData.WINE, plan.itemId());
        assertEquals(GOLDEN_HOE, plan.expectedItems().get(HOE));
    }

    @Test void mainProductCanMergeDirectlyIntoHotbarButNeverTheHoeSlot() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(50, 12)); put(inventory, 20, wine(10, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).orElseThrow();
        assertTrue(plan.direct()); assertEquals(20, plan.sourceIndex()); assertEquals(List.of(0), plan.destinations());
        put(inventory, 0, STONE); put(inventory, HOE, wine(50, 12));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty(), "even a product in the reserved hoe slot is protected");
    }

    @Test void preferredSourceBreaksTiesWithoutPreferringThreeClicksOverOne() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 0, wine(5, 10)); put(inventory, 10, wine(40, 10));
        put(inventory, 20, wine(10, 12)); put(inventory, 21, wine(20, 12)); put(inventory, SCRATCH, tomato(12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).orElseThrow();
        assertTrue(plan.direct()); assertEquals(0, plan.sourceIndex());
        plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 10).orElseThrow();
        assertTrue(plan.direct()); assertEquals(10, plan.sourceIndex());
    }

    @Test void sameRegionProductsUseAnEmptyAlternateHotbarWithoutMovingTheMaterialStack() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, wine(10, 12)); put(inventory, 10, wine(50, 12)); put(inventory, MATERIAL, tomato(12)); put(inventory, SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 9).orElseThrow();
        assertFalse(plan.direct()); assertEquals(9, plan.sourceIndex()); assertEquals(SCRATCH, plan.scratchHotbar());
        assertEquals(SCRATCH, plan.quickMoveIndex()); assertEquals(List.of(10), plan.destinations());
        assertFalse(plan.requiresRestore()); assertEquals(2, plan.maximumClicks());
        assertEquals(tomato(12), plan.expectedItems().get(MATERIAL)); assertTrue(plan.expectedItems().get(SCRATCH).empty());
        assertEquals(GOLDEN_HOE, plan.expectedItems().get(HOE));
    }

    @Test void emptyScratchNeedsNoFinalEmptyToEmptyRestoreClick() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, preserves(10, 2)); put(inventory, 10, preserves(50, 2)); put(inventory, SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.PRESERVES, HOE, 9).orElseThrow();
        assertFalse(plan.direct()); assertFalse(plan.requiresRestore()); assertEquals(2, plan.maximumClicks());
        assertEquals(ItemData.PRESERVES, plan.itemId());
    }

    @Test void scratchWrapsAfterLastHoeSlotWithoutUsingTheHoe() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 8, GOLDEN_HOE); put(inventory, 0, tomato(12)); put(inventory, 1, ItemData.EMPTY);
        put(inventory, 9, wine(10, 12)); put(inventory, 10, wine(50, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, 8, 9).orElseThrow();
        assertEquals(1, plan.scratchHotbar()); assertEquals(2, plan.maximumClicks());
        assertFalse(plan.destinations().contains(8)); assertNotEquals(8, plan.sourceIndex());
    }

    @Test void ingredientHoeAndSameProductCannotBeBorrowedEvenWhenAllOtherHotbarSlotsAreUnsafe() {
        for (ItemData scratch : List.of(tomato(52), GOLDEN_HOE, wine(10, 11), new ItemData(ItemData.TOMATO, 10, 0, null, true, 0))) {
            List<ItemSlot> inventory = inventory();
            for (int index=0;index<9;index++) if (index!=HOE) put(inventory,index,tomato(52));
            put(inventory, 9, wine(10, 12)); put(inventory, 10, wine(50, 12)); put(inventory, SCRATCH, scratch);
            assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 9).isEmpty(), scratch.id());
        }
    }

    @Test void destinationCapacityCanSpanSeveralPartialStacksButMustFitTheEntireSource() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 0, wine(18, 12)); put(inventory, 10, wine(50, 12)); put(inventory, 11, wine(60, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 0).orElseThrow();
        assertEquals(List.of(10, 11), plan.destinations()); assertEquals(0, plan.sourceIndex());
        put(inventory, 0, wine(19, 12));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 0).isEmpty(), "moving some items without freeing a slot is not a plan");
    }

    @Test void anEmptyDestinationOrFullSourceIsNotAConsolidationCandidate() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, ItemData.EMPTY);
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
        put(inventory, 0, wine(64, 12)); put(inventory, 10, wine(1, 12));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
    }

    @Test void wineYearQualityAndProductFeatureStaySeparate() {
        for (ItemData other : List.of(wine(50, 11), new ItemData(ItemData.WINE, 50, 1, 12, false, 0), preserves(50, 0))) {
            List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, other);
            assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
        }
        List<ItemSlot> inventory = inventory(); put(inventory, 0, preserves(10, 0)); put(inventory, 10, preserves(50, 1));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.PRESERVES, HOE).isEmpty());
        put(inventory, 10, preserves(50, 0));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.PRESERVES, HOE).isPresent());
    }

    @Test void missingOrInvalidWineYearIsNeverGuessedFromAnotherStack() {
        for (Integer year : new Integer[]{null, -1}) {
            List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, year)); put(inventory, 10, wine(50, year));
            assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
        }
    }

    @Test void publicMetadataIsOnlyACandidateAndCannotProveHiddenNativeNbtMatches() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, wine(50, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 0).orElseThrow();
        Map<Integer, String> hiddenNativeTags = Map.of(0, "custom-name:A", 10, "custom-name:B");
        assertTrue(ProductionMergePlanner.matchesSnapshot(plan, inventory));
        assertTrue(plan.destinations().stream().noneMatch(index -> hiddenNativeTags.get(index).equals(hiddenNativeTags.get(plan.sourceIndex()))),
                "the adapter must reject this candidate with native same-item/same-tags checks; public snapshot equality is not proof");
    }

    @Test void snapshotsAreImmutableAndChangesAfterPlanningRequireRevalidation() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, wine(50, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 0).orElseThrow();
        assertTrue(ProductionMergePlanner.matchesSnapshot(plan, inventory));
        assertThrows(UnsupportedOperationException.class, () -> plan.expectedItems().set(0, ItemData.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> plan.destinations().add(20));
        put(inventory, 0, wine(11, 12));
        assertFalse(ProductionMergePlanner.matchesSnapshot(plan, inventory));
        assertEquals(10, plan.expectedItems().get(0).count());
    }

    @Test void cancellingAfterScratchSwapReplansFromObservedLayoutInsteadOfBlindlyRestoring() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, wine(10, 12)); put(inventory, 10, wine(50, 12)); put(inventory, SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan original = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 9).orElseThrow();
        put(inventory, 9, ItemData.EMPTY); put(inventory, SCRATCH, wine(10, 12));
        assertFalse(ProductionMergePlanner.matchesSnapshot(original, inventory));
        ProductionMergePlanner.Plan resumed = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 9).orElseThrow();
        assertTrue(resumed.direct()); assertEquals(SCRATCH, resumed.sourceIndex());
        assertEquals(List.of(10), resumed.destinations()); assertTrue(resumed.expectedItems().get(9).empty());
        assertEquals(1, resumed.maximumClicks());
    }

    @Test void cancellingAfterMergeDoesNotInventARestoreForTheOldTomatoLayout() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, tomato(12)); put(inventory, 10, wine(60, 12)); put(inventory, SCRATCH, ItemData.EMPTY);
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 9).isEmpty());
        assertEquals(tomato(12), inventory.get(9).item()); assertTrue(inventory.get(SCRATCH).item().empty());
    }

    @Test void completeSnapshotUsesInventoryIndicesRegardlessOfMenuIndicesOrListOrder() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, wine(50, 12));
        ProductionMergePlanner.Plan original = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 0).orElseThrow();
        List<ItemSlot> shuffled = new ArrayList<>();
        for (ItemSlot slot : inventory) shuffled.add(new ItemSlot(slot.index() + 100, slot.inventoryIndex(), true, slot.item()));
        Collections.reverse(shuffled);
        assertEquals(original, ProductionMergePlanner.plan(shuffled, Feature.WINE, HOE, 0).orElseThrow());
        assertTrue(ProductionMergePlanner.matchesSnapshot(original, shuffled));
    }

    @Test void incompleteDuplicateForeignOrOutOfRangeSlotsFailClosed() {
        List<ItemSlot> valid = inventory(); put(valid, 0, wine(10, 12)); put(valid, 10, wine(50, 12));
        assertTrue(ProductionMergePlanner.plan(null, Feature.WINE, HOE).isEmpty());
        assertTrue(ProductionMergePlanner.plan(valid.subList(0, 35), Feature.WINE, HOE).isEmpty());
        List<ItemSlot> extra = new ArrayList<>(valid); extra.add(new ItemSlot(40, 40, true, ItemData.EMPTY));
        assertTrue(ProductionMergePlanner.plan(extra, Feature.WINE, HOE).isEmpty());
        for (ItemSlot invalid : java.util.Arrays.asList(null,
                new ItemSlot(35, 0, true, STONE), new ItemSlot(35, 35, false, STONE),
                new ItemSlot(35, 36, true, STONE), new ItemSlot(35, -1, true, STONE),
                new ItemSlot(35, 35, true, null), new ItemSlot(35, 35, true, wine(65, 12)))) {
            List<ItemSlot> inventory = new ArrayList<>(valid); inventory.set(35, invalid);
            assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
        }
    }

    @Test void onlyCurrentProductionFeaturesAndValidHoeIndicesAreAccepted() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, wine(10, 12)); put(inventory, 10, wine(50, 12));
        for (Feature feature : Feature.values()) {
            if (feature != Feature.WINE && feature != Feature.PRESERVES)
                assertTrue(ProductionMergePlanner.plan(inventory, feature, HOE).isEmpty(), feature.name());
        }
        assertTrue(ProductionMergePlanner.plan(inventory, null, HOE).isEmpty());
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, -1).isEmpty());
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, 9).isEmpty());
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 999).isPresent(), "an invalid preference grants no extra permission");
    }

    @Test void compatiblePartialHoeSlotRejectsMainToHotbarPlanEvenWhenOtherSlotsHaveCapacity() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 20, wine(50, 12));
        for (int index : List.of(0, 1, 2)) put(inventory, index, wine(40, 12));
        ProductionMergePlanner.Plan safe = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).orElseThrow();
        assertTrue(safe.direct()); assertEquals(20, safe.sourceIndex());
        put(inventory, HOE, wine(10, 12));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).isEmpty(),
                "native QUICK_MOVE could fill the protected partial stack even though the advertised destinations exclude it");
        put(inventory, HOE, wine(64, 12));
        assertEquals(20, ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).orElseThrow().sourceIndex());
    }

    @Test void protectedHoeDoesNotForbidSafeReverseDirectionIntoMainInventory() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 20, wine(10, 12)); put(inventory, 0, wine(50, 12)); put(inventory, HOE, wine(10, 12));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).orElseThrow();
        assertTrue(plan.direct()); assertEquals(0, plan.sourceIndex()); assertEquals(List.of(20), plan.destinations());
    }

    @Test void emptyProtectedHoeAlsoRejectsMainToHotbarEvenWithVisibleMergeCapacity() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 20, wine(50, 12));
        for (int index : List.of(0, 1, 2)) put(inventory, index, wine(40, 12));
        assertEquals(20, ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).orElseThrow().sourceIndex());
        put(inventory, HOE, ItemData.EMPTY);
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 20).isEmpty(),
                "native QUICK_MOVE must not fall back to the empty protected hoe slot when tags do not merge");
    }

    @Test void emptyProtectedHoeStillAllowsBoundedScratchPlanIntoMainInventory() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, wine(10, 12)); put(inventory, 10, wine(50, 12));
        put(inventory, MATERIAL, tomato(12)); put(inventory, SCRATCH, ItemData.EMPTY); put(inventory, HOE, ItemData.EMPTY);
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.plan(inventory, Feature.WINE, HOE, 9).orElseThrow();
        assertFalse(plan.direct()); assertEquals(SCRATCH, plan.scratchHotbar());
        assertEquals(List.of(10), plan.destinations()); assertTrue(plan.expectedItems().get(HOE).empty());
    }

    @Test void tomatoFragmentPlansKeepTheOwningProductionFeatureAndExplicitItemId() {
        for (Feature feature : List.of(Feature.WINE, Feature.PRESERVES)) {
            List<ItemSlot> inventory = inventory(); put(inventory, 0, tomato(2)); put(inventory, 10, tomato(2));
            ProductionMergePlanner.Plan plan = ProductionMergePlanner.planTomatoes(inventory, feature, HOE, 0).orElseThrow();
            assertEquals(feature, plan.feature()); assertEquals(ItemData.TOMATO, plan.itemId());
            assertTrue(plan.direct()); assertEquals(0, plan.sourceIndex()); assertEquals(List.of(10), plan.destinations());
            assertTrue(ProductionMergePlanner.plan(inventory, feature, HOE).isEmpty(), "ingredient plans must be requested explicitly");
        }
    }

    @Test void selectedTomatoGradeCannotBeReplacedByAnotherEasierMerge() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 0, tomato(1)); put(inventory, 10, tomato(2));
        put(inventory, 1, tomato(3, 2)); put(inventory, 11, tomato(4, 2));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 2, 0).orElseThrow();
        assertEquals(1, plan.sourceIndex()); assertEquals(List.of(11), plan.destinations());
        assertEquals(2, plan.expectedItems().get(plan.sourceIndex()).quality());
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 3, null).isEmpty());
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, -1, null).isEmpty());
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 4, null).isEmpty());
    }

    @Test void differentGradeMaterialIsPreservedWhileAnEmptyAlternateScratchMergesIngredients() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, tomato(10, 2)); put(inventory, 10, tomato(50, 2)); put(inventory, MATERIAL, tomato(12, 0)); put(inventory, SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.planTomatoes(inventory, Feature.PRESERVES, HOE, 2, 9).orElseThrow();
        assertFalse(plan.direct()); assertEquals(9, plan.sourceIndex()); assertEquals(SCRATCH, plan.scratchHotbar());
        assertNotEquals(plan.sourceIndex(), plan.scratchHotbar()); assertEquals(List.of(10), plan.destinations());
        assertFalse(plan.destinations().contains(9)); assertEquals(2, plan.maximumClicks());
    }

    @Test void fullSameGradeMaterialIsPreservedAndNeverInventedAsDestinationCapacity() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, tomato(10)); put(inventory, 10, tomato(50)); put(inventory, MATERIAL, tomato(64)); put(inventory, SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 0, 9).orElseThrow();
        assertFalse(plan.direct()); assertEquals(List.of(10), plan.destinations()); assertEquals(2, plan.maximumClicks());
        put(inventory, 10, tomato(60));
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 0, 9).isEmpty());
    }

    @Test void partialSameGradeScratchUsesDirectMergeInsteadOfUnnecessarySwaps() {
        List<ItemSlot> inventory = inventory();
        put(inventory, 9, tomato(10)); put(inventory, 10, tomato(50)); put(inventory, SCRATCH, tomato(20));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 0, 9).orElseThrow();
        assertTrue(plan.direct()); assertEquals(1, plan.maximumClicks()); assertEquals(-1, plan.scratchHotbar());
    }

    @Test void tomatoPlannerAlsoRejectsNonProductionFeaturesAndMismatchedGrades() {
        List<ItemSlot> inventory = inventory(); put(inventory, 0, tomato(2)); put(inventory, 10, tomato(2, 1));
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE).isEmpty());
        put(inventory, 10, tomato(2));
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.HARVEST, HOE).isEmpty());
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.TOMATO_STORAGE, HOE).isEmpty());
    }

    @Test void hotbarOnlyTomatoFragmentsCanBridgeOnceToAnEmptyMainSlot() {
        List<ItemSlot> inventory = inventoryWithEmptyMain();
        put(inventory, 0, GOLDEN_HOE); put(inventory, 1, tomato(2)); put(inventory, 2, tomato(1));
        ProductionMergePlanner.Plan bridge = ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, 0, 0, 1).orElseThrow();
        assertTrue(bridge.direct()); assertTrue(bridge.reposition()); assertEquals(ItemData.TOMATO, bridge.itemId());
        assertEquals(1, bridge.sourceIndex()); assertEquals(List.of(9), bridge.destinations());
        assertEquals(1, bridge.maximumClicks()); assertFalse(bridge.requiresRestore());
        assertEquals(-1, bridge.scratchHotbar());
        // Vanilla may choose another empty main slot. No old destination is replayed:
        // the next candidate is planned from that actual acknowledged placement.
        put(inventory, 1, ItemData.EMPTY); put(inventory, 15, tomato(2));
        ProductionMergePlanner.Plan merge = ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, 0, 0, 1).orElseThrow();
        assertFalse(merge.reposition()); assertTrue(merge.direct()); assertEquals(2, merge.sourceIndex());
        assertEquals(List.of(15), merge.destinations());
    }

    @Test void bridgeDoesNotNeedToTouchAToolInTheProductionScratchSlot() {
        List<ItemSlot> inventory = inventoryWithEmptyMain();
        put(inventory, 1, tomato(2)); put(inventory, 2, tomato(1)); put(inventory, SCRATCH, PICKAXE);
        ProductionMergePlanner.Plan bridge = ProductionMergePlanner.planTomatoes(inventory, Feature.PRESERVES, HOE, 0, 2).orElseThrow();
        assertTrue(bridge.reposition()); assertEquals(2, bridge.sourceIndex());
        assertEquals(PICKAXE, bridge.expectedItems().get(SCRATCH)); assertEquals(-1, bridge.scratchHotbar());
    }

    @Test void bridgeRequiresTwoCompatibleHotbarFragmentsWhoseTotalFitsOneStack() {
        List<ItemSlot> inventory = inventoryWithEmptyMain();
        put(inventory, 1, tomato(40)); put(inventory, 2, tomato(40));
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE).isEmpty());
        put(inventory, 1, tomato(2)); put(inventory, 2, tomato(1, 1));
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE).isEmpty());
        put(inventory, 2, STONE); put(inventory, HOE, tomato(1));
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE).isEmpty(), "the protected hoe slot cannot be a partner");
    }

    @Test void bridgeIsTomatoOnlyAndRequiresAnActuallyEmptyMainSlot() {
        List<ItemSlot> inventory = inventoryWithEmptyMain();
        put(inventory, 1, wine(2, 12)); put(inventory, 2, wine(1, 12));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.WINE, HOE).isEmpty());
        put(inventory, 1, preserves(2, 0)); put(inventory, 2, preserves(1, 0));
        assertTrue(ProductionMergePlanner.plan(inventory, Feature.PRESERVES, HOE).isEmpty());
        put(inventory, 1, tomato(2)); put(inventory, 2, tomato(1));
        for (int index = 9; index < 36; index++) put(inventory, index, STONE);
        assertTrue(ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE).isEmpty());
    }

    @Test void anExistingDirectMergeAlwaysPrecedesTheTomatoBridge() {
        List<ItemSlot> inventory = inventoryWithEmptyMain();
        put(inventory, 1, tomato(2)); put(inventory, 2, tomato(1)); put(inventory, 9, tomato(40));
        ProductionMergePlanner.Plan plan = ProductionMergePlanner.planTomatoes(inventory, Feature.WINE, HOE, 0, 1).orElseThrow();
        assertFalse(plan.reposition()); assertEquals(List.of(9), plan.destinations()); assertEquals(1, plan.maximumClicks());
    }

    private static List<ItemSlot> inventory() {
        List<ItemSlot> slots = new ArrayList<>();
        for (int index = 0; index < 36; index++) slots.add(new ItemSlot(index, index, true, index == HOE ? GOLDEN_HOE : STONE));
        return slots;
    }

    private static List<ItemSlot> inventoryWithEmptyMain() {
        List<ItemSlot> inventory = inventory();
        for (int index = 9; index < 36; index++) put(inventory, index, ItemData.EMPTY);
        return inventory;
    }

    private static void put(List<ItemSlot> inventory, int index, ItemData item) {
        inventory.set(index, new ItemSlot(index, index, true, item));
    }
    private static ItemData wine(int count, Integer year) { return new ItemData(ItemData.WINE, count, 0, year, false, 0); }
    private static ItemData preserves(int count, int quality) { return new ItemData(ItemData.PRESERVES, count, quality, null, false, 0); }
    private static ItemData tomato(int count) { return new ItemData(ItemData.TOMATO, count, 0, null, false, 0); }
    private static ItemData tomato(int count, int quality) { return new ItemData(ItemData.TOMATO, count, quality, null, false, 0); }
}
