package dev.schwalbe.autovalley.core;

import java.util.*;

/** Shared validation for data-driven crop, storage and artisan registrations. */
public final class AdditionalWorkRules {
    private AdditionalWorkRules() { }
    public static void validate(Profile p) {
        if(p.crops==null)p.crops=CropRules.defaults();
        if(p.commodityStores==null)p.commodityStores=new LinkedHashMap<>();
        if(p.cropStores==null)p.cropStores=new LinkedHashMap<>();
        if(p.artisanJobs==null)p.artisanJobs=new LinkedHashMap<>();
        if(p.fruitPatches==null)p.fruitPatches=new ArrayList<>();
        if(p.orchardDrafts==null)p.orchardDrafts=new ArrayList<>();
        if(p.crops.size()>64 || p.commodityStores.size()>128 || p.artisanJobs.size()>128 || p.fruitPatches.size()>128)
            throw new IllegalArgumentException("Too many additional work definitions");
        if(p.orchardDrafts.size()>OrchardDraft.MAX_DRAFTS)throw new IllegalArgumentException("Too many orchard drafts");
        Set<String> draftIds=new HashSet<>();Set<Pos> draftFruits=new HashSet<>();
        for(OrchardDraft draft:p.orchardDrafts) {
            if(draft==null || !draft.valid() || !draftIds.add(draft.id()))throw new IllegalArgumentException("Invalid orchard draft");
            for(Pos pos:draft.observedFruits())if(!draftFruits.add(pos))throw new IllegalArgumentException("Duplicate orchard observation");
        }
        for(var entry:p.crops.entrySet())if(!CropRules.valid(entry.getValue()) || !entry.getKey().equals(entry.getValue().key()))
            throw new IllegalArgumentException("Invalid crop definition");
        for(Farm farm:p.farms)if(CropRules.definition(p,farm)==null || !CoordinateDestinationRules.validPosition(farm.first())
            || !CoordinateDestinationRules.validPosition(farm.second()))throw new IllegalArgumentException("Farm has no valid crop definition or location");
        for(var entry:p.commodityStores.entrySet()) {
            CommodityStore store=entry.getValue();
            if(store==null || !store.valid() || !entry.getKey().equals(store.id()) || store.containers().stream().anyMatch(pos->!CoordinateDestinationRules.validPosition(pos)))
                throw new IllegalArgumentException("Invalid commodity storage group");
            if(p.pois.stream().anyMatch(poi->store.containers().contains(poi.pos()) && poi.kind()!=PoiKind.STORAGE_CANDIDATE))
                throw new IllegalArgumentException("Commodity groups cannot override shipping or existing reserved storage roles");
        }
        for(var entry:p.cropStores.entrySet()) {
            CropDefinition crop=CropRules.definition(p,entry.getKey());
            CommodityStore store=CommodityStorageRules.store(p,entry.getValue());
            if(crop==null || store==null || !store.items().contains(crop.itemId()))throw new IllegalArgumentException("Crop storage must accept its commodity");
        }
        Set<Pos> machines=new HashSet<>();
        for(var entry:p.artisanJobs.entrySet()) {
            ArtisanJob job=entry.getValue();
            if(job==null || job.id()==null || !job.id().matches("[a-z0-9_][a-z0-9_.-]{0,63}") || !entry.getKey().equals(job.id())
                || job.machines().isEmpty() || job.machines().size()>4096)throw new IllegalArgumentException("Invalid artisan job");
            ArtisanRecipe recipe=ArtisanRecipe.find(job.recipeId());
            CommodityStore input=CommodityStorageRules.store(p,job.inputStoreId()),output=CommodityStorageRules.store(p,job.outputStoreId());
            if(recipe==null || input==null || output==null || !input.items().contains(recipe.inputId()) || !output.items().contains(recipe.outputId()))
                throw new IllegalArgumentException("Artisan input/output store does not accept the recipe");
            for(Pos pos:job.machines())if(!CoordinateDestinationRules.validPosition(pos) || !machines.add(pos))
                throw new IllegalArgumentException("Artisan machines need unambiguous ownership");
        }
        Set<String> patches=new HashSet<>();Set<Pos> fruits=new HashSet<>();
        for(FruitPatch patch:p.fruitPatches) {
            if(patch==null || !patch.valid() || !patches.add(patch.id()))throw new IllegalArgumentException("Invalid fruit patch");
            CommodityStore store=CommodityStorageRules.store(p,patch.storeId());
            if(store==null || !store.items().contains(FruitRules.ITEM))throw new IllegalArgumentException("Fruit patch has no compatible store");
            for(Pos pos:patch.fruits())if(!CoordinateDestinationRules.validPosition(pos) || !fruits.add(pos))throw new IllegalArgumentException("Invalid fruit position");
        }
    }
    public static List<Pos> sites(Profile p) {
        // Orchard drafts contain observations, never registered work locations or harvesting origins.
        List<Pos> result=new ArrayList<>();
        if(p.commodityStores!=null)p.commodityStores.values().stream().filter(s->s!=null && s.valid()).forEach(s->result.addAll(s.containers()));
        if(p.artisanJobs!=null)p.artisanJobs.values().stream().filter(Objects::nonNull).forEach(j->result.addAll(j.machines()));
        if(p.wineProductionLines!=null)p.wineProductionLines.values().stream().filter(Objects::nonNull).forEach(j->result.addAll(j.machines()));
        if(p.fruitPatches!=null)p.fruitPatches.stream().filter(s->s!=null && s.valid()).forEach(s->result.addAll(s.fruits()));
        return List.copyOf(result);
    }
}
