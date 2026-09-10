package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.function.Function;

/** Dispatches independent wine lines while retaining each in-flight owner's complete transaction. */
public final class WineProductionModule implements AutomationModule {
    private record Hold(WorkResult result,long until) { }
    private final Function<String,AutomationModule> factory;
    private final Map<String,AutomationModule> modules=new LinkedHashMap<>();
    private final Map<String,Hold> holds=new LinkedHashMap<>();
    private List<String> sweep=List.of();
    private int next;
    private String active;
    private long closeTicket=-1,lastTick=Long.MIN_VALUE;
    private WorkResult afterClose;
    private String boundaryFailure;
    private AutomationModule cleanup;
    private boolean returningInput;
    public WineProductionModule() { this(MachineModule::new); }
    WineProductionModule(Function<String,AutomationModule> factory) { this.factory=Objects.requireNonNull(factory); }
    @Override public Feature feature() { return Feature.WINE; }
    @Override public int priority() { return 60; }
    @Override public boolean canYieldForNearbyWork(Context c) {
        return closeTicket<0 && cleanup==null && active!=null && modules.get(active).canYieldForNearbyWork(c);
    }
    @Override public boolean sleepSafeDeferred(Context c) {
        return active==null && closeTicket<0 && clean(c,false)
            && holds.values().stream().allMatch(h->h.result().state()==WorkResult.State.COOLDOWN);
    }
    @Override public WorkResult tick(Context c) {
        if(boundaryFailure!=null)return WorkResult.blocked(boundaryFailure);
        if(lastTick>c.world().tick())holds.clear();lastTick=c.world().tick();
        if(closeTicket>=0) {
            ActionOutcome outcome=c.actions().outcome(closeTicket);
            if(!outcome.done())return WorkResult.busy(label(c,active)+" — 재료 보관함 닫기 확인 중");
            closeTicket=-1;
            if(!outcome.success() || c.world().menu()==null || c.world().menu().container()) {
                boundaryFailure="Wine line source did not close; no other line was started";return WorkResult.blocked(boundaryFailure);
            }
            if(!releasable(c,owner(),false)) {
                boundaryFailure="Wine line ownership remains unresolved after closing its source";return WorkResult.blocked(boundaryFailure);
            }
            WorkResult finished=finishOwner(c,afterClose);afterClose=null;
            if(finished!=null)return finished;
        }
        if(active!=null) {
            WorkResult finished=tickOwner(c);
            if(finished!=null)return finished;
        }
        if(sweep.isEmpty()) {
            sweep=WineProductionRules.lines(c.profile()).stream().filter(WineProductionLine::enabled).map(WineProductionLine::id).toList();
            next=0;holds.keySet().retainAll(sweep);
        }
        while(next<sweep.size()) {
            String id=sweep.get(next++);
            WineProductionLine line=WineProductionRules.line(c.profile(),id);
            if(line==null || !line.enabled())continue;
            Hold hold=holds.get(id);
            if(hold!=null && c.world().tick()<hold.until())continue;
            holds.remove(id);active=id;modules.computeIfAbsent(id,factory);
            // Empty/future-only lines are genuinely idle, not synthetic BUSY work.
            // A one-shot may inspect all idle lines in this bounded registered sweep.
            WorkResult finished=tickOwner(c);
            if(finished!=null)return finished;
        }
        sweep=List.of();next=0;
        for(WorkResult.State state:List.of(WorkResult.State.BLOCKED,WorkResult.State.DEFERRED,WorkResult.State.COOLDOWN))
            for(var entry:holds.entrySet()) if(entry.getValue().result().state()==state)
                return annotate(c,entry.getKey(),entry.getValue().result());
        return WorkResult.idle();
    }
    private AutomationModule owner() { return cleanup==null ? modules.get(active) : cleanup; }
    private WorkResult tickOwner(Context c) {
        AutomationModule owner=owner();
        WorkResult result=owner.tick(c);
        if(result.state()==WorkResult.State.BUSY)return annotate(c,active,result);
        if(!releasable(c,owner,false)) {
            if(c.world().menu()!=null && c.world().menu().container() && c.actions().ownsContainer() && releasable(c,owner,true)) {
                afterClose=result;
                closeTicket=c.actions().submit(new Action.CloseContainer(c.world().menu().id()));
                return WorkResult.busy(label(c,active)+" — 다음 생산 라인 전 보관함 닫기");
            }
            return WorkResult.blocked(label(c,active)+" — 기존 조작 확인이 남아 다른 라인을 시작하지 않습니다: "+result.message());
        }
        return finishOwner(c,result);
    }
    private WorkResult finishOwner(Context c,WorkResult result) {
        if(result.state()==WorkResult.State.IDLE && !WineProductionRules.LEGACY_ID.equals(active)) {
            WineProductionLine line=WineProductionRules.line(c.profile(),active);
            if(line==null)return WorkResult.blocked("Completed wine line registration changed before ingredient return");
            if(cleanup==null && carrying(c,line.inputItemId())) {
                cleanup=new CommodityStorageModule(Feature.WINE,line.inputStoreId(),Set.of(line.inputItemId()));returningInput=true;
                return WorkResult.busy(line.name()+" — 남은 원료를 지정 보관함에 반환");
            }
            if((cleanup==null || returningInput) && carrying(c,line.outputItemId())
                && (c.session().oneShotFeature==Feature.WINE || c.profile().enabled(Feature.WINE_STORAGE))) {
                cleanup=new WineLineStorageModule(Feature.WINE,line.id());returningInput=false;
                return WorkResult.busy(line.name()+" — 생산한 와인을 지정 보관함에 보관");
            }
        }
        release(c,result);
        // Continuous production yields once at a line boundary so higher-priority
        // tomato return/wine storage can run before the next recipe takes ownership.
        // One-shot WINE keeps its cursor until every selected line and cleanup finishes.
        return c.session().oneShotFeature==Feature.WINE ? null : WorkResult.idle();
    }
    private static boolean carrying(Context c,String itemId) {
        return c.world().inventory().stream().anyMatch(slot->slot.item().is(itemId));
    }
    private void release(Context c,WorkResult result) {
        AutomationModule owner=modules.get(active);
        if(result.state()==WorkResult.State.BLOCKED || result.state()==WorkResult.State.DEFERRED || result.state()==WorkResult.State.COOLDOWN) {
            long delay=result.state()==WorkResult.State.COOLDOWN ? 100 : 1200;
            holds.put(active,new Hold(result,c.world().tick()+delay));
            owner.reset();
        } else holds.remove(active);
        if(cleanup!=null)cleanup.reset();cleanup=null;returningInput=false;
        c.actions().stopMovement();c.navigation().reset();active=null;
    }
    private static boolean clean(Context c,boolean allowContainerClose) {
        return !c.actions().busy() && c.actions().pauseReason()==null && c.world().player().connected() && c.world().player().onGround()
            && c.world().menu()!=null && (allowContainerClose || !c.world().menu().container()) && c.world().menu().carried().empty()
            && c.profile().loggingHotbarLease==null && !MachineOutputLedger.hasPending(c);
    }
    private static boolean releasable(Context c,AutomationModule owner,boolean allowContainerClose) {
        return clean(c,allowContainerClose) && (!(owner instanceof MachineModule machine) || machine.canSwitchLine(c,allowContainerClose));
    }
    private static String label(Context c,String id) {
        WineProductionLine line=WineProductionRules.line(c.profile(),id);return line==null ? id : line.name();
    }
    private static WorkResult annotate(Context c,String id,WorkResult result) {
        return new WorkResult(result.state(),label(c,id)+" — "+result.message());
    }
    @Override public void reset() {
        modules.values().forEach(AutomationModule::reset);holds.clear();sweep=List.of();next=0;
        if(cleanup!=null)cleanup.reset();cleanup=null;returningInput=false;
        active=null;closeTicket=-1;afterClose=null;boundaryFailure=null;lastTick=Long.MIN_VALUE;
    }
}
