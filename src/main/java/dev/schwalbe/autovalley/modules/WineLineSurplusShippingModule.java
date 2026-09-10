package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Reserve-first sale of carried line output, with one fresh scan of every registered reserve container. */
public final class WineLineSurplusShippingModule implements AutomationModule {
    private enum Stage { FIND, RESERVE, INSPECT, PERMIT, SHIPPING, TRANSFER, FINISH }
    private enum Pending { OPEN_RESERVE, OPEN_SHIPPING, CLOSE, SELL }
    private Stage stage=Stage.FIND,afterClose;
    private Pending pending;
    private SessionState session;
    private final Deque<String> lines=new ArrayDeque<>();
    private boolean initialized;
    private WineProductionLine line;
    private CommodityStore store;
    private ItemData exemplar;
    private List<Poi> reserves=List.of(),shipping=List.of();
    private int reserveIndex,shippingIndex,containerId=-1,beforeDestination,beforeAllowance,sentCount;
    private long ticket=-1,firstVerifiedTick=-1,verificationDay;
    private WorkResult finishResult;
    @Override public Feature feature() { return Feature.WINE_SURPLUS_SHIPPING; }
    @Override public int priority() { return 36; }
    @Override public WorkResult tick(Context c) {
        session=c.session();
        if (ticket>=0) {
            ActionOutcome outcome=c.actions().outcome(ticket);
            if (!outcome.done()) return busy("서버 확인 대기");
            ticket=-1;
            if (!outcome.success()) return fail("와인 잉여 출하 조작이 확인되지 않았습니다: "+outcome.message());
            switch (pending) {
                case CLOSE -> { containerId=-1;stage=afterClose; }
                case OPEN_RESERVE,OPEN_SHIPPING -> {
                    if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("와인 출하 메뉴가 바뀌었습니다.");
                    containerId=c.world().menu().id();stage=pending==Pending.OPEN_RESERVE ? Stage.INSPECT : Stage.TRANSFER;
                }
                case SELL -> {
                    int quantity=outcome.confirmedCount(),increase=destinationCount(c)-beforeDestination;
                    if (!validMenu(c) || quantity<=0 || quantity>sentCount || quantity>beforeAllowance || increase<quantity)
                        return fail("와인 출하 수량의 실제 서버 확인이 일치하지 않습니다.");
                    WineLineSalePermit remaining=session.wineLineSalePermits.get(line.id());
                    if (remaining!=null && remaining.inventoryLimit()>beforeAllowance-quantity)
                        return fail("와인 라인 출하 허가 수량이 차감되지 않았습니다.");
                }
            }
            pending=null;
        }
        if (stage==Stage.FINISH) { WorkResult result=finishResult;clear();return result; }
        if (!c.session().allows(c.profile(),feature()) || MachineOutputLedger.hasPending(c))
            return finish(c,WorkResult.blocked("와인 잉여 출하 권한 또는 미확인 생산물 수거를 확인하세요."));
        if (line!=null && (!line.enabled() || !line.equals(WineLineSaleRules.outputLine(c.profile(),line.outputItemId()))
            || !store.equals(WineProductionRules.outputStore(c.profile(),line)) || day(c)!=verificationDay))
            return finish(c,WorkResult.blocked("와인 라인·출력 보관함 또는 날짜가 바뀌어 출하를 보류했습니다."));
        switch (stage) {
            case FIND -> {
                if (!initialized) {
                    LinkedHashSet<String> initial=new LinkedHashSet<>();
                    for (ItemSlot held:c.world().inventory()) {
                        WineProductionLine candidate=WineLineSaleRules.outputLine(c.profile(),held.item().id());
                        if (candidate!=null && candidate.enabled() && !held.item().empty()) initial.add(candidate.id());
                    }
                    lines.addAll(initial);initialized=true;
                }
                revoke();line=null;store=null;
                while (!lines.isEmpty() && line==null) {
                    WineProductionLine candidate=WineProductionRules.line(c.profile(),lines.removeFirst());
                    if (candidate!=null && candidate.enabled()
                        && ModuleSupport.inventoryItem(c,item->item.is(candidate.outputItemId()))!=null) line=candidate;
                }
                if (line==null) return finish(c,WorkResult.idle());
                session.wineLineSalePermits.remove(line.id());
                store=WineProductionRules.outputStore(c.profile(),line);
                if (store==null) return finish(c,WorkResult.blocked("와인 라인 출력 보관함을 먼저 등록하세요."));
                exemplar=ModuleSupport.inventoryItem(c,item->item.is(line.outputItemId())).item();
                reserves=ModuleSupport.nearest(c,store.containers().stream()
                    .map(pos->new Poi(pos,PoiKind.STORAGE_CANDIDATE,line.name(),null)).toList());
                reserveIndex=0;firstVerifiedTick=-1;verificationDay=day(c);stage=Stage.RESERVE;
                if (c.world().menu().container()) close(c,Stage.RESERVE);
            }
            case RESERVE -> {
                if (reserveIndex>=reserves.size()) { stage=Stage.PERMIT;break; }
                Navigation.Result nav=c.navigation().moveTo(reserves.get(reserveIndex).pos(),2.5,c);
                if (nav==Navigation.Result.BLOCKED) return finish(c,ModuleSupport.navigationResult(c,"와인 보관함을 확인할 수 없습니다."));
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(reserves.get(reserveIndex).pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_RESERVE);
            }
            case INSPECT -> {
                if (!validMenu(c)) return fail("와인 보관함 확인 중 메뉴가 바뀌었습니다.");
                List<ItemSlot> slots=c.world().menu().slots().stream().filter(slot->!slot.player()).toList();
                if ((slots.size()!=27 && slots.size()!=54) || slots.stream().anyMatch(slot->!c.world().mayPlace(slot.index(),exemplar)))
                    return finish(c,WorkResult.blocked("와인 출력 보관함은 일반 27/54칸 창고여야 합니다."));
                if (slots.stream().anyMatch(slot->!slot.item().empty() && !slot.item().is(line.outputItemId())))
                    return finish(c,WorkResult.blocked("와인 출력 보관함에 다른 품목이 섞여 잉여 판매를 보류했습니다."));
                if (!WineLineSaleRules.fullReserve(c.world().menu(),line.outputItemId())) {
                    revoke();close(c,Stage.FIND);break;
                }
                if (firstVerifiedTick<0) firstVerifiedTick=c.world().tick();
                reserveIndex++;close(c,Stage.RESERVE);
            }
            case PERMIT -> {
                if (firstVerifiedTick<0 || c.world().tick()<firstVerifiedTick
                    || c.world().tick()-firstVerifiedTick>=WineLineSaleRules.VALID_TICKS)
                    return finish(c,WorkResult.blocked("와인 보관함 확인이 만료되어 잉여 출하를 보류했습니다."));
                int held=ModuleSupport.count(c,item->item.is(line.outputItemId()));
                if (held<=0) { stage=Stage.FIND;break; }
                session.wineLineSalePermits.put(line.id(),new WineLineSalePermit(line.id(),line.outputItemId(),store.id(),held,
                    firstVerifiedTick,verificationDay,Set.copyOf(store.containers())));
                if (!WineLineSaleRules.permitted(exemplar,c)) return finish(c,WorkResult.blocked("와인 보관함 전체의 로드·등록 상태를 확인할 수 없습니다."));
                shipping=ModuleSupport.nearest(c,c.profile().pois(PoiKind.SHIPPING_BIN));shippingIndex=0;
                if (shipping.isEmpty()) return finish(c,WorkResult.blocked("잉여 와인 출하함을 등록하세요."));
                stage=Stage.SHIPPING;
            }
            case SHIPPING -> {
                if (!WineLineSaleRules.permitted(exemplar,c)) return finish(c,WorkResult.blocked("와인 잉여 출하 허가가 만료되었습니다."));
                Navigation.Result nav=c.navigation().moveTo(shipping.get(shippingIndex).pos(),2.5,c);
                if (nav==Navigation.Result.BLOCKED) return finish(c,ModuleSupport.navigationResult(c,"와인 출하함에 도달하지 못했습니다."));
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(shipping.get(shippingIndex).pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_SHIPPING);
            }
            case TRANSFER -> {
                if (!validMenu(c)) return fail("와인 출하 메뉴가 바뀌었습니다.");
                ItemSlot held=ModuleSupport.menuPlayerItem(c,item->item.is(line.outputItemId()));
                if (held==null) { revoke();close(c,Stage.FIND);break; }
                if (!WineLineSaleRules.permitted(held.item(),c)) return finish(c,WorkResult.blocked("새로 주운 와인을 포함하려면 보관함을 다시 확인해야 합니다."));
                if (!ModuleSupport.canReceive(c,held.item())) {
                    if (++shippingIndex>=shipping.size()) return finish(c,WorkResult.blocked("출하함이 가득 차 잉여 와인을 보존했습니다."));
                    exemplar=held.item();close(c,Stage.SHIPPING);break;
                }
                beforeDestination=destinationCount(c);beforeAllowance=session.wineLineSalePermits.get(line.id()).inventoryLimit();
                sentCount=held.item().count();submit(c,new Action.QuickMove(containerId,held.index()),Pending.SELL);
            }
            case FINISH -> throw new IllegalStateException("Finished wine line sweep");
        }
        return busy("등록 보관함을 모두 채운 뒤 남은 와인만 출하");
    }
    private int destinationCount(Context c) {
        return c.world().menu().slots().stream().filter(slot->!slot.player()).map(ItemSlot::item)
            .filter(item->line!=null && item.is(line.outputItemId())).mapToInt(ItemData::count).sum();
    }
    private static long day(Context c) { return Math.floorDiv(c.world().dayTime(),24000); }
    private boolean validMenu(Context c) { return c.world().menu().container() && c.world().menu().id()==containerId && c.world().menu().carried().empty(); }
    private void submit(Context c,Action action,Pending why) { pending=why;ticket=c.actions().submit(action); }
    private void close(Context c,Stage next) { afterClose=next;submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE); }
    private WorkResult finish(Context c,WorkResult result) {
        revoke();
        if (validMenu(c)) { finishResult=result;close(c,Stage.FINISH);return busy("확인한 메뉴 닫기"); }
        clear();return result;
    }
    private static WorkResult busy(String message) { return WorkResult.busy("와인 라인: "+message); }
    private void revoke() { if (session!=null && line!=null) session.wineLineSalePermits.remove(line.id()); }
    private WorkResult fail(String message) { clear();return WorkResult.blocked(message); }
    private void clear() {
        revoke();stage=Stage.FIND;afterClose=null;pending=null;lines.clear();initialized=false;line=null;store=null;exemplar=null;
        reserves=List.of();shipping=List.of();reserveIndex=shippingIndex=0;containerId=-1;ticket=-1;firstVerifiedTick=-1;finishResult=null;
    }
    @Override public void reset() { clear(); }
}
