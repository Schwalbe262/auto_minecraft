package dev.schwalbe.autovalley.core;

import java.util.*;

public final class AutomationEngine {
    public enum State { OFF, RUNNING, WAITING, PAUSED, COMPLETE, ERROR }
    private final List<AutomationModule> modules;
    private final Map<AutomationModule,String> blockedThisSweep=new LinkedHashMap<>();
    private record DeferredRetry(int failures,long at,String message) { }
    private final Map<AutomationModule,DeferredRetry> deferred=new LinkedHashMap<>();
    private AutomationModule resourceWaiting;
    private String resourceWaitMessage;
    private long resourceCheckAt;
    private long lastTick=Long.MIN_VALUE;
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
        AutomationModule logging=null;
        if (c.profile().loggingRunActive) {
            if (mode==RunMode.ONCE && oneShotFeature!=Feature.LOGGING
                || mode==RunMode.CONTINUOUS && !c.profile().enabled(Feature.LOGGING)) {
                status=unfinishedLoggingMessage(); return false;
            }
            logging=modules.stream().filter(module -> module.feature()==Feature.LOGGING).findFirst().orElse(null);
            if (logging==null) { status="미완료 벌목이 있지만 실행 모듈이 없어 재개할 수 없습니다."; return false; }
        }
        String actionRejection=c.actions().startRejection();
        if (actionRejection!=null) { status=actionRejection; return false; }
        try { MachineOutputLedger.reconcile(c); }
        catch (RuntimeException e) { stop(c,State.ERROR,"Could not save machine output verification; no work started"); return false; }
        if (MachineOutputLedger.hasPending(c)) { status=pendingOutputMessage(c); return false; }
        retryAt=0;
        // A parked hotbar item may itself be a tomato, wine, or shipping product.
        // Resume its whole durable logging routine before any ordinary consumer.
        if (mode==RunMode.CONTINUOUS) active=logging;
        state=State.RUNNING;
        status="Starting";
        return true;
    }
    public void stop(Context c, State next, String reason) {
        c.actions().cancel();
        c.navigation().reset();
        for (AutomationModule module : modules) module.reset();
        blockedThisSweep.clear();
        deferred.clear(); clearResourceWait(); lastTick=Long.MIN_VALUE;
        active=null;
        c.session().oneShotFeature=null;
        c.session().activeMachineOutputId=null;
        state=next;
        status=reason;
    }
    public void tick(Context c) {
        if (!running()) return;
        PlayerState player=c.world().player();
        if (!player.connected()) { stop(c,State.PAUSED,"Game disconnected"); return; }
        if (!c.profile().allowBackground && !player.focused()) { stop(c,State.PAUSED,"Game lost focus"); return; }
        if (player.health()<=4 || player.food()<=4) { stop(c,State.PAUSED,"Low health or hunger: take over manually"); return; }
        if (pauseForActions(c)) return;
        if (lastTick>c.world().tick()) { deferred.clear(); resourceCheckAt=0; retryAt=0; }
        lastTick=c.world().tick();
        if (c.world().tick()<retryAt) return;
        try {
            MachineOutputLedger.reconcile(c);
            if (MachineOutputLedger.hasPending(c) && (active==null || !MachineOutputLedger.ownsActive(c,active.feature()))) {
                stop(c,State.PAUSED,pendingOutputMessage(c)); return;
            }
            if (c.profile().loggingRunActive) {
                if (mode==RunMode.ONCE && oneShotFeature!=Feature.LOGGING
                    || mode==RunMode.CONTINUOUS && !c.profile().enabled(Feature.LOGGING)) {
                    stop(c,State.PAUSED,unfinishedLoggingMessage()); return;
                }
                AutomationModule logging=modules.stream().filter(module -> module.feature()==Feature.LOGGING).findFirst().orElse(null);
                if (logging==null || resourceWaiting!=logging && active!=null && active!=logging) {
                    stop(c,State.PAUSED,"미완료 벌목을 먼저 재개해야 합니다. 다른 작업은 진행하지 않았습니다."); return;
                }
                if (resourceWaiting!=logging) active=logging;
            }
            if (!refreshResourceWait(c)) return;
            if (mode==RunMode.ONCE) { tickOnce(c); return; }
            deferred.keySet().removeIf(module -> !c.profile().enabled(module.feature()));
            if (active!=null && !c.profile().enabled(active.feature())) { stop(c,State.PAUSED,"Feature was disabled"); return; }
            if (active!=null) {
                WorkResult result=active.tick(c);
                if (pauseForActions(c)) return;
                if (result.state()==WorkResult.State.BUSY) { state=State.RUNNING; status=result.message(); return; }
                if (result.state()==WorkResult.State.RESOURCE_WAIT && !grantResourceWait(c,active,result)) return;
                if (pauseForUnfinishedLogging(c,active,result)) return;
                if (MachineOutputLedger.hasPending(c)) { stop(c,State.PAUSED,pendingOutputMessage(c)+" — "+result.message()); return; }
                if (result.state()==WorkResult.State.DEFERRED && !defer(c,active,result)) return;
                if (result.state()==WorkResult.State.IDLE) deferred.remove(active);
                c.actions().stopMovement();
                c.navigation().reset();
                if (result.state()==WorkResult.State.BLOCKED) {
                    // Yield the rest of this sweep. Restarting the same multi-tick failure
                    // from the highest priority would starve work capable of resolving it.
                    blockedThisSweep.put(active,result.message());
                }
                active=null;
            }
            // Never preempt another module's BUSY/native transaction. Once it yields,
            // supplied saplings take priority over starting another ordinary consumer.
            if (!refreshResourceWait(c)) return;
            if (active!=null) { state=State.RUNNING; status="미완료 벌목 재식재 다시 확인"; retryAt=0; return; }
            String blocked=blockedThisSweep.values().stream().findFirst().orElseGet(() -> deferred.values().stream().map(DeferredRetry::message).findFirst().orElse(null));
            boolean wineBlocked=blockedThisSweep.keySet().stream().anyMatch(m -> m.feature()==Feature.WINE);
            for (AutomationModule module : modules) {
                if (!c.profile().enabled(module.feature())) continue;
                if (module==resourceWaiting) continue;
                if (blockedThisSweep.containsKey(module)) continue;
                DeferredRetry retry=deferred.get(module);
                if (retry!=null && c.world().tick()<retry.at()) continue;
                if (module.feature()==Feature.SLEEP && blocked!=null) continue;
                if (module.feature()==Feature.PRESERVES && wineBlocked) continue;
                WorkResult result=module.tick(c);
                if (pauseForActions(c)) return;
                if (result.state()==WorkResult.State.BUSY) { active=module; state=State.RUNNING; status=result.message(); return; }
                if (result.state()==WorkResult.State.RESOURCE_WAIT && !grantResourceWait(c,module,result)) return;
                if (pauseForUnfinishedLogging(c,module,result)) return;
                if (MachineOutputLedger.hasPending(c)) { stop(c,State.PAUSED,pendingOutputMessage(c)+" — "+result.message()); return; }
                if (result.state()==WorkResult.State.DEFERRED) {
                    if (!defer(c,module,result)) return;
                    if (blocked==null) blocked=deferred.get(module).message();
                } else if (result.state()==WorkResult.State.IDLE) deferred.remove(module);
                if (result.state()==WorkResult.State.BLOCKED) {
                    blockedThisSweep.put(module,result.message());
                    if (blocked==null) blocked=result.message();
                    if (module.feature()==Feature.WINE) wineBlocked=true;
                }
            }
            c.actions().stopMovement();
            state=State.WAITING;
            status=blockedThisSweep.values().stream().findFirst().orElseGet(() -> deferred.values().stream()
                .map(DeferredRetry::message).findFirst().orElse(resourceWaitMessage!=null ? resourceWaitMessage : "Waiting for crops, machines, or bedtime"));
            blockedThisSweep.clear();
            retryAt=c.world().tick()+20;
        } catch (RuntimeException e) {
            stop(c,State.ERROR,"Stopped after an unexpected error: " + e.getClass().getSimpleName());
            throw e;
        }
    }
    private boolean pauseForActions(Context c) {
        String reason=c.actions().pauseReason();
        if (reason==null) return false;
        stop(c,State.PAUSED,reason);
        return true;
    }
    private void tickOnce(Context c) {
        if (active==null) { stop(c,State.PAUSED,"Selected one-shot job is unavailable"); return; }
        if (active==resourceWaiting) { state=State.WAITING; status=resourceWaitMessage; retryAt=c.world().tick()+20; return; }
        DeferredRetry retry=deferred.get(active);
        if (retry!=null && c.world().tick()<retry.at()) { state=State.WAITING; status=retry.message(); return; }
        WorkResult result=active.tick(c);
        if (pauseForActions(c)) return;
        if (result.state()==WorkResult.State.RESOURCE_WAIT && !grantResourceWait(c,active,result)) return;
        if (result.state()!=WorkResult.State.BUSY && pauseForUnfinishedLogging(c,active,result)) return;
        if (result.state()!=WorkResult.State.BUSY && MachineOutputLedger.hasPending(c)) {
            stop(c,State.PAUSED,pendingOutputMessage(c)+" — "+result.message()); return;
        }
        switch (result.state()) {
            case BUSY -> { state=State.RUNNING; status="One-shot " + oneShotFeature + ": " + result.message(); }
            case IDLE -> stop(c,State.COMPLETE,"One-shot " + oneShotFeature + " complete: no eligible work remains"
                + (result.message()==null || result.message().isBlank() ? "" : " — "+result.message()));
            case BLOCKED -> stop(c,State.PAUSED,"One-shot " + oneShotFeature + " paused: " + result.message());
            case DEFERRED -> { if (defer(c,active,result)) { state=State.WAITING; status=deferred.get(active).message(); } }
            case RESOURCE_WAIT -> { state=State.WAITING; status=resourceWaitMessage; retryAt=c.world().tick()+20; }
        }
    }
    private boolean grantResourceWait(Context c,AutomationModule module,WorkResult result) {
        if (module.feature()!=Feature.LOGGING || !c.profile().loggingRunActive || !resourceBoundary(c)
            || module.resourceReadiness(c)!=AutomationModule.ResourceReadiness.WAITING) {
            stop(c,State.PAUSED,"재식재 재료 보류 조건이 불확실해 미완료 작업을 보존했습니다: "+result.message()); return false;
        }
        resourceWaiting=module; resourceWaitMessage=result.message(); resourceCheckAt=c.world().tick()+1200;
        c.actions().stopMovement(); c.navigation().reset();
        // Keep the module's confirmed PLANT phase, not an action ticket or an outcome.
        return true;
    }
    private boolean refreshResourceWait(Context c) {
        if (resourceWaiting==null || active!=null && active!=resourceWaiting) return true;
        if (!c.profile().loggingRunActive || !resourceBoundary(c)) {
            stop(c,State.PAUSED,"재식재 재료 대기 중 조작 상태가 바뀌었습니다. 미완료 벌목을 확인하세요."); return false;
        }
        AutomationModule.ResourceReadiness readiness=resourceWaiting.resourceReadiness(c);
        if (readiness==AutomationModule.ResourceReadiness.UNSAFE) {
            stop(c,State.PAUSED,"재식재 구역이나 임시 아이템이 바뀌었습니다. 미완료 벌목을 보존했습니다."); return false;
        }
        if (readiness==AutomationModule.ResourceReadiness.READY || c.world().tick()>=resourceCheckAt) {
            active=resourceWaiting; clearResourceWait();
        } else active=mode==RunMode.ONCE ? resourceWaiting : null;
        return true;
    }
    private static boolean resourceBoundary(Context c) {
        return c.world().player().onGround() && !c.actions().busy() && c.world().menu()!=null
            && !c.world().menu().container() && c.world().menu().carried().empty()
            && c.profile().loggingHotbarLease==null && !MachineOutputLedger.hasPending(c);
    }
    private void clearResourceWait() { resourceWaiting=null; resourceWaitMessage=null; resourceCheckAt=0; }
    private boolean defer(Context c,AutomationModule module,WorkResult result) {
        boolean loggingAllowsOtherRetry=resourceWaiting!=null && resourceWaiting!=module
            && resourceWaiting.resourceReadiness(c)!=AutomationModule.ResourceReadiness.UNSAFE;
        if (!c.world().player().onGround() || c.actions().busy() || c.world().menu()==null || c.world().menu().container()
            || !c.world().menu().carried().empty() || c.profile().loggingRunActive && !loggingAllowsOtherRetry
            || c.profile().loggingHotbarLease!=null || MachineOutputLedger.hasPending(c)) {
            stop(c,State.PAUSED,"미완료 조작 또는 독점 작업을 보존하고 중지했습니다: "+result.message()); return false;
        }
        DeferredRetry old=deferred.get(module);
        int failures=old==null ? 1 : Math.min(5,old.failures()+1);
        long delay=1200L*failures;
        String message=module.feature()+" 이동 보류 ("+delay+"틱 후 재확인): "+result.message();
        deferred.put(module,new DeferredRetry(failures,c.world().tick()+delay,message));
        c.actions().stopMovement(); c.navigation().reset(); module.reset();
        return true;
    }
    private static String pendingOutputMessage(Context c) {
        return "미회수 산출물 "+c.profile().pendingMachineOutputs.size()+"건 — Ctrl+F8 → 실행·기록에서 회수 상태를 확인하세요";
    }
    private boolean pauseForUnfinishedLogging(Context c,AutomationModule module,WorkResult result) {
        if (!c.profile().loggingRunActive || module.feature()!=Feature.LOGGING) return false;
        if (result.state()==WorkResult.State.RESOURCE_WAIT && resourceWaiting==module) return false;
        stop(c,State.PAUSED,"미완료 벌목을 보존하고 중지했습니다: "+result.message());
        return true;
    }
    private static String unfinishedLoggingMessage() {
        return "미완료 벌목이 있습니다. 벌목을 다시 실행하거나 활성화해 먼저 마무리하세요.";
    }
}
