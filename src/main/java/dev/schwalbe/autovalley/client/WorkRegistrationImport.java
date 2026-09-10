package dev.schwalbe.autovalley.client;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Explicit, add-only registration import. Never replays a recording or replaces a live schedule. */
public final class WorkRegistrationImport {
    private static final Gson GSON=new Gson();
    public record Data(Map<String,CropDefinition> crops,List<Farm> farms,Map<String,CommodityStore> stores,
                       Map<String,String> cropStores,Map<String,ArtisanJob> artisanJobs,List<FruitPatch> fruitPatches,
                       Map<String,WineProductionLine> wineProductionLines,List<OrchardDraft> orchardDrafts,Set<Feature> enable) {
        public Data(Map<String,CropDefinition> crops,List<Farm> farms,Map<String,CommodityStore> stores,
                    Map<String,String> cropStores,Map<String,ArtisanJob> artisanJobs,List<FruitPatch> fruitPatches,
                    Map<String,WineProductionLine> wineProductionLines,Set<Feature> enable) {
            this(crops,farms,stores,cropStores,artisanJobs,fruitPatches,wineProductionLines,List.of(),enable);
        }
        public Data(Map<String,CropDefinition> crops,List<Farm> farms,Map<String,CommodityStore> stores,
                    Map<String,String> cropStores,Map<String,ArtisanJob> artisanJobs,List<FruitPatch> fruitPatches,Set<Feature> enable) {
            this(crops,farms,stores,cropStores,artisanJobs,fruitPatches,Map.of(),List.of(),enable);
        }
    }
    private WorkRegistrationImport() { }
    public static Profile merge(Profile current,String json) {
        if(json==null || json.length()>200_000)throw new IllegalArgumentException("Work import is too large");
        JsonElement tree=JsonParser.parseString(json);
        if(!tree.isJsonObject() || !Set.of("crops","farms","stores","cropStores","artisanJobs","fruitPatches","wineProductionLines","orchardDrafts","enable")
                .containsAll(tree.getAsJsonObject().keySet()))throw new IllegalArgumentException("Unknown work import field");
        validateDraftFields(tree.getAsJsonObject().get("orchardDrafts"));
        Data data=GSON.fromJson(tree,Data.class);
        if(data.orchardDrafts()!=null && !data.orchardDrafts().isEmpty() && data.enable()!=null && !data.enable().isEmpty())
            throw new IllegalArgumentException("Orchard draft imports cannot enable automation");
        Profile candidate=GSON.fromJson(GSON.toJson(current),Profile.class);
        ProfileStore.validate(candidate);
        add(candidate.crops,data.crops());add(candidate.commodityStores,data.stores());
        add(candidate.cropStores,data.cropStores());add(candidate.artisanJobs,data.artisanJobs());
        add(candidate.wineProductionLines,data.wineProductionLines());
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
        if(data.orchardDrafts()!=null)for(OrchardDraft draft:data.orchardDrafts()) {
            if(draft==null || !draft.valid())throw new IllegalArgumentException("Invalid orchard draft");
            OrchardDraft existing=candidate.orchardDrafts.stream().filter(d->d.id().equals(draft.id())).findFirst().orElse(null);
            if(existing!=null && !existing.equals(draft))throw new IllegalArgumentException("Import cannot overwrite an existing orchard draft");
            if(existing==null)candidate.orchardDrafts.add(draft);
        }
        if(data.enable()!=null)for(Feature feature:data.enable()) {
            boolean wine=feature==Feature.WINE || feature==Feature.WINE_STORAGE || feature==Feature.WINE_SURPLUS_SHIPPING;
            if(wine && (data.wineProductionLines()==null || data.wineProductionLines().isEmpty()))
                throw new IllegalArgumentException("Wine switches require an explicit wine-line registration");
            if(feature==null || !Set.of(Feature.HARVEST,Feature.COMMODITY_STORAGE,Feature.SEED_MAKER,Feature.CRYSTAL_COPY,Feature.STARFRUIT,
                    Feature.WINE,Feature.WINE_STORAGE,Feature.WINE_SURPLUS_SHIPPING).contains(feature))
                throw new IllegalArgumentException("Import cannot change unrelated feature switches");
            candidate.enabled.put(feature,true);
        }
        ProfileStore.validate(candidate);return candidate;
    }
    private static void validateDraftFields(JsonElement drafts) {
        if(drafts==null || drafts.isJsonNull())return;
        if(!drafts.isJsonArray() || drafts.getAsJsonArray().size()>OrchardDraft.MAX_DRAFTS)
            throw new IllegalArgumentException("Invalid orchard drafts");
        Set<String> fields=Set.of("id","name","toolItemId","fruitBlockId","outputItemId","observedFruits","sourceRecording");
        for(JsonElement draft:drafts.getAsJsonArray()) {
            if(!draft.isJsonObject() || !fields.equals(draft.getAsJsonObject().keySet()))
                throw new IllegalArgumentException("Unknown or missing orchard draft field");
            JsonObject object=draft.getAsJsonObject();
            for(String field:fields)if(!field.equals("observedFruits")) {
                JsonElement value=object.get(field);
                if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Orchard metadata must be text");
            }
            JsonElement fruits=object.get("observedFruits");
            if(!fruits.isJsonArray() || fruits.getAsJsonArray().isEmpty() || fruits.getAsJsonArray().size()>OrchardDraft.MAX_OBSERVED_FRUITS)
                throw new IllegalArgumentException("Invalid orchard observations");
            for(JsonElement fruit:fruits.getAsJsonArray()) {
                if(!fruit.isJsonObject() || !Set.of("x","y","z").equals(fruit.getAsJsonObject().keySet()))
                    throw new IllegalArgumentException("Orchard observations need all three coordinates");
                for(JsonElement coordinate:fruit.getAsJsonObject().asMap().values()) {
                    if(!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber())
                        throw new IllegalArgumentException("Orchard coordinates must be integers");
                    try {coordinate.getAsBigDecimal().intValueExact();}
                    catch(ArithmeticException | NumberFormatException invalid) {
                        throw new IllegalArgumentException("Orchard coordinates must be integers",invalid);
                    }
                }
            }
        }
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
