package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** One opportunistic nearby fruit, followed by its registered commodity deposit. Never patrols a tree. */
public final class StarfruitModule implements AutomationModule {
    private static final double NEARBY_REACH=6;
    private static final int APPROACH_TICKS=100,PICKUP_TICKS=20;
    private enum Stage { FIND, APPROACH, EQUIP, VERIFY, PICKUP, STORE }
    private enum Pending { SELECT, FRUIT }
    private Stage stage=Stage.FIND;
    private Pending pending;
    private long ticket=-1,approachSince,verifySince,confirmedDay=Long.MIN_VALUE;
    private Profile observedProfile;
    private final Set<Pos> confirmedToday=new HashSet<>();
    private FruitPatch patch;
    private Pos target;
    private int fruitBefore;
    private CommodityStorageModule storage;

    @Override public Feature feature() { return Feature.STARFRUIT; }
    @Override public int priority() { return 80; }

    @Override public WorkResult tick(Context c) {
        if (stage==Stage.FIND) {
            // This module only receives scheduler boundaries. Even a direct caller
            // cannot make it touch an unrelated in-flight action or open menu.
            if (c.actions().busy() || !c.session().allows(c.profile(),feature()) || !clearMenu(c)) return WorkResult.idle();
            long today=Math.floorDiv(c.world().dayTime(),24000L);
            if (observedProfile!=c.profile() || confirmedDay!=today) {
                confirmedToday.clear();observedProfile=c.profile();confirmedDay=today;
            }
            if (safeHotbar(c)<0 || !hasPickupRoom(c)) return WorkResult.idle();
            chooseNearby(c);
            if (target==null) return WorkResult.idle();
            approachSince=c.world().tick();stage=Stage.APPROACH;
        }
        if (ticket>=0) {
            ActionOutcome result=c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("스타프루트 서버 확인 대기");
            ticket=-1;
            if (!result.success()) return fail(c,"스타프루트 조작 실패: "+result.message());
            Pending completed=pending;pending=null;
            if (completed==Pending.FRUIT) { verifySince=c.world().tick();stage=Stage.VERIFY; }
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
            if (result.state()!=WorkResult.State.BUSY) reset();
            return result;
        }
        if (c.actions().busy()) return fail(c,"다른 조작 중이므로 스타프루트 작업을 중지했습니다");
        if (!clearMenu(c)) return fail(c,"스타프루트 작업 중 메뉴 또는 커서가 바뀌었습니다");

        switch (stage) {
            case APPROACH -> {
                if (!nearbyMature(c) || c.world().tick()-approachSince>=APPROACH_TICKS) return skip(c);
                Navigation.Result result=c.navigation().moveTo(target,4,c);
                if (result==Navigation.Result.BLOCKED) return skip(c);
                if (result==Navigation.Result.ARRIVED) stage=Stage.EQUIP;
            }
            case EQUIP -> {
                c.actions().stopMovement();
                if (!nearbyMature(c) || !hasPickupRoom(c)) return skip(c);
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
                    confirmedToday.add(target);stage=Stage.PICKUP;
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
                storage=new CommodityStorageModule(feature(),patch.storeId(),Set.of(FruitRules.ITEM));
                stage=Stage.STORE;
                WorkResult result=storage.tick(c);
                if (result.state()!=WorkResult.State.BUSY) reset();
                return result;
            }
        }
        return WorkResult.busy(switch(stage) {
            case APPROACH -> "지나가는 길의 익은 스타프루트에 접근";
            case EQUIP -> "스타프루트용 빈손 준비";
            case VERIFY -> "스타프루트 성장 상태 확인";
            case PICKUP -> "스타프루트 도착 관측";
            default -> "스타프루트 보관";
        });
    }

    private void chooseNearby(Context c) {
        target=null;patch=null;
        if (c.profile().fruitPatches==null) return;
        double nearest=NEARBY_REACH;
        for (FruitPatch candidate:c.profile().fruitPatches) {
            if (candidate==null || !candidate.valid() || !validStore(c,candidate)) continue;
            for (Pos pos:candidate.fruits()) {
                double distance=c.world().player().distance(pos);
                if (distance>nearest || confirmedToday.contains(pos) || !c.world().loaded(pos)) continue;
                // Loaded + nearby is checked before reading any native block.
                BlockData block=c.world().block(pos);
                if (block!=null && pos.equals(block.pos()) && FruitRules.mature(block)
                        && candidate.equals(FruitRules.patch(c.profile(),pos))) {
                    target=pos;patch=candidate;nearest=distance;
                }
            }
        }
    }
    private boolean registered(Context c) {
        return target!=null && patch!=null && patch.equals(FruitRules.patch(c.profile(),target)) && validStore(c,patch);
    }
    private static boolean validStore(Context c,FruitPatch patch) {
        CommodityStore store=CommodityStorageRules.store(c.profile(),patch.storeId());
        return store!=null && store.items().contains(FruitRules.ITEM);
    }
    private boolean nearbyMature(Context c) {
        if (c.world().player().distance(target)>NEARBY_REACH || !c.world().loaded(target)) return false;
        BlockData block=c.world().block(target);
        return block!=null && target.equals(block.pos()) && FruitRules.mature(block);
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
    private void submit(Context c,Action action,Pending next) { pending=next;ticket=c.actions().submit(action); }
    private WorkResult skip(Context c) { c.actions().stopMovement();c.navigation().reset();reset();return WorkResult.idle(); }
    private WorkResult fail(Context c,String message) { c.actions().stopMovement();c.navigation().reset();reset();return WorkResult.blocked(message); }
    @Override public void reset() {
        if (storage!=null) storage.reset();
        stage=Stage.FIND;pending=null;ticket=-1;target=null;patch=null;storage=null;
        // Confirmed same-day evidence survives scheduler/one-shot reset; no action,
        // borrowed inventory, debt, or persisted completion is retained.
    }
}
