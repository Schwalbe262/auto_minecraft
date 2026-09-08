package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarfruitModuleTest {
    private static final Pos FRUIT=new Pos(3,1,0),STORE=new Pos(20,0,0);

    @Test void onlyCurrentlyLoadedNearbyRegisteredMatureFruitIsConsidered() {
        Fixture f=new Fixture();Pos far=new Pos(60,1,0),unloaded=new Pos(2,1,0),unregistered=new Pos(1,1,0);
        f.patch(FRUIT,far,unloaded);f.fruit(FRUIT,6);f.fruit(far,7);f.fruit(unloaded,7);f.fruit(unregistered,7);
        f.unloaded.add(unloaded);
        assertEquals(WorkResult.State.IDLE,f.step().state());
        assertEquals(List.of(FRUIT),f.blockReads);assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());
        assertEquals(0,f.scans);
    }

    @Test void missingOrWrongItemStoreDoesNotAuthorizeFruitPicking() {
        Fixture f=new Fixture();f.profile.commodityStores.clear();
        assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.submitted.isEmpty());
        f.profile.commodityStores.put("fruit",new CommodityStore("fruit","Other",Set.of(ItemData.TOMATO),List.of(STORE)));
        assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.travel.isEmpty());
    }

    @Test void aRegisteredCommodityStillRequiresCurrentOrdinaryContainerProof() {
        Fixture f=new Fixture();Action open=new Action.UseBlock(STORE,Action.Use.OPEN_CONTAINER);
        assertNull(SafetyPolicy.rejection(open,f.context));
        f.blocks.put(STORE,new BlockData(STORE,"minecraft:chest",Map.of()));
        assertNotNull(SafetyPolicy.rejection(open,f.context),"A block ID alone does not prove a live container");
        f.blocks.put(STORE,new BlockData(STORE,"society:seed_maker",Map.of("container","true")));
        assertNotNull(SafetyPolicy.rejection(open,f.context),"Commodity registration must not authorize a machine menu");
        f.blocks.put(STORE,new BlockData(STORE,"minecraft:barrel",Map.of("container","true")));
        assertNull(SafetyPolicy.rejection(open,f.context));
    }

    @Test void choosesAnExplicitEmptyHotbarWithoutBorrowingFoodOrTools() {
        Fixture f=new Fixture();f.put(1,f.food);f.put(2,f.tool);f.run();
        assertEquals(List.of(new Action.SelectHotbar(3)),f.submitted.stream().filter(Action.SelectHotbar.class::isInstance).toList());
        assertEquals(f.tool,f.inventory.get(0).item());assertEquals(f.food,f.inventory.get(1).item());assertEquals(f.tool,f.inventory.get(2).item());
        assertTrue(f.submitted.stream().noneMatch(Action.SwapHotbar.class::isInstance));
        assertEquals(List.of(FRUIT),f.clicked());assertEquals(1,f.stored);assertEquals(WorkResult.State.IDLE,f.result.state());
    }

    @Test void anAlreadySelectedOwnedFruitIsSafeAndDoesNotNeedASelectOrSwap() {
        Fixture f=new Fixture();f.selected=2;f.put(2,f.product(3));f.run();
        assertTrue(f.submitted.stream().noneMatch(a -> a instanceof Action.SelectHotbar || a instanceof Action.SwapHotbar));
        assertEquals(4,f.stored);assertEquals(1,f.clicked().size());
    }

    @Test void filledOrUnknownHotbarNeverBorrowsAnItem() {
        for (boolean missing:List.of(false,true)) {
            Fixture f=new Fixture();
            if (missing) f.inventory.removeIf(s -> s.inventoryIndex()<9);
            else for (int index=0;index<9;index++) f.put(index,f.food);
            assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());
        }
    }

    @Test void fullInventoryDoesNotTurnTheOptionalHarvestIntoUncollectedOutput() {
        Fixture f=new Fixture();for (int index=0;index<36;index++) f.put(index,f.food);
        f.selected=2;f.put(2,f.product(63));
        assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.submitted.isEmpty());
    }

    @Test void oneNearbyPickNeverStartsAWholePatchPatrol() {
        Fixture f=new Fixture();Pos other=new Pos(5,1,0);f.patch(FRUIT,other,new Pos(200,1,0));f.fruit(other,7);
        f.run();assertEquals(List.of(FRUIT),f.clicked());assertTrue(f.travel.stream().allMatch(p -> p.equals(FRUIT)||p.equals(STORE)));
        assertEquals(7,f.blocks.get(other).number("age",-1));assertTrue(f.blockReads.stream().noneMatch(p -> p.x()==200));
    }

    @Test void aTargetWhichLeavesLocalReachOrBecomesImmatureIsNotChasedOrClicked() {
        for (boolean immature:List.of(false,true)) {
            Fixture f=new Fixture();f.step();
            if (immature) f.fruit(FRUIT,6);else f.x=100;
            assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.clicked().isEmpty());
            assertTrue(f.travel.stream().allMatch(FRUIT::equals));
        }
    }

    @Test void nearbyNavigationIsBoundedAndNeverRetriesAUse() {
        Fixture f=new Fixture();f.navigationResult=Navigation.Result.MOVING;f.run();
        assertEquals(WorkResult.State.IDLE,f.result.state());assertTrue(f.clicked().isEmpty());assertTrue(f.now<=102);
        Fixture blocked=new Fixture();blocked.navigationResult=Navigation.Result.BLOCKED;
        assertEquals(WorkResult.State.IDLE,blocked.step().state());assertTrue(blocked.submitted.isEmpty());
    }

    @Test void aPendingNativeAckDoesNotBecomeConfirmedFromInventoryOrAgeAlone() {
        Fixture f=new Fixture();f.untilFruit();f.fruit(FRUIT,0);f.put(9,f.product(1));
        for(int tick=0;tick<30;tick++) assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(1,f.clicked().size());assertFalse(f.travel.contains(STORE));assertEquals(0,f.stored);
        f.deliver=false;f.complete(true);f.run();assertEquals(1,f.stored);assertEquals(1,f.clicked().size());
    }

    @Test void nativeSuccessAlsoRequiresTheSameFruitWithAValidResetAge() {
        for (String replacement:List.of("mature","air","missingAge","wrongPosition","unloaded")) {
            Fixture f=new Fixture();f.profile.interactionTimeoutTicks=5;f.untilFruit();f.changeAge=false;f.deliver=false;f.complete(true);
            switch(replacement) {
                case "air" -> f.blocks.put(FRUIT,new BlockData(FRUIT,"minecraft:air",Map.of()));
                case "missingAge" -> f.blocks.put(FRUIT,new BlockData(FRUIT,FruitRules.BLOCK,Map.of()));
                case "wrongPosition" -> f.blocks.put(FRUIT,new BlockData(FRUIT.offset(1,0,0),FruitRules.BLOCK,Map.of("age","0")));
                case "unloaded" -> f.unloaded.add(FRUIT);
                default -> { }
            }
            f.runUntilTerminal();assertEquals(WorkResult.State.BLOCKED,f.result.state(),replacement);
            assertEquals(1,f.clicked().size());assertFalse(f.travel.contains(STORE));
        }
    }

    @Test void failedUseCannotBeReclassifiedAsSuccessfulByAnAgeChange() {
        Fixture f=new Fixture();f.untilFruit();f.fruit(FRUIT,0);f.complete(false);
        assertEquals(WorkResult.State.BLOCKED,f.step().state());assertEquals(1,f.clicked().size());assertFalse(f.travel.contains(STORE));
    }

    @Test void registrationChangesWaitForTheSubmittedNativeOperationButNeverGrantANewOne() {
        Fixture f=new Fixture();f.untilFruit();f.profile.fruitPatches=List.of();
        assertEquals(WorkResult.State.BUSY,f.step().state());assertTrue(f.busy());assertEquals(1,f.clicked().size());
        f.complete(true);assertEquals(WorkResult.State.BLOCKED,f.step().state());assertFalse(f.travel.contains(STORE));
    }

    @Test void arrivedFruitEntersStorageImmediatelyWithoutAnArtificialSettlingDelay() {
        Fixture f=new Fixture();f.untilFruit();f.complete(true);long ack=f.now;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        f.step();assertTrue(f.travel.contains(STORE));assertTrue(f.now-ack<=2);
        f.run();assertEquals(1,f.stored);
    }

    @Test void delayedPickupIsObservedBeforeStorageAndExactStoreAckFinishesThePass() {
        Fixture f=new Fixture();f.untilFruit();f.deliver=false;f.complete(true);
        for(int tick=0;tick<8;tick++) f.step();
        assertFalse(f.travel.contains(STORE));f.put(9,f.product(1));
        f.untilAction(Action.QuickMove.class);
        for(int tick=0;tick<8;tick++) assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(0,f.stored);f.complete(true);f.run();assertEquals(1,f.stored);
        assertEquals(WorkResult.State.IDLE,f.result.state());assertFalse(f.menu().container());
    }

    @Test void noPickupEndsAfterItsBoundedObservationWithoutCreatingDebtOrRepeatingTheFruit() {
        Fixture f=new Fixture();f.deliver=false;f.run();
        assertTrue(f.now<30);assertEquals(WorkResult.State.IDLE,f.result.state());assertEquals(0,f.stored);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.fruit(FRUIT,7);f.module.reset();assertEquals(WorkResult.State.IDLE,f.step().state());assertEquals(1,f.clicked().size());
    }

    @Test void confirmedSameDayEvidenceSurvivesResetButANewDayCanPickAgain() {
        Fixture f=new Fixture();f.run();f.fruit(FRUIT,7);f.module.reset();
        assertEquals(WorkResult.State.IDLE,f.step().state());assertEquals(1,f.clicked().size());
        f.day+=24000;f.run();assertEquals(2,f.clicked().size());assertEquals(2,f.stored);
    }

    @Test void lateFruitAfterThePickupWindowIsStoredAtTheNextSafeBoundaryWithoutAnotherPick() {
        Fixture f=new Fixture();f.deliver=false;f.run();
        assertEquals(0,f.stored);assertEquals(1,f.clicked().size());
        f.x=100;f.put(9,f.product(1));int travelBefore=f.travel.size();
        f.run();
        assertEquals(1,f.stored);assertEquals(1,f.clicked().size());
        assertTrue(f.travel.subList(travelBefore,f.travel.size()).stream().allMatch(STORE::equals));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }

    @Test void lateFruitCleanupDoesNotNeedAnEmptyHotbarOrAnotherPickupSlot() {
        Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;
        for(int index=0;index<36;index++)f.put(index,f.food);
        f.put(9,f.product(1));f.run();
        assertEquals(1,f.stored);assertEquals(1,f.clicked().size());
        assertTrue(f.submitted.stream().noneMatch(Action.SwapHotbar.class::isInstance));
        assertEquals(f.food,f.inventory.get(0).item());
    }

    @Test void lateFruitCannotUseAChangedRegistrationOrAnUnrelatedGenericStore() {
        for(boolean removed:List.of(false,true)) {
            Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;f.put(9,f.product(1));
            if(removed)f.profile.fruitPatches=List.of();
            else f.profile.fruitPatches=List.of(new FruitPatch("changed","fruit",List.of(FRUIT)));
            int actions=f.submitted.size();f.run();
            assertEquals(actions,f.submitted.size());assertEquals(0,f.stored);
        }
    }

    @Test void delayedCleanupLeavesManualOffMenusAndUnrelatedPendingActionsAlone() {
        for(String guard:List.of("off","menu","busy")) {
            Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;f.put(9,f.product(1));
            if(guard.equals("off"))f.profile.enabled.put(Feature.STARFRUIT,false);
            else if(guard.equals("menu"))f.open=true;
            else f.externalBusy=true;
            int actions=f.submitted.size(),stops=f.stops;f.step();
            assertEquals(WorkResult.State.IDLE,f.result.state(),guard);
            assertEquals(actions,f.submitted.size());assertEquals(stops,f.stops);assertEquals(0,f.stored);
        }
    }

    @Test void lateCleanupDoesNotFollowAReplacedStoreEvenIfItsIdAndItemStayTheSame() {
        Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;f.put(9,f.product(1));
        f.profile.commodityStores.put("fruit",new CommodityStore("fruit","Moved",Set.of(FruitRules.ITEM),List.of(STORE.offset(5,0,0))));
        int actions=f.submitted.size(),travel=f.travel.size();f.run();
        assertEquals(actions,f.submitted.size());assertEquals(travel,f.travel.size());assertEquals(0,f.stored);
    }

    @Test void aStoreEditDuringLateDepositWaitsForTheExistingAckAndPreventsFurtherActions() {
        Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;f.put(9,f.product(1));
        f.untilAction(Action.QuickMove.class);int actions=f.submitted.size();
        f.profile.commodityStores.put("fruit",new CommodityStore("fruit","Moved",Set.of(FruitRules.ITEM),List.of(STORE.offset(5,0,0))));
        assertEquals(WorkResult.State.BUSY,f.step().state());assertEquals(actions,f.submitted.size());
        f.complete(true);assertEquals(WorkResult.State.BLOCKED,f.step().state());assertEquals(actions,f.submitted.size());
    }

    @Test void anUnconfirmedUseCannotCreateALateCleanupDestination() {
        Fixture f=new Fixture();f.untilFruit();f.complete(false);f.step();
        assertEquals(WorkResult.State.BLOCKED,f.result.state());
        f.fruit(FRUIT,0);f.x=100;f.put(9,f.product(1));int actions=f.submitted.size();f.run();
        assertEquals(actions,f.submitted.size());assertEquals(0,f.stored);
    }

    @Test void registrationRemovalDuringLateDepositWaitsForItsAckButSendsNoNextAction() {
        Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;f.put(9,f.product(1));
        f.untilAction(Action.QuickMove.class);f.profile.fruitPatches=List.of();int actions=f.submitted.size();
        assertEquals(WorkResult.State.BUSY,f.step().state());assertEquals(actions,f.submitted.size());
        f.complete(true);assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(actions,f.submitted.size());assertEquals(1,f.stored);
    }

    @Test void aDifferentProfileCannotInheritAPreviousFruitCleanupDestination() {
        Fixture f=new Fixture();f.deliver=false;f.run();f.x=100;f.put(9,f.product(1));
        Profile replacement=new Profile();replacement.enabled.put(Feature.STARFRUIT,true);
        replacement.fruitPatches=List.copyOf(f.profile.fruitPatches);replacement.commodityStores.putAll(f.profile.commodityStores);
        int actions=f.submitted.size();
        assertEquals(WorkResult.State.IDLE,f.module.tick(new Context(f,f,f,replacement)).state());
        assertEquals(actions,f.submitted.size());assertEquals(0,f.stored);
    }

    @Test void aUseConfirmedAfterMidnightBelongsToTheActualConfirmationDay() {
        Fixture f=new Fixture();f.untilFruit();f.day+=24000;f.complete(true);f.run();
        f.fruit(FRUIT,7);assertEquals(WorkResult.State.IDLE,f.step().state());assertEquals(1,f.clicked().size());
    }

    @Test void anUnrelatedBusyActionAndOpenMenuAreLeftUntouched() {
        for(boolean menu:List.of(false,true)) {
            Fixture f=new Fixture();f.externalBusy=!menu;f.open=menu;
            assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.submitted.isEmpty());
            assertTrue(f.travel.isEmpty());assertEquals(0,f.stops);assertEquals(0,f.resets);
        }
    }

    @Test void schedulerDoesNotPreemptAnActiveBusyJobAndOnceKeepsTheSavedSwitchOff() {
        Fixture f=new Fixture();int[] calls={0};
        AutomationModule busyJob=new AutomationModule() {
            public Feature feature(){return Feature.HARVEST;}public int priority(){return 1;}
            public WorkResult tick(Context c){calls[0]++;return WorkResult.busy("existing harvest");}public void reset(){}
        };
        AutomationEngine engine=new AutomationEngine(List.of(f.module,busyJob));engine.start(f.context);
        for(int tick=0;tick<20;tick++){engine.tick(f.context);f.now++;}
        assertEquals(20,calls[0]);assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());
        f.profile.enabled.put(Feature.STARFRUIT,false);engine.startOnce(f.context,Feature.STARFRUIT);
        for(int tick=0;tick<100&&engine.running();tick++){engine.tick(f.context);if(f.busy())f.complete(true);f.now++;}
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertEquals(1,f.stored);
        assertFalse(f.profile.enabled(Feature.STARFRUIT));assertEquals(20,calls[0]);
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final StarfruitModule module=new StarfruitModule();
        final Context context=new Context(this,this,this,profile);
        final Map<Pos,BlockData> blocks=new HashMap<>();final Set<Pos> unloaded=new HashSet<>();
        final List<Pos> blockReads=new ArrayList<>(),travel=new ArrayList<>();
        final List<ItemSlot> inventory=new ArrayList<>();final List<Action> submitted=new ArrayList<>();
        final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final ItemData tool=new ItemData("minecraft:iron_hoe",1,0,null,true,100),food=new ItemData("farmersdelight:fruit_salad",57,0,null,false,0);
        long now,day=5000,current;int selected,stored,stops,resets,scans;
        double x=.5;boolean open,externalBusy,changeAge=true,deliver=true;
        Navigation.Result navigationResult=Navigation.Result.ARRIVED;
        WorkResult result=WorkResult.busy("initial");
        Fixture() {
            profile.enabled.put(Feature.STARFRUIT,true);
            profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit",Set.of(FruitRules.ITEM),List.of(STORE)));
            // MinecraftWorld reports this flag only for an observed native container.
            patch(FRUIT);fruit(FRUIT,7);blocks.put(STORE,new BlockData(STORE,"minecraft:chest",Map.of("container","true")));
            for(int index=0;index<36;index++)inventory.add(new ItemSlot(index,index,true,index==0?tool:ItemData.EMPTY));
        }
        void patch(Pos...positions){profile.fruitPatches=List.of(new FruitPatch("nearby","fruit",List.of(positions)));}
        void fruit(Pos pos,int age){blocks.put(pos,new BlockData(pos,FruitRules.BLOCK,Map.of("age",String.valueOf(age))));}
        ItemData product(int count){return new ItemData(FruitRules.ITEM,count,0,null,false,0);}
        void put(int index,ItemData item){inventory.set(index,new ItemSlot(index,index,true,item));}
        List<Pos> clicked(){return submitted.stream().filter(a->a instanceof Action.UseBlock use&&use.purpose()==Action.Use.FRUIT).map(a->((Action.UseBlock)a).pos()).toList();}
        WorkResult step(){result=module.tick(context);now++;return result;}
        void run(){runUntilTerminal();assertEquals(WorkResult.State.IDLE,result.state(),result.message());}
        void runUntilTerminal(){for(int tick=0;tick<250;tick++){step();if(busy())complete(true);if(result.state()!=WorkResult.State.BUSY)return;}fail("Fruit pass did not terminate");}
        void untilFruit(){untilAction(Action.UseBlock.class);assertEquals(Action.Use.FRUIT,((Action.UseBlock)submitted.get(submitted.size()-1)).purpose());}
        void untilAction(Class<? extends Action> type){
            for(int tick=0;tick<100;tick++) {
                step();assertEquals(WorkResult.State.BUSY,result.state(),result.message());
                if(busy()){if(type.isInstance(submitted.get(submitted.size()-1)))return;complete(true);}
            }
            fail("Expected "+type.getSimpleName());
        }
        void complete(boolean success) {
            Action action=submitted.get(submitted.size()-1);int moved=0;
            if(success) {
                if(action instanceof Action.SelectHotbar select)selected=select.slot();
                else if(action instanceof Action.UseBlock use) {
                    if(use.purpose()==Action.Use.FRUIT){if(changeAge)fruit(use.pos(),0);if(deliver)put(9,product(inventory.get(9).item().count()+1));}
                    else if(use.purpose()==Action.Use.OPEN_CONTAINER)open=true;
                    else fail("Unexpected use "+use);
                } else if(action instanceof Action.QuickMove move) {
                    int source=move.slot()-27;ItemData item=inventory.get(source).item();assertTrue(item.is(FruitRules.ITEM));
                    moved=item.count();stored+=moved;put(source,ItemData.EMPTY);
                } else if(action instanceof Action.CloseContainer)open=false;
                else fail("Unexpected action "+action);
            }
            outcomes.put(current,new ActionOutcome(success?ActionOutcome.State.SUCCEEDED:ActionOutcome.State.FAILED,"native ack",moved));
        }
        public long tick(){return now;}public long dayTime(){return day;}
        public PlayerState player(){return new PlayerState(x,0,.5,0,0,true,false,20,20,selected,true,true);}
        public BlockData block(Pos pos){blockReads.add(pos);return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of()));}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos pos,int horizontal,int vertical){scans++;throw new AssertionError("Opportunistic fruit must not scan a tree");}
        public List<ItemSlot> inventory(){return inventory;}
        public MenuData menu(){
            if(!open)return new MenuData(0,0,inventory,ItemData.EMPTY,false);
            List<ItemSlot> slots=new ArrayList<>();for(int index=0;index<27;index++)slots.add(new ItemSlot(index,-1,false,index==0&&stored>0?product(stored):ItemData.EMPTY));
            for(ItemSlot slot:inventory)slots.add(new ItemSlot(slot.inventoryIndex()+27,slot.inventoryIndex(),true,slot.item()));
            return new MenuData(12,1,slots,ItemData.EMPTY,true);
        }
        public boolean mayPlace(int slot,ItemData item){return slot>=0&&slot<27;}public boolean canInteract(Pos pos,double reach){return true;}
        public boolean busy(){return externalBusy||current>0&&!outcomes.get(current).done();}
        public long submit(Action action){assertFalse(busy());assertNull(SafetyPolicy.rejection(action,context));submitted.add(action);outcomes.put(++current,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return current;}
        public ActionOutcome outcome(long ticket){return outcomes.get(ticket);}
        public void move(Movement movement){}public void stopMovement(){stops++;}public void cancel(){}
        public Result moveTo(Pos target,double reach,Context c){travel.add(target);return navigationResult;}public void reset(){resets++;}
    }
}
