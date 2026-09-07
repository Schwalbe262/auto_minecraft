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

    /** Called only after every registered rack member passes the module's read-only preflight. */
    public static void open(Context c,List<Pos> targets) {
        WineBatchSchedule schedule=ensure(c);
        if (schedule==null || schedule.active() || day(c)<schedule.nextDueDay() || targets.isEmpty())
            throw new IllegalStateException("Wine batch cannot be opened");
        Set<Pos> registered=new HashSet<>(); c.profile().pois(PoiKind.WINE_KEG).forEach(p -> registered.add(p.pos()));
        if (!registered.equals(new HashSet<>(targets))) throw new IllegalArgumentException("Wine batch must include the whole registered rack");
        replace(c,new WineBatchSchedule(schedule.nextDueDay(),true,targets,null));
    }

    /** Only an actual successful native input/state ACK is allowed to remove an unfinished member. */
    public static void confirmFeed(Context c,Pos target) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        if (old==null || !old.active() || !old.remaining().contains(target)) throw new IllegalStateException("Wine refill is outside the active batch");
        long fed=day(c), latest=old.latestFeedDay()==null ? fed : Math.max(fed,old.latestFeedDay());
        List<Pos> remaining=new ArrayList<>(old.remaining()); remaining.remove(target);
        WineBatchSchedule next=new WineBatchSchedule(old.nextDueDay(),true,remaining,latest);
        String key=key(target); Long prior=c.profile().nextEligibleDay.put(key,Math.addExact(fed,c.profile().wineCycleDays));
        try { replace(c,next); }
        catch (RuntimeException e) {
            if (prior==null) c.profile().nextEligibleDay.remove(key); else c.profile().nextEligibleDay.put(key,prior);
            throw e;
        }
    }

    /** Do not publish the next cycle until all refill ACKs and the module's final cleanup have completed. */
    public static void finish(Context c) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        if (old==null || !old.active() || !old.remaining().isEmpty() || old.latestFeedDay()==null)
            throw new IllegalStateException("Unfinished wine batch cannot complete");
        replace(c,new WineBatchSchedule(Math.addExact(old.latestFeedDay(),c.profile().wineCycleDays),false,List.of(),old.latestFeedDay()));
    }

    public static void validate(Profile profile) {
        WineBatchSchedule schedule=profile.wineBatchSchedule;
        if (schedule!=null) new WineBatchSchedule(schedule.nextDueDay(),schedule.active(),schedule.remaining(),schedule.latestFeedDay());
    }
    private static void replace(Context c,WineBatchSchedule next) {
        WineBatchSchedule old=c.profile().wineBatchSchedule;
        c.profile().wineBatchSchedule=next;
        try { c.checkpoint().run(); }
        catch (RuntimeException e) { c.profile().wineBatchSchedule=old; throw e; }
    }
}
