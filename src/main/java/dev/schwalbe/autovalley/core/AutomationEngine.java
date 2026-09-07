package dev.schwalbe.autovalley.core;

import java.util.*;

public final class AutomationEngine {
    public enum State { OFF, RUNNING, WAITING, PAUSED, COMPLETE, ERROR }
    private final List<AutomationModule> modules;
    private final Map<AutomationModule,String> blockedThisSweep=new LinkedHashMap<>();
    private AutomationModule active;
    private State state=State.OFF;
    private RunMode mode=RunMode.CONTINUOUS;
    private Feature oneShotFeature;
    private String status="OFF";
    private long retryAt;
    public AutomationEngine(List<AutomationModule> modules) {
        this.modules=modules.stream().sorted(Comparator.comparingInt(AutomationModule::priority)).toList();
    }
    public State state() { return state; }
    public boolean running() { return state==State.RUNNING || state==State.WAITING; }
    public String status() { return status; }
    public RunMode mode() { return mode; }
    /** Last explicitly selected job, retained after completion for the status display. */
    public Feature oneShotFeature() { return oneShotFeature; }
    public void start(Context c) {
        mode=RunMode.CONTINUOUS;
        oneShotFeature=null;
        begin(c);
    }
    /** Does not enable a saved feature or run any of its scheduler neighbours. */
    public void startOnce(Context c,Feature feature) {
        mode=RunMode.ONCE;
        oneShotFeature=feature;
        if (!begin(c)) return;
        AutomationModule selected=modules.stream().filter(module -> module.feature()==feature).findFirst().orElse(null);
        if (selected==null) { stop(c,State.PAUSED,"Selected one-shot job is unavailable"); return; }
        if (hasPendingHaul(c) && (feature==Feature.HARVEST || needsCompletedHaul(feature))) {
            stop(c,State.PAUSED,"Finish storing/discarding the magnet harvest before starting this job");
            return;
        }
        active=selected;
        c.session().oneShotFeature=feature;
        status="Starting one-shot " + feature;
    }
    private boolean begin(Context c) {
        stop(c,State.PAUSED,"Checking current state");
        if (!c.world().player().connected()) { status="Connect to the game first"; return false; }
        if (!c.profile().allowBackground && !c.world().player().focused()) { status="Focus the game first"; return false; }
        if (c.world().menu()==null || !c.world().menu().carried().empty()) { status="Put down the item on the cursor first"; return false; }
        if (c.world().menu().container()) { status="Close the container before starting"; return false; }
        retryAt=0;
        state=State.RUNNING;
        status="Starting";
        return true;
    }
    public void stop(Context c, State next, String reason) {
        c.actions().cancel();
        c.navigation().reset();
        for (AutomationModule module : modules) module.reset();
        blockedThisSweep.clear();
        active=null;
        c.session().oneShotFeature=null;
        state=next;
        status=reason;
    }
    public void tick(Context c) {
        if (!running()) return;
        PlayerState player=c.world().player();
        if (!player.connected()) { stop(c,State.PAUSED,"Game disconnected"); return; }
        if (!c.profile().allowBackground && !player.focused()) { stop(c,State.PAUSED,"Game lost focus"); return; }
        if (player.health()<=4 || player.food()<=4) { stop(c,State.PAUSED,"Low health or hunger: take over manually"); return; }
        if (c.world().tick()<retryAt) return;
        try {
            if (mode==RunMode.ONCE) { tickOnce(c); return; }
            if (active!=null && !c.profile().enabled(active.feature())) { stop(c,State.PAUSED,"Feature was disabled"); return; }
            if (active!=null) {
                WorkResult result=active.tick(c);
                if (result.state()==WorkResult.State.BUSY) { state=State.RUNNING; status=result.message(); return; }
                c.actions().stopMovement();
                c.navigation().reset();
                if (result.state()==WorkResult.State.BLOCKED) {
                    // Yield the rest of this sweep. Restarting the same multi-tick failure
                    // from the highest priority would starve work capable of resolving it.
                    blockedThisSweep.put(active,result.message());
                }
                active=null;
            }
            String blocked=blockedThisSweep.values().stream().findFirst().orElse(null);
            boolean wineBlocked=blockedThisSweep.keySet().stream().anyMatch(m -> m.feature()==Feature.WINE);
            for (AutomationModule module : modules) {
                if (!c.profile().enabled(module.feature())) continue;
                if (hasPendingHaul(c) && needsCompletedHaul(module.feature())) {
                    if (blocked==null) blocked="Finish storing/discarding the magnet harvest before production or sleep";
                    continue;
                }
                if (blockedThisSweep.containsKey(module)) continue;
                if (module.feature()==Feature.SLEEP && blocked!=null) continue;
                if (module.feature()==Feature.PRESERVES && wineBlocked) continue;
                WorkResult result=module.tick(c);
                if (result.state()==WorkResult.State.BUSY) { active=module; state=State.RUNNING; status=result.message(); return; }
                if (result.state()==WorkResult.State.BLOCKED) {
                    blockedThisSweep.put(module,result.message());
                    if (blocked==null) blocked=result.message();
                    if (module.feature()==Feature.WINE) wineBlocked=true;
                }
            }
            c.actions().stopMovement();
            state=State.WAITING;
            status=blocked==null ? "Waiting for crops, machines, or bedtime" : blocked;
            blockedThisSweep.clear();
            retryAt=c.world().tick()+20;
        } catch (RuntimeException e) {
            stop(c,State.ERROR,"Stopped after an unexpected error: " + e.getClass().getSimpleName());
            throw e;
        }
    }
    private void tickOnce(Context c) {
        if (active==null) { stop(c,State.PAUSED,"Selected one-shot job is unavailable"); return; }
        // A harvest that creates an overflow must finish its existing sweep. Only a
        // new harvest is blocked by the start guard; production/sleep never consumes it.
        if (hasPendingHaul(c) && needsCompletedHaul(oneShotFeature)) {
            stop(c,State.PAUSED,"Finish storing/discarding the magnet harvest before continuing this job");
            return;
        }
        WorkResult result=active.tick(c);
        switch (result.state()) {
            case BUSY -> { state=State.RUNNING; status="One-shot " + oneShotFeature + ": " + result.message(); }
            case IDLE -> stop(c,State.COMPLETE,"One-shot " + oneShotFeature + " complete: no eligible work remains");
            case BLOCKED -> stop(c,State.PAUSED,"One-shot " + oneShotFeature + " paused: " + result.message());
        }
    }
    private static boolean hasPendingHaul(Context c) {
        return c.session().magnetHaulPending || !c.session().magnetHaulRemaining.isEmpty();
    }
    private static boolean needsCompletedHaul(Feature feature) {
        return feature==Feature.WINE || feature==Feature.PRESERVES || feature==Feature.SLEEP;
    }
}
