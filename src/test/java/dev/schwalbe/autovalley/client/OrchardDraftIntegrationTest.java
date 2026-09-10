package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Persistence uses disposable test directories; no live profile, recording or game is opened. */
class OrchardDraftIntegrationTest {
    private static final Gson JSON = new Gson();
    private static final Pos FRUIT = new Pos(500, 72, -500);
    private static final Pos ORANGE_STORE = new Pos(900, 64, -900);
    @TempDir Path directory;

    private static OrchardDraft draft(String id, Pos fruit) {
        return new OrchardDraft(id, "Orange orchard", OrchardDraft.TOOL_ITEM_ID, OrchardDraft.FRUIT_BLOCK_ID,
                OrchardDraft.OUTPUT_ITEM_ID, List.of(fruit), "recording-2026-09-10.jsonl");
    }

    private static String additions(List<OrchardDraft> drafts, Map<String, CommodityStore> stores, Set<Feature> enable) {
        return JSON.toJson(new WorkRegistrationImport.Data(Map.of(), List.of(), stores, Map.of(), Map.of(),
                List.of(), Map.of(), drafts, enable));
    }

    private static String additions(OrchardDraft draft) {
        return additions(List.of(draft), Map.of(), Set.of());
    }

    private static CommodityStore orangeStore() {
        return new CommodityStore("oranges", "Orange storage", Set.of(OrchardDraft.OUTPUT_ITEM_ID), List.of(ORANGE_STORE));
    }

    private static Profile existingProfile() {
        Profile profile = new Profile();
        profile.pois.add(new Poi(new Pos(1, 64, 0), PoiKind.WINE_KEG, "Existing keg", null));
        profile.farms.add(new Farm("Existing tomatoes", new Pos(10, 64, 0), new Pos(12, 64, 2)));
        profile.coordinateDestinations.add(new CoordinateDestination("Existing destination", new Pos(30, 64, 0), null, null, false));
        profile.commodityStores.put("tomatoes", new CommodityStore("tomatoes", "Existing tomatoes",
                Set.of(ItemData.TOMATO), List.of(new Pos(20, 64, 0))));
        profile.cropStores.put(CropRules.TOMATO, "tomatoes");
        profile.commodityStores.put("starfruit", new CommodityStore("starfruit", "Existing starfruit",
                Set.of(FruitRules.ITEM), List.of(new Pos(40, 64, 0))));
        profile.fruitPatches.add(new FruitPatch("existing_starfruit", "starfruit", List.of(new Pos(45, 70, 0))));
        profile.wineBatchSchedule = new WineBatchSchedule(80, false, List.of(), 74L);
        profile.nextEligibleDay.put("harvest:Existing tomatoes", 79L);
        profile.nextEligibleDay.put("orchard:existing_starfruit", 81L);
        profile.enabled.put(Feature.HARVEST, false);
        profile.enabled.put(Feature.WINE, true);
        profile.enabled.put(Feature.STARFRUIT, false);
        profile.navigationMode = NavigationMode.WAYPOINTS;
        profile.useWaypointHints = false;
        ProfileStore.validate(profile);
        return profile;
    }

    private static void assertExistingStateUnchanged(Profile before, Profile after) {
        assertEquals(before.pois, after.pois);
        assertEquals(before.farms, after.farms);
        assertEquals(before.crops, after.crops);
        assertEquals(before.cropStores, after.cropStores);
        assertEquals(before.commodityStores, after.commodityStores);
        assertEquals(before.artisanJobs, after.artisanJobs);
        assertEquals(before.fruitPatches, after.fruitPatches);
        assertEquals(before.loggingPlots, after.loggingPlots);
        assertEquals(before.coordinateDestinations, after.coordinateDestinations);
        assertEquals(before.navigationMode, after.navigationMode);
        assertEquals(before.useWaypointHints, after.useWaypointHints);
        assertEquals(before.enabled, after.enabled);
        assertEquals(before.nextEligibleDay, after.nextEligibleDay);
        assertEquals(before.wineBatchSchedule, after.wineBatchSchedule);
        assertEquals(before.wineProductionLines, after.wineProductionLines);
        assertEquals(before.wineProductionSchedules, after.wineProductionSchedules);
        assertEquals(before.pendingMachineOutputs, after.pendingMachineOutputs);
    }

    @Test void importAddsAnInertDraftAndIsIdempotentWithoutChangingExistingRegistrationsOrSchedules() {
        Profile current = existingProfile();
        String before = JSON.toJson(current);
        OrchardDraft draft = draft("orange_orchard", FRUIT);
        Profile added = WorkRegistrationImport.merge(current, additions(draft));
        Profile repeated = WorkRegistrationImport.merge(added, additions(draft));
        assertEquals(before, JSON.toJson(current));
        assertEquals(List.of(draft), added.orchardDrafts);
        assertEquals(JSON.toJson(added), JSON.toJson(repeated));
        assertExistingStateUnchanged(current, added);
        assertEquals(AdditionalWorkRules.sites(current), AdditionalWorkRules.sites(added));
        assertFalse(added.inWorkArea(FRUIT));
        assertNull(FruitRules.patch(added, FRUIT));
        assertNull(CropRules.registeredCrop(added, FRUIT));
        assertFalse(CropRules.scanBlockIds(added).contains(OrchardDraft.FRUIT_BLOCK_ID));
    }

    @Test void serializedAndDiskProfilesRetainDraftMetadataWithoutRewritingOnLoad() throws Exception {
        Profile original = existingProfile();
        Profile profile = WorkRegistrationImport.merge(original, additions(draft("orange_orchard", FRUIT)));
        Profile serialized = JSON.fromJson(JSON.toJson(profile), Profile.class);
        ProfileStore.validate(serialized);
        assertEquals(profile.orchardDrafts, serialized.orchardDrafts);
        assertExistingStateUnchanged(original, serialized);
        ProfileStore store = new ProfileStore(directory);
        String key = ProfileStore.key("orchard draft round trip");
        store.save(key, profile);
        Path file = directory.resolve(key + ".json");
        String bytes = Files.readString(file);
        Profile loaded = store.load(key);
        assertEquals(bytes, Files.readString(file));
        assertEquals(profile.orchardDrafts, loaded.orchardDrafts);
        assertExistingStateUnchanged(original, loaded);
        assertFalse(loaded.inWorkArea(FRUIT));
        assertNull(FruitRules.patch(loaded, FRUIT));
        assertNull(CropRules.registeredCrop(loaded, FRUIT));
        assertThrows(UnsupportedOperationException.class, () -> loaded.orchardDrafts.get(0).observedFruits().clear());
    }

    @Test void missingAndExplicitNullLegacyDraftsLoadAsEmptyWithoutRewritingDisk() throws Exception {
        ProfileStore store = new ProfileStore(directory);
        for (int schema : List.of(1, 2, 3, 4, 5, 6, 7)) {
            for (String field : List.of("", ",\"orchardDrafts\":null")) {
                String key = ProfileStore.key("legacy orchard " + schema + field);
                Path file = directory.resolve(key + ".json");
                String bytes = "{\"schemaVersion\":" + schema + field + "}";
                Files.writeString(file, bytes);
                Profile loaded = store.load(key);
                assertNotNull(loaded.orchardDrafts);
                assertTrue(loaded.orchardDrafts.isEmpty());
                assertTrue(loaded.fruitPatches.isEmpty());
                assertEquals(new Profile().enabled, loaded.enabled);
                assertEquals(bytes, Files.readString(file));
            }
        }
    }

    @Test void bothLegacyImportConstructorsStillRepresentAnEmptyDraftCollection() {
        WorkRegistrationImport.Data seven = new WorkRegistrationImport.Data(Map.of(), List.of(), Map.of(),
                Map.of(), Map.of(), List.of(), Set.of());
        WorkRegistrationImport.Data eight = new WorkRegistrationImport.Data(Map.of(), List.of(), Map.of(),
                Map.of(), Map.of(), List.of(), Map.of(), Set.of());
        for (WorkRegistrationImport.Data data : List.of(seven, eight)) {
            assertNotNull(data.orchardDrafts());
            assertTrue(data.orchardDrafts().isEmpty());
            assertTrue(WorkRegistrationImport.merge(new Profile(), JSON.toJson(data)).orchardDrafts.isEmpty());
        }
    }

    @Test void conflictingDraftIdsAndOverlappingObservationsRollBackOtherCandidateAdditions() {
        Profile current = WorkRegistrationImport.merge(existingProfile(), additions(draft("orange_orchard", FRUIT)));
        String before = JSON.toJson(current);
        for (OrchardDraft conflict : List.of(draft("orange_orchard", FRUIT.offset(1, 0, 0)),
                draft("another_orchard", FRUIT),
                new OrchardDraft("orange_orchard", "Changed name", OrchardDraft.TOOL_ITEM_ID, OrchardDraft.FRUIT_BLOCK_ID,
                        OrchardDraft.OUTPUT_ITEM_ID, List.of(FRUIT), "different-recording.jsonl"))) {
            String json = additions(List.of(draft("new_orchard", FRUIT.offset(10, 0, 0)), conflict),
                    Map.of("oranges", orangeStore()), Set.of());
            assertThrows(IllegalArgumentException.class, () -> WorkRegistrationImport.merge(current, json));
            assertEquals(before, JSON.toJson(current));
            assertFalse(current.commodityStores.containsKey("oranges"));
            assertEquals(1, current.orchardDrafts.size());
        }
    }

    @Test void aDraftImportCannotOverwriteAnExistingStorageRegistration() {
        Profile current = existingProfile();
        String before = JSON.toJson(current);
        CommodityStore conflict = new CommodityStore("tomatoes", "Changed storage",
                Set.of(OrchardDraft.OUTPUT_ITEM_ID), List.of(ORANGE_STORE));
        String json = additions(List.of(draft("orange_orchard", FRUIT)), Map.of("tomatoes", conflict), Set.of());
        assertThrows(IllegalArgumentException.class, () -> WorkRegistrationImport.merge(current, json));
        assertEquals(before, JSON.toJson(current));
        assertTrue(current.orchardDrafts.isEmpty());
    }

    @Test void everyNonemptyActivationRequestIsRejectedWhenDraftsArePresent() {
        Profile current = existingProfile();
        String before = JSON.toJson(current);
        for (Feature feature : Feature.values()) {
            String json = additions(List.of(draft("orange_orchard", FRUIT)), Map.of("oranges", orangeStore()), Set.of(feature));
            assertThrows(IllegalArgumentException.class, () -> WorkRegistrationImport.merge(current, json), feature.toString());
            assertEquals(before, JSON.toJson(current));
        }
    }

    @Test void missingOrEmptyDraftsDoNotDisableTheExistingExplicitActivationImport() {
        for (String json : List.of("{\"enable\":[\"STARFRUIT\"]}",
                "{\"orchardDrafts\":[],\"enable\":[\"STARFRUIT\"]}",
                "{\"orchardDrafts\":null,\"enable\":[\"STARFRUIT\"]}")) {
            Profile imported = WorkRegistrationImport.merge(new Profile(), json);
            assertTrue(imported.enabled(Feature.STARFRUIT));
            assertTrue(imported.orchardDrafts.isEmpty());
        }
    }

    @Test void compatibleOrangeStorageDoesNotPromoteTheDraftOrEnablePicking() {
        Profile original = new Profile();
        Profile imported = WorkRegistrationImport.merge(original,
                additions(List.of(draft("orange_orchard", FRUIT)), Map.of("oranges", orangeStore()), Set.of()));
        assertEquals(original.enabled, imported.enabled);
        assertEquals(original.nextEligibleDay, imported.nextEligibleDay);
        assertEquals(List.of(ORANGE_STORE), AdditionalWorkRules.sites(imported));
        assertFalse(imported.inWorkArea(FRUIT));
        assertTrue(imported.fruitPatches.isEmpty());
        assertTrue(imported.farms.isEmpty());
        assertTrue(imported.coordinateDestinations.isEmpty());
        assertNull(FruitRules.patch(imported, FRUIT));
        assertNull(CropRules.registeredCrop(imported, FRUIT));
        SessionState session = new SessionState();
        Context context = new Context(null, null, null, imported, session);
        assertFalse(CommodityStorageRules.openAllowed(context, ORANGE_STORE));
        session.oneShotFeature = Feature.STARFRUIT;
        assertFalse(CommodityStorageRules.openAllowed(context, ORANGE_STORE));
        assertNotNull(FruitRules.rejection(context, FRUIT,
                new BlockData(FRUIT, OrchardDraft.FRUIT_BLOCK_ID, Map.of("age", "7")), ItemData.EMPTY));
    }

    @Test void invalidDraftPayloadsFailWithoutChangingTheCurrentProfile() {
        Profile current = existingProfile();
        String before = JSON.toJson(current);
        List<String> invalid = new ArrayList<>(List.of("{\"orchardDrafts\":[null]}",
                "{\"orchardDrafts\":[{}]}", "{\"orchardDrafts\":{}}"));
        JsonObject base = JsonParser.parseString(additions(draft("orange_orchard", FRUIT))).getAsJsonObject();
        for (String field : List.of("id", "name", "toolItemId", "fruitBlockId", "outputItemId", "sourceRecording")) {
            JsonObject changed = base.deepCopy();
            changed.getAsJsonArray("orchardDrafts").get(0).getAsJsonObject().addProperty(field, "");
            invalid.add(changed.toString());
            for (String primitive : List.of("7", "true")) {
                JsonObject nonString = base.deepCopy();
                nonString.getAsJsonArray("orchardDrafts").get(0).getAsJsonObject().add(field, JsonParser.parseString(primitive));
                invalid.add(nonString.toString());
            }
        }
        JsonObject invalidPosition = base.deepCopy();
        invalidPosition.getAsJsonArray("orchardDrafts").get(0).getAsJsonObject()
                .getAsJsonArray("observedFruits").get(0).getAsJsonObject().addProperty("y", 2048);
        invalid.add(invalidPosition.toString());
        for (String json : invalid) {
            assertThrows(RuntimeException.class, () -> WorkRegistrationImport.merge(current, json));
            assertEquals(before, JSON.toJson(current));
        }
    }

    @Test void malformedObservationCoordinatesCannotBeCoercedOrFilledWithInventedDefaults() {
        Profile current = existingProfile();
        String before = JSON.toJson(current);
        JsonObject base = JsonParser.parseString(additions(draft("orange_orchard", FRUIT))).getAsJsonObject();
        for (String coordinate : List.of(
                "{\"y\":72,\"z\":-500}",
                "{\"x\":500,\"z\":-500}",
                "{\"x\":500,\"y\":72}",
                "{\"x\":\"500\",\"y\":72,\"z\":-500}",
                "{\"x\":500,\"y\":\"72\",\"z\":-500}",
                "{\"x\":500,\"y\":72,\"z\":\"-500\"}",
                "{\"x\":500.5,\"y\":72,\"z\":-500}",
                "{\"x\":500,\"y\":72.5,\"z\":-500}",
                "{\"x\":500,\"y\":72,\"z\":-500.5}",
                "{\"x\":null,\"y\":72,\"z\":-500}",
                "{\"x\":500,\"y\":null,\"z\":-500}",
                "{\"x\":500,\"y\":72,\"z\":null}",
                "{\"x\":500,\"y\":72,\"z\":-500,\"radius\":3}",
                "null")) {
            JsonObject malformed = base.deepCopy();
            malformed.getAsJsonArray("orchardDrafts").get(0).getAsJsonObject()
                    .getAsJsonArray("observedFruits").set(0, JsonParser.parseString(coordinate));
            assertThrows(IllegalArgumentException.class,
                    () -> WorkRegistrationImport.merge(current, malformed.toString()), coordinate);
            assertEquals(before, JSON.toJson(current));
        }
    }

    @Test void unexpectedActivationAndScheduleFieldsCannotBeSilentlyAccepted() {
        Profile current = existingProfile();
        String before = JSON.toJson(current);
        JsonObject base = JsonParser.parseString(additions(draft("orange_orchard", FRUIT))).getAsJsonObject();
        for (String field : List.of("enabled", "active", "storeId", "outputStoreId", "nextEligibleDay", "cycleDays")) {
            JsonObject nested = base.deepCopy();
            nested.getAsJsonArray("orchardDrafts").get(0).getAsJsonObject().addProperty(field, "unexpected");
            assertThrows(IllegalArgumentException.class, () -> WorkRegistrationImport.merge(current, nested.toString()), field);
            assertEquals(before, JSON.toJson(current));
        }
        for (String field : List.of("enabled", "nextEligibleDay", "wineBatchSchedule", "wineProductionSchedules")) {
            JsonObject topLevel = base.deepCopy();
            topLevel.add(field, new JsonObject());
            assertThrows(IllegalArgumentException.class, () -> WorkRegistrationImport.merge(current, topLevel.toString()), field);
            assertEquals(before, JSON.toJson(current));
        }
    }

    @Test void invalidDraftsCannotOverwriteAnExistingDiskProfileAndFailedLoadPreservesItsInput() throws Exception {
        ProfileStore store = new ProfileStore(directory);
        String key = ProfileStore.key("invalid orchard save");
        Profile valid = WorkRegistrationImport.merge(existingProfile(), additions(draft("orange_orchard", FRUIT)));
        store.save(key, valid);
        Path file = directory.resolve(key + ".json");
        String bytes = Files.readString(file);
        Profile invalid = JSON.fromJson(JSON.toJson(valid), Profile.class);
        invalid.orchardDrafts.add(draft("duplicate_position", FRUIT));
        assertThrows(IllegalArgumentException.class, () -> store.save(key, invalid));
        assertEquals(bytes, Files.readString(file));

        String invalidKey = ProfileStore.key("invalid orchard load");
        Path invalidFile = directory.resolve(invalidKey + ".json");
        String invalidBytes = JSON.toJson(invalid);
        Files.writeString(invalidFile, invalidBytes);
        assertThrows(IOException.class, () -> store.load(invalidKey));
        assertEquals(invalidBytes, Files.readString(invalidFile));
        assertEquals(bytes, Files.readString(file));
    }
}
