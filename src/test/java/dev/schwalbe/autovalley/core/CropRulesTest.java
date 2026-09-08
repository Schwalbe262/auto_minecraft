package dev.schwalbe.autovalley.core;

import com.google.gson.Gson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CropRulesTest {
    private static final Pos ORIGIN=new Pos(0,64,0);

    @Test void legacyFarmConstructionAndJsonStillSelectTomatoes() {
        Farm legacy=new Farm("first",ORIGIN,ORIGIN);
        assertEquals(CropRules.TOMATO,legacy.cropId());
        assertEquals(legacy,new Farm("first",ORIGIN,ORIGIN,null));
        String oldJson="{\"name\":\"first\",\"first\":{\"x\":0,\"y\":64,\"z\":0},\"second\":{\"x\":0,\"y\":64,\"z\":0}}";
        assertEquals(legacy,new Gson().fromJson(oldJson,Farm.class));
        Profile profile=new Gson().fromJson("{\"farms\":["+oldJson+"],\"crops\":null,\"harvestCycleDays\":3}",Profile.class);
        assertEquals(CropRules.TOMATO,CropRules.definition(profile,profile.farms.get(0)).key());
        assertEquals(3,CropRules.cycleDays(profile,profile.farms.get(0)));
        assertEquals("harvest:first",CropRules.farmKey(legacy));
    }

    @Test void ancientFruitUsesItsActualMaxAgeAndSeparateConfiguredCycle() {
        Profile profile=new Profile();profile.harvestCycleDays=3;
        Farm ancient=new Farm("ancient",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT);
        CropDefinition definition=CropRules.definition(profile,ancient);
        assertEquals("society:ancient_fruit",definition.itemId());
        assertEquals(10,CropRules.cycleDays(profile,ancient));
        for(String age:List.of("0","7","9","11","10.0","unknown"))
            assertFalse(CropRules.mature(definition,new BlockData(ORIGIN,"society:ancient_fruit",Map.of("age",age))));
        assertFalse(CropRules.mature(definition,new BlockData(ORIGIN,"society:ancient_fruit",Map.of())));
        assertTrue(CropRules.mature(definition,new BlockData(ORIGIN,"society:ancient_fruit",Map.of("age","10"))));
    }

    @Test void tomatoBudsRemainLayoutCellsButNeverBecomeMatureClickTargets() {
        CropDefinition tomato=CropRules.definition(new Profile(),CropRules.TOMATO);
        BlockData bud=new BlockData(ORIGIN,"farmersdelight:budding_tomatoes",Map.of("age","3"));
        assertTrue(CropRules.matches(tomato,bud));assertFalse(CropRules.mature(tomato,bud));
        for(String id:List.of("farmersdelight:tomatoes","farmersdelight:tomatoes_on_rope"))
            assertTrue(CropRules.mature(tomato,new BlockData(ORIGIN,id,Map.of("age","3"))));
        assertFalse(CropRules.mature(tomato,new BlockData(ORIGIN,"minecraft:wheat",Map.of("age","3"))));
    }

    @Test void customDefinitionsRequireEveryDeclaredMaturityPropertyAndOwnTheirData() {
        Set<String> blocks=new HashSet<>(Set.of("example:berries"));
        Map<String,String> predicates=new HashMap<>(Map.of("age","4","fruiting","true"));
        Map<String,Map<String,String>> states=new HashMap<>(Map.of("example:berries",predicates));
        CropDefinition crop=new CropDefinition("berries","example:berry",blocks,states,2);
        blocks.clear();predicates.clear();states.clear();
        assertTrue(CropRules.valid(crop));
        assertFalse(CropRules.mature(crop,new BlockData(ORIGIN,"example:berries",Map.of("age","4"))));
        assertTrue(CropRules.mature(crop,new BlockData(ORIGIN,"example:berries",Map.of("age","4","fruiting","true"))));
        assertThrows(UnsupportedOperationException.class,()->crop.matureStates().get("example:berries").clear());
        Profile profile=new Profile();profile.crops.put(crop.key(),crop);
        Farm farm=new Farm("berries",ORIGIN,ORIGIN,crop.key());profile.farms.add(farm);
        Profile loaded=new Gson().fromJson(new Gson().toJson(profile),Profile.class);
        assertEquals(crop,CropRules.definition(loaded,loaded.farms.get(0)));
    }

    @Test void invalidOrMissingCropDefinitionsDoNotFallBackToAnotherCrop() {
        Profile profile=new Profile();
        assertNull(CropRules.definition(profile,"missing"));
        profile.crops.put("bad",new CropDefinition("bad","example:crop",Set.of("example:crop"),Map.of("example:crop",Map.of()),2));
        assertNull(CropRules.definition(profile,"bad"),"An empty predicate cannot make every plant mature");
        profile.crops.put("other",profile.crops.get(CropRules.TOMATO));
        assertNull(CropRules.definition(profile,"other"),"Map key and definition identity must agree");
    }

    @Test void overlappingDifferentCropMasksAreAmbiguousWhileSameCropFieldsCanJoin() {
        Profile profile=new Profile();profile.farms.add(new Farm("one",ORIGIN,ORIGIN));
        profile.farms.add(new Farm("two",ORIGIN,ORIGIN));
        assertEquals(CropRules.TOMATO,CropRules.registeredCrop(profile,ORIGIN).key());
        profile.farms.add(new Farm("ancient",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT));
        assertNull(CropRules.registeredCrop(profile,ORIGIN));
        assertNull(CropRules.registeredCrop(profile,ORIGIN.offset(1,0,0)));
    }

    @Test void extremeCoordinatesCannotOverflowIntoASmallAuthorizedCropMask() {
        Profile profile=new Profile();
        Farm huge=new Farm("overflow",new Pos(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),
            new Pos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        profile.farms.add(huge);
        assertEquals(Long.MAX_VALUE,huge.volume());assertNull(CropRules.registeredCrop(profile,ORIGIN));
    }
}
