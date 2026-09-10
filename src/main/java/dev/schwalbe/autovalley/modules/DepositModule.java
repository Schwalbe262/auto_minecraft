package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Confirmed-container deposit with one acknowledged operation at a time. */
abstract class DepositModule implements AutomationModule {
    private enum Stage { FIND, APPROACH, OPEN, TRANSFER, CLOSE, SKIP_CLOSE, WAIT_CLOSE }
    private Stage stage = Stage.FIND;
    private long ticket = -1, unknownSince = -1;
    private ItemData selected;
    private List<Poi> candidates = List.of();
    private int candidate, containerId = -1, before, beforeDestination;
    private boolean navigationBlocked;
    private boolean unavailableBoundary;
    private String unavailableReason;
    /** Typed unsent approach failure; a failed open/transfer/close never grants this boundary. */
    final boolean navigationWaitBoundary() { return navigationBlocked && stage==Stage.APPROACH && ticket<0; }
    final boolean unavailableWaitBoundary() { return unavailableBoundary && stage==Stage.FIND && ticket<0; }
    /** Only the retained logging owner opts in; other storage module behavior is unchanged. */
    protected boolean deferUnavailableStorage() { return false; }
    protected abstract String itemId();
    protected boolean accepts(ItemData item) { return item.is(itemId()); }
    protected boolean requiresKnownQuality() { return destinationKind()!=PoiKind.TOMATO_CHEST; }
    protected abstract PoiKind destinationKind();
    protected abstract Integer classifier(ItemData item);
    protected List<Poi> destinations(Context c,ItemData item) {
        return c.profile().pois(destinationKind()).stream().filter(p -> Objects.equals(p.classifier(),classifier(item))).toList();
    }
    /** False means close this occupied destination and try the next one, without moving any item. */
    protected boolean prepareDestination(Context c,Poi destination,ItemData item) { return true; }
    /** Logging overrides only its own storage approach; ordinary consumers never opt in to jumping. */
    protected Navigation.Result approachDestination(Context c,Poi destination) {
        return c.navigation().moveTo(destination.pos(),2.5,c);
    }

    @Override public WorkResult tick(Context c) {
        navigationBlocked=false; unavailableBoundary=false;
        if (ticket >= 0) {
            ActionOutcome result = c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("Waiting for storage acknowledgement");
            ticket = -1;
            if (!result.success()) return fail("Storage action failed: " + result.message());
            if (stage == Stage.OPEN) {
                if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("Storage menu changed or cursor occupied");
                containerId = c.world().menu().id(); stage = Stage.TRANSFER;
            } else if (stage == Stage.TRANSFER) {
                int moved=result.confirmedCount()>0 ? result.confirmedCount() : Math.max(before-ModuleSupport.count(c,selected),destinationCount(c)-beforeDestination);
                if (moved<=0) return fail("Storage transfer was not acknowledged by the server");
            } else if (stage == Stage.CLOSE) {
                stage = Stage.FIND; containerId = -1;
            } else if (stage == Stage.SKIP_CLOSE) {
                containerId=-1;
                if (candidate>=candidates.size()) return unavailable(c,"No compatible destination is empty or ready; legacy contents were left untouched");
                stage=Stage.APPROACH;
            } else if(stage==Stage.WAIT_CLOSE) {
                if(c.world().menu()==null || c.world().menu().container() || !c.world().menu().carried().empty())
                    return fail("Storage close state changed; retained wait was not granted");
                return unavailable(c,unavailableReason);
            }
        }
        switch (stage) {
            case FIND -> {
                ItemSlot slot = ModuleSupport.inventoryItem(c, this::accepts);
                if (slot == null) {
                    unknownSince = -1;
                    return WorkResult.idle();
                }
                selected = slot.item();
                if (requiresKnownQuality() && (selected.quality() < 0 || selected.quality() > 3)) return fail("Unknown product quality; inspect the item");
                Integer group = classifier(selected);
                if (destinationKind() == PoiKind.WINE_CHEST && group == null) {
                    if (unknownSince < 0) unknownSince = c.world().tick();
                    if (c.world().tick() - unknownSince < c.profile().interactionTimeoutTicks) return WorkResult.busy("Waiting for wine production Year to synchronize");
                    return fail("Wine has no synchronized Year; inspect it before storing");
                }
                unknownSince = -1;
                candidates = ModuleSupport.nearest(c,destinations(c,selected));
                if (candidates.isEmpty()) return unavailable(c,"Register a destination for " + selected.id() + (group==null ? "" : " classification " + group));
                candidate = 0; stage = Stage.APPROACH;
            }
            case APPROACH -> {
                if (c.world().menu().container()) { ticket = c.actions().submit(new Action.CloseContainer(c.world().menu().id())); return WorkResult.busy("Closing previous container"); }
                Navigation.Result nav = approachDestination(c,candidates.get(candidate));
                if (nav == Navigation.Result.BLOCKED) {
                    navigationBlocked=true;
                    return ModuleSupport.navigationResult(c,"Registered storage cannot be reached");
                }
                if (nav == Navigation.Result.ARRIVED) { stage = Stage.OPEN; ticket = c.actions().submit(new Action.UseBlock(candidates.get(candidate).pos(),Action.Use.OPEN_CONTAINER)); }
            }
            case OPEN -> { return fail("Storage open acknowledgement was lost"); }
            case TRANSFER -> {
                MenuData menu = c.world().menu();
                if (!menu.container() || menu.id() != containerId || !menu.carried().empty()) return fail("Storage menu changed; transfer cancelled");
                try {
                    if (!prepareDestination(c,candidates.get(candidate),selected)) {
                        candidate++; stage=Stage.SKIP_CLOSE;
                        ticket=c.actions().submit(new Action.CloseContainer(containerId));
                        return WorkResult.busy("Leaving incompatible legacy contents untouched; checking the next destination");
                    }
                } catch (RuntimeException failure) { return fail("Could not safely prepare storage: "+failure.getMessage()); }
                if (destinationKind() == PoiKind.WINE_CHEST && menu.slots().stream().filter(s -> !s.player()).map(ItemSlot::item)
                    .anyMatch(i -> i.is(itemId()) && !Objects.equals(classifier(i),classifier(selected)))) return fail("Storage contains a conflicting grade or production Year");
                ItemSlot source = ModuleSupport.menuPlayerItem(c,i -> ModuleSupport.same(i,selected) && !i.empty());
                if (source == null) {
                    stage = Stage.CLOSE; ticket = c.actions().submit(new Action.CloseContainer(containerId));
                } else if (!ModuleSupport.canReceive(c,source.item())) {
                    if (++candidate >= candidates.size()) return unavailable(c,"Registered destination storage is full");
                    stage = Stage.APPROACH; ticket = c.actions().submit(new Action.CloseContainer(containerId));
                } else {
                    before = ModuleSupport.count(c,selected);
                    beforeDestination=destinationCount(c);
                    ticket = c.actions().submit(new Action.QuickMove(containerId,source.index()));
                }
            }
            case CLOSE -> { return fail("Storage close acknowledgement was lost"); }
            case SKIP_CLOSE -> { return fail("Skipped storage close acknowledgement was lost"); }
            case WAIT_CLOSE -> { return fail("Storage wait close acknowledgement was lost"); }
        }
        return WorkResult.busy("Storing " + (selected==null ? itemId() : selected.id()));
    }
    private int destinationCount(Context c) {
        return c.world().menu().slots().stream().filter(s -> !s.player()).map(ItemSlot::item)
            .filter(i -> ModuleSupport.same(i,selected)).mapToInt(ItemData::count).sum();
    }
    private WorkResult fail(String message) { reset(); return WorkResult.blocked(message); }
    private WorkResult unavailable(Context c,String message) {
        if(!deferUnavailableStorage()) return fail(message);
        MenuData menu=c.world().menu();
        if(ticket>=0 || c.actions().busy() || c.actions().pauseReason()!=null || menu==null || !menu.carried().empty())
            return fail("Storage wait has an unresolved action or cursor: "+message);
        if(menu.container()) {
            if(stage!=Stage.TRANSFER || menu.id()!=containerId) return fail("Storage wait cannot close an unowned menu: "+message);
            unavailableReason=message;stage=Stage.WAIT_CLOSE;
            ticket=c.actions().submit(new Action.CloseContainer(containerId));
            return WorkResult.busy("Closing full storage before a retained retry");
        }
        reset();unavailableBoundary=true;
        return WorkResult.deferred(message);
    }
    @Override public void reset() { stage = Stage.FIND; ticket = -1; selected = null; candidates = List.of(); candidate = 0; containerId = -1; unknownSince = -1; navigationBlocked=false; unavailableBoundary=false; unavailableReason=null; }
}
