package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** One bounded service pass over registered artisan jobs; native actions own every inventory mutation. */
public final class ArtisanModule implements AutomationModule {
    private enum Stage { START, JOB, TARGET, CHOOSE, SOURCE, SNAPSHOT, FETCH_SOURCE, FETCH, APPROACH, EQUIP, VERIFY, PICKUP, OUTPUT, RETURN_INPUT }
    private enum Pending { CLOSE, OPEN_SCAN, OPEN_FETCH, WITHDRAW, SWAP, CLEAR_OUTPUT, SELECT, INSPECT_TARGET, INSPECT_EQUIP, USE }
    private enum Equipment { READY, PENDING, NO_SLOT }
    private final Feature feature;
    private final HotbarWorkspace workspace;
    private WorkResult finishing;
    private boolean finishingSleepSafe;
    private Stage stage=Stage.START,afterClose;
    private Pending pending;
    private long ticket=-1,verifySince,settledSince=-1,dispatchDay=Long.MIN_VALUE;
    private List<ArtisanJob> jobs=List.of();
    private ArtisanJob job;
    private ArtisanRecipe recipe;
    private CommodityStore inputStore;
    private List<Pos> machines=List.of(),sources=List.of();
    private final Map<Pos,int[]> stock=new LinkedHashMap<>();
    private int jobIndex,machineIndex,sourceIndex,containerId=-1,grade=-1,hotbar=-1;
    private int scanPasses;
    private int inputBefore,outputBefore,withdrawalBefore,expectedOutput;
    private Pos source;
    private boolean stockReady,feeding,collected,cycleAdvanced,crystalEquipInspected,crystalOriginalKnown;
    private long stockDay=-1;
    private String deferredAfterCleanup;
    private final Map<String,Set<String>> uncertainJobs=new LinkedHashMap<>();
    private boolean sleepSafeDefer;
    private CommodityStorageModule outputStorage,inputReturn;

    public ArtisanModule(Feature feature) {
        if (feature!=Feature.SEED_MAKER && feature!=Feature.CRYSTAL_COPY)
            throw new IllegalArgumentException("Artisan feature must be SEED_MAKER or CRYSTAL_COPY");
        this.feature=feature;
        this.workspace=new HotbarWorkspace(feature);
    }
    @Override public Feature feature() { return feature; }
    @Override public int priority() { return feature==Feature.SEED_MAKER ? 72 : 74; }
    private boolean crystalMode() { return feature==Feature.CRYSTAL_COPY; }
    private Pos target() { return machines.get(machineIndex); }
    private static long day(Context c) { return Math.floorDiv(c.world().dayTime(),24000L); }
    private String status(String phase) {
        return (feature==Feature.SEED_MAKER ? "씨앗기 "+(job==null ? "" : job.id()+" ")
            : "결정생성기 "+(crystalOriginalKnown && recipe!=null ? "["+recipe.inputId()+"] " : ""))
            +Math.min(machineIndex+1,machines.size())+"/"+machines.size()+" — "+phase;
    }

    @Override public WorkResult tick(Context c) {
        sleepSafeDefer=false;
        try {
            if(c.profile().workHotbarLease!=null && !workspace.owns(c))
                return WorkResult.blocked("다른 작업의 임시 단축바 복원이 먼저 필요합니다");
            if(workspace.owns(c) && c.session().workHotbarOwner!=feature) {
                WorkResult recovered=workspace.restore(c);
                return recovered==null ? WorkResult.busy("이전 단축바 복원 완료 — 가공 상태 재확인") : recovered;
            }
            WorkResult waiting=workspace.pending(c);
            if(waiting!=null)return waiting;
            if(finishing!=null)return finishWorkspace(c);
            WorkResult result=advance(c);
            if(result.state()!=WorkResult.State.BUSY && workspace.owns(c)) {
                finishing=result;finishingSleepSafe=sleepSafeDefer;
                return finishWorkspace(c);
            }
            return result;
        }
        catch (RuntimeException failure) { return fail("가공 상태를 안전하게 저장·확인하지 못했습니다: "+failure.getClass().getSimpleName()
            +(failure instanceof IllegalStateException ? " — "+failure.getMessage() : "")); }
    }
    private WorkResult finishWorkspace(Context c) {
        WorkResult restored=workspace.restore(c);if(restored!=null)return restored;
        WorkResult result=finishing;finishing=null;sleepSafeDefer=finishingSleepSafe;finishingSleepSafe=false;
        return result;
    }
    private WorkResult advance(Context c) {
        if (ticket>=0) {
            ActionOutcome result=c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy(status("서버 확인 대기"));
            ticket=-1;
            if (!result.success()) {
                if (pending==Pending.INSPECT_TARGET || pending==Pending.INSPECT_EQUIP)
                    return skipUncertainTarget(c,"결정복제기의 현재 원본을 확인하지 못했습니다: "+result.message());
                String uncertainty=pending==Pending.USE ? c.actions().artisanRejection(target()) : null;
                if(uncertainty!=null) return skipUncertainTarget(c,uncertainty);
                return fail("가공 조작 실패: "+result.message());
            }
            Pending completed=pending;pending=null;
            switch (completed) {
                case CLOSE -> { containerId=-1;stage=afterClose; }
                case OPEN_SCAN,OPEN_FETCH -> {
                    if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("재료 상자 동기화 실패");
                    containerId=c.world().menu().id();stage=completed==Pending.OPEN_SCAN ? Stage.SNAPSHOT : Stage.FETCH;
                }
                case WITHDRAW -> {
                    if (!validMenu(c) || inputCount(c,grade)<=withdrawalBefore) return fail("재료 인출 수량이 확인되지 않았습니다");
                    snapshot(c);stage=Stage.FETCH;
                }
                case SWAP,SELECT -> { crystalEquipInspected=false;stage=Stage.EQUIP; }
                case CLEAR_OUTPUT -> { crystalEquipInspected=false;stage=Stage.CHOOSE; }
                case INSPECT_TARGET,INSPECT_EQUIP -> {
                    WorkResult inspected=acceptCrystalInspection(c,completed==Pending.INSPECT_EQUIP);
                    if(inspected!=null)return inspected;
                }
                case USE -> {
                    cycleAdvanced=result.proof()==ActionOutcome.Proof.ARTISAN_CYCLE_ADVANCED;
                    verifySince=c.world().tick();stage=Stage.VERIFY;
                }
            }
        }
        switch (stage) {
            case START -> {
                jobs=c.profile().artisanJobs==null ? List.of() : c.profile().artisanJobs.entrySet().stream()
                    .filter(e -> e.getValue()!=null && e.getKey().equals(e.getValue().id()) && e.getValue().recipe().feature()==feature)
                    .map(Map.Entry::getValue).toList();
                jobIndex=0;stage=Stage.JOB;
                return selectJob(c);
            }
            case JOB -> { return selectJob(c); }
            case TARGET -> {
                crystalOriginalKnown=false;
                if (machineIndex>=machines.size()) { stage=Stage.OUTPUT;break; }
                String uncertainty=c.actions().artisanRejection(target());
                if(uncertainty!=null)return skipUncertainTarget(c,uncertainty);
                if (closeIfNeeded(c,Stage.TARGET)) break;
                Navigation.Result nav=c.navigation().moveTo(target(),4,c);
                if (nav==Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"등록한 가공 기계에 접근할 수 없습니다");
                if (nav!=Navigation.Result.ARRIVED) return WorkResult.busy(status("기계로 이동"));
                BlockData block=machine(c);
                if (crystalMode()) {
                    crystalEquipInspected=false;submit(c,new Action.InspectCrystal(target()),Pending.INSPECT_TARGET);break;
                }
                if (block.flag("working")) {
                    schedule(c,1);machineIndex++;break;
                }
                stage=Stage.CHOOSE;
            }
            case CHOOSE -> {
                grade=carriedGrade(c);
                boolean emptyHandCollection=grade<0 && recipe.sameInputAndOutput() && machine(c).flag("mature");
                feeding=!emptyHandCollection;
                // Check capacity before surveying or withdrawing ingredients. Recheck
                // again in EQUIP before dispatch; no tool is borrowed to create it.
                if (chooseHotbar(c,!emptyHandCollection)<0) return deferHotbarShortage(c);
                if (grade>=0) { feeding=true;stage=Stage.APPROACH;break; }
                if (emptyHandCollection) {
                    feeding=false;stage=Stage.APPROACH;break;
                }
                if (crystalMode() && (inputStore==null || !inputStore.items().contains(recipe.inputId())))
                    return skipUncertainTarget(c,"같은 원본 "+recipe.inputId()+"을 재투입할 등록 재료 저장소가 없습니다");
                if (!stockReady || stockDay!=day(c)) {
                    if (scanPasses>=2) return materialShortage(c,"재료 상태가 바뀌거나 한 배치로 합칠 수 없습니다. 재조사를 잠시 보류합니다");
                    beginScan(c);break;
                }
                int[] totals=totals(c);grade=largestGrade(totals,recipe.inputCount());
                if (grade<0) return materialShortage(c,"등록한 저장고에 한 배치의 재료가 부족합니다");
                source=sourceForGrade();
                if (source==null) return materialShortage(c,"같은 등급의 조각 재료를 한 배치 크기로 합친 뒤 다시 확인하세요");
                feeding=true;stage=Stage.FETCH_SOURCE;
            }
            case SOURCE -> {
                if (sourceIndex>=sources.size()) { stockReady=true;stockDay=day(c);stage=Stage.CHOOSE;break; }
                source=sources.get(sourceIndex);
                Navigation.Result nav=c.navigation().moveTo(source,2.5,c);
                if (nav==Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"재료 저장고를 확인할 수 없습니다");
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(source,Action.Use.OPEN_CONTAINER),Pending.OPEN_SCAN);
                return WorkResult.busy(status("재료 재고 확인 "+(sourceIndex+1)+"/"+sources.size()+" — 가장 많은 등급 선택"));
            }
            case SNAPSHOT -> {
                if (!validMenu(c)) return fail("재료 조사 중 저장고가 바뀌었습니다");
                snapshot(c);sourceIndex++;close(c,Stage.SOURCE);
            }
            case FETCH_SOURCE -> {
                Navigation.Result nav=c.navigation().moveTo(source,2.5,c);
                if (nav==Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"선택한 재료 저장고에 접근할 수 없습니다");
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(source,Action.Use.OPEN_CONTAINER),Pending.OPEN_FETCH);
            }
            case FETCH -> {
                if (!validMenu(c)) return fail("재료 인출 중 저장고가 바뀌었습니다");
                snapshot(c);
                if (stockDay!=day(c)) { stockReady=false;close(c,Stage.CHOOSE);break; }
                int needed=Math.max(0,(crystalMode()?1:machines.size()-machineIndex)*recipe.inputCount()-inputCount(c,grade));
                boolean funded=ingredient(c,grade)!=null;
                if (funded && (needed==0 || emptySlots(c)<=2)) { close(c,Stage.APPROACH);break; }
                if (emptySlots(c)<=2) { deferredAfterCleanup="재료와 산출물용 빈 인벤토리 칸이 필요합니다";close(c,Stage.OUTPUT);break; }
                List<ItemSlot> available=c.world().menu().slots().stream().filter(s -> !s.player() && input(s.item(),grade)
                    && s.item().count()<=64).toList();
                ItemSlot fitting=available.stream().filter(s -> s.item().count()<=needed)
                    .max(Comparator.comparingInt(s -> s.item().count())).orElse(null);
                ItemSlot over=available.stream().filter(s -> s.item().count()>needed)
                    .min(Comparator.comparingInt(s -> s.item().count())).orElse(null);
                int fittingTotal=available.stream().filter(s -> s.item().count()<=needed).mapToInt(s -> s.item().count()).sum();
                ItemSlot chosen=over!=null && fittingTotal<needed ? over : fitting;
                if (chosen==null) {
                    Pos next=sourceForGrade();
                    if (next!=null && !next.equals(source)) { source=next;close(c,Stage.FETCH_SOURCE); }
                    else { stockReady=false;close(c,funded ? Stage.APPROACH : Stage.CHOOSE); }
                    break;
                }
                withdrawalBefore=inputCount(c,grade);
                submit(c,new Action.QuickMove(containerId,chosen.index()),Pending.WITHDRAW);
            }
            case APPROACH -> {
                if (closeIfNeeded(c,Stage.APPROACH)) break;
                Navigation.Result nav=c.navigation().moveTo(target(),4,c);
                if (nav==Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"재료를 들고 기계에 접근할 수 없습니다");
                if (nav==Navigation.Result.ARRIVED) { settledSince=c.world().tick();crystalEquipInspected=false;stage=Stage.EQUIP; }
            }
            case EQUIP -> {
                c.actions().stopMovement();
                if (c.world().menu().container() || !c.world().menu().carried().empty()) return fail("가공 전 상자와 커서를 비우세요");
                BlockData block=machine(c);
                if (!crystalMode() && block.flag("working")) { schedule(c,1);machineIndex++;stage=Stage.TARGET;break; }
                if (feeding && ingredient(c,grade)==null) { stage=Stage.CHOOSE;break; }
                Equipment equipment=equip(c);
                if (equipment==Equipment.NO_SLOT) return deferHotbarShortage(c);
                if (equipment==Equipment.PENDING) return WorkResult.busy(status("사용할 재료 준비"));
                if (crystalMode() && !crystalEquipInspected) {
                    submit(c,new Action.InspectCrystal(target()),Pending.INSPECT_EQUIP);break;
                }
                collected=block.flag("mature");
                if (!feeding && !collected) { stage=Stage.CHOOSE;break; }
                if (collected && emptySlots(c)<1) return deferAfterCleanup(c,"완성품을 받을 빈 인벤토리 칸이 필요합니다");
                if (c.world().tick()-settledSince<2) break;
                if (!c.world().canInteract(target(),4)) { c.navigation().reset();stage=Stage.APPROACH;break; }
                // The same fresh server inspection must be saved before this use can erase its original.
                if(crystalMode() && collected)CrystalRefillRules.remember(c,job,target(),recipe.inputId());
                inputBefore=inputCount(c,grade);outputBefore=outputCount(c);
                expectedOutput=outputBefore+(collected ? recipe.outputCount() : 0)
                    -(feeding && recipe.sameInputAndOutput() ? recipe.inputCount() : 0);
                // This is only a reinspection calendar anchor. It grants neither
                // consumption proof nor a schedule until the native ACK and VERIFY succeed.
                cycleAdvanced=false;
                dispatchDay=day(c);
                submit(c,new Action.UseBlock(target(),Action.Use.ARTISAN),Pending.USE);
            }
            case VERIFY -> {
                BlockData block=machine(c);int after=inputCount(c,grade);
                // Same-ID crystals may be picked up in the same native ACK as
                // their input consumption. Native confirmation combines the
                // selected-slot server receipt with the target's working-state
                // receipt; it cannot isolate consumption from simultaneous pickup.
                // This projection checks the allowed aggregate net range only.
                // A non-mature seed maker may already hold one or two manually
                // inserted fruits. Its unsynchronized client BE is not evidence:
                // require the native working+selected-slot ACK and a 1..3 decrease.
                boolean inputConfirmed=!feeding || (!recipe.sameInputAndOutput()
                    ? after>=inputBefore-recipe.inputCount() && after<=inputBefore-recipe.minimumInputConsumed(collected)
                    : after>=inputBefore-recipe.inputCount() && after<=inputBefore-recipe.inputCount()+(collected ? recipe.outputCount() : 0));
                // Only this successful ticket's retained native progression proof
                // permits a cycle that matured before its ACK was consumed. Merely
                // seeing an initial mature block (or a later working reversal) does not.
                boolean stateConfirmed=cycleAdvanced
                    ? feeding && block.flag("mature") && !block.flag("working")
                    : !block.flag("mature") && block.flag("working")==feeding;
                if (stateConfirmed && inputConfirmed) {
                    if (feeding) {
                        if (dispatchDay==Long.MIN_VALUE) return fail("가공 전송 날짜가 없어 다음 확인일을 저장할 수 없습니다");
                        long nextDay=Math.addExact(dispatchDay,recipe.cycleDays());
                        if(crystalMode())CrystalRefillRules.complete(c,job,target(),nextDay);else scheduleAt(c,nextDay);
                        stockReady=false;scanPasses=0;
                    }
                    stage=collected ? Stage.PICKUP : Stage.TARGET;
                    if (!collected) machineIndex++;
                } else if (c.world().tick()-verifySince>c.profile().interactionTimeoutTicks)
                    return fail("가공 후 정확한 재료 소비·기계 상태가 확인되지 않았습니다");
            }
            case PICKUP -> {
                if (outputCount(c)>=expectedOutput) {
                    if (feeding) { machineIndex++;stage=Stage.TARGET; }
                    else { crystalEquipInspected=false;stage=Stage.CHOOSE; }
                    break;
                }
                c.actions().stopMovement();
                if (c.world().tick()-verifySince>c.profile().interactionTimeoutTicks) {
                    deferredAfterCleanup="완성품 도착을 아직 관측하지 못했습니다. 같은 기계를 다시 누르지 않고 보유품만 정리합니다";
                    stage=Stage.OUTPUT;
                }
            }
            case OUTPUT -> {
                WorkResult result=outputStorage.tick(c);
                if (result.state()!=WorkResult.State.IDLE) return result;
                stage=Stage.RETURN_INPUT;
            }
            case RETURN_INPUT -> {
                if (!crystalMode() && !recipe.sameInputAndOutput()) {
                    WorkResult result=inputReturn.tick(c);
                    if (result.state()!=WorkResult.State.IDLE) return result;
                }
                if (deferredAfterCleanup!=null) return deferClean(c,deferredAfterCleanup);
                jobIndex++;stage=Stage.JOB;
                if (crystalMode() && ModuleSupport.count(c,i -> CrystalCollection.accepts(i)
                        && !CrystalCollection.BASE_OUTPUT_IDS.contains(i.id()) && !CrystalCollection.outputIds(c.profile(),job).contains(i.id()))>0)
                    return WorkResult.busy(status("미등록 결정 보너스는 인벤토리에 남겨 둡니다 — 이 작업은 자동 보관·판매하지 않습니다"));
            }
        }
        return WorkResult.busy(status(phaseLabel()));
    }

    private WorkResult selectJob(Context c) {
        // The scheduler restarts at its highest priority after a BUSY module
        // finishes. Quiet jobs must therefore yield in this same tick, not
        // reserve another multi-tick 0/0 pass that starves every later module.
        while (jobIndex<jobs.size()) {
            job=jobs.get(jobIndex);recipe=job.recipe();crystalOriginalKnown=false;
            inputStore=CommodityStorageRules.store(c.profile(),job.inputStoreId());
            CommodityStore output=CommodityStorageRules.store(c.profile(),job.outputStoreId());
            Set<String> outputs=crystalMode() ? CrystalCollection.outputIds(c.profile(),job) : Set.of(recipe.outputId());
            if (output==null || outputs.isEmpty() || !crystalMode() && (inputStore==null
                    || !inputStore.items().contains(recipe.inputId()) || !output.items().contains(recipe.outputId())))
                return fail("가공 작업의 입력·출력 품목 저장고를 등록하세요");
            boolean due=job.machines().stream().anyMatch(p -> eligible(c,p));
            boolean held=c.world().inventory().stream().map(ItemSlot::item)
                .anyMatch(item -> crystalMode() ? !item.empty() && outputs.contains(item.id()) : item.is(recipe.inputId()) || item.is(recipe.outputId()));
            if (!due && !held) { jobIndex++;continue; }
            if (due && Math.floorMod(c.world().dayTime(),24000)<240)
                return WorkResult.busy(status("아침 기계 상태 갱신 대기"));
            machines=job.machines().stream().filter(p -> eligible(c,p))
                .sorted(Comparator.comparingDouble(p -> c.world().player().distance(p))).toList();
            machineIndex=0;scanPasses=0;stockReady=false;stock.clear();stockDay=-1;
            outputStorage=new CommodityStorageModule(feature,job.outputStoreId(),outputs);
            inputReturn=crystalMode() ? null : new CommodityStorageModule(feature,job.inputStoreId(),Set.of(recipe.inputId()));
            stage=machines.isEmpty() ? Stage.OUTPUT : Stage.TARGET;
            return WorkResult.busy(status(phaseLabel()));
        }
        if (!uncertainJobs.isEmpty()) {
            String unsafe=uncertaintySkipRejection(c);
            if (unsafe!=null) return fail(unsafe);
            return deferClean(c,"미확정 기계는 재클릭하지 않았습니다. 나머지 가공 작업과 보유품 정리를 마치고 보류합니다");
        }
        resetJob();return WorkResult.idle();
    }

    private String phaseLabel() {
        return switch(stage) {
            case SOURCE,SNAPSHOT -> "저장고 재료 조사";
            case FETCH_SOURCE,FETCH -> "재료 가져오는 중";
            case VERIFY -> "재료 소비·생산 상태 확인";
            case PICKUP -> "관측된 완성품 도착 대기";
            case OUTPUT,RETURN_INPUT -> "완성품·남은 재료 보관";
            default -> "등록한 배치 처리 중";
        };
    }
    private WorkResult acceptCrystalInspection(Context c,boolean beforeUse) {
        CrystalInspection inspected=c.actions().crystalInspection(target());
        BlockData block=machine(c);
        if(inspected==null || !target().equals(inspected.pos()) || inspected.mature()!=block.flag("mature")
                || inspected.working()!=block.flag("working") || inspected.mature() && inspected.working())
            return skipUncertainTarget(c,"결정복제기의 현재 원본·상태 조회가 일치하지 않습니다");
        ArtisanRecipe actual=CrystalRecipe.forInput(inspected.inputId());
        if(inspected.working()) {
            if(actual==null)return skipUncertainTarget(c,"가동 중인 결정복제기의 원본을 확인하지 못했습니다");
            CrystalRefillRules.complete(c,job,target(),Math.addExact(day(c),1));
            machineIndex++;crystalEquipInspected=false;stage=Stage.TARGET;
            return WorkResult.busy(status("이미 같은 기계가 가동 중 — 다음 기계 확인"));
        }
        if(inspected.mature()) {
            if(actual==null)return skipUncertainTarget(c,"지원되지 않거나 확인되지 않은 결정 원본은 교체하지 않습니다");
        } else {
            if(!inspected.empty())return skipUncertainTarget(c,"비어 있는 결정복제기의 상태를 확정하지 못했습니다");
            actual=CrystalRefillRules.pendingRecipe(c.profile(),job,target());
            if(actual==null) {
                schedule(c,1);machineIndex++;crystalEquipInspected=false;stage=Stage.TARGET;
                return WorkResult.busy(status("원본을 직접 넣지 않은 빈 기계는 건너뜁니다"));
            }
        }
        boolean changed=recipe==null || !recipe.inputId().equals(actual.inputId());
        recipe=actual;
        crystalOriginalKnown=true;
        if(changed) { stockReady=false;stock.clear();stockDay=-1;scanPasses=0;grade=-1; }
        crystalEquipInspected=beforeUse && !changed;
        stage=crystalEquipInspected?Stage.EQUIP:Stage.CHOOSE;
        return null;
    }
    private WorkResult materialShortage(Context c,String reason) {
        return crystalMode()?skipUncertainTarget(c,reason+" — 같은 원본 재투입 기록은 보존합니다"):deferAfterCleanup(c,reason);
    }
    private String scheduleKey(Pos p) { return crystalMode() ? CrystalCollection.scheduleKey(job,p) : job.scheduleKey(p); }
    private boolean eligible(Context c,Pos p) { return day(c)>=c.profile().nextEligibleDay.getOrDefault(scheduleKey(p),Long.MIN_VALUE); }
    private BlockData machine(Context c) {
        if (!c.world().loaded(target())) throw new IllegalStateException("Machine is not loaded");
        BlockData block=c.world().block(target());
        if (block==null || !recipe.machineId().equals(block.id()) || !block.properties().containsKey("mature")
                || !block.properties().containsKey("working") || block.flag("mature") && block.flag("working"))
            throw new IllegalStateException("Registered artisan machine state changed");
        return block;
    }
    private void schedule(Context c,int days) {
        scheduleAt(c,Math.addExact(day(c),days));
    }
    private void scheduleAt(Context c,long nextDay) {
        String key=scheduleKey(target());Long prior=c.profile().nextEligibleDay.put(key,nextDay);
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) {
            if (prior==null) c.profile().nextEligibleDay.remove(key);else c.profile().nextEligibleDay.put(key,prior);
            throw failure;
        }
    }
    private boolean input(ItemData item,int selectedGrade) {
        return item!=null && item.is(recipe.inputId()) && item.quality()==selectedGrade && selectedGrade>=0 && selectedGrade<=3;
    }
    private int inputCount(Context c,int selectedGrade) { return ModuleSupport.count(c,i -> input(i,selectedGrade)); }
    private int outputCount(Context c) { return ModuleSupport.count(c,i -> i.is(recipe.outputId())); }
    private int emptySlots(Context c) { return 36-(int)c.world().inventory().stream().filter(s -> !s.item().empty()).count(); }
    private ItemSlot ingredient(Context c,int selectedGrade) {
        return c.world().inventory().stream().filter(s -> s.inventoryIndex()>=0 && s.inventoryIndex()<36
            && s.inventoryIndex()!=c.profile().hoeHotbarSlot && input(s.item(),selectedGrade) && s.item().count()>=recipe.inputCount())
            .max(Comparator.comparingInt(s -> s.item().count())).orElse(null);
    }
    private int carriedGrade(Context c) {
        int[] counts=new int[4];
        for (int q=0;q<4;q++) if (ingredient(c,q)!=null) counts[q]=inputCount(c,q);
        return largestGrade(counts,recipe.inputCount());
    }
    static int largestGrade(int[] counts,int minimum) {
        int grade=-1;
        for (int q=0;q<4;q++) if (counts[q]>=minimum && (grade<0 || counts[q]>counts[grade])) grade=q;
        return grade;
    }
    private void beginScan(Context c) {
        sources=inputStore.containers().stream().sorted(Comparator.comparingDouble(p -> c.world().player().distance(p))).toList();
        sourceIndex=0;scanPasses++;stockReady=false;stock.clear();stage=Stage.SOURCE;
    }
    private int[] totals(Context c) {
        int[] totals=new int[4];for (int q=0;q<4;q++) totals[q]=inputCount(c,q);
        for (int[] counts:stock.values()) for (int q=0;q<4;q++) totals[q]=Math.addExact(totals[q],counts[q]);
        return totals;
    }
    private void snapshot(Context c) {
        int[] counts=new int[4];
        for (ItemSlot slot:c.world().menu().slots()) if (!slot.player() && slot.item().is(recipe.inputId())) {
            int q=slot.item().quality();if (q<0 || q>3) continue;
            counts[q]=Math.addExact(counts[q],slot.item().count());
        }
        stock.put(source,counts);
    }
    private Pos sourceForGrade() { return stock.entrySet().stream().filter(e -> grade>=0 && e.getValue()[grade]>0).map(Map.Entry::getKey).findFirst().orElse(null); }
    private boolean validMenu(Context c) {
        MenuData menu=c.world().menu();
        return menu!=null && menu.container() && menu.id()==containerId && menu.carried().empty()
            && source!=null && inputStore.containers().contains(source);
    }
    private Equipment equip(Context c) {
        ItemSlot prepared=feeding ? ingredient(c,grade) : null;
        if (feeding && prepared==null) return Equipment.PENDING;
        hotbar=chooseHotbar(c,feeding);
        if (hotbar<0) return Equipment.NO_SLOT;
        ItemSlot current=null;
        for (ItemSlot slot:c.world().inventory()) if (slot.inventoryIndex()==hotbar) current=slot;
        if (feeding && (current==null || !input(current.item(),grade) || current.item().count()<recipe.inputCount()
                || current.item().count()==recipe.inputCount() && prepared.item().count()>recipe.inputCount())) {
            submit(c,new Action.SwapHotbar(prepared.inventoryIndex(),hotbar),Pending.SWAP);return Equipment.PENDING;
        }
        if (c.world().player().selectedSlot()!=hotbar) { submit(c,new Action.SelectHotbar(hotbar),Pending.SELECT);return Equipment.PENDING; }
        return Equipment.READY;
    }
    private WorkResult deferHotbarShortage(Context c) {
        if (pending!=null || uncertaintySkipRejection(c)!=null)
            return fail("단축바 빈칸 대기 전에 미확인 조작·메뉴·빌린 슬롯을 확인해야 합니다");
        if (clearCollectedWorkingSlot(c))
            return WorkResult.busy(status("회수한 결정을 일반 인벤토리 빈칸으로 옮기고 빈손 재준비"));
        WorkResult prepared=workspace.prepare(c);
        if(prepared.state()==WorkResult.State.DEFERRED)
            return deferClean(c,status("단축바에 빈칸 1개를 준비할 수 없습니다. "+prepared.message()));
        return prepared;
    }
    /** A pickup may occupy the one leased empty hand. Only that slot's known crystal can move. */
    private boolean clearCollectedWorkingSlot(Context c) {
        if (!crystalMode() || feeding || !workspace.owns(c)) return false;
        HotbarLease lease=c.profile().workHotbarLease;
        if (lease.stage()!=HotbarLease.Stage.PARKED || !c.actions().workHotbarParked(lease)) return false;
        ItemSlot collected=c.world().inventory().stream().filter(s -> s.player() && s.inventoryIndex()==lease.hotbarSlot())
            .findFirst().orElse(null);
        if (collected==null || !CrystalCollection.accepts(collected.item())) return false;
        ItemSlot empty=c.world().inventory().stream().filter(s -> s.player() && s.inventoryIndex()>=9 && s.inventoryIndex()<36
            && s.inventoryIndex()!=lease.sourceIndex() && s.item().empty()).findFirst().orElse(null);
        if (empty==null) return false;
        c.actions().stopMovement();
        submit(c,new Action.SwapHotbar(empty.inventoryIndex(),lease.hotbarSlot()),Pending.CLEAR_OUTPUT);
        return true;
    }
    private int chooseHotbar(Context c,boolean forFeeding) {
        if (forFeeding) {
            ItemSlot carried=c.world().inventory().stream().filter(s -> s.inventoryIndex()>=0 && s.inventoryIndex()<9
                && s.inventoryIndex()!=c.profile().hoeHotbarSlot && input(s.item(),grade) && s.item().count()>=recipe.inputCount())
                .max(Comparator.comparingInt(s -> s.item().count())).orElse(null);
            if (carried!=null) return carried.inventoryIndex();
        }
        for (int index=0;index<9;index++) {
            if (index==c.profile().hoeHotbarSlot) continue;
            ItemData item=ItemData.EMPTY;
            for (ItemSlot slot:c.world().inventory()) if (slot.inventoryIndex()==index) item=slot.item();
            if (item.empty() || forFeeding && (item.is(recipe.inputId()) || item.is(recipe.outputId())
                    || crystalMode() && workspace.owns(c) && index==c.profile().workHotbarLease.hotbarSlot()
                        && CrystalCollection.accepts(item))) return index;
        }
        return -1;
    }
    private void submit(Context c,Action action,Pending next) { pending=next;ticket=c.actions().submit(action); }
    private void close(Context c,Stage next) { afterClose=next;submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE); }
    private boolean closeIfNeeded(Context c,Stage next) {
        if (!c.world().menu().container()) return false;
        close(c,next);return true;
    }
    private WorkResult deferClean(Context c,String message) {
        if (ticket>=0 || c.actions().busy() || !c.world().player().onGround() || c.world().menu().container() || !c.world().menu().carried().empty())
            return fail("가공 보류 전에 현재 조작을 확인해야 합니다: "+message);
        String detailed=message;
        if (!uncertainJobs.isEmpty()) detailed+=" — "+String.join(" / ",uncertainJobs.entrySet().stream()
            .map(e->e.getKey()+": "+String.join("; ",e.getValue())).toList());
        c.actions().stopMovement();resetJob();sleepSafeDefer=true;return WorkResult.deferred(detailed);
    }
    /** A target-scoped sent-use uncertainty never grants a skip across a global unsafe boundary. */
    private String uncertaintySkipRejection(Context c) {
        if (ticket>=0 || c.actions().busy() || c.actions().pauseReason()!=null
                || !c.world().player().connected() || !c.world().player().onGround()
                || c.world().menu()==null || c.world().menu().container() || !c.world().menu().carried().empty()
                || c.profile().loggingHotbarLease!=null || MachineOutputLedger.hasPending(c))
            return "미확정 가공 기계를 건너뛰기 전에 현재 조작·메뉴·빌린 슬롯을 안전하게 확인해야 합니다";
        return null;
    }
    private WorkResult skipUncertainTarget(Context c,String reason) {
        String unsafe=uncertaintySkipRejection(c);
        if (unsafe!=null) return fail(unsafe);
        uncertainJobs.computeIfAbsent(job.id(),ignored->new LinkedHashSet<>()).add(reason);
        c.actions().stopMovement();pending=null;machineIndex++;stage=Stage.TARGET;
        return WorkResult.busy(status("미확정 기계는 재클릭 없이 보류 — 나머지 등록 기계 확인"));
    }
    @Override public boolean sleepSafeDeferred(Context c) {
        return sleepSafeDefer && ticket<0 && !c.actions().busy() && c.actions().pauseReason()==null
            && c.world().player().onGround() && c.world().menu()!=null && !c.world().menu().container()
            && c.world().menu().carried().empty() && c.profile().loggingHotbarLease==null && c.profile().workHotbarLease==null && !MachineOutputLedger.hasPending(c);
    }
    private WorkResult deferAfterCleanup(Context c,String message) {
        c.actions().stopMovement();deferredAfterCleanup=message;stage=Stage.OUTPUT;
        return WorkResult.busy(status("보유 완성품·재료 정리 후 보류"));
    }
    private WorkResult fail(String message) { resetJob();return WorkResult.blocked(message); }
    @Override public void reset() {
        workspace.reset();finishing=null;finishingSleepSafe=false;resetJob();
    }
    private void resetJob() {
        sleepSafeDefer=false;
        if (outputStorage!=null) outputStorage.reset();if (inputReturn!=null) inputReturn.reset();
        stage=Stage.START;pending=null;ticket=-1;job=null;recipe=null;inputStore=null;jobs=List.of();machines=List.of();sources=List.of();
        stock.clear();stockReady=false;stockDay=-1;source=null;containerId=-1;settledSince=-1;dispatchDay=Long.MIN_VALUE;cycleAdvanced=false;crystalEquipInspected=false;crystalOriginalKnown=false;grade=-1;hotbar=-1;
        jobIndex=0;machineIndex=0;sourceIndex=0;scanPasses=0;deferredAfterCleanup=null;uncertainJobs.clear();outputStorage=null;inputReturn=null;
    }
}
