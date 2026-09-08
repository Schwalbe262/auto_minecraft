package dev.schwalbe.autovalley.client;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Explicit, add-only registration import. Never replays a recording or replaces a live schedule. */
public final class WorkRegistrationImport {
    private static final Gson GSON=new Gson();
    public record Data(Map<String,CropDefinition> crops,List<Farm> farms,Map<String,CommodityStore> stores,
                       Map<String,String> cropStores,Map<String,ArtisanJob> artisanJobs,List<FruitPatch> fruitPatches,
                       Set<Feature> enable) { }
    private WorkRegistrationImport() { }
    public static Profile merge(Profile current,String json) {
        if(json==null || json.length()>200_000)throw new IllegalArgumentException("Work import is too large");
        JsonElement tree=JsonParser.parseString(json);
        if(!tree.isJsonObject() || !Set.of("crops","farms","stores","cropStores","artisanJobs","fruitPatches","enable")
                .containsAll(tree.getAsJsonObject().keySet()))throw new IllegalArgumentException("Unknown work import field");
        Data data=GSON.fromJson(tree,Data.class);
        Profile candidate=GSON.fromJson(GSON.toJson(current),Profile.class);
        ProfileStore.validate(candidate);
        add(candidate.crops,data.crops());add(candidate.commodityStores,data.stores());
        add(candidate.cropStores,data.cropStores());add(candidate.artisanJobs,data.artisanJobs());
        if(data.farms()!=null)for(Farm farm:data.farms()) {
            if(farm==null)throw new IllegalArgumentException("Null farm");
            Farm existing=candidate.farms.stream().filter(f->f.name().equals(farm.name())).findFirst().orElse(null);
            if(existing!=null && !existing.equals(farm))throw new IllegalArgumentException("Import cannot overwrite an existing farm");
            if(existing==null) {
                if(candidate.farms.stream().anyMatch(f->dev.schwalbe.autovalley.ui.RegistrationRules.overlap(f,farm)))
                    throw new IllegalArgumentException("Imported farm overlaps an existing field");
                candidate.farms.add(farm);
            }
        }
        if(data.fruitPatches()!=null)for(FruitPatch patch:data.fruitPatches()) {
            if(patch==null)throw new IllegalArgumentException("Null fruit patch");
            FruitPatch existing=candidate.fruitPatches.stream().filter(p->p.id().equals(patch.id())).findFirst().orElse(null);
            if(existing!=null && !existing.equals(patch))throw new IllegalArgumentException("Import cannot overwrite an existing fruit patch");
            if(existing==null)candidate.fruitPatches.add(patch);
        }
        if(data.enable()!=null)for(Feature feature:data.enable()) {
            if(feature==null || !Set.of(Feature.HARVEST,Feature.COMMODITY_STORAGE,Feature.SEED_MAKER,Feature.CRYSTAL_COPY,Feature.STARFRUIT).contains(feature))
                throw new IllegalArgumentException("Import cannot change unrelated feature switches");
            candidate.enabled.put(feature,true);
        }
        ProfileStore.validate(candidate);return candidate;
    }
    private static <T> void add(Map<String,T> target,Map<String,T> additions) {
        if(additions==null)return;
        for(var entry:additions.entrySet()) {
            if(entry.getKey()==null || entry.getValue()==null)throw new IllegalArgumentException("Null registration");
            if(target.containsKey(entry.getKey()) && !target.get(entry.getKey()).equals(entry.getValue()))
                throw new IllegalArgumentException("Import cannot overwrite an existing registration: "+entry.getKey());
            target.putIfAbsent(entry.getKey(),entry.getValue());
        }
    }
}
