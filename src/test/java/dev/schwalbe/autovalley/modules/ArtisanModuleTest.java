package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtisanModuleTest {
    private static ItemData item(String id,int count,int quality) { return new ItemData(id,count,quality,null,false,999); }

    @Test void seedMakerCollectsFourOldSeedsRefillsOnceAndReturnsOnlyUnusedFruit() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,4);f.inventory[1]=item(f.recipe.inputId(),16,0);
        f.inventory[8]=item("minecraft:egg",7,0);
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),400);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());assertEquals(4,f.uses);assertEquals(12,f.consumed);
        assertEquals(4,f.stored(f.output,f.recipe.outputId()));assertEquals(4,f.stored(f.input,f.recipe.inputId()));
        assertEquals(7,f.inventory[8].count(),"a mixed store does not authorize unrelated cleanup");
        assertEquals(0,f.opens.getOrDefault(f.input,0)-1,"usable carried ingredients do not trigger a source stock survey");
        assertEquals(4,f.profile.nextEligibleDay.size());
    }
    @Test void sourceSurveyChoosesLargestActualGradeButNeverWithdrawsForeignMixedContents() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,4);
        f.chests.get(f.input)[0]=item(f.recipe.inputId(),16,0);f.chests.get(f.input)[1]=item(f.recipe.inputId(),18,1);
        f.chests.get(f.input)[2]=item("minecraft:egg",9,0);
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),500);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());assertEquals(List.of(1,1,1,1),f.usedGrades);
        assertEquals(16,f.chests.get(f.input)[0].count());assertEquals(9,f.chests.get(f.input)[2].count());
        assertEquals(6,f.storedGrade(f.input,f.recipe.inputId(),1));assertEquals(1,f.withdrawals);
    }
    @Test void partialManualSeedFillConsumesOnlyItsMissingInputsAndPersistsOneCompletedBatch() {
        for(int cost:List.of(1,2,3)) {
            Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),3,0);f.seedConsumption=cost;
            f.machineStates.replaceAll((p,b)->f.state(p,false,false));
            WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),300);
            assertEquals(WorkResult.State.IDLE,result.state(),result.message());assertEquals(1,f.uses);
            assertEquals(List.of(3),f.handCounts);assertEquals(cost,f.consumed);
            assertEquals(3-cost,f.stored(f.input,f.recipe.inputId()));assertEquals(0,f.stored(f.output,f.recipe.outputId()));
            assertEquals(List.of(436L),List.copyOf(f.profile.nextEligibleDay.values()));
        }
    }
    @Test void partialSeedFillCannotAuthorizeNoConsumptionOrAnExcessiveDecrease() {
        for(int cost:List.of(0,4)) {
            Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),8,0);f.seedConsumption=cost;
            f.machineStates.replaceAll((p,b)->f.state(p,false,false));
            assertEquals(WorkResult.State.BLOCKED,f.run(new ArtisanModule(f.recipe.feature()),300).state());
            assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }
    @Test void upgradedSeedBonusIsStoredWithoutChangingFeedCostOrSchedule() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),3,0);f.upgraded=true;f.emitBonus=true;
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(f.recipe.feature()),300).state());
        assertEquals(3,f.consumed);assertEquals(2,f.stored(f.output,f.recipe.outputId()));
        assertEquals(1,f.uses);assertEquals(List.of(436L),List.copyOf(f.profile.nextEligibleDay.values()));
    }
    @Test void pristineJadeBonusRemainsInInventoryWithExplicitStatusAndNoExtraStoragePermission() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);f.inventory[1]=item(f.recipe.inputId(),1,0);f.upgraded=true;f.emitBonus=true;
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(f.recipe.feature()),300).state());
        assertEquals(1,f.consumed);assertEquals(2,f.stored(f.output,f.recipe.outputId()));
        assertEquals(1,Arrays.stream(f.inventory).filter(i->i.is("society:pristine_jade")).mapToInt(ItemData::count).sum());
        assertEquals(0,f.stored(f.output,"society:pristine_jade"));assertEquals(0,f.stored(f.input,"society:pristine_jade"));
        assertTrue(f.statuses.stream().anyMatch(s->s.contains("보너스 비취")&&s.contains("자동 보관·판매하지 않습니다")));
        assertEquals(1,f.uses);assertEquals(List.of(440L),List.copyOf(f.profile.nextEligibleDay.values()));
    }
    @Test void serverRejectionOfADifferentPartialRecipeYieldsAndNeverRetriesTheTarget() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.foreignRecipeReject=true;
        f.machineStates.replaceAll((p,b)->f.state(p,false,false));
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),300).state());
        assertEquals(1,f.uses);assertEquals(0,f.consumed);assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(6,f.stored(f.input,f.recipe.inputId()));
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),300).state());
        assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void emptyInventoryCrystalCollectionBootstrapsRefillAndStoresOnlyItsNetSurplus() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,3);
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),500);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());assertEquals(4,f.uses);
        assertEquals(3,f.consumed);assertEquals(3,f.stored(f.output,f.recipe.outputId()));assertEquals(0,f.withdrawals);
        assertEquals(0,f.opens.getOrDefault(f.input,0));assertEquals(3,f.profile.nextEligibleDay.size());
        assertTrue(f.profile.nextEligibleDay.values().stream().allMatch(d -> d==440));
    }
    @Test void sameDayResetPreservesAlreadyAcknowledgedMachineDueDates() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,4);f.inventory[1]=item(f.recipe.inputId(),16,0);
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        for(int i=0;i<100 && f.profile.nextEligibleDay.isEmpty();i++) { module.tick(f.context());f.now++; }
        assertEquals(1,f.profile.nextEligibleDay.size());module.reset();
        assertEquals(WorkResult.State.IDLE,f.run(module,500).state());assertEquals(4,f.uses);
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(f.recipe.feature()),100).state());assertEquals(4,f.uses);
    }
    @Test void pendingAndFailedNativeAcksCannotCreateExtraMachineUsesOrSchedules() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        for(int i=0;i<70;i++){module.tick(f.context());f.now++;}
        assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.FAILED,"unconfirmed native reply"));
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());assertEquals(1,f.uses);
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void nativeSuccessStillRequiresObservedInputAndWorkingStateBeforeCheckpoint() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.suppressMutation=true;
        assertEquals(WorkResult.State.BLOCKED,f.run(new ArtisanModule(f.recipe.feature()),250).state());
        assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void checkpointFailureRollsBackTheDueMapAndDoesNotRetryTheNativeUse() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.failCheckpoint=true;
        assertEquals(WorkResult.State.BLOCKED,f.run(new ArtisanModule(f.recipe.feature()),250).state());
        assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void latePickupWaitsWithoutReusingTheMachineAndTimeoutOnlyDefersThisFeature() {
        for(boolean absent:new boolean[]{false,true}) {
            Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.pickupDelay=absent ? 10000 : 20;
            WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),400);
            assertEquals(absent ? WorkResult.State.DEFERRED : WorkResult.State.IDLE,result.state(),result.message());
            assertEquals(1,f.uses);assertEquals(1,f.profile.nextEligibleDay.size());assertFalse(f.menu().container());
            assertEquals(absent ? 0 : 1,f.stored(f.output,f.recipe.outputId()));
        }
    }
    @Test void finalRecipeHotbarIsPrefilledFromLargerOwnedStackWithoutBorrowingTools() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),3,0);f.inventory[9]=item(f.recipe.inputId(),60,0);
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(f.recipe.feature()),300).state());
        assertEquals(List.of(60),f.handCounts);assertTrue(f.history.contains(new Action.SwapHotbar(9,1)));
        assertTrue(f.inventory[0].hoe());
    }
    @Test void noHotbarToolsOrFoodAreBorrowedWhenNoOwnedOrEmptySlotExists() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[9]=item(f.recipe.inputId(),8,0);
        for(int i=1;i<9;i++) f.inventory[i]=item("minecraft:bread",1,0);
        assertEquals(WorkResult.State.BLOCKED,f.run(new ArtisanModule(f.recipe.feature()),100).state());
        assertEquals(0,f.uses);assertTrue(f.history.stream().noneMatch(Action.SwapHotbar.class::isInstance));
    }
    @Test void insufficientSourceYieldsWithoutPretendingThatAnUnfedMatureMachineCompleted() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.chests.get(f.input)[0]=item(f.recipe.inputId(),2,0);
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),200).state());
        assertEquals(0,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(f.machineStates.values().iterator().next().flag("mature"));
    }
    @Test void unchangedOrWrongContainerCannotAuthorizeAWithdrawal() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.chests.get(f.input)[0]=item(f.recipe.inputId(),16,0);f.wrongOpen=true;
        assertEquals(WorkResult.State.BLOCKED,f.run(new ArtisanModule(f.recipe.feature()),100).state());assertEquals(0,f.withdrawals);assertEquals(0,f.uses);
    }
    @Test void dawnSnapshotWaitDoesNotOpenContainersOrStartTinyMachineBatches() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,4);f.dayTime=435L*24000+100;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        for(int i=0;i<100;i++){assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());f.now++;}
        assertTrue(f.history.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void alreadyWorkingMachinesAreObservedButNeverClickedOrCountedAsNewFeeds() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,3);f.inventory[1]=item(f.recipe.inputId(),6,0);
        Pos first=f.machineStates.keySet().iterator().next();f.machineStates.put(first,f.state(first,false,true));
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(f.recipe.feature()),400).state());
        assertEquals(2,f.uses);assertEquals(6,f.consumed);
        assertTrue(f.history.stream().noneMatch(a->a instanceof Action.UseBlock use && use.purpose()==Action.Use.ARTISAN && use.pos().equals(first)));
    }
    @Test void selectedFeatureDoesNotServiceAnotherRecipesRegisteredJob() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);
        Pos foreign=new Pos(30,64,0);
        f.profile.artisanJobs.put("crystal",new ArtisanJob("crystal","jade_crystal",List.of(foreign),"input","output"));
        f.profile.enabled.put(Feature.CRYSTAL_COPY,true);
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(Feature.SEED_MAKER),300).state());
        assertEquals(1,f.uses);assertTrue(f.history.stream().noneMatch(a->a instanceof Action.UseBlock use && use.pos().equals(foreign)));
    }
    @Test void unknownIngredientGradeCannotBeChosenOrWithdrawnByCommodityRegistrationAlone() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.chests.get(f.input)[0]=item(f.recipe.inputId(),64,-1);
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),150).state());
        assertEquals(0,f.withdrawals);assertEquals(0,f.uses);assertEquals(64,f.stored(f.input,f.recipe.inputId()));
    }
    @Test void uncertainPreviousUseDefersWithoutAnyNewMachinePacketOrFakeDueDate() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.uncertainArtisan=true;
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),150);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertEquals(0,f.uses);
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertFalse(f.menu().container());
    }
    @Test void timedOutUseWithTargetFenceYieldsAfterCleanupInsteadOfGloballyBlocking() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        for(int i=0;i<100 && f.uses==0;i++){module.tick(f.context());f.now++;}
        assertEquals(1,f.uses);f.uncertainArtisan=true;
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.FAILED,"unconfirmed native reply"));
        WorkResult result=f.run(module,150);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertEquals(1,f.uses);
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertFalse(f.menu().container());
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),150).state());assertEquals(1,f.uses);
    }
    @Test void continuousEngineCanSleepWhileAnUnconfirmedArtisanTargetCannotBeReclicked() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.uncertainArtisan=true;f.dayTime=435L*24000+13000;
        f.profile.pois.add(new Poi(new Pos(3,64,0),PoiKind.BED,"Bed",null));
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        AutomationEngine engine=new AutomationEngine(List.of(module,new SleepModule()));engine.start(f.context());
        for(int i=0;i<180 && f.sleepUses==0;i++){engine.tick(f.context());f.now++;}
        assertEquals(1,f.sleepUses);assertEquals(0,f.uses);assertTrue(engine.running());
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void continuousEngineCanSleepAfterInsufficientIngredientCleanup() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.dayTime=435L*24000+13000;
        f.chests.get(f.input)[0]=item(f.recipe.inputId(),2,0);
        f.profile.pois.add(new Poi(new Pos(3,64,0),PoiKind.BED,"Bed",null));
        AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),new SleepModule()));engine.start(f.context());
        for(int i=0;i<200 && f.sleepUses==0;i++){engine.tick(f.context());f.now++;}
        assertEquals(1,f.sleepUses);assertEquals(0,f.uses);assertFalse(f.menu().container());
    }
    @Test void retryableNavigationDeferralDoesNotBorrowTheArtisanSleepException() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.dayTime=435L*24000+13000;f.navigationBlocked=true;
        f.profile.pois.add(new Poi(new Pos(3,64,0),PoiKind.BED,"Bed",null));
        AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),new SleepModule()));engine.start(f.context());
        for(int i=0;i<200;i++){engine.tick(f.context());f.now++;}
        assertEquals(0,f.sleepUses);assertEquals(0,f.uses);assertTrue(engine.running());
    }
    @Test void sleepSafeGrantRequiresCleanCurrentStateAndResetRevokesIt() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.uncertainArtisan=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());assertFalse(module.sleepSafeDeferred(f.context()));
        assertEquals(WorkResult.State.DEFERRED,f.run(module,100).state());assertTrue(module.sleepSafeDeferred(f.context()));
        f.cursor=item(f.recipe.inputId(),1,0);assertFalse(module.sleepSafeDeferred(f.context()));f.cursor=ItemData.EMPTY;
        f.grounded=false;assertFalse(module.sleepSafeDeferred(f.context()));f.grounded=true;
        f.actionFence="native inventory uncertainty";assertFalse(module.sleepSafeDeferred(f.context()));f.actionFence=null;
        assertTrue(module.sleepSafeDeferred(f.context()));module.reset();assertFalse(module.sleepSafeDeferred(f.context()));
    }
    @Test void selectedArtisanOneShotNeverRunsSleepEvenWhenItsWaitIsSleepSafe() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.uncertainArtisan=true;f.dayTime=435L*24000+13000;
        f.profile.pois.add(new Poi(new Pos(3,64,0),PoiKind.BED,"Bed",null));
        AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),new SleepModule()));
        Context c=f.context();engine.startOnce(c,f.recipe.feature());
        for(int i=0;i<200;i++){engine.tick(c);f.now++;}
        assertEquals(0,f.sleepUses);assertEquals(0,f.uses);assertTrue(engine.running());
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final ArtisanRecipe recipe;final Profile profile=new Profile();final ItemData[] inventory=new ItemData[36];
        final Pos input=new Pos(1,64,0),output=new Pos(2,64,0);
        final Map<Pos,ItemData[]> chests=new LinkedHashMap<>();final Map<Pos,BlockData> machineStates=new LinkedHashMap<>();
        final List<Action> history=new ArrayList<>();final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final Map<Pos,Integer> opens=new HashMap<>();final List<Integer> usedGrades=new ArrayList<>(),handCounts=new ArrayList<>();
        final List<Delayed> delayed=new ArrayList<>();
        final List<String> statuses=new ArrayList<>();
        long now=100,dayTime=435L*24000+5000,lastTicket;int selected=1,menuId,uses,consumed,withdrawals,pickupDelay,sleepUses;
        int seedConsumption=-1;
        boolean holdUse,suppressMutation,failCheckpoint,wrongOpen,uncertainArtisan,navigationBlocked,sleeping,grounded=true,upgraded,emitBonus,foreignRecipeReject;Pos opened;
        ItemData cursor=ItemData.EMPTY;String actionFence;
        Fixture(ArtisanRecipe recipe,int count) {
            this.recipe=recipe;Arrays.fill(inventory,ItemData.EMPTY);inventory[0]=new ItemData("minecraft:golden_hoe",1,0,null,true,99);
            profile.enabled.put(recipe.feature(),true);profile.hoeHotbarSlot=0;
            chests.put(input,emptyChest());chests.put(output,emptyChest());
            profile.commodityStores.put("input",new CommodityStore("input","Mixed input",Set.of(recipe.inputId(),"minecraft:egg"),List.of(input)));
            profile.commodityStores.put("output",new CommodityStore("output","Output",Set.of(recipe.outputId()),List.of(output)));
            for(int i=0;i<count;i++){Pos p=new Pos(10+i,64,0);machineStates.put(p,state(p,true,false));}
            profile.artisanJobs.put("job",new ArtisanJob("job",recipe.id(),List.copyOf(machineStates.keySet()),"input","output"));
        }
        private ItemData[] emptyChest(){ItemData[] items=new ItemData[27];Arrays.fill(items,ItemData.EMPTY);return items;}
        BlockData state(Pos pos,boolean mature,boolean working){return new BlockData(pos,recipe.machineId(),Map.of("mature",""+mature,"working",""+working,"upgraded",""+upgraded));}
        Context context(){return new Context(this,this,new Navigation(){public Result moveTo(Pos p,double reach,Context c){return navigationBlocked?Result.BLOCKED:Result.ARRIVED;}public boolean retryableFailure(){return navigationBlocked;}public Failure failureKind(){return navigationBlocked?Failure.NO_PATH:Failure.NONE;}public void reset(){}},profile,new SessionState(),()->{if(failCheckpoint)throw new IllegalStateException("checkpoint failed");});}
        WorkResult run(ArtisanModule module,int limit){WorkResult r=WorkResult.busy("");for(int i=0;i<limit;i++){for(Iterator<Delayed> it=delayed.iterator();it.hasNext();){Delayed d=it.next();if(now>=d.at){put(inventory,d.item);it.remove();}}r=module.tick(context());statuses.add(r.message());now++;if(r.state()!=WorkResult.State.BUSY)return r;}return r;}
        int stored(Pos p,String id){return Arrays.stream(chests.get(p)).filter(i->i.is(id)).mapToInt(ItemData::count).sum();}
        int storedGrade(Pos p,String id,int q){return Arrays.stream(chests.get(p)).filter(i->i.is(id)&&i.quality()==q).mapToInt(ItemData::count).sum();}
        public long tick(){return now;}public long dayTime(){return dayTime;}
        public PlayerState player(){return new PlayerState(10.5,64,.5,0,0,grounded,sleeping,20,20,selected,true,true);}
        public BlockData block(Pos p){return machineStates.getOrDefault(p,new BlockData(p,chests.containsKey(p)?"minecraft:barrel":"minecraft:air",chests.containsKey(p)?Map.of("container","true"):Map.of()));}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public boolean canInteract(Pos p,double reach){return true;}public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){List<ItemSlot> slots=new ArrayList<>();for(int i=0;i<36;i++)slots.add(new ItemSlot(i,i,true,inventory[i]));return slots;}
        public MenuData menu(){List<ItemSlot> slots=new ArrayList<>();if(opened!=null)for(int i=0;i<27;i++)slots.add(new ItemSlot(i,-1,false,chests.get(opened)[i]));for(int i=0;i<36;i++)slots.add(new ItemSlot(opened==null?i:i<9?54+i:18+i,i,true,inventory[i]));return new MenuData(opened==null?0:menuId,0,slots,cursor,opened!=null);}
        public boolean mayPlace(int slot,ItemData item){return true;}public boolean busy(){return false;}
        public String artisanRejection(Pos target){return uncertainArtisan ? "Unconfirmed target" : null;}
        public String pauseReason(){return actionFence;}
        public long submit(Action action) {
            history.add(action);long id=++lastTicket;int count=0;
            if(action instanceof Action.UseBlock use) {
                if(use.purpose()==Action.Use.OPEN_CONTAINER){if(!wrongOpen){opened=use.pos();menuId++;opens.merge(opened,1,Integer::sum);}}
                else if(use.purpose()==Action.Use.SLEEP){sleepUses++;sleeping=true;}
                else if(use.purpose()==Action.Use.ARTISAN){
                    uses++;ItemData held=inventory[selected];handCounts.add(held.count());boolean feed=held.is(recipe.inputId())&&held.count()>=recipe.inputCount();
                    if(feed)usedGrades.add(held.quality());BlockData before=machineStates.get(use.pos());
                    if(foreignRecipeReject){uncertainArtisan=true;outcomes.put(id,new ActionOutcome(ActionOutcome.State.FAILED,"unchanged raw idle block and selected input"));return id;}
                    if(!suppressMutation){if(feed){int cost=seedConsumption>=0 && !before.flag("mature") ? seedConsumption : recipe.inputCount();consumed+=cost;inventory[selected]=withCount(held,held.count()-cost);}
                        if(before.flag("mature")&&before.flag("upgraded")&&emitBonus)put(inventory,item(recipe.sameInputAndOutput()?"society:pristine_jade":recipe.outputId(),1,0));
                        if(before.flag("mature")){ItemData produced=item(recipe.outputId(),recipe.outputCount(),0);if(pickupDelay>0)delayed.add(new Delayed(now+pickupDelay,produced));else put(inventory,produced);}
                        machineStates.put(use.pos(),state(use.pos(),false,feed));}
                    if(holdUse){outcomes.put(id,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return id;}
                }
            } else if(action instanceof Action.CloseContainer){opened=null;}
            else if(action instanceof Action.SelectHotbar select){selected=select.slot();}
            else if(action instanceof Action.SwapHotbar swap){ItemData old=inventory[swap.hotbarSlot()];inventory[swap.hotbarSlot()]=inventory[swap.inventoryIndex()];inventory[swap.inventoryIndex()]=old;}
            else if(action instanceof Action.QuickMove move){
                assertEquals(menuId,move.containerId());ItemSlot slot=menu().slots().stream().filter(s->s.index()==move.slot()).findFirst().orElseThrow();count=slot.item().count();
                if(slot.player()){put(chests.get(opened),slot.item());inventory[slot.inventoryIndex()]=ItemData.EMPTY;}
                else {withdrawals++;assertEquals(recipe.inputId(),slot.item().id());put(inventory,slot.item());chests.get(opened)[slot.index()]=ItemData.EMPTY;}
            } else throw new AssertionError("Unexpected action: "+action);
            outcomes.put(id,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"confirmed",count));return id;
        }
        public ActionOutcome outcome(long id){return outcomes.get(id);}public void move(Movement m){}public void stopMovement(){}public void cancel(){}
        static ItemData withCount(ItemData i,int n){return n<=0?ItemData.EMPTY:new ItemData(i.id(),n,i.quality(),i.year(),i.hoe(),i.durability());}
        static void put(ItemData[] slots,ItemData item){for(int i=0;i<slots.length;i++)if(ModuleSupport.same(slots[i],item)&&slots[i].count()+item.count()<=64){slots[i]=withCount(item,slots[i].count()+item.count());return;}for(int i=0;i<slots.length;i++)if(slots[i].empty()){slots[i]=item;return;}throw new AssertionError("No capacity");}
        record Delayed(long at,ItemData item){}
    }
}
