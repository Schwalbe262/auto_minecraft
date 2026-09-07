package dev.schwalbe.autovalley.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Write-ahead accounting for uncertain machine output. Persistence is synchronous: callers
 * must not dispatch Use until prepare returns, or continue work until reconciliation returns.
 * A persisted count is never sufficient evidence in a new connection's SessionState.
 */
public final class MachineOutputLedger {
    private static final int MAX_PENDING = 16;
    private static final int MAX_HISTORY = 64;
    private static final int MAX_INVENTORY_COUNT = 36 * 64;

    public enum Resolution {
        AUTOMATIC_PICKUP, RECOVERED_AND_HANDLED, CONFIRMED_LOST,
        /** User-disabled wine pickup tracking; deliberately makes no recovery or loss claim. */
        WINE_PICKUP_TRACKING_DISABLED
    }

    public record ResolutionEntry(PendingMachineOutput output, Resolution resolution, long resolvedDay) { }

    private MachineOutputLedger() { }

    public static PendingMachineOutput prepare(Context c, Feature feature, Pos machine) {
        validate(c.profile());
        if (hasPending(c)) throw new IllegalStateException("Resolve the pending machine output before another machine use");
        if (feature != Feature.WINE && feature != Feature.PRESERVES)
            throw new IllegalArgumentException("Only wine and preserves machines produce tracked output");
        if (!c.world().player().connected()) throw new IllegalStateException("Machine output requires a connected world");
        Integer year = feature == Feature.WINE ? c.world().wineYear() : null;
        if (feature == Feature.WINE && (year == null || year < 0))
            throw new IllegalStateException("The native wine year is unavailable; machine use is blocked");
        String outputId = feature == Feature.WINE ? ItemData.WINE : ItemData.PRESERVES;
        // Even another wine cohort may be collected during Use. Do not claim an old ground
        // item as this machine's new output, and do not invent a quantity for unknown metadata.
        for (GroundItem ground : c.world().groundItems()) {
            if (ground.item().is(outputId))
                throw new IllegalStateException("Existing ground output must be handled before machine use");
        }
        long before = countInventory(c, outputId, year);
        if (before >= MAX_INVENTORY_COUNT)
            throw new IllegalStateException("Machine output exceeds observable inventory capacity");
        PendingMachineOutput output = new PendingMachineOutput(UUID.randomUUID().toString(), feature,
                machine, day(c), year, (int) before + 1, PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION);
        validateOutput(output);
        checkpoint(c, () -> {
            c.profile().pendingMachineOutputs.put(output.id(), output);
            c.session().liveMachineOutputs.add(output.id());
            c.session().activeMachineOutputId = output.id();
        });
        return output;
    }

    public static void confirmMachine(Context c, String id) {
        PendingMachineOutput output = requirePending(c, id);
        if (!c.session().liveMachineOutputs.contains(id) || !Objects.equals(c.session().activeMachineOutputId, id))
            throw new IllegalStateException("A restored or inactive machine output requires explicit review");
        if (output.phase() == PendingMachineOutput.Phase.AWAITING_PICKUP) return;
        PendingMachineOutput confirmed = new PendingMachineOutput(output.id(), output.feature(), output.machine(),
                output.createdDay(), output.expectedWineYear(), output.minimumInventoryCount(),
                PendingMachineOutput.Phase.AWAITING_PICKUP);
        checkpoint(c, () -> c.profile().pendingMachineOutputs.put(id, confirmed));
    }

    /** Returns the number resolved; ground absence and loaded persisted baselines prove nothing. */
    public static int reconcile(Context c) {
        validate(c.profile());
        if (!c.world().player().connected()) return 0;
        List<PendingMachineOutput> collected = new ArrayList<>();
        for (PendingMachineOutput output : c.profile().pendingMachineOutputs.values()) {
            if (output.phase() == PendingMachineOutput.Phase.AWAITING_PICKUP
                    && c.session().liveMachineOutputs.contains(output.id())
                    && countInventory(c, output.outputId(), output.expectedWineYear()) >= output.minimumInventoryCount())
                collected.add(output);
        }
        if (collected.isEmpty()) return 0;
        checkpoint(c, () -> {
            for (PendingMachineOutput output : collected) resolve(c, output, Resolution.AUTOMATIC_PICKUP);
        });
        return collected.size();
    }

    public static boolean isPending(Context c, String id) {
        return id != null && c.profile().pendingMachineOutputs.containsKey(id);
    }

    public static boolean hasPending(Context c) {
        return !c.profile().pendingMachineOutputs.isEmpty();
    }

    /**
     * Explicit compatibility migration after the user disabled wine pickup tracking.
     * Run with the loaded profile before reconciliation/start gates, never from a query.
     * Only historical WINE obligations are archived. This does not inspect inventory,
     * infer collection, acknowledge native actions, modify cooldowns, or permit sales.
     * The ordinary bounded resolution-history retention policy still applies.
     */
    public static int archiveWinePickupTrackingDisabled(Context c) {
        validate(c.profile());
        List<PendingMachineOutput> wine=c.profile().pendingMachineOutputs.values().stream()
                .filter(output -> output.feature()==Feature.WINE).toList();
        if (wine.isEmpty()) return 0;
        if (!c.world().player().connected())
            throw new IllegalStateException("Wine ledger migration requires the loaded world context");
        day(c); // Validate the archive date before mutating any ledger field.
        checkpoint(c, () -> {
            for (PendingMachineOutput output: wine) resolve(c,output,Resolution.WINE_PICKUP_TRACKING_DISABLED);
        });
        return wine.size();
    }

    public static boolean ownsActive(Context c, Feature feature) {
        String id = c.session().activeMachineOutputId;
        PendingMachineOutput output = id == null ? null : c.profile().pendingMachineOutputs.get(id);
        return output != null && output.feature() == feature && c.session().liveMachineOutputs.contains(id);
    }

    /**
     * Manual item interaction makes an inventory increase ambiguous. Revoke only the
     * transient evidence, never the durable obligation. Closing a screen or restarting
     * automation must not reconstruct these tokens from a persisted numeric baseline.
     */
    public static void invalidateLiveEvidence(Context c) {
        c.session().liveMachineOutputs.clear();
        c.session().activeMachineOutputId = null;
    }

    /** An explicit user acknowledgement, not an inventory inference or a retry permission. */
    public static void resolveByUser(Context c, String id, Resolution reason) {
        if (reason != Resolution.RECOVERED_AND_HANDLED && reason != Resolution.CONFIRMED_LOST)
            throw new IllegalArgumentException("A user resolution must explicitly acknowledge recovery or loss");
        PendingMachineOutput output = requirePending(c, id);
        checkpoint(c, () -> resolve(c, output, reason));
    }

    private static PendingMachineOutput requirePending(Context c, String id) {
        validate(c.profile());
        PendingMachineOutput output = id == null ? null : c.profile().pendingMachineOutputs.get(id);
        if (output == null) throw new IllegalArgumentException("The selected machine output is no longer pending");
        return output;
    }

    private static long countInventory(Context c, String outputId, Integer year) {
        long count = 0;
        for (ItemSlot slot : c.world().inventory()) {
            ItemData item = slot.item();
            if (item.is(outputId) && (!ItemData.WINE.equals(outputId) || Objects.equals(year, item.year())))
                count += item.count();
        }
        return count;
    }

    private static long day(Context c) {
        long day = Math.floorDiv(c.world().dayTime(), 24000L);
        if (day < 0) throw new IllegalStateException("Machine output requires a valid world day");
        return day;
    }

    private static void resolve(Context c, PendingMachineOutput output, Resolution reason) {
        c.profile().pendingMachineOutputs.remove(output.id());
        c.session().liveMachineOutputs.remove(output.id());
        if (Objects.equals(c.session().activeMachineOutputId, output.id())) c.session().activeMachineOutputId = null;
        c.profile().machineOutputResolutions.add(new ResolutionEntry(output, reason, day(c)));
        while (c.profile().machineOutputResolutions.size() > MAX_HISTORY) c.profile().machineOutputResolutions.remove(0);
    }

    /** Restore every ledger-owned mutable field if validation or persistence fails. */
    private static void checkpoint(Context c, Runnable mutation) {
        Map<String, PendingMachineOutput> pending = new LinkedHashMap<>(c.profile().pendingMachineOutputs);
        List<ResolutionEntry> history = new ArrayList<>(c.profile().machineOutputResolutions);
        Set<String> live = new HashSet<>(c.session().liveMachineOutputs);
        String active = c.session().activeMachineOutputId;
        try {
            mutation.run();
            validate(c.profile());
            c.checkpoint().run();
        } catch (RuntimeException failure) {
            c.profile().pendingMachineOutputs.clear();
            c.profile().pendingMachineOutputs.putAll(pending);
            c.profile().machineOutputResolutions.clear();
            c.profile().machineOutputResolutions.addAll(history);
            c.session().liveMachineOutputs.clear();
            c.session().liveMachineOutputs.addAll(live);
            c.session().activeMachineOutputId = active;
            throw new IllegalStateException("Machine-output checkpoint failed; automation must remain paused", failure);
        }
    }

    public static void validate(Profile profile) {
        if (profile == null || profile.pendingMachineOutputs == null || profile.machineOutputResolutions == null)
            throw new IllegalArgumentException("Missing machine-output ledger");
        if (profile.pendingMachineOutputs.size() > MAX_PENDING || profile.machineOutputResolutions.size() > MAX_HISTORY)
            throw new IllegalArgumentException("Machine-output ledger exceeds its bounded size");
        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, PendingMachineOutput> entry : profile.pendingMachineOutputs.entrySet()) {
            validateOutput(entry.getValue());
            if (!Objects.equals(entry.getKey(), entry.getValue().id()) || !ids.add(entry.getKey()))
                throw new IllegalArgumentException("Machine-output key does not match its unique operation ID");
        }
        for (ResolutionEntry entry : profile.machineOutputResolutions) {
            if (entry == null || entry.resolution() == null || entry.resolvedDay() < 0)
                throw new IllegalArgumentException("Invalid machine-output resolution");
            validateOutput(entry.output());
            if (entry.resolution() == Resolution.WINE_PICKUP_TRACKING_DISABLED && entry.output().feature() != Feature.WINE)
                throw new IllegalArgumentException("Disabled wine pickup tracking cannot resolve preserves output");
            if (!ids.add(entry.output().id())) throw new IllegalArgumentException("Duplicate machine-output operation ID");
            if (entry.resolution() == Resolution.AUTOMATIC_PICKUP
                    && entry.output().phase() != PendingMachineOutput.Phase.AWAITING_PICKUP)
                throw new IllegalArgumentException("Unconfirmed machine use cannot have an automatic pickup resolution");
        }
    }

    private static void validateOutput(PendingMachineOutput output) {
        if (output == null || output.id() == null
                || !output.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || output.machine() == null || output.createdDay() < 0 || output.phase() == null
                || output.minimumInventoryCount() < 1 || output.minimumInventoryCount() > MAX_INVENTORY_COUNT)
            throw new IllegalArgumentException("Invalid pending machine output");
        if (output.feature() == Feature.WINE) {
            if (output.expectedWineYear() == null || output.expectedWineYear() < 0)
                throw new IllegalArgumentException("Wine output requires a known native year");
        } else if (output.feature() == Feature.PRESERVES) {
            if (output.expectedWineYear() != null)
                throw new IllegalArgumentException("Preserves output cannot have a wine year");
        } else throw new IllegalArgumentException("Unsupported pending machine feature");
    }
}
