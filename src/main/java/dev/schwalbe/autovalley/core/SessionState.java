package dev.schwalbe.autovalley.core;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Ephemeral observations/permissions: never serialized into the user's profile. */
public final class SessionState {
    public Feature oneShotFeature;
    /** Live evidence does not survive reconnect; persisted output debts do. */
    public final Set<String> liveMachineOutputs=new HashSet<>();
    public String activeMachineOutputId;
    public boolean allows(Profile profile,Feature feature) { return oneShotFeature==null ? profile.enabled(feature) : oneShotFeature==feature; }
    public boolean magnetHaulPending;
    public final Map<String,Integer> magnetHaulRemaining=new HashMap<>();
    /** Operator reconciliation is not recorded as successful delivery or machine-output pickup. */
    public Map<String,Integer> lastManuallyResolvedHaul=Map.of();
    /** Capacity-policy audit only: these quantities are not confirmed deposits or manual resolutions. */
    public Map<String,Integer> lastCapacityReleasedHaul=Map.of();
    public long lastCapacityReleaseTick=-1;
    public String magnetCapacityStatus="not_observed";
    long magnetCapacityObservedTick=Long.MIN_VALUE;
    int magnetFreeCapacityTicks;
    public final Map<Integer,WineSalePermit> wineSalePermits=new HashMap<>();
    /** Local inspection evidence, not permission to transfer; retained after a one-shot finishes. */
    public final Map<Pos,StorageSurveyObservation> storageSurveyObservations=new java.util.LinkedHashMap<>();
    public int storageSurveyTotal;
    public boolean storageSurveyComplete;
    public String storageSurveyStatus="not_started";
    public Pos storageSurveyBlockedAt;
    public void recordFarmRemoval(String id,int amount) {
        if (!magnetHaulPending || amount<=0) return;
        magnetHaulRemaining.computeIfPresent(id,(key,count) -> Math.max(0,count-amount));
        magnetHaulRemaining.values().removeIf(count -> count<=0);
        if (magnetHaulRemaining.isEmpty()) magnetHaulPending=false;
    }
}
