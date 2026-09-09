package dev.schwalbe.autovalley.core;

import java.util.*;

public final class Profile {
    public int schemaVersion = 5;
    public NavigationMode navigationMode = NavigationMode.TERRAIN;
    public boolean useWaypointHints = true;
    /** Coordinate drafts remain separate from registered, authorized work locations. */
    public List<CoordinateDestination> coordinateDestinations = new ArrayList<>();
    public List<Poi> pois = new ArrayList<>();
    public Map<String,MachineGroup> machineGroups = new LinkedHashMap<>();
    public Map<String,ArtisanJob> artisanJobs = new LinkedHashMap<>();
    public List<Farm> farms = new ArrayList<>();
    public Map<String,CommodityStore> commodityStores = new LinkedHashMap<>();
    public Map<String,String> cropStores = new LinkedHashMap<>();
    public List<FruitPatch> fruitPatches = new ArrayList<>();
    public Map<String,CropDefinition> crops = CropRules.defaults();
    public List<LoggingPlot> loggingPlots = new ArrayList<>();
    public boolean loggingRunActive = false;
    public List<Pos> loggingRemainingPlots = new ArrayList<>();
    public List<Pos> loggingReplantingPlots = new ArrayList<>();
    public LoggingHotbarLease loggingHotbarLease = null;
    public Map<Feature, Boolean> enabled = new EnumMap<>(Feature.class);
    public boolean allowBackground = true;
    public boolean continueHarvestWhenFull = true;
    /** Keep this share of tomato storage capacity; ship only surplus through registered shipping. */
    public int tomatoStorageLimitPercent = 90;
    public boolean tomatoSurplusShippingEnabled = true;
    public int hoeHotbarSlot = 0;
    public int loggingAxeHotbarSlot = -1;
    public int loggingSaplingReserve = 0;
    public LoggingMode loggingMode = LoggingMode.ALL_GROWN;
    public int loggingCheckTicks = 1200;
    public int loggingCycleDays = 1;
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
    public WineBatchSchedule wineBatchSchedule;
    public Map<String,PendingMachineOutput> pendingMachineOutputs = new LinkedHashMap<>();
    public List<MachineOutputLedger.ResolutionEntry> machineOutputResolutions = new ArrayList<>();
    public long lastSeenDay = -1;
    public Map<String,Look> disposalDirections = new HashMap<>();
    /** Desired future tomato grade; Poi.classifier remains the actual source grade until the chest is verified empty. */
    public Map<String,Integer> tomatoStorageTargets = new HashMap<>();
    public static String positionKey(Pos pos) { return pos.x()+":"+pos.y()+":"+pos.z(); }
    public Profile() { for (Feature feature : Feature.values()) enabled.put(feature, feature.defaultEnabled()); }
    public boolean enabled(Feature f) { return Boolean.TRUE.equals(enabled.get(f)); }
    public List<Poi> pois(PoiKind kind) { return pois.stream().filter(p -> p.kind() == kind).toList(); }
    public boolean inWorkArea(Pos p) {
        if (farms.stream().anyMatch(f -> f.contains(p) || f.contains(p.offset(0,1,0)) || f.contains(p.offset(0,-1,0)) )) return true;
        for (Poi poi : pois) if (poi.pos().distanceSquared(p) <= corridorRadius * corridorRadius) return true;
        for (Pos site:AdditionalWorkRules.sites(this)) if(site.distanceSquared(p)<=corridorRadius*corridorRadius)return true;
        return false;
    }
}
