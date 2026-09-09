package dev.schwalbe.autovalley.core;

import java.util.*;

/** Explicit shipment of a frozen current inventory lot, never proof of an old machine operation. */
public final class PendingShipmentRecovery {
    public enum Result { MOVING, COMPLETE, BLOCKED }
    private enum Stage { APPROACH, OPEN, TRANSFER, CLOSE, DONE, STOPPED }
    public record TransferReceipt(long ticket,int confirmedCount) { }
    private final Context owner;
    private final PendingMachineOutput pending;
    private final List<Poi> destinations;
    private final Poi destination;
    private final Map<Integer,ItemData> expectedInventory;
    private final Set<String> liveTokens;
    private final String activeOutput;
    private final long started;
    private final int startQty;
    private final List<TransferReceipt> transfers=new ArrayList<>();
    private Stage stage=Stage.APPROACH;
    private long lastTick,ticket=-1,sentAt,closeTicket=-1;
    private int menuId=-1,sourceIndex=-1,sourceSlot=-1,sourceCount,deliveredQty;
    private Action authorised;
    private String status="미확인 절임 출하 준비 — 장부는 유지됩니다.";

    private PendingShipmentRecovery(Context c,PendingMachineOutput output,List<Poi> shipping,Map<Integer,ItemData> inventory) {
        owner=c; pending=output; destinations=shipping;
        destination=shipping.stream().min(Comparator.comparingDouble(p->c.world().player().distance(p.pos()))).orElseThrow();
        expectedInventory=new LinkedHashMap<>(inventory);
        liveTokens=Set.copyOf(c.session().liveMachineOutputs); activeOutput=c.session().activeMachineOutputId;
        started=lastTick=c.world().tick();
        startQty=inventory.values().stream().filter(i->i.is(ItemData.PRESERVES)).mapToInt(ItemData::count).sum();
    }

    /** Caller must also prove its runtime is OFF, not recording, with no unmanaged screen or persistence error. */
    public static PendingShipmentRecovery start(Context c,String pendingId) {
        String rejection=basicRejection(c);
        if (rejection!=null) throw new IllegalArgumentException(rejection);
        if (!c.world().player().onGround() || c.world().menu().container() || c.world().menu().id()!=0
                || c.actions().busy() || c.actions().startRejection()!=null || c.session().oneShotFeature!=null
                || c.session().pendingShipmentRecovery!=null) throw new IllegalArgumentException("정지·지상·빈 일반 메뉴에서만 출하 복구를 시작할 수 있습니다.");
        PendingMachineOutput output=c.profile().pendingMachineOutputs.get(pendingId);
        if (output==null || !output.id().equals(pendingId) || output.feature()!=Feature.PRESERVES
                || c.profile().pendingMachineOutputs.size()!=1) throw new IllegalArgumentException("미확인 절임 항목을 정확히 하나 선택하세요.");
        Map<Integer,ItemData> inventory=inventory(c);
        int total=inventory.values().stream().filter(i->i.is(ItemData.PRESERVES)).mapToInt(ItemData::count).sum();
        if (total<=0 || inventory.values().stream().anyMatch(i->i.is(ItemData.PRESERVES) && (i.quality()<0 || i.quality()>3)))
            throw new IllegalArgumentException("출하할 절임과 확인된 품질이 필요합니다.");
        List<Poi> shipping=List.copyOf(c.profile().pois(PoiKind.SHIPPING_BIN));
        if (shipping.isEmpty()) throw new IllegalArgumentException("등록한 출하 상자가 필요합니다.");
        PendingShipmentRecovery recovery=new PendingShipmentRecovery(c,output,shipping,inventory);
        c.session().pendingShipmentRecovery=recovery; c.session().oneShotFeature=Feature.SHIPPING;
        return recovery;
    }

    private static String basicRejection(Context c) {
        PlayerState p=c.world().player(); MenuData menu=c.world().menu();
        if (p==null || !p.connected() || (!p.focused() && !c.profile().allowBackground)
                || p.sleeping() || !Float.isFinite(p.health()) || p.health()<=4 || p.food()<=4)
            return "접속·활성 창·체력·허기를 먼저 확인하세요.";
        if (menu==null || !menu.carried().empty()) return "커서의 아이템을 먼저 정리하세요.";
        if (c.profile().loggingHotbarLease!=null || c.profile().loggingRunActive && c.profile().enabled(Feature.LOGGING))
            return "벌목 작업 또는 빌린 핫바 복원을 먼저 마치세요.";
        if (c.actions().pauseReason()!=null) return "미확정 조작을 먼저 확인하세요.";
        return null;
    }

    /** Available projections are frozen, not a claim about unsurfaced native NBT identity. */
    private static Map<Integer,ItemData> inventory(Context c) {
        Map<Integer,ItemData> result=new LinkedHashMap<>();
        for (ItemSlot slot:c.world().inventory()) {
            if (!slot.player() || slot.inventoryIndex()<0 || slot.inventoryIndex()>=36
                    || slot.item()==null || slot.item().count()<0 || slot.item().count()>64
                    || result.putIfAbsent(slot.inventoryIndex(),slot.item())!=null)
                throw new IllegalArgumentException("정상 인벤토리 스냅샷이 변경되었습니다.");
        }
        if (result.size()!=36) throw new IllegalArgumentException("정상 인벤토리 36칸을 확인할 수 없습니다.");
        return result;
    }

    private String identityRejection(Context c) {
        if (c.world()!=owner.world() || c.profile()!=owner.profile() || c.session()!=owner.session()
                || c.actions()!=owner.actions() || c.navigation()!=owner.navigation()
                || c.session().pendingShipmentRecovery!=this || c.session().oneShotFeature!=Feature.SHIPPING)
            return "복구 실행의 접속 또는 권한이 변경되었습니다.";
        if (c.profile().pendingMachineOutputs.size()!=1 || !pending.equals(c.profile().pendingMachineOutputs.get(pending.id()))
                || !destinations.equals(c.profile().pois(PoiKind.SHIPPING_BIN))
                || !liveTokens.equals(c.session().liveMachineOutputs) || !Objects.equals(activeOutput,c.session().activeMachineOutputId))
            return "미확인 장부·출하 등록·확인 증거가 변경되었습니다.";
        return basicRejection(c);
    }

    /** Exact per-dispatch allow-list, in addition to the existing native menu/transfer checks. */
    public boolean permits(Action action,Context c) {
        if (stage==Stage.DONE || stage==Stage.STOPPED || identityRejection(c)!=null) return false;
        if (action instanceof Action.UseBlock use && use.purpose()==Action.Use.DOOR)
            return stage==Stage.APPROACH && ticket<0 && !c.world().menu().container();
        return authorised!=null && authorised.equals(action);
    }

    public Result tick(Context c) {
        if (stage==Stage.DONE) return Result.COMPLETE;
        if (stage==Stage.STOPPED) return Result.BLOCKED;
        try {
            String rejection=identityRejection(c);
            if (rejection!=null) return blocked(c,rejection);
            long now=c.world().tick();
            if (now<lastTick || now-started>2400) return blocked(c,"출하 복구의 제한 시간이 지났습니다.");
            lastTick=now;
            if (ticket>=0) {
                ActionOutcome outcome=c.actions().outcome(ticket);
                if (outcome==null || !outcome.done()) {
                    if (now-sentAt>200) return blocked(c,"출하 조작의 서버 확인을 받지 못했습니다. 재전송하지 않습니다.");
                    return Result.MOVING;
                }
                long completed=ticket; ticket=-1; authorised=null;
                if (!outcome.success()) return blocked(c,"출하 조작이 완료되지 않았습니다. 장부를 유지합니다.");
                if (stage==Stage.OPEN) {
                    MenuData menu=c.world().menu();
                    if (!SmartShippingRules.matchesMenu(menu) || menu.id()==0) return blocked(c,"출하 메뉴의 열림 확인이 없습니다.");
                    menuId=menu.id(); stage=Stage.TRANSFER;
                } else if (stage==Stage.TRANSFER) {
                    int moved=outcome.confirmedCount();
                    if (moved<=0 || moved>sourceCount || transfers.size()>=36)
                        return blocked(c,"양수의 정확한 출하 수량 확인이 없습니다.");
                    ItemData old=expectedInventory.get(sourceIndex);
                    expectedInventory.put(sourceIndex,moved==old.count() ? ItemData.EMPTY
                        : new ItemData(old.id(),old.count()-moved,old.quality(),old.year(),old.hoe(),old.durability()));
                    deliveredQty=Math.addExact(deliveredQty,moved); transfers.add(new TransferReceipt(completed,moved));
                } else if (stage==Stage.CLOSE) {
                    if (c.world().menu().container() || c.world().menu().id()!=0 || deliveredQty!=startQty
                            || !inventory(c).equals(expectedInventory)) return blocked(c,"출하 뒤 메뉴·잔량 확인이 일치하지 않습니다.");
                    closeTicket=completed; stage=Stage.DONE; release(); c.actions().stopMovement(); c.navigation().reset();
                    status="절임 "+deliveredQty+"개 출하 확인 — 자동화 OFF. 미확인 장부는 별도로 수동 확인하세요.";
                    return Result.COMPLETE;
                }
            }
            if (!inventory(c).equals(expectedInventory)) return blocked(c,"계획 밖 재고 변화가 있어 출하 복구를 중지했습니다.");
            if (stage==Stage.APPROACH) {
                if (c.world().menu().container()) return blocked(c,"예상하지 않은 메뉴가 열렸습니다.");
                Navigation.Result result=c.navigation().moveTo(destination.pos(),2.5,c);
                if (result==Navigation.Result.BLOCKED) return blocked(c,"출하 상자까지 안전한 경로를 찾지 못했습니다.");
                if (result==Navigation.Result.MOVING) return Result.MOVING;
                if (c.actions().busy()) return Result.MOVING;
                if (!c.world().loaded(destination.pos()) || !SmartShippingRules.BLOCK_ID.equals(c.world().block(destination.pos()).id()))
                    return blocked(c,"등록한 출하 상자의 실제 종류가 다릅니다.");
                c.actions().stopMovement(); stage=Stage.OPEN;
                submit(c,new Action.UseBlock(destination.pos(),Action.Use.OPEN_CONTAINER));
            } else if (stage==Stage.TRANSFER) {
                MenuData menu=c.world().menu();
                if (!SmartShippingRules.matchesMenu(menu) || menu.id()!=menuId || c.actions().busy()) return blocked(c,"출하 메뉴의 소유 상태가 변경되었습니다.");
                if (deliveredQty==startQty) { stage=Stage.CLOSE; submit(c,new Action.CloseContainer(menuId)); }
                else {
                    if (transfers.size()>=36) return blocked(c,"출하 확인 횟수 제한에 도달했습니다.");
                    ItemSlot selected=menu.slots().stream().filter(s->s.player() && s.inventoryIndex()>=0 && s.inventoryIndex()<36
                        && s.item().is(ItemData.PRESERVES) && s.item().equals(expectedInventory.get(s.inventoryIndex()))).findFirst().orElse(null);
                    if (selected==null || selected.item().count()>startQty-deliveredQty
                            || menu.slots().stream().noneMatch(s->!s.player() && c.world().mayPlace(s.index(),selected.item())))
                        return blocked(c,"계획한 절임을 받을 출하 칸이 없습니다.");
                    sourceIndex=selected.inventoryIndex(); sourceSlot=selected.index(); sourceCount=selected.item().count();
                    submit(c,new Action.QuickMove(menuId,sourceSlot));
                }
            }
            status="미확인 절임 출하 "+deliveredQty+"/"+startQty+" — "+stage;
            return Result.MOVING;
        } catch (RuntimeException e) { return blocked(c,"출하 확인 상태가 변경되었습니다. 장부는 유지됩니다."); }
    }

    private void submit(Context c,Action action) {
        authorised=action; sentAt=c.world().tick(); ticket=c.actions().submit(action);
        if (ticket<0) throw new IllegalStateException("Missing action ticket");
    }
    private Result blocked(Context c,String reason) { cancel(c,reason); return Result.BLOCKED; }
    public void cancel(Context c,String reason) {
        if (stage==Stage.DONE || stage==Stage.STOPPED) return;
        stage=Stage.STOPPED; authorised=null; release();
        // Normal adapter cancellation preserves any unresolved native receipt fence.
        owner.actions().cancel(); owner.actions().stopMovement(); owner.navigation().reset();
        status="출하 복구 중지 (확인 "+deliveredQty+"/"+startQty+") — "+reason;
    }
    private void release() {
        if (owner.session().pendingShipmentRecovery==this) {
            owner.session().pendingShipmentRecovery=null;
            if (owner.session().oneShotFeature==Feature.SHIPPING) owner.session().oneShotFeature=null;
        }
    }
    public String status() { return status; }
    /** At most 36 native transfer receipts; no inventory/profile contents. */
    public Map<String,Object> report() {
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("pendingId",pending.id()); report.put("target",destination.pos());
        report.put("startQty",startQty); report.put("deliveredQty",deliveredQty);
        report.put("complete",stage==Stage.DONE); report.put("status",status);
        report.put("transfers",List.copyOf(transfers)); report.put("closeTicket",closeTicket);
        return Collections.unmodifiableMap(report);
    }
}
