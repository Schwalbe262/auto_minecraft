package dev.schwalbe.autovalley.core;

import java.util.Set;

/** RAM-only permission for carried tomatoes after a complete warehouse survey. */
public record TomatoSalePermit(int inventoryLimit,long verifiedTick,long gameDay,int limitPercent,
                               long stored,long capacity,Set<Pos> storages,long cacheEpoch) {
    public TomatoSalePermit { storages=Set.copyOf(storages); }
    /** Legacy direct-survey callers retain their original, non-cache permission checks. */
    public TomatoSalePermit(int inventoryLimit,long verifiedTick,long gameDay,int limitPercent,
                            long stored,long capacity,Set<Pos> storages) {
        this(inventoryLimit,verifiedTick,gameDay,limitPercent,stored,capacity,storages,-1);
    }
    public TomatoSalePermit consumed(int count) {
        return withInventoryLimit(Math.max(0,inventoryLimit-Math.max(0,count)));
    }
    /** Quantity replacement never renews the original batch's age or warehouse evidence. */
    public TomatoSalePermit withInventoryLimit(int count) {
        return new TomatoSalePermit(count,verifiedTick,gameDay,limitPercent,stored,capacity,storages,cacheEpoch);
    }
}
