package dev.schwalbe.autovalley.core;

import java.util.Set;
import java.util.stream.Collectors;

public final class WineSaleRules {
    private WineSaleRules() { }
    public static boolean permitted(ItemData item,Context c) {
        if (!c.profile().tomatoWineEnabled || !c.session().allows(c.profile(),Feature.WINE_SURPLUS_SHIPPING) || !item.is(ItemData.WINE) || item.year()==null) return false;
        Integer now=c.world().wineYear();
        if (now==null || item.year()<0 || item.year()>now) return false;
        WineSalePermit permit=c.session().wineSalePermits.get(item.year());
        if (permit==null || permit.year()!=item.year() || permit.inventoryLimit()<item.count()
            || c.world().tick()<permit.verifiedTick() || c.world().tick()-permit.verifiedTick()>=1200
            || Math.floorDiv(c.world().dayTime(),24000)!=permit.gameDay()) return false;
        Set<Pos> registered=c.profile().pois(PoiKind.WINE_CHEST).stream().filter(p -> item.year().equals(p.classifier())).map(Poi::pos).collect(Collectors.toSet());
        return !registered.isEmpty() && registered.equals(permit.storages()) && registered.stream().allMatch(c.world()::loaded);
    }
    public static void consume(ItemData item,int amount,Context c) {
        if (!item.is(ItemData.WINE) || item.year()==null || amount<=0) return;
        c.session().wineSalePermits.computeIfPresent(item.year(),(year,permit) -> {
            WineSalePermit remaining=permit.consumed(amount);
            return remaining.inventoryLimit()>0 ? remaining : null;
        });
    }
}
