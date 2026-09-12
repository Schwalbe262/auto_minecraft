package dev.schwalbe.autovalley.core;

import java.util.*;

public final class AutomationEngine {
    public enum State { OFF, RUNNING, WAITING, PAUSED, COMPLETE, ERROR }
    private final List<AutomationModule> modules;
    private final EngineFailureHistory failureHistory=new EngineFailureHistory();
    private final Map<AutomationModule,String> blockedThisSweep=new LinkedHashMap<>();
    private record DeferredRetry(int failures,long at,String message,boolean sleepSafe) { }
    private final Map<AutomationModule,DeferredRetry> deferred=new LinkedHashMap<>();
    private record ProductionCooldown(long at,long day,String message,boolean sleepSafe) { }
    private final Map<AutomationModule,ProductionCooldown> productionCooldowns=new LinkedHashMap<>();
    private AutomationModule resourceWaiting;
    private String resourceWaitMessage;
    private long resourceCheckAt;
    private boolean loggingSuspended;
    private long lastTick=Long.MIN_VALUE;
    private AutomationModule active;
    /** Attribution only; never selects or resumes a module. */
    private Feature diagnosticFeature;
    private HotbarWorkspace hotbarRecovery;
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
    public List<EngineFailureHistory.Entry> failureHistory() { return failureHistory.snapshot(); }
    /** Called by connection lifecycle only; ordinary OFF/ON must retain the original failure. */
    public void clearFailureHistory() { failureHistory.clear(); }
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
        // An interrupted work slot is restored before entering a read-only or
        // unrelated one-shot's permissions; no ordinary module runs meanwhile.
        c.session().oneShotFeature=hotbarRecovery==null ? feature : null;
        status="Starting one-shot " + feature;
    }
    private boolean begin(Context c) {
        stop(c,State.PAUSED,"Checking current state");
        if (!c.world().player().connected()) { status="Connect to the game first"; return false; }
        if (!c.profile().allowBackground && !c.world().player().focused()) { status="Focus the game first"; return false; }
        if (c.world().menu()==null || !c.world().menu().carried().empty()) { status="Put down the item on the cursor first"; return false; }
        if (c.world().menu().container()) { status="Close the container before starting"; return false; }
        String actionRejection=c.actions().startRejection();
        if (actionRejection!=null) { status=actionRejection; return false; }
        try { MachineOutputLedger.reconcile(c); }
        catch (RuntimeException e) { stop(c,State.ERROR,"Could not save machine output verification; no work started"); return false; }
        if (MachineOutputLedger.hasPending(c)) { status=pendingOutputMessage(c); return false; }
        if(c.profile().workHotbarLease!=null) {
            HotbarLease lease=c.profile().workHotbarLease;
            if(!lease.valid() || c.profile().loggingHotbarLease!=null) {
                status="임시 단축바 복원 기록을 먼저 확인해야 합니다.";return false;
            }
            hotbarRecovery=new HotbarWorkspace(lease.owner());
            state=State.RUNNING;status="이전 작업의 단축바부터 복원합니다.";return true;
        }
        AutomationModule logging=null;
        if (c.profile().loggingRunActive) {
            if (mode==RunMode.ONCE && oneShotFeature!=Feature.LOGGING
                || mode==RunMode.CONTINUOUS && !c.profile().enabled(Feature.LOGGING)) {
                if (c.profile().enabled(Feature.LOGGING) || !resourceBoundary(c)) {
                    status=unfinishedLoggingMessage(); return false;
                }
                // OFF suspends this feature, not its durable obligations. Native
                // uncertainty, borrowed slots and output debt still prevent a grant.
                loggingSuspended=true;
            } else {
                logging=modules.stream().filter(module -> module.feature()==Feature.LOGGING).findFirst().orElse(null);
                if (logging==null) { status="미완료 벌목이 있지만 실행 모듈이 없어 재개할 수 없습니다."; return false; }
            }
        }
        retryAt=0;
        // A parked hotbar item may itself be a tomato, wine, or shipping product.
        // Resume its whole durable logging routine before any ordinary consumer.
        if (mode==RunMode.CONTINUOUS) active=logging;
        state=State.RUNNING;
        status="Starting";
        return true;
    }
    public void stop(Context c, State next, String reason) {
        failureHistory.recordStop(c.world().tick(),active!=null ? active.feature()
            : diagnosticFeature!=null ? diagnosticFeature : oneShotFeature,next,reason);
        c.actions().cancel();
        c.navigation().reset();
        for (AutomationModule module : modules) module.reset();
        blockedThisSweep.clear();
        deferred.clear(); productionCooldowns.clear(); clearResourceWait(); loggingSuspended=false; lastTick=Long.MIN_VALUE;
        active=null;
        diagnosticFeature=null;
        hotbarRecovery=null;
        c.session().workHotbarOwner=null;
        c.session().oneShotFeature=null;
        c.session().activeMachineOutputId=null;
        state=next;
        status=reason;
    }
    public void tick(Context c) {
        if (!running()) return;
        diagnosticFeature=null;
        PlayerState player=c.world().player();
        if (!player.connected()) { stop(c,State.PAUSED,"Game disconnected"); return; }
        if (!c.profile().allowBackground && !player.focused()) { stop(c,State.PAUSED,"Game lost focus"); return; }
        if (player.health()<=4 || player.food()<=4) { stop(c,State.PAUSED,"Low health or hunger: take over manually"); return; }
        if (pauseForActions(c)) return;
        if (lastTick>c.world().tick()) { deferred.clear(); productionCooldowns.clear(); resourceCheckAt=0; retryAt=0; }
        lastTick=c.world().tick();
        // A day boundary is new production evidence, not another failed attempt.
        // Only normal cooldowns wake here; actual navigation/response backoff is retained.
        if(productionCooldowns.entrySet().removeIf(entry->entry.getValue().day()!=Math.floorDiv(c.world().dayTime(),24000L)))retryAt=0;
        if (c.world().tick()<retryAt) return;
        try {
            if(hotbarRecovery!=null) {
                WorkResult restored=hotbarRecovery.restore(c);
                if(restored==null) {
                    hotbarRecovery=null;
                    // Re-enter the ordinary explicit start checks after custody
                    // is settled, retaining the operator's selected run mode.
                    if(mode==RunMode.ONCE)startOnce(c,oneShotFeature);else start(c);
                } else if(restored.state()==WorkResult.State.BUSY) {
                    state=State.RUNNING;status=restored.message();
                } else stop(c,State.PAUSED,restored.message());
                return;
            }
            if(c.profile().workHotbarLease!=null && (active==null
                || active.feature()!=c.profile().workHotbarLease.owner()
                || c.session().workHotbarOwner!=c.profile().workHotbarLease.owner())) {
                stop(c,State.PAUSED,"임시 단축바 복원 전 다른 작업을 시작하지 않았습니다.");return;
            }
            MachineOutputLedger.reconcile(c);
            if (MachineOutputLedger.hasPending(c) && (active==null || !MachineOutputLedger.ownsActive(c,active.feature()))) {
                stop(c,State.PAUSED,pendingOutputMessage(c)); return;
            }
            if (!refreshLoggingOwnership(c)) return;
            if (!refreshResourceWait(c)) return;
            if (mode==RunMode.ONCE) { tickOnce(c); return; }
            deferred.keySet().removeIf(module -> !c.profile().enabled(module.feature()));
            productionCooldowns.keySet().removeIf(module -> !c.profile().enabled(module.feature()));
            if (active!=null && !c.profile().enabled(active.feature())) { stop(c,State.PAUSED,"Feature was disabled"); return; }
            if (active!=null) {
                diagnosticFeature=active.feature();
                WorkResult result=active.tick(c);
                failureHistory.recordWork(c.world().tick(),active.feature(),result);
                if(pauseForWorkHotbar(c,active,result))return;
                if(result.state()!=WorkResult.State.COOLDOWN)productionCooldowns.remove(active);
                if (pauseForActions(c)) return;
                if (result.state()==WorkResult.State.BUSY) {
                    state=State.RUNNING;status=result.message();return;
                }
                if (result.state()==WorkResult.State.RESOURCE_WAIT && !grantResourceWait(c,active,result)) return;
                if (pauseForUnfinishedLogging(c,active,result)) return;
                if (MachineOutputLedger.hasPending(c)) { stop(c,State.PAUSED,pendingOutputMessage(c)+" — "+result.message()); return; }
                if (result.state()==WorkResult.State.DEFERRED && !defer(c,active,result)) return;
                if (result.state()==WorkResult.State.COOLDOWN && !cooldown(c,active,result)) return;
                if (result.state()==WorkResult.State.IDLE) { deferred.remove(active);productionCooldowns.remove(active); }
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
            if (!refreshLoggingOwnership(c)) return;
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
                ProductionCooldown production=productionCooldowns.get(module);
                if(production!=null && c.world().tick()<production.at())continue;
                if (module.feature()==Feature.SLEEP && (resourceWaiting!=null && !resourceWaiting.sleepSafeResourceWait(c)
                    || !blockedThisSweep.isEmpty()
                    || deferred.values().stream().anyMatch(wait->!wait.sleepSafe())
                    || productionCooldowns.values().stream().anyMatch(wait->!wait.sleepSafe())
                    || (!deferred.isEmpty() || !productionCooldowns.isEmpty()) && (!resourceBoundary(c) || c.actions().pauseReason()!=null))) continue;
                if (module.feature()==Feature.PRESERVES && wineBlocked) continue;
                diagnosticFeature=module.feature();
                WorkResult result=module.tick(c);
                failureHistory.recordWork(c.world().tick(),module.feature(),result);
                if(pauseForWorkHotbar(c,module,result))return;
                if(result.state()!=WorkResult.State.COOLDOWN)productionCooldowns.remove(module);
                if (pauseForActions(c)) return;
                if (result.state()==WorkResult.State.BUSY) { active=module; state=State.RUNNING; status=result.message(); return; }
                if (result.state()==WorkResult.State.RESOURCE_WAIT && !grantResourceWait(c,module,result)) return;
                if (pauseForUnfinishedLogging(c,module,result)) return;
                if (MachineOutputLedger.hasPending(c)) { stop(c,State.PAUSED,pendingOutputMessage(c)+" — "+result.message()); return; }
                if (result.state()==WorkResult.State.DEFERRED) {
                    if (!defer(c,module,result)) return;
                    if (blocked==null) blocked=deferred.get(module).message();
                } else if(result.state()==WorkResult.State.COOLDOWN) {
                    if(!cooldown(c,module,result))return;
                } else if (result.state()==WorkResult.State.IDLE) { deferred.remove(module);productionCooldowns.remove(module); }
                if (result.state()==WorkResult.State.BLOCKED) {
                    blockedThisSweep.put(module,result.message());
                    if (blocked==null) blocked=result.message();
                    if (module.feature()==Feature.WINE) wineBlocked=true;
                }
            }
            c.actions().stopMovement();
            state=State.WAITING;
            status=blockedThisSweep.values().stream().findFirst().orElseGet(() -> deferred.values().stream()
                .map(DeferredRetry::message).findFirst().orElse(resourceWaitMessage!=null ? resourceWaitMessage : normalWaitStatus(c)));
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
        ProductionCooldown production=productionCooldowns.get(active);
        if(production!=null && c.world().tick()<production.at()) {state=State.WAITING;status=cooldownStatus(c,production);return;}
        diagnosticFeature=active.feature();
        WorkResult result=active.tick(c);
        failureHistory.recordWork(c.world().tick(),active.feature(),result);
        if(pauseForWorkHotbar(c,active,result))return;
        if(result.state()!=WorkResult.State.COOLDOWN)productionCooldowns.remove(active);
        if (pauseForActions(c)) return;
        if (result.state()==WorkResult.State.RESOURCE_WAIT && !grantResourceWait(c,active,result)) return;
        if (result.state()!=WorkResult.State.BUSY && pauseForUnfinishedLogging(c,active,result)) return;
        if (result.state()!=WorkResult.State.BUSY && MachineOutputLedger.hasPending(c)) {
            stop(c,State.PAUSED,pendingOutputMessage(c)+" — "+result.message()); return;
        }
        switch (result.state()) {
            case BUSY -> { state=State.RUNNING; status="One-shot " + oneShotFeature + ": " + result.message(); }
            case IDLE -> stop(c,State.COMPLETE,"One-shot " + oneShotFeature
                + (oneShotFeature==Feature.STARFRUIT ? " complete: orchard harvest/storage routine finished; inaccessible fruit may remain" : " complete: no eligible work remains")
                + (result.message()==null || result.message().isBlank() ? "" : " — "+result.message()));
            case BLOCKED -> stop(c,State.PAUSED,"One-shot " + oneShotFeature + " paused: " + result.message());
            case DEFERRED -> { if (defer(c,active,result)) { state=State.WAITING; status=deferred.get(active).message(); } }
            case COOLDOWN -> {if(cooldown(c,active,result)){state=State.WAITING;status=cooldownStatus(c,productionCooldowns.get(active));}}
            case RESOURCE_WAIT -> { state=State.WAITING; status=resourceWaitMessage; retryAt=c.world().tick()+20; }
        }
    }
    private boolean grantResourceWait(Context c,AutomationModule module,WorkResult result) {
        if (module.feature()!=Feature.LOGGING || !c.profile().loggingRunActive || !resourceBoundary(c)
            || module.resourceReadiness(c)!=AutomationModule.ResourceReadiness.WAITING) {
            stop(c,State.PAUSED,"벌목 대기 조건이 불확실해 미완료 작업을 보존했습니다: "+result.message()); return false;
        }
        resourceWaiting=module; resourceWaitMessage=result.message(); resourceCheckAt=c.world().tick()+1200;
        c.actions().stopMovement(); c.navigation().reset();
        // Keep only the module's explicitly revalidated phase, never reset it or manufacture an action outcome.
        return true;
    }
    private boolean refreshLoggingOwnership(Context c) {
        if (!c.profile().loggingRunActive) { loggingSuspended=false; return true; }
        boolean explicitLogging=mode==RunMode.ONCE && oneShotFeature==Feature.LOGGING;
        if (loggingSuspended) {
            if (c.profile().loggingHotbarLease!=null) {
                stop(c,State.PAUSED,"벌목 보류 중 임시 단축바 복원이 필요해 중지했습니다."); return false;
            }
            // A selected one-shot stays isolated even if the saved switch changes.
            if (mode==RunMode.ONCE && !explicitLogging) return true;
            if (!c.profile().enabled(Feature.LOGGING) && !explicitLogging) return true;
            // Let the current module finish its own BUSY/acknowledged work first.
            if (active!=null && active.feature()!=Feature.LOGGING) return true;
            if (!resourceBoundary(c)) {
                stop(c,State.PAUSED,"벌목 재개 전 현재 조작을 안전하게 마무리해야 합니다."); return false;
            }
            loggingSuspended=false;
        }
        if (!c.profile().enabled(Feature.LOGGING) && !explicitLogging) {
            // An existing resource wait already yielded to this ordinary module;
            // postpone the new OFF grant until that module reaches its boundary.
            if (resourceWaiting!=null && active!=null && active!=resourceWaiting) return true;
            if (!resourceBoundary(c)) {
                stop(c,State.PAUSED,"벌목을 껐지만 미확인 조작 또는 임시 아이템 복원이 남아 있습니다."); return false;
            }
            if (active!=null && active.feature()==Feature.LOGGING) {
                c.actions().stopMovement(); c.navigation().reset(); active.reset(); active=null;
            }
            clearResourceWait(); loggingSuspended=true;
            return true;
        }
        if (mode==RunMode.ONCE && !explicitLogging) {
            stop(c,State.PAUSED,unfinishedLoggingMessage()); return false;
        }
        AutomationModule logging=modules.stream().filter(module -> module.feature()==Feature.LOGGING).findFirst().orElse(null);
        if (logging==null || resourceWaiting!=logging && active!=null && active!=logging) {
            stop(c,State.PAUSED,"미완료 벌목을 먼저 재개해야 합니다. 다른 작업은 진행하지 않았습니다."); return false;
        }
        if (resourceWaiting!=logging) active=logging;
        return true;
    }
    private boolean refreshResourceWait(Context c) {
        if (resourceWaiting==null || active!=null && active!=resourceWaiting) return true;
        if (!c.profile().loggingRunActive || !resourceBoundary(c)) {
            stop(c,State.PAUSED,"벌목 대기 중 조작 상태가 바뀌었습니다. 미완료 작업을 확인하세요."); return false;
        }
        AutomationModule.ResourceReadiness readiness=resourceWaiting.resourceReadiness(c);
        if (readiness==AutomationModule.ResourceReadiness.UNSAFE) {
            stop(c,State.PAUSED,"벌목 구역이나 임시 아이템이 바뀌었습니다. 미완료 벌목을 보존했습니다."); return false;
        }
        if (readiness==AutomationModule.ResourceReadiness.READY || c.world().tick()>=resourceCheckAt) {
            active=resourceWaiting; clearResourceWait();
        } else active=mode==RunMode.ONCE ? resourceWaiting : null;
        return true;
    }
    private static boolean resourceBoundary(Context c) {
        return c.world().player().onGround() && !c.actions().busy() && c.world().menu()!=null
            && !c.world().menu().container() && c.world().menu().carried().empty()
            && c.profile().loggingHotbarLease==null && c.profile().workHotbarLease==null && !MachineOutputLedger.hasPending(c);
    }
    private void clearResourceWait() { resourceWaiting=null; resourceWaitMessage=null; resourceCheckAt=0; }
    private void suspendDisabledLoggingAtYield(Context c,AutomationModule module) {
        // OFF may arrive while an ordinary module owns work after a resource
        // wait. Both an actual deferral and a normal production cooldown can
        // reach this same clean boundary. Disabled terrain must not authorize
        // either unrelated wait; durable obligations and native fences remain.
        if (c.profile().loggingRunActive && !c.profile().enabled(Feature.LOGGING)
            && resourceWaiting!=null && resourceWaiting!=module && resourceBoundary(c)
            && c.actions().pauseReason()==null) {
            clearResourceWait(); loggingSuspended=true;
        }
    }
    private boolean defer(Context c,AutomationModule module,WorkResult result) {
        productionCooldowns.remove(module);
        suspendDisabledLoggingAtYield(c,module);
        boolean loggingAllowsOtherRetry=loggingSuspended && module.feature()!=Feature.LOGGING
            || resourceWaiting!=null && resourceWaiting!=module
                && resourceWaiting.resourceReadiness(c)!=AutomationModule.ResourceReadiness.UNSAFE;
        if (!c.world().player().onGround() || c.actions().busy() || c.world().menu()==null || c.world().menu().container()
            || !c.world().menu().carried().empty() || c.profile().loggingRunActive && !loggingAllowsOtherRetry
            || c.profile().loggingHotbarLease!=null || c.profile().workHotbarLease!=null || MachineOutputLedger.hasPending(c)) {
            stop(c,State.PAUSED,"미완료 조작 또는 독점 작업을 보존하고 중지했습니다: "+result.message()); return false;
        }
        DeferredRetry old=deferred.get(module);
        int failures=old==null ? 1 : Math.min(5,old.failures()+1);
        long delay=1200L*failures;
        boolean sleepSafe=module.sleepSafeDeferred(c);
        String message=module.feature()+(sleepSafe ? " 재료·응답 보류 (" : " 이동 보류 (")+delay+"틱 후 재확인): "+result.message();
        deferred.put(module,new DeferredRetry(failures,c.world().tick()+delay,message,sleepSafe));
        c.actions().stopMovement(); c.navigation().reset(); module.resetForRetry();
        return true;
    }
    private boolean cooldown(Context c,AutomationModule module,WorkResult result) {
        // Currently only the explicitly observed whole-rack WINE wait opts in.
        // This is not an escape hatch for sent actions, borrowed inventory or unknown output.
        suspendDisabledLoggingAtYield(c,module);
        boolean loggingAllowsOtherWork=!c.profile().loggingRunActive || loggingSuspended
            || resourceWaiting!=null && resourceWaiting!=module
                && resourceWaiting.resourceReadiness(c)!=AutomationModule.ResourceReadiness.UNSAFE;
        if(module.feature()!=Feature.WINE || !resourceBoundary(c) || !loggingAllowsOtherWork || c.actions().pauseReason()!=null
            || !module.sleepSafeDeferred(c)) {
            stop(c,State.PAUSED,"생산 대기 전 미완료 조작을 확인해야 합니다: "+result.message());return false;
        }
        deferred.remove(module);
        productionCooldowns.put(module,new ProductionCooldown(c.world().tick()+100,Math.floorDiv(c.world().dayTime(),24000L),
            result.message(),module.sleepSafeDeferred(c)));
        c.actions().stopMovement();c.navigation().reset();
        // Keep the clean START observation, not a reset/retry cycle. No action is resent.
        return true;
    }
    private String cooldownStatus(Context c,ProductionCooldown cooldown) {
        long seconds=Math.max(0,(cooldown.at()-c.world().tick()+19)/20);
        return "정상 생산 대기 — "+cooldown.message()+" · "+seconds+"초 후 상태 확인";
    }
    private String normalWaitStatus(Context c) {
        String waiting=productionCooldowns.values().stream().findFirst().map(value->cooldownStatus(c,value))
            .orElse("정상 대기 — 작물·설비의 다음 작업일 확인 중");
        if(c.profile().enabled(Feature.SLEEP) && !c.profile().pois(PoiKind.BED).isEmpty()) {
            long remaining=Math.max(0,Math.max(12584,c.profile().sleepAtTick)-Math.floorMod(c.world().dayTime(),24000L));
            waiting+=remaining==0 ? " · 취침 가능 시간" : " · 취침까지 약 "+((remaining+1199)/1200)+"분(20TPS 기준)";
        }
        return waiting;
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
    private boolean pauseForWorkHotbar(Context c,AutomationModule module,WorkResult result) {
        HotbarLease lease=c.profile().workHotbarLease;
        if(lease==null)return false;
        if(result.state()==WorkResult.State.BUSY && module.feature()==lease.owner()
            && c.session().workHotbarOwner==lease.owner())return false;
        stop(c,State.PAUSED,"임시 단축바 복원을 보존하고 중지했습니다: "+result.message());return true;
    }
    private static String unfinishedLoggingMessage() {
        return "미완료 벌목이 있습니다. 벌목을 다시 실행하거나 활성화해 먼저 마무리하세요.";
    }
}
