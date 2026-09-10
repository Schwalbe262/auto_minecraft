package dev.schwalbe.autovalley.core;

import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrchardDraftTest {
    private static final Pos FRUIT = new Pos(120, 70, -40);
    private static final String SOURCE = "recording-2026-09-10.jsonl";

    private static OrchardDraft draft(String id, String name, String tool, String block, String output,
                                      List<Pos> fruits, String source) {
        return new OrchardDraft(id, name, tool, block, output, fruits, source);
    }

    private static OrchardDraft draft(String id, List<Pos> fruits) {
        return draft(id, "Orange orchard", OrchardDraft.TOOL_ITEM_ID, OrchardDraft.FRUIT_BLOCK_ID,
                OrchardDraft.OUTPUT_ITEM_ID, fruits, SOURCE);
    }

    @Test void observedOrangeIdentityIsExactAndIndependentOfExistingStarfruitSupport() {
        assertEquals("society:cornucopia", OrchardDraft.TOOL_ITEM_ID);
        assertEquals("pamhc2trees:pamorange", OrchardDraft.FRUIT_BLOCK_ID);
        assertEquals("atmospheric:orange", OrchardDraft.OUTPUT_ITEM_ID);
        assertEquals(128, OrchardDraft.MAX_DRAFTS);
        assertEquals(4096, OrchardDraft.MAX_OBSERVED_FRUITS);
        assertTrue(draft("orange_orchard", List.of(FRUIT)).valid());
    }

    @Test void metadataAndCoordinatesAcceptTheirDocumentedBoundaries() {
        int limit = CoordinateDestinationRules.MAX_COORDINATE;
        OrchardDraft value = draft("a".repeat(64), "n".repeat(64), OrchardDraft.TOOL_ITEM_ID,
                OrchardDraft.FRUIT_BLOCK_ID, OrchardDraft.OUTPUT_ITEM_ID,
                List.of(new Pos(limit, 2047, -limit), new Pos(-limit, -2048, limit)), "r".repeat(255));
        assertTrue(value.valid());
        assertTrue(draft("_orange.2026-09", List.of(FRUIT)).valid());
    }

    @Test void malformedOrOversizedIdsCannotBecomeRegistrationKeys() {
        for (String id : Arrays.asList(null, "", " ", "Orange", "-orange", ".orange", "orange/tree",
                "orange orchard", "orange\n", "a".repeat(65))) {
            assertFalse(draft(id, List.of(FRUIT)).valid(), "Invalid draft id: " + id);
        }
    }

    @Test void namesAndRecordingReferencesRejectMissingTextControlCharactersAndExcessLength() {
        for (String name : Arrays.asList(null, "", " \t", "n".repeat(65), "orange\norchard", "orange\u007forchard")) {
            assertFalse(draft("orange", name, OrchardDraft.TOOL_ITEM_ID, OrchardDraft.FRUIT_BLOCK_ID,
                    OrchardDraft.OUTPUT_ITEM_ID, List.of(FRUIT), SOURCE).valid());
        }
        for (String source : Arrays.asList(null, "", " ", "r".repeat(256), "recording\r.jsonl", "recording\u0085.jsonl")) {
            assertFalse(draft("orange", "Orange orchard", OrchardDraft.TOOL_ITEM_ID, OrchardDraft.FRUIT_BLOCK_ID,
                    OrchardDraft.OUTPUT_ITEM_ID, List.of(FRUIT), source).valid());
        }
    }

    @Test void similarItemsAndPreviouslySupportedFruitIdsDoNotWidenTheDraftContract() {
        for (String tool : Arrays.asList(null, "society:cornucopia_extra", "minecraft:iron_hoe", "Society:cornucopia")) {
            assertFalse(draft("orange", "Orange", tool, OrchardDraft.FRUIT_BLOCK_ID,
                    OrchardDraft.OUTPUT_ITEM_ID, List.of(FRUIT), SOURCE).valid());
        }
        for (String block : Arrays.asList(null, FruitRules.BLOCK, "pamhc2trees:pamorange_leaves")) {
            assertFalse(draft("orange", "Orange", OrchardDraft.TOOL_ITEM_ID, block,
                    OrchardDraft.OUTPUT_ITEM_ID, List.of(FRUIT), SOURCE).valid());
        }
        for (String output : Arrays.asList(null, FruitRules.ITEM, "pamhc2trees:orangeitem", "atmospheric:orange_slice")) {
            assertFalse(draft("orange", "Orange", OrchardDraft.TOOL_ITEM_ID, OrchardDraft.FRUIT_BLOCK_ID,
                    output, List.of(FRUIT), SOURCE).valid());
        }
    }

    @Test void fruitObservationsAreDefensivelyCopiedAndCannotBeMutatedThroughTheRecord() {
        List<Pos> observations = new ArrayList<>(List.of(FRUIT));
        OrchardDraft value = draft("orange", observations);
        observations.clear();
        observations.add(FRUIT.offset(1, 0, 0));
        assertEquals(List.of(FRUIT), value.observedFruits());
        assertTrue(value.valid());
        assertThrows(UnsupportedOperationException.class, () -> value.observedFruits().add(FRUIT.offset(2, 0, 0)));
        assertThrows(UnsupportedOperationException.class, () -> value.observedFruits().set(0, FRUIT.offset(2, 0, 0)));
    }

    @Test void missingDuplicateAndInvalidCoordinatesFailValidation() {
        assertFalse(draft("orange", null).valid());
        assertFalse(draft("orange", List.of()).valid());
        assertFalse(draft("orange", Arrays.asList(FRUIT, null)).valid());
        assertFalse(draft("orange", List.of(FRUIT, FRUIT)).valid());
        int limit = CoordinateDestinationRules.MAX_COORDINATE;
        for (Pos pos : List.of(new Pos(limit + 1, 64, 0), new Pos(-limit - 1, 64, 0),
                new Pos(0, 64, limit + 1), new Pos(0, 64, -limit - 1), new Pos(0, -2049, 0),
                new Pos(0, 2048, 0), new Pos(Integer.MIN_VALUE, 64, 0), new Pos(0, 64, Integer.MAX_VALUE))) {
            assertFalse(draft("orange", List.of(pos)).valid(), pos.toString());
        }
    }

    @Test void aDraftBoundsTheNumberOfObservedFruits() {
        List<Pos> observations = IntStream.range(0, 4097).mapToObj(x -> new Pos(x, 70, 0)).toList();
        assertTrue(draft("orange", observations.subList(0, 4096)).valid());
        assertFalse(draft("orange", observations).valid());
    }

    @Test void profileValidationDefaultsLegacyNullDraftsToAnEmptyCollection() {
        Profile profile = new Profile();
        assertTrue(profile.orchardDrafts.isEmpty());
        profile.orchardDrafts = null;
        AdditionalWorkRules.validate(profile);
        assertNotNull(profile.orchardDrafts);
        assertTrue(profile.orchardDrafts.isEmpty());
    }

    @Test void profileRejectsNullDraftsDuplicateIdsAndCoordinatesSharedBetweenDrafts() {
        OrchardDraft first = draft("first", List.of(FRUIT));
        for (OrchardDraft conflicting : Arrays.asList(null, draft("first", List.of(FRUIT.offset(1, 0, 0))),
                draft("other", List.of(FRUIT)), draft("bad", List.of(new Pos(0, 3000, 0))))) {
            Profile profile = new Profile();
            profile.orchardDrafts.add(first);
            profile.orchardDrafts.add(conflicting);
            assertThrows(IllegalArgumentException.class, () -> AdditionalWorkRules.validate(profile));
        }
    }

    @Test void profileBoundsDraftCountWithoutInventingAnyWorkSites() {
        Profile profile = new Profile();
        for (int index = 0; index < 128; index++) {
            profile.orchardDrafts.add(draft("orange_" + index, List.of(new Pos(index, 70, 0))));
        }
        assertDoesNotThrow(() -> AdditionalWorkRules.validate(profile));
        assertTrue(AdditionalWorkRules.sites(profile).isEmpty());
        profile.orchardDrafts.add(draft("orange_128", List.of(new Pos(128, 70, 0))));
        assertThrows(IllegalArgumentException.class, () -> AdditionalWorkRules.validate(profile));
    }

    @Test void enablingExistingFeaturesCannotTurnDraftObservationsIntoWorkOrHarvestAuthority() {
        Profile profile = new Profile();
        profile.orchardDrafts.add(draft("orange", List.of(FRUIT)));
        for (Feature feature : Feature.values()) profile.enabled.put(feature, true);
        AdditionalWorkRules.validate(profile);
        assertTrue(AdditionalWorkRules.sites(profile).isEmpty());
        assertFalse(profile.inWorkArea(FRUIT));
        assertFalse(profile.inWorkArea(FRUIT.offset(1, 0, 0)));
        assertNull(FruitRules.patch(profile, FRUIT));
        assertNull(CropRules.registeredCrop(profile, FRUIT));
        assertFalse(CropRules.scanBlockIds(profile).contains(OrchardDraft.FRUIT_BLOCK_ID));
        Context context = new Context(null, null, null, profile, new SessionState());
        assertNotNull(FruitRules.rejection(context, FRUIT,
                new BlockData(FRUIT, OrchardDraft.FRUIT_BLOCK_ID, Map.of("age", "7")), ItemData.EMPTY));
        assertNotNull(FruitRules.rejection(context, FRUIT,
                new BlockData(FRUIT, FruitRules.BLOCK, Map.of("age", "7")), ItemData.EMPTY),
                "An orange observation must not authorize existing starfruit picking either");
        assertTrue(profile.pois.isEmpty());
        assertTrue(profile.farms.isEmpty());
        assertTrue(profile.fruitPatches.isEmpty());
        assertTrue(profile.coordinateDestinations.isEmpty());
    }
}
