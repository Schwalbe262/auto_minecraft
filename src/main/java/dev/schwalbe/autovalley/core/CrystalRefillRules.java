package dev.schwalbe.autovalley.core;

import java.util.*;

/** A recorded original may refill only its registered empty machine, never replace an occupied recipe. */
public final class CrystalRefillRules {
    private CrystalRefillRules() { }
    private static String key(Pos pos) { return Profile.positionKey(pos); }
    public static void validate(Profile profile) {
        if(profile==null || profile.crystalRefills==null || profile.crystalRefills.size()>4096)
            throw new IllegalArgumentException("Invalid crystal refill records");
        for(var entry:profile.crystalRefills.entrySet()) {
            CrystalRefill refill=entry.getValue();
            if(refill==null || !refill.valid() || !key(refill.pos()).equals(entry.getKey()))
                throw new IllegalArgumentException("Invalid crystal refill record");
        }
        // Removed jobs retain inert records rather than silently losing an unfinished intent.
        // Every consumer still checks exact current job/position ownership below.
    }
    private static boolean registered(Profile profile,ArtisanJob job,Pos pos) {
        return profile!=null && job!=null && profile.artisanJobs!=null && job.equals(profile.artisanJobs.get(job.id()))
            && job.recipe().feature()==Feature.CRYSTAL_COPY && job.machines().contains(pos)
            && ArtisanRules.at(profile,pos)!=null && ArtisanRules.at(profile,pos).feature()==Feature.CRYSTAL_COPY;
    }
    public static ArtisanRecipe pendingRecipe(Profile profile,ArtisanJob job,Pos pos) {
        if(!registered(profile,job,pos) || profile.crystalRefills==null)return null;
        CrystalRefill refill=profile.crystalRefills.get(key(pos));
        return refill!=null && refill.valid() && job.id().equals(refill.jobId()) && pos.equals(refill.pos())
            ? CrystalRecipe.forInput(refill.inputId()) : null;
    }
    public static boolean hasPending(Profile profile,ArtisanJob job) {
        return job!=null && job.machines().stream().anyMatch(pos->pendingRecipe(profile,job,pos)!=null);
    }
    public static boolean pendingInput(Profile profile,ArtisanJob job,ItemData item) {
        return item!=null && !item.empty() && job!=null && job.machines().stream().anyMatch(pos->{
            ArtisanRecipe recipe=pendingRecipe(profile,job,pos);return recipe!=null && item.is(recipe.inputId());
        });
    }
    /** Remember BEFORE harvesting; knowing an input is never permission to send a click. */
    public static void remember(Context c,ArtisanJob job,Pos pos,String inputId) {
        if(!registered(c.profile(),job,pos) || !c.session().allows(c.profile(),Feature.CRYSTAL_COPY))
            throw new IllegalStateException("Crystal refill target is not registered for this run");
        CrystalInspection inspection=c.actions().crystalInspection(pos);
        if(inspection==null || !inspection.matches(c.world().block(pos)) || !inspection.mature()
            || !Objects.equals(inputId,inspection.inputId()))
            throw new IllegalStateException("A fresh server-observed mature original is required before harvesting");
        long day=Math.floorDiv(c.world().dayTime(),24000L);
        CrystalRefill next=new CrystalRefill(job.id(),pos,inputId,day);
        if(!next.valid())throw new IllegalStateException("Invalid observed crystal original");
        Profile profile=c.profile();String key=key(pos);CrystalRefill previous=profile.crystalRefills.get(key);
        if(next.equals(previous))return;
        int oldSchema=profile.schemaVersion;
        profile.crystalRefills.put(key,next);profile.schemaVersion=Math.max(profile.schemaVersion,8);
        try {validate(profile);c.checkpoint().run();}
        catch(RuntimeException failure) {
            if(previous==null)profile.crystalRefills.remove(key);else profile.crystalRefills.put(key,previous);
            profile.schemaVersion=oldSchema;throw failure;
        }
    }
    /** Caller has either confirmed its feed or freshly observed a working machine; no old ACK is changed. */
    public static void complete(Context c,ArtisanJob job,Pos pos,long nextDay) {
        if(!registered(c.profile(),job,pos) || nextDay<0)throw new IllegalArgumentException("Invalid crystal completion target/date");
        update(c,job,pos,nextDay);
    }
    public static void clear(Context c,ArtisanJob job,Pos pos) {
        if(!registered(c.profile(),job,pos))throw new IllegalArgumentException("Invalid crystal continuation target");
        update(c,job,pos,null);
    }
    private static void update(Context c,ArtisanJob job,Pos pos,Long nextDay) {
        Profile profile=c.profile();String key=key(pos),schedule=CrystalCollection.scheduleKey(job,pos);
        CrystalRefill old=profile.crystalRefills.get(key);Long oldDay=profile.nextEligibleDay.get(schedule);
        boolean removing=old!=null && old.jobId().equals(job.id()) && old.pos().equals(pos);
        if(!removing && nextDay==null)return;
        if(removing)profile.crystalRefills.remove(key);
        if(nextDay!=null)profile.nextEligibleDay.put(schedule,nextDay);
        try {c.checkpoint().run();}
        catch(RuntimeException failure) {
            if(removing)profile.crystalRefills.put(key,old);
            if(oldDay==null)profile.nextEligibleDay.remove(schedule);else profile.nextEligibleDay.put(schedule,oldDay);
            throw failure;
        }
    }
    /** Fresh occupied originals always win over the saved continuation, including manual reseeding. */
    public static ArtisanRecipe forAction(Context c,Pos pos,BlockData block) {
        if(c==null || c.actions()==null || c.profile()==null || c.profile().artisanJobs==null)return null;
        CrystalInspection inspection=c.actions().crystalInspection(pos);
        if(inspection==null || !inspection.matches(block) || inspection.working())return null;
        if(inspection.mature())return CrystalRecipe.forInput(inspection.inputId());
        if(!inspection.empty())return null;
        for(ArtisanJob job:c.profile().artisanJobs.values()) {
            ArtisanRecipe pending=pendingRecipe(c.profile(),job,pos);if(pending!=null)return pending;
        }
        return null;
    }
}
