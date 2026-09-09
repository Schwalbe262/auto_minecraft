package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.LoggingApproachSearch;
import java.util.*;

/** The registered spruce routine; one acknowledged native operation at a time. */
public final class LoggingModule implements AutomationModule {
    private static final int SAPLING_WAIT_TICKS=400;
    private static final int VISIBILITY_RETRY_TICKS=1200;
    private static final int MAX_STALE_APPROACH_REPLANS=2;
    private static final int CLEANUP_QUIET_TICKS=20,CLEANUP_QUIET_TIMEOUT=400;
    private enum Stage { START, PLOT, CHOP, SETTLE, PLANT, WASTE, RESTORE, CRAFT_OPEN, CRAFT, CRAFT_CLOSE, WOOD, BERRIES, FINISH }
    private enum Pending { SELECT, SWAP, BORROW, RESTORE, CHOP, PLANT, TRASH, OPEN, CRAFT, CLOSE }
    private Stage stage=Stage.START;
    private boolean restoreBeforePlot;
    private Pending pending;
    private long ticket=-1, settleUntil, nextCheckTick=-1, lastTick=-1;
    private long saplingWaitUntil=-1;
    private int saplingWaitRequired;
    private boolean plantingStanceReady;
    private List<Pos> plantingOrder=List.of();
    private List<ItemData> cleanupInventory;
    private Map<Integer,String> cleanupFingerprints=Map.of();
    private long cleanupWindowStart=-1,cleanupSampleTick=-1;
    private int cleanupQuietTicks;
    private boolean cleanupQuietApproved;
    private Pos actionPos;
    private LoggingPlot plot;
    private Poi table;
    private int craftingMenu=-1, chopStrokes, trashOperations, craftOperations;
    private String failure;
    private WorkResult approachResult;
    private LoggingApproachSearch choppingApproach;
    private long visibilityRetryAt=-1;
    // Temporary negative observations only: never reorder or complete the saved batch.
    private final Map<Pos,LoggingApproachSearch> invisiblePlots=new LinkedHashMap<>();
    private final Map<Pos,Integer> staleApproachReplans=new HashMap<>();
    private long visibilityScanTick=Long.MIN_VALUE;
    private final Map<Pos,List<String>> initialPlotObservations=new HashMap<>();
    private long initialObservationDay=Long.MIN_VALUE;
    private final ModuleSupport.ObservationWindow observationWindow=new ModuleSupport.ObservationWindow();
    private final DepositModule wood=new LoggingDeposit(false), berries=new LoggingDeposit(true);

    @Override public Feature feature() { return Feature.LOGGING; }
    @Override public int priority() { return 80; }

    @Override public WorkResult tick(Context c) {
        if (!LoggingRules.allowed(c)) return WorkResult.idle();
        if (failure!=null) return WorkResult.blocked(failure);
        if (!c.world().player().connected()) return fail("벌목 중 연결이 끊겼습니다. 재접속 후 다시 실행하세요.");
        if (lastTick>c.world().tick()) { nextCheckTick=-1; saplingWaitUntil=-1; clearCleanupQuiet(); clearVisibilitySweep(); }
        lastTick=c.world().tick();
        try {
            if (ticket>=0) {
                ActionOutcome outcome=c.actions().outcome(ticket);
                if (!outcome.done()) return busy("서버 응답 확인 중");
                ticket=-1;
                if (!outcome.success()) return fail("벌목 작업 응답 실패: "+outcome.message());
                Pending completed=pending; pending=null;
                if (completed==Pending.CHOP) {
                    if (outcome.confirmedCount()<=0) return fail("벌목 진행이 서버에서 확인되지 않았습니다.");
                    if (++chopStrokes>512) return fail("한 나무의 벌목 진행 횟수가 한도를 넘었습니다. 밑동을 확인하세요.");
                    // TreeChop's native outline can shrink without changing BlockData.
                    // Only real progress renews the consecutive stale-goal budget.
                    if (plot!=null) staleApproachReplans.remove(plot.corner());
                } else if (completed==Pending.PLANT) {
                    if (outcome.confirmedCount()<=0 || !occupied(c,actionPos)) return fail("묘목 재식재가 확인되지 않았습니다.");
                } else if (completed==Pending.TRASH) {
                    if (outcome.confirmedCount()<=0) return fail("벌목 부산물 폐기가 확인되지 않았습니다.");
                } else if (completed==Pending.BORROW) {
                    LoggingHotbarLease lease=c.profile().loggingHotbarLease;
                    if (lease==null || !parked(c,lease)) return fail("임시 단축바의 원래 아이템 위치가 확인되지 않았습니다.");
                    saveLease(c,lease.withStage(LoggingHotbarLease.Stage.PARKED));
                } else if (completed==Pending.RESTORE) {
                    LoggingHotbarLease lease=c.profile().loggingHotbarLease;
                    if (lease==null || !restored(c,lease)) return fail("임시 단축바의 원래 아이템 복원이 확인되지 않았습니다.");
                    saveLease(c,null);
                    stage=restoreBeforePlot ? Stage.PLOT : nextAfterWaste(c);
                    if (restoreBeforePlot) clearCleanupQuiet();
                    restoreBeforePlot=false;
                } else if (completed==Pending.OPEN) {
                    if (!c.world().menu().container() || !c.world().loggingCraftingMenu()
                        || !c.world().loggingCraftingGridEmpty() || !c.world().menu().carried().empty())
                        return fail("빈 등록 제작대 메뉴를 확인할 수 없습니다.");
                    craftingMenu=c.world().menu().id(); stage=Stage.CRAFT;
                } else if (completed==Pending.CRAFT) {
                    if (outcome.confirmedCount()<=0 || outcome.confirmedCount()>64 || !c.world().loggingCraftingGridEmpty())
                        return fail("장작 제작 결과 또는 빈 제작 격자가 확인되지 않았습니다.");
                } else if (completed==Pending.CLOSE) {
                    if (c.world().menu().container()) return fail("제작대 닫기가 확인되지 않았습니다.");
                    craftingMenu=-1; stage=Stage.WOOD;
                }
            }
            return advance(c);
        } catch (RuntimeException problem) {
            return fail("벌목 상태를 안전하게 저장하거나 확인하지 못했습니다: "+problem.getMessage());
        }
    }

    private WorkResult advance(Context c) {
        switch (stage) {
            case START -> {
                if (c.world().menu().container() || !c.world().menu().carried().empty())
                    return fail("열린 메뉴와 커서의 아이템을 정리한 뒤 벌목을 실행하세요.");
                if (c.profile().loggingPlots.isEmpty())
                    return once(c) ? fail("벌목할 2x2 식재 구역을 먼저 등록하세요.") : WorkResult.idle();
                if (c.profile().loggingRunActive) {
                    validateRemaining(c);
                    recoverLease(c);
                    stage=Stage.PLOT;
                    return busy("미완료 벌목·재식재 이어하기");
                }
                if (!once(c) && (c.profile().loggingMode==LoggingMode.ONCE_ONLY
                    || c.world().tick()<nextCheckTick
                    || c.profile().loggingMode==LoggingMode.DAILY_GROWN
                        && day(c)<c.profile().nextEligibleDay.getOrDefault(LoggingRules.DUE_KEY,Long.MIN_VALUE))) return WorkResult.idle();
                List<LoggingPlot> plots=new ArrayList<>(c.profile().loggingPlots);
                plots.sort(Comparator.comparingDouble(p -> c.world().player().distance(p.corner())));
                if (initialObservationDay!=day(c)) { initialPlotObservations.clear(); initialObservationDay=day(c); }
                List<Pos> ready=new ArrayList<>(), repair=new ArrayList<>();
                boolean allGrown=true;
                for (LoggingPlot candidate:plots) {
                    if (candidate.plantingPositions().stream().allMatch(c.world()::loaded)) {
                        inspect(c,candidate);
                        initialPlotObservations.put(candidate.corner(),candidate.plantingPositions().stream().map(p -> c.world().block(p).id()).toList());
                    }
                    List<String> observed=initialPlotObservations.get(candidate.corner());
                    if (observed==null) return observePlot(c,candidate);
                    boolean grown=observed.stream().allMatch(LoggingRules.LOG::equals);
                    allGrown &= grown;
                    if (grown) ready.add(candidate.corner());
                    else if (observed.stream().anyMatch(id -> id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                        || id.equals(LoggingRules.CHOPPED_LOG))) repair.add(candidate.corner());
                }
                List<Pos> selected=new ArrayList<>(repair);
                if (once(c) || c.profile().loggingMode!=LoggingMode.ALL_GROWN || allGrown) selected.addAll(ready);
                boolean cleanup=LoggingRules.count(c.world(),LoggingRules.LOG)>0 || LoggingRules.count(c.world(),LoggingRules.FIRE_LOG)>0
                    || LoggingRules.count(c.world(),LoggingRules.BERRY)>0 || LoggingRules.count(c.world(),LoggingRules.TWIG)>0;
                if (selected.isEmpty() && !cleanup) { initialPlotObservations.clear(); nextCheckTick=c.world().tick()+c.profile().loggingCheckTicks; return WorkResult.idle(); }
                begin(c,selected); observationWindow.clear(); stage=Stage.PLOT;
                return busy("벌목 대상과 재식재 의무 저장 완료");
            }
            case PLOT -> {
                validateRemaining(c);
                if (c.profile().loggingRemainingPlots.isEmpty()) { observationWindow.clear(); stage=Stage.WASTE; return busy("전체 재식재 확인"); }
                Pos corner=nextPlot(c);
                plot=c.profile().loggingPlots.stream().filter(p -> p.corner().equals(corner)).findFirst().orElseThrow();
                if (plot.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) return observePlot(c,plot);
                inspect(c,plot); chopStrokes=0; saplingWaitUntil=-1; choppingApproach=invisiblePlots.get(corner);
                plantingStanceReady=false; plantingOrder=List.of();
                // A mixed sapling/log plot can contain a newly regrown planting. Never recut it on resume.
                if (c.profile().loggingReplantingPlots.contains(corner)
                    || plot.plantingPositions().stream().anyMatch(p -> c.world().block(p).id().equals(LoggingRules.SAPLING))) {
                    markReplanting(c,corner); stage=Stage.PLANT;
                }
                else stage=Stage.CHOP;
                return busy("구역 "+plot.name()+" 확인");
            }
            case CHOP -> {
                if (plot.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) return observePlot(c,plot);
                inspect(c,plot);
                List<Pos> stumps=plot.plantingPositions().stream().filter(p -> LoggingRules.stump(c.world().block(p))).toList();
                // Installed FallingTrees uses a four-second fall lifetime. This is a fixed
                // settling delay, never a requirement to observe or count dropped items.
                if (stumps.isEmpty()) {
                    // A genuinely felled tree can expose a neighbour's canopy. A selected
                    // axe, FOUND stance or partial chop acknowledgement cannot do this.
                    clearVisibilitySweep(); markReplanting(c,plot.corner());
                    settleUntil=c.world().tick()+80; stage=Stage.SETTLE; return busy("벌목 후 잠시 대기");
                }
                int axe=c.profile().loggingAxeHotbarSlot;
                if (axe<0 || axe==c.profile().hoeHotbarSlot || !item(c,axe).is(LoggingRules.AXE)
                    || item(c,axe).durability()<=1 || !c.world().loggingAxe(axe)) return fail("등록한 사용 가능한 네더라이트 도끼가 필요합니다.");
                Pos stump=choppingTarget(c,stumps);
                if (stump==null) return currentProgress();
                if (!approach(c,stump,4)) return currentProgress();
                if (c.world().player().selectedSlot()!=axe) { submit(c,new Action.SelectHotbar(axe),Pending.SELECT); return busy("도끼 선택"); }
                String rejection=LoggingRules.chopRejection(stump,c);
                if (rejection!=null) return fail(rejection);
                actionPos=stump; submit(c,new Action.ChopTree(stump),Pending.CHOP);
                return busy("등록한 밑동 벌목");
            }
            case SETTLE -> {
                c.actions().stopMovement();
                if (c.world().tick()<settleUntil) return busy("벌목 후 잠시 대기");
                // A later tree may provide the seeds for an earlier empty plot.
                // Re-enter the durable FIFO selector only after this fell has settled.
                stage=Stage.PLOT; return busy("먼저 비워 둔 2x2 식재 구역 확인");
            }
            case PLANT -> {
                if (plot.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) return observePlot(c,plot);
                inspect(c,plot);
                if (LoggingRules.partiallyGrown(c.world(),plot))
                    return fail("2x2 재식재 중 일부 나무만 자랐습니다. 미완료 구역을 보존하며 자동 재벌목·추가 식재하지 않습니다.");
                if (c.world().menu().container() || !c.world().menu().carried().empty())
                    return fail("재식재 중 메뉴나 커서가 바뀌었습니다. 식재 의무를 보존하고 중단했습니다.");
                List<Pos> missingCells=plot.plantingPositions().stream().filter(p -> !occupied(c,p)).toList();
                if (missingCells.isEmpty()) { completePlot(c,plot.corner()); plot=null; stage=Stage.PLOT; return busy("4칸 재식재 완료 저장"); }
                if (missingCells.stream().anyMatch(p -> !air(c.world().block(p))))
                    return fail("재식재할 칸에 남은 밑동이 있습니다. 등록 구역을 확인하세요.");
                // Have every currently missing 2x2 planting covered BEFORE the first
                // use. Planting one or two early can let a small tree grow before
                // the other seeds arrive. Existing plantings/logs are never recut.
                int seeds=availableSaplings(c).stream().mapToInt(s -> s.item().count()).sum();
                if (seeds<missingCells.size()) return waitForSaplings(c,seeds,missingCells.size());
                // Find one native stance exposing every remaining soil UP face.
                // Keep that stance and its far-to-near order while all faces remain
                // visible; only replan if a newly planted sapling actually occludes one.
                if (!plantingStanceReady || missingCells.stream().anyMatch(p -> !c.world().canPlantLoggingSapling(p,3.25)))
                    if (!approachPlanting(c,missingCells)) return currentProgress();
                plantingOrder=plantingOrder.stream().filter(missingCells::contains).toList();
                if (plantingOrder.size()!=missingCells.size())
                    return fail("재식재 순서와 남은 등록 칸이 달라졌습니다. 식재 의무를 보존했습니다.");
                Pos missing=plantingOrder.get(0);
                int selected=c.world().player().selectedSlot();
                if (!item(c,selected).is(LoggingRules.SAPLING) || selected==c.profile().hoeHotbarSlot || selected==c.profile().loggingAxeHotbarSlot) {
                    ItemSlot sapling=availableSaplings(c).stream().findFirst().orElse(null);
                    if (sapling==null) return waitForSaplings(c,0,missingCells.size());
                    if (sapling.inventoryIndex()<9) submit(c,new Action.SelectHotbar(sapling.inventoryIndex()),Pending.SELECT);
                    else {
                        int target=-1;
                        for (int slot=0;slot<9;slot++) if (slot!=c.profile().hoeHotbarSlot && slot!=c.profile().loggingAxeHotbarSlot && item(c,slot).empty()) { target=slot; break; }
                        if (target>=0) submit(c,new Action.SwapHotbar(sapling.inventoryIndex(),target),Pending.SWAP);
                        else {
                            if (c.profile().loggingHotbarLease!=null) return fail("임시 단축바에 다른 아이템이 생겼습니다. 원래 아이템을 보존한 채 중지합니다.");
                            for (int slot=0;slot<9;slot++) if (slot!=c.profile().hoeHotbarSlot && slot!=c.profile().loggingAxeHotbarSlot) { target=slot; break; }
                            String fingerprint=c.world().loggingItemFingerprint(target);
                            if (fingerprint==null || !fingerprint.matches("[0-9a-fA-F]{64}")) return fail("임시 단축바 아이템의 정확한 상태를 확인할 수 없습니다.");
                            saveLease(c,new LoggingHotbarLease(sapling.inventoryIndex(),target,item(c,target),fingerprint));
                            submit(c,new Action.SwapHotbar(sapling.inventoryIndex(),target),Pending.BORROW);
                        }
                    }
                    return busy("재식재용 묘목 준비");
                }
                String rejection=LoggingRules.plantRejection(missing,c);
                if (rejection!=null) return fail(rejection);
                actionPos=missing; submit(c,new Action.PlantSapling(missing),Pending.PLANT);
                return busy("등록한 빈 칸에 묘목 재식재");
            }
            case WASTE -> {
                for (LoggingPlot registered:c.profile().loggingPlots)
                    if (registered.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) return observePlot(c,registered);
                for (LoggingPlot registered:c.profile().loggingPlots)
                    if (!LoggingRules.completePlanting(c.world(),registered)) return fail("모든 등록 구역의 2x2 묘목 또는 네 기둥을 확인해야 합니다. 묘목과 가지는 폐기하지 않았습니다.");
                WorkResult quiet=awaitCleanupQuiet(c); if (quiet!=null) return quiet;
                ItemSlot trash=inventory(c).stream()
                    .filter(s -> c.profile().loggingHotbarLease==null || s.inventoryIndex()!=c.profile().loggingHotbarLease.sourceIndex())
                    .filter(s -> s.item().is(LoggingRules.TWIG)
                    || s.item().is(LoggingRules.SAPLING) && LoggingRules.count(c.world(),LoggingRules.SAPLING)-s.item().count()>=c.profile().loggingSaplingReserve).findFirst().orElse(null);
                if (trash!=null) {
                    if (++trashOperations>72) return fail("벌목 부산물 폐기 횟수 한도에 도달했습니다. 재고를 확인하세요.");
                    Action.TrashLogging action=new Action.TrashLogging(trash.inventoryIndex(),trash.item());
                    String rejection=LoggingRules.trashRejection(action,c); if (rejection!=null) return fail(rejection);
                    submit(c,action,Pending.TRASH); return busy("예비 묘목을 남기고 잉여 묘목·가지만 폐기");
                }
                stage=c.profile().loggingHotbarLease==null ? nextAfterWaste(c) : Stage.RESTORE;
                return busy("장작 제작 재료 확인");
            }
            case RESTORE -> {
                LoggingHotbarLease lease=c.profile().loggingHotbarLease;
                if (restoreBeforePlot && (lease==null || !plotRestoreBoundary(c)))
                    return fail("다음 벌목 구역 확인 전 단축바 복원 경계를 확인할 수 없습니다. 복원 의무를 보존했습니다.");
                if (lease==null) { stage=nextAfterWaste(c); return busy("단축바 복원 완료"); }
                if (lease.stage()!=LoggingHotbarLease.Stage.PARKED || !parked(c,lease))
                    return fail("빌린 단축바나 보관한 원래 아이템이 바뀌었습니다. 임의로 교환하지 않습니다.");
                WorkResult quiet=awaitCleanupQuiet(c); if (quiet!=null) return quiet;
                saveLease(c,lease.withStage(LoggingHotbarLease.Stage.RESTORING));
                submit(c,new Action.SwapHotbar(lease.sourceIndex(),lease.hotbarSlot()),Pending.RESTORE);
                return busy("잠시 옮긴 원래 단축바 아이템 복원");
            }
            case CRAFT_OPEN -> {
                if (table==null) table=ModuleSupport.nearest(c,c.profile().pois(PoiKind.LOGGING_CRAFTING_TABLE)).stream().findFirst().orElse(null);
                if (table==null) return fail("장작을 제작할 3x3 제작대를 먼저 등록하세요.");
                if (!approach(c,table.pos(),2.5)) return currentProgress();
                WorkResult quiet=awaitCleanupQuiet(c); if (quiet!=null) return quiet;
                submit(c,new Action.UseBlock(table.pos(),Action.Use.OPEN_CRAFTING),Pending.OPEN);
                return busy("등록한 장작 제작대 열기");
            }
            case CRAFT -> {
                if (!c.world().menu().container() || c.world().menu().id()!=craftingMenu || !c.world().loggingCraftingMenu()
                    || !c.world().menu().carried().empty() || !c.world().loggingCraftingGridEmpty()) return fail("제작대 소유권 또는 빈 제작 격자가 바뀌었습니다.");
                WorkResult quiet=awaitCleanupQuiet(c); if (quiet!=null) return quiet;
                if (LoggingRules.count(c.world(),LoggingRules.LOG)<6) {
                    stage=Stage.CRAFT_CLOSE; submit(c,new Action.CloseContainer(craftingMenu),Pending.CLOSE);
                } else {
                    // Manual placement may produce only one fire log from a
                    // fragmented haul. Bound by 36 full stacks / six ingredients,
                    // not the old recipe-book batch size.
                    if (++craftOperations>36*64/6) return fail("장작 제작 배치 한도에 도달했습니다. 재고를 확인하세요.");
                    submit(c,new Action.CraftFireLogs(table.pos()),Pending.CRAFT);
                }
                return busy("가문비나무 원목 6개당 장작 1개 제작");
            }
            case CRAFT_CLOSE -> { return fail("제작대 닫기 응답을 잃었습니다."); }
            case WOOD -> {
                WorkResult result=wood.tick(c);
                if (result.state()==WorkResult.State.BLOCKED || result.state()==WorkResult.State.DEFERRED) return fail(result.message());
                if (result.state()==WorkResult.State.IDLE) { stage=Stage.BERRIES; return busy("장작·남은 원목 보관 완료"); }
                return busy("장작·남은 원목 보관: "+result.message());
            }
            case BERRIES -> {
                WorkResult result=berries.tick(c);
                if (result.state()==WorkResult.State.BLOCKED || result.state()==WorkResult.State.DEFERRED) return fail(result.message());
                if (result.state()==WorkResult.State.IDLE) { stage=Stage.FINISH; return busy("벌목 베리 배송 투입 완료"); }
                return busy("벌목 베리 배송: "+result.message());
            }
            case FINISH -> {
                if (c.world().menu().container() || !c.world().menu().carried().empty()) return fail("벌목 완료 전 열린 메뉴를 확인하세요.");
                finish(c); reset(); nextCheckTick=c.world().tick()+c.profile().loggingCheckTicks;
                return new WorkResult(WorkResult.State.IDLE,"벌목·재식재·장작 제작·보관·베리 배송 투입 완료");
            }
        }
        throw new IllegalStateException("Unknown logging stage");
    }

    private boolean approach(Context c,Pos target,double reach) {
        approachResult=null;
        if (c.world().menu().container() || !c.world().menu().carried().empty()) { fail("벌목 이동 중 메뉴나 커서가 바뀌었습니다."); return false; }
        if (!c.world().loaded(target)) { approachResult=ModuleSupport.observe(c,target,8,"등록한 벌목 작업 위치 확인"); return false; }
        Navigation.Result result=c.navigation().moveToLogging(target,reach,c);
        if (result==Navigation.Result.BLOCKED) { approachResult=ModuleSupport.navigationResult(c,"등록한 벌목 작업 위치에 접근할 수 없습니다."); return false; }
        if (result!=Navigation.Result.ARRIVED || !c.world().canInteract(target,reach)) return false;
        c.actions().stopMovement(); return true;
    }
    private Pos choppingTarget(Context c,List<Pos> stumps) {
        approachResult=null;
        if (c.world().menu().container() || !c.world().menu().carried().empty()) {
            fail("벌목 이동 중 메뉴나 커서가 바뀌었습니다."); c.actions().stopMovement(); return null;
        }
        if (choppingApproach!=null && !choppingApproach.matches(c.world(),stumps)) {
            invisiblePlots.remove(plot.corner()); choppingApproach=null; visibilityRetryAt=-1;
        }
        if (visibilityRetryAt>=0 && (c.world().tick()>=visibilityRetryAt
            || c.world().tick()<visibilityRetryAt-VISIBILITY_RETRY_TICKS)) {
            // The scheduler keeps this module's confirmed phase during the wait.
            // Its 1200-tick retry must examine a fresh canopy, not a cached failure.
            clearVisibilitySweep(); stage=Stage.PLOT;
            approachResult=busy("시야 대기 후 남은 등록 구역 전체를 새로 확인"); return null;
        }
        // Existing actual-eye visibility is sufficient to choose another base of
        // this SAME plot. The dispatch still requires native whole-tree proof.
        // The preflight stops movement. Do not repeat four actual-eye queries
        // outside its slice on every SEARCHING tick; retry/reset starts a fresh
        // check, and dispatch still validates the current eye before any chop.
        if (choppingApproach==null && visibilityScanTick==c.world().tick()) {
            approachResult=busy("다음 등록 구역의 시야 확인 차례 대기"); return null;
        }
        if (choppingApproach==null) {
            visibilityScanTick=c.world().tick();
            for (Pos stump:stumps) if (c.world().canInteract(stump,4)) { choppingApproach=null; return stump; }
        }
        if (choppingApproach==null) choppingApproach=new LoggingApproachSearch(c.world(),plot,stumps);
        if (choppingApproach.status()==LoggingApproachSearch.Status.FOUND) {
            if (c.world().canInteract(choppingApproach.target(),4) || choppingApproach.endpointValid(c.world()))
                return choppingApproach.target();
            // An acknowledged partial chop can shrink the native outline while the
            // block ID/properties stay unchanged. Replan geometry, never replay a use.
            if (!plotRestoreBoundary(c)) {
                fail("벌목 접근 위치가 바뀌었지만 미확인 조작이 남아 다시 탐색할 수 없습니다."); return null;
            }
            LoggingHotbarLease lease=c.profile().loggingHotbarLease;
            if (lease!=null && (lease.stage()!=LoggingHotbarLease.Stage.PARKED || !parked(c,lease))) {
                fail("벌목 재탐색 전 임시 단축바의 원래 아이템을 확인할 수 없습니다. 복원 의무를 보존했습니다."); return null;
            }
            int attempts=staleApproachReplans.getOrDefault(plot.corner(),0);
            if (attempts>=MAX_STALE_APPROACH_REPLANS) {
                fail("새 벌목 진행 없이 접근 시야가 반복해서 바뀌어 재탐색 한도에 도달했습니다. 미완료 구역을 보존했습니다."); return null;
            }
            staleApproachReplans.put(plot.corner(),attempts+1);
            choppingApproach=null; visibilityScanTick=c.world().tick();
            c.actions().stopMovement(); c.navigation().reset();
            if (lease!=null) {
                restoreBeforePlot=true; clearCleanupQuiet(); stage=Stage.RESTORE;
                approachResult=busy("벌목 접근 재탐색 전 빌린 단축바를 먼저 복원");
            } else approachResult=busy("변경된 벌목 밑동 시야를 새로 확인 ("+(attempts+1)+"/"+MAX_STALE_APPROACH_REPLANS+")");
            return null;
        }
        c.actions().stopMovement();
        visibilityScanTick=c.world().tick();
        LoggingApproachSearch.Status state=choppingApproach.advance(c.world());
        if (state==LoggingApproachSearch.Status.FOUND) return choppingApproach.target();
        if (state==LoggingApproachSearch.Status.SEARCHING)
            approachResult=busy("등록한 2x2 밑동의 실제 시야가 있는 접근 위치 확인 ("+choppingApproach.checkedStances()+"/"+choppingApproach.candidateCount()+")");
        else if (state==LoggingApproachSearch.Status.UNLOADED)
            approachResult=WorkResult.deferred("벌목 접근 후보에 로드되지 않은 지형이 있습니다. 시야 불가로 단정하지 않고 미완료 구역을 보존합니다.");
        else if (state==LoggingApproachSearch.Status.CHANGED)
            approachResult=WorkResult.deferred("벌목 밑동 또는 월드 관측이 바뀌었습니다. 미완료 구역을 보존하고 다시 확인합니다.");
        else {
            invisiblePlots.put(plot.corner(),choppingApproach);
            if (c.profile().loggingHotbarLease!=null) {
                LoggingHotbarLease lease=c.profile().loggingHotbarLease;
                if (!plotRestoreBoundary(c) || lease.stage()!=LoggingHotbarLease.Stage.PARKED || !parked(c,lease)) {
                    fail("시야 대기 전 빌린 단축바의 원래 아이템과 복원 경계를 확인할 수 없습니다. 복원 의무를 보존했습니다."); return null;
                }
                // A negative visibility proof never waives custody. Use the normal
                // checkpointed native inverse, then resume selection, not cleanup.
                restoreBeforePlot=true; clearCleanupQuiet(); stage=Stage.RESTORE;
                approachResult=busy("다음 구역 확인 전 빌린 단축바를 먼저 복원"); return null;
            }
            if (resourceReadiness(c)==ResourceReadiness.UNSAFE) {
                fail("벌목 시야 대기의 안전한 작업 경계를 확인할 수 없습니다. 미완료 구역을 보존하고 중지합니다."); return null;
            }
            Pos next=nextPlot(c);
            if (!next.equals(plot.corner())) {
                stage=Stage.PLOT;
                approachResult=busy("시야가 막힌 구역은 보존하고 다음 등록 구역 확인"); return null;
            }
            if (visibilityRetryAt<0) visibilityRetryAt=Math.addExact(c.world().tick(),VISIBILITY_RETRY_TICKS);
            if (resourceReadiness(c)!=ResourceReadiness.WAITING)
                fail("벌목 시야 대기의 안전한 작업 경계를 확인할 수 없습니다. 미완료 구역을 보존하고 중지합니다.");
            else approachResult=WorkResult.resourceWait("벌목 시야 대기: 등록한 2x2 밑동을 볼 수 있는 안전한 격자 위치가 없습니다. "
                +"미완료 구역을 보존한 채 "+(once(c) ? "이 작업은 대기하고 " : "다른 작업을 진행하고 ")
                +"1200틱 후 다시 확인합니다. 잎·다른 블록은 임의로 제거하지 않습니다.");
        }
        return null;
    }
    private void clearVisibilitySweep() {
        invisiblePlots.clear(); staleApproachReplans.clear(); choppingApproach=null; visibilityRetryAt=-1; visibilityScanTick=Long.MIN_VALUE;
    }
    private boolean plotRestoreBoundary(Context c) {
        return pending==null && ticket<0 && c.profile().loggingRunActive && c.world().player().onGround()
            && !c.actions().busy() && c.actions().pauseReason()==null && c.world().menu()!=null
            && !c.world().menu().container() && c.world().menu().carried().empty() && !MachineOutputLedger.hasPending(c);
    }
    private boolean approachPlanting(Context c,List<Pos> remaining) {
        approachResult=null;
        plantingStanceReady=false; plantingOrder=List.of();
        if (remaining.stream().anyMatch(p -> !c.world().loaded(p) || !c.world().loaded(p.offset(0,-1,0)))) {
            fail("재식재할 등록 칸의 청크가 로드되지 않았습니다."); return false;
        }
        Navigation.Result result=c.navigation().moveToLoggingPlanting(remaining,c);
        if (result==Navigation.Result.BLOCKED) {
            fail(ModuleSupport.navigationFailure(c,"남은 2x2 식재 칸의 윗면을 함께 볼 수 있는 위치에 접근할 수 없습니다.")); return false;
        }
        if (result!=Navigation.Result.ARRIVED || remaining.stream().anyMatch(p -> !c.world().canPlantLoggingSapling(p,3.25))) return false;
        c.actions().stopMovement();
        PlayerState stance=c.world().player();
        plantingOrder=remaining.stream().sorted(Comparator.<Pos>comparingDouble(stance::distance).reversed()
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z).thenComparingInt(Pos::y)).toList();
        plantingStanceReady=true;
        return true;
    }
    private WorkResult observePlot(Context c,LoggingPlot target) {
        Pos missing=target.plantingPositions().stream().filter(p -> !c.world().loaded(p)).findFirst().orElse(target.corner());
        if (stage==Stage.START || stage==Stage.WASTE) return observationWindow.observe(c,missing,8,"벌목 구역 "+target.name()+" 관측");
        return ModuleSupport.observe(c,missing,8,"벌목 구역 "+target.name()+" 관측");
    }
    private WorkResult currentProgress() { return approachResult!=null ? approachResult : failure==null ? busy("등록한 작업 위치로 이동") : WorkResult.blocked(failure); }
    private static List<ItemSlot> availableSaplings(Context c) {
        return inventory(c).stream().filter(s -> s.item().is(LoggingRules.SAPLING)
            && s.inventoryIndex()!=c.profile().hoeHotbarSlot && s.inventoryIndex()!=c.profile().loggingAxeHotbarSlot).toList();
    }
    private Pos nextPlot(Context c) {
        // Actual manual felling/planting is not an automation receipt. Save newly
        // observed empty/sapling-only registered bases as obligations, never as success.
        for (Pos corner:c.profile().loggingRemainingPlots) {
            LoggingPlot candidate=registeredPlot(c,corner);
            if (candidate.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) continue;
            inspect(c,candidate);
            long trunks=candidate.plantingPositions().stream().filter(p -> c.world().block(p).id().equals(LoggingRules.LOG)).count();
            boolean chopped=candidate.plantingPositions().stream().anyMatch(p -> c.world().block(p).id().equals(LoggingRules.CHOPPED_LOG));
            boolean sapling=candidate.plantingPositions().stream().anyMatch(p -> c.world().block(p).id().equals(LoggingRules.SAPLING));
            if (c.profile().loggingReplantingPlots.contains(corner)) {
                if (LoggingRules.partiallyGrown(c.world(),candidate) || chopped)
                    throw new IllegalStateException("미완료 2x2 구역에 일부 나무나 밑동이 남았습니다. 자동 재벌목하지 않습니다.");
            } else if (!chopped && trunks==0) markReplanting(c,corner);
            else if (sapling || !chopped && trunks!=4)
                throw new IllegalStateException("등록한 2x2 나무가 일부만 남거나 묘목과 섞였습니다. 건드리지 않고 확인을 기다립니다.");
        }
        invisiblePlots.entrySet().removeIf(entry -> !c.profile().loggingRemainingPlots.contains(entry.getKey())
            || c.profile().loggingReplantingPlots.contains(entry.getKey())
            || registeredPlot(c,entry.getKey()).plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))
            || !entry.getValue().matches(c.world(),registeredPlot(c,entry.getKey()).plantingPositions().stream()
                .filter(p -> LoggingRules.stump(c.world().block(p))).toList()));
        int seeds=availableSaplings(c).stream().mapToInt(s -> s.item().count()).sum();
        for (Pos corner:c.profile().loggingReplantingPlots) {
            LoggingPlot candidate=registeredPlot(c,corner);
            if (candidate.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) {
                if (seeds>=4) return corner; // Observation, not a planting permission.
            } else {
                long missing=candidate.plantingPositions().stream().filter(p -> air(c.world().block(p))).count();
                if (seeds>=missing) return corner;
            }
            break; // Strict oldest-first: a smaller newer obligation cannot spend its seeds.
        }
        // Only existing, unprocessed batch members are considered. Native tree
        // footprint validation and the registered axe still guard every actual cut.
        for (Pos corner:c.profile().loggingRemainingPlots)
            if (!c.profile().loggingReplantingPlots.contains(corner) && !invisiblePlots.containsKey(corner)) return corner;
        // All unprocessed trees were checked once. Anchor the wait to a real
        // negative proof rather than bouncing between it and a seed-short PLANT.
        for (Pos corner:c.profile().loggingRemainingPlots)
            if (invisiblePlots.containsKey(corner)) return corner;
        return c.profile().loggingReplantingPlots.get(0);
    }
    private static LoggingPlot registeredPlot(Context c,Pos corner) {
        return c.profile().loggingPlots.stream().filter(p -> p.corner().equals(corner)).findFirst().orElseThrow();
    }
    private WorkResult waitForSaplings(Context c,int available,int required) {
        c.actions().stopMovement();
        saplingWaitRequired=required;
        Pos next=nextPlot(c);
        if (!next.equals(plot.corner())) {
            stage=Stage.PLOT;
            return busy("묘목이 부족한 2x2 구역은 남겨 두고 다음 등록 구역 확인");
        }
        // Existing 80-tick falling animation delay is unchanged. This is a further
        // maximum 400 client ticks (20 seconds at 20 TPS) from first missing seed,
        // shared by the entire plot, not renewed per empty cell or partial pickup.
        if (saplingWaitUntil<0) saplingWaitUntil=Math.addExact(c.world().tick(),SAPLING_WAIT_TICKS);
        if (c.world().tick()>=saplingWaitUntil) {
            if (resourceReadiness(c)!=ResourceReadiness.WAITING)
                return fail("재식재 재료 대기 상태를 안전하게 확인할 수 없습니다. 미완료 구역을 보존했습니다.");
            return WorkResult.resourceWait("벌목 재식재 보류: 가문비나무 묘목 "+available+"/"+required
                +"개. 2x2 미완료 구역을 보존하며 묘목 보충 후 다시 확인합니다.");
        }
        return busy("가문비나무 묘목 도착 대기 ("+available+"/"+required+"개, "+((saplingWaitUntil-c.world().tick()+19)/20)+"초 남음)");
    }
    @Override public ResourceReadiness resourceReadiness(Context c) {
        // No menu actions, world changes, saved deadlines or ground-item guesses here.
        if (failure!=null || stage!=Stage.PLANT && stage!=Stage.CHOP || pending!=null || ticket>=0 || plot==null
            || !c.profile().loggingRunActive || c.profile().loggingHotbarLease!=null)
            return ResourceReadiness.UNSAFE;
        try { validateRemaining(c); }
        catch (RuntimeException invalid) { return ResourceReadiness.UNSAFE; }
        if (!c.profile().loggingPlots.contains(plot) || !c.profile().loggingRemainingPlots.contains(plot.corner())) return ResourceReadiness.UNSAFE;
        if (stage==Stage.CHOP) {
            if (c.profile().loggingReplantingPlots.contains(plot.corner()) || choppingApproach==null
                || choppingApproach.status()!=LoggingApproachSearch.Status.NO_VISIBLE_STANCE
                || !c.world().player().onGround() || c.actions().busy() || c.actions().pauseReason()!=null
                || c.world().menu()==null || c.world().menu().container() || !c.world().menu().carried().empty()
                || MachineOutputLedger.hasPending(c) || plot.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p)))
                return ResourceReadiness.UNSAFE;
            List<Pos> stumps=plot.plantingPositions().stream().filter(p -> LoggingRules.stump(c.world().block(p))).toList();
            if (!choppingApproach.matches(c.world(),stumps)) return ResourceReadiness.UNSAFE;
        } else if (!c.profile().loggingReplantingPlots.contains(plot.corner())) return ResourceReadiness.UNSAFE;
        boolean unprocessed=false,unobservedNegative=false;
        for (Pos corner:c.profile().loggingRemainingPlots) {
            LoggingPlot candidate=registeredPlot(c,corner);
            LoggingApproachSearch negative=invisiblePlots.get(corner);
            if (candidate.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p))) {
                // Another consumer can unload a non-current negative. Wake for
                // ordinary observation, not a new all-blocked/safe-sleep claim.
                if (negative!=null) unobservedNegative=true;
                continue;
            }
            if (negative!=null && !negative.matches(c.world(),candidate.plantingPositions().stream()
                .filter(p -> LoggingRules.stump(c.world().block(p))).toList())) return ResourceReadiness.UNSAFE;
            try { inspect(c,candidate); }
            catch (RuntimeException changed) { return ResourceReadiness.UNSAFE; }
            long trunks=candidate.plantingPositions().stream().filter(p -> c.world().block(p).id().equals(LoggingRules.LOG)).count();
            boolean chopped=candidate.plantingPositions().stream().anyMatch(p -> c.world().block(p).id().equals(LoggingRules.CHOPPED_LOG));
            boolean sapling=candidate.plantingPositions().stream().anyMatch(p -> c.world().block(p).id().equals(LoggingRules.SAPLING));
            if (c.profile().loggingReplantingPlots.contains(corner)) {
                if (trunks>0 && trunks<4 || chopped) return ResourceReadiness.UNSAFE;
            } else {
                if (sapling && trunks>0 || !chopped && trunks>0 && trunks<4) return ResourceReadiness.UNSAFE;
                if (negative==null) unprocessed=true;
            }
        }
        List<ItemSlot> stock=inventory(c).stream().sorted(Comparator.comparingInt(ItemSlot::inventoryIndex)).toList();
        if (stock.size()!=36) return ResourceReadiness.UNSAFE;
        for (int i=0;i<36;i++) if (stock.get(i).inventoryIndex()!=i || stock.get(i).item()==null) return ResourceReadiness.UNSAFE;
        int seeds=availableSaplings(c).stream().mapToInt(s -> s.item().count()).sum();
        if (unprocessed || unobservedNegative) return ResourceReadiness.READY;
        if (stage==Stage.CHOP) {
            if (!c.profile().loggingReplantingPlots.isEmpty()) {
                LoggingPlot oldest=registeredPlot(c,c.profile().loggingReplantingPlots.get(0));
                long missing=oldest.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p)) ? 4
                    : oldest.plantingPositions().stream().filter(p -> air(c.world().block(p))).count();
                if (seeds>=missing) return ResourceReadiness.READY;
            }
            return visibilityRetryAt<0 || c.world().tick()>=visibilityRetryAt || c.world().tick()<visibilityRetryAt-VISIBILITY_RETRY_TICKS
                ? ResourceReadiness.READY : ResourceReadiness.WAITING;
        }
        // Other work can unload this site. Keep its existing obligation; the normal
        // PLANT observation path revalidates every cell before any resumed use.
        if (plot.plantingPositions().stream().anyMatch(p -> !c.world().loaded(p)))
            return saplingWaitRequired>0 && seeds>=saplingWaitRequired ? ResourceReadiness.READY : ResourceReadiness.WAITING;
        if (LoggingRules.partiallyGrown(c.world(),plot)) return ResourceReadiness.UNSAFE;
        int missing=0;
        for (Pos cell:plot.plantingPositions()) {
            BlockData block=c.world().block(cell);
            if (air(block)) missing++;
            else if (!block.id().equals(LoggingRules.SAPLING) && !block.id().equals(LoggingRules.LOG))
                return ResourceReadiness.UNSAFE;
        }
        return seeds>=missing ? ResourceReadiness.READY : ResourceReadiness.WAITING;
    }
    private void submit(Context c,Action action,Pending kind) { pending=kind; ticket=c.actions().submit(action); }
    private static boolean once(Context c) { return c.session().oneShotFeature==Feature.LOGGING; }
    private static long day(Context c) { return Math.floorDiv(c.world().dayTime(),24000L); }
    private static List<ItemSlot> inventory(Context c) { return c.world().inventory().stream().filter(s -> s.player() && s.inventoryIndex()>=0 && s.inventoryIndex()<36).toList(); }
    private static ItemData item(Context c,int index) { return inventory(c).stream().filter(s -> s.inventoryIndex()==index).map(ItemSlot::item).findFirst().orElse(ItemData.EMPTY); }
    private static boolean air(BlockData block) { return block.id().equals("minecraft:air") || block.id().equals("minecraft:cave_air") || block.id().equals("minecraft:void_air"); }
    private static boolean occupied(Context c,Pos p) { return c.world().loaded(p) && (c.world().block(p).id().equals(LoggingRules.SAPLING) || c.world().block(p).id().equals(LoggingRules.LOG)); }
    private WorkResult awaitCleanupQuiet(Context c) {
        long now=c.world().tick();
        if (c.world().menu()==null || !c.world().menu().carried().empty()) return fail("벌목 정리 전 커서의 아이템을 확인하세요.");
        List<ItemSlot> slots=inventory(c).stream().sorted(Comparator.comparingInt(ItemSlot::inventoryIndex)).toList();
        if (slots.size()!=36) return fail("벌목 정리 전 일반 인벤토리 36칸을 정확히 확인할 수 없습니다.");
        for(int i=0;i<36;i++) if (slots.get(i).inventoryIndex()!=i || slots.get(i).item()==null)
            return fail("벌목 정리 전 인벤토리 슬롯 대응이 바뀌었습니다.");
        List<ItemData> snapshot=slots.stream().map(ItemSlot::item).toList();
        if (cleanupWindowStart<0 && !cleanupQuietApproved) cleanupWindowStart=now;
        if (cleanupWindowStart>=0 && now-cleanupWindowStart>=CLEANUP_QUIET_TIMEOUT)
            return fail("벌목 정리 전 재고가 20초 동안 안정되지 않았습니다. 폐기·복원·제작을 보내지 않고 미완료 작업을 보존합니다.");
        boolean same=snapshot.equals(cleanupInventory);
        boolean consecutive=cleanupSampleTick>=0 && now-cleanupSampleTick==1;
        if (!same || cleanupSampleTick!=now && !consecutive) {
            if (cleanupWindowStart<0) cleanupWindowStart=now;
            cleanupInventory=snapshot; cleanupFingerprints=cleanupFingerprints(c,snapshot);
            cleanupQuietTicks=0; cleanupQuietApproved=false;
        } else if (consecutive && !cleanupQuietApproved) cleanupQuietTicks++;
        cleanupSampleTick=now;
        if (cleanupQuietApproved) return null;
        if (cleanupQuietTicks>=CLEANUP_QUIET_TICKS) {
            // Hash only at the baseline and quiet-window boundary, not 36 slots
            // every tick. Unknown adapters still verify the reduced item/count map.
            Map<Integer,String> fingerprints=cleanupFingerprints(c,snapshot);
            if (fingerprints.equals(cleanupFingerprints)) {
                cleanupQuietApproved=true; cleanupWindowStart=-1; return null;
            }
            cleanupFingerprints=fingerprints; cleanupQuietTicks=0;
        }
        c.actions().stopMovement();
        return busy("정리 전 인벤토리 안정화 확인 ("+cleanupQuietTicks+"/20틱)");
    }
    private static Map<Integer,String> cleanupFingerprints(Context c,List<ItemData> inventory) {
        Map<Integer,String> result=new HashMap<>();
        for(int i=0;i<inventory.size();i++) if (!inventory.get(i).empty()) {
            String fingerprint=c.world().loggingItemFingerprint(i);
            if (fingerprint!=null) {
                if (!fingerprint.matches("[0-9a-fA-F]{64}")) throw new IllegalStateException("인벤토리 상태 지문을 확인할 수 없습니다.");
                result.put(i,fingerprint);
            }
        }
        return Map.copyOf(result);
    }
    private void clearCleanupQuiet() {
        cleanupInventory=null; cleanupFingerprints=Map.of(); cleanupWindowStart=cleanupSampleTick=-1;
        cleanupQuietTicks=0; cleanupQuietApproved=false;
    }
    private static void inspect(Context c,LoggingPlot p) {
        for (Pos cell:p.plantingPositions()) {
            if (!c.world().loaded(cell)) throw new IllegalStateException("벌목 구역 청크가 로드되지 않았습니다.");
            BlockData block=c.world().block(cell);
            if (!air(block) && !LoggingRules.stump(block) && !block.id().equals(LoggingRules.SAPLING))
                throw new IllegalStateException("등록 식재 칸에 다른 블록이 있습니다. 임의로 제거하지 않습니다.");
        }
    }
    private static void validateRemaining(Context c) {
        Set<Pos> registered=new HashSet<>(); c.profile().loggingPlots.forEach(p -> registered.add(p.corner()));
        if (!c.profile().loggingRunActive || c.profile().loggingRemainingPlots==null
            || new HashSet<>(c.profile().loggingRemainingPlots).size()!=c.profile().loggingRemainingPlots.size()
            || !registered.containsAll(c.profile().loggingRemainingPlots)
            || c.profile().loggingReplantingPlots==null
            || !c.profile().loggingRemainingPlots.containsAll(c.profile().loggingReplantingPlots)) throw new IllegalStateException("저장된 벌목 대상과 등록 구역이 다릅니다.");
    }
    private static void begin(Context c,List<Pos> selected) {
        boolean oldActive=c.profile().loggingRunActive; List<Pos> oldRemaining=c.profile().loggingRemainingPlots;
        c.profile().loggingRunActive=true; c.profile().loggingRemainingPlots=new ArrayList<>(selected);
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) { c.profile().loggingRunActive=oldActive; c.profile().loggingRemainingPlots=oldRemaining; throw failure; }
    }
    private static void completePlot(Context c,Pos corner) {
        LoggingPlot completed=c.profile().loggingPlots.stream().filter(p -> p.corner().equals(corner)).findFirst().orElseThrow();
        if (!LoggingRules.completePlanting(c.world(),completed)) throw new IllegalStateException("2x2 재식재가 같은 상태의 네 칸으로 완성되지 않았습니다.");
        List<Pos> oldRemaining=c.profile().loggingRemainingPlots;
        List<Pos> oldReplanting=c.profile().loggingReplantingPlots;
        List<Pos> remaining=new ArrayList<>(oldRemaining); remaining.remove(corner); c.profile().loggingRemainingPlots=remaining;
        List<Pos> replanting=new ArrayList<>(oldReplanting); replanting.remove(corner); c.profile().loggingReplantingPlots=replanting;
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) { c.profile().loggingRemainingPlots=oldRemaining; c.profile().loggingReplantingPlots=oldReplanting; throw failure; }
    }
    private static void markReplanting(Context c,Pos corner) {
        if (c.profile().loggingReplantingPlots.contains(corner)) return;
        List<Pos> before=c.profile().loggingReplantingPlots, next=new ArrayList<>(before);
        next.add(corner); c.profile().loggingReplantingPlots=next;
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) { c.profile().loggingReplantingPlots=before; throw failure; }
    }
    private static Stage nextAfterWaste(Context c) { return LoggingRules.count(c.world(),LoggingRules.LOG)>=6 ? Stage.CRAFT_OPEN : Stage.WOOD; }
    private static boolean saplingsOrEmpty(ItemData item) { return item.empty() || item.is(LoggingRules.SAPLING); }
    private static boolean parked(Context c,LoggingHotbarLease lease) {
        return lease.original().equals(item(c,lease.sourceIndex()))
            && lease.fingerprint().equals(c.world().loggingItemFingerprint(lease.sourceIndex()))
            && saplingsOrEmpty(item(c,lease.hotbarSlot()));
    }
    private static boolean restored(Context c,LoggingHotbarLease lease) {
        return lease.original().equals(item(c,lease.hotbarSlot()))
            && lease.fingerprint().equals(c.world().loggingItemFingerprint(lease.hotbarSlot()))
            && saplingsOrEmpty(item(c,lease.sourceIndex()));
    }
    private static void saveLease(Context c,LoggingHotbarLease lease) {
        LoggingHotbarLease before=c.profile().loggingHotbarLease; c.profile().loggingHotbarLease=lease;
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) { c.profile().loggingHotbarLease=before; throw failure; }
    }
    private static void recoverLease(Context c) {
        LoggingHotbarLease lease=c.profile().loggingHotbarLease;
        if (lease==null) return;
        if (lease.stage()==LoggingHotbarLease.Stage.RESTORING) {
            if (!restored(c,lease)) throw new IllegalStateException("단축바 복원 요청의 결과가 불명확합니다. 같은 교환을 재전송하지 않습니다.");
            saveLease(c,null);
        } else if (parked(c,lease)) {
            if (lease.stage()==LoggingHotbarLease.Stage.PREPARED) saveLease(c,lease.withStage(LoggingHotbarLease.Stage.PARKED));
        } else throw new IllegalStateException("임시 단축바 교환의 결과가 불명확합니다. 원래 아이템을 확인하세요.");
    }
    private static void finish(Context c) {
        for (LoggingPlot registered:c.profile().loggingPlots)
            if (!LoggingRules.completePlanting(c.world(),registered)) throw new IllegalStateException("2x2 재식재 상태가 바뀌어 벌목 완료를 저장하지 않습니다.");
        if (!c.profile().loggingRemainingPlots.isEmpty() || !c.profile().loggingReplantingPlots.isEmpty()
            || c.profile().loggingHotbarLease!=null) throw new IllegalStateException("재식재 또는 단축바 복원이 아직 끝나지 않았습니다.");
        long due=Math.addExact(day(c),c.profile().loggingCycleDays);
        boolean oldActive=c.profile().loggingRunActive; Long oldDue=c.profile().nextEligibleDay.get(LoggingRules.DUE_KEY);
        c.profile().loggingRunActive=false; c.profile().nextEligibleDay.put(LoggingRules.DUE_KEY,due);
        try { c.checkpoint().run(); }
        catch (RuntimeException failure) {
            c.profile().loggingRunActive=oldActive;
            if (oldDue==null) c.profile().nextEligibleDay.remove(LoggingRules.DUE_KEY); else c.profile().nextEligibleDay.put(LoggingRules.DUE_KEY,oldDue);
            throw failure;
        }
    }
    private static WorkResult busy(String text) { return WorkResult.busy("벌목: "+text); }
    private WorkResult fail(String text) { failure=text; return WorkResult.blocked(text); }
    @Override public void reset() {
        stage=Stage.START; restoreBeforePlot=false; pending=null; ticket=-1; actionPos=null; plot=null; table=null; craftingMenu=-1;
        // Scheduler resets must not turn the bounded ALL_GROWN readiness poll into
        // a per-tick scan. Explicit one-shot and durable active resumes bypass it.
        chopStrokes=trashOperations=craftOperations=0; settleUntil=0; saplingWaitUntil=-1; saplingWaitRequired=0; failure=null;
        plantingStanceReady=false; plantingOrder=List.of();
        approachResult=null; clearVisibilitySweep(); initialPlotObservations.clear(); initialObservationDay=Long.MIN_VALUE;
        observationWindow.clear();
        clearCleanupQuiet();
        // Explicit stop/reconnect may start a fresh bounded seed wait. The durable
        // replant phase, remaining plots and hotbar lease are never cleared here.
        wood.reset(); berries.reset();
    }

    private static final class LoggingDeposit extends DepositModule {
        private final boolean shipping;
        LoggingDeposit(boolean shipping) { this.shipping=shipping; }
        @Override public Feature feature() { return Feature.LOGGING; }
        @Override public int priority() { return 80; }
        @Override protected String itemId() { return shipping ? LoggingRules.BERRY : LoggingRules.FIRE_LOG; }
        @Override protected boolean accepts(ItemData item) { return shipping ? LoggingRules.byproduct(item) : LoggingRules.wood(item); }
        @Override protected PoiKind destinationKind() { return shipping ? PoiKind.SHIPPING_BIN : PoiKind.WOOD_CHEST; }
        @Override protected Integer classifier(ItemData item) { return null; }
        @Override protected Navigation.Result approachDestination(Context c,Poi destination) {
            return c.navigation().moveToLogging(destination.pos(),2.5,c);
        }
        @Override protected boolean prepareDestination(Context c,Poi destination,ItemData item) {
            return shipping || c.world().menu().slots().stream().filter(s -> !s.player()).map(ItemSlot::item)
                .allMatch(i -> i.empty() || LoggingRules.wood(i));
        }
    }
}
