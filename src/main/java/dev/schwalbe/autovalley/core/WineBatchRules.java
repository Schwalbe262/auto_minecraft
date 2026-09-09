package dev.schwalbe.autovalley.core;

import java.util.*;

/** Persisted scheduling only: these rules neither interact with nor infer completion of a machine. */
public final class WineBatchRules {
    private WineBatchRules() { }
    public static String key(Pos pos) { return "wine:"+Profile.positionKey(pos); }
    private static long day(Context c) { return Math.max(0,Math.floorDiv(c.world().dayTime(),24000)); }

    /** Existing clients' latest registered-machine deadline becomes the rack's first boundary. */
    public static WineBatchSchedule ensure(Context c) {
        if (c.profile().wineBatchSchedule!=null) return c.profile().wineBatchSchedule;
        List<Poi> registered=c.profile().pois(PoiKind.WINE_KEG);
        if (registered.isEmpty()) return null;
        long due=day(c);
        for (Poi poi:registered) due=Math.max(due,c.profile().nextEligibleDay.getOrDefault(key(poi.pos()),due));
        replace(c,new WineBatchSchedule(due,false,List.of(),null));
        return c.profile().wineBatchSchedule;
    }

    /** Snapshot the due rack before the module checks and services individual members. */
    public static void open(Context c,List<Pos> targets) {
        WineBatchSchedule schedule=ensure(c);
        if (schedule==null || schedule.active() || day(c)<schedule.nextDueDay() || targets.isEmpty())
            throw new IllegalStateException("Wine batch cannot be opened");
        Set<Pos> registered=new HashSet<>(); c.profile().pois(PoiKind.WINE_KEG).forEach(p -> registered.add(p.pos()));
        if (!registered.equals(new HashSet<>(targets))) throw new IllegalArgumentException("Wine batch must include the whole registered rack");
        replace(c,new WineBatchSchedule(schedule.nextDueDay(),true,targets,null));
    }

    /** Record an actual successful native input/state ACK, never an inferred or skipped refill. */
    public static void confirmFeed(Context c,Pos target) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        if (old==null || !old.active() || !old.remaining().contains(target)) throw new IllegalStateException("Wine refill is outside the active batch");
        long fed=day(c), latest=old.latestFeedDay()==null ? fed : Math.max(fed,old.latestFeedDay());
        List<Pos> remaining=new ArrayList<>(old.remaining()); remaining.remove(target);
        WineBatchSchedule next=new WineBatchSchedule(old.nextDueDay(),true,remaining,latest,old.skipped());
        String key=key(target); Long prior=c.profile().nextEligibleDay.put(key,Math.addExact(fed,c.profile().wineCycleDays));
        try { replace(c,next); }
        catch (RuntimeException e) {
            if (prior==null) c.profile().nextEligibleDay.remove(key); else c.profile().nextEligibleDay.put(key,prior);
            throw e;
        }
    }

    /** Defer one unavailable member to the next rack pass without claiming an input ACK. */
    public static void skip(Context c,Pos target,String reason) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        if (old==null || !old.active() || !old.remaining().contains(target))
            throw new IllegalStateException("Wine skip is outside the active batch");
        List<Pos> remaining=new ArrayList<>(old.remaining()); remaining.remove(target);
        List<WineBatchSchedule.SkippedMember> skipped=new ArrayList<>(old.skipped());
        skipped.add(new WineBatchSchedule.SkippedMember(target,reason,day(c)));
        replace(c,new WineBatchSchedule(old.nextDueDay(),true,remaining,old.latestFeedDay(),skipped));
    }

    /** Publish the next scheduled boundary only after every member and final cleanup are resolved. */
    public static void finish(Context c) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        if (old==null || !old.active() || !old.remaining().isEmpty())
            throw new IllegalStateException("Unfinished wine batch cannot complete");
        long cycle=c.profile().wineCycleDays;
        if (cycle<1) throw new IllegalArgumentException("Wine cycle must be positive");
        long elapsed=Math.max(0,day(c)-old.nextDueDay());
        long cycles=Math.addExact(Math.floorDiv(elapsed,cycle),1);
        long nextDue=Math.addExact(old.nextDueDay(),Math.multiplyExact(cycles,cycle));
        replace(c,new WineBatchSchedule(nextDue,false,List.of(),old.latestFeedDay(),old.skipped()));
    }

    public static void validate(Profile profile) {
        WineBatchSchedule schedule=profile.wineBatchSchedule;
        if (schedule!=null) new WineBatchSchedule(schedule.nextDueDay(),schedule.active(),schedule.remaining(),schedule.latestFeedDay(),schedule.skipped());
    }
    private static void replace(Context c,WineBatchSchedule next) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        c.profile().wineBatchSchedule=next;
        try { c.checkpoint().run(); }
        catch (RuntimeException e) { c.profile().wineBatchSchedule=old; throw e; }
    }
}
