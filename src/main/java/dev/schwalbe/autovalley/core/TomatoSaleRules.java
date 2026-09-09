package dev.schwalbe.autovalley.core;

import java.util.*;
import java.util.stream.Collectors;

public final class TomatoSaleRules {
    private TomatoSaleRules() { }
    public static boolean enabled(Context c) {
        Feature once=c.session().oneShotFeature;
        return c.profile().tomatoSurplusShippingEnabled && c.profile().enabled(Feature.SHIPPING)
            && c.profile().tomatoStorageLimitPercent>=1 && c.profile().tomatoStorageLimitPercent<=100
            && (once==null && (c.profile().enabled(Feature.TOMATO_STORAGE) || c.profile().enabled(Feature.HARVEST))
                || once==Feature.HARVEST || once==Feature.TOMATO_STORAGE);
    }
    public static long reserve(long capacity,int percent) { return (Math.multiplyExact(capacity,percent)+99)/100; }
    public static boolean permitted(ItemData item,Context c) {
        TomatoSalePermit p=c.session().tomatoSalePermit;
        return item!=null && !item.empty() && item.is(ItemData.TOMATO) && p!=null
            && p.inventoryLimit()>=item.count() && validProof(p,c);
    }
    /** Checks evidence independently of the remaining quantity; it does not issue permission. */
    public static boolean validProof(TomatoSalePermit p,Context c) {
        return routeHintValid(p,c) && p.storages().stream().allMatch(c.world()::loaded);
    }
    /** Remembered stock may guide travel from unloaded terrain, never an OPEN or transfer. */
    public static boolean routeHintValid(TomatoSalePermit p,Context c) {
        if (!enabled(c) || p==null || p.inventoryLimit()<0
            || p.capacity()<=0 || p.capacity()>4096L*54*64 || p.stored()<reserve(p.capacity(),p.limitPercent())
            || p.stored()>p.capacity() || p.limitPercent()!=c.profile().tomatoStorageLimitPercent
            || c.world().tick()<p.verifiedTick() || c.world().tick()-p.verifiedTick()>=1200
            || Math.floorDiv(c.world().dayTime(),24000L)!=p.gameDay()) return false;
        List<Poi> stores=c.profile().pois(PoiKind.TOMATO_CHEST);
        Set<Pos> positions=stores.stream().map(Poi::pos).collect(Collectors.toSet());
        if(positions.isEmpty() || positions.size()!=stores.size() || !positions.equals(p.storages()))return false;
        if(p.cacheEpoch()<0)return true;
        return c.session().tomatoStockCache.reusable(c).filter(view->view.epoch()==p.cacheEpoch()
            && view.capacity()==p.capacity() && view.total()>=reserve(view.capacity(),p.limitPercent())).isPresent();
    }
    public static void consume(int amount,Context c) {
        if(amount>0 && c.session().tomatoSalePermit!=null) {
            TomatoSalePermit remaining=c.session().tomatoSalePermit.consumed(amount);
            c.session().tomatoSalePermit=remaining.inventoryLimit()>0 ? remaining : null;
        }
    }
}
