package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoggingPlotTest {
    private static final Pos BASE = new Pos(0, 64, 0);
    private static final ItemData TORCH = new ItemData("minecraft:torch", 12, 0, null, false, 0);
    private static final String FINGERPRINT = "a".repeat(64);

    @Test void plantingPositionsAreExactlyFourSameHeightCellsEastAndSouth() {
        LoggingPlot plot = new LoggingPlot("spruce", BASE);
        assertEquals(List.of(BASE, BASE.offset(1, 0, 0), BASE.offset(0, 0, 1), BASE.offset(1, 0, 1)), plot.plantingPositions());
        assertThrows(UnsupportedOperationException.class, () -> plot.plantingPositions().add(BASE));
    }

    @Test void trunkAndNavigationEnvelopeHaveSeparateBoundedMeanings() {
        LoggingPlot plot = new LoggingPlot("spruce", BASE);
        assertTrue(plot.containsTrunk(BASE)); assertTrue(plot.containsTrunk(BASE.offset(1, 63, 1)));
        for (Pos outside : List.of(BASE.offset(-1, 0, 0), BASE.offset(2, 0, 0), BASE.offset(0, -1, 0), BASE.offset(0, 64, 0)))
            assertFalse(plot.containsTrunk(outside));
        assertTrue(plot.containsEnvelope(BASE.offset(-7, -1, -7))); assertTrue(plot.containsEnvelope(BASE.offset(8, 64, 8)));
        assertFalse(plot.containsTrunk(BASE.offset(7, 0, 7)), "walking envelope is not logging authority");
        assertFalse(plot.containsEnvelope(BASE.offset(9, 0, 0))); assertFalse(plot.containsEnvelope(BASE.offset(0, 65, 0)));
    }

    @Test void malformedCornersAndFarIntegerCoordinatesNeverWrapIntoValidBounds() {
        LoggingPlot malformed = new LoggingPlot("overflow", new Pos(Integer.MAX_VALUE, 64, Integer.MAX_VALUE));
        assertFalse(malformed.containsEnvelope(new Pos(Integer.MIN_VALUE, 64, Integer.MIN_VALUE)));
        assertFalse(malformed.containsTrunk(new Pos(Integer.MIN_VALUE, 64, Integer.MIN_VALUE)));
        assertFalse(new LoggingPlot("missing", null).containsTrunk(BASE));
        for (Pos corner : Arrays.asList(null, new Pos(Integer.MIN_VALUE, 64, 0), new Pos(Integer.MAX_VALUE, 64, 0),
                new Pos(0, -2033, 0), new Pos(0, 1968, 0))) {
            Profile p = new Profile(); p.loggingPlots.add(new LoggingPlot("invalid", corner));
            assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        }
    }

    @Test void duplicateNamesPlantingOverlapAndTomatoOverlapAreRejected() {
        for (LoggingPlot extra : List.of(new LoggingPlot("original", BASE.offset(10, 0, 0)),
                new LoggingPlot("other", BASE), new LoggingPlot("east overlap", BASE.offset(1, 0, 0)))) {
            Profile p = profile(); p.loggingPlots.add(extra);
            assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        }
        Profile p = profile(); p.farms.add(new Farm("tomatoes", BASE, BASE.offset(3, 1, 3)));
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
    }

    @Test void remainingAndReplantingCheckpointsAreDurableSubsetsOfRegisteredCorners() {
        Profile p = profile(); Pos second = BASE.offset(10, 0, 0);
        p.loggingPlots.add(new LoggingPlot("second", second));
        p.loggingRunActive = true; p.loggingRemainingPlots.add(second); p.loggingReplantingPlots.add(second);
        assertDoesNotThrow(() -> LoggingRules.validate(p));
        assertEquals(List.of(second), p.loggingRemainingPlots); assertEquals(List.of(second), p.loggingReplantingPlots);
        p.enabled.put(Feature.LOGGING, false); assertDoesNotThrow(() -> LoggingRules.validate(p));
        p.loggingRemainingPlots.add(second); assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        p.loggingRemainingPlots.remove(1); p.loggingReplantingPlots.add(second);
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
    }

    @Test void inactiveMissingUnknownAndNonRemainingReplantingCheckpointsAreRejected() {
        Profile p = profile(); p.loggingRemainingPlots.add(BASE);
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        p.loggingRunActive = true; p.loggingRemainingPlots.set(0, BASE.offset(100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        p.loggingRemainingPlots.clear(); p.loggingReplantingPlots.add(BASE);
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        p.loggingReplantingPlots.clear(); p.loggingRemainingPlots = null;
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        p.loggingRemainingPlots = new ArrayList<>(); p.loggingReplantingPlots = null;
        assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
    }

    @Test void validHotbarLeaseKeepsExactIdentityAndStageEvenAfterAllTreesAreFinished() {
        Profile p = profile(); p.loggingRunActive = true;
        LoggingHotbarLease lease = new LoggingHotbarLease(9, 3, TORCH, FINGERPRINT);
        for (LoggingHotbarLease.Stage stage : LoggingHotbarLease.Stage.values()) {
            p.loggingHotbarLease = lease.withStage(stage); assertDoesNotThrow(() -> LoggingRules.validate(p));
            assertEquals(TORCH, p.loggingHotbarLease.original()); assertEquals(FINGERPRINT, p.loggingHotbarLease.fingerprint());
            assertEquals(9, p.loggingHotbarLease.sourceIndex()); assertEquals(3, p.loggingHotbarLease.hotbarSlot());
        }
        assertEquals(LoggingHotbarLease.Stage.PREPARED, lease.stage(), "withStage does not mutate the old checkpoint");
        p.loggingRunActive = false; assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
    }

    @Test void leaseRejectsProtectedSlotsInvalidOriginsAndUnverifiableIdentity() {
        List<LoggingHotbarLease> invalid = new ArrayList<>();
        for (int source : new int[] {8, 36}) invalid.add(new LoggingHotbarLease(source, 3, TORCH, FINGERPRINT));
        for (int hotbar : new int[] {-1, 9, 0, 2}) invalid.add(new LoggingHotbarLease(9, hotbar, TORCH, FINGERPRINT));
        for (ItemData original : Arrays.asList(null, ItemData.EMPTY,
                new ItemData("minecraft:netherite_hoe", 1, 0, null, true, 100),
                new ItemData(LoggingRules.AXE, 1, 0, null, false, 100)))
            invalid.add(new LoggingHotbarLease(9, 3, original, FINGERPRINT));
        for (String fingerprint : Arrays.asList(null, "", "a".repeat(63), "g".repeat(64)))
            invalid.add(new LoggingHotbarLease(9, 3, TORCH, fingerprint));
        invalid.add(new LoggingHotbarLease(9, 3, TORCH, FINGERPRINT, null));
        for (LoggingHotbarLease lease : invalid) {
            Profile p = profile(); p.loggingRunActive = true; p.loggingHotbarLease = lease;
            assertThrows(IllegalArgumentException.class, () -> LoggingRules.validate(p));
        }
    }

    private static Profile profile() {
        Profile p = new Profile(); p.loggingAxeHotbarSlot = 2;
        p.loggingPlots.add(new LoggingPlot("original", BASE)); return p;
    }
}
