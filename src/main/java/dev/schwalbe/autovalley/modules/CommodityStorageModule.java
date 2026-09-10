package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** One shared, quality-agnostic deposit routine for named item storage groups. */
public final class CommodityStorageModule extends DepositModule {
    private final Feature scope;
    private final String storeId;
    private final Set<String> restrictItems;
    private Profile profile;
    public CommodityStorageModule(){this(Feature.COMMODITY_STORAGE,null,Set.of());}
    public CommodityStorageModule(Feature scope,String storeId){this(scope,storeId,Set.of());}
    public CommodityStorageModule(Feature scope,String storeId,Set<String> restrictItems) {
        this.scope=Objects.requireNonNull(scope);this.storeId=storeId;this.restrictItems=Set.copyOf(restrictItems);
    }
    @Override public Feature feature(){return scope;}
    @Override public int priority(){return 21;}
    @Override protected String itemId(){return "registered commodity";}
    @Override protected PoiKind destinationKind(){return PoiKind.STORAGE_CANDIDATE;}
    @Override protected Integer classifier(ItemData item){return null;}
    @Override protected boolean requiresKnownQuality(){return false;}
    @Override public WorkResult tick(Context c){profile=c.profile();return super.tick(c);}
    @Override protected boolean accepts(ItemData item) {
        return profile!=null && item!=null && !item.empty() && (restrictItems.isEmpty() || restrictItems.contains(item.id()))
            && (storeId!=null || profile.wineProductionLines==null || profile.wineProductionLines.values().stream()
                .filter(Objects::nonNull).noneMatch(line->line.outputItemId().equals(item.id())))
            && stores().stream().anyMatch(store->store.accepts(item));
    }
    private List<CommodityStore> stores() {
        if(storeId!=null) { CommodityStore store=CommodityStorageRules.store(profile,storeId);return store==null ? List.of() : List.of(store); }
        return profile.commodityStores==null ? List.of() : profile.commodityStores.entrySet().stream()
            .filter(e->e.getValue()!=null && e.getValue().valid() && e.getKey().equals(e.getValue().id())).map(Map.Entry::getValue).toList();
    }
    @Override protected List<Poi> destinations(Context c,ItemData item) {
        return stores().stream().filter(store->store.accepts(item)).flatMap(store->store.containers().stream())
            .distinct().map(pos->new Poi(pos,PoiKind.STORAGE_CANDIDATE,"commodity",null)).toList();
    }
    @Override protected boolean prepareDestination(Context c,Poi destination,ItemData item) {
        return stores().stream().anyMatch(store->store.accepts(item) && store.containers().contains(destination.pos()))
            && CommodityStorageRules.depositAllowed(c,destination.pos(),item);
    }
}
