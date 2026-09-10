package dev.schwalbe.autovalley.core;

import java.util.*;

/** Persisted scheduling only: these rules neither interact with nor infer completion of a machine. */
public final class WineBatchRules {
    private WineBatchRules() { }
    public static String key(Pos pos) { return "wine:"+Profile.positionKey(pos); }
    public static String key(String lineId,Pos pos) {
        return WineProductionRules.LEGACY_ID.equals(lineId) ? key(pos) : "wine-line:"+lineId+":"+Profile.positionKey(pos);
    }
    public static WineBatchSchedule schedule(Profile profile,String lineId) {
        return WineProductionRules.LEGACY_ID.equals(lineId) ? profile.wineBatchSchedule : profile.wineProductionSchedules.get(lineId);
    }
    private static long day(Context c) { return Math.max(0,Math.floorDiv(c.world().dayTime(),24000)); }

    /** Existing clients' latest registered-machine deadline becomes the rack's first boundary. */
    public static WineBatchSchedule ensure(Context c) {
        return ensure(c,WineProductionRules.LEGACY_ID);
    }
    public static WineBatchSchedule ensure(Context c,String lineId) {
        WineBatchSchedule existing=schedule(c.profile(),lineId);
        if (existing!=null) return existing;
        WineProductionLine line=WineProductionRules.line(c.profile(),lineId);
        List<Poi> registered=WineProductionRules.machines(c.profile(),line);
        if (registered.isEmpty()) return null;
        long due=day(c);
        for (Poi poi:registered) due=Math.max(due,c.profile().nextEligibleDay.getOrDefault(key(lineId,poi.pos()),due));
        replace(c,lineId,new WineBatchSchedule(due,false,List.of(),null));
        return schedule(c.profile(),lineId);
    }

    /** Snapshot the due rack before the module checks and services individual members. */
    public static void open(Context c,List<Pos> targets) {
        open(c,WineProductionRules.LEGACY_ID,targets);
    }
    public static void open(Context c,String lineId,List<Pos> targets) {
        WineBatchSchedule schedule=ensure(c,lineId);
        if (schedule==null || schedule.active() || day(c)<schedule.nextDueDay() || targets.isEmpty())
            throw new IllegalStateException("Wine batch cannot be opened");
        Set<Pos> registered=new HashSet<>(); WineProductionRules.machines(c.profile(),WineProductionRules.line(c.profile(),lineId)).forEach(p -> registered.add(p.pos()));
        if (!registered.equals(new HashSet<>(targets))) throw new IllegalArgumentException("Wine batch must include the whole registered rack");
        replace(c,lineId,new WineBatchSchedule(schedule.nextDueDay(),true,targets,null));
    }

    /** Record an actual successful native input/state ACK, never an inferred or skipped refill. */
    public static void confirmFeed(Context c,Pos target) {
        confirmFeed(c,WineProductionRules.LEGACY_ID,target);
    }
    public static void confirmFeed(Context c,String lineId,Pos target) {
        WineBatchSchedule old=schedule(c.profile(),lineId);
        if (old==null || !old.active() || !old.remaining().contains(target)) throw new IllegalStateException("Wine refill is outside the active batch");
        long fed=day(c), latest=old.latestFeedDay()==null ? fed : Math.max(fed,old.latestFeedDay());
        List<Pos> remaining=new ArrayList<>(old.remaining()); remaining.remove(target);
        WineBatchSchedule next=new WineBatchSchedule(old.nextDueDay(),true,remaining,latest,old.skipped());
        String key=key(lineId,target); Long prior=c.profile().nextEligibleDay.put(key,Math.addExact(fed,cycle(c.profile(),lineId)));
        try { replace(c,lineId,next); }
        catch (RuntimeException e) {
            if (prior==null) c.profile().nextEligibleDay.remove(key); else c.profile().nextEligibleDay.put(key,prior);
            throw e;
        }
    }

    /** Defer one unavailable member to the next rack pass without claiming an input ACK. */
    public static void skip(Context c,Pos target,String reason) {
        skip(c,WineProductionRules.LEGACY_ID,target,reason);
    }
    public static void skip(Context c,String lineId,Pos target,String reason) {
        WineBatchSchedule old=schedule(c.profile(),lineId);
        if (old==null || !old.active() || !old.remaining().contains(target))
            throw new IllegalStateException("Wine skip is outside the active batch");
        List<Pos> remaining=new ArrayList<>(old.remaining()); remaining.remove(target);
        List<WineBatchSchedule.SkippedMember> skipped=new ArrayList<>(old.skipped());
        skipped.add(new WineBatchSchedule.SkippedMember(target,reason,day(c)));
        replace(c,lineId,new WineBatchSchedule(old.nextDueDay(),true,remaining,old.latestFeedDay(),skipped));
    }

    /** Publish the next scheduled boundary only after every member and final cleanup are resolved. */
    public static void finish(Context c) {
        finish(c,WineProductionRules.LEGACY_ID);
    }
    public static void finish(Context c,String lineId) {
        WineBatchSchedule old=schedule(c.profile(),lineId);
        if (old==null || !old.active() || !old.remaining().isEmpty())
            throw new IllegalStateException("Unfinished wine batch cannot complete");
        long cycle=cycle(c.profile(),lineId);
        if (cycle<1) throw new IllegalArgumentException("Wine cycle must be positive");
        long elapsed=Math.max(0,day(c)-old.nextDueDay());
        long cycles=Math.addExact(Math.floorDiv(elapsed,cycle),1);
        long nextDue=Math.addExact(old.nextDueDay(),Math.multiplyExact(cycles,cycle));
        replace(c,lineId,new WineBatchSchedule(nextDue,false,List.of(),old.latestFeedDay(),old.skipped()));
    }

    public static void validate(Profile profile) {
        WineBatchSchedule schedule=profile.wineBatchSchedule;
        if (schedule!=null) new WineBatchSchedule(schedule.nextDueDay(),schedule.active(),schedule.remaining(),schedule.latestFeedDay(),schedule.skipped());
    }
    private static int cycle(Profile profile,String lineId) {
        WineProductionLine line=WineProductionRules.line(profile,lineId);
        if(line==null)throw new IllegalStateException("Wine production line is no longer registered");
        return line.cycleDays();
    }
    private static void replace(Context c,String lineId,WineBatchSchedule next) {
        boolean legacy=WineProductionRules.LEGACY_ID.equals(lineId);
        WineBatchSchedule old=schedule(c.profile(),lineId);
        if(legacy)c.profile().wineBatchSchedule=next;else c.profile().wineProductionSchedules.put(lineId,next);
        try { c.checkpoint().run(); }
        catch (RuntimeException e) {
            if(legacy)c.profile().wineBatchSchedule=old;
            else if(old==null)c.profile().wineProductionSchedules.remove(lineId);else c.profile().wineProductionSchedules.put(lineId,old);
            throw e;
        }
    }
}
