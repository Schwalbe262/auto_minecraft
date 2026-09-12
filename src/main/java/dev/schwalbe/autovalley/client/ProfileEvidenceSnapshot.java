package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Detached, bounded client-thread memory evidence. Never reads or writes a profile file. */
public final class ProfileEvidenceSnapshot {
    private static final Gson GSON = new Gson();
    private static final int MAX_SCHEDULE = 20000, MAX_CONTINUATIONS = 2048, MAX_HISTORY = 4096;
    private static final List<String> PERSISTENCE_FIELDS = List.of(
        "profileLoaded", "memoryProfileMatchesContext", "errorLatched", "recoveryPending",
        "lastSaveCommitted", "lastSuccessfulCommitAtUtc", "stage", "exceptionClass", "attempts",
        "committed", "recoveryAttempts", "nextRetryTick", "autoResumeEligible", "lastRecoveryOutcome");

    private ProfileEvidenceSnapshot() { }

    /** Static four-base growth observation only: not hasWork, route planning, canopy safety, or action authority. */
    public static Map<String,Object> loggingGrowth(WorldAccess world, Profile profile, boolean connected) {
        Map<String,Object> result = new LinkedHashMap<>();
        List<Map<String,Object>> plots = new ArrayList<>();
        int registered = profile.loggingPlots == null ? 0 : profile.loggingPlots.size();
        boolean truncated = registered > 32, unknown = !connected || profile.loggingPlots == null;
        int readyCount = 0;
        if (profile.loggingPlots != null) for (int i=0; i<Math.min(32,registered); i++) {
            LoggingPlot plot = profile.loggingPlots.get(i);
            Map<String,Object> observed = new LinkedHashMap<>();
            observed.put("index", i); observed.put("corner", plot == null ? null : plot.corner());
            List<Map<String,Object>> bases = new ArrayList<>();
            boolean allKnown = connected && plot != null && plot.corner() != null, ready = allKnown;
            if (plot != null && plot.corner() != null) for (Pos pos : plot.plantingPositions()) {
                boolean loaded = connected && world.loaded(pos);
                BlockData block = loaded ? world.block(pos) : null;
                boolean known = loaded && block != null && block.id() != null;
                Map<String,Object> base = new LinkedHashMap<>();
                base.put("pos",pos); base.put("loaded",loaded); base.put("blockId",known ? block.id() : null);
                base.put("plantedSapling",known ? LoggingRules.plantedSapling(block) : null);
                base.put("stump",known ? LoggingRules.stump(block) : null);
                bases.add(base); allKnown &= known;
                // Matches the module's initial ALL_GROWN test; chopped logs alone are not mature spruce.
                ready &= known && LoggingRules.LOG.equals(block.id());
            }
            unknown |= !allKnown;
            if (allKnown && ready) readyCount++;
            observed.put("bases",bases); observed.put("ready",allKnown ? ready : null);
            observed.put("unknown",!allKnown); plots.add(observed);
        }
        result.put("registeredPlotCount",registered); result.put("mode",profile.loggingMode == null ? null : profile.loggingMode.name());
        result.put("plots",plots); result.put("truncated",truncated); result.put("unknown",unknown || truncated);
        result.put("observedReadyCount",readyCount);
        result.put("allRegisteredReady",unknown || truncated || registered == 0 ? null : readyCount == registered);
        result.put("limitation","Loaded base blocks only; not canopy safety, reachability, receipts, or scheduler readiness.");
        return result;
    }

    /** Caller must hold the client-thread boundary; commit information describes attempts, not RAM equality. */
    public static Map<String,Object> capture(Profile profile, String profileKey, String capturedAt,
                                             Map<String,Object> persistence) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("source", "CLIENT_THREAD_MEMORY");
        result.put("capturedAt", capturedAt);
        result.put("profileKey", profileKey != null && profileKey.matches("[a-f0-9]{24}") ? profileKey : null);
        result.put("diskStateMatchesMemory", null); // No disk inspection or equality claim.
        result.put("available", false);
        try {
            require(profile != null && profileKey != null && profileKey.matches("[a-f0-9]{24}"));
            Map<String,Object> safePersistence = new LinkedHashMap<>();
            for (String key : PERSISTENCE_FIELDS) {
                Object value = Objects.requireNonNull(persistence).get(key);
                require(value == null || value instanceof Boolean || value instanceof Number ||
                    value instanceof String text && text.length() <= 128);
                safePersistence.put(key, value);
            }
            result.put("persistence", safePersistence);
            Map<String,Object> enabled = new LinkedHashMap<>();
            for (Feature feature : Feature.values()) enabled.put(feature.name(), profile.enabled(feature));
            result.put("enabled", enabled);

            bounded(profile.nextEligibleDay, MAX_SCHEDULE);
            SortedMap<String,Long> schedule = new TreeMap<>(profile.nextEligibleDay);
            Map<String,long[]> families = new TreeMap<>();
            for (var entry : schedule.entrySet()) {
                require(entry.getKey().length() <= 256 && entry.getValue() != null && entry.getValue() >= 0);
                String family = entry.getKey().split(":", 2)[0];
                long day = entry.getValue();
                long[] values = families.computeIfAbsent(family, ignored -> new long[]{0, day, day});
                values[0]++; values[1] = Math.min(values[1], day); values[2] = Math.max(values[2], day);
            }
            require(families.size() <= 128);
            List<Object> familyEvidence = new ArrayList<>();
            families.forEach((name, values) -> familyEvidence.add(Map.of("family", name, "count", values[0],
                "minimumDay", values[1], "maximumDay", values[2])));
            result.put("schedule", Map.of("count", schedule.size(), "digest", digest(schedule), "families", familyEvidence));

            bounded(profile.loggingPlots, MAX_CONTINUATIONS);
            bounded(profile.loggingRemainingPlots, MAX_CONTINUATIONS);
            bounded(profile.loggingReplantingPlots, MAX_CONTINUATIONS);
            Map<String,Object> logging = new LinkedHashMap<>();
            logging.put("enabled", profile.enabled(Feature.LOGGING));
            logging.put("active", profile.loggingRunActive);
            logging.put("registeredPlotCount", profile.loggingPlots.size());
            logging.put("remainingCount", profile.loggingRemainingPlots.size());
            logging.put("replantingCount", profile.loggingReplantingPlots.size());
            logging.put("pendingDigest", digest(List.of(List.copyOf(profile.loggingRemainingPlots), List.copyOf(profile.loggingReplantingPlots))));
            logging.put("dueDay", schedule.get(LoggingRules.DUE_KEY));
            logging.put("lease", loggingLease(profile.loggingHotbarLease));
            result.put("logging", logging);
            result.put("workHotbarLease", workLease(profile.workHotbarLease));

            bounded(profile.crystalRefills, MAX_CONTINUATIONS);
            SortedMap<String,CrystalRefill> refills = new TreeMap<>(profile.crystalRefills);
            for (var refill : refills.entrySet()) {
                require(refill.getKey().length() <= 256 && refill.getValue() != null && refill.getValue().valid());
            }
            result.put("crystalRefillCount", refills.size());
            result.put("crystalRefillDigest", digest(refills));
            result.put("crystalRefills", refills.values().stream().limit(32).toList());
            result.put("crystalRefillsTruncated", refills.size() > 32);

            // These validators inspect only immutable continuation records; they do not migrate or save Profile.
            MachineOutputLedger.validate(profile);
            ManualWorkHotbarResolution.validate(profile);
            ManualLoggingHotbarResolution.validate(profile);
            bounded(profile.pendingMachineOutputs, MAX_CONTINUATIONS);
            SortedMap<String,PendingMachineOutput> pending = new TreeMap<>(profile.pendingMachineOutputs);
            for (var output : pending.entrySet()) {
                require(output.getKey().length() <= 256 && output.getValue() != null);
            }
            result.put("pendingMachineOutputCount", pending.size());
            result.put("pendingMachineOutputDigest", digest(pending));
            result.put("pendingMachineOutputs", pending.values().stream().limit(32).toList());
            result.put("pendingMachineOutputsTruncated", pending.size() > 32);
            history(result, "machineOutputResolutions", profile.machineOutputResolutions);
            history(result, "manualWorkHotbarResolutions", profile.manualWorkHotbarResolutions);
            history(result, "manualLoggingHotbarResolutions", profile.manualLoggingHotbarResolutions);
            result.put("lastSeenDay", profile.lastSeenDay);
            result.put("available", true);
        } catch (RuntimeException exception) {
            // Preserve only provenance on malformed/oversized RAM; never substitute a disk read or zero obligations.
            result.keySet().retainAll(Set.of("schemaVersion", "source", "capturedAt", "profileKey", "diskStateMatchesMemory", "available"));
            result.put("reason", "MEMORY_EVIDENCE_UNAVAILABLE");
        }
        return result;
    }

    private static void history(Map<String,Object> result, String name, List<?> entries) {
        bounded(entries, MAX_HISTORY);
        List<?> copy = List.copyOf(entries);
        result.put(name + "Count", copy.size());
        result.put(name + "Digest", digest(copy));
        result.put(name, copy.subList(Math.max(0, copy.size() - 16), copy.size()));
        result.put(name + "Truncated", copy.size() > 16);
    }

    private static Map<String,Object> loggingLease(LoggingHotbarLease lease) {
        if (lease == null) return null;
        require(ManualLoggingHotbarResolution.validLease(lease));
        return lease(lease.sourceIndex(), lease.hotbarSlot(), lease.original(), lease.fingerprint(), lease.stage().name());
    }

    private static Map<String,Object> workLease(HotbarLease lease) {
        if (lease == null) return null;
        require(lease.valid());
        Map<String,Object> evidence = lease(lease.sourceIndex(), lease.hotbarSlot(), lease.original(), lease.fingerprint(), lease.stage().name());
        evidence.put("owner", lease.owner().name());
        return evidence;
    }

    private static Map<String,Object> lease(int source, int hotbar, ItemData item, String fingerprint, String stage) {
        require(source >= 9 && source <= 35 && hotbar >= 0 && hotbar <= 8 && item != null && item.id() != null && item.id().length() <= 128);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("sourceIndex", source); result.put("hotbarSlot", hotbar); result.put("original", item);
        result.put("fingerprint", fingerprint); result.put("stage", stage);
        return result;
    }

    private static void bounded(Map<?,?> values, int limit) { require(values != null && values.size() <= limit); }
    private static void bounded(Collection<?> values, int limit) { require(values != null && values.size() <= limit); }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid bounded memory evidence"); }
    private static String digest(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GSON.toJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
