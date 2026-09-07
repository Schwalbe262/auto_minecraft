package dev.schwalbe.autovalley.core;

import java.util.*;

public final class AutomationEngine {
    public enum State { OFF, RUNNING, WAITING, PAUSED, ERROR }
    private final List<AutomationModule> modules;
    private AutomationModule active;
    private State state=State.OFF;
    private String status="OFF";
    private long retryAt;
    public AutomationEngine(List<AutomationModule> modules) {
        this.modules=modules.stream().sorted(Comparator.comparingInt(AutomationModule::priority)).toList();
    }
    public State state() { return state; }
    public boolean running() { return state==State.RUNNING || state==State.WAITING; }
    public String status() { return status; }
    public void start(Context c) {
        stop(c,State.PAUSED,"Checking current state");
        if (!c.world().player().connected() || !c.world().player().focused()) { status="Open and focus the game first"; return; }
        if (c.world().menu()==null || !c.world().menu().carried().empty()) { status="Put down the item on the cursor first"; return; }
        if (c.world().menu().container()) { status="Close the container before starting"; return; }
        retryAt=0;
        state=State.RUNNING;
        status="Starting";
    }
    public void stop(Context c, State next, String reason) {
        c.actions().cancel();
        c.navigation().reset();
        for (AutomationModule module : modules) module.reset();
        active=null;
        state=next;
        status=reason;
    }
    public void tick(Context c) {
        if (!running()) return;
        PlayerState player=c.world().player();
        if (!player.connected() || !player.focused()) { stop(c,State.PAUSED,"Game disconnected or lost focus"); return; }
        if (player.health()<=4 || player.food()<=4) { stop(c,State.PAUSED,"Low health or hunger: take over manually"); return; }
        if (c.world().tick()<retryAt) return;
        try {
            if (active!=null && !c.profile().enabled(active.feature())) { stop(c,State.PAUSED,"Feature was disabled"); return; }
            if (active!=null) {
                WorkResult result=active.tick(c);
                if (result.state()==WorkResult.State.BUSY) { state=State.RUNNING; status=result.message(); return; }
                c.actions().stopMovement();
                c.navigation().reset();
                active=null;
                if (result.state()==WorkResult.State.BLOCKED) {
                    state=State.WAITING;
                    status=result.message();
                    // Re-run priorities next tick so e.g. full inventory can be relieved by storage.
                    retryAt=c.world().tick()+20;
                    return;
                }
            }
            String blocked=null;
            boolean wineBlocked=false;
            for (AutomationModule module : modules) {
                if (!c.profile().enabled(module.feature())) continue;
                if (module.feature()==Feature.SLEEP && blocked!=null) continue;
                if (module.feature()==Feature.PRESERVES && wineBlocked) continue;
                WorkResult result=module.tick(c);
                if (result.state()==WorkResult.State.BUSY) { active=module; state=State.RUNNING; status=result.message(); return; }
                if (result.state()==WorkResult.State.BLOCKED) {
                    if (blocked==null) blocked=result.message();
                    if (module.feature()==Feature.WINE) wineBlocked=true;
                }
            }
            c.actions().stopMovement();
            state=State.WAITING;
            status=blocked==null ? "Waiting for crops, machines, or bedtime" : blocked;
            retryAt=c.world().tick()+20;
        } catch (RuntimeException e) {
            stop(c,State.ERROR,"Stopped after an unexpected error: " + e.getClass().getSimpleName());
            throw e;
        }
    }
}
