package dev.schwalbe.autovalley.core;

import java.util.*;
import static dev.schwalbe.autovalley.core.StorageSurveyObservation.Status;

/** Conservative classification of every storage slot in an acknowledged ordinary menu. */
public final class StorageSurveyRules {
    private StorageSurveyRules() { }
    private record StockKey(String id, int quality, Integer cohort) { }

    public static boolean ordinaryStorage(BlockData block) {
        return block!=null && block.properties()!=null && block.flag("container")
            && ("minecraft:barrel".equals(block.id()) || "minecraft:chest".equals(block.id())
                || "minecraft:trapped_chest".equals(block.id()));
    }

    public static StorageSurveyObservation inspect(Pos pos, long tick, List<ItemSlot> slots) {
        Objects.requireNonNull(pos,"position"); Objects.requireNonNull(slots,"slots");
        Map<StockKey,Integer> totals=new LinkedHashMap<>();
        Set<Integer> indices=new HashSet<>();
        int storage=0, empty=0;
        boolean unknown=false;
        for (ItemSlot slot:slots) {
            if (slot==null || slot.index()<0 || !indices.add(slot.index()) || slot.item()==null)
                throw new IllegalArgumentException("Invalid or duplicate menu slot");
            if (slot.player()) continue;
            storage++;
            ItemData item=slot.item();
            if (item.id()==null || item.id().isBlank() || item.count()<0
                || "minecraft:air".equals(item.id()) && item.count()!=0)
                throw new IllegalArgumentException("Invalid storage item");
            if (item.empty()) { empty++; continue; }
            StockKey key=new StockKey(item.id(),item.quality(),item.year());
            totals.merge(key,item.count(),Math::addExact);
            if (item.is(ItemData.WINE) && (item.quality()<0 || item.quality()>3)) unknown=true;
            if (item.is(ItemData.WINE) && (item.year()==null || item.year()<0)) unknown=true;
        }
        List<StorageSurveyObservation.Stock> contents=totals.entrySet().stream()
            .map(e -> new StorageSurveyObservation.Stock(e.getKey().id(),e.getValue(),
                e.getKey().quality(),e.getKey().cohort())).toList();
        Status status; Integer classifier=null;
        if (contents.isEmpty()) status=Status.EMPTY;
        else if (unknown) status=Status.UNKNOWN;
        else if (contents.stream().allMatch(s -> s.itemId().equals(ItemData.TOMATO))) {
            // Grade remains in the observations for stock counting, not as a
            // warehouse restriction. Even an unknown grade does not change the item.
            status=Status.TOMATO;
        } else if (contents.stream().allMatch(s -> s.itemId().equals(ItemData.WINE))) {
            Set<Integer> cohorts=new HashSet<>(); contents.forEach(s -> cohorts.add(s.cohort()));
            status=cohorts.size()==1 ? Status.WINE : Status.MIXED;
            if (status==Status.WINE) classifier=cohorts.iterator().next();
        } else {
            status=contents.stream().map(StorageSurveyObservation.Stock::itemId).distinct().count()==1
                ? Status.OTHER : Status.MIXED;
        }
        return new StorageSurveyObservation(pos,tick,storage,empty,contents,status,classifier);
    }
}
