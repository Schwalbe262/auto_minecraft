package dev.schwalbe.autovalley.core;

import java.util.*;

/** Society 4.1.4 crystalarium outputs. Recognition never authorizes inserting a seed. */
public final class CrystalCollection {
    public static final String MACHINE_ID="society:crystalarium";
    public static final Set<String> BASE_OUTPUT_IDS=baseOutputs();
    public static final Set<String> OUTPUT_IDS=allOutputs();
    private CrystalCollection() { }

    private static Set<String> baseOutputs() {
        Set<String> ids=new LinkedHashSet<>();
        // Exact installed customMachines/crystalarium.js catalog, not a mineral-name heuristic.
        for(String path:List.of("ocean_stone","opal","pyrite","soapstone","frozen_tear","baryte",
                "basalt_shard","bixbyite","dolomite","fire_quartz","earth_crystal","allanite",
                "calcite_gem","celestine","granite_slate","jagoite","jamborite","slate",
                "limestone_pebble","malachite","mudstone","nekoite","orpiment","petrified_slime",
                "sandstone_slate","thunder_egg","aerinite","esperite","fairy_stone","fluorapatite",
                "geminite","ghost_crystal","hematite","kyanite","lunarite","marble","fire_opal",
                "helvite","jasper","lemon_stone","neptunite","pure_obsidian","star_shards","tigerseye",
                "aquamarine","ruby","amethyst_chunk","topaz","jade","spinel")) ids.add("society:"+path);
        for(String path:List.of("emerald","lapis_lazuli","diamond","amethyst_shard","prismarine_crystals","quartz"))
            ids.add("minecraft:"+path);
        return Collections.unmodifiableSet(ids);
    }
    private static Set<String> allOutputs() {
        Set<String> ids=new LinkedHashSet<>(BASE_OUTPUT_IDS);
        for(String id:BASE_OUTPUT_IDS)ids.add("society:pristine_"+id.substring(id.indexOf(':')+1));
        return Collections.unmodifiableSet(ids);
    }
    public static boolean accepts(ItemData item) { return item!=null && !item.empty() && OUTPUT_IDS.contains(item.id()); }
    public static Set<String> outputIds(Profile profile,ArtisanJob job) {
        CommodityStore store=CommodityStorageRules.store(profile,job.outputStoreId());
        if(store==null)return Set.of();
        Set<String> ids=new LinkedHashSet<>(store.items());ids.retainAll(OUTPUT_IDS);
        return Collections.unmodifiableSet(ids);
    }
    /** An inspection is not a production start. Legacy jade production dates cannot describe a manually changed seed. */
    public static String scheduleKey(ArtisanJob job,Pos machine) {
        if(!job.machines().contains(machine))throw new IllegalArgumentException("Machine does not belong to crystal job");
        return "crystal-inspection:"+job.id()+":"+Profile.positionKey(machine);
    }
}
