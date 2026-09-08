package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationRulesTest {
    @Test void classifierRejectsMissingWineAgeButTomatoesNeverRequireGrade() {
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, ""));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "2.5"));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "-1"));
        assertNull(RegistrationRules.classifier(PoiKind.TOMATO_CHEST, ""));
        assertNull(RegistrationRules.classifier(PoiKind.TOMATO_CHEST, " 3 "));
        assertEquals(3, RegistrationRules.classifier(PoiKind.WINE_CHEST, "7", 10));
        assertNull(RegistrationRules.classifier(PoiKind.BED, ""));
    }

    @Test void editingTomatoStorageClearsAnyLegacyClassifierWithoutUsingWineClock() {
        for (String previous:Arrays.asList(null,"0","1","2","3","4","unknown"))
            assertNull(RegistrationRules.classifier(PoiKind.TOMATO_CHEST,previous,null));
    }

    @Test void displayedAgeZeroUsesCurrentNativeWineCohort() {
        assertEquals(17, RegistrationRules.classifier(PoiKind.WINE_CHEST, "0", 17));
        assertEquals(15, RegistrationRules.classifier(PoiKind.WINE_CHEST, "2", 17));
        assertEquals(0, RegistrationRules.classifier(PoiKind.WINE_CHEST, "17", 17));
    }

    @Test void wineRegistrationRequiresClockAndRejectsNegativeCohort() {
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "0", null));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "0", -1));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "18", 17));
    }

    @Test void existingCohortKeepsIdentityWhileDisplayedAgeIncreases() {
        assertEquals(2, RegistrationRules.wineAge(15, 17));
        assertEquals(3, RegistrationRules.wineAge(15, 18));
        assertEquals(15, RegistrationRules.classifier(PoiKind.WINE_CHEST, "3", 18));
        assertNull(RegistrationRules.wineAge(15, null));
        assertNull(RegistrationRules.wineAge(-1, 17));
        assertNull(RegistrationRules.wineAge(18, 17));
    }

    @Test void farmBoundsAreInclusiveAndOverflowSafe() {
        assertTrue(RegistrationRules.validBounds(new Pos(0, 64, 0), new Pos(31, 95, 31)));
        assertFalse(RegistrationRules.validBounds(new Pos(0, 64, 0), new Pos(32, 95, 31)));
        assertFalse(RegistrationRules.validBounds(null, new Pos(0, 0, 0)));
        assertFalse(RegistrationRules.validBounds(new Pos(Integer.MIN_VALUE, 0, 0), new Pos(Integer.MAX_VALUE, 0, 0)));
        assertTrue(RegistrationRules.validBounds(new Pos(3, 64, 3), new Pos(-3, 65, -3)));
    }

    @Test void machineClassificationNeverOffersWineStorageForShipping() {
        assertEquals(List.of(PoiKind.SHIPPING_BIN), RegistrationRules.kinds(block("shippingbin:smart_shipping_bin", new Pos(0, 0, 0), true)));
        assertEquals(List.of(PoiKind.STORAGE_CANDIDATE, PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST, PoiKind.WOOD_CHEST), RegistrationRules.kinds(block("minecraft:chest", new Pos(0, 0, 0), true)));
        assertTrue(RegistrationRules.kinds(block("minecraft:stone", new Pos(0, 0, 0), false)).isEmpty());
    }

    @Test void suggestionsContainBothVineHeightsAndKeepSeparatedFieldsSeparate() {
        List<Farm> farms = RegistrationRules.suggestFarms(List.of(
            tomato(0, 64, 0), block("farmersdelight:tomatoes_on_rope", new Pos(0, 65, 0), false), tomato(2, 64, 0), tomato(2, 65, 0),
            tomato(20, 64, 0), tomato(21, 64, 0)));
        assertEquals(2, farms.size());
        assertEquals(new Pos(0, 64, 0), farms.get(0).first());
        assertEquals(new Pos(2, 65, 0), farms.get(0).second());
        assertEquals(new Pos(20, 64, 0), farms.get(1).first());
        assertFalse(RegistrationRules.overlap(farms.get(0), farms.get(1)));
        assertTrue(RegistrationRules.overlap(farms.get(0), new Farm("touches", new Pos(2, 64, 0), new Pos(3, 65, 2))));
    }

    private static BlockData tomato(int x, int y, int z) { return block("farmersdelight:tomatoes", new Pos(x, y, z), false); }
    private static BlockData block(String id, Pos p, boolean container) { return new BlockData(p, id, Map.of("container", String.valueOf(container))); }

    @Test void parallelRowsAndVerticalVinesFormTwoFieldsAcrossTwoBlockPath() {
        List<BlockData> scan = new ArrayList<>();
        for (int z : new int[]{0, 2, 4, 7, 9}) for (int x = 0; x < 6; x++) {
            scan.add(tomato(x, 64, z));
            scan.add(block("farmersdelight:tomatoes_on_rope", new Pos(x, 65, z), false));
        }
        List<Farm> fields = RegistrationRules.suggestFarms(scan);
        assertEquals(2, fields.size());
        assertEquals(new Pos(5, 65, 4), fields.get(0).second());
        assertEquals(new Pos(0, 64, 7), fields.get(1).first());
        assertEquals(new Pos(5, 65, 9), fields.get(1).second());
    }

    @Test void oneBlockPathRemainsAnAmbiguousSingleSuggestionRequiringManualCorners() {
        List<BlockData> scan = new ArrayList<>();
        for (int z : new int[]{0, 2, 4, 6, 8}) for (int x = 0; x < 6; x++) scan.add(tomato(x, 64, z));
        assertEquals(1, RegistrationRules.suggestFarms(scan).size());
    }

    @Test void verticallySeparatedFloorsDoNotMergeThroughMissingVines() {
        assertEquals(2, RegistrationRules.suggestFarms(List.of(
                tomato(0, 64, 0), tomato(0, 65, 0), tomato(0, 68, 0), tomato(0, 69, 0))).size());
    }

    @Test void partialChunkInsideScanRadiusCannotBePresentedAsCompleteBounds() {
        Farm clipped = new Farm("", new Pos(0, 64, 0), new Pos(15, 65, 4));
        Pos center = new Pos(0, 64, 0);
        assertFalse(RegistrationRules.mayBePartial(clipped, center, 32, 16, p -> true));
        assertTrue(RegistrationRules.mayBePartial(clipped, center, 32, 16, p -> p.x() < 16));
        Farm spansHole = new Farm("", new Pos(0, 64, 0), new Pos(30, 65, 4));
        assertTrue(RegistrationRules.mayBePartial(spansHole, center, 32, 16, p -> p.x() < 16 || p.x() >= 24));
    }

    @Test void bothHorizontalAndVerticalScanEdgesProducePartialWarning() {
        Pos center = new Pos(0, 64, 0);
        assertTrue(RegistrationRules.mayBePartial(new Farm("", new Pos(30, 64, 0), new Pos(32, 65, 4)), center, 32, 16, p -> true));
        assertTrue(RegistrationRules.mayBePartial(new Farm("", new Pos(0, 48, 0), new Pos(2, 49, 4)), center, 32, 16, p -> true));
        assertTrue(RegistrationRules.mayBePartial(new Farm("", new Pos(0, 79, 0), new Pos(2, 80, 4)), center, 32, 16, p -> true));
    }

    @Test void machineGroupJoinsRackNeighborsButNotDifferentTypesOrDistantRacks() {
        BlockData seed = block("society:wine_keg", new Pos(0, 64, 0), false);
        List<BlockData> group = RegistrationRules.connectedMachines(List.of(seed,
                block("society:wine_keg", new Pos(3, 64, 0), false),
                block("society:wine_keg", new Pos(3, 67, 0), false),
                block("society:wine_keg", new Pos(7, 67, 0), false),
                block("society:preserves_jar", new Pos(1, 64, 0), false)), seed);
        assertEquals(3, group.size());
        assertEquals(new Pos(0, 64, 0), RegistrationRules.blockBounds(group).first());
        assertEquals(new Pos(3, 67, 0), RegistrationRules.blockBounds(group).second());
    }

    @Test void bulkRegistrationNeverInfersStorageOrBedClassifications() {
        BlockData chest = block("minecraft:chest", new Pos(0, 64, 0), true);
        BlockData bed = block("minecraft:red_bed", new Pos(1, 64, 0), false);
        assertTrue(RegistrationRules.connectedMachines(List.of(chest, bed), chest).isEmpty());
        assertTrue(RegistrationRules.connectedMachines(List.of(chest, bed), bed).isEmpty());
    }

    @Test void bulkGroupingUsesManhattanDistanceAndDeduplicatesPositions() {
        BlockData seed = block("society:preserves_jar", new Pos(0, 64, 0), false);
        BlockData diagonal = block("society:preserves_jar", new Pos(2, 64, 2), false);
        assertEquals(1, RegistrationRules.connectedMachines(List.of(seed, seed, diagonal), seed).size());
    }
}
