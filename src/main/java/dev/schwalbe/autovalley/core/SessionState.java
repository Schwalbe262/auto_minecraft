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
    /** Explicit, non-persistent shipment permission; never resolves a production ledger entry. */
    public PendingShipmentRecovery pendingShipmentRecovery;
    public boolean allows(Profile profile,Feature feature) { return oneShotFeature==null ? profile.enabled(feature) : oneShotFeature==feature; }
    public final Map<Integer,WineSalePermit> wineSalePermits=new HashMap<>();
    public TomatoSalePermit tomatoSalePermit;
    /** Last confirmed stock survives ordinary OFF/ON, never reconnect or application restart. */
    public final TomatoStockCache tomatoStockCache=new TomatoStockCache();
    /** Local inspection evidence, not permission to transfer; retained after a one-shot finishes. */
    public final Map<Pos,StorageSurveyObservation> storageSurveyObservations=new java.util.LinkedHashMap<>();
    public int storageSurveyTotal;
    public boolean storageSurveyComplete;
    public String storageSurveyStatus="not_started";
    public Pos storageSurveyBlockedAt;
}
