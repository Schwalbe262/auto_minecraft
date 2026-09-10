package dev.schwalbe.autovalley.core;

import java.util.Set;

/** Session-only sale budget for carried output after every container in this line's reserve was full. */
public record WineLineSalePermit(String lineId,String outputItemId,String outputStoreId,int inventoryLimit,
                                 long verifiedTick,long gameDay,Set<Pos> storages) {
    public WineLineSalePermit { storages=Set.copyOf(storages); }
    public WineLineSalePermit consumed(int amount) {
        return new WineLineSalePermit(lineId,outputItemId,outputStoreId,
            Math.max(0,inventoryLimit-Math.max(0,amount)),verifiedTick,gameDay,storages);
    }
}
