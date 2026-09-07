package dev.schwalbe.autovalley.core;

import java.util.Set;

/** A short-lived allowance to ship NEW inventory wine only, never withdraw the reserved stock. */
public record WineSalePermit(int year,int inventoryLimit,long verifiedTick,long gameDay,Set<Pos> storages) {
    public WineSalePermit { storages=Set.copyOf(storages); }
    public WineSalePermit consumed(int count) {
        return new WineSalePermit(year,Math.max(0,inventoryLimit-Math.max(0,count)),verifiedTick,gameDay,storages);
    }
}
