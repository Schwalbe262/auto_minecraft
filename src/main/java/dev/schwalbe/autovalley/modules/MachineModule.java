package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.ProductionVisitOrder;
import java.util.*;

/** Wine runs before preserves; each run services machines due on the current game day. */
public final class MachineModule implements AutomationModule {
    private enum Stage { START, MACHINE, SOURCE, SNAPSHOT, CHOOSE, FETCH_SOURCE, FETCH, RETURN, EQUIP, VERIFY, PICKUP, OUTPUT }
    private enum Pending { CLOSE, OPEN_SCAN, OPEN_FETCH, WITHDRAW, SWAP, SELECT, USE, INPUT_MERGE, OUTPUT_MERGE }
    private final Feature feature;
    private Stage stage = Stage.START, afterClose;
    private Pending pending;
    private long ticket = -1, verifySince;
    private long useSettleAt = -1;
    private List<Poi> machines = List.of(), sources = List.of();
    private final Map<Poi,int[]> stock = new LinkedHashMap<>();
    private final Map<Pos,BlockData> wineObservations=new LinkedHashMap<>();
    private Set<Pos> wineObservationTargets=Set.of();
    private long wineObservationDay=Long.MIN_VALUE,wineObservationRetry;
    private static final int USE_SETTLE_TICKS = 2;
    private static final int PRESERVES_MORNING_SNAPSHOT_TICK = 240;
    private static final int RESERVED_OUTPUT_SLOTS = 2;
    private boolean stockReady, freshForHaul;
    private long stockDay;
    private int machineIndex, selectedMachineIndex = -1, sourceIndex, containerId = -1, grade = -1, cost, hotbar, inputBefore, withdrawalBefore;
    private Poi source;
    private boolean collected, feeding;
    private boolean partialWineFeedEligible;
    private Pos partialWineFeedTarget;
    private int confirmedPartialWineInput;
    private boolean fullWineFeedEligible, confirmedFullWineInput;
    private Pos fullWineFeedTarget;
    private String unresolvedInteraction;
    private String outputOperationId;
    private Map<Integer,ItemData> inventoryBeforeOutput=Map.of();
    private Integer outputWineYear, preferredOutputSource;
    private String rejectedInputMerge, rejectedOutputMerge, pendingMergeState;
    private String singleStackFallbackState;
    private String repositionedInputState;
    private boolean pendingReposition;
    private boolean yieldTravel;
    private WineBatchSchedule waitingWineRack;
    private final ModuleSupport.ObservationWindow wineWaitObservation=new ModuleSupport.ObservationWindow();
    private int inputMergeAttempts, outputMergeAttempts;

    public MachineModule(Feature feature) {
        if (feature != Feature.WINE && feature != Feature.PRESERVES) throw new IllegalArgumentException("Machine feature must be WINE or PRESERVES");
        this.feature = feature;
    }
    @Override public Feature feature() { return feature; }
    @Override public int priority() { return feature == Feature.WINE ? 60 : 70; }
    @Override public boolean canYieldForNearbyWork(Context c) {
        return yieldTravel && ticket<0 && pending==null && unresolvedInteraction==null && outputOperationId==null;
    }
    /** Grant-time permission only; the engine also applies its normal cleanup/sleep gates. */
    @Override public boolean sleepSafeDeferred(Context c) {
        return waitingWineRack!=null && waitingWineRack==c.profile().wineBatchSchedule
            && waitingWineRack.active() && cleanWineWaitBoundary(c)
            && c.world().inventory().stream().noneMatch(slot -> slot.item().is(ItemData.TOMATO)
                || slot.item().is(ItemData.WINE) || slot.item().is(ItemData.PRESERVES));
    }
    private boolean cleanWineWaitBoundary(Context c) {
        return feature==Feature.WINE && stage==Stage.START && ticket<0 && pending==null
            && unresolvedInteraction==null && outputOperationId==null && !c.actions().busy()
            && c.actions().pauseReason()==null && c.world().player().connected() && c.world().player().onGround()
            && c.world().menu()!=null && !c.world().menu().container() && c.world().menu().carried().empty()
            && c.profile().loggingHotbarLease==null && !MachineOutputLedger.hasPending(c);
    }
    private String blockId() { return feature == Feature.WINE ? "society:wine_keg" : "society:preserves_jar"; }
    private String outputId() { return feature == Feature.WINE ? ItemData.WINE : ItemData.PRESERVES; }
    private Poi target() { return machines.get(machineIndex); }
    private BlockData machine(Context c) { return c.world().block(target().pos()); }

    @Override public WorkResult tick(Context c) {
        yieldTravel=false;
        waitingWineRack=null;
        if (MachineOutputLedger.hasPending(c) && (outputOperationId==null || !MachineOutputLedger.ownsActive(c,feature)))
            return WorkResult.blocked("Resolve the pending machine output before starting another production run");
        if (unresolvedInteraction != null) return WorkResult.blocked(unresolvedInteraction);
        if (ticket >= 0) {
            ActionOutcome result = c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy(progressStatus() + " · 서버 응답 대기");
            ticket = -1;
            boolean skippedOutput=result.state()==ActionOutcome.State.SKIPPED && pending==Pending.OUTPUT_MERGE
                && feature==Feature.WINE && result.proof()==ActionOutcome.Proof.CONSOLIDATION_SKIPPED_UNSENT
                && result.confirmedCount()==0;
            if (!result.success() && !skippedOutput) return fail("Production action failed: " + result.message());
            switch (pending) {
                case CLOSE -> { containerId = -1; stage = afterClose; }
                case OPEN_SCAN, OPEN_FETCH -> {
                    if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("Tomato source menu did not synchronize");
                    containerId = c.world().menu().id(); stage = pending == Pending.OPEN_SCAN ? Stage.SNAPSHOT : Stage.FETCH;
                }
                case WITHDRAW -> {
                    if (!validMenu(c)) return fail("Tomato source changed during withdrawal");
                    if (ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade)) <= withdrawalBefore) return fail("Tomato withdrawal was not acknowledged");
                    String invalid = snapshot(c,source);
                    if (invalid != null) return fail(invalid);
                    stage = Stage.FETCH;
                }
                case SWAP, SELECT -> stage = Stage.EQUIP;
                case USE -> {
                    confirmedPartialWineInput=0;
                    confirmedFullWineInput=false;
                    if(result.proof()==ActionOutcome.Proof.WINE_PARTIAL_FEED) {
                        if(!partialWineFeedEligible || feature!=Feature.WINE || !feeding || collected || cost!=3
                            || !Objects.equals(partialWineFeedTarget,target().pos()) || result.confirmedCount()<1 || result.confirmedCount()>2)
                            return fail("Unexpected partial wine-feed proof; inspect the machine");
                        confirmedPartialWineInput=result.confirmedCount();
                    }
                    if(result.proof()==ActionOutcome.Proof.WINE_FULL_FEED) {
                        if(!fullWineFeedEligible || feature!=Feature.WINE || !feeding || cost!=3
                            || !Objects.equals(fullWineFeedTarget,target().pos()) || result.confirmedCount()!=3)
                            return fail("Unexpected full wine-feed proof; inspect the machine");
                        confirmedFullWineInput=true;
                    }
                    stage = Stage.VERIFY; verifySince = c.world().tick();
                }
                case INPUT_MERGE -> {
                    if (result.confirmedCount()==0 && !pendingReposition) rejectedInputMerge=pendingMergeState;
                    stage=Stage.CHOOSE;
                }
                case OUTPUT_MERGE -> {
                    // A safe native skip proves no in-flight click or borrowed slot,
                    // not a completed merge. Reject the current (possibly updated)
                    // layout so this OUTPUT stage cannot immediately plan it again.
                    if (skippedOutput) rejectedOutputMerge=mergeState(c,outputId(),null);
                    else if (result.confirmedCount()==0) rejectedOutputMerge=pendingMergeState;
                    preferredOutputSource=null; inventoryBeforeOutput=Map.of();
                    stage=Stage.OUTPUT;
                }
            }
            pending = null;
        }
        if (stockReady && stockDay != gameDay(c) && (stage==Stage.FETCH_SOURCE || stage==Stage.FETCH)) {
            if (carriedBatchGrade(c)>=0) {
                // A partial haul already supplied usable material. Stop withdrawing,
                // close its menu, and use that inventory before another day's count.
                stockReady=false; freshForHaul=false;
                if (c.world().menu().container()) close(c,Stage.CHOOSE); else stage=Stage.CHOOSE;
                return WorkResult.busy(progressStatus() + " · 보유 재료부터 사용");
            }
            beginStockScan(c);
            if (c.world().menu().container()) close(c,Stage.SOURCE);
            return WorkResult.busy(progressStatus() + " · 재료 보충 전 날짜 변경 확인");
        }
        switch (stage) {
            case START -> {
                if (feature==Feature.WINE) {
                    WorkResult waiting=prepareWineRun(c);
                    if (waiting!=null) return waiting;
                } else {
                    List<Poi> jars = c.profile().pois(PoiKind.PRESERVES_JAR);
                    // Native artisan updates are spread over ticks 20..219. Taking
                    // a snapshot during that window starts a tiny partial batch and
                    // repeats the whole supply survey for the remaining jars. Allow
                    // one more second for client updates before testing eligibility
                    // (which may move a still-working jar's deadline to tomorrow).
                    // Only new due runs wait; in-flight actions and future-only
                    // schedules retain their normal behavior. BUSY keeps one-shots
                    // alive instead of falsely reporting completion at dawn.
                    if (Math.floorMod(c.world().dayTime(),24000) < PRESERVES_MORNING_SNAPSHOT_TICK
                        && jars.stream().anyMatch(p -> gameDay(c) >= c.profile().nextEligibleDay
                            .getOrDefault(scheduleKey(p),Long.MIN_VALUE)))
                        return WorkResult.busy("절임통 아침 생산 상태 갱신 대기");
                    machines = new ArrayList<>(ModuleSupport.nearest(c,jars.stream().filter(p -> eligible(c,p)).toList()));
                }
                if (machines.isEmpty()) return WorkResult.idle();
                machineIndex = 0; stage = Stage.MACHINE;
            }
            case MACHINE -> {
                if (machineIndex >= machines.size()) {
                    if (feature==Feature.WINE) WineBatchRules.finish(c);
                    clearRun(); return WorkResult.idle();
                }
                if (closeIfNeeded(c,Stage.MACHINE)) return WorkResult.busy(machineStatus("상자 닫는 중"));
                if (selectedMachineIndex!=machineIndex) {
                    // Select only at a new-machine boundary. A source haul, hotbar
                    // change, native ACK or output cleanup keeps this target locked.
                    int next=ProductionVisitOrder.nextIndex(c.world(),c.profile(),machines,machineIndex,4.0);
                    if (next>=0 && next!=machineIndex) Collections.swap(machines,machineIndex,next);
                    selectedMachineIndex=machineIndex;
                }
                Navigation.Result nav = c.navigation().moveTo(target().pos(),4.0,c);
                yieldTravel=nav==Navigation.Result.MOVING;
                if (nav == Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"Registered production machine cannot be reached");
                if (nav != Navigation.Result.ARRIVED) return WorkResult.busy(machineStatus("설비로 이동 중"));
                BlockData block = machine(c);
                if (!block.id().equals(blockId())) return fail("Registered production machine no longer matches its type");
                if (!block.properties().containsKey("working") || !block.properties().containsKey("mature")) return fail("Machine state is not synchronized");
                if (block.flag("working") && !block.flag("mature")) {
                    if (feature==Feature.WINE) return unfinishedWine();
                    if (morningSettled(c)) schedule(c,target(),1);
                    machineIndex++; return WorkResult.busy(machineStatus("생산 중인 설비를 건너뛰고 다음 확인"));
                }
                cost = feature == Feature.WINE || block.flag("upgraded") ? 3 : 5;
                inputMergeAttempts=0;
                stage = Stage.CHOOSE;
            }
            case SOURCE -> {
                if (sourceIndex >= sources.size()) {
                    stockReady = true; freshForHaul = true; stockDay = gameDay(c);
                    stage = Stage.CHOOSE; break;
                }
                source = sources.get(sourceIndex);
                Navigation.Result nav = c.navigation().moveTo(source.pos(),2.5,c);
                yieldTravel=nav==Navigation.Result.MOVING;
                if (nav == Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"Registered tomato source cannot be reached");
                if (nav == Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(source.pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_SCAN);
            }
            case SNAPSHOT -> {
                if (!validMenu(c)) return fail("Tomato source menu changed during stock count");
                String invalid = snapshot(c,source);
                if (invalid != null) return fail(invalid);
                sourceIndex++; close(c,Stage.SOURCE);
            }
            case CHOOSE -> {
                int carriedGrade=carriedBatchGrade(c);
                if (carriedGrade>=0) grade=carriedGrade;
                else {
                    // A supply trip allocates one grade from a fresh whole-stock count.
                    // Carried batches are used first; time or stock balancing cannot
                    // trigger an underground recount while usable ingredients remain.
                    if (!stockReady || !freshForHaul || stockDay!=gameDay(c)) { beginStockScan(c); break; }
                    grade=chooseGrade(totals(c),cost);
                }
                // Bulk-hauling 64-stacks leaves 1/4-tomato fragments for 3/5-input
                // recipes. Merge held fragments before recounting or buying another haul.
                if (grade>=0 && heldCandidate(c)==null && c.world().inventory().stream().filter(s -> ModuleSupport.tomatoGrade(s.item(),grade)).count()>1) {
                    String fingerprint=mergeState(c,ItemData.TOMATO,grade);
                    if (inputMergeAttempts<36 && !fingerprint.equals(rejectedInputMerge)) {
                        var merge=ProductionMergePlanner.planTomatoes(c.world().inventory(),feature,c.profile().hoeHotbarSlot,grade,null);
                        if (merge.isPresent() && (!merge.get().reposition() || !fingerprint.equals(repositionedInputState))) {
                            pendingMergeState=fingerprint; pendingReposition=merge.get().reposition(); inputMergeAttempts++;
                            if (pendingReposition) repositionedInputState=fingerprint;
                            submit(c,new Action.ConsolidateInventory(merge.get()),Pending.INPUT_MERGE); break;
                        }
                    }
                    if (ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade))>=cost) {
                        int fundedGrade=singleStackGrade(c);
                        if (fundedGrade<0) return fail("Combine fragmented tomatoes of the selected grade into a compatible stack before retrying");
                        // Zero progress or no safe merge plan does not invalidate a
                        // real single recipe stack. Permit it without claiming a merge,
                        // or use another carried grade before any new supply trip.
                        grade=fundedGrade;
                        singleStackFallbackState=mergeState(c,ItemData.TOMATO,grade);
                    }
                    // Even1+1 fragments may need merging to make room for another haul.
                    // If fewer than one recipe remain, a normal refill is still necessary.
                }
                if (grade < 0) {
                    // A one-shot must not report an unfunded refill as completed or hide
                    // it behind tomorrow's polling deadline. Leave mature output in place
                    // so supplying ingredients permits a safe retry on this same game day.
                    if (feature==Feature.WINE || c.session().oneShotFeature==feature)
                        return fail("Not enough tomatoes: "+(machines.size()-machineIndex)+" machine(s) remaining; need "
                            +cost+" tomatoes of one grade to refill the machine at "+target().pos()+". Add ingredients and retry.");
                    feeding = false;
                    if (!machine(c).flag("mature")) { schedule(c,target(),1); machineIndex++; stage = Stage.MACHINE; break; }
                    stage = Stage.RETURN;
                } else {
                    feeding = true;
                    if (heldCandidate(c) != null) stage = Stage.RETURN;
                    else {
                        source = sourceForGrade(c,null);
                        if (source == null) return fail("Combine fragmented tomatoes of the selected grade into one stack");
                        stage = Stage.FETCH_SOURCE;
                    }
                }
            }
            case FETCH_SOURCE -> {
                Navigation.Result nav = c.navigation().moveTo(source.pos(),2.5,c);
                yieldTravel=nav==Navigation.Result.MOVING;
                if (nav == Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"Selected tomato source cannot be reached");
                if (nav == Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(source.pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_FETCH);
            }
            case FETCH -> {
                if (!validMenu(c)) return fail("Tomato source menu changed before withdrawal");
                String invalid = snapshot(c,source);
                if (invalid != null) return fail(invalid);
                // Count this source again after opening it; never withdraw from an old chest snapshot.
                boolean funded=heldCandidate(c)!=null;
                int held=ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade));
                int needed=remainingIngredientDemand(c)-held;
                if (funded && needed<=0) { close(c,Stage.RETURN); break; }
                // A source stack can have tags absent from our ItemData projection. Never
                // count apparent partial-stack room as one of the two real output slots.
                if (emptyInventorySlots(c)<=RESERVED_OUTPUT_SLOTS) {
                    if (funded) { close(c,Stage.RETURN); break; }
                    return fail("Keep two empty output slots before withdrawing production tomatoes");
                }
                List<ItemSlot> available=c.world().menu().slots().stream().filter(s -> !s.player())
                    .filter(s -> ModuleSupport.tomatoGrade(s.item(),grade) && s.item().count()<=64).toList();
                // Full-stack QuickMove is cursor-free. Prefer a stack within the demand;
                // the final indivisible stack may exceed it by at most 63 tomatoes.
                ItemSlot under=available.stream().filter(s -> s.item().count()<=needed)
                    .max(Comparator.comparingInt(s -> s.item().count())).orElse(null);
                ItemSlot over=available.stream().filter(s -> s.item().count()>needed)
                    .min(Comparator.comparingInt(s -> s.item().count())).orElse(null);
                int underTotal=available.stream().filter(s -> s.item().count()<=needed).mapToInt(s -> s.item().count()).sum();
                // Do not add a small remainder and then still need a full stack: e.g.
                // needing39 with16+64 available needs only64, not16 followed by64.
                ItemSlot slot=over!=null && underTotal<needed ? over : under;
                if (slot==null) {
                    if (stock.get(source)[grade]>0) return fail("Tomato source contains an unsupported stack size");
                    Poi next=sourceForGrade(c,source);
                    if (next!=null) { source=next; close(c,Stage.FETCH_SOURCE); }
                    else close(c,funded ? Stage.RETURN : Stage.CHOOSE);
                    break;
                }
                withdrawalBefore=held;
                submit(c,new Action.QuickMove(containerId,slot.index()),Pending.WITHDRAW);
            }
            case RETURN -> {
                Navigation.Result nav = c.navigation().moveTo(target().pos(),4.0,c);
                yieldTravel=nav==Navigation.Result.MOVING;
                if (nav == Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"Production machine cannot be reached with ingredients");
                if (nav == Navigation.Result.ARRIVED) {
                    BlockData block = machine(c);
                    if (!block.id().equals(blockId())) return fail("Production machine changed");
                    if (block.flag("working") && !block.flag("mature")) {
                        if (feature==Feature.WINE) return unfinishedWine();
                        machineIndex++; stage = Stage.MACHINE; break;
                    }
                    if (!feeding && !block.flag("mature")) { machineIndex++; stage = Stage.MACHINE; break; }
                    c.actions().stopMovement();
                    useSettleAt = c.world().tick();
                    hotbar = (c.profile().hoeHotbarSlot + 1) % 9; stage = Stage.EQUIP;
                }
            }
            case EQUIP -> {
                c.actions().stopMovement();
                if (c.world().menu().container() || !c.world().menu().carried().empty()) return fail("Close inventory screens and clear the cursor before production");
                ItemSlot selected = c.world().inventory().stream().filter(s -> s.inventoryIndex() == hotbar).findFirst().orElse(null);
                ItemSlot prepared = feeding ? heldCandidate(c) : null;
                boolean correct = feeding ? selected != null && ModuleSupport.tomatoGrade(selected.item(),grade) && selected.item().count() >= cost
                    && (selected.item().count()>cost || prepared!=null && (prepared.inventoryIndex()==hotbar || prepared.item().count()<=cost))
                    : selected == null || selected.item().empty();
                if (!correct) {
                    // Replace the last recipe in the hand before a new output can occupy
                    // that slot. Never SWAP the hand with itself or repeatedly shuffle it.
                    ItemSlot ingredient = feeding ? prepared : emptySlot(c);
                    if (feeding && ingredient==null) { stage=Stage.CHOOSE; break; }
                    if (ingredient == null) return fail(feeding ? "Selected-grade tomato stack is no longer available" : "A free inventory slot is needed for empty-hand collection");
                    submit(c,new Action.SwapHotbar(ingredient.inventoryIndex(),hotbar),Pending.SWAP); break;
                }
                if (c.world().player().selectedSlot() != hotbar) { submit(c,new Action.SelectHotbar(hotbar),Pending.SELECT); break; }
                BlockData block = machine(c);
                if (!block.id().equals(blockId())) return fail("Production machine changed before interaction");
                if (block.flag("working") && !block.flag("mature")) {
                    if (feature==Feature.WINE) return unfinishedWine();
                    machineIndex++; stage = Stage.MACHINE; break;
                }
                collected = block.flag("mature");
                if (!feeding && !collected) { machineIndex++; stage = Stage.MACHINE; break; }
                // Preserves still require durable pickup evidence. Wine pickup tracking,
                // including speculative capacity/ground-product gates, is user-disabled.
                if (collected && feature==Feature.PRESERVES && !ModuleSupport.hasEmptyInventorySlot(c)) return fail("Free an inventory slot to collect the completed product");
                // An older dropped preserves product must not impersonate its new output.
                if (collected && feature==Feature.PRESERVES && c.world().groundItems().stream().anyMatch(g -> g.item().is(outputId())))
                    return fail("Pick up previously dropped "+outputId()+" before collecting another completed machine");
                // Arrival is not a lease on reach: inventory selection takes multiple
                // ticks and residual movement can leave the native hit ray out of range.
                // Count the two real stopped ticks from arrival, including time already
                // spent awaiting equipment ACKs; selecting a slot does not restart motion.
                // Recheck the actual ray immediately before a write-ahead obligation or use.
                if (useSettleAt < 0) useSettleAt = c.world().tick();
                if (c.world().tick() - useSettleAt < USE_SETTLE_TICKS)
                    return WorkResult.busy(machineStatus("상호작용 전 정지 안정화"));
                if (!c.world().loaded(target().pos()) || !c.world().canInteract(target().pos(),4.0)) {
                    useSettleAt = -1; c.navigation().reset(); stage = Stage.RETURN;
                    return WorkResult.busy(machineStatus("상호작용 위치 재접근"));
                }
                inputBefore = ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade));
                partialWineFeedEligible=feature==Feature.WINE && feeding && !collected && cost==3
                    && "false".equals(block.properties().get("working")) && "false".equals(block.properties().get("mature"));
                partialWineFeedTarget=partialWineFeedEligible ? target().pos() : null;
                confirmedPartialWineInput=0;
                fullWineFeedEligible=feature==Feature.WINE && feeding && cost==3
                    && (partialWineFeedEligible || "true".equals(block.properties().get("mature"))
                        && ("false".equals(block.properties().get("working")) || "true".equals(block.properties().get("working"))));
                fullWineFeedTarget=fullWineFeedEligible ? target().pos() : null;
                confirmedFullWineInput=false;
                // submit() can dispatch immediately: preserves obligations reach disk first.
                if (collected) {
                    Map<Integer,ItemData> before=new HashMap<>();
                    for (ItemSlot slot:c.world().inventory()) before.put(slot.inventoryIndex(),slot.item());
                    inventoryBeforeOutput=Map.copyOf(before);
                    if (feature==Feature.PRESERVES) {
                        PendingMachineOutput output=MachineOutputLedger.prepare(c,feature,target().pos());
                        outputOperationId=output.id(); outputWineYear=output.expectedWineYear();
                    } else outputWineYear=c.world().wineYear(); // Optional merge preference only.
                }
                submit(c,new Action.UseBlock(target().pos(),Action.Use.MACHINE),Pending.USE);
            }
            case VERIFY -> {
                BlockData block = machine(c);
                int consumed = inputBefore - ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade));
                boolean exactPartial=feature==Feature.WINE && feeding && !collected && partialWineFeedEligible && cost==3
                    && Objects.equals(partialWineFeedTarget,target().pos()) && confirmedPartialWineInput>=1 && confirmedPartialWineInput<=2;
                boolean exactFull=feature==Feature.WINE && feeding && fullWineFeedEligible && cost==3
                    && Objects.equals(fullWineFeedTarget,target().pos()) && confirmedFullWineInput;
                boolean inputConfirmed = !feeding || consumed == cost || exactPartial || exactFull;
                boolean stateConfirmed = blockId().equals(block.id()) && target().pos().equals(block.pos())
                    && !block.flag("mature") && (!feeding || block.flag("working"));
                if(exactPartial || exactFull)stateConfirmed &= "false".equals(block.properties().get("mature"))
                    && "true".equals(block.properties().get("working"));
                if (inputConfirmed && stateConfirmed) {
                    if (feeding) { freshForHaul = false; inputMergeAttempts=0; }
                    if (feature==Feature.WINE) WineBatchRules.confirmFeed(c,target().pos());
                    else schedule(c,target(),feeding ? c.profile().preservesCycleDays : 1);
                    if (collected && feature==Feature.PRESERVES) {
                        MachineOutputLedger.confirmMachine(c,outputOperationId);
                        stage = Stage.PICKUP;
                    } else if (collected) {
                        preferredOutputSource=outputWineYear==null ? null : newlyReceivedOutput(c);
                        outputMergeAttempts=0; stage=Stage.OUTPUT;
                    }
                    else { machineIndex++; stage = Stage.MACHINE; }
                } else if (c.world().tick() - verifySince > c.profile().interactionTimeoutTicks) return fail("Production input or machine state did not synchronize; inspect the machine");
            }
            case PICKUP -> {
                MachineOutputLedger.reconcile(c);
                if (!MachineOutputLedger.isPending(c,outputOperationId)) {
                    preferredOutputSource=newlyReceivedOutput(c);
                    outputOperationId=null; outputMergeAttempts=0; stage=Stage.OUTPUT; break;
                }
                if (c.world().tick() - verifySince > c.profile().interactionTimeoutTicks) return fail("Completed product was not picked up; inspect the machine output side");
                // Machine/input acknowledgement has already completed. A currently observed,
                // standable pickup position is enough to approach; a fixed delay adds no proof.
                Pos observed = observedPickup(c);
                if (observed == null) {
                    c.actions().stopMovement();
                    return WorkResult.busy(machineStatus("완성된 병조림 회수 대기"));
                }
                // Elevated outputs may still be falling or temporarily unreachable. A failed
                // path is not a failed interaction: wait for another observation, never click again.
                Navigation.Result nav = c.navigation().moveTo(observed,0.9,c);
                if (nav == Navigation.Result.BLOCKED) {
                    c.actions().stopMovement();
                    return WorkResult.busy(machineStatus("병조림을 회수할 수 있는 위치 확인"));
                }
            }
            case OUTPUT -> {
                // Preserves pickup was checkpointed; wine intentionally has no pickup gate.
                // Inventory-only merging is not a warehouse deposit or a sale.
                // Fresh wine need not be rearranged after every bottle. Keep the same
                // two-real-slot reserve used for supply hauling; apparent partial-stack
                // capacity is not native tag compatibility or guaranteed pickup room.
                if (feature!=Feature.WINE || emptyInventorySlots(c)<=RESERVED_OUTPUT_SLOTS) {
                    String fingerprint=mergeState(c,outputId(),null);
                    if (outputMergeAttempts<36 && !fingerprint.equals(rejectedOutputMerge)) {
                        var merge=ProductionMergePlanner.plan(c.world().inventory(),feature,c.profile().hoeHotbarSlot,preferredOutputSource);
                        if (merge.isPresent()) {
                            pendingMergeState=fingerprint; outputMergeAttempts++;
                            submit(c,new Action.ConsolidateInventory(merge.get(),feature==Feature.WINE),Pending.OUTPUT_MERGE); break;
                        }
                    }
                }
                preferredOutputSource=null; inventoryBeforeOutput=Map.of();
                machineIndex++; stage=Stage.MACHINE;
            }
        }
        return WorkResult.busy(progressStatus());
    }

    /** Presentation only: reporting progress must never open, cache, reset, or skip a source. */
    private String progressStatus() {
        if (pending==Pending.OPEN_SCAN || pending==Pending.CLOSE && afterClose==Stage.SOURCE
                || stage==Stage.SOURCE || stage==Stage.SNAPSHOT)
            return "토마토 재고 확인 " + Math.min(sourceIndex,sources.size()) + "/" + sources.size() + " — 가장 많은 등급 선택";
        if (pending==Pending.OPEN_FETCH || pending==Pending.WITHDRAW || stage==Stage.FETCH_SOURCE || stage==Stage.FETCH)
            return "재료 가져오는 중 — " + (grade<0 ? "토마토" : "등급 " + grade + " 토마토") + " · " + machineStatus("보충 준비");
        if (pending==Pending.INPUT_MERGE) return machineStatus("선택한 등급의 재료 합치는 중");
        if (pending==Pending.OUTPUT_MERGE) return machineStatus("인벤토리 산출물 정리 중");
        return machineStatus(switch (stage) {
            case START -> "처리할 설비 확인";
            case MACHINE -> "이동·생산 상태 확인";
            case CHOOSE -> "가장 많은 토마토 등급 선택";
            case RETURN -> "재료를 들고 설비로 돌아가는 중";
            case EQUIP -> "사용할 재료 준비";
            case VERIFY -> "재료 소비·생산 상태 확인";
            case PICKUP -> "완성된 병조림 회수 확인";
            case OUTPUT -> "인벤토리 산출물 정리 중";
            case SOURCE, SNAPSHOT, FETCH_SOURCE, FETCH -> "재료 확인";
        });
    }
    private String machineStatus(String phase) {
        int current=machines.isEmpty() ? 0 : Math.min(machineIndex+1,machines.size());
        return (feature==Feature.WINE ? "와인통 " : "절임통 ") + current + "/" + machines.size() + " — " + phase;
    }

    /** Whole-rack preflight is read-only; a few early mature kegs cannot open a new cycle. */
    private WorkResult prepareWineRun(Context c) {
        WineBatchSchedule schedule=WineBatchRules.ensure(c);
        if (schedule==null) return WorkResult.idle();
        if (!schedule.active() && gameDay(c)<schedule.nextDueDay())
            return new WorkResult(WorkResult.State.IDLE,"와인 랙 공통 생산일 "+schedule.nextDueDay()+"일 대기 — 개별 조기 완료 통은 방문하지 않습니다");
        if (schedule.active() && schedule.remaining().isEmpty()) {
            WineBatchRules.finish(c); return WorkResult.idle();
        }
        Map<Pos,Poi> registered=new LinkedHashMap<>();
        for (Poi poi:c.profile().pois(PoiKind.WINE_KEG)) registered.put(poi.pos(),poi);
        List<Pos> targets=schedule.active() ? schedule.remaining() : new ArrayList<>(registered.keySet());
        if (targets.isEmpty()) return fail("Registered wine rack is empty; review the common production schedule");
        Set<Pos> targetSet=Set.copyOf(targets);
        if (wineObservationDay!=gameDay(c) || !targetSet.equals(wineObservationTargets)) {
            wineObservations.clear(); wineObservationTargets=targetSet; wineObservationDay=gameDay(c); wineObservationRetry=0;
            wineWaitObservation.clear();
        }
        if (c.world().tick()<wineObservationRetry && targets.stream().anyMatch(p -> !c.world().loaded(p)))
            return WorkResult.idle();
        boolean processing=false;
        for (Pos pos:targets) {
            if (!registered.containsKey(pos)) return fail("An unfinished wine batch member is no longer registered; restore or review the rack registration");
            if (c.world().loaded(pos)) wineObservations.put(pos,c.world().block(pos));
            BlockData block=wineObservations.get(pos);
            if (block==null) return ModuleSupport.observe(c,pos,8,"와인 전체 랙 생산 상태 확인");
            if (!block.id().equals(blockId())) return fail("Registered production machine no longer matches its type");
            if (!block.properties().containsKey("working") || !block.properties().containsKey("mature")) return fail("Wine rack state is not synchronized");
            processing|=block.flag("working") && !block.flag("mature");
        }
        if (processing) {
            if (schedule.active()) {
                // A restart or another actor can leave an unconfirmed member
                // working. Do not invent its feed ACK, delete it from remaining,
                // or visit a ready subset. Wait for ALL remaining members to be
                // ready again, without blocking unrelated work or clean sleep.
                // Cached observations can support approach planning, but cannot
                // grant a clean wait after a remaining member unloads.
                Pos unloaded=targets.stream().filter(pos -> !c.world().loaded(pos)).findFirst().orElse(null);
                if (unloaded!=null) return wineWaitObservation.observe(c,unloaded,8,"미완료 와인 랙 현재 상태 확인");
                if (!cleanWineWaitBoundary(c)) return unfinishedWine();
                waitingWineRack=schedule;
                return WorkResult.deferred("미완료 와인 랙 생산 대기 — 남은 통 전체가 준비되면 다시 확인합니다. 재투입·완료 처리는 하지 않았습니다");
            }
            wineObservations.clear(); wineObservationRetry=c.world().tick()+1200;
            return new WorkResult(WorkResult.State.IDLE,"와인 랙 전체 완료 대기 — 아직 생산 중인 통이 있어 공통 작업을 시작하지 않습니다");
        }
        if (!schedule.active()) WineBatchRules.open(c,targets);
        machines=new ArrayList<>(ModuleSupport.nearest(c,targets.stream().map(registered::get).toList()));
        return null;
    }
    private WorkResult unfinishedWine() {
        return fail("Unfinished wine batch contains a working machine without its native refill confirmation; inspect it before resuming");
    }

    /** Largest total stock wins, with lower grade winning ties. Counts include synchronized chests and player inventory. */
    static int chooseGrade(int[] counts, int required) {
        int best = -1;
        for (int i = 0; i < 4; i++) if (counts[i] >= required && (best < 0 || counts[i] > counts[best])) best = i;
        return best;
    }
    private int[] totals(Context c) {
        int[] counts = heldCounts(c);
        for (int[] chest : stock.values()) for (int i = 0; i < 4; i++) counts[i] += chest[i];
        return counts;
    }
    private int[] heldCounts(Context c) {
        int[] counts=new int[4];
        for (ItemSlot slot:c.world().inventory()) if (slot.inventoryIndex()>=0 && slot.inventoryIndex()<36
                && slot.item().is(ItemData.TOMATO) && slot.item().quality()>=0 && slot.item().quality()<4)
            counts[slot.item().quality()]+=slot.item().count();
        return counts;
    }
    private int carriedBatchGrade(Context c) {
        int[] held=heldCounts(c);
        if (grade>=0 && held[grade]>=cost) {
            // Prefer another substantial carried batch before emptying the last hand
            // recipe, but exact-cost remnants must still be usable without a scan loop.
            if (held[grade]==cost && remainingIngredientDemand(c)>cost) {
                int replacement=chooseGrade(held,cost+1);
                if (replacement>=0) return replacement;
            }
            return grade;
        }
        return chooseGrade(held,cost);
    }
    private int remainingIngredientDemand(Context c) {
        long demand=0, day=gameDay(c);
        for (int n=machineIndex;n<machines.size();n++) {
            Poi poi=machines.get(n);
            if (feature!=Feature.WINE && day<c.profile().nextEligibleDay.getOrDefault(scheduleKey(poi),Long.MIN_VALUE)) continue;
            if (!c.world().loaded(poi.pos())) { demand+=feature==Feature.WINE ? 3 : 5; continue; }
            BlockData block=c.world().block(poi.pos());
            if (!block.id().equals(blockId()) || block.flag("working") && !block.flag("mature")) continue;
            demand+=feature==Feature.WINE || block.flag("upgraded") ? 3 : 5;
        }
        return (int)Math.min(Integer.MAX_VALUE,Math.max(cost,demand));
    }
    private static int emptyInventorySlots(Context c) {
        return (int)c.world().inventory().stream().filter(s -> s.inventoryIndex()>=0 && s.inventoryIndex()<36 && s.item().empty()).count();
    }
    private static String mergeState(Context c,String id,Integer grade) {
        // Ignore slot positions: the adapter's temporary SWAPs cannot manufacture a new
        // retry candidate when native tags prevent the same visible stacks from merging.
        return c.world().inventory().stream().map(ItemSlot::item).filter(i -> i.is(id) && (grade==null || i.quality()==grade))
            .map(i -> i.id()+":"+i.quality()+":"+i.year()+":"+i.count()).sorted().reduce("",(a,b) -> a+"|"+b);
    }
    private Integer newlyReceivedOutput(Context c) {
        return c.world().inventory().stream().filter(s -> s.inventoryIndex()>=0 && s.inventoryIndex()<36)
            .filter(s -> s.item().is(outputId()) && (feature!=Feature.WINE || Objects.equals(s.item().year(),outputWineYear)))
            .filter(s -> {
                ItemData before=inventoryBeforeOutput.getOrDefault(s.inventoryIndex(),ItemData.EMPTY);
                return !ModuleSupport.same(before,s.item()) || s.item().count()>before.count();
            })
            .min(Comparator.comparingInt((ItemSlot s) -> s.item().count()).thenComparingInt(ItemSlot::inventoryIndex))
            .map(ItemSlot::inventoryIndex).orElse(null);
    }
    private void beginStockScan(Context c) {
        sources = StorageVisitOrder.order(c.profile().pois(PoiKind.TOMATO_CHEST),c.world().player());
        stock.clear(); stockReady = false; freshForHaul = false; sourceIndex = 0; grade=-1; stage = Stage.SOURCE;
    }
    private Poi sourceForGrade(Context c,Poi excluded) {
        // Storage is tomato-only, not grade-assigned. Source order comes from the
        // grouped synchronized containers; historical classifiers/layouts are irrelevant.
        return stock.entrySet().stream().filter(e -> !e.getKey().equals(excluded) && e.getValue()[grade]>0)
            .map(Map.Entry::getKey).findFirst().orElse(null);
    }
    private static long gameDay(Context c) { return Math.floorDiv(c.world().dayTime(),24000); }
    private boolean eligible(Context c,Poi poi) {
        long day = Math.floorDiv(c.world().dayTime(),24000);
        if (day < c.profile().nextEligibleDay.getOrDefault(scheduleKey(poi),Long.MIN_VALUE)) return false;
        if (c.world().loaded(poi.pos())) {
            BlockData block = c.world().block(poi.pos());
            if (!block.id().equals(blockId())) return true; // Report a replaced registration instead of hiding it.
            if (block.flag("mature")) return true;
            if (block.flag("working")) {
                // Artisan morning progress is processed asynchronously during ticks 20..219.
                // Keep today's deadline until that window has settled instead of skipping a ripe batch.
                if (morningSettled(c)) schedule(c,poi,1);
                return false;
            }
        }
        return true;
    }
    private String scheduleKey(Poi poi) {
        Pos p = poi.pos();
        return (feature == Feature.WINE ? "wine:" : "preserves:") + p.x() + ":" + p.y() + ":" + p.z();
    }
    private static boolean morningSettled(Context c) { return Math.floorMod(c.world().dayTime(),24000) >= 220; }
    private void schedule(Context c,Poi poi,int days) {
        c.profile().nextEligibleDay.put(scheduleKey(poi),Math.floorDiv(c.world().dayTime(),24000)+Math.max(1,days));
    }
    private String snapshot(Context c, Poi poi) {
        int[] counts = new int[4];
        for (ItemSlot s : c.world().menu().slots()) if (!s.player() && !s.item().empty()) {
            if (!s.item().is(ItemData.TOMATO)) return "Tomato source contains a non-tomato item; inspect the registered tomato-only storage";
            int q = s.item().quality();
            if (q < 0 || q > 3) return "Tomato source contains an unknown tomato grade";
            counts[q] += s.item().count();
        }
        stock.put(poi,counts); return null;
    }
    private ItemSlot heldCandidate(Context c) {
        List<ItemSlot> held=c.world().inventory().stream()
            .filter(s -> s.inventoryIndex()>=0 && s.inventoryIndex()<36 && ModuleSupport.tomatoGrade(s.item(),grade)).toList();
        ItemSlot refill=held.stream().filter(s -> s.item().count()>cost).findFirst().orElse(null);
        if (refill!=null) return refill;
        // More work and carried same-grade fragments warrant a bounded merge
        // before emptying the hand. A genuinely final recipe
        // keeps the old exact-cost completion semantics; it needs no later refill.
        if (grade>=0 && remainingIngredientDemand(c)>cost && heldCounts(c)[grade]>cost
                && !mergeState(c,ItemData.TOMATO,grade).equals(singleStackFallbackState)) return null;
        return held.stream().filter(s -> s.item().count()>=cost).findFirst().orElse(null);
    }
    private int singleStackGrade(Context c) {
        int[] counts=heldCounts(c); boolean[] funded=new boolean[4];
        for (ItemSlot slot:c.world().inventory()) {
            ItemData item=slot.item();
            if (slot.inventoryIndex()>=0 && slot.inventoryIndex()<36 && item.is(ItemData.TOMATO)
                    && item.quality()>=0 && item.quality()<4 && item.count()>=cost) funded[item.quality()]=true;
        }
        if (grade>=0 && funded[grade]) return grade;
        for (int candidate=0;candidate<4;candidate++) if (!funded[candidate]) counts[candidate]=0;
        return chooseGrade(counts,cost);
    }
    private ItemSlot emptySlot(Context c) {
        for (int i = 0; i < 36; i++) {
            final int index = i;
            if (i == c.profile().hoeHotbarSlot) continue;
            ItemSlot slot = c.world().inventory().stream().filter(s -> s.inventoryIndex() == index).findFirst().orElse(null);
            if (slot == null || slot.item().empty()) return new ItemSlot(i,i,true,ItemData.EMPTY);
        }
        return null;
    }
    private boolean validMenu(Context c) { return c.world().menu().container() && c.world().menu().id() == containerId && c.world().menu().carried().empty(); }
    private Pos observedPickup(Context c) {
        Pos output=outputPosition(machine(c));
        PlayerState player=c.world().player();
        return c.world().groundItems().stream().filter(g -> g.item().is(outputId()))
            .filter(g -> Double.isFinite(g.x()) && Double.isFinite(g.y()) && Double.isFinite(g.z()))
            .filter(g -> Math.pow(g.x()-output.x()-.5,2)+Math.pow(g.z()-output.z()-.5,2)<=9
                && g.y()<=output.y()+1 && g.y()>=output.y()-6)
            .sorted(Comparator.comparingDouble(g -> Math.pow(g.x()-player.x(),2)+Math.pow(g.y()-player.y(),2)+Math.pow(g.z()-player.z(),2)))
            .map(g -> observedStandingCell(c,g)).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static Pos observedStandingCell(Context c,GroundItem item) {
        Pos cell=new Pos((int)Math.floor(item.x()),(int)Math.floor(item.y()),(int)Math.floor(item.z()));
        if (!c.world().loaded(cell)) return null;
        if (c.world().canStand(cell)) return cell;
        // Items on a bottom slab, farmland or a stair half share the support block's
        // cell. Normalize only when the observed item height matches its actual surface.
        Pos above=cell.offset(0,1,0);
        if (!c.world().loaded(above) || !c.world().canStand(above)) return null;
        double surface=c.world().standingY(above);
        return Double.isFinite(surface) && Math.abs(surface-item.y())<=.125 ? above : null;
    }
    private static Pos outputPosition(BlockData block) {
        return switch (block.properties().getOrDefault("facing","")) {
            case "north" -> block.pos().offset(0,0,-1);
            case "south" -> block.pos().offset(0,0,1);
            case "west" -> block.pos().offset(-1,0,0);
            case "east" -> block.pos().offset(1,0,0);
            default -> block.pos();
        };
    }
    private void submit(Context c, Action action, Pending reason) { pending = reason; ticket = c.actions().submit(action); }
    private void close(Context c, Stage next) { afterClose = next; submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE); }
    private boolean closeIfNeeded(Context c, Stage next) { if (!c.world().menu().container()) return false; close(c,next); return true; }
    private WorkResult fail(String message) {
        // A refill may already have happened even if its response is missing. Input-only
        // uncertainty uses a resettable local latch; collected output uses the durable ledger.
        boolean uncertainInteraction = pending == Pending.USE || stage == Stage.VERIFY || stage == Stage.PICKUP;
        boolean durableOutput = outputOperationId!=null;
        clearRun();
        // Durable output state is the guard after reset and may be reconciled while OFF.
        // An unrelated local latch must not keep blocking after that obligation is resolved.
        if (uncertainInteraction && !durableOutput) unresolvedInteraction = message;
        return WorkResult.blocked(message);
    }
    @Override public void reset() { yieldTravel=false; clearRun(); unresolvedInteraction = null; rejectedInputMerge=null; rejectedOutputMerge=null; repositionedInputState=null; }
    private void clearRun() {
        waitingWineRack=null;
        wineWaitObservation.clear();
        wineObservations.clear(); wineObservationTargets=Set.of(); wineObservationDay=Long.MIN_VALUE; wineObservationRetry=0;
        stage = Stage.START; afterClose = null; pending = null; ticket = -1; verifySince = 0; useSettleAt = -1;
        machines = List.of(); sources = List.of(); stock.clear(); machineIndex = 0; selectedMachineIndex = -1; sourceIndex = 0;
        stockReady = false; freshForHaul = false; stockDay = 0;
        source = null; containerId = -1; grade = -1; collected = false; feeding = false;
        partialWineFeedEligible=false;partialWineFeedTarget=null;confirmedPartialWineInput=0;
        fullWineFeedEligible=false;fullWineFeedTarget=null;confirmedFullWineInput=false;
        outputOperationId=null;
        inventoryBeforeOutput=Map.of(); outputWineYear=null; preferredOutputSource=null;
        pendingMergeState=null; singleStackFallbackState=null; inputMergeAttempts=0; outputMergeAttempts=0;
        pendingReposition=false;
    }
}
