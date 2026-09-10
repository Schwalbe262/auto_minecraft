package dev.schwalbe.autovalley.core;

import java.util.HashMap;
import java.util.Map;

/** Fixed per-field cadence. Maturity is a click condition, never permission to shorten a cycle. */
public final class HarvestSchedule {
    private HarvestSchedule() { }
    public static long nextCycle(Profile profile,Farm farm,long day) {
        int days=CropRules.cycleDays(profile,farm);
        return day>Long.MAX_VALUE-days ? Long.MAX_VALUE : day+days;
    }
    public static void reserveNextCycle(Context context,Farm farm) {
        Profile profile=context.profile();String key=CropRules.farmKey(farm);
        Long previous=profile.nextEligibleDay.get(key);
        long due=nextCycle(profile,farm,Math.floorDiv(context.world().dayTime(),24000L));
        if(previous!=null && previous>=due)return;
        profile.nextEligibleDay.put(key,due);
        // Durable cycle admission/inspection, not a successful-harvest receipt.
        // A normal OFF/restart must not open an early new cycle, even before ACK.
        context.checkpoint().run();
    }
    /** One conservative transition, not an invented historical harvest receipt. */
    public static void transition(Context context) {
        Profile profile=context.profile();
        if(profile.strictHarvestTimingVersion>=1)return;
        long day=Math.floorDiv(context.world().dayTime(),24000L);
        Map<String,Long> before=new HashMap<>(profile.nextEligibleDay);
        int previousVersion=profile.strictHarvestTimingVersion;
        for(Farm farm:profile.farms) {
            CropDefinition crop=CropRules.definition(profile,farm);
            String key=CropRules.farmKey(farm);
            // Only the former built-in ancient-fruit forecast was capable of shortening dates.
            if(!CropRules.ANCIENT_FRUIT.equals(farm.cropId()) || !CropRules.defaults().get(CropRules.ANCIENT_FRUIT).equals(crop)
                    || !profile.nextEligibleDay.containsKey(key))continue;
            long due=nextCycle(profile,farm,day);
            Long existing=profile.nextEligibleDay.get(key);
            profile.nextEligibleDay.put(key,existing==null ? due : Math.max(existing,due));
        }
        profile.strictHarvestTimingVersion=1;
        try {context.checkpoint().run();}
        catch(RuntimeException failure) {
            profile.nextEligibleDay.clear();profile.nextEligibleDay.putAll(before);
            profile.strictHarvestTimingVersion=previousVersion;throw failure;
        }
    }
}
