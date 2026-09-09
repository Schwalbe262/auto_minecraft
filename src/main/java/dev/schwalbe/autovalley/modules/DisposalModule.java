package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.List;

public final class DisposalModule implements AutomationModule {
    private long ticket = -1;
    private boolean throwing;
    private boolean inventoryTrash;
    private int before;
    @Override public Feature feature() { return Feature.DISPOSAL; }
    @Override public int priority() { return 10; }
    @Override public WorkResult tick(Context c) {
        if (ticket >= 0) {
            ActionOutcome result = c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("Waiting for rotten tomato disposal");
            ticket = -1;
            if (!result.success()) return fail("Disposal action failed: " + result.message());
            if (throwing) {
                int removed=result.confirmedCount()>0 ? result.confirmedCount() : inventoryTrash ? 0 : before-ModuleSupport.count(c,i -> i.is(ItemData.ROTTEN));
                if (removed<=0) return fail("Rotten tomato disposal was not acknowledged");
            }
            throwing = false;
            inventoryTrash = false;
        }
        if (ModuleSupport.inventoryItem(c,i -> i.is(ItemData.ROTTEN)) == null) return WorkResult.idle();
        if (MachineOutputLedger.hasPending(c)) return fail("Resolve pending production output before disposal");
        if (c.world().menu().container()) { ticket = c.actions().submit(new Action.CloseContainer(c.world().menu().id())); return WorkResult.busy("Closing container before disposal"); }
        if (!c.world().menu().carried().empty()) return fail("Clear the inventory cursor before disposal");
        if (c.actions().supportsInventoryTrash()) {
            String rejection=c.actions().inventoryTrashRejection();
            if (rejection!=null) return fail(rejection);
            ItemSlot rotten=c.world().inventory().stream().filter(s -> s.player() && s.inventoryIndex()>=0 && s.inventoryIndex()<36
                && s.item().is(ItemData.ROTTEN)).findFirst().orElse(null);
            if (rotten==null) return fail("No normal inventory rotten tomato stack is available");
            before=rotten.item().count(); throwing=true; inventoryTrash=true;
            ticket=c.actions().submit(new Action.TrashRotten(rotten.inventoryIndex(),rotten.item()));
            return WorkResult.busy("Deleting rotten tomatoes through inventory TrashSlot");
        }
        List<Poi> sites = ModuleSupport.nearest(c,c.profile().pois(PoiKind.DISPOSAL));
        if (sites.isEmpty()) return fail("Server inventory TrashSlot is unavailable; enable it or register a safe disposal point");
        Pos site = sites.get(0).pos();
        Navigation.Result nav = c.navigation().moveTo(site,0.7,c);
        if (nav == Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"Disposal point cannot be reached");
        if (nav == Navigation.Result.ARRIVED) {
            ItemSlot rotten = ModuleSupport.menuPlayerItem(c,i -> i.is(ItemData.ROTTEN));
            if (rotten == null) return fail("Rotten tomato inventory has not synchronized");
            before = ModuleSupport.count(c,i -> i.is(ItemData.ROTTEN)); throwing = true;
            ticket = c.actions().submit(new Action.ThrowRotten(c.world().menu().id(),rotten.index(),site));
        }
        return WorkResult.busy("Disposing rotten tomatoes");
    }
    private WorkResult fail(String message) { reset(); return WorkResult.blocked(message); }
    @Override public void reset() { ticket = -1; throwing = false; inventoryTrash=false; before = 0; }
}
