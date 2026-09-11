package dev.schwalbe.autovalley.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CrystalRecipeTest {
    @Test void all56InstalledInputsHaveExactCyclesAndSameOriginOneToTwoRecipes() {
        Set<String> inputs=new LinkedHashSet<>(),recipeIds=new LinkedHashSet<>();
        Map<Integer,Integer> counts=new TreeMap<>();
        for(var group:installedCycles().entrySet())for(String input:group.getValue()) {
            assertTrue(inputs.add(input),input);
            ArtisanRecipe recipe=CrystalRecipe.forInput(input);
            assertNotNull(recipe,input);
            assertEquals(group.getKey().intValue(),recipe.cycleDays(),input);
            assertEquals(Feature.CRYSTAL_COPY,recipe.feature(),input);
            assertEquals("society:crystalarium",recipe.machineId(),input);
            assertEquals(input,recipe.inputId(),input);assertEquals(input,recipe.outputId(),input);
            assertEquals(1,recipe.inputCount(),input);assertEquals(2,recipe.outputCount(),input);
            assertTrue(recipe.sameInputAndOutput(),input);
            assertEquals("crystal_"+input.replace(':','_'),recipe.id(),input);
            assertTrue(recipeIds.add(recipe.id()),input);
            assertSame(recipe,CrystalRecipe.forInput(input),input);
            counts.merge(recipe.cycleDays(),1,Integer::sum);
        }
        assertEquals(56,inputs.size());assertEquals(56,recipeIds.size());
        assertEquals(CrystalCollection.BASE_OUTPUT_IDS,inputs);
        assertEquals(Map.of(1,8,2,4,3,38,4,4,5,1,6,1),counts);
    }

    @Test void everyBonusUsesSocietyNamespaceButNeverBecomesAnInput() {
        Set<String> bonuses=new LinkedHashSet<>();
        for(String input:CrystalCollection.BASE_OUTPUT_IDS) {
            String bonus="society:pristine_"+input.substring(input.indexOf(':')+1);
            assertEquals(bonus,CrystalRecipe.bonusId(input),input);
            assertTrue(bonuses.add(bonus),input);
            assertNull(CrystalRecipe.forInput(bonus),bonus);
            assertNull(CrystalRecipe.bonusId(bonus),bonus);
        }
        assertEquals(56,bonuses.size());
        Set<String> expected=new LinkedHashSet<>(CrystalCollection.OUTPUT_IDS);
        expected.removeAll(CrystalCollection.BASE_OUTPUT_IDS);
        assertEquals(expected,bonuses);
        assertEquals("society:pristine_quartz",CrystalRecipe.bonusId("minecraft:quartz"));
        assertEquals("society:pristine_fire_quartz",CrystalRecipe.bonusId("society:fire_quartz"));
    }

    @Test void unknownMalformedAndUpgradeItemsHaveNoFallbackRecipeOrBonus() {
        assertNull(CrystalRecipe.forInput(null));assertNull(CrystalRecipe.bonusId(null));
        for(String input:List.of(""," ","jade","society:JADE"," society:jade","society:jade ",
                "other:jade","minecraft:jade","society:quartz","minecraft:fire_quartz","society:black_opal",
                "minecraft:coal","minecraft:diamond_block","minecraft:quartz_block","society:new_crystal")) {
            assertNull(CrystalRecipe.forInput(input),input);assertNull(CrystalRecipe.bonusId(input),input);
        }
    }

    /** Independent transcription of installed crystalarium.js, grouped only to make all 56 cycles auditable. */
    private static Map<Integer,Set<String>> installedCycles() {
        return Map.of(
            1,Set.of("society:frozen_tear","society:earth_crystal","society:aquamarine","society:amethyst_chunk",
                "minecraft:lapis_lazuli","minecraft:amethyst_shard","minecraft:prismarine_crystals","minecraft:quartz"),
            2,Set.of("society:fire_quartz","society:ruby","society:topaz","minecraft:emerald"),
            3,Set.of("society:ocean_stone","society:opal","society:pyrite","society:soapstone","society:baryte",
                "society:basalt_shard","society:bixbyite","society:dolomite","society:allanite","society:calcite_gem",
                "society:celestine","society:granite_slate","society:jagoite","society:jamborite","society:slate",
                "society:limestone_pebble","society:malachite","society:mudstone","society:nekoite","society:orpiment",
                "society:petrified_slime","society:sandstone_slate","society:thunder_egg","society:aerinite",
                "society:esperite","society:fairy_stone","society:fluorapatite","society:geminite","society:ghost_crystal",
                "society:hematite","society:kyanite","society:lunarite","society:marble","society:fire_opal",
                "society:jasper","society:lemon_stone","society:pure_obsidian","society:tigerseye"),
            4,Set.of("society:helvite","society:neptunite","society:star_shards","minecraft:diamond"),
            5,Set.of("society:jade"),
            6,Set.of("society:spinel"));
    }
}
