package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.schwalbe.autovalley.core.MachineOutputLedger.Resolution.*;
import static dev.schwalbe.autovalley.core.PendingMachineOutput.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineOutputLedgerTest {
    private static final Pos MACHINE = new Pos(1, 64, 2);

    @Test void prepareWritesAheadAndCountsOnlyTheExactNativeWineCohort() {
        Fixture f = new Fixture();
        f.inventory(wine(5, 12), wine(3, 12), wine(20, 11), wine(9, null), preserves(7, 0));
        f.onCheckpoint = () -> {
            assertEquals(1, f.profile.pendingMachineOutputs.size());
            PendingMachineOutput output = f.profile.pendingMachineOutputs.values().iterator().next();
            assertEquals(AWAITING_MACHINE_CONFIRMATION, output.phase());
            assertTrue(f.session.liveMachineOutputs.contains(output.id()));
            assertEquals(output.id(), f.session.activeMachineOutputId);
        };
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        assertEquals(1, f.saves);
        assertEquals(9, output.minimumInventoryCount());
        assertEquals(12, output.expectedWineYear());
        assertEquals(3, output.createdDay());
        assertEquals(ItemData.WINE, output.outputId());
        assertTrue(MachineOutputLedger.isPending(f.context(), output.id()));
        assertTrue(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        assertFalse(MachineOutputLedger.ownsActive(f.context(), Feature.PRESERVES));
    }

    @Test void preservesCountsEveryQualityWithoutWineYear() {
        Fixture f = new Fixture(); f.year = null;
        f.inventory(preserves(5, 0), preserves(3, 3), wine(20, 12));
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.PRESERVES, MACHINE);
        assertNull(output.expectedWineYear());
        assertEquals(9, output.minimumInventoryCount());
        assertEquals(ItemData.PRESERVES, output.outputId());
    }

    @Test void unknownOrNegativeNativeWineYearBlocksBeforeWritingAnything() {
        for (Integer year : new Integer[]{null, -1}) {
            Fixture f = new Fixture(); f.year = year;
            assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE));
            assertEquals(0, f.saves); assertTrue(f.profile.pendingMachineOutputs.isEmpty());
            assertTrue(f.session.liveMachineOutputs.isEmpty()); assertNull(f.session.activeMachineOutputId);
        }
    }

    @Test void anyObservedSameIdGroundBlocksEvenForDifferentOrUnknownWineYear() {
        for (Integer year : new Integer[]{12, 11, null}) {
            Fixture f = new Fixture(); f.ground.add(ground(wine(1, year)));
            assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE));
            assertEquals(0, f.saves); assertFalse(MachineOutputLedger.hasPending(f.context()));
        }
        Fixture jars = new Fixture(); jars.ground.add(ground(preserves(1, 3)));
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(jars.context(), Feature.PRESERVES, MACHINE));
        Fixture unrelated = new Fixture(); unrelated.ground.add(ground(preserves(1, 0)));
        assertNotNull(MachineOutputLedger.prepare(unrelated.context(), Feature.WINE, MACHINE));
    }

    @Test void anotherPendingOperationPreventsSharedCountEvidenceForMultipleUses() {
        Fixture f = new Fixture();
        PendingMachineOutput first = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(f.context(), Feature.PRESERVES, MACHINE));
        assertEquals(Map.of(first.id(), first), f.profile.pendingMachineOutputs);
        assertEquals(first.id(), f.session.activeMachineOutputId); assertEquals(1, f.saves);
    }

    @Test void disconnectedUnsupportedFeatureAndExcessiveCountNeverWrite() {
        Fixture disconnected = new Fixture(); disconnected.connected = false;
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(disconnected.context(), Feature.WINE, MACHINE));
        Fixture unsupported = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> MachineOutputLedger.prepare(unsupported.context(), Feature.SHIPPING, MACHINE));
        Fixture full = new Fixture(); full.inventory(wine(2304, 12));
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(full.context(), Feature.WINE, MACHINE));
        assertEquals(0, disconnected.saves + unsupported.saves + full.saves);
        full.inventory(wine(2303, 12));
        assertEquals(2304, MachineOutputLedger.prepare(full.context(), Feature.WINE, MACHINE).minimumInventoryCount());
    }

    @Test void inventoryGrowthBeforeMachineConfirmationCannotResolve() {
        Fixture f = new Fixture();
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        f.inventory(wine(1, 12));
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        assertTrue(MachineOutputLedger.isPending(f.context(), output.id())); assertEquals(1, f.saves);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        assertEquals(AWAITING_PICKUP, f.profile.pendingMachineOutputs.get(output.id()).phase());
        assertEquals(2, f.saves);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        assertEquals(2, f.saves, "confirmation is idempotent");
        assertEquals(1, MachineOutputLedger.reconcile(f.context()));
        assertEquals(3, f.saves); assertFalse(MachineOutputLedger.hasPending(f.context()));
        assertTrue(f.session.liveMachineOutputs.isEmpty()); assertNull(f.session.activeMachineOutputId);
        assertEquals(AUTOMATIC_PICKUP, f.profile.machineOutputResolutions.get(0).resolution());
        assertEquals(0, MachineOutputLedger.reconcile(f.context())); assertEquals(3, f.saves);
    }

    @Test void wrongOrMissingWineYearCannotSatisfyPickupEvenWithHigherTotalCount() {
        Fixture f = new Fixture(); f.inventory(wine(2, 12));
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        f.inventory(wine(2, 12), wine(64, 11), wine(64, null));
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        f.inventory(wine(3, 12), wine(64, 11));
        assertEquals(1, MachineOutputLedger.reconcile(f.context()));
    }

    @Test void disappearingGroundNeverLowersTheImmutableInventoryThreshold() {
        Fixture f = new Fixture(); f.inventory(preserves(3, 0));
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.PRESERVES, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        f.ground.add(ground(preserves(1, 0)));
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        f.ground.clear();
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        assertEquals(4, f.profile.pendingMachineOutputs.get(output.id()).minimumInventoryCount());
    }

    @Test void newSessionCannotClearLoadedDebtFromInventoryNumbersOrConfirmItsUse() {
        Fixture f = new Fixture();
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        f.inventory(wine(64, 12)); f.session = new SessionState();
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        assertTrue(MachineOutputLedger.isPending(f.context(), output.id()));
        assertFalse(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.confirmMachine(f.context(), output.id()));
        assertEquals(2, f.saves);
    }

    @Test void pausedSameSessionMayReconcileWithoutGrantingActiveOwnerPermission() {
        Fixture f = new Fixture();
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        f.session.activeMachineOutputId = null; f.inventory(wine(1, 12));
        assertFalse(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        f.connected = false;
        assertEquals(0, MachineOutputLedger.reconcile(f.context()), "stale disconnected inventory is not evidence");
        f.connected = true;
        assertEquals(1, MachineOutputLedger.reconcile(f.context()));
    }

    @Test void explicitRecoveryAndLossRemoveOnlySelectedIdAndKeepCooldowns() {
        for (MachineOutputLedger.Resolution reason : List.of(RECOVERED_AND_HANDLED, CONFIRMED_LOST)) {
            Fixture f = new Fixture();
            PendingMachineOutput selected = output(Feature.WINE, AWAITING_MACHINE_CONFIRMATION);
            PendingMachineOutput other = output(Feature.PRESERVES, AWAITING_PICKUP);
            f.profile.pendingMachineOutputs.put(selected.id(), selected);
            f.profile.pendingMachineOutputs.put(other.id(), other);
            f.profile.nextEligibleDay.put("wine:1:64:2", 99L);
            f.session.liveMachineOutputs.add(other.id()); f.session.activeMachineOutputId = other.id();
            MachineOutputLedger.resolveByUser(f.context(), selected.id(), reason);
            assertEquals(Map.of(other.id(), other), f.profile.pendingMachineOutputs);
            assertEquals(Map.of("wine:1:64:2", 99L), f.profile.nextEligibleDay);
            assertTrue(f.session.liveMachineOutputs.contains(other.id()));
            assertEquals(other.id(), f.session.activeMachineOutputId);
            assertEquals(new MachineOutputLedger.ResolutionEntry(selected, reason, 3), f.profile.machineOutputResolutions.get(0));
            assertEquals(1, f.saves);
        }
    }

    @Test void automaticOrMissingReasonAndUnknownOperationAreNotUserAcknowledgements() {
        Fixture f = new Fixture();
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        assertThrows(IllegalArgumentException.class, () -> MachineOutputLedger.resolveByUser(f.context(), output.id(), AUTOMATIC_PICKUP));
        assertThrows(IllegalArgumentException.class, () -> MachineOutputLedger.resolveByUser(f.context(), output.id(), null));
        assertThrows(IllegalArgumentException.class, () -> MachineOutputLedger.resolveByUser(f.context(), UUID.randomUUID().toString(), CONFIRMED_LOST));
        assertTrue(MachineOutputLedger.isPending(f.context(), output.id())); assertEquals(1, f.saves);
    }

    @Test void failedPrepareRollsBackRecordAndAllLivePermissions() {
        Fixture f = new Fixture(); f.failSave = true;
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty()); assertTrue(f.profile.machineOutputResolutions.isEmpty());
        assertTrue(f.session.liveMachineOutputs.isEmpty()); assertNull(f.session.activeMachineOutputId);
        f.failSave = false;
        assertNotNull(MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE));
    }

    @Test void failedMachineConfirmationKeepsUnconfirmedPhaseAndRequiresSuccessfulRetry() {
        Fixture f = new Fixture();
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        f.failSave = true;
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.confirmMachine(f.context(), output.id()));
        assertEquals(output, f.profile.pendingMachineOutputs.get(output.id()));
        assertTrue(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        f.failSave = false; f.inventory(wine(1, 12));
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        assertEquals(1, MachineOutputLedger.reconcile(f.context()));
    }

    @Test void failedAutomaticResolutionRestoresRecordHistoryAndLiveEvidence() {
        Fixture f = new Fixture();
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        PendingMachineOutput confirmed = f.profile.pendingMachineOutputs.get(output.id());
        f.inventory(wine(1, 12)); f.failSave = true;
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.reconcile(f.context()));
        assertEquals(Map.of(output.id(), confirmed), f.profile.pendingMachineOutputs);
        assertTrue(f.profile.machineOutputResolutions.isEmpty());
        assertEquals(output.id(), f.session.activeMachineOutputId);
        assertTrue(f.session.liveMachineOutputs.contains(output.id()));
        f.failSave = false;
        assertEquals(1, MachineOutputLedger.reconcile(f.context()));
    }

    @Test void failedUserResolutionRestoresEvenTheOldestTrimmedHistoryEntry() {
        Fixture f = new Fixture();
        for (int i = 0; i < 64; i++) f.profile.machineOutputResolutions.add(new MachineOutputLedger.ResolutionEntry(
                output(Feature.PRESERVES, AWAITING_PICKUP), CONFIRMED_LOST, 3));
        List<MachineOutputLedger.ResolutionEntry> history = List.copyOf(f.profile.machineOutputResolutions);
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        f.failSave = true;
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.resolveByUser(f.context(), output.id(), CONFIRMED_LOST));
        assertEquals(history, f.profile.machineOutputResolutions);
        assertTrue(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        f.failSave = false;
        MachineOutputLedger.resolveByUser(f.context(), output.id(), CONFIRMED_LOST);
        assertEquals(64, f.profile.machineOutputResolutions.size());
        assertEquals(history.subList(1, 64), f.profile.machineOutputResolutions.subList(0, 63));
        assertEquals(output, f.profile.machineOutputResolutions.get(63).output());
        assertFalse(MachineOutputLedger.hasPending(f.context()));
    }

    @Test void missingLiveTokenCannotBeRecreatedBySettingOnlyActiveId() {
        Fixture f = new Fixture();
        PendingMachineOutput output = output(Feature.WINE, AWAITING_PICKUP);
        f.profile.pendingMachineOutputs.put(output.id(), output);
        f.session.activeMachineOutputId = output.id(); f.inventory(wine(64, 12));
        assertFalse(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
    }

    @Test void manualInteractionRevokesOnlyLiveEvidenceWithoutSavingOrChangingDurableState() {
        Fixture f = new Fixture();
        f.profile.machineOutputResolutions.add(new MachineOutputLedger.ResolutionEntry(
                output(Feature.PRESERVES, AWAITING_PICKUP), CONFIRMED_LOST, 3));
        f.profile.nextEligibleDay.put("wine:1:64:2", 99L);
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        Map<String, PendingMachineOutput> pending = Map.copyOf(f.profile.pendingMachineOutputs);
        List<MachineOutputLedger.ResolutionEntry> history = List.copyOf(f.profile.machineOutputResolutions);
        Map<String, Long> deadlines = Map.copyOf(f.profile.nextEligibleDay);
        f.failSave = true;

        MachineOutputLedger.invalidateLiveEvidence(f.context());
        MachineOutputLedger.invalidateLiveEvidence(f.context());

        assertEquals(pending, f.profile.pendingMachineOutputs);
        assertEquals(history, f.profile.machineOutputResolutions);
        assertEquals(deadlines, f.profile.nextEligibleDay);
        assertTrue(f.session.liveMachineOutputs.isEmpty()); assertNull(f.session.activeMachineOutputId);
        assertFalse(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        assertEquals(2, f.saves, "revoking transient evidence must not depend on disk availability");
    }

    @Test void manualSameCohortIncreaseCannotResolveOrRegainPermissionOnRestart() {
        Fixture f = new Fixture(); f.inventory(wine(2, 12));
        PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
        MachineOutputLedger.confirmMachine(f.context(), output.id());
        MachineOutputLedger.invalidateLiveEvidence(f.context());
        // The user took an old bottle out of a chest, then closed it. The world has
        // exactly the old target count, but the new inventory cannot prove this pickup.
        f.inventory(wine(3, 12));
        assertEquals(0, MachineOutputLedger.reconcile(f.context()));
        AutomationModule consumer = new AutomationModule() {
            @Override public Feature feature() { return Feature.WINE_STORAGE; }
            @Override public int priority() { return 0; }
            @Override public WorkResult tick(Context c) { fail("Unverified output must block the storage consumer"); return WorkResult.idle(); }
            @Override public void reset() { }
        };
        AutomationEngine engine = new AutomationEngine(List.of(consumer));
        engine.startOnce(f.context(), Feature.WINE_STORAGE); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED, engine.state());
        engine.start(f.context()); engine.tick(f.context());
        assertFalse(engine.running());
        assertTrue(MachineOutputLedger.isPending(f.context(), output.id()));
        assertTrue(f.profile.machineOutputResolutions.isEmpty()); assertEquals(2, f.saves);
        assertTrue(f.session.liveMachineOutputs.isEmpty()); assertNull(f.session.activeMachineOutputId);
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.confirmMachine(f.context(), output.id()));
        assertThrows(IllegalStateException.class, () -> MachineOutputLedger.prepare(f.context(), Feature.PRESERVES, MACHINE));
    }

    @Test void invalidatedEvidenceStillAllowsOnlyAnExplicitRecoveryOrLossAcknowledgement() {
        for (MachineOutputLedger.Resolution reason : List.of(RECOVERED_AND_HANDLED, CONFIRMED_LOST)) {
            Fixture f = new Fixture();
            PendingMachineOutput output = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
            MachineOutputLedger.confirmMachine(f.context(), output.id());
            MachineOutputLedger.invalidateLiveEvidence(f.context()); f.inventory(wine(64, 12));
            assertEquals(0, MachineOutputLedger.reconcile(f.context()));
            MachineOutputLedger.resolveByUser(f.context(), output.id(), reason);
            assertFalse(MachineOutputLedger.hasPending(f.context()));
            assertEquals(reason, f.profile.machineOutputResolutions.get(0).resolution());
            assertEquals(3, f.saves);
            PendingMachineOutput next = MachineOutputLedger.prepare(f.context(), Feature.WINE, MACHINE);
            assertNotEquals(output.id(), next.id()); assertEquals(65, next.minimumInventoryCount());
            assertEquals(1, f.session.liveMachineOutputs.size());
            assertFalse(f.session.liveMachineOutputs.contains(output.id()));
        }
    }

    private static PendingMachineOutput output(Feature feature, PendingMachineOutput.Phase phase) {
        return new PendingMachineOutput(UUID.randomUUID().toString(), feature, MACHINE, 3,
                feature == Feature.WINE ? 12 : null, 1, phase);
    }

    private static ItemData wine(int count, Integer year) { return new ItemData(ItemData.WINE, count, 0, year, false, 0); }
    private static ItemData preserves(int count, int quality) { return new ItemData(ItemData.PRESERVES, count, quality, null, false, 0); }
    private static GroundItem ground(ItemData item) { return new GroundItem(1, 1.5, 64, 2.5, item); }

    private static final class Fixture implements WorldAccess, ActionPort, Navigation {
        final Profile profile = new Profile();
        SessionState session = new SessionState();
        final List<ItemSlot> items = new ArrayList<>();
        final List<GroundItem> ground = new ArrayList<>();
        Integer year = 12;
        boolean connected = true, failSave;
        int saves;
        Runnable onCheckpoint = () -> { };

        Context context() { return new Context(this, this, this, profile, session, () -> {
            saves++; onCheckpoint.run();
            if (failSave) throw new IllegalStateException("test disk failure");
        }); }
        void inventory(ItemData... stacks) {
            items.clear();
            for (int i = 0; i < stacks.length; i++) items.add(new ItemSlot(i, i, true, stacks[i]));
        }
        @Override public long tick() { return 100; }
        @Override public long dayTime() { return 3 * 24000 + 1000; }
        @Override public PlayerState player() { return new PlayerState(.5, 64, .5, 0, 0, true, false, 20, 20, 0, connected, true); }
        @Override public BlockData block(Pos pos) { return new BlockData(pos, "society:wine_keg", Map.of()); }
        @Override public boolean loaded(Pos pos) { return true; }
        @Override public boolean canStand(Pos feet) { return true; }
        @Override public boolean canTraverse(Pos from, Pos to) { return true; }
        @Override public List<BlockData> scan(Pos center, int horizontalRadius, int verticalRadius) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return items; }
        @Override public List<GroundItem> groundItems() { return ground; }
        @Override public Integer wineYear() { return year; }
        @Override public MenuData menu() { return new MenuData(0, 0, items, ItemData.EMPTY, false); }
        @Override public boolean mayPlace(int menuSlot, ItemData item) { return true; }
        @Override public boolean busy() { return false; }
        @Override public long submit(Action action) { fail("Ledger tests must not issue gameplay actions"); return 0; }
        @Override public ActionOutcome outcome(long ticket) { return new ActionOutcome(ActionOutcome.State.CANCELLED, "no action"); }
        @Override public void move(Movement movement) { fail("Ledger tests must not issue movement"); }
        @Override public void stopMovement() { }
        @Override public void cancel() { }
        @Override public Navigation.Result moveTo(Pos target, double reach, Context c) { fail("Ledger tests must not request navigation"); return Navigation.Result.BLOCKED; }
        @Override public void reset() { }
    }
}
