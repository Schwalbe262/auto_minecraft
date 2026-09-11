package dev.schwalbe.autovalley.core;

import java.util.*;

/** An existing work lease narrows authority; it never authorizes a new consumer or a guessed restore. */
public final class WorkHotbarLeasePolicy {
    private WorkHotbarLeasePolicy() { }
    public static String rejection(Action action,Context c) {
        HotbarLease lease=c.profile().workHotbarLease;if(lease==null)return null;
        if(!lease.valid() || c.profile().loggingHotbarLease!=null || c.session().workHotbarOwner!=lease.owner()
            || lease.hotbarSlot()==c.profile().hoeHotbarSlot || lease.hotbarSlot()==c.profile().loggingAxeHotbarSlot)
            return "Work hotbar custody is invalid or belongs to another job";
        MenuData menu=c.world().menu();
        if(menu==null || !menu.carried().empty())return "Work hotbar custody requires an empty cursor";
        // Recovery is explicitly owned, but does not require the feature to remain ON.
        if(action instanceof Action.RefreshInventory)return menu.container() || menu.id()!=0
            || !c.actions().supportsInventoryRefresh() ? "Work custody refresh requires the normal inventory" : null;
        if(action instanceof Action.SwapHotbar swap && swap.inventoryIndex()==lease.sourceIndex()) {
            if(menu.container() || swap.hotbarSlot()!=lease.hotbarSlot())return "Only the exact leased pair may restore custody";
            if(lease.stage()==HotbarLease.Stage.PREPARED)
                return !c.session().allows(c.profile(),lease.owner()) || !original(c,lease,lease.hotbarSlot())
                    || !empty(c,lease.sourceIndex()) ? "Prepared work parking endpoints changed" : null;
            if(lease.stage()==HotbarLease.Stage.RESTORING)
                return !original(c,lease,lease.sourceIndex()) || !distinctWorkingSlot(c,lease,lease.hotbarSlot())
                    ? "Work restoration endpoints changed; no inverse swap is authorized" : null;
            return "The parked original may move only through an explicit restoration";
        }
        if(lease.stage()!=HotbarLease.Stage.PARKED || !c.session().allows(c.profile(),lease.owner())
            || !original(c,lease,lease.sourceIndex()) || !distinctWorkingSlot(c,lease,lease.hotbarSlot()))
            return "Finish or restore the exact parked work item before other actions";
        if(action instanceof Action.SwapHotbar swap) {
            ItemSlot source=inventorySlot(c,swap.inventoryIndex()),destination=inventorySlot(c,swap.hotbarSlot());
            return menu.container() || swap.hotbarSlot()!=lease.hotbarSlot() || source==null || destination==null
                || swap.inventoryIndex()==lease.sourceIndex() || swap.inventoryIndex()==c.profile().hoeHotbarSlot
                || swap.inventoryIndex()==c.profile().loggingAxeHotbarSlot
                || !workingItem(c.profile(),lease.owner(),source.item()) || !workingItem(c.profile(),lease.owner(),destination.item())
                ? "Only owner work items may use the temporary hotbar slot" : null;
        }
        if(action instanceof Action.QuickMove move) {
            ItemSlot source=menu.slot(move.slot());
            if(source==null || source.player() && source.inventoryIndex()==lease.sourceIndex())return "The parked original cannot be transferred";
            // A withdrawal can merge into a player slot even when that slot is not
            // the clicked source. Never let it merge into the parked original.
            if(!source.player() && source.item().is(lease.original().id()))return "A withdrawal could merge into the parked original";
            return !workingItem(c.profile(),lease.owner(),source.item()) ? "Transfer is outside the leased work owner" : null;
        }
        if(action instanceof Action.SelectHotbar)return null;
        if(action instanceof Action.InspectCrystal inspect) {
            ArtisanRecipe recipe=ArtisanRules.at(c.profile(),inspect.pos());
            return lease.owner()!=Feature.CRYSTAL_COPY || recipe==null || recipe.feature()!=Feature.CRYSTAL_COPY
                ? "This lease does not authorize that crystal inspection" : null;
        }
        if(action instanceof Action.CloseContainer)return !c.actions().ownsContainer() ? "Work lease cannot close an unowned menu" : null;
        if(action instanceof Action.UseBlock use) {
            return switch(use.purpose()) {
                case DOOR -> null;
                case OPEN_CONTAINER -> containerAllowed(c,use.pos()) ? null : "Container is outside the leased work owner";
                case ARTISAN -> {
                    ArtisanRecipe recipe=ArtisanRules.at(c.profile(),use.pos());
                    yield recipe!=null && recipe.feature()==lease.owner() ? null : "This lease does not authorize that artisan work";
                }
                case FRUIT -> lease.owner()==Feature.STARFRUIT ? null : "This lease does not authorize fruit work";
                default -> "Restore the work hotbar before another feature can act";
            };
        }
        return "The work lease does not authorize disposal, crafting or another inventory operation";
    }
    public static boolean containerAllowed(Context c,Pos pos) {
        HotbarLease lease=c.profile().workHotbarLease;if(lease==null)return true;
        if(c.session().workHotbarOwner!=lease.owner())return false;
        if(lease.owner()==Feature.STARFRUIT) {
            if(c.profile().fruitPatches==null)return false;
            return c.profile().fruitPatches.stream().filter(p->p!=null && p.valid()).anyMatch(p->{
                CommodityStore store=CommodityStorageRules.store(c.profile(),p.storeId());
                return store!=null && store.containers().contains(pos);
            });
        }
        if(c.profile().artisanJobs==null)return false;
        for(ArtisanJob job:c.profile().artisanJobs.values()) {
            if(job==null)continue;ArtisanRecipe recipe=ArtisanRecipe.find(job.recipeId());
            if(recipe==null || recipe.feature()!=lease.owner())continue;
            List<String> stores=recipe.feature()==Feature.CRYSTAL_COPY
                ? List.of(job.outputStoreId()) : List.of(job.inputStoreId(),job.outputStoreId());
            for(String id:stores) {
                CommodityStore store=CommodityStorageRules.store(c.profile(),id);
                if(store!=null && store.containers().contains(pos))return true;
            }
        }
        return false;
    }
    public static boolean workingItem(Profile profile,Feature owner,ItemData item) {
        if(item==null)return false;if(item.empty())return true;
        if(item.count()>64)return false;
        if(owner==Feature.STARFRUIT)return item.is(FruitRules.ITEM);
        if(profile.artisanJobs==null)return false;
        for(ArtisanJob job:profile.artisanJobs.values()) {
            if(job==null)continue;ArtisanRecipe recipe=ArtisanRecipe.find(job.recipeId());
            if(recipe!=null && recipe.feature()==owner && (owner==Feature.CRYSTAL_COPY
                ? CrystalCollection.accepts(item) : item.is(recipe.inputId()) || item.is(recipe.outputId())))return true;
        }
        return false;
    }
    private static ItemSlot inventorySlot(Context c,int index) {
        List<ItemSlot> slots=c.world().inventory().stream().filter(s->s.player() && s.inventoryIndex()==index).toList();
        return slots.size()==1 ? slots.get(0) : null;
    }
    private static boolean original(Context c,HotbarLease lease,int index) {
        ItemSlot slot=inventorySlot(c,index);
        return slot!=null && lease.original().equals(slot.item()) && lease.fingerprint().equals(c.world().loggingItemFingerprint(index));
    }
    private static boolean empty(Context c,int index) {
        ItemSlot slot=inventorySlot(c,index);return slot!=null && slot.item().empty();
    }
    private static boolean distinctWorkingSlot(Context c,HotbarLease lease,int index) {
        ItemSlot slot=inventorySlot(c,index);
        return slot!=null && (slot.item().empty() || !slot.item().is(lease.original().id()));
    }
}
