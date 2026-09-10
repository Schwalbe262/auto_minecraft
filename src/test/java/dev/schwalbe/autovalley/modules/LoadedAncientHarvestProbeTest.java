package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoadedAncientHarvestProbeTest {
    @Test void repeatedlyUnloadedBudgetSizedFirstFarmCannotStarveALaterLoadedFarm() {
        Fixture f=new Fixture();f.farm("unloaded",0,511);f.farm("ripe",512,512);f.ripe(512);
        for(int x=0;x<512;x++)f.unloaded.add(new Pos(x,72,0));
        f.probe.refresh(f.context,null);assertEquals(0,f.reads);assertEquals(611L,f.due("ripe"));
        f.now=100;f.probe.refresh(f.context,null);
        assertEquals(1,f.reads);assertEquals(603L,f.due("ripe"));assertEquals(611L,f.due("unloaded"));
    }
    @Test void futureObservationHasOneSharedBoundedBudgetAndContinuesAcrossCalls() {
        Fixture f=new Fixture();f.farm("large",0,1200);f.ripe(1200);
        f.probe.refresh(f.context,null);assertEquals(512,f.reads);assertEquals(611L,f.due("large"));
        f.probe.refresh(f.context,null);assertEquals(1024,f.reads);assertEquals(611L,f.due("large"));
        f.probe.refresh(f.context,null);assertEquals(1201,f.reads);assertEquals(603L,f.due("large"));
    }
    @Test void unloadedCellsAreNotReadAndDoNotCauseTravelOrCancelTheFutureDate() {
        Fixture f=new Fixture();f.farm("field",0,2);f.ripe(2);f.unloaded.add(new Pos(2,72,0));
        f.probe.refresh(f.context,null);assertEquals(2,f.reads);assertEquals(611L,f.due("field"));
        f.unloaded.clear();f.probe.refresh(f.context,null);assertEquals(2,f.reads);
        f.now=100;f.probe.refresh(f.context,null);assertEquals(603L,f.due("field"));
    }
    @Test void aFullyObservedNegativeFieldIsNotScannedRepeatedlyDuringTheSameDay() {
        Fixture f=new Fixture();f.farm("field",0,5);f.probe.refresh(f.context,null);assertEquals(6,f.reads);
        f.now+=1000;f.probe.refresh(f.context,null);assertEquals(6,f.reads);
        f.day++;f.ripe(5);f.probe.refresh(f.context,null);assertEquals(12,f.reads);assertEquals(604L,f.due("field"));
    }
    @Test void changedBoundsInvalidateTheNegativeObservationEvenWithTheSameNameAndDay() {
        Fixture f=new Fixture();f.farm("field",0,1);f.probe.refresh(f.context,null);f.ripe(2);
        f.profile.farms.set(0,new Farm("field",new Pos(0,72,0),new Pos(2,72,0),CropRules.ANCIENT_FRUIT));
        f.probe.refresh(f.context,null);assertEquals(603L,f.due("field"));
    }
    @Test void otherScopesOutOfBoundsAndConflictingRegistrationsCannotLendReadiness() {
        Fixture f=new Fixture();f.farm("field",0,0);f.ripe(0);f.ripe(1);
        f.probe.refresh(f.context,CropRules.TOMATO);assertEquals(0,f.reads);
        f.profile.farms.add(new Farm("tomato",new Pos(0,72,0),new Pos(0,72,0),CropRules.TOMATO));
        f.probe.refresh(f.context,null);assertEquals(611L,f.due("field"));assertEquals(1,f.reads);
    }
    @Test void distinctProfilesAndWorldsNeverReuseNegativeCacheOrPositiveAuthority() {
        Fixture f=new Fixture();f.farm("field",0,0);f.probe.refresh(f.context,null);
        Fixture other=new Fixture();other.farm("field",0,0);other.ripe(0);
        f.probe.refresh(other.context,null);assertEquals(603L,other.due("field"));assertEquals(611L,f.due("field"));
        f.ripe(0);f.probe.refresh(f.context,null);assertEquals(603L,f.due("field"));
    }
    @Test void oversizeAndExplicitNonstandardCycleDefinitionsAreNotProbed() {
        Fixture f=new Fixture();f.farm("large",0,32768);f.ripe(0);f.probe.refresh(f.context,null);assertEquals(0,f.reads);
        f.profile.farms.clear();f.farm("custom",0,0);
        CropDefinition old=CropRules.definition(f.profile,CropRules.ANCIENT_FRUIT);
        f.profile.crops.put(old.key(),new CropDefinition(old.key(),old.itemId(),old.blockIds(),old.matureStates(),20));
        f.probe.refresh(f.context,null);assertEquals(0,f.reads);assertEquals(611L,f.due("custom"));
    }
    private static final class Fixture implements WorldAccess {
        final Profile profile=new Profile();final Context context=new Context(this,null,null,profile);
        final LoadedAncientHarvestProbe probe=new LoadedAncientHarvestProbe();
        final Map<Pos,BlockData> blocks=new HashMap<>();final Set<Pos> unloaded=new HashSet<>();
        long now,day=603;int reads;
        void farm(String name,int first,int last){profile.farms.add(new Farm(name,new Pos(first,72,0),new Pos(last,72,0),CropRules.ANCIENT_FRUIT));profile.nextEligibleDay.put("harvest:"+name,611L);}
        void ripe(int x){Pos p=new Pos(x,72,0);blocks.put(p,new BlockData(p,CropRules.ANCIENT_FRUIT_ITEM,Map.of("age","10")));}
        Long due(String name){return profile.nextEligibleDay.get("harvest:"+name);}
        public long tick(){return now;}public long dayTime(){return day*24000+5000;}
        public boolean loaded(Pos p){return !unloaded.contains(p);}
        public BlockData block(Pos p){assertTrue(loaded(p));reads++;return blocks.getOrDefault(p,new BlockData(p,"minecraft:air",Map.of()));}
        public PlayerState player(){throw new AssertionError("No player movement needed");}
        public boolean canStand(Pos p){throw new AssertionError();}public boolean canTraverse(Pos a,Pos b){throw new AssertionError();}
        public List<BlockData> scan(Pos p,int h,int v){throw new AssertionError("No surrounding/unbounded scan");}
        public List<ItemSlot> inventory(){throw new AssertionError();}public MenuData menu(){throw new AssertionError();}
        public boolean mayPlace(int slot,ItemData item){throw new AssertionError();}
    }
}
