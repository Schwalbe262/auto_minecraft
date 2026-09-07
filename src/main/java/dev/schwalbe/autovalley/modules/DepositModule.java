package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Confirmed-container deposit with one acknowledged operation at a time. */
abstract class DepositModule implements AutomationModule {
    private enum Stage { FIND, APPROACH, OPEN, TRANSFER, CLOSE }
    private Stage stage = Stage.FIND;
    private long ticket = -1, unknownSince = -1, groundWaitSince=-1;
    private ItemData selected;
    private List<Poi> candidates = List.of();
    private int candidate, containerId = -1, before, beforeDestination;
    protected abstract String itemId();
    protected abstract PoiKind destinationKind();
    protected abstract Integer classifier(ItemData item);

    @Override public WorkResult tick(Context c) {
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
                c.session().recordFarmRemoval(selected.id(),moved);
            } else if (stage == Stage.CLOSE) {
                stage = Stage.FIND; containerId = -1;
            }
        }
        switch (stage) {
            case FIND -> {
                ItemSlot slot = ModuleSupport.inventoryItem(c, i -> i.is(itemId()));
                if (slot == null) {
                    unknownSince = -1;
                    if (itemId().equals(ItemData.TOMATO) && c.session().magnetHaulRemaining.getOrDefault(itemId(),0)>0) {
                        if (groundWaitSince<0) groundWaitSince=c.world().tick();
                        if (c.world().tick()-groundWaitSince<c.profile().interactionTimeoutTicks)
                            return WorkResult.busy("Waiting for magnet-held tomatoes to enter freed inventory slots");
                        return fail("Unstored magnet harvest remains; check following ground items or free an inventory slot");
                    }
                    groundWaitSince=-1; return WorkResult.idle();
                }
                groundWaitSince=-1;
                selected = slot.item();
                if (selected.quality() < 0 || selected.quality() > 3) return fail("Unknown product quality; inspect the item");
                Integer group = classifier(selected);
                if (destinationKind() == PoiKind.WINE_CHEST && group == null) {
                    if (unknownSince < 0) unknownSince = c.world().tick();
                    if (c.world().tick() - unknownSince < c.profile().interactionTimeoutTicks) return WorkResult.busy("Waiting for wine production Year to synchronize");
                    return fail("Wine has no synchronized Year; inspect it before storing");
                }
                unknownSince = -1;
                candidates = ModuleSupport.nearest(c,c.profile().pois(destinationKind()).stream()
                    .filter(p -> Objects.equals(p.classifier(),group)).toList());
                if (candidates.isEmpty()) return fail("Register a destination for " + itemId() + " classification " + group);
                candidate = 0; stage = Stage.APPROACH;
            }
            case APPROACH -> {
                if (c.world().menu().container()) { ticket = c.actions().submit(new Action.CloseContainer(c.world().menu().id())); return WorkResult.busy("Closing previous container"); }
                Navigation.Result nav = c.navigation().moveTo(candidates.get(candidate).pos(),2.5,c);
                if (nav == Navigation.Result.BLOCKED) return fail("Registered storage cannot be reached");
                if (nav == Navigation.Result.ARRIVED) { stage = Stage.OPEN; ticket = c.actions().submit(new Action.UseBlock(candidates.get(candidate).pos(),Action.Use.OPEN_CONTAINER)); }
            }
            case OPEN -> { return fail("Storage open acknowledgement was lost"); }
            case TRANSFER -> {
                MenuData menu = c.world().menu();
                if (!menu.container() || menu.id() != containerId || !menu.carried().empty()) return fail("Storage menu changed; transfer cancelled");
                if (destinationKind() != PoiKind.SHIPPING_BIN && menu.slots().stream().filter(s -> !s.player()).map(ItemSlot::item)
                    .anyMatch(i -> i.is(itemId()) && !Objects.equals(classifier(i),classifier(selected)))) return fail("Storage contains a conflicting grade or production Year");
                ItemSlot source = ModuleSupport.menuPlayerItem(c,i -> ModuleSupport.same(i,selected) && !i.empty());
                if (source == null) {
                    stage = Stage.CLOSE; ticket = c.actions().submit(new Action.CloseContainer(containerId));
                } else if (!ModuleSupport.canReceive(c,source.item())) {
                    if (++candidate >= candidates.size()) return fail("Registered destination storage is full");
                    stage = Stage.APPROACH; ticket = c.actions().submit(new Action.CloseContainer(containerId));
                } else {
                    before = ModuleSupport.count(c,selected);
                    beforeDestination=destinationCount(c);
                    ticket = c.actions().submit(new Action.QuickMove(containerId,source.index()));
                }
            }
            case CLOSE -> { return fail("Storage close acknowledgement was lost"); }
        }
        return WorkResult.busy("Storing " + itemId());
    }
    private int destinationCount(Context c) {
        return c.world().menu().slots().stream().filter(s -> !s.player()).map(ItemSlot::item)
            .filter(i -> ModuleSupport.same(i,selected)).mapToInt(ItemData::count).sum();
    }
    private WorkResult fail(String message) { reset(); return WorkResult.blocked(message); }
    @Override public void reset() { stage = Stage.FIND; ticket = -1; selected = null; candidates = List.of(); candidate = 0; containerId = -1; unknownSince = -1; groundWaitSince=-1; }
}
