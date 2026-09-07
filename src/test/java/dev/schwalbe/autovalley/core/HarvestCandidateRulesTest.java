package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HarvestCandidateRulesTest {
    @Test void vanillaGrassIsNotInferredFromItsBonemealableBushType() {
        assertFalse(HarvestCandidateRules.potentialTarget("minecraft",true,false,false));
    }
    @Test void explicitlyMappedVanillaCropsRemainPotentialTargets() {
        for (boolean plant:new boolean[]{true,false})
            assertTrue(HarvestCandidateRules.potentialTarget("minecraft",plant,true,false));
    }
    @Test void explicitlyClickableVanillaPlantsRemainPotentialTargets() {
        assertTrue(HarvestCandidateRules.potentialTarget("minecraft",true,false,true));
        assertTrue(HarvestCandidateRules.potentialTarget("minecraft",false,false,true));
    }
    @Test void explicitCustomMappingsCannotBeBypassedByTheVanillaExclusion() {
        // Even grass/stone must remain guarded if a native mapping explicitly
        // includes them. This is not an unconditional minecraft:grass allow-list.
        assertTrue(HarvestCandidateRules.potentialTarget("minecraft",false,true,true));
    }
    @Test void nonVanillaCropInferenceAndExplicitMappingsArePreserved() {
        assertTrue(HarvestCandidateRules.potentialTarget("farmersdelight",true,false,false));
        assertTrue(HarvestCandidateRules.potentialTarget("veggiesdelight",false,true,false));
        assertTrue(HarvestCandidateRules.potentialTarget("farm_and_charm",false,false,true));
        assertFalse(HarvestCandidateRules.potentialTarget("example",false,false,false));
    }
    @Test void unknownNamespaceFailsClosedInsteadOfClassifyingAPlantAsInert() {
        for (String namespace:Arrays.asList(null,""," "))
            assertThrows(IllegalArgumentException.class,() -> HarvestCandidateRules.potentialTarget(namespace,true,false,false));
    }
    @Test void inferredGrassNoLongerExpandsAnOtherwiseSafeTomatoFootprint() {
        Fixture f=new Fixture("minecraft:grass",true,false,false);
        assertEquals(List.of(f.target),f.footprint.potentialTargets()); assertNull(HarvestSafety.rejection(f.context,f.target));
        assertEquals(List.of(new Farm("tomatoes",f.target,f.target)),f.profile.farms,"no farm-boundary change is needed");
    }
    @Test void unregisteredTomatoesAndMappedVanillaCropsStillBlockTheSameFootprint() {
        for (String id:List.of("farmersdelight:tomatoes","minecraft:wheat","minecraft:sweet_berry_bush")) {
            Fixture f=new Fixture(id,true,id.equals("minecraft:wheat"),id.equals("minecraft:sweet_berry_bush"));
            assertEquals(2,f.footprint.potentialTargets().size()); assertNotNull(HarvestSafety.rejection(f.context,f.target));
            if (!id.equals("farmersdelight:tomatoes")) {
                f.profile.farms.add(new Farm("inside bounds",f.neighbor,f.neighbor));
                assertNotNull(HarvestSafety.rejection(f.context,f.target),"registered bounds cannot grant permission for another crop");
            }
        }
    }
    private static final class Fixture implements WorldAccess {
        final Pos target=new Pos(0,64,0),neighbor=target.offset(1,0,0);
        final Profile profile=new Profile(); final Map<Pos,BlockData> blocks=new HashMap<>();
        final HarvestFootprint footprint; final Context context;
        Fixture(String id,boolean inferred,boolean mapped,boolean clickable) {
            profile.farms.add(new Farm("tomatoes",target,target));
            blocks.put(target,new BlockData(target,"farmersdelight:tomatoes",Map.of("age","3")));
            blocks.put(neighbor,new BlockData(neighbor,id,Map.of("age","3")));
            boolean include=HarvestCandidateRules.potentialTarget(id.substring(0,id.indexOf(':')),inferred,mapped,clickable);
            footprint=new HarvestFootprint(true,1,include ? List.of(target,neighbor) : List.of(target));
            context=new Context(this,null,null,profile);
        }
        public long tick(){return 0;} public long dayTime(){return 0;} public PlayerState player(){return null;}
        public BlockData block(Pos pos){return blocks.get(pos);} public boolean loaded(Pos pos){return true;}
        public HarvestFootprint harvestFootprint(Pos pos){return footprint;}
        public boolean canStand(Pos pos){return false;} public boolean canTraverse(Pos from,Pos to){return false;}
        public List<BlockData> scan(Pos center,int horizontal,int vertical){throw new AssertionError("No navigation or scan is required");}
        public List<ItemSlot> inventory(){return List.of();} public MenuData menu(){return null;}
        public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
