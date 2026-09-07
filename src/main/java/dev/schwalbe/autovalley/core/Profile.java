package dev.schwalbe.autovalley.core;

import java.util.*;

public final class Profile {
    public int schemaVersion = 2;
    public List<Poi> pois = new ArrayList<>();
    public Map<String,MachineGroup> machineGroups = new LinkedHashMap<>();
    public List<Farm> farms = new ArrayList<>();
    public Map<Feature, Boolean> enabled = new EnumMap<>(Feature.class);
    public boolean allowBackground = true;
    public boolean continueHarvestWhenFull = true;
    public int hoeHotbarSlot = 0;
    public boolean sprintHarvest = false;
    public boolean sprintCalibrated = false;
    public int scanRadius = 32;
    public int corridorRadius = 5;
    public int sleepAtTick = 12584;
    public int interactionTimeoutTicks = 100;
    public int harvestCheckTicks = 100;
    public int machineCheckTicks = 40;
    public int resourceRetryTicks = 200;
    public int harvestCycleDays = 1;
    public int wineCycleDays = 6;
    public int preservesCycleDays = 3;
    public Map<String, Long> nextEligibleDay = new HashMap<>();
    public Map<String,PendingMachineOutput> pendingMachineOutputs = new LinkedHashMap<>();
    public List<MachineOutputLedger.ResolutionEntry> machineOutputResolutions = new ArrayList<>();
    public long lastSeenDay = -1;
    public Map<String,Look> disposalDirections = new HashMap<>();
    /** Desired future tomato grade; Poi.classifier remains the actual source grade until the chest is verified empty. */
    public Map<String,Integer> tomatoStorageTargets = new HashMap<>();
    public static String positionKey(Pos pos) { return pos.x()+":"+pos.y()+":"+pos.z(); }
    public Profile() { for (Feature feature : Feature.values()) enabled.put(feature, true); }
    public boolean enabled(Feature f) { return Boolean.TRUE.equals(enabled.get(f)); }
    public List<Poi> pois(PoiKind kind) { return pois.stream().filter(p -> p.kind() == kind).toList(); }
    public boolean inWorkArea(Pos p) {
        if (farms.stream().anyMatch(f -> f.contains(p) || f.contains(p.offset(0,1,0)) || f.contains(p.offset(0,-1,0)) )) return true;
        for (Poi poi : pois) if (poi.pos().distanceSquared(p) <= corridorRadius * corridorRadius) return true;
        return false;
    }
}
