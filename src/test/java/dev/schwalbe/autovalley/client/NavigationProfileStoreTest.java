package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class NavigationProfileStoreTest {
    @TempDir Path directory;

    @Test void newProfilesUseTerrainAndRetainWaypointHintsWithoutEnablingLogging() {
        Profile profile = new Profile();
        assertEquals(6, profile.schemaVersion); assertEquals(NavigationMode.TERRAIN, profile.navigationMode);
        assertTrue(profile.useWaypointHints); assertTrue(profile.coordinateDestinations.isEmpty());
        assertFalse(profile.enabled(Feature.LOGGING));
    }

    @Test void allThreeLegacySchemasUpgradeInMemoryWithoutRewritingOrRemovingWaypoints() throws Exception {
        ProfileStore store = new ProfileStore(directory);
        for (int schema : new int[] {1, 2, 3}) {
            String key = ProfileStore.key("legacy navigation " + schema);
            Path file = directory.resolve(key + ".json");
            String old = "{\"schemaVersion\":" + schema + ",\"pois\":[{\"pos\":{\"x\":1,\"y\":64,\"z\":2},\"kind\":\"WAYPOINT\",\"label\":\"Keep\"}]}";
            Files.writeString(file, old); Profile loaded = store.load(key);
            assertEquals(6, loaded.schemaVersion); assertEquals(NavigationMode.TERRAIN, loaded.navigationMode);
            assertTrue(loaded.useWaypointHints); assertTrue(loaded.coordinateDestinations.isEmpty());
            assertEquals(1, loaded.pois.size()); assertEquals(PoiKind.WAYPOINT, loaded.pois.get(0).kind());
            assertEquals(old, Files.readString(file));
            store.save(key, loaded);
            assertEquals(old, Files.readString(directory.resolve(key + ".json.bak")));
            assertTrue(Files.readString(file).contains("\"schemaVersion\": 6"));
        }
    }

    @Test void ModeAndHintsRoundTripWithoutTouchingWorkBoundsOrSchedules() throws Exception {
        Profile profile = new Profile();
        profile.pois.add(new Poi(new Pos(2, 64, 3), PoiKind.WAYPOINT, "walkway", null));
        profile.pois.add(new Poi(new Pos(3, 64, 3), PoiKind.WINE_CHEST, "fixed cohort", 7));
        profile.farms.add(new Farm("tomatoes", new Pos(5, 64, 5), new Pos(8, 65, 8)));
        profile.nextEligibleDay.put("farm:tomatoes", 27L);
        profile.navigationMode = NavigationMode.WAYPOINTS; profile.useWaypointHints = false;
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("navigation options");
        store.save(key, profile); Profile loaded = store.load(key);
        assertEquals(NavigationMode.WAYPOINTS, loaded.navigationMode); assertFalse(loaded.useWaypointHints);
        assertEquals(profile.pois, loaded.pois); assertEquals(profile.farms, loaded.farms);
        assertEquals(profile.nextEligibleDay, loaded.nextEligibleDay);
        loaded.navigationMode = NavigationMode.TERRAIN; store.save(key, loaded);
        assertEquals(profile.pois, store.load(key).pois);
    }

    @Test void UnverifiedFacilitiesAndFeetPersistOutsideTheAuthorizedPoiList() throws Exception {
        Profile profile = new Profile();
        profile.coordinateDestinations.add(new CoordinateDestination("move", new Pos(1, 65, 2), null, null, false));
        profile.coordinateDestinations.add(new CoordinateDestination("keg", new Pos(2, 64, 2), PoiKind.WINE_KEG, null, false));
        profile.coordinateDestinations.add(new CoordinateDestination("future wine", new Pos(3, 64, 2), PoiKind.WINE_CHEST, 22, true));
        profile.coordinateDestinations.add(new CoordinateDestination("all tomatoes", new Pos(4, 64, 2), PoiKind.TOMATO_CHEST, null, true));
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("coordinate drafts");
        store.save(key, profile); Profile loaded = store.load(key);
        assertEquals(profile.coordinateDestinations, loaded.coordinateDestinations);
        assertTrue(loaded.pois.isEmpty()); assertTrue(loaded.farms.isEmpty()); assertTrue(loaded.loggingPlots.isEmpty());
        assertTrue(loaded.pois(PoiKind.WINE_KEG).isEmpty());
    }

    @Test void InvalidNavigationFieldsNeverOverwriteAnExistingProfile() throws Exception {
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("invalid navigation");
        store.save(key, new Profile()); Path file = directory.resolve(key + ".json"); String original = Files.readString(file);
        List<Consumer<Profile>> changes = List.of(p -> p.navigationMode = null, p -> p.coordinateDestinations = null,
                p -> p.coordinateDestinations.add(null),
                p -> p.coordinateDestinations.add(new CoordinateDestination("bad", new Pos(0, 64, 0), PoiKind.DISPOSAL, null, false)),
                p -> p.coordinateDestinations.add(new CoordinateDestination("wine", new Pos(0, 64, 0), PoiKind.WINE_CHEST, null, true)),
                p -> p.coordinateDestinations.add(new CoordinateDestination("unchecked", new Pos(0, 64, 0), PoiKind.TOMATO_CHEST, null, false)));
        for (Consumer<Profile> change : changes) {
            Profile invalid = new Profile(); change.accept(invalid);
            assertThrows(IllegalArgumentException.class, () -> store.save(key, invalid));
            assertEquals(original, Files.readString(file));
        }
    }

    @Test void DuplicateDraftNamesAndPositionsAreRejectedIndependentlyOfExistingPois() {
        CoordinateDestination first = new CoordinateDestination("first", new Pos(0, 64, 0), null, null, false);
        for (CoordinateDestination second : List.of(
                new CoordinateDestination("first", new Pos(1, 64, 0), null, null, false),
                new CoordinateDestination("other", first.pos(), PoiKind.WINE_KEG, null, false))) {
            Profile profile = new Profile(); profile.coordinateDestinations.addAll(List.of(first, second));
            assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(profile));
        }
        Profile profile = new Profile(); profile.coordinateDestinations.add(first);
        profile.pois.add(new Poi(first.pos(), PoiKind.WAYPOINT, "old waypoint", null));
        assertDoesNotThrow(() -> ProfileStore.validate(profile), "unverified coordinates must not rewrite or remove existing locations");
    }

    @Test void DraftCountIsBoundedWithoutLimitingRegisteredFarmCount() {
        Profile profile = new Profile();
        for (int index = 0; index <= 4096; index++) profile.coordinateDestinations.add(
                new CoordinateDestination("destination " + index, new Pos(index, 64, 0), null, null, false));
        assertThrows(IllegalArgumentException.class, () -> ProfileStore.validate(profile));
        profile.coordinateDestinations.remove(4096);
        assertDoesNotThrow(() -> ProfileStore.validate(profile));
    }

    @Test void UnknownModeNullDraftCollectionAndFutureSchemaAreRejectedReadOnly() throws Exception {
        String key = ProfileStore.key("broken coordinate format"); Path file = directory.resolve(key + ".json");
        for (String json : List.of("{\"schemaVersion\":9}", "{\"schemaVersion\":4,\"navigationMode\":\"UNKNOWN\"}",
                "{\"schemaVersion\":3,\"coordinateDestinations\":null}")) {
            Files.writeString(file, json);
            assertThrows(IOException.class, () -> new ProfileStore(directory).load(key));
            assertEquals(json, Files.readString(file));
        }
    }
}
