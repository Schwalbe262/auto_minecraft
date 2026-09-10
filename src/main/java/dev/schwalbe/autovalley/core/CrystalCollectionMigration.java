package dev.schwalbe.autovalley.core;

import java.util.*;

/** Narrow migration of the original imported jade-only facility, never a scan of arbitrary nearby storage. */
public final class CrystalCollectionMigration {
    private CrystalCollectionMigration() { }
    public static void migrate(Profile profile) {
        if(profile.artisanJobs==null || profile.commodityStores==null)return;
        ArtisanJob job=profile.artisanJobs.get("jade_crystalariums");
        if(job==null || !job.id().equals("jade_crystalariums") || !job.recipeId().equals(ArtisanRecipe.JADE_CRYSTAL.id())
                || !job.inputStoreId().equals("jade_stock") || !job.outputStoreId().equals("jade_stock"))return;
        CommodityStore store=CommodityStorageRules.store(profile,"jade_stock");
        if(store==null || !store.name().equals("옥 결정복제기 재료·산출물") || !store.items().equals(Set.of("society:jade")))return;
        // Do not widen a customized/shared store's permissions on behalf of another consumer.
        if(profile.artisanJobs.values().stream().anyMatch(other->other!=job
                && (store.id().equals(other.inputStoreId()) || store.id().equals(other.outputStoreId())))
                || profile.cropStores.containsValue(store.id())
                || profile.fruitPatches.stream().anyMatch(patch->store.id().equals(patch.storeId()))
                || profile.wineProductionLines.values().stream().anyMatch(line->store.id().equals(line.inputStoreId())
                    || store.id().equals(line.outputStoreId()))
                || profile.commodityStores.values().stream().anyMatch(other->other!=store
                    && mayShareInventory(store.containers(),other.containers())))return;
        profile.commodityStores.put(store.id(),new CommodityStore(store.id(),"결정생성기 산출물 보관함",
            CrystalCollection.BASE_OUTPUT_IDS,store.containers()));
        profile.artisanJobs.put(job.id(),new ArtisanJob(job.id(),ArtisanRecipe.CRYSTAL_COLLECTION.id(),job.machines(),
            job.inputStoreId(),job.outputStoreId()));
        // IDs, positions, feature switches and old production deadlines remain intact.
        // The module uses separate daily inspection keys; no date here claims an automatic refill.
    }
    private static boolean mayShareInventory(List<Pos> first,List<Pos> second) {
        // Offline profile loading cannot distinguish adjacent barrels from two halves
        // of one chest. Conservatively leave either arrangement's permissions alone.
        return first.stream().anyMatch(a->second.stream().anyMatch(b->a.y()==b.y()
            && Math.abs((long)a.x()-b.x())+Math.abs((long)a.z()-b.z())<=1));
    }
}
