package dev.schwalbe.autovalley.core;

import java.util.HashMap;
import java.util.Map;

/** Ephemeral observations/permissions: never serialized into the user's profile. */
public final class SessionState {
    public Feature oneShotFeature;
    public boolean allows(Profile profile,Feature feature) { return oneShotFeature==null ? profile.enabled(feature) : oneShotFeature==feature; }
    public boolean magnetHaulPending;
    public final Map<String,Integer> magnetHaulRemaining=new HashMap<>();
    public final Map<Integer,WineSalePermit> wineSalePermits=new HashMap<>();
    public void recordFarmRemoval(String id,int amount) {
        if (!magnetHaulPending || amount<=0) return;
        magnetHaulRemaining.computeIfPresent(id,(key,count) -> Math.max(0,count-amount));
        magnetHaulRemaining.values().removeIf(count -> count<=0);
        if (magnetHaulRemaining.isEmpty()) magnetHaulPending=false;
    }
}
