package dev.schwalbe.autovalley.core;

import java.util.Set;

/** RAM-only permission for carried tomatoes after a complete warehouse survey. */
public record TomatoSalePermit(int inventoryLimit,long verifiedTick,long gameDay,int limitPercent,
                               long stored,long capacity,Set<Pos> storages) {
    public TomatoSalePermit { storages=Set.copyOf(storages); }
    public TomatoSalePermit consumed(int count) {
        return new TomatoSalePermit(Math.max(0,inventoryLimit-Math.max(0,count)),verifiedTick,gameDay,
            limitPercent,stored,capacity,storages);
    }
}
