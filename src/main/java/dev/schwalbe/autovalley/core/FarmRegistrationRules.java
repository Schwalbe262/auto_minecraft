package dev.schwalbe.autovalley.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A farm edit and its harvest calendar are one reversible registration change. */
public final class FarmRegistrationRules {
    private FarmRegistrationRules() { }

    /** Apply an already bounds/overlap-validated UI draft; return the exact save-failure rollback. */
    public static Runnable apply(Profile profile,Farm previous,Farm candidate) {
        Objects.requireNonNull(profile); Objects.requireNonNull(profile.farms); Objects.requireNonNull(profile.nextEligibleDay);
        Objects.requireNonNull(candidate); Objects.requireNonNull(candidate.first()); Objects.requireNonNull(candidate.second());
        if (candidate.name()==null || candidate.name().isBlank()) throw new IllegalArgumentException("A farm name is required");
        int index=previous==null ? -1 : profile.farms.indexOf(previous);
        if (previous!=null && index<0) throw new IllegalArgumentException("The edited farm no longer exists");
        for(int i=0;i<profile.farms.size();i++)
            if (i!=index && candidate.name().equals(profile.farms.get(i).name()))
                throw new IllegalArgumentException("The farm name already belongs to another field");
        List<Farm> before=new ArrayList<>(profile.farms);
        Map<String,Long> beforeDays=new LinkedHashMap<>(profile.nextEligibleDay);
        String nextKey=harvestKey(candidate);
        if (previous==null) {
            profile.farms.add(candidate);
            // A removed field's orphaned name must not defer a newly registered field.
            profile.nextEligibleDay.remove(nextKey);
        } else {
            profile.farms.set(index,candidate);
            String oldKey=harvestKey(previous);
            if (!sameWork(previous,candidate)) {
                profile.nextEligibleDay.remove(oldKey);
                profile.nextEligibleDay.remove(nextKey);
            } else if (!oldKey.equals(nextKey)) {
                boolean scheduled=profile.nextEligibleDay.containsKey(oldKey);
                Long due=profile.nextEligibleDay.remove(oldKey);
                profile.nextEligibleDay.remove(nextKey);
                if (scheduled) profile.nextEligibleDay.put(nextKey,due);
            }
        }
        return () -> {
            profile.farms.clear(); profile.farms.addAll(before);
            profile.nextEligibleDay.clear(); profile.nextEligibleDay.putAll(beforeDays);
        };
    }

    private static String harvestKey(Farm farm) { return "harvest:"+farm.name(); }

    /** Reversing A/B without changing covered cells is not a new harvesting region. */
    static boolean sameWork(Farm before,Farm after) {
        return Objects.equals(before.cropId(),after.cropId())
            && sameAxis(before.first().x(),before.second().x(),after.first().x(),after.second().x())
            && sameAxis(before.first().y(),before.second().y(),after.first().y(),after.second().y())
            && sameAxis(before.first().z(),before.second().z(),after.first().z(),after.second().z());
    }
    private static boolean sameAxis(int a,int b,int c,int d) {
        return Math.min(a,b)==Math.min(c,d) && Math.max(a,b)==Math.max(c,d);
    }
}
