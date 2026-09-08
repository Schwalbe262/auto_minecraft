package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtisanRecipeTest {
    @Test void installedRecipesKeepInputCostSeparateFromTheirActualMorningCycle() {
        assertEquals(3,ArtisanRecipe.ANCIENT_SEED.inputCount());assertEquals(1,ArtisanRecipe.ANCIENT_SEED.outputCount());
        assertEquals(1,ArtisanRecipe.ANCIENT_SEED.cycleDays());assertEquals(5,ArtisanRecipe.JADE_CRYSTAL.cycleDays());
        assertEquals(2,ArtisanRecipe.JADE_CRYSTAL.outputCount());assertTrue(ArtisanRecipe.JADE_CRYSTAL.sameInputAndOutput());
        assertNull(ArtisanRecipe.find("arbitrary_recipe"));assertThrows(IllegalArgumentException.class,()->ArtisanRecipe.require("arbitrary_recipe"));
    }
    @Test void explicitMachineGroupsAreImmutableAndRejectDuplicatesOrUnknownRecipes() {
        List<Pos> list=new ArrayList<>(List.of(new Pos(1,64,0)));ArtisanJob job=new ArtisanJob("job","ancient_seed",list,"input","output");
        list.clear();assertEquals(1,job.machines().size());assertThrows(UnsupportedOperationException.class,()->job.machines().clear());
        assertThrows(IllegalArgumentException.class,()->new ArtisanJob("job","ancient_seed",List.of(new Pos(1,64,0),new Pos(1,64,0)),"input","output"));
        assertThrows(IllegalArgumentException.class,()->new ArtisanJob("job","unknown",List.of(new Pos(1,64,0)),"input","output"));
        assertThrows(IllegalArgumentException.class,()->job.scheduleKey(new Pos(2,64,0)));
    }
    @Test void scheduleKeysSeparateJobsRecipesAndPhysicalMachines() {
        ArtisanJob a=new ArtisanJob("a","ancient_seed",List.of(new Pos(1,64,0),new Pos(2,64,0)),"i","o");
        ArtisanJob b=new ArtisanJob("b","ancient_seed",List.of(new Pos(1,64,0)),"i","o");
        assertNotEquals(a.scheduleKey(a.machines().get(0)),a.scheduleKey(a.machines().get(1)));
        assertNotEquals(a.scheduleKey(a.machines().get(0)),b.scheduleKey(b.machines().get(0)));
    }
}
