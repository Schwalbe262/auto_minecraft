package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.HarvestRoutePlanner;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericHarvestModuleTest {
    private static final Pos ORIGIN=new Pos(0,0,0);

    @Test void oneEngineHarvestsTomatoesAndAncientFruitWithIndependentCycles() {
        Fixture fixture=new Fixture();fixture.profile.harvestCycleDays=3;fixture.day=5*24000L+5000;
        fixture.farm("tomatoes",ORIGIN,ORIGIN,CropRules.TOMATO);fixture.crop(ORIGIN,CropRules.TOMATO,3);
        Pos ancient=ORIGIN.offset(10,0,0);
        fixture.farm("ancient",ancient,ancient,CropRules.ANCIENT_FRUIT);fixture.crop(ancient,CropRules.ANCIENT_FRUIT,10);
        fixture.run();
        assertEquals(Feature.HARVEST,fixture.module.feature());assertEquals(List.of(ORIGIN,ancient),fixture.clicked());
        assertEquals(Map.of("harvest:tomatoes",8L,"harvest:ancient",15L),fixture.profile.nextEligibleDay);
        assertEquals(WorkResult.State.IDLE,fixture.result.state());
    }

    @Test void ancientAgeSevenIsNotMatureAndAnUnripeFieldChecksAgainTomorrow() {
        Fixture fixture=new Fixture();fixture.day=5*24000L+5000;
        fixture.farm("ancient",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT);fixture.crop(ORIGIN,CropRules.ANCIENT_FRUIT,7);
        fixture.run();assertTrue(fixture.clicked().isEmpty());assertEquals(6L,fixture.profile.nextEligibleDay.get("harvest:ancient"));
        fixture.day=6*24000L+5000;fixture.crop(ORIGIN,CropRules.ANCIENT_FRUIT,10);fixture.run();
        assertEquals(List.of(ORIGIN),fixture.clicked());assertEquals(16L,fixture.profile.nextEligibleDay.get("harvest:ancient"));
    }

    @Test void ancientFruitReusesTheSameAreaCentersAndObservedCropAcknowledgements() {
        Fixture fixture=new Fixture();fixture.radius=1;
        fixture.farm("ancient",ORIGIN,new Pos(5,0,2),CropRules.ANCIENT_FRUIT);
        for(int z=0;z<3;z++)for(int x=0;x<6;x++)fixture.crop(new Pos(x,0,z),CropRules.ANCIENT_FRUIT,10);
        fixture.run();
        assertEquals(List.of(new Pos(1,0,1),new Pos(4,0,1)),fixture.clicked());
        assertEquals(10L,fixture.profile.nextEligibleDay.get("harvest:ancient"));
        assertTrue(fixture.blocks.values().stream().noneMatch(block->block.number("age",-1)==10));
    }

    @Test void anAreaHintNeverDiscardsAncientFruitWhoseAgeDidNotActuallyChange() {
        Fixture fixture=new Fixture();fixture.radius=1;fixture.applyArea=false;
        fixture.farm("ancient",ORIGIN,new Pos(2,0,2),CropRules.ANCIENT_FRUIT);
        for(int z=0;z<3;z++)for(int x=0;x<3;x++)fixture.crop(new Pos(x,0,z),CropRules.ANCIENT_FRUIT,10);
        fixture.run();assertEquals(9,fixture.clicked().size());assertEquals(9,new HashSet<>(fixture.clicked()).size());
        assertEquals(new Pos(1,0,1),fixture.clicked().get(0));
    }

    @Test void aNativeAreaCannotCrossIntoAnotherCropEvenWhenBothFieldsAreRegistered() {
        Fixture fixture=new Fixture();fixture.radius=1;
        fixture.farm("tomatoes",ORIGIN,ORIGIN,CropRules.TOMATO);fixture.crop(ORIGIN,CropRules.TOMATO,3);
        Pos adjacent=ORIGIN.offset(1,0,0);fixture.farm("ancient",adjacent,adjacent,CropRules.ANCIENT_FRUIT);
        fixture.crop(adjacent,CropRules.ANCIENT_FRUIT,10);
        fixture.result=fixture.module.tick(fixture.context);
        assertEquals(WorkResult.State.BLOCKED,fixture.result.state());assertTrue(fixture.clicked().isEmpty());
        assertTrue(fixture.profile.nextEligibleDay.isEmpty());
    }

    @Test void anAncientCropDefinitionDoesNotInventNativeRightClickOrAreaSupport() {
        Fixture fixture=new Fixture();fixture.nativeKnown=false;
        fixture.farm("ancient",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT);fixture.crop(ORIGIN,CropRules.ANCIENT_FRUIT,10);
        fixture.result=fixture.module.tick(fixture.context);
        assertEquals(WorkResult.State.BLOCKED,fixture.result.state());assertTrue(fixture.clicked().isEmpty());
        assertTrue(fixture.profile.nextEligibleDay.isEmpty());
    }

    @Test void theSharedActionPolicyRequiresTheExactRegisteredAncientMaturity() {
        Fixture fixture=new Fixture();fixture.farm("ancient",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT);
        Action action=new Action.UseBlock(ORIGIN,Action.Use.HARVEST);
        fixture.crop(ORIGIN,CropRules.ANCIENT_FRUIT,10);assertNull(SafetyPolicy.rejection(action,fixture.context));
        fixture.crop(ORIGIN,CropRules.ANCIENT_FRUIT,7);assertNotNull(SafetyPolicy.rejection(action,fixture.context));
        fixture.crop(ORIGIN,CropRules.TOMATO,3);assertNotNull(SafetyPolicy.rejection(action,fixture.context));
    }

    @Test void unknownAndOverlappingCropRegistrationsCannotSilentlyCompleteAField() {
        for(boolean overlap:List.of(false,true)) {
            Fixture fixture=new Fixture();fixture.farm("first",ORIGIN,ORIGIN,overlap?CropRules.TOMATO:"unknown");
            fixture.crop(ORIGIN,CropRules.TOMATO,3);
            if(overlap)fixture.farm("other",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT);
            fixture.result=fixture.module.tick(fixture.context);
            assertEquals(WorkResult.State.BLOCKED,fixture.result.state());assertTrue(fixture.clicked().isEmpty());
            assertTrue(fixture.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void changingTheDefinitionWhileAUseIsPendingCannotInventHarvestCompletion() {
        Fixture fixture=new Fixture();fixture.farm("ancient",ORIGIN,ORIGIN,CropRules.ANCIENT_FRUIT);
        fixture.crop(ORIGIN,CropRules.ANCIENT_FRUIT,10);fixture.module.tick(fixture.context);assertEquals(1,fixture.clicked().size());
        fixture.profile.crops.put(CropRules.ANCIENT_FRUIT,new CropDefinition(CropRules.ANCIENT_FRUIT,CropRules.ANCIENT_FRUIT_ITEM,
            Set.of(CropRules.ANCIENT_FRUIT_ITEM),Map.of(CropRules.ANCIENT_FRUIT_ITEM,Map.of("age","7")),10));
        fixture.completeUse();fixture.now++;
        assertEquals(WorkResult.State.BLOCKED,fixture.module.tick(fixture.context).state());
        assertEquals(1,fixture.clicked().size());assertTrue(fixture.profile.nextEligibleDay.isEmpty());
    }

    @Test void capacityCountsTheSelectedProductAndDoesNotBorrowTomatoOrRottenSpace() {
        Fixture fixture=new Fixture();
        for(int slot=0;slot<36;slot++)fixture.put(slot,new ItemData("minecraft:stone",64,0,null,false,0));
        for(int grade=0;grade<4;grade++)fixture.put(grade,new ItemData(CropRules.ANCIENT_FRUIT_ITEM,60,grade,null,false,0));
        fixture.put(4,ItemData.EMPTY);
        CropDefinition ancient=CropRules.definition(fixture.profile,CropRules.ANCIENT_FRUIT);
        assertTrue(HarvestModule.hasHarvestRoom(fixture.inventory,ancient));
        for(int grade=0;grade<4;grade++)fixture.put(grade,new ItemData(ItemData.TOMATO,60,grade,null,false,0));
        fixture.put(5,new ItemData(ItemData.ROTTEN,60,0,null,false,0));
        assertFalse(HarvestModule.hasHarvestRoom(fixture.inventory,ancient));
        assertTrue(HarvestModule.hasHarvestRoom(fixture.inventory),"Legacy tomato reserve remains compatible");
        fixture.put(5,ItemData.EMPTY);assertTrue(HarvestModule.hasHarvestRoom(fixture.inventory,ancient));
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final HarvestModule module=new HarvestModule();
        final Context context=new Context(this,this,this,profile);
        final Map<Pos,BlockData> blocks=new LinkedHashMap<>();final List<ItemSlot> inventory=new ArrayList<>();
        final List<Action> submitted=new ArrayList<>();final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        long now,day=5000,current;int radius;boolean nativeKnown=true,applyArea=true;
        WorkResult result=WorkResult.busy("initial");
        Fixture(){for(int slot=0;slot<36;slot++)put(slot,slot==0?new ItemData("minecraft:iron_hoe",1,0,null,true,200):ItemData.EMPTY);}
        void farm(String name,Pos first,Pos second,String crop){profile.farms.add(new Farm(name,first,second,crop));}
        void crop(Pos pos,String crop,int age){String id=CropRules.TOMATO.equals(crop)?"farmersdelight:tomatoes":CropRules.ANCIENT_FRUIT_ITEM;blocks.put(pos,new BlockData(pos,id,Map.of("age",String.valueOf(age))));}
        void put(int slot,ItemData item){ItemSlot value=new ItemSlot(slot,slot,true,item);if(slot==inventory.size())inventory.add(value);else inventory.set(slot,value);}
        List<Pos> clicked(){return submitted.stream().map(action->((Action.UseBlock)action).pos()).toList();}
        void run(){for(int tick=0;tick<500;tick++){result=module.tick(context);assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());if(busy())completeUse();now++;if(result.state()==WorkResult.State.IDLE)return;}fail("Harvest did not finish");}
        void completeUse(){Pos selected=((Action.UseBlock)submitted.get(submitted.size()-1)).pos();for(Pos pos:List.copyOf(blocks.keySet()))if(pos.equals(selected)||applyArea&&HarvestRoutePlanner.withinFootprint(selected,pos,radius)){BlockData block=blocks.get(pos);blocks.put(pos,new BlockData(pos,block.id(),Map.of("age","0")));}outcomes.put(current,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"server state changed"));}
        public long tick(){return now;}public long dayTime(){return day;}
        public PlayerState player(){return new PlayerState(.5,0,.5,0,0,true,false,20,20,0,true,true);}
        public BlockData block(Pos pos){return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of()));}
        public boolean loaded(Pos pos){return true;}public boolean canStand(Pos pos){return true;}
        public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos center,int radius,int vertical){return List.copyOf(blocks.values());}
        public List<ItemSlot> inventory(){return inventory;}
        public HarvestFootprint harvestFootprint(Pos target){if(!nativeKnown)return HarvestFootprint.UNKNOWN;if(radius==0)return HarvestFootprint.single(target);return new HarvestFootprint(true,radius,blocks.keySet().stream().filter(pos->HarvestRoutePlanner.withinFootprint(target,pos,radius)).toList());}
        public MenuData menu(){return new MenuData(0,0,inventory,ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return current>0&&!outcomes.get(current).done();}
        public long submit(Action action){assertFalse(busy());assertInstanceOf(Action.UseBlock.class,action);assertEquals(Action.Use.HARVEST,((Action.UseBlock)action).purpose());submitted.add(action);outcomes.put(++current,new ActionOutcome(ActionOutcome.State.PENDING,""));return current;}
        public ActionOutcome outcome(long ticket){return outcomes.get(ticket);}
        public void move(Movement movement){}public void stopMovement(){}public void cancel(){}
        public Result moveTo(Pos target,double reach,Context context){return Result.ARRIVED;}public void reset(){}
    }
}
