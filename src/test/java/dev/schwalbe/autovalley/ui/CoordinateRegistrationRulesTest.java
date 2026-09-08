package dev.schwalbe.autovalley.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateRegistrationRulesTest {
    @Test void IntegerCoordinatesPreserveNegativeAndZeroValues() {
        assertEquals(new Pos(-12, 0, 34), CoordinateDestinationRules.parsePosition(" -12 ", "0", "+34"));
        assertEquals(new Pos(29_999_984, 2047, -29_999_984), CoordinateDestinationRules.parsePosition("29999984", "2047", "-29999984"));
        assertTrue(CoordinateDestinationRules.validPosition(new Pos(0, -2048, 0)));
    }

    @Test void MissingFractionalOverflowAndOutOfRangeCoordinatesFailBeforeWorldQueries() {
        for (String bad : Arrays.asList(null, "", "1.2", "1e3", "NaN", "Infinity", "2147483648", "30000000"))
            assertThrows(IllegalArgumentException.class, () -> CoordinateDestinationRules.parsePosition(bad, "64", "0"));
        for (String y : List.of("-2049", "2048", "2147483647"))
            assertThrows(IllegalArgumentException.class, () -> CoordinateDestinationRules.parsePosition("0", y, "0"));
        assertFalse(CoordinateDestinationRules.validPosition(new Pos(Integer.MIN_VALUE, 0, 0)));
        assertFalse(CoordinateDestinationRules.validPosition(null));
    }

    @Test void FeetDraftHasNoFacilityClassifierOrContentAuthority() {
        CoordinateDestination feet = draft(null, "", null, null, false);
        assertNull(feet.facilityKind()); assertNull(feet.classifier()); assertFalse(feet.contentsConfirmed());
        assertEquals(RegistrationRules.CoordinateState.MOVE_ONLY, RegistrationRules.coordinateState(feet,
                p -> { fail("no facility load query"); return false; }, p -> { fail("no block query"); return null; }));
    }

    @Test void UnloadedDraftIsPendingWithoutQueryingOrRegisteringItsBlock() {
        CoordinateDestination keg = draft(PoiKind.WINE_KEG, "", null, null, false);
        assertEquals(RegistrationRules.CoordinateState.UNLOADED, RegistrationRules.coordinateState(keg, p -> false,
                p -> { fail("unloaded block must never be queried"); return null; }));
        assertNull(keg.classifier());
    }

    @Test void LoadedWrongKindRemainsUnverifiedAndCorrectKindStillRequiresExplicitConfirmation() {
        CoordinateDestination keg = draft(PoiKind.WINE_KEG, "", null, null, false);
        assertEquals(RegistrationRules.CoordinateState.TYPE_MISMATCH, RegistrationRules.coordinateState(keg, p -> true,
                p -> block(p, "society:preserves_jar", false)));
        assertEquals(RegistrationRules.CoordinateState.TYPE_MISMATCH, RegistrationRules.coordinateState(keg, p -> true,
                p -> block(p.offset(1, 0, 0), "society:wine_keg", false)));
        assertEquals(RegistrationRules.CoordinateState.READY_TO_CONFIRM, RegistrationRules.coordinateState(keg, p -> true,
                p -> block(p, "society:wine_keg", false)));
    }

    @Test void StorageConfirmationCannotBeSkippedAndTomatoGradesRemainUnrestricted() {
        for (PoiKind kind : List.of(PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST, PoiKind.WOOD_CHEST))
            assertThrows(IllegalArgumentException.class, () -> draft(kind, "0", 12, 12, false));
        CoordinateDestination tomato = draft(PoiKind.TOMATO_CHEST, "3", null, null, true);
        assertNull(tomato.classifier()); assertTrue(tomato.contentsConfirmed());
        assertNull(draft(PoiKind.WOOD_CHEST, "", null, null, true).classifier());
    }

    @Test void WineAgeAndFutureReservationsKeepTheExistingClockCheckedCohortRules() {
        assertEquals(12, draft(PoiKind.WINE_CHEST, "0", 12, 12, true).classifier());
        assertEquals(9, draft(PoiKind.WINE_CHEST, "3", 12, 12, true).classifier());
        assertEquals(13, draft(PoiKind.WINE_CHEST, "+1", 12, 12, true).classifier());
        assertThrows(IllegalArgumentException.class, () -> draft(PoiKind.WINE_CHEST, "0", 12, 13, true));
        assertThrows(IllegalArgumentException.class, () -> draft(PoiKind.WINE_CHEST, "0", null, null, true));
        assertThrows(IllegalArgumentException.class, () -> draft(PoiKind.WINE_CHEST, "13", 12, 12, true));
        assertThrows(IllegalArgumentException.class, () -> draft(PoiKind.WINE_CHEST, "+1001", 12, 12, true));
    }

    @Test void CoordinatesNeverRegisterDisposalWaypointsOrGrantFarmOrTreeBounds() {
        for (PoiKind forbidden : List.of(PoiKind.DISPOSAL, PoiKind.WAYPOINT))
            assertThrows(IllegalArgumentException.class, () -> draft(forbidden, "", null, null, false));
        assertFalse(CoordinateDestinationRules.matches(PoiKind.WINE_KEG, block(new Pos(0, 64, 0), "farmersdelight:tomatoes", false)));
        assertTrue(CoordinateDestinationRules.kinds(block(new Pos(0, 64, 0), "minecraft:spruce_log", false)).isEmpty());
    }

    @Test void SharedFacilityMatchingAndExistingRegistrationRemainIdentical() {
        for (String id : List.of("society:wine_keg", "society:preserves_jar", "shippingbin:smart_shipping_bin",
                "minecraft:crafting_table", "minecraft:barrel", "minecraft:red_bed", "minecraft:stone")) {
            BlockData block = block(new Pos(0, 64, 0), id, id.equals("minecraft:barrel"));
            assertEquals(RegistrationRules.kinds(block), CoordinateDestinationRules.kinds(block));
            for (PoiKind kind : PoiKind.values()) assertEquals(RegistrationRules.kinds(block).contains(kind), CoordinateDestinationRules.matches(kind, block));
        }
        assertFalse(CoordinateDestinationRules.matches(null, block(new Pos(0, 64, 0), "minecraft:barrel", true)));
    }

    @Test void NamesAreBoundedAndControlsCannotBePersisted() {
        for (String bad : Arrays.asList(null, "", "  ", "x".repeat(65), "line\nbreak"))
            assertThrows(IllegalArgumentException.class, () -> CoordinateDestinationRules.validate(
                    new CoordinateDestination(bad, new Pos(0, 64, 0), null, null, false)));
        assertDoesNotThrow(() -> CoordinateDestinationRules.validate(new CoordinateDestination("목적지".repeat(20), new Pos(0, 64, 0), null, null, false)));
    }

    @Test void CoordinateListAndEditorFit240PixelHeightWithoutFooterOverlap() {
        assertEquals(3, RegistrationRules.coordinateRows(240));
        for (int height : new int[] {240, 256, 300, 480}) {
            int bottom = 104 + RegistrationRules.coordinateRows(height) * 23;
            assertTrue(bottom <= height - 53);
        }
        assertTrue(168 + 20 < 196); assertTrue(196 + 9 < 240 - 25);
        assertTrue(190 + 20 <= 240 - 25);
    }

    @Test void BothLanguagesCoverEveryCoordinateStateAndModeWithMatchingFormatArguments() throws Exception {
        JsonObject english = language("en_us"), korean = language("ko_kr");
        Set<String> keys = new HashSet<>();
        for (String key : english.keySet()) if (key.startsWith("autovalley.coordinates.") || key.startsWith("autovalley.navigation.")) keys.add(key);
        assertTrue(keys.size() >= 30);
        for (String key : korean.keySet()) if (key.startsWith("autovalley.coordinates.") || key.startsWith("autovalley.navigation.")) assertTrue(keys.contains(key), key);
        for (String key : keys) {
            assertTrue(korean.has(key), key); String en = english.get(key).getAsString(), ko = korean.get(key).getAsString();
            assertFalse(en.isBlank()); assertFalse(ko.isBlank());
            assertEquals(en.split("%s", -1).length, ko.split("%s", -1).length, key);
        }
        for (RegistrationRules.CoordinateState state : RegistrationRules.CoordinateState.values())
            assertTrue(keys.contains("autovalley.coordinates.state." + state.name().toLowerCase(Locale.ROOT)));
        for (NavigationMode mode : NavigationMode.values()) assertTrue(keys.contains("autovalley.navigation.mode." + mode.name().toLowerCase(Locale.ROOT)));
    }

    private static CoordinateDestination draft(PoiKind kind, String classifier, Integer formYear, Integer currentYear, boolean checked) {
        return RegistrationRules.coordinateDraft("destination", "1", "64", "2", kind, classifier, formYear, currentYear, checked);
    }
    private static BlockData block(Pos pos, String id, boolean container) { return new BlockData(pos, id, Map.of("container", Boolean.toString(container))); }
    private static JsonObject language(String name) throws Exception {
        try (InputStream stream = CoordinateRegistrationRulesTest.class.getResourceAsStream("/assets/autovalley/lang/" + name + ".json")) {
            assertNotNull(stream); return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
