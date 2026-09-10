package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real harvest + linked deposit + one-shot scheduler, with only native ports replaced by deterministic ACKs. */
class HarvestAndStorageModuleTest {
    private static final Pos CROP=new Pos(0,64,0),STORE=new Pos(3,64,0),OTHER=new Pos(8,64,0);
    private static final Pos TOMATO_FIRST=new Pos(10,64,0),TOMATO_SECOND=new Pos(12,64,0),TOMATO_STORE=new Pos(15,64,0);
    private static final String FRUIT=CropRules.ANCIENT_FRUIT_ITEM;
    private static ItemData item(String id,int count,int grade){return new ItemData(id,count,grade,null,false,99);}
    @Test void harvestOneShotIncludesLinkedMixedStorageAndFinishesOnlyAfterClosingIt() {
        Fixture f=new Fixture();f.inventory[8]=item("minecraft:egg",7,0);f.chests.get(STORE)[4]=item("minecraft:egg",12,0);
        f.start();f.run(150);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state(),f.engine.status());assertEquals(1,f.harvests);
        assertEquals(2,f.stored(STORE,FRUIT));assertEquals(0,f.count(FRUIT));assertEquals(7,f.count("minecraft:egg"));
        assertEquals(12,f.stored(STORE,"minecraft:egg"));assertFalse(f.menu().container());
        assertEquals(0,f.unrelatedTicks);assertFalse(f.profile.enabled(Feature.HARVEST));assertFalse(f.profile.enabled(Feature.COMMODITY_STORAGE));
        assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient"));
        assertInstanceOf(Action.CloseContainer.class,f.history.get(f.history.size()-1));
    }
    @Test void pendingDepositAckKeepsOneShotRunningWithoutReharvestingOrStartingOtherJobs() {
        Fixture f=new Fixture();f.holdTransfer=true;f.start();f.run(100);
        assertTrue(f.engine.running());assertEquals(1,f.harvests);assertEquals(1,f.transfers);assertEquals(0,f.unrelatedTicks);
        int sent=f.history.size();f.run(80);assertEquals(sent,f.history.size());
        f.releaseTransfer();f.run(100);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(2,f.stored(STORE,FRUIT));
    }
    @Test void failedDepositDoesNotReportCompleteOrEraseAlreadyHarvestedFieldSchedule() {
        Fixture f=new Fixture();f.failTransfer=true;f.start();f.run(100);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertEquals(1,f.harvests);assertEquals(2,f.count(FRUIT));
        assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient"));
        f.opened=null;f.failTransfer=false;f.start();f.run(100);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(1,f.harvests);assertEquals(2,f.stored(STORE,FRUIT));
    }
    @Test void futureDueUnripeFarmSkipsCropUseButStillStoresExistingLinkedCommodity() {
        Fixture f=new Fixture();f.profile.nextEligibleDay.put("harvest:Ancient",450L);f.inventory[1]=item(FRUIT,9,-1);
        f.blocks.put(CROP,new BlockData(CROP,FRUIT,Map.of("age","1")));
        f.start();f.run(100);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(0,f.harvests);assertEquals(9,f.stored(STORE,FRUIT));
        assertEquals(450L,f.profile.nextEligibleDay.get("harvest:Ancient"));
    }
    @Test void futureDueButRipeAncientFruitIsHarvestedAndStoredAfterAllTomatoes() {
        Fixture f=new Fixture();f.tomatoes();f.profile.nextEligibleDay.put("harvest:Ancient",450L);
        f.requireTomatoStoredBeforeAncient=true;f.start();f.run(200);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state(),f.engine.status());
        assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND,CROP),f.harvested());
        assertEquals(4,f.stored(TOMATO_STORE,ItemData.TOMATO));assertEquals(2,f.stored(STORE,FRUIT));
        assertEquals(0,f.count(FRUIT));assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient"));
    }
    @Test void immatureCropDoesNotGetClickedAndItsLinkedExistingProduceCanStillBeStored() {
        Fixture f=new Fixture();f.blocks.put(CROP,new BlockData(CROP,FRUIT,Map.of("age","9")));f.inventory[1]=item(FRUIT,3,2);
        f.start();f.run(100);assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());
        assertEquals(0,f.harvests);assertEquals(3,f.stored(STORE,FRUIT));assertEquals("9",f.block(CROP).properties().get("age"));
        assertEquals(442L,f.profile.nextEligibleDay.get("harvest:Ancient"),"Immature recheck is not a completed ten-day harvest cycle");
    }
    @Test void unrelatedCommodityGroupAndOtherItemsInSharedStoreDoNotBroadenOneShot() {
        Fixture f=new Fixture();f.inventory[1]=item("society:jade",4,0);
        f.profile.commodityStores.put("jade",new CommodityStore("jade","Jade",Set.of("society:jade"),List.of(OTHER)));
        f.chests.put(OTHER,new ItemData[27]);Arrays.fill(f.chests.get(OTHER),ItemData.EMPTY);
        f.start();f.run(100);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(4,f.count("society:jade"));
        assertTrue(f.history.stream().noneMatch(a->a instanceof Action.UseBlock use && use.pos().equals(OTHER)));
    }
    @Test void separateCropLinksRetainTheirIndividualItemsAndCycleLengths() {
        Fixture f=new Fixture();Pos tomato=new Pos(1,64,0);f.profile.harvestCycleDays=2;
        f.profile.farms.add(new Farm("Tomato",tomato,tomato));f.blocks.put(tomato,new BlockData(tomato,"farmersdelight:tomatoes",Map.of("age","3")));
        f.profile.commodityStores.put("tomato",new CommodityStore("tomato","Tomato",Set.of(ItemData.TOMATO),List.of(OTHER)));
        f.profile.cropStores.put(CropRules.TOMATO,"tomato");f.chests.put(OTHER,new ItemData[27]);Arrays.fill(f.chests.get(OTHER),ItemData.EMPTY);
        f.start();f.run(200);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(2,f.harvests);
        assertEquals(2,f.stored(STORE,FRUIT));assertEquals(2,f.stored(OTHER,ItemData.TOMATO));
        assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient"));assertEquals(443L,f.profile.nextEligibleDay.get("harvest:Tomato"));
    }
    @Test void unreachableLinkedStorageLeavesProduceOwnedAndOneShotIncomplete() {
        Fixture f=new Fixture();f.storageBlocked=true;f.start();f.run(100);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertEquals(1,f.harvests);assertEquals(2,f.count(FRUIT));
        assertEquals(0,f.stored(STORE,FRUIT));assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient"));
    }
    @Test void manualStopBetweenHarvestAndDepositDoesNotClearCropDatesOrEmitCleanupClicks() {
        Fixture f=new Fixture();f.start();
        for(int i=0;i<100 && !f.profile.nextEligibleDay.containsKey("harvest:Ancient");i++)f.run(1);
        int sent=f.history.size();f.engine.stop(f.context,AutomationEngine.State.OFF,"manual OFF");f.run(80);
        assertEquals(sent,f.history.size());assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient"));
        f.opened=null;f.start();f.run(100);assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(1,f.harvests);
    }
    @Test void allTomatoFieldsFillTheInventoryThenFinishTheirLegacyDepositBeforeEitherAncientField() {
        Fixture f=new Fixture();f.tomatoes();Pos secondAncient=new Pos(1,64,2);
        f.profile.farms.add(new Farm("Ancient second",secondAncient,secondAncient,CropRules.ANCIENT_FRUIT));
        f.blocks.put(secondAncient,new BlockData(secondAncient,FRUIT,Map.of("age","10")));
        f.inventory[1]=item(ItemData.TOMATO,62,-1);
        for(int i=2;i<34;i++)f.inventory[i]=item("minecraft:stone",64,0);
        f.requireTomatoStoredBeforeAncient=true;f.start();f.run(200);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state(),f.engine.status());
        assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND,CROP,secondAncient),f.harvested());
        assertEquals(0,f.emptySlotsBeforeTomatoDeposit);assertEquals(66,f.stored(TOMATO_STORE,ItemData.TOMATO));
        assertEquals(4,f.stored(STORE,FRUIT));assertEquals(0,f.count(ItemData.TOMATO));assertEquals(0,f.count(FRUIT));
        assertEquals(2,f.history.stream().filter(Action.CloseContainer.class::isInstance).count());
        assertFalse(f.profile.enabled(Feature.TOMATO_STORAGE));assertFalse(f.profile.enabled(Feature.COMMODITY_STORAGE));
        assertEquals(442L,f.profile.nextEligibleDay.get("harvest:Tomato first"));
        assertEquals(451L,f.profile.nextEligibleDay.get("harvest:Ancient second"));
    }
    @Test void existingTomatoesAreStoredFirstEvenWithoutADueTomatoFieldAndWithNoFreeInventorySlot() {
        Fixture f=new Fixture();f.legacyTomatoStore();
        for(int i=1;i<36;i++)f.inventory[i]=item(ItemData.TOMATO,64,-1);
        f.requireTomatoStoredBeforeAncient=true;f.start();f.run(200);
        // More stacks than one chest can hold is an ordinary storage failure, not
        // permission to enter the next crop with a partly drained inventory.
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertEquals(0,f.harvests);
        assertTrue(f.count(ItemData.TOMATO)>0);assertEquals(0,f.count(FRUIT));
    }
    @Test void aPendingTomatoTransferOrCloseCannotYieldToTheNextCropOrAnotherOneShotJob() {
        for(boolean close:List.of(false,true)) {
            Fixture f=new Fixture();f.tomatoes();f.holdTransfer=!close;f.holdClose=close;f.start();f.run(80);
            assertTrue(f.engine.running());assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND),f.harvested());
            assertFalse(f.profile.nextEligibleDay.containsKey("harvest:Ancient"));int sent=f.history.size();
            f.run(60);assertEquals(sent,f.history.size());assertEquals(0,f.unrelatedTicks);
            if(close)f.releaseClose();else f.releaseTransfer();f.run(150);
            assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(4,f.stored(TOMATO_STORE,ItemData.TOMATO));
            assertEquals(2,f.stored(STORE,FRUIT));assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND,CROP),f.harvested());
        }
    }
    @Test void failedTomatoStorageRestartsAtItsRemainingProduceWithoutReharvestingOrSkippingToAncient() {
        Fixture f=new Fixture();f.tomatoes();f.failTransfer=true;f.start();f.run(100);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND),f.harvested());
        assertEquals(4,f.count(ItemData.TOMATO));assertFalse(f.profile.nextEligibleDay.containsKey("harvest:Ancient"));
        Map<String,Long> before=Map.copyOf(f.profile.nextEligibleDay);
        f.opened=null;f.failTransfer=false;f.requireTomatoStoredBeforeAncient=true;f.start();f.run(150);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND,CROP),f.harvested());
        before.forEach((key,value)->assertEquals(value,f.profile.nextEligibleDay.get(key)));
        assertEquals(4,f.stored(TOMATO_STORE,ItemData.TOMATO));assertEquals(2,f.stored(STORE,FRUIT));
    }
    @Test void cropLinkEditsDuringThePriorDepositWaitForItsExistingAckButCannotAuthorizeTheNextCrop() {
        Fixture f=new Fixture();f.tomatoes();f.holdTransfer=true;f.start();f.run(80);
        f.profile.cropStores.put(CropRules.ANCIENT_FRUIT,"changed");int sent=f.history.size();f.run(20);
        assertEquals(sent,f.history.size());assertTrue(f.engine.running());f.releaseTransfer();f.run(20);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertEquals(sent,f.history.size());
        assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND),f.harvested());assertFalse(f.profile.nextEligibleDay.containsKey("harvest:Ancient"));
    }
    @Test void aNewDayDuringStorageRechecksTomatoPriorityWithoutInventingEitherCropCycle() {
        Fixture f=new Fixture();f.tomatoes();f.holdClose=true;f.start();f.run(80);
        assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND),f.harvested());
        f.day+=24000;
        for(Pos pos:List.of(TOMATO_FIRST,TOMATO_SECOND))f.blocks.put(pos,new BlockData(pos,"farmersdelight:tomatoes",Map.of("age","3")));
        f.releaseClose();f.run(200);
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());
        assertEquals(List.of(TOMATO_FIRST,TOMATO_SECOND,TOMATO_FIRST,TOMATO_SECOND,CROP),f.harvested());
        assertEquals(443L,f.profile.nextEligibleDay.get("harvest:Tomato first"));assertEquals(452L,f.profile.nextEligibleDay.get("harvest:Ancient"));
        assertEquals(8,f.stored(TOMATO_STORE,ItemData.TOMATO));assertEquals(2,f.stored(STORE,FRUIT));
    }
    @Test void anEmptyExplicitlyFutureCropWithoutUsableStorageDoesNotSuppressOtherContinuousJobsOrSleep() {
        for(boolean missingLink:List.of(false,true)) {
            Fixture f=new Fixture();Pos second=new Pos(1,64,2),bed=new Pos(30,64,0);
            f.profile.farms.add(new Farm("Ancient second",second,second,CropRules.ANCIENT_FRUIT));
            f.profile.nextEligibleDay.put("harvest:Ancient",450L);f.profile.nextEligibleDay.put("harvest:Ancient second",451L);
            if(missingLink)f.profile.cropStores.clear();else f.profile.commodityStores.clear();
            f.day=441L*24000+13000;f.profile.pois.add(new Poi(bed,PoiKind.BED,"Bed",null));
            f.blocks.put(bed,new BlockData(bed,"minecraft:red_bed",Map.of()));
            for(Feature feature:List.of(Feature.HARVEST,Feature.SEED_MAKER,Feature.SLEEP))f.profile.enabled.put(feature,true);
            Map<String,Long> dates=Map.copyOf(f.profile.nextEligibleDay);
            f.engine.start(f.context);f.run(40);
            assertTrue(f.engine.running(),f.engine.status());assertEquals(1,f.sleepUses);assertTrue(f.unrelatedTicks>0);
            assertEquals(0,f.harvests);assertEquals(0,f.transfers);assertEquals(dates,f.profile.nextEligibleDay);
            assertEquals(List.of(new Action.UseBlock(bed,Action.Use.SLEEP)),f.history);
        }
    }
    @Test void missingStorageStillBlocksUnknownDueHeldOrUnownedCropJobsBeforeAnyHarvest() {
        for(String guard:List.of("unknown","nullDate","due","past","held","oneMissing","oneDue","noDefinition","noMatchingFarm")) {
            Fixture f=new Fixture();f.profile.cropStores.clear();f.profile.nextEligibleDay.put("harvest:Ancient",450L);
            switch(guard) {
                case "unknown" -> f.profile.nextEligibleDay.remove("harvest:Ancient");
                case "nullDate" -> f.profile.nextEligibleDay.put("harvest:Ancient",null);
                case "due" -> f.profile.nextEligibleDay.put("harvest:Ancient",441L);
                case "past" -> f.profile.nextEligibleDay.put("harvest:Ancient",440L);
                case "held" -> f.inventory[1]=item(FRUIT,1,0);
                case "oneMissing","oneDue" -> {
                    Pos second=new Pos(1,64,2);f.profile.farms.add(new Farm("Ancient second",second,second,CropRules.ANCIENT_FRUIT));
                    if(guard.equals("oneDue"))f.profile.nextEligibleDay.put("harvest:Ancient second",441L);
                }
                case "noDefinition" -> f.profile.crops.remove(CropRules.ANCIENT_FRUIT);
                case "noMatchingFarm" -> {
                    f.profile.farms=List.of(new Farm("Only tomato",TOMATO_FIRST,TOMATO_FIRST));
                    f.profile.nextEligibleDay.put("harvest:Only tomato",450L);f.profile.cropStores.put(CropRules.ANCIENT_FRUIT,"missing");
                }
            }
            Map<String,Long> dates=new HashMap<>(f.profile.nextEligibleDay);f.start();f.run(40);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),guard);assertTrue(f.history.isEmpty(),guard);
            assertEquals(dates,f.profile.nextEligibleDay);assertEquals(guard.equals("held")?1:0,f.count(FRUIT));
        }
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();final Context context=new Context(this,this,this,profile,session);
        final ItemData[] inventory=new ItemData[36];final Map<Pos,BlockData> blocks=new HashMap<>();
        final Map<Pos,ItemData[]> chests=new HashMap<>();final Map<Long,ActionOutcome> outcomes=new HashMap<>();final List<Action> history=new ArrayList<>();
        final HarvestAndStorageModule harvest=new HarvestAndStorageModule(new HarvestModule());final AutomationEngine engine;
        long now=100,last,day=441L*24000+5000;int selected,menuId,harvests,transfers,unrelatedTicks,sleepUses,emptySlotsBeforeTomatoDeposit=-1;
        boolean holdTransfer,holdClose,failTransfer,storageBlocked,requireTomatoStoredBeforeAncient,sleeping;
        Pos opened;Action.QuickMove delayed;Action.CloseContainer delayedClose;long delayedId,delayedCloseId;
        Fixture(){
            Arrays.fill(inventory,ItemData.EMPTY);inventory[0]=new ItemData("minecraft:diamond_hoe",1,0,null,true,999);
            profile.enabled.replaceAll((k,v)->false);profile.farms.add(new Farm("Ancient",CROP,CROP,CropRules.ANCIENT_FRUIT));
            profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit and eggs",Set.of(FRUIT,"minecraft:egg"),List.of(STORE)));
            profile.cropStores.put(CropRules.ANCIENT_FRUIT,"fruit");blocks.put(CROP,new BlockData(CROP,FRUIT,Map.of("age","10")));
            ItemData[] chest=new ItemData[27];Arrays.fill(chest,ItemData.EMPTY);chests.put(STORE,chest);
            engine=new AutomationEngine(List.of(harvest,new AutomationModule(){public Feature feature(){return Feature.SEED_MAKER;}public int priority(){return 72;}public WorkResult tick(Context c){unrelatedTicks++;return WorkResult.idle();}public void reset(){}},new SleepModule()));
        }
        void legacyTomatoStore(){profile.pois.add(new Poi(TOMATO_STORE,PoiKind.TOMATO_CHEST,"Tomato warehouse",null));ItemData[] chest=new ItemData[27];Arrays.fill(chest,ItemData.EMPTY);chests.put(TOMATO_STORE,chest);}
        void tomatoes(){legacyTomatoStore();profile.farms.add(new Farm("Tomato first",TOMATO_FIRST,TOMATO_FIRST));profile.farms.add(new Farm("Tomato second",TOMATO_SECOND,TOMATO_SECOND));for(Pos pos:List.of(TOMATO_FIRST,TOMATO_SECOND))blocks.put(pos,new BlockData(pos,"farmersdelight:tomatoes",Map.of("age","3")));}
        List<Pos> harvested(){return history.stream().filter(a->a instanceof Action.UseBlock use&&use.purpose()==Action.Use.HARVEST).map(a->((Action.UseBlock)a).pos()).toList();}
        void start(){engine.startOnce(context,Feature.HARVEST);}void run(int ticks){for(int i=0;i<ticks;i++){engine.tick(context);now++;}}
        int count(String id){return Arrays.stream(inventory).filter(i->i.is(id)).mapToInt(ItemData::count).sum();}
        int stored(Pos pos,String id){return Arrays.stream(chests.get(pos)).filter(i->i.is(id)).mapToInt(ItemData::count).sum();}
        public long tick(){return now;}public long dayTime(){return day;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,sleeping,20,20,selected,true,true);}
        public BlockData block(Pos pos){return blocks.getOrDefault(pos,new BlockData(pos,chests.containsKey(pos)?"minecraft:barrel":"minecraft:air",chests.containsKey(pos)?Map.of("container","true"):Map.of()));}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public boolean canInteract(Pos p,double reach){return true;}public List<BlockData> scan(Pos p,int h,int v){return List.copyOf(blocks.values());}
        public HarvestFootprint harvestFootprint(Pos target){return HarvestFootprint.single(target);}
        public List<ItemSlot> inventory(){List<ItemSlot> result=new ArrayList<>();for(int i=0;i<36;i++)result.add(new ItemSlot(i,i,true,inventory[i]));return result;}
        public MenuData menu(){List<ItemSlot> slots=new ArrayList<>();if(opened!=null)for(int i=0;i<27;i++)slots.add(new ItemSlot(i,-1,false,chests.get(opened)[i]));for(int i=0;i<36;i++)slots.add(new ItemSlot((opened==null?0:27)+i,i,true,inventory[i]));return new MenuData(opened==null?0:menuId,0,slots,ItemData.EMPTY,opened!=null);}
        public boolean mayPlace(int slot,ItemData item){return opened!=null && slot>=0 && slot<27;}
        public Result moveTo(Pos p,double reach,Context c){return storageBlocked&&chests.containsKey(p)?Result.BLOCKED:Result.ARRIVED;}
        public void reset(){}public boolean busy(){return delayed!=null||delayedClose!=null;}
        public long submit(Action action){
            assertNull(SafetyPolicy.rejection(action,context),action.toString());history.add(action);long id=++last;
            if(action instanceof Action.UseBlock use){
                if(use.purpose()==Action.Use.HARVEST){harvests++;CropDefinition crop=CropRules.registeredCrop(profile,use.pos());if(requireTomatoStoredBeforeAncient&&crop.key().equals(CropRules.ANCIENT_FRUIT)){assertEquals(0,count(ItemData.TOMATO));assertNull(opened);assertTrue(history.stream().anyMatch(Action.CloseContainer.class::isInstance));}blocks.put(use.pos(),new BlockData(use.pos(),block(use.pos()).id(),Map.of("age","0")));put(inventory,item(crop.itemId(),2,-1));}
                else if(use.purpose()==Action.Use.OPEN_CONTAINER){opened=use.pos();menuId++;if(opened.equals(TOMATO_STORE))emptySlotsBeforeTomatoDeposit=(int)Arrays.stream(inventory).filter(ItemData::empty).count();}
                else if(use.purpose()==Action.Use.SLEEP){sleepUses++;sleeping=true;}
                else throw new AssertionError("Unrelated use "+use.purpose());
            }else if(action instanceof Action.QuickMove move){
                transfers++;if(failTransfer){outcomes.put(id,new ActionOutcome(ActionOutcome.State.FAILED,"storage refused"));return id;}
                if(holdTransfer){delayed=move;delayedId=id;outcomes.put(id,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return id;}
                int n=transfer(move);outcomes.put(id,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native transfer",n));return id;
            }else if(action instanceof Action.CloseContainer close){if(holdClose){delayedClose=close;delayedCloseId=id;outcomes.put(id,new ActionOutcome(ActionOutcome.State.PENDING,"waiting for close"));return id;}opened=null;}
            else if(action instanceof Action.SelectHotbar select){selected=select.slot();}
            else throw new AssertionError("Unexpected inventory action "+action);
            outcomes.put(id,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native confirmation"));return id;
        }
        private int transfer(Action.QuickMove move){ItemSlot slot=menu().slot(move.slot());assertTrue(slot.player());assertTrue(CommodityStorageRules.depositAllowed(context,opened,slot.item())||SafetyPolicy.registered(profile,opened,PoiKind.TOMATO_CHEST)&&TomatoStorageRules.permitsTransfer(slot.item(),menu()));int count=slot.item().count();put(chests.get(opened),slot.item());inventory[slot.inventoryIndex()]=ItemData.EMPTY;return count;}
        void releaseTransfer(){Action.QuickMove move=delayed;delayed=null;holdTransfer=false;outcomes.put(delayedId,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native transfer",transfer(move)));}
        void releaseClose(){assertNotNull(delayedClose);delayedClose=null;holdClose=false;opened=null;outcomes.put(delayedCloseId,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native close"));}
        public ActionOutcome outcome(long id){return outcomes.get(id);}public void move(Movement m){}public void stopMovement(){}public void cancel(){}
        private static void put(ItemData[] slots,ItemData item){for(int i=0;i<slots.length;i++)if(slots[i].empty()){slots[i]=item;return;}throw new AssertionError("No room");}
    }
}
