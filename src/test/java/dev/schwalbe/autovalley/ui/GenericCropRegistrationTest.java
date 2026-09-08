package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericCropRegistrationTest {
    private static BlockData block(String id,int x,int y,int z) {return new BlockData(new Pos(x,y,z),id,Map.of("age","0"));}
    private static BlockData ancient(int x,int z) {return block(CropRules.ANCIENT_FRUIT_ITEM,x,64,z);}
    private static CropDefinition other(String key,String block) {
        return new CropDefinition(key,"example:fruit",Set.of(block),Map.of(block,Map.of("age","5")),7);
    }
    @Test void bothBuiltInCropKindsAppearInTheSameFarmFilterEvenWhenImmature() {
        Profile p=new Profile();
        assertEquals(RegistrationRules.Group.FARMS,RegistrationRules.group(p,ancient(0,0)));
        assertEquals(RegistrationRules.Group.FARMS,RegistrationRules.group(p,block("farmersdelight:budding_tomatoes",1,64,0)));
        assertNull(RegistrationRules.group(p,null));
        assertNull(RegistrationRules.crop(p,block("minecraft:stone",2,64,0)));
    }
    @Test void ancientRowsGroupTogetherWithTheirCropIdInsteadOfBecomingTomatoDrafts() {
        List<BlockData> scan=new ArrayList<>();
        for(int x=0;x<3;x++)for(int z=0;z<11;z++)scan.add(ancient(x,z));
        List<Farm> result=RegistrationRules.suggestFarms(scan,new Profile());
        assertEquals(1,result.size());Farm farm=result.get(0);
        assertEquals(CropRules.ANCIENT_FRUIT,farm.cropId());
        assertEquals(new Pos(0,64,0),farm.first());assertEquals(new Pos(2,64,10),farm.second());
    }
    @Test void touchingDifferentCropsNeverBecomeOneMixedCropSuggestion() {
        List<Farm> result=RegistrationRules.suggestFarms(List.of(ancient(0,0),ancient(1,0),
            block("farmersdelight:tomatoes",2,64,0),block("farmersdelight:tomatoes_on_rope",2,65,0)),new Profile());
        assertEquals(2,result.size());
        assertEquals(Set.of(CropRules.TOMATO,CropRules.ANCIENT_FRUIT),new HashSet<>(result.stream().map(Farm::cropId).toList()));
        assertFalse(RegistrationRules.overlap(result.get(0),result.get(1)));
    }
    @Test void configuredCustomCropIsIncludedByExactIdNotTomatoSubstringOrVanillaClass() {
        Profile p=new Profile();p.crops.put("custom",other("custom","example:custom_crop"));
        BlockData known=block("example:custom_crop",0,64,0),lookalike=block("other:custom_crop",1,64,0);
        assertEquals(RegistrationRules.Group.FARMS,RegistrationRules.group(p,known));
        assertNull(RegistrationRules.group(p,lookalike));
        assertTrue(CropRules.scanBlockIds(p).contains(known.id()));assertFalse(CropRules.scanBlockIds(p).contains(lookalike.id()));
        assertEquals("custom",RegistrationRules.suggestFarms(List.of(known,lookalike),p).get(0).cropId());
    }
    @Test void ambiguousOrMismatchedDefinitionCannotSeedAFieldDraft() {
        Profile p=new Profile();p.crops.put("duplicate",other("duplicate",CropRules.ANCIENT_FRUIT_ITEM));
        assertNull(RegistrationRules.crop(p,ancient(0,0)));
        assertTrue(RegistrationRules.suggestFarms(List.of(ancient(0,0)),p).isEmpty());
        p.crops.clear();p.crops.put("wrong_key",other("real_key","example:crop"));
        assertTrue(CropRules.scanBlockIds(p).isEmpty());assertNull(RegistrationRules.crop(p,block("example:crop",0,64,0)));
    }
    @Test void legacyTomatoGroupingAndBoundaryWarningsStayIntactAndOrderIsStable() {
        List<BlockData> scan=List.of(block("farmersdelight:tomatoes",20,64,0),ancient(0,0),ancient(1,0));
        List<Farm> forward=RegistrationRules.suggestFarms(scan);
        List<BlockData> reverse=new ArrayList<>(scan);Collections.reverse(reverse);
        assertEquals(forward,RegistrationRules.suggestFarms(reverse));
        assertEquals(CropRules.TOMATO,forward.get(1).cropId());
        assertTrue(RegistrationRules.mayBePartial(forward.get(0),new Pos(0,64,0),32,16,p->p.x()>=0));
        assertFalse(RegistrationRules.mayBePartial(forward.get(0),new Pos(0,64,0),32,16,p->true));
    }
    @Test void obtainingSuggestionsDoesNotWriteFarmRegistrationsOrSchedules() {
        Profile p=new Profile();p.nextEligibleDay.put("harvest:old",77L);
        List<Farm> suggested=RegistrationRules.suggestFarms(List.of(ancient(0,0)),p);
        assertEquals(1,suggested.size());assertTrue(p.farms.isEmpty());
        assertEquals(Map.of("harvest:old",77L),p.nextEligibleDay);
    }
}
