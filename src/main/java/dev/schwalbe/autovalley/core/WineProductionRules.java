package dev.schwalbe.autovalley.core;

import java.util.*;

/** Exact recipe and facility authority shared by production, storage and native adapters. */
public final class WineProductionRules {
    public static final String LEGACY_ID="tomato";
    public static final String ANCIENT_FRUIT="society:ancient_fruit";
    public static final String ANCIENT_WINE="society:ancient_vespertine";
    private static final Map<String,String> RECIPES=Map.of(ItemData.TOMATO,ItemData.WINE,ANCIENT_FRUIT,ANCIENT_WINE);
    private WineProductionRules() { }
    public static boolean supportedRecipe(String input,String output) { return input!=null && Objects.equals(RECIPES.get(input),output) && output!=null; }
    public static WineProductionLine line(Profile profile,String id) {
        if (profile==null || id==null) return null;
        if (LEGACY_ID.equals(id)) return new WineProductionLine(LEGACY_ID,"토마토 와인",ItemData.TOMATO,ItemData.WINE,null,null,
            profile.pois(PoiKind.WINE_KEG).stream().map(Poi::pos).toList(),profile.wineCycleDays,profile.tomatoWineEnabled);
        WineProductionLine line=profile.wineProductionLines==null ? null : profile.wineProductionLines.get(id);
        return line!=null && id.equals(line.id()) && line.valid() && supportedRecipe(line.inputItemId(),line.outputItemId()) ? line : null;
    }
    /** Disabled lines remain registered authorities; callers choose whether execution is enabled. */
    public static List<WineProductionLine> lines(Profile profile) {
        if (profile==null) return List.of();
        List<WineProductionLine> result=new ArrayList<>(); result.add(line(profile,LEGACY_ID));
        if (profile.wineProductionLines!=null) for(String id:profile.wineProductionLines.keySet()) {
            if(LEGACY_ID.equals(id))continue;
            WineProductionLine line=line(profile,id); if(line!=null)result.add(line);
        }
        return List.copyOf(result);
    }
    public static WineProductionLine at(Profile profile,Pos pos) {
        if(pos==null)return null;
        List<WineProductionLine> matches=lines(profile).stream().filter(line->line.machines().contains(pos)).toList();
        return matches.size()==1 ? matches.get(0) : null;
    }
    public static CommodityStore inputStore(Profile profile,WineProductionLine line) {
        return line==null || LEGACY_ID.equals(line.id()) ? null : CommodityStorageRules.store(profile,line.inputStoreId());
    }
    public static CommodityStore outputStore(Profile profile,WineProductionLine line) {
        return line==null || LEGACY_ID.equals(line.id()) ? null : CommodityStorageRules.store(profile,line.outputStoreId());
    }
    public static boolean input(ItemData item,WineProductionLine line) {
        return line!=null && item!=null && item.is(line.inputItemId()) && item.quality()>=0 && item.quality()<4;
    }
    public static boolean allowed(Context c,WineProductionLine line) {
        return c!=null && line!=null && line.enabled() && c.session().allows(c.profile(),Feature.WINE)
            && line.equals(line(c.profile(),line.id())) && configured(c.profile(),line);
    }
    public static boolean configured(Profile profile,WineProductionLine line) {
        if(line==null || !line.valid() || !supportedRecipe(line.inputItemId(),line.outputItemId()))return false;
        Set<Pos> owned=new HashSet<>(line.machines());
        for(WineProductionLine other:lines(profile)) if(!other.id().equals(line.id()) && !Collections.disjoint(owned,other.machines()))return false;
        if(LEGACY_ID.equals(line.id()))return true;
        CommodityStore input=inputStore(profile,line), output=outputStore(profile,line);
        if(input==null || output==null || !input.items().contains(line.inputItemId()) || !output.items().equals(Set.of(line.outputItemId()))
            || input.id().equals(output.id()) || !Collections.disjoint(input.containers(),output.containers()))return false;
        Set<Pos> outputCells=new HashSet<>(output.containers());
        if(profile.pois.stream().anyMatch(poi->owned.contains(poi.pos())
            || outputCells.contains(poi.pos()) && poi.kind()!=PoiKind.STORAGE_CANDIDATE))return false;
        for(CommodityStore store:profile.commodityStores.values()) {
            if(store==null || !Collections.disjoint(owned,store.containers()))return false;
            if(!store.id().equals(output.id()) && !Collections.disjoint(outputCells,store.containers()))return false;
        }
        if(profile.artisanJobs!=null)for(ArtisanJob job:profile.artisanJobs.values()) {
            if(job==null || !Collections.disjoint(owned,job.machines()) || !Collections.disjoint(outputCells,job.machines())
                || output.id().equals(job.inputStoreId()) || output.id().equals(job.outputStoreId()))return false;
        }
        if(profile.cropStores!=null && profile.cropStores.containsValue(output.id()))return false;
        if(profile.fruitPatches!=null && profile.fruitPatches.stream().anyMatch(patch->patch!=null && output.id().equals(patch.storeId())))return false;
        for(WineProductionLine other:lines(profile)) if(!other.id().equals(line.id())
            && (output.id().equals(other.inputStoreId()) || output.id().equals(other.outputStoreId())))return false;
        return true;
    }
    public static List<Poi> machines(Profile profile,WineProductionLine line) {
        if(line==null)return List.of();
        if(LEGACY_ID.equals(line.id()))return profile.pois(PoiKind.WINE_KEG);
        List<Poi> result=new ArrayList<>();int index=0;
        for(Pos pos:line.machines())result.add(new Poi(pos,PoiKind.WINE_KEG,line.name()+" "+(++index),null));
        return List.copyOf(result);
    }
    public static List<Poi> sources(Profile profile,WineProductionLine line) {
        if(line==null || LEGACY_ID.equals(line.id()))return profile.pois(PoiKind.TOMATO_CHEST);
        CommodityStore store=inputStore(profile,line);if(store==null)return List.of();
        List<Poi> result=new ArrayList<>();int index=0;
        for(Pos pos:store.containers())result.add(new Poi(pos,PoiKind.STORAGE_CANDIDATE,store.name()+" "+(++index),null));
        return List.copyOf(result);
    }
    public static void validate(Profile profile) {
        if(profile.wineProductionLines==null || profile.wineProductionSchedules==null)
            throw new IllegalArgumentException("Wine production line maps are missing");
        if(profile.wineProductionLines.size()>64 || profile.wineProductionLines.containsKey(LEGACY_ID))
            throw new IllegalArgumentException("Legacy tomato wine cannot be replaced by a custom line");
        Set<String> outputs=new HashSet<>(); outputs.add(ItemData.WINE);
        for(var entry:profile.wineProductionLines.entrySet()) {
            WineProductionLine line=line(profile,entry.getKey());
            if(line==null || !configured(profile,line) || !outputs.add(line.outputItemId()))
                throw new IllegalArgumentException("Invalid or overlapping wine production line: "+entry.getKey());
        }
        for(var entry:profile.wineProductionSchedules.entrySet()) {
            if(LEGACY_ID.equals(entry.getKey()) || line(profile,entry.getKey())==null || entry.getValue()==null)
                throw new IllegalArgumentException("Wine schedule has no registered custom line");
            WineBatchSchedule value=entry.getValue();
            new WineBatchSchedule(value.nextDueDay(),value.active(),value.remaining(),value.latestFeedDay(),value.skipped());
        }
    }
}
