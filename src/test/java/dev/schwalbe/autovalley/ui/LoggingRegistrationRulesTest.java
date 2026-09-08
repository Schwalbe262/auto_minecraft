package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoggingRegistrationRulesTest {
    @Test void woodIsOnlyOfferedForOrdinaryStorageAndNeverForShopsOrShipping() {
        for (String id : List.of("minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel"))
            assertTrue(RegistrationRules.kinds(block(new Pos(0, 64, 0), id, true)).contains(PoiKind.WOOD_CHEST));
        for (String id : List.of("numismatics:bank_terminal", "some_mod:shop", "shippingbin:smart_shipping_bin"))
            assertFalse(RegistrationRules.kinds(block(new Pos(0, 64, 0), id, true)).contains(PoiKind.WOOD_CHEST));
    }

    @Test void craftingTableHasItsOwnExplicitRegistrationAndIsNotBulkInferred() {
        BlockData table = block(new Pos(0, 64, 0), "minecraft:crafting_table", false);
        assertEquals(List.of(PoiKind.LOGGING_CRAFTING_TABLE), RegistrationRules.kinds(table));
        assertEquals(RegistrationRules.Group.MACHINES, RegistrationRules.group(table));
        assertTrue(RegistrationRules.connectedMachines(List.of(table), table).isEmpty());
    }

    @Test void woodNeedsManualContentConfirmationButNeverAQualityOrWineClock() {
        assertTrue(RegistrationRules.requiresContentsConfirmation(PoiKind.WOOD_CHEST));
        assertNull(RegistrationRules.classifier(PoiKind.WOOD_CHEST, "anything", null));
        assertFalse(RegistrationRules.requiresContentsConfirmation(PoiKind.LOGGING_CRAFTING_TABLE));
        assertTrue(RegistrationRules.requiresContentsConfirmation(PoiKind.WINE_CHEST));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "0", null));
    }

    @Test void plantingDraftRequiresTheFourSameHeightSpruceCellsEastAndSouth() {
        Pos corner = new Pos(-12, 64, -12);
        Set<Pos> checked = new HashSet<>();
        assertTrue(RegistrationRules.loggingBaseReady(corner, p -> true, p -> {
            checked.add(p); return block(p, "minecraft:spruce_sapling", false);
        }));
        assertEquals(Set.of(corner, corner.offset(1, 0, 0), corner.offset(0, 0, 1), corner.offset(1, 0, 1)), checked);
        assertTrue(RegistrationRules.loggingBaseReady(corner, p -> true, p -> block(p, "minecraft:spruce_log", false)));
    }

    @Test void unloadOrWrongSpeciesNeverBecomesAValidPlot() {
        Pos corner = new Pos(0, 64, 0), missing = corner.offset(1, 0, 1);
        assertFalse(RegistrationRules.loggingBaseReady(corner, p -> !p.equals(missing), p -> {
            assertNotEquals(missing, p, "never query an unloaded block"); return block(p, "minecraft:spruce_log", false);
        }));
        for (String other : List.of("minecraft:air", "minecraft:oak_sapling", "minecraft:oak_log", "minecraft:dirt"))
            assertFalse(RegistrationRules.loggingBaseReady(corner, p -> true,
                    p -> block(p, p.equals(missing) ? other : "minecraft:spruce_log", false)));
        assertFalse(RegistrationRules.loggingBaseReady(corner, p -> true, p -> null));
        assertFalse(RegistrationRules.loggingBaseReady(corner, p -> true, p -> block(corner, "minecraft:spruce_log", false)));
    }

    @Test void cornerOverflowAndUnknownDraftAreRejectedBeforeReadingTheWorld() {
        for (Pos corner : Arrays.asList(null, new Pos(Integer.MAX_VALUE, 64, 0), new Pos(0, 64, Integer.MAX_VALUE)))
            assertFalse(RegistrationRules.loggingBaseReady(corner, p -> { fail("no world lookup"); return true; }, p -> null));
    }

    @Test void onlyUsableNetheriteAxeInANonHoeHotbarSlotIsRegistrable() {
        ItemData axe = new ItemData("minecraft:netherite_axe", 1, 0, null, false, 10);
        assertTrue(RegistrationRules.loggingAxe(axe, 2, 0));
        for (int slot : new int[] {-1, 0, 9}) assertFalse(RegistrationRules.loggingAxe(axe, slot, 0));
        for (int durability : new int[] {0, 1})
            assertFalse(RegistrationRules.loggingAxe(new ItemData(axe.id(), 1, 0, null, false, durability), 2, 0));
        for (String id : List.of("minecraft:diamond_axe", "minecraft:netherite_hoe", "minecraft:spruce_log"))
            assertFalse(RegistrationRules.loggingAxe(new ItemData(id, 1, 0, null, false, 100), 2, 0));
        assertFalse(RegistrationRules.loggingAxe(ItemData.EMPTY, 2, 0));
    }

    @Test void loggingIntervalRoundTripsWithoutRoundingAwayServerTicks() {
        for (int ticks : new int[] {20, 21, 25, 1200, 24000})
            assertEquals(ticks, RegistrationRules.loggingCheckTicks(RegistrationRules.loggingCheckSeconds(ticks)));
        for (String text : Arrays.asList(null, "", "abc", "0", "0.95", "1200.05", "1.001", "NaN"))
            assertThrows(IllegalArgumentException.class, () -> RegistrationRules.loggingCheckTicks(text));
    }

    @Test void tenModuleButtonsKeepFooterAboveCloseAt240AndOneShotFitsThreeRows() {
        int moduleCount = (int) Arrays.stream(Feature.values()).filter(f -> f != Feature.STORAGE_SURVEY).count();
        assertEquals(10, moduleCount);
        int columns = RegistrationRules.moduleColumns(moduleCount);
        assertEquals(4, columns); assertEquals(3, RegistrationRules.moduleColumns(9));
        int lastControlY = 54 + ((moduleCount + columns - 1) / columns) * 23 + 4 + 41 + 24;
        assertTrue(lastControlY + 20 <= 240 - 25);
        int onceBackY = 92 + ((Feature.values().length + 3) / 4) * 24 + 14;
        assertTrue(onceBackY + 20 <= 240 - 25);
    }

    private static BlockData block(Pos pos, String id, boolean container) {
        return new BlockData(pos, id, container ? Map.of("container", "true") : Map.of());
    }
}
