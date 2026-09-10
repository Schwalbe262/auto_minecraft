package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Independent orchard pass: inspect registered patches, pick ripe fruit, and store each patch before the next. */
public final class StarfruitModule implements AutomationModule {
    private static final int APPROACH_TICKS=2400,PICKUP_TICKS=20;
    private enum Stage { FIND, APPROACH, EQUIP, VERIFY, PICKUP, STORE }
    private enum Pending { SELECT, FRUIT }
    private Stage stage=Stage.FIND;
    private Pending pending;
    private long ticket=-1,approachSince,verifySince,confirmedDay=Long.MIN_VALUE,passDay=Long.MIN_VALUE;
    private Profile observedProfile;
    private final Set<Pos> confirmedToday=new HashSet<>();
    private FruitPatch patch;
    private Pos target;
    private CommodityStore passStore;
    private final List<Pos> remainingFruit=new ArrayList<>();
    private List<FruitPatch> scheduledPatches=List.of();
    private int patchIndex;
    private boolean runStarted,passIncomplete;
    private boolean passPicked;
    private FruitPatch lastConfirmedPatch;
    private Pos lastConfirmedTarget;
    private CommodityStore lastConfirmedStore;
    private boolean lateCleanup;
    private int fruitBefore;
    private CommodityStorageModule storage;
    private static final int SKIP_TICKS=1200,MAX_SKIPPED_TARGETS=4096;
    private record SkippedFruit(FruitPatch patch,CommodityStore store,long day,long tick) { }
    private final Map<Pos,SkippedFruit> skippedTargets=new LinkedHashMap<>();
    private final Map<String,SkippedFruit> skippedPatches=new LinkedHashMap<>();

    @Override public Feature feature() { return Feature.STARFRUIT; }
    @Override public int priority() { return 80; }

    @Override public WorkResult tick(Context c) {
        if (stage==Stage.FIND) {
            // This module only receives scheduler boundaries. Even a direct caller
            // cannot make it touch an unrelated in-flight action or open menu.
            if (c.actions().busy() || !c.session().allows(c.profile(),feature()) || !clearMenu(c)) return WorkResult.idle();
            long today=Math.floorDiv(c.world().dayTime(),24000L);
            if (observedProfile!=c.profile()) {
                lastConfirmedPatch=null;lastConfirmedTarget=null;lastConfirmedStore=null;
                skippedTargets.clear();skippedPatches.clear();
            }
            if (observedProfile!=c.profile() || confirmedDay!=today) {
                confirmedToday.clear();observedProfile=c.profile();confirmedDay=today;
            }
            skippedTargets.entrySet().removeIf(e->!skipActive(c,e.getKey(),e.getValue()));
            skippedPatches.entrySet().removeIf(e->!skipActive(c,e.getValue().patch().fruits().get(0),e.getValue()));
            if (lateFruitReady(c)) {
                // This is an actual currently held item, not a pickup debt or a
                // prediction. A delayed arrival can use the same still-registered
                // destination without visiting or clicking the fruit again.
                patch=lastConfirmedPatch;target=lastConfirmedTarget;lateCleanup=true;
                storage=new CommodityStorageModule(feature(),patch.storeId(),Set.of(FruitRules.ITEM));
                stage=Stage.STORE;
            } else {
                if (!runStarted) {
                    scheduledPatches=c.profile().fruitPatches==null ? List.of() : c.profile().fruitPatches.stream()
                        .filter(p -> p!=null && p.valid() && validStore(c,p)
                            && today>=c.profile().nextEligibleDay.getOrDefault(FruitRules.inspectionKey(p),Long.MIN_VALUE)
                            && !skipActive(c,p.fruits().get(0),skippedPatches.get(p.id())))
                        .sorted(Comparator.comparingDouble(p -> p.fruits().stream()
                            .mapToDouble(pos -> c.world().player().distance(pos)).min().orElse(Double.POSITIVE_INFINITY)))
                        .toList();
                    runStarted=true;patchIndex=0;
                }
                if (patchIndex>=scheduledPatches.size()) { reset();return WorkResult.idle(); }
                patch=scheduledPatches.get(patchIndex++);
                if (!c.profile().fruitPatches.contains(patch) || !validStore(c,patch))
                    return fail(c,"과수원 구역 또는 저장고 등록이 바뀌었습니다");
                passStore=CommodityStorageRules.store(c.profile(),patch.storeId());passDay=today;
                passPicked=false;passIncomplete=false;lateCleanup=false;
                // Freeze explicit ownership, not a nearby/loaded mask. Distant and
                // initially unloaded registered fruits are inspected by this pass.
                remainingFruit.clear();remainingFruit.addAll(patch.fruits());
                target=patch.fruits().get(0);
                if (!chooseNextFruit(c)) return finishPatch(c);
            }
        }
        if (ticket>=0) {
            ActionOutcome result=c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("스타프루트 서버 확인 대기");
            ticket=-1;
            if (!result.success()) return fail(c,"스타프루트 조작 실패: "+result.message());
            Pending completed=pending;pending=null;
            if (completed==Pending.FRUIT) { verifySince=c.world().tick();stage=Stage.VERIFY; }
        }
        if (stage==Stage.APPROACH) {
            // Long orchard trips may open a door. Await that navigator-owned
            // receipt before inspecting/skipping the target or changing a route.
            // An unrelated busy action still fails at the normal boundary below.
            ActionOutcome travelAction=c.navigation().pendingInteractionOutcome(c);
            if (travelAction!=null) {
                if (!travelAction.done()) { c.actions().stopMovement();return WorkResult.busy("과수원 이동 중 문 조작 응답 대기"); }
                if (!travelAction.success()) return fail(c,"과수원 이동 중 문 조작 실패: "+travelAction.message());
            }
        }
        if (!c.session().allows(c.profile(),feature()) || observedProfile!=c.profile() || !registered(c)) {
            // A registration edit cannot forget a submitted native operation.
            // Let a nested storage action settle, then stop before any next action.
            if (stage==Stage.STORE && c.actions().busy()) return WorkResult.busy("기존 저장 조작 확인 대기");
            return fail(c,"스타프루트 구역 또는 저장고 등록이 바뀌었습니다");
        }
        // The nested deposit owns its own action tickets and acknowledgement wait.
        if (stage==Stage.STORE) {
            WorkResult result=storage.tick(c);
            if (result.state()==WorkResult.State.BUSY) return result;
            if (result.state()!=WorkResult.State.IDLE) { reset();return result; }
            if (lateCleanup) {
                storage=null;lateCleanup=false;stage=Stage.FIND;target=null;patch=null;passStore=null;
                return WorkResult.busy("보유 과일 보관 완료 — 과수원 순회 확인");
            }
            return completePatch(c);
        }
        if (c.actions().busy()) return fail(c,"다른 조작 중이므로 스타프루트 작업을 중지했습니다");
        if (!clearMenu(c)) return fail(c,"스타프루트 작업 중 메뉴 또는 커서가 바뀌었습니다");
        // Settle a submitted FRUIT through VERIFY/PICKUP first. A SELECT receipt
        // may settle too, but never grants a new use after the frozen day changes.
        if ((stage==Stage.APPROACH || stage==Stage.EQUIP) && !samePassDay(c)) {
            c.actions().stopMovement();c.navigation().reset();
            passIncomplete=true;return finishPatch(c);
        }

        switch (stage) {
            case APPROACH -> {
                if (c.world().tick()-approachSince>=APPROACH_TICKS) return skip(c);
                if (!c.world().loaded(target)) {
                    Navigation.Result observed=c.navigation().moveToObserve(target,8,c);
                    if (observed==Navigation.Result.BLOCKED) return skip(c);
                    return WorkResult.busy("등록 과수원으로 이동·열매 상태 확인");
                }
                if (!knownFruitObservation(c.world().block(target),target)) return fail(c,"과수원 열매 상태가 아직 동기화되지 않았습니다");
                if (!targetMature(c)) return inspected(c);
                Navigation.Result result=c.navigation().moveTo(target,4,c);
                if (result==Navigation.Result.BLOCKED) return skip(c);
                if (result==Navigation.Result.ARRIVED) stage=Stage.EQUIP;
            }
            case EQUIP -> {
                c.actions().stopMovement();
                if (!c.world().loaded(target)) { stage=Stage.APPROACH;break; }
                if (!knownFruitObservation(c.world().block(target),target)) return fail(c,"과수원 열매 상태가 아직 동기화되지 않았습니다");
                if (!targetMature(c)) return inspected(c);
                if (!hasPickupRoom(c)) return skip(c);
                int hotbar=safeHotbar(c);
                if (hotbar<0) return skip(c);
                if (c.world().player().selectedSlot()!=hotbar) {
                    submit(c,new Action.SelectHotbar(hotbar),Pending.SELECT);break;
                }
                if (!c.world().canInteract(target,4)) { stage=Stage.APPROACH;break; }
                Action action=new Action.UseBlock(target,Action.Use.FRUIT);
                String rejection=SafetyPolicy.rejection(action,c);
                if (rejection!=null) return fail(c,rejection);
                fruitBefore=fruitCount(c);
                submit(c,action,Pending.FRUIT);
            }
            case VERIFY -> {
                // An inventory increase or disappearing block is not fruit-use
                // evidence. Require native success AND this same fruit resetting.
                BlockData block=c.world().loaded(target) ? c.world().block(target) : null;
                int age=block==null ? -1 : block.number("age",-1);
                if (block!=null && target.equals(block.pos()) && FruitRules.BLOCK.equals(block.id()) && age>=0 && age<7) {
                    long today=Math.floorDiv(c.world().dayTime(),24000L);
                    if (confirmedDay!=today) { confirmedToday.clear();confirmedDay=today; }
                    confirmedToday.add(target);lastConfirmedPatch=patch;lastConfirmedTarget=target;
                    passPicked=true;
                    lastConfirmedStore=CommodityStorageRules.store(c.profile(),patch.storeId());stage=Stage.PICKUP;
                } else if (c.world().tick()-verifySince>=Math.max(1,c.profile().interactionTimeoutTicks))
                    return fail(c,"같은 스타프루트의 수확 후 성장 상태가 확인되지 않았습니다");
            }
            case PICKUP -> { /* Shared below so an already observed pickup adds no settling delay. */ }
            default -> { }
        }
        if (stage==Stage.PICKUP) {
            c.actions().stopMovement();
            // Delayed ground delivery gets a bounded observation window, not a
            // persistent output debt. Already arrived fruit goes straight to storage.
            if (fruitCount(c)>fruitBefore || c.world().tick()-verifySince>=PICKUP_TICKS) {
                if (chooseNextFruit(c)) return WorkResult.busy("과수원의 다음 등록 열매 확인");
                return finishPatch(c);
            }
        }
        return WorkResult.busy(switch(stage) {
            case APPROACH -> "등록 구역의 익은 스타프루트에 접근";
            case EQUIP -> "스타프루트용 빈손 준비";
            case VERIFY -> "스타프루트 성장 상태 확인";
            case PICKUP -> "스타프루트 도착 관측";
            default -> "스타프루트 보관";
        });
    }

    /** Inspect the frozen registration mask; unknown chunks are approached, never read speculatively. */
    private boolean chooseNextFruit(Context c) {
        if (!samePassDay(c)) { passIncomplete=true;return false; }
        remainingFruit.removeIf(pos -> {
            if (confirmedToday.contains(pos)) return true;
            if (skipActive(c,pos,skippedTargets.get(pos))) { passIncomplete=true;return true; }
            if (!c.world().loaded(pos)) return false;
            BlockData block=c.world().block(pos);
            return knownFruitObservation(block,pos) && !FruitRules.mature(block);
        });
        Pos next=remainingFruit.stream().min(Comparator.comparingDouble(pos -> c.world().player().distance(pos))).orElse(null);
        if (next==null) return false;
        if (safeHotbar(c)<0 || !hasPickupRoom(c)) { passIncomplete=true;return false; }
        remainingFruit.remove(next);target=next;
        approachSince=c.world().tick();stage=Stage.APPROACH;
        return true;
    }
    private WorkResult finishPatch(Context c) {
        remainingFruit.clear();
        if (!passPicked) return completePatch(c);
        storage=new CommodityStorageModule(feature(),patch.storeId(),Set.of(FruitRules.ITEM));
        stage=Stage.STORE;
        WorkResult result=storage.tick(c);
        if (result.state()==WorkResult.State.IDLE) return completePatch(c);
        if (result.state()!=WorkResult.State.BUSY) reset();
        return result;
    }
    private WorkResult completePatch(Context c) {
        if (!registered(c)) return fail(c,"과수원 확인을 저장하기 전에 등록이 바뀌었습니다");
        if (!clearMenu(c) || c.actions().busy() || c.actions().pauseReason()!=null)
            return fail(c,"과수원 완료 전에 보관함과 조작 응답을 확인하세요");
        if (!passIncomplete && samePassDay(c)) {
            String key=FruitRules.inspectionKey(patch);
            Long before=c.profile().nextEligibleDay.put(key,Math.addExact(passDay,1));
            try { c.checkpoint().run(); }
            catch (RuntimeException failure) {
                if (before==null) c.profile().nextEligibleDay.remove(key);else c.profile().nextEligibleDay.put(key,before);
                return fail(c,"과수원 점검일을 저장하지 못했습니다");
            }
            skippedPatches.remove(patch.id());
        } else {
            // Whole-patch backoff prevents several incomplete patches from
            // endlessly reacquiring BUSY ahead of sleep or later routines.
            skippedPatches.put(patch.id(),new SkippedFruit(patch,passStore,
                Math.floorDiv(c.world().dayTime(),24000L),c.world().tick()));
        }
        if (patchIndex>=scheduledPatches.size()) { reset();return WorkResult.idle(); }
        storage=null;stage=Stage.FIND;target=null;patch=null;passStore=null;passPicked=false;
        return WorkResult.busy("과수원 구역 확인·보관 완료 — 다음 등록 구역 확인");
    }
    private WorkResult inspected(Context c) {
        c.actions().stopMovement();c.navigation().reset();
        if (chooseNextFruit(c)) return WorkResult.busy("아직 익지 않은 열매는 두고 다음 등록 열매 확인");
        return finishPatch(c);
    }
    private boolean registered(Context c) {
        return target!=null && patch!=null && patch.equals(FruitRules.patch(c.profile(),target)) && validStore(c,patch)
            && (passStore==null || passStore.equals(CommodityStorageRules.store(c.profile(),patch.storeId())))
            && (!lateCleanup || Objects.equals(lastConfirmedStore,CommodityStorageRules.store(c.profile(),patch.storeId())));
    }
    private boolean lateFruitReady(Context c) {
        return lastConfirmedTarget!=null && lastConfirmedPatch!=null && fruitCount(c)>0
            && lastConfirmedPatch.equals(FruitRules.patch(c.profile(),lastConfirmedTarget))
            && lastConfirmedStore!=null && lastConfirmedStore.equals(CommodityStorageRules.store(c.profile(),lastConfirmedPatch.storeId()));
    }
    private static boolean validStore(Context c,FruitPatch patch) {
        CommodityStore store=CommodityStorageRules.store(c.profile(),patch.storeId());
        return store!=null && store.items().contains(FruitRules.ITEM);
    }
    private boolean samePassDay(Context c) { return passDay==Math.floorDiv(c.world().dayTime(),24000L); }
    private boolean targetMature(Context c) {
        if (!c.world().loaded(target)) return false;
        BlockData block=c.world().block(target);
        return block!=null && target.equals(block.pos()) && FruitRules.mature(block);
    }
    private static boolean knownFruitObservation(BlockData block,Pos pos) {
        if (block==null || !pos.equals(block.pos()) || block.id()==null || block.id().isBlank()
                || "autovalley:unloaded".equals(block.id())) return false;
        return !FruitRules.BLOCK.equals(block.id()) || block.number("age",-1)>=0 && block.number("age",-1)<=7;
    }
    private static boolean clearMenu(Context c) {
        MenuData menu=c.world().menu();return menu!=null && !menu.container() && menu.carried().empty();
    }
    private static boolean safe(ItemData item) { return item!=null && (item.empty() || item.is(FruitRules.ITEM)); }
    private static ItemData explicitSlot(Context c,int index) {
        List<ItemSlot> slots=c.world().inventory().stream().filter(s -> s.player() && s.inventoryIndex()==index).toList();
        return slots.size()==1 ? slots.get(0).item() : null;
    }
    private static int safeHotbar(Context c) {
        int selected=c.world().player().selectedSlot();
        if (selected>=0 && selected<9 && selected!=c.profile().hoeHotbarSlot && safe(explicitSlot(c,selected))) return selected;
        for (int index=0;index<9;index++) {
            if (index!=c.profile().hoeHotbarSlot && safe(explicitSlot(c,index))) return index;
        }
        return -1;
    }
    private static boolean hasPickupRoom(Context c) {
        // Fruit grade is not predicted: one explicitly observed empty slot is
        // sufficient for any grade, unlike a partly filled unrelated-grade stack.
        return c.world().inventory().stream().anyMatch(s -> s.player() && s.inventoryIndex()>=0
            && s.inventoryIndex()<36 && s.item()!=null && s.item().empty());
    }
    private static int fruitCount(Context c) { return ModuleSupport.count(c,i -> i.is(FruitRules.ITEM)); }
    private boolean skipActive(Context c,Pos pos,SkippedFruit skip) {
        return skip!=null && observedProfile==c.profile() && skip.day()==Math.floorDiv(c.world().dayTime(),24000L)
            && c.world().tick()>=skip.tick() && c.world().tick()-skip.tick()<SKIP_TICKS
            && skip.patch().equals(FruitRules.patch(c.profile(),pos))
            && Objects.equals(skip.store(),CommodityStorageRules.store(c.profile(),skip.patch().storeId()));
    }
    private void submit(Context c,Action action,Pending next) { pending=next;ticket=c.actions().submit(action); }
    private WorkResult skip(Context c) {
        // Only a pre-use approach/equipment skip comes here. This is never a
        // receipt resolution or permission to resend an uncertain native use.
        if (target!=null && patch!=null && registered(c)) {
            if (skippedTargets.size()>=MAX_SKIPPED_TARGETS && !skippedTargets.containsKey(target))
                skippedTargets.remove(skippedTargets.keySet().iterator().next());
            skippedTargets.put(target,new SkippedFruit(patch,CommodityStorageRules.store(c.profile(),patch.storeId()),
                Math.floorDiv(c.world().dayTime(),24000L),c.world().tick()));
        }
        c.actions().stopMovement();c.navigation().reset();
        passIncomplete=true;
        if (chooseNextFruit(c)) return WorkResult.busy("접근할 수 없는 열매를 건너뛰고 같은 과수원 구역 확인");
        return finishPatch(c);
    }
    private WorkResult fail(Context c,String message) { c.actions().stopMovement();c.navigation().reset();reset();return WorkResult.blocked(message); }
    @Override public void reset() {
        if (storage!=null) storage.reset();
        stage=Stage.FIND;pending=null;ticket=-1;target=null;patch=null;storage=null;lateCleanup=false;
        remainingFruit.clear();passStore=null;passPicked=false;passDay=Long.MIN_VALUE;passIncomplete=false;
        scheduledPatches=List.of();patchIndex=0;runStarted=false;
        // Confirmed same-day evidence and its destination survive scheduler/one-shot
        // reset. A late arrival is stored only when actually in inventory; no action,
        // borrowed inventory, debt, expected count, or persisted completion is retained.
    }
}
