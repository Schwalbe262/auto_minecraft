package dev.schwalbe.autovalley.core;

import java.util.*;

/** Exact Society 4.1.4 crystalarium inputs and morning-cycle counts, not seed-selection authority. */
public final class CrystalRecipe {
    private static final Map<String,ArtisanRecipe> RECIPES=recipes();
    private CrystalRecipe() { }

    /** Unknown IDs, including pristine bonus items, never become recipes by name or fallback. */
    public static ArtisanRecipe forInput(String id) { return id==null ? null : RECIPES.get(id); }

    /** A mature upgraded machine can emit one separate bonus (10%); it is never an ingredient. */
    public static String bonusId(String base) {
        return forInput(base)==null ? null : "society:pristine_"+base.substring(base.indexOf(':')+1);
    }

    private static Map<String,ArtisanRecipe> recipes() {
        Map<String,ArtisanRecipe> recipes=new LinkedHashMap<>();
        // Installed kubejs/startup_scripts/customMachines/crystalarium.js: every input consumes one,
        // outputs two of the same ID, and advances at morning ticks. Upgrading does not halve this time.
        add(recipes,1,"society","frozen_tear","earth_crystal","aquamarine","amethyst_chunk");
        add(recipes,1,"minecraft","lapis_lazuli","amethyst_shard","prismarine_crystals","quartz");
        add(recipes,2,"society","fire_quartz","ruby","topaz");
        add(recipes,2,"minecraft","emerald");
        add(recipes,3,"society","ocean_stone","opal","pyrite","soapstone","baryte","basalt_shard",
            "bixbyite","dolomite","allanite","calcite_gem","celestine","granite_slate","jagoite",
            "jamborite","slate","limestone_pebble","malachite","mudstone","nekoite","orpiment",
            "petrified_slime","sandstone_slate","thunder_egg","aerinite","esperite","fairy_stone",
            "fluorapatite","geminite","ghost_crystal","hematite","kyanite","lunarite","marble",
            "fire_opal","jasper","lemon_stone","pure_obsidian","tigerseye");
        add(recipes,4,"society","helvite","neptunite","star_shards");
        add(recipes,4,"minecraft","diamond");
        add(recipes,5,"society","jade");
        add(recipes,6,"society","spinel");
        return Collections.unmodifiableMap(recipes);
    }

    private static void add(Map<String,ArtisanRecipe> recipes,int days,String namespace,String... paths) {
        for(String path:paths) {
            String itemId=namespace+":"+path;
            ArtisanRecipe recipe=new ArtisanRecipe("crystal_"+namespace+"_"+path,Feature.CRYSTAL_COPY,
                "society:crystalarium",itemId,1,itemId,2,days);
            if(recipes.putIfAbsent(itemId,recipe)!=null)throw new IllegalStateException("Duplicate crystal input: "+itemId);
        }
    }
}
