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
        if (!enabled(c) || item==null || !item.is(ItemData.TOMATO) || p==null || p.inventoryLimit()<item.count()
            || p.capacity()<=0 || p.capacity()>4096L*54*64 || p.stored()<reserve(p.capacity(),p.limitPercent())
            || p.stored()>p.capacity() || p.limitPercent()!=c.profile().tomatoStorageLimitPercent
            || c.world().tick()<p.verifiedTick() || c.world().tick()-p.verifiedTick()>=1200
            || Math.floorDiv(c.world().dayTime(),24000L)!=p.gameDay()) return false;
        List<Poi> stores=c.profile().pois(PoiKind.TOMATO_CHEST);
        Set<Pos> positions=stores.stream().map(Poi::pos).collect(Collectors.toSet());
        return !positions.isEmpty() && positions.size()==stores.size() && positions.equals(p.storages())
            && positions.stream().allMatch(c.world()::loaded);
    }
    public static void consume(int amount,Context c) {
        if(amount>0 && c.session().tomatoSalePermit!=null) {
            TomatoSalePermit remaining=c.session().tomatoSalePermit.consumed(amount);
            c.session().tomatoSalePermit=remaining.inventoryLimit()>0 ? remaining : null;
        }
    }
}
