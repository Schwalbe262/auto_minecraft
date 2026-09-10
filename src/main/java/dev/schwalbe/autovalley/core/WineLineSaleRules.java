package dev.schwalbe.autovalley.core;

import java.util.*;

/** New wine lines have independent output reserves; tomato age permits never participate. */
public final class WineLineSaleRules {
    public static final int VALID_TICKS=1200;
    private WineLineSaleRules() { }
    public static WineProductionLine outputLine(Profile profile,String itemId) {
        if (profile==null || itemId==null || ItemData.WINE.equals(itemId)) return null;
        List<WineProductionLine> matches=WineProductionRules.lines(profile).stream()
            .filter(line->!WineProductionRules.LEGACY_ID.equals(line.id()) && itemId.equals(line.outputItemId())).toList();
        return matches.size()==1 && WineProductionRules.configured(profile,matches.get(0)) ? matches.get(0) : null;
    }
    public static boolean permitted(ItemData item,Context c) {
        if (item==null || item.empty() || item.count()>64 || c==null
            || !c.session().allows(c.profile(),Feature.WINE_SURPLUS_SHIPPING)) return false;
        WineProductionLine line=outputLine(c.profile(),item.id());
        if (line==null || !line.enabled()) return false;
        WineLineSalePermit permit=c.session().wineLineSalePermits.get(line.id());
        CommodityStore store=WineProductionRules.outputStore(c.profile(),line);
        if (permit==null || store==null || !store.accepts(item) || !line.id().equals(permit.lineId())
            || !line.outputItemId().equals(permit.outputItemId()) || !store.id().equals(permit.outputStoreId())
            || permit.inventoryLimit()<item.count() || permit.verifiedTick()<0
            || c.world().tick()<permit.verifiedTick() || c.world().tick()-permit.verifiedTick()>=VALID_TICKS
            || Math.floorDiv(c.world().dayTime(),24000)!=permit.gameDay()) return false;
        Set<Pos> current=new HashSet<>(store.containers());
        return !current.isEmpty() && current.size()==store.containers().size() && current.equals(permit.storages())
            && current.stream().allMatch(pos->c.world().loaded(pos) && StorageSurveyRules.ordinaryStorage(c.world().block(pos)))
            && CommodityStorageRules.unreservedContainerSet(c.profile(),current);
    }
    /** Only the adapter consumes the acknowledged quantity, even if an ambient pickup refilled the source. */
    public static void consume(ItemData item,int amount,Context c) {
        if (item==null || item.empty() || ItemData.WINE.equals(item.id()) || amount<=0 || c==null) return;
        List<String> matching=c.session().wineLineSalePermits.entrySet().stream()
            .filter(entry->entry.getValue()!=null && !WineProductionRules.LEGACY_ID.equals(entry.getKey())
                && entry.getKey().equals(entry.getValue().lineId()) && item.id().equals(entry.getValue().outputItemId()))
            .map(Map.Entry::getKey).toList();
        if (matching.size()!=1) return;
        c.session().wineLineSalePermits.computeIfPresent(matching.get(0),(key,permit)->{
            if (!key.equals(permit.lineId()) || !item.id().equals(permit.outputItemId())) return permit;
            WineLineSalePermit remaining=permit.consumed(amount);
            return remaining.inventoryLimit()>0 ? remaining : null;
        });
    }
    /** Fullness is output-ID based across the entire reserve; vintages remain native stack distinctions. */
    public static boolean fullReserve(MenuData menu,String outputItemId) {
        if (menu==null || !menu.container() || !menu.carried().empty() || outputItemId==null || ItemData.WINE.equals(outputItemId)) return false;
        List<ItemSlot> slots=menu.slots().stream().filter(slot->!slot.player()).toList();
        return (slots.size()==27 || slots.size()==54)
            && slots.stream().allMatch(slot->slot.item().is(outputItemId) && slot.item().count()==64);
    }
}
