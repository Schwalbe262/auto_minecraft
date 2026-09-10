package dev.schwalbe.autovalley.core;

import java.util.*;

public final class CommodityStorageRules {
    private CommodityStorageRules() { }
    public static CommodityStore store(Profile profile,String id) {
        CommodityStore result=profile.commodityStores==null ? null : profile.commodityStores.get(id);
        return result!=null && result.valid() && result.id().equals(id) ? result : null;
    }
    public static List<CommodityStore> at(Profile profile,Pos pos) {
        if(profile.commodityStores==null || pos==null)return List.of();
        return profile.commodityStores.entrySet().stream().filter(entry->entry.getValue()!=null
            && entry.getValue().valid() && entry.getKey().equals(entry.getValue().id()) && entry.getValue().containers().contains(pos))
            .map(Map.Entry::getValue).toList();
    }
    /** Every physical half matters: another registered role cannot share a generic chest inventory. */
    public static boolean unreservedContainer(Profile profile,Collection<Pos> physicalCells) {
        if(profile==null || profile.pois==null || physicalCells==null || physicalCells.isEmpty() || physicalCells.size()>2
            || physicalCells.stream().anyMatch(pos->!CoordinateDestinationRules.validPosition(pos)))return false;
        return profile.pois.stream().allMatch(poi->poi!=null && poi.pos()!=null && poi.kind()!=null
            && (!physicalCells.contains(poi.pos()) || poi.kind()==PoiKind.STORAGE_CANDIDATE));
    }
    public static boolean openAllowed(Context c,Pos pos) {
        return at(c.profile(),pos).stream().anyMatch(store->scopeAllows(c,store.id()) || wineOpenAllows(c,store.id()));
    }
    public static boolean depositAllowed(Context c,Pos pos,ItemData item) {
        return at(c.profile(),pos).stream().anyMatch(store->store.accepts(item)
            && (scopeAllows(c,store.id()) || wineDepositAllows(c,store.id(),item)));
    }
    public static boolean knownItem(Context c,ItemData item) {
        return c.profile().commodityStores!=null && c.profile().commodityStores.values().stream()
            .anyMatch(store->store!=null && store.accepts(item)
                && (scopeAllows(c,store.id()) || wineDepositAllows(c,store.id(),item)));
    }
    public static boolean withdrawalAllowed(Context c,Pos pos,ItemData item) {
        for (WineProductionLine line:WineProductionRules.lines(c.profile())) {
            if (WineProductionRules.LEGACY_ID.equals(line.id()) || !WineProductionRules.allowed(c,line)
                || !WineProductionRules.input(item,line)) continue;
            CommodityStore input=WineProductionRules.inputStore(c.profile(),line);
            if (input!=null && input.containers().contains(pos) && input.accepts(item)) return true;
        }
        if(c.profile().artisanJobs!=null)for(ArtisanJob job:c.profile().artisanJobs.values()) {
            if(job==null)continue;
            ArtisanRecipe recipe=ArtisanRecipe.find(job.recipeId());
            CommodityStore store=store(c.profile(),job.inputStoreId());
            if(recipe!=null && c.session().allows(c.profile(),recipe.feature()) && store!=null
                && store.containers().contains(pos) && store.accepts(item) && item.is(recipe.inputId()))return true;
        }
        return false;
    }
    /** Entire declared reserve, each cell checked independently of the native one/two-cell menu shape. */
    public static boolean unreservedContainerSet(Profile profile,Collection<Pos> cells) {
        return cells!=null && !cells.isEmpty() && cells.stream().allMatch(pos->pos!=null && unreservedContainer(profile,List.of(pos)));
    }
    private static boolean wineOpenAllows(Context c,String storeId) {
        for (WineProductionLine line:WineProductionRules.lines(c.profile())) {
            if (!line.enabled() || WineProductionRules.LEGACY_ID.equals(line.id()) || !WineProductionRules.configured(c.profile(),line)) continue;
            if (storeId.equals(line.inputStoreId()) && c.session().allows(c.profile(),Feature.WINE)) return true;
            if (storeId.equals(line.outputStoreId()) && (c.session().allows(c.profile(),Feature.WINE)
                || c.session().allows(c.profile(),Feature.WINE_STORAGE)
                || c.session().allows(c.profile(),Feature.WINE_SURPLUS_SHIPPING))) return true;
        }
        return false;
    }
    private static boolean wineDepositAllows(Context c,String storeId,ItemData item) {
        if (item==null) return false;
        for (WineProductionLine line:WineProductionRules.lines(c.profile())) {
            if (!line.enabled() || WineProductionRules.LEGACY_ID.equals(line.id()) || !WineProductionRules.configured(c.profile(),line)) continue;
            if (storeId.equals(line.inputStoreId()) && item.is(line.inputItemId())
                && c.session().allows(c.profile(),Feature.WINE)) return true;
            if (storeId.equals(line.outputStoreId()) && item.is(line.outputItemId())
                && (c.session().allows(c.profile(),Feature.WINE) || c.session().allows(c.profile(),Feature.WINE_STORAGE))) return true;
        }
        return false;
    }
    private static boolean scopeAllows(Context c,String storeId) {
        if(c.session().allows(c.profile(),Feature.COMMODITY_STORAGE))return true;
        if(c.session().allows(c.profile(),Feature.HARVEST) && c.profile().cropStores!=null && c.profile().cropStores.containsValue(storeId))return true;
        if(c.session().allows(c.profile(),Feature.STARFRUIT) && c.profile().fruitPatches!=null
            && c.profile().fruitPatches.stream().anyMatch(p->p!=null && p.valid() && p.storeId().equals(storeId)))return true;
        if(c.profile().artisanJobs!=null)for(ArtisanJob job:c.profile().artisanJobs.values()) {
            if(job==null)continue;
            ArtisanRecipe recipe=ArtisanRecipe.find(job.recipeId());
            if(recipe!=null && c.session().allows(c.profile(),recipe.feature())
                && (storeId.equals(job.inputStoreId()) || storeId.equals(job.outputStoreId())))return true;
        }
        return false;
    }
}
