package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.List;

public final class DisposalModule implements AutomationModule {
    private long ticket = -1;
    private boolean throwing;
    private int before;
    @Override public Feature feature() { return Feature.DISPOSAL; }
    @Override public int priority() { return 10; }
    @Override public WorkResult tick(Context c) {
        if (ticket >= 0) {
            ActionOutcome result = c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("Waiting for rotten tomato disposal");
            ticket = -1;
            if (!result.success()) return fail("Disposal action failed: " + result.message());
            if (throwing && ModuleSupport.count(c,i -> i.is(ItemData.ROTTEN)) >= before) return fail("Rotten tomato disposal was not acknowledged");
            throwing = false;
        }
        if (ModuleSupport.inventoryItem(c,i -> i.is(ItemData.ROTTEN)) == null) return WorkResult.idle();
        List<Poi> sites = ModuleSupport.nearest(c,c.profile().pois(PoiKind.DISPOSAL));
        if (sites.isEmpty()) return fail("Register a safe rotten tomato disposal standing point");
        if (c.world().menu().container()) { ticket = c.actions().submit(new Action.CloseContainer(c.world().menu().id())); return WorkResult.busy("Closing container before disposal"); }
        if (!c.world().menu().carried().empty()) return fail("Clear the inventory cursor before disposal");
        Pos site = sites.get(0).pos();
        Navigation.Result nav = c.navigation().moveTo(site,0.7,c);
        if (nav == Navigation.Result.BLOCKED) return fail("Disposal point cannot be reached");
        if (nav == Navigation.Result.ARRIVED) {
            ItemSlot rotten = ModuleSupport.menuPlayerItem(c,i -> i.is(ItemData.ROTTEN));
            if (rotten == null) return fail("Rotten tomato inventory has not synchronized");
            before = ModuleSupport.count(c,i -> i.is(ItemData.ROTTEN)); throwing = true;
            ticket = c.actions().submit(new Action.ThrowRotten(c.world().menu().id(),rotten.index(),site));
        }
        return WorkResult.busy("Disposing rotten tomatoes");
    }
    private WorkResult fail(String message) { reset(); return WorkResult.blocked(message); }
    @Override public void reset() { ticket = -1; throwing = false; before = 0; }
}
