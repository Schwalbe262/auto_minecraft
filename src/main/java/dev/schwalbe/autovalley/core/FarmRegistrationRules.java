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
            // Registration is not permission to skip a retained harvest cooldown.
            // A genuinely new name without a schedule remains eligible for its first pass.
        } else {
            profile.farms.set(index,candidate);
            String oldKey=harvestKey(previous);
            // Bounds and crop edits cannot bring an existing due date forward.
            // A rename carries the stricter of both keys rather than bypassing either calendar.
            if (!oldKey.equals(nextKey)) {
                boolean scheduled=profile.nextEligibleDay.containsKey(oldKey) || profile.nextEligibleDay.containsKey(nextKey);
                Long oldDue=profile.nextEligibleDay.remove(oldKey),nextDue=profile.nextEligibleDay.get(nextKey);
                if (scheduled) profile.nextEligibleDay.put(nextKey,laterDue(oldDue,nextDue));
            }
        }
        return () -> {
            profile.farms.clear(); profile.farms.addAll(before);
            profile.nextEligibleDay.clear(); profile.nextEligibleDay.putAll(beforeDays);
        };
    }

    private static String harvestKey(Farm farm) { return "harvest:"+farm.name(); }

    private static Long laterDue(Long first,Long second) {
        if (first==null) return second;
        if (second==null) return first;
        return Math.max(first,second);
    }
}
