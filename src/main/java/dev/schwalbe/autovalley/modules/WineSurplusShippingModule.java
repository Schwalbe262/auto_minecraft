package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Sells carried surplus only after every reserve container in its age cohort is full. */
public final class WineSurplusShippingModule implements AutomationModule {
    private enum Stage { FIND, RESERVE, INSPECT, PERMIT, SHIPPING, TRANSFER, FINISH }
    private enum Pending { CLOSE, OPEN_RESERVE, OPEN_SHIPPING, SELL }
    private Stage stage=Stage.FIND, afterClose;
    private Pending pending;
    private SessionState session;
    private long ticket=-1, firstVerifiedTick=-1, verificationDay;
    private Integer cohort;
    private ItemData exemplar;
    private List<Poi> reserves=List.of(), shipping=List.of();
    private Set<Pos> reservePositions=Set.of();
    private int reserveIndex, shippingIndex, containerId=-1, beforeDestination, beforeAllowance, sentCount;
    private WorkResult finishResult;

    public WineSurplusShippingModule() { }
    @Override public Feature feature() { return Feature.WINE_SURPLUS_SHIPPING; }
    @Override public int priority() { return 35; }

    @Override public WorkResult tick(Context c) {
        session=c.session();
        if (ticket>=0) {
            ActionOutcome outcome=c.actions().outcome(ticket);
            if (!outcome.done()) return WorkResult.busy("Waiting for surplus wine acknowledgement");
            ticket=-1;
            if (!outcome.success()) return fail("Surplus wine action failed: "+outcome.message());
            switch (pending) {
                case CLOSE -> { containerId=-1; stage=afterClose; }
                case OPEN_RESERVE, OPEN_SHIPPING -> {
                    if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("Wine container did not synchronize");
                    containerId=c.world().menu().id();
                    stage=pending==Pending.OPEN_RESERVE ? Stage.INSPECT : Stage.TRANSFER;
                }
                case SELL -> {
                    if (!validMenu(c)) return fail("Shipping container changed during surplus transfer");
                    int transferred=destinationCount(c)-beforeDestination;
                    if (transferred<=0 || transferred>sentCount || transferred>beforeAllowance) return fail("Surplus wine destination increase was not confirmed");
                    WineSalePermit remaining=session.wineSalePermits.get(cohort);
                    // The action adapter is the sole allowance consumer, including magnet refills.
                    if (remaining!=null && remaining.inventoryLimit()>beforeAllowance-transferred) return fail("Surplus wine allowance was not updated after transfer");
                    stage=Stage.TRANSFER;
                }
            }
            pending=null;
        }
        if (stage!=Stage.FIND && stage!=Stage.FINISH) {
            Integer current=c.world().wineYear();
            if (current==null || cohort==null || cohort<0 || cohort>current) return finish(c,WorkResult.blocked("Wine age is unavailable; surplus sales are paused"));
            if (!registeredReserves(c).equals(reservePositions) || gameDay(c)!=verificationDay) {
                revoke();
                return finish(c,WorkResult.blocked("Wine reserve registrations or game day changed; verification must restart"));
            }
            if (reservePositions.stream().anyMatch(p -> !c.world().loaded(p))) return finish(c,WorkResult.blocked("A wine reserve is unloaded; surplus sales are paused"));
        }
        switch (stage) {
            case FIND -> {
                ItemSlot held=ModuleSupport.inventoryItem(c,i -> i.is(ItemData.WINE));
                if (held==null) return WorkResult.idle();
                exemplar=held.item(); cohort=exemplar.year();
                Integer current=c.world().wineYear();
                if (current==null || cohort==null || cohort<0 || cohort>current) return fail("Wine age is unknown or invalid; surplus sales are paused");
                reserves=ModuleSupport.nearest(c,c.profile().pois(PoiKind.WINE_CHEST).stream().filter(p -> Objects.equals(p.classifier(),cohort)).toList());
                if (reserves.isEmpty()) return fail("Register reserve storage for wine age "+(current-cohort)+" before selling surplus");
                reservePositions=new HashSet<>(); for (Poi reserve:reserves) reservePositions.add(reserve.pos());
                if (reservePositions.size()!=reserves.size()) return fail("Duplicate wine reserve registrations require correction");
                verificationDay=gameDay(c); firstVerifiedTick=-1; reserveIndex=0; revoke();
                stage=Stage.RESERVE;
                if (c.world().menu().container()) close(c,Stage.RESERVE);
            }
            case RESERVE -> {
                if (reserveIndex>=reserves.size()) { stage=Stage.PERMIT; break; }
                Navigation.Result nav=c.navigation().moveTo(reserves.get(reserveIndex).pos(),2.5,c);
                if (nav==Navigation.Result.BLOCKED) return finish(c,WorkResult.blocked("A wine reserve cannot be reached; surplus sales are paused"));
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(reserves.get(reserveIndex).pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_RESERVE);
            }
            case INSPECT -> {
                if (!validMenu(c)) return fail("Wine reserve container changed during verification");
                List<ItemSlot> slots=c.world().menu().slots().stream().filter(s -> !s.player()).toList();
                if ((slots.size()!=27 && slots.size()!=54) || slots.stream().anyMatch(s -> !c.world().mayPlace(s.index(),exemplar)))
                    return finish(c,WorkResult.blocked("Wine reserves must expose 27 or 54 ordinary storage slots"));
                if (slots.stream().anyMatch(s -> !s.item().empty() && (!s.item().is(ItemData.WINE) || !Objects.equals(s.item().year(),cohort))))
                    return finish(c,WorkResult.blocked("Wine reserve contains another age group or another item; no surplus may be sold"));
                if (slots.stream().anyMatch(s -> s.item().empty() || s.item().count()!=64))
                    return finish(c,WorkResult.blocked("Reserved wine storage still has room; fill it before selling surplus"));
                if (firstVerifiedTick<0) firstVerifiedTick=c.world().tick();
                reserveIndex++; close(c,Stage.RESERVE);
            }
            case PERMIT -> {
                if (firstVerifiedTick<0 || c.world().tick()-firstVerifiedTick>=1200) return fail("Wine reserve verification expired; retry before selling");
                int held=heldCount(c);
                if (held<=0) { clear(); return WorkResult.idle(); }
                session.wineSalePermits.put(cohort,new WineSalePermit(cohort,held,firstVerifiedTick,verificationDay,Set.copyOf(reservePositions)));
                shipping=ModuleSupport.nearest(c,c.profile().pois(PoiKind.SHIPPING_BIN)); shippingIndex=0;
                if (shipping.isEmpty()) return fail("Register a shipping bin for surplus wine");
                stage=Stage.SHIPPING;
            }
            case SHIPPING -> {
                if (!permitFresh(c)) return finish(c,WorkResult.blocked("Surplus wine permission expired; reserve verification must restart"));
                Navigation.Result nav=c.navigation().moveTo(shipping.get(shippingIndex).pos(),2.5,c);
                if (nav==Navigation.Result.BLOCKED) return finish(c,WorkResult.blocked("Surplus wine shipping bin cannot be reached"));
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(shipping.get(shippingIndex).pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_SHIPPING);
            }
            case TRANSFER -> {
                if (!validMenu(c)) return fail("Shipping container changed; surplus transfer cancelled");
                ItemSlot held=ModuleSupport.menuPlayerItem(c,i -> i.is(ItemData.WINE) && Objects.equals(i.year(),cohort));
                if (held==null) return finish(c,WorkResult.idle());
                if (!permitFresh(c)) return finish(c,WorkResult.blocked("Surplus wine permission expired or was consumed; verify reserves again"));
                WineSalePermit permit=session.wineSalePermits.get(cohort);
                if (held.item().count()>permit.inventoryLimit()) return finish(c,WorkResult.blocked("New wine joined the held stack; verify reserves again before selling it"));
                if (!ModuleSupport.canReceive(c,held.item())) {
                    if (++shippingIndex>=shipping.size()) return finish(c,WorkResult.blocked("Shipping bins are full; surplus wine remains in inventory"));
                    close(c,Stage.SHIPPING); break;
                }
                beforeDestination=destinationCount(c); beforeAllowance=permit.inventoryLimit(); sentCount=held.item().count();
                submit(c,new Action.QuickMove(containerId,held.index()),Pending.SELL);
            }
            case FINISH -> {
                WorkResult result=finishResult; clear(); return result;
            }
        }
        return WorkResult.busy("Verifying reserves and shipping surplus wine");
    }

    private int heldCount(Context c) { return ModuleSupport.count(c,i -> i.is(ItemData.WINE) && Objects.equals(i.year(),cohort)); }
    private int destinationCount(Context c) {
        return c.world().menu().slots().stream().filter(s -> !s.player()).map(ItemSlot::item)
            .filter(i -> i.is(ItemData.WINE) && Objects.equals(i.year(),cohort)).mapToInt(ItemData::count).sum();
    }
    private Set<Pos> registeredReserves(Context c) {
        Set<Pos> result=new HashSet<>();
        for (Poi poi:c.profile().pois(PoiKind.WINE_CHEST)) if (Objects.equals(poi.classifier(),cohort)) result.add(poi.pos());
        return result;
    }
    private boolean permitFresh(Context c) {
        WineSalePermit permit=session.wineSalePermits.get(cohort);
        return permit!=null && permit.inventoryLimit()>0 && permit.gameDay()==gameDay(c)
            && c.world().tick()>=permit.verifiedTick() && c.world().tick()-permit.verifiedTick()<1200
            && permit.storages().equals(registeredReserves(c));
    }
    private static long gameDay(Context c) { return Math.floorDiv(c.world().dayTime(),24000); }
    private boolean validMenu(Context c) { return c.world().menu().container() && c.world().menu().id()==containerId && c.world().menu().carried().empty(); }
    private void submit(Context c,Action action,Pending reason) { pending=reason; ticket=c.actions().submit(action); }
    private void close(Context c,Stage next) { afterClose=next; submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE); }
    private WorkResult finish(Context c,WorkResult result) {
        revoke();
        if (validMenu(c)) { finishResult=result; close(c,Stage.FINISH); return WorkResult.busy("Closing verified wine container"); }
        clear(); return result;
    }
    private void revoke() { if (session!=null && cohort!=null) session.wineSalePermits.remove(cohort); }
    private WorkResult fail(String message) { clear(); return WorkResult.blocked(message); }
    private void clear() {
        revoke(); stage=Stage.FIND; afterClose=null; pending=null; ticket=-1; firstVerifiedTick=-1; cohort=null; exemplar=null;
        reserves=List.of(); shipping=List.of(); reservePositions=Set.of(); reserveIndex=0; shippingIndex=0; containerId=-1; finishResult=null;
    }
    @Override public void reset() { clear(); if (session!=null) session.wineSalePermits.clear(); }
}
