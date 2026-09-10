package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class LoggingProfileStoreTest {
    @TempDir Path directory;

    @Test void newProfileKeepsLoggingOffAndAllExistingDefaultsUnchanged() {
        Profile p = new Profile();
        assertEquals(6, p.schemaVersion);
        assertFalse(p.enabled(Feature.LOGGING));
        Set<Feature> optIn = Set.of(Feature.LOGGING, Feature.COMMODITY_STORAGE, Feature.SEED_MAKER,
                Feature.CRYSTAL_COPY, Feature.STARFRUIT);
        for (Feature feature : Feature.values()) assertEquals(!optIn.contains(feature), p.enabled(feature), feature.name());
        assertTrue(p.loggingPlots.isEmpty()); assertEquals(-1, p.loggingAxeHotbarSlot);
        assertEquals(0, p.loggingSaplingReserve); assertEquals(LoggingMode.ALL_GROWN, p.loggingMode);
        assertEquals(1200, p.loggingCheckTicks); assertEquals(1, p.loggingCycleDays);
        assertFalse(p.loggingRunActive); assertTrue(p.loggingRemainingPlots.isEmpty());
        assertFalse(p.loggingClearObstructingLeaves);
        assertTrue(p.loggingReplantingPlots.isEmpty()); assertNull(p.loggingHotbarLease);
    }

    @Test void oldProfileWithReplacedEnabledMapGetsLoggingOffWithoutDiskRewrite() throws Exception {
        String key = ProfileStore.key("legacy logging missing");
        String json = "{\"schemaVersion\":2,\"enabled\":{\"WINE\":false}}";
        Path file = directory.resolve(key + ".json"); Files.writeString(file, json);
        Profile loaded = new ProfileStore(directory).load(key);
        assertEquals(6, loaded.schemaVersion);
        assertFalse(loaded.enabled(Feature.LOGGING)); assertFalse(loaded.enabled(Feature.WINE));
        assertTrue(loaded.enabled(Feature.HARVEST)); assertEquals(-1, loaded.loggingAxeHotbarSlot);
        assertTrue(loaded.loggingPlots.isEmpty()); assertTrue(loaded.loggingRemainingPlots.isEmpty());
        assertFalse(loaded.loggingRunActive); assertEquals(json, Files.readString(file));
        assertFalse(loaded.loggingClearObstructingLeaves);
    }

    @Test void explicitLoggingToggleAndSettingsRoundTripWithoutChangingOtherModules() throws Exception {
        Profile p = new Profile(); p.enabled.put(Feature.LOGGING, true); p.enabled.put(Feature.HARVEST, false);
        p.loggingAxeHotbarSlot = 2; p.loggingMode = LoggingMode.DAILY_GROWN;
        p.loggingCheckTicks = 25; p.loggingCycleDays = 3; p.loggingSaplingReserve = 20;
        p.loggingClearObstructingLeaves = true;
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("logging settings");
        store.save(key, p); Profile loaded = store.load(key);
        assertTrue(loaded.enabled(Feature.LOGGING)); assertFalse(loaded.enabled(Feature.HARVEST));
        assertTrue(loaded.loggingClearObstructingLeaves);
        assertEquals(2, loaded.loggingAxeHotbarSlot); assertEquals(LoggingMode.DAILY_GROWN, loaded.loggingMode);
        assertEquals(25, loaded.loggingCheckTicks); assertEquals(3, loaded.loggingCycleDays); assertEquals(20, loaded.loggingSaplingReserve);
        loaded.enabled.put(Feature.LOGGING, false); loaded.loggingMode = LoggingMode.ONCE_ONLY;
        loaded.loggingClearObstructingLeaves = false;
        store.save(key, loaded); assertFalse(store.load(key).enabled(Feature.LOGGING));
        assertEquals(LoggingMode.ONCE_ONLY, store.load(key).loggingMode);
        assertFalse(store.load(key).loggingClearObstructingLeaves);
    }

    @Test void manyLoggingPlotsRoundTripAlongsideTomatoFarmsAndWineDestinations() throws Exception {
        Profile p = new Profile();
        p.farms.add(new Farm("tomatoes", new Pos(-20, 64, -20), new Pos(-15, 65, -15)));
        p.pois.add(new Poi(new Pos(-10, 64, -10), PoiKind.WINE_CHEST, "unchanged cohort", 7));
        p.pois.add(new Poi(new Pos(-9, 64, -10), PoiKind.TOMATO_CHEST, "all grades", null));
        p.pois.add(new Poi(new Pos(-8, 64, -10), PoiKind.WOOD_CHEST, "wood", null));
        p.pois.add(new Poi(new Pos(-7, 64, -10), PoiKind.LOGGING_CRAFTING_TABLE, "crafting", null));
        for (int index = 0; index < 25; index++) p.loggingPlots.add(new LoggingPlot("plot " + index, new Pos(index * 10, 64, 10)));
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("many logging plots");
        store.save(key, p); Profile loaded = store.load(key);
        assertEquals(p.farms, loaded.farms); assertEquals(p.pois, loaded.pois); assertEquals(p.loggingPlots, loaded.loggingPlots);
        LoggingPlot last = loaded.loggingPlots.get(24);
        loaded.loggingPlots.set(24, new LoggingPlot("renamed", last.corner())); loaded.loggingPlots.remove(12);
        store.save(key, loaded); Profile saved = store.load(key);
        assertEquals(24, saved.loggingPlots.size()); assertEquals("renamed", saved.loggingPlots.get(23).name());
        assertEquals(p.farms, saved.farms); assertEquals(p.pois, saved.pois);
    }

    @Test void activeBatchAndRemainingCornersSurviveRoundTripAndUnrelatedToggle() throws Exception {
        Profile p = new Profile();
        p.loggingPlots.add(new LoggingPlot("finished", new Pos(0, 64, 0)));
        p.loggingPlots.add(new LoggingPlot("remaining", new Pos(10, 64, 0)));
        p.loggingRunActive = true; p.loggingRemainingPlots.add(p.loggingPlots.get(1).corner());
        p.loggingReplantingPlots.add(p.loggingPlots.get(1).corner());
        p.nextEligibleDay.put(LoggingRules.DUE_KEY, 20L);
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("unfinished logging");
        store.save(key, p); Profile loaded = store.load(key);
        assertTrue(loaded.loggingRunActive); assertEquals(p.loggingRemainingPlots, loaded.loggingRemainingPlots);
        assertEquals(p.loggingReplantingPlots, loaded.loggingReplantingPlots);
        loaded.enabled.put(Feature.LOGGING, false); store.save(key, loaded);
        Profile paused = store.load(key);
        assertTrue(paused.loggingRunActive); assertEquals(p.loggingRemainingPlots, paused.loggingRemainingPlots);
        assertEquals(p.loggingReplantingPlots, paused.loggingReplantingPlots);
        assertEquals(p.nextEligibleDay, paused.nextEligibleDay);
    }

    @Test void invalidLoggingSettingsAreRejectedByProfileValidation() {
        List<Consumer<Profile>> invalid = List.of(
                p -> p.loggingAxeHotbarSlot = -2, p -> p.loggingAxeHotbarSlot = 9,
                p -> p.loggingAxeHotbarSlot = p.hoeHotbarSlot,
                p -> p.loggingCheckTicks = 19, p -> p.loggingCheckTicks = 24001,
                p -> p.loggingCycleDays = 0, p -> p.loggingCycleDays = 29,
                p -> p.loggingSaplingReserve = -1, p -> p.loggingSaplingReserve = 2305,
                p -> p.loggingMode = null, p -> p.loggingPlots = null);
        for (Consumer<Profile> change : invalid) {
            Profile p = new Profile(); change.accept(p);
            assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(p));
        }
    }

    @Test void overlappingPlotsCannotOverwritePreviouslyValidDiskProfile() throws Exception {
        Profile p = new Profile(); p.loggingPlots.add(new LoggingPlot("original", new Pos(0, 64, 0)));
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("overlap logging");
        store.save(key, p); String before = Files.readString(directory.resolve(key + ".json"));
        p.loggingPlots.add(new LoggingPlot("overlap", new Pos(1, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> store.save(key, p));
        assertEquals(before, Files.readString(directory.resolve(key + ".json")));
    }

    @Test void loggingCannotOverlapTomatoPlantingCellsOrUseDuplicateNames() {
        Profile p = new Profile(); p.farms.add(new Farm("tomatoes", new Pos(0, 64, 0), new Pos(5, 65, 5)));
        p.loggingPlots.add(new LoggingPlot("tree", new Pos(2, 64, 2)));
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(p));
        p.farms.clear(); p.loggingPlots.add(new LoggingPlot("tree", new Pos(20, 64, 0)));
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(p));
    }

    @Test void woodAndCraftingClassifiersAreForbiddenButWineStillRequiresCohort() {
        for (PoiKind kind : List.of(PoiKind.WOOD_CHEST, PoiKind.LOGGING_CRAFTING_TABLE)) {
            Profile p = new Profile(); p.pois.add(new Poi(new Pos(0, 64, 0), kind, "destination", 1));
            assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(p));
        }
        Profile p = new Profile(); p.pois.add(new Poi(new Pos(0, 64, 0), PoiKind.WINE_CHEST, "wine", null));
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(p));
    }

    @Test void malformedLoggingFieldsPreserveTheOriginalFileOnLoadFailure() throws Exception {
        String key = ProfileStore.key("bad logging"); Path file = directory.resolve(key + ".json");
        String json = "{\"schemaVersion\":2,\"loggingPlots\":null}"; Files.writeString(file, json);
        assertThrows(IOException.class, () -> new ProfileStore(directory).load(key));
        assertEquals(json, Files.readString(file));
    }

    @Test void legacySchemaTwoUpgradesOnlyOnExplicitSaveAndGuardsLoggingProgressFromOldClients() throws Exception {
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("schema two logging upgrade");
        Path file = directory.resolve(key + ".json"); String old = "{\"schemaVersion\":2}"; Files.writeString(file, old);
        Profile p = store.load(key); assertEquals(6, p.schemaVersion); assertEquals(old, Files.readString(file));
        p.loggingPlots.add(new LoggingPlot("remaining", new Pos(0, 64, 0)));
        p.loggingRunActive = true; p.loggingRemainingPlots.add(p.loggingPlots.get(0).corner());
        p.loggingReplantingPlots.add(p.loggingPlots.get(0).corner());
        store.save(key, p);
        assertTrue(Files.readString(file).contains("\"schemaVersion\": 6"));
        assertEquals(old, Files.readString(directory.resolve(key + ".json.bak")));
        Profile loaded = store.load(key); assertEquals(6, loaded.schemaVersion); assertTrue(loaded.loggingRunActive);
        assertEquals(p.loggingRemainingPlots, loaded.loggingRemainingPlots);
        assertEquals(p.loggingReplantingPlots, loaded.loggingReplantingPlots);
    }
}
