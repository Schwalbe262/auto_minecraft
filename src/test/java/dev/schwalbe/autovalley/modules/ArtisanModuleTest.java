package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtisanModuleTest {
    private static ItemData item(String id,int count,int quality) { return new ItemData(id,count,quality,null,false,999); }

    @Test void coolingMachinesWithoutOwnedItemsYieldImmediatelyOnEverySweep() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.ANCIENT_SEED,ArtisanRecipe.JADE_CRYSTAL)) {
            Fixture f=new Fixture(recipe,4);coolJob(f,"job",500);
            f.inventory[8]=item("minecraft:egg",7,0);
            f.inventory[7]=item("society:pristine_jade",1,0);
            Map<String,Long> schedule=Map.copyOf(f.profile.nextEligibleDay);
            ArtisanModule module=new ArtisanModule(recipe.feature());
            for(int i=0;i<40;i++){assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());f.now++;}
            assertTrue(f.history.isEmpty());assertTrue(f.navigationTargets.isEmpty());
            assertEquals(schedule,f.profile.nextEligibleDay);assertEquals(7,f.inventory[8].count());
        }
    }
    @Test void noConfiguredJobAlsoYieldsOnItsFirstTick() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.profile.artisanJobs.clear();
        assertEquals(WorkResult.State.IDLE,new ArtisanModule(f.recipe.feature()).tick(f.context()).state());
        assertTrue(f.history.isEmpty());assertTrue(f.navigationTargets.isEmpty());
    }
    @Test void quietFirstJobDoesNotHideALaterDueJob() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);coolJob(f,"job",500);
        Pos quiet=f.machineStates.keySet().iterator().next(),due=new Pos(20,64,0);
        f.machineStates.put(due,f.state(due,true,false));
        f.profile.artisanJobs.put("later",new ArtisanJob("later",f.recipe.id(),List.of(due),"input","output"));
        f.chests.get(f.input)[0]=item(f.recipe.inputId(),3,0);
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        assertEquals(WorkResult.State.IDLE,f.run(module,300).state());
        assertEquals(0,f.machineUses(quiet));assertEquals(1,f.machineUses(due));
        assertFalse(f.navigationTargets.contains(quiet));assertEquals(500L,f.profile.nextEligibleDay.get(f.profile.artisanJobs.get("job").scheduleKey(quiet)));
    }
    @Test void coolingJobsStillStoreActualHeldInputsAndOutputsWithoutVisitingMachines() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.ANCIENT_SEED,ArtisanRecipe.JADE_CRYSTAL)) {
            Fixture f=new Fixture(recipe,2);coolJob(f,"job",500);
            f.inventory[1]=item(recipe.inputId(),7,0);
            if(!recipe.sameInputAndOutput())f.inventory[2]=item(recipe.outputId(),2,0);
            ArtisanModule module=new ArtisanModule(recipe.feature());
            assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
            assertEquals(WorkResult.State.IDLE,f.run(module,200).state());
            assertEquals(0,f.uses);assertEquals(0,f.withdrawals);
            assertTrue(f.navigationTargets.stream().noneMatch(f.machineStates::containsKey));
            assertEquals(recipe.sameInputAndOutput()?7:2,f.stored(f.output,recipe.outputId()));
            if(!recipe.sameInputAndOutput())assertEquals(7,f.stored(f.input,recipe.inputId()));
            assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        }
    }
    @Test void continuousEngineReachesLowerWorkAndActualSleepWhileBothArtisansAreCooling() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,2);coolJob(f,"job",500);
        ArtisanRecipe jade=ArtisanRecipe.JADE_CRYSTAL;Pos crystal=new Pos(30,64,0),jadeStore=new Pos(31,64,0);
        f.profile.enabled.put(Feature.CRYSTAL_COPY,true);f.profile.enabled.put(Feature.STARFRUIT,true);f.profile.enabled.put(Feature.SLEEP,true);
        f.profile.commodityStores.put("jade",new CommodityStore("jade","Jade",Set.of(jade.inputId()),List.of(jadeStore)));
        f.profile.artisanJobs.put("crystal",new ArtisanJob("crystal",jade.id(),List.of(crystal),"jade","jade"));coolJob(f,"crystal",500);
        f.profile.pois.add(new Poi(new Pos(3,64,0),PoiKind.BED,"Bed",null));
        int[] lowerTicks={0};
        AutomationModule lower=new AutomationModule(){
            public Feature feature(){return Feature.STARFRUIT;}public int priority(){return 80;}
            public WorkResult tick(Context c){return ++lowerTicks[0]<=3?WorkResult.busy("lower work"):WorkResult.idle();}
            public void reset(){}
        };
        Context c=f.context();AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(Feature.SEED_MAKER),
            new ArtisanModule(Feature.CRYSTAL_COPY),lower,new SleepModule()));engine.start(c);
        for(int i=0;i<100;i++){engine.tick(c);f.now++;}
        assertTrue(lowerTicks[0]>3);assertTrue(engine.running());assertEquals(AutomationEngine.State.WAITING,engine.state());
        assertTrue(f.history.isEmpty());assertTrue(f.navigationTargets.isEmpty());
        f.dayTime=435L*24000+13000;
        for(int i=0;i<100 && f.sleepUses==0;i++){engine.tick(c);f.now++;}
        assertEquals(1,f.sleepUses);assertEquals(0,f.uses);
        engine.tick(c);f.now++; // Observe actual sleeping before advancing the test world's day.
        f.dayTime=436L*24000+5000;f.sleeping=false;int before=lowerTicks[0];
        for(int i=0;i<100;i++){engine.tick(c);f.now++;}
        assertTrue(lowerTicks[0]>before);assertTrue(engine.running());
        assertEquals(1,f.history.size());assertTrue(f.profile.nextEligibleDay.values().stream().allMatch(d->d==500));
        assertTrue(f.navigationTargets.stream().noneMatch(p->f.machineStates.containsKey(p)||p.equals(crystal)));
    }
    @Test void quietTrailingJobDoesNotEraseEarlierTargetUncertainty() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);Pos uncertain=f.machineStates.keySet().iterator().next(),quiet=new Pos(20,64,0);
        f.uncertainTargets.put(uncertain,"unconfirmed target");
        f.machineStates.put(quiet,f.state(quiet,true,false));
        f.profile.artisanJobs.put("quiet",new ArtisanJob("quiet",f.recipe.id(),List.of(quiet),"input","output"));coolJob(f,"quiet",500);
        ArtisanModule module=new ArtisanModule(f.recipe.feature());WorkResult result=f.run(module,100);
        assertEquals(WorkResult.State.DEFERRED,result.state());assertTrue(result.message().contains("job: unconfirmed target"));
        assertTrue(module.sleepSafeDeferred(f.context()));assertTrue(f.history.isEmpty());assertTrue(f.navigationTargets.isEmpty());
    }
    @Test void coolingSelectedOneShotCompletesWithoutRunningNeighbouringModules() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);coolJob(f,"job",500);f.dayTime=435L*24000+13000;
        Context c=f.context();AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),new SleepModule()));
        engine.startOnce(c,f.recipe.feature());engine.tick(c);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertTrue(f.history.isEmpty());assertTrue(f.navigationTargets.isEmpty());
    }
    private static void coolJob(Fixture f,String id,long due) {
        ArtisanJob job=f.profile.artisanJobs.get(id);for(Pos pos:job.machines())f.profile.nextEligibleDay.put(job.scheduleKey(pos),due);
    }

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
    @Test void crystalCollectionWithNineNonJobHotbarItemsDefersBeforeAnyActionOrCheckpoint() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.failCheckpoint=true;
        ItemData[] before=f.inventory.clone();ArtisanModule module=new ArtisanModule(f.recipe.feature());
        WorkResult result=f.run(module,100);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertTrue(result.message().contains("단축바에 빈칸 1개"));
        assertTrue(result.message().contains("결정복제기"));assertFalse(result.message().contains("IllegalStateException"));
        assertArrayEquals(before,f.inventory);assertTrue(f.history.isEmpty());assertEquals(0,f.uses);assertEquals(0,f.withdrawals);
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(module.sleepSafeDeferred(f.context()));
    }
    @Test void bothArtisanRecipesWithIngredientsOnlyInMainInventoryNeverBorrowToolsOrFood() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.JADE_CRYSTAL,ArtisanRecipe.ANCIENT_SEED)) {
            Fixture f=new Fixture(recipe,1);fillNonJobHotbar(f);f.inventory[9]=item(recipe.inputId(),recipe.inputCount(),2);f.failCheckpoint=true;
            ItemData[] before=f.inventory.clone();WorkResult result=f.run(new ArtisanModule(recipe.feature()),100);
            assertEquals(WorkResult.State.DEFERRED,result.state(),recipe.id()+": "+result.message());
            assertTrue(result.message().contains("단축바에 빈칸 1개"));assertArrayEquals(before,f.inventory);
            assertTrue(f.history.isEmpty());assertEquals(0,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }
    @Test void missingHotbarCapacityIsReportedBeforeOpeningOrWithdrawingFromTheSeedIngredientStore() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);fillNonJobHotbar(f);
        f.chests.get(f.input)[0]=item(f.recipe.inputId(),64,2);f.failCheckpoint=true;
        ItemData[] before=f.inventory.clone();WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),100);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertTrue(result.message().contains("씨앗기"));
        assertTrue(f.history.isEmpty());assertEquals(0,f.withdrawals);assertEquals(64,f.stored(f.input,f.recipe.inputId()));
        assertArrayEquals(before,f.inventory);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void hotbarShortageDoesNotWaivePendingNativeCustodyCursorMenuOrOutputFences() {
        for(String cause:List.of("busy","fence","lease","cursor","menu","output","airborne")) {
            Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.inventory[1]=ItemData.EMPTY;
            ArtisanModule module=new ArtisanModule(f.recipe.feature());
            // Reach EQUIP normally, before the preflight has sent a slot change or machine use.
            for(int i=0;i<4;i++){assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());f.now++;}
            fillNonJobHotbar(f);ItemData[] before=f.inventory.clone();assertTrue(f.history.isEmpty());
            switch(cause) {
                case "busy" -> f.actionBusy=true;
                case "fence" -> f.actionFence="unconfirmed native reply";
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(12,1,item(ItemData.TOMATO,3,0),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "cursor" -> f.cursor=item("minecraft:diamond",1,0);
                case "menu" -> {f.opened=f.input;f.menuId=1;}
                case "output" -> {String id="11111111-1111-1111-1111-111111111111";f.profile.pendingMachineOutputs.put(id,
                    new PendingMachineOutput(id,Feature.PRESERVES,new Pos(30,64,0),435,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));}
                case "airborne" -> f.grounded=false;
                default -> throw new AssertionError(cause);
            }
            assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state(),cause);assertFalse(module.sleepSafeDeferred(f.context()),cause);
            assertArrayEquals(before,f.inventory);assertTrue(f.history.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
            if(cause.equals("lease"))assertNotNull(f.profile.loggingHotbarLease);
            if(cause.equals("output"))assertEquals(1,f.profile.pendingMachineOutputs.size());
        }
    }
    @Test void continuousHotbarShortageBacksOffWhileOtherWorkRunsAndLaterCapacityUsesTheNormalRecipe() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.profile.enabled.put(Feature.STARFRUIT,true);
        int[] otherCalls={0};AutomationModule other=new AutomationModule(){
            public Feature feature(){return Feature.STARFRUIT;}public int priority(){return 90;}
            public WorkResult tick(Context c){otherCalls[0]++;return WorkResult.idle();}public void reset(){}
        };
        Context c=f.context();AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),other));engine.start(c);
        for(int i=0;i<100 && engine.state()!=AutomationEngine.State.WAITING;i++){engine.tick(c);f.now++;}
        assertEquals(AutomationEngine.State.WAITING,engine.state(),engine.status());assertTrue(engine.status().contains("1200틱"));
        int firstVisits=f.navigationTargets.size();assertTrue(firstVisits>0);int firstOthers=otherCalls[0];
        for(int i=0;i<600;i++){engine.tick(c);f.now++;}
        assertEquals(firstVisits,f.navigationTargets.size());assertTrue(otherCalls[0]>firstOthers);assertTrue(f.history.isEmpty());
        for(int i=0;i<900 && !engine.status().contains("2400틱");i++){engine.tick(c);f.now++;}
        assertTrue(engine.running(),engine.status());assertTrue(engine.status().contains("2400틱"),engine.status());
        assertEquals(firstVisits*2,f.navigationTargets.size());assertTrue(f.history.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        // A later inventory observation supplies capacity; the module itself never moves the old hotbar item.
        ItemData old=f.inventory[1];f.inventory[10]=old;f.inventory[1]=ItemData.EMPTY;
        for(int i=0;i<2600 && f.stored(f.output,f.recipe.outputId())==0;i++){engine.tick(c);f.now++;}
        assertTrue(engine.running(),engine.status());assertEquals(2,f.uses);assertEquals(1,f.consumed);
        assertEquals(1,f.stored(f.output,f.recipe.outputId()));assertEquals(old,f.inventory[10]);
        assertEquals(List.of(440L),List.copyOf(f.profile.nextEligibleDay.values()));
    }
    @Test void oneShotHotbarWaitNeverRestartsAfterManualOffAndExplicitResumeUsesTheNewFreeSlot() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.JADE_CRYSTAL,ArtisanRecipe.ANCIENT_SEED)) {
            Fixture f=new Fixture(recipe,1);fillNonJobHotbar(f);
            if(!recipe.sameInputAndOutput())f.inventory[9]=item(recipe.inputId(),recipe.inputCount(),0);
            Context c=f.context();AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(recipe.feature())));engine.startOnce(c,recipe.feature());
            for(int i=0;i<100 && engine.state()!=AutomationEngine.State.WAITING;i++){engine.tick(c);f.now++;}
            assertEquals(AutomationEngine.State.WAITING,engine.state(),engine.status());assertTrue(f.history.isEmpty());
            engine.stop(c,AutomationEngine.State.OFF,"manual F8 OFF");
            ItemData old=f.inventory[1];f.inventory[10]=old;f.inventory[1]=ItemData.EMPTY;
            f.now+=5000;engine.tick(c);assertEquals(AutomationEngine.State.OFF,engine.state());assertTrue(f.history.isEmpty());
            assertTrue(f.profile.nextEligibleDay.isEmpty());
            engine.startOnce(c,recipe.feature());
            for(int i=0;i<200 && engine.running();i++){engine.tick(c);f.now++;}
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());assertEquals(old,f.inventory[10]);
            assertEquals(recipe.sameInputAndOutput()?2:1,f.uses);assertEquals(recipe.inputCount(),f.consumed);
            assertEquals(1,f.profile.nextEligibleDay.size());
        }
    }
    private static void fillNonJobHotbar(Fixture f) {
        List<String> ids=List.of("minecraft:diamond_sword","minecraft:netherite_axe","minecraft:bread","minecraft:bricks",
            "society:hearthstone","minecraft:torch","minecraft:carrot","minecraft:coal");
        for(int i=1;i<9;i++)f.inventory[i]=item(ids.get(i-1),i,0);
    }
    @Test void bothArtisanRecipesPrepareAFullHotbarAndRestoreEveryOriginalItemAfterStorage() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.JADE_CRYSTAL,ArtisanRecipe.ANCIENT_SEED)) {
            Fixture f=new Fixture(recipe,3);fillNonJobHotbar(f);f.workspaceProofs=true;
            ItemData[] originalHotbar=Arrays.copyOf(f.inventory,9);
            if(!recipe.sameInputAndOutput())f.inventory[12]=item(recipe.inputId(),12,1);
            WorkResult result=f.run(new ArtisanModule(recipe.feature()),600);
            assertEquals(WorkResult.State.IDLE,result.state(),result.message());
            assertArrayEquals(originalHotbar,Arrays.copyOf(f.inventory,9));
            assertNull(f.profile.workHotbarLease);assertNull(f.session.workHotbarOwner);
            assertEquals(recipe.sameInputAndOutput()?4:3,f.uses);
            assertEquals(recipe.inputCount()*3,f.consumed);assertEquals(3,f.profile.nextEligibleDay.size());
            assertEquals(3,f.stored(f.output,recipe.outputId()));
            assertTrue(f.history.stream().filter(Action.SwapHotbar.class::isInstance).count()>=2);
        }
    }
    @Test void aFullMainInventoryStillCannotBeOverwrittenToCreateAWorkingHotbar() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.workspaceProofs=true;
        for(int i=9;i<36;i++)f.inventory[i]=item("minecraft:cobblestone",64,0);
        ItemData[] original=f.inventory.clone();
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),100).state());
        assertArrayEquals(original,f.inventory);assertTrue(f.history.isEmpty());assertNull(f.profile.workHotbarLease);
    }
    @Test void parkingProjectionCannotStartCrystalUseUntilTheSameSwapReceiptSucceeds() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.workspaceProofs=true;f.holdPark=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());
        for(int i=0;i<60;i++){assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());f.now++;}
        assertEquals(HotbarLease.Stage.PREPARED,f.profile.workHotbarLease.stage());assertEquals(1,f.history.size());assertEquals(0,f.uses);
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"park acknowledged"));f.holdPark=false;
        assertEquals(WorkResult.State.IDLE,f.run(module,300).state());assertEquals(2,f.uses);assertNull(f.profile.workHotbarLease);
    }
    @Test void anotherModuleCannotStartBeforeTheOriginalHotbarRestorationReceipt() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.workspaceProofs=true;f.holdRestore=true;
        f.profile.enabled.put(Feature.STARFRUIT,true);int[] neighbours={0};
        AutomationModule neighbour=new AutomationModule(){public Feature feature(){return Feature.STARFRUIT;}public int priority(){return 90;}
            public WorkResult tick(Context c){neighbours[0]++;return WorkResult.idle();}public void reset(){}};
        Context c=f.context();AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),neighbour));engine.start(c);
        for(int i=0;i<400;i++){engine.tick(c);f.now++;}
        assertTrue(engine.running(),engine.status());assertEquals(HotbarLease.Stage.RESTORING,f.profile.workHotbarLease.stage());
        assertEquals(0,neighbours[0]);int sent=f.history.size();
        for(int i=0;i<40;i++){engine.tick(c);f.now++;}assertEquals(sent,f.history.size());
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"restore acknowledged"));f.holdRestore=false;
        for(int i=0;i<100 && neighbours[0]==0;i++){engine.tick(c);f.now++;}
        assertTrue(neighbours[0]>0);assertNull(f.profile.workHotbarLease);assertTrue(engine.running());
    }
    @Test void explicitResumeRestoresAnInterruptedDisabledOwnerBeforeTheSelectedDifferentJob() {
        for(Feature requested:List.of(Feature.STARFRUIT,Feature.STORAGE_SURVEY)) {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);fillNonJobHotbar(f);f.workspaceProofs=true;
        ItemData[] originalHotbar=Arrays.copyOf(f.inventory,9);int[] other={0};
        AutomationModule selected=new AutomationModule(){public Feature feature(){return requested;}public int priority(){return 90;}
            public WorkResult tick(Context c){assertNull(c.profile().workHotbarLease);assertEquals(requested,c.session().oneShotFeature);other[0]++;return WorkResult.idle();}public void reset(){}};
        Context c=f.context();AutomationEngine engine=new AutomationEngine(List.of(new ArtisanModule(f.recipe.feature()),selected));engine.start(c);
        for(int i=0;i<30 && (f.profile.workHotbarLease==null || f.profile.workHotbarLease.stage()!=HotbarLease.Stage.PARKED);i++){engine.tick(c);f.now++;}
        assertNotNull(f.profile.workHotbarLease);assertEquals(0,f.uses);
        engine.stop(c,AutomationEngine.State.OFF,"manual F8 OFF");int sent=f.history.size();
        for(int i=0;i<2000;i++){engine.tick(c);f.now++;}assertEquals(sent,f.history.size());
        f.profile.enabled.put(f.recipe.feature(),false);engine.startOnce(c,requested);
        for(int i=0;i<100 && engine.running();i++){engine.tick(c);f.now++;}
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());assertEquals(1,other[0]);assertEquals(0,f.uses);
        assertNull(f.profile.workHotbarLease);assertArrayEquals(originalHotbar,Arrays.copyOf(f.inventory,9));
        assertFalse(f.profile.enabled(f.recipe.feature()));assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
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
        ItemData[] before=f.inventory.clone();
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),100).state());
        assertEquals(0,f.uses);assertTrue(f.history.stream().noneMatch(Action.SwapHotbar.class::isInstance));
        assertArrayEquals(before,f.inventory);assertTrue(f.profile.nextEligibleDay.isEmpty());
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
    @Test void firstMiddleOrLastUncertainTargetDoesNotStarveOtherMachines() {
        for(int blockedIndex:List.of(0,1,3)) {
            Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,4);f.inventory[1]=item(f.recipe.inputId(),16,0);
            Pos blocked=List.copyOf(f.machineStates.keySet()).get(blockedIndex);f.uncertainTargets.put(blocked,"Target-specific uncertainty");
            WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),700);
            assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertEquals(3,f.uses);
            assertEquals(9,f.consumed);assertEquals(3,f.stored(f.output,f.recipe.outputId()));assertEquals(7,f.stored(f.input,f.recipe.inputId()));
            assertEquals(3,f.profile.nextEligibleDay.size());assertFalse(f.profile.nextEligibleDay.containsKey(f.profile.artisanJobs.get("job").scheduleKey(blocked)));
            assertEquals(0,f.machineUses(blocked));assertFalse(f.menu().container());assertTrue(result.message().contains("job: Target-specific uncertainty"));
        }
    }
    @Test void independentJobsAllRunAndKeepSeparateUncertainReasons() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,2);f.inventory[1]=item(f.recipe.inputId(),18,0);
        Pos first=List.copyOf(f.machineStates.keySet()).get(0),secondBlocked=new Pos(20,64,0),secondGood=new Pos(21,64,0);
        f.machineStates.put(secondBlocked,f.state(secondBlocked,true,false));f.machineStates.put(secondGood,f.state(secondGood,true,false));
        f.profile.artisanJobs.put("second",new ArtisanJob("second",f.recipe.id(),List.of(secondBlocked,secondGood),"input","output"));
        f.uncertainTargets.put(first,"First reason");f.uncertainTargets.put(secondBlocked,"Second reason");
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),1000);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertEquals(2,f.uses);assertEquals(6,f.consumed);
        assertEquals(0,f.machineUses(first));assertEquals(0,f.machineUses(secondBlocked));assertEquals(1,f.machineUses(secondGood));
        assertEquals(2,f.profile.nextEligibleDay.size());assertEquals(2,f.stored(f.output,f.recipe.outputId()));
        assertEquals(12,f.stored(f.input,f.recipe.inputId()));assertFalse(f.menu().container());
        assertTrue(result.message().contains("job: First reason"));assertTrue(result.message().contains("second: Second reason"));
    }
    @Test void uncertainFailedUseSkipsOnlyThatMachineAndNeverRepeatsItsSentPacket() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,4);f.inventory[1]=item(f.recipe.inputId(),16,0);
        Pos failed=List.copyOf(f.machineStates.keySet()).get(1);f.failUncertainUses.add(failed);
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),700);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertEquals(4,f.uses);assertEquals(1,f.machineUses(failed));
        assertEquals(9,f.consumed);assertEquals(3,f.profile.nextEligibleDay.size());
        assertEquals(WorkResult.State.DEFERRED,f.run(new ArtisanModule(f.recipe.feature()),300).state());
        assertEquals(4,f.uses);assertEquals(1,f.machineUses(failed));
    }
    @Test void resetAndNewDayPreserveTargetUncertaintyAndOnlyRepeatActuallyDueSuccessfulMachines() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,2);f.inventory[1]=item(f.recipe.inputId(),12,0);
        List<Pos> machines=List.copyOf(f.machineStates.keySet());Pos blocked=machines.get(0),good=machines.get(1);
        f.uncertainTargets.put(blocked,"Still unconfirmed");ArtisanModule module=new ArtisanModule(f.recipe.feature());
        assertEquals(WorkResult.State.DEFERRED,f.run(module,500).state());assertEquals(1,f.uses);
        module.reset();assertEquals(WorkResult.State.DEFERRED,f.run(module,300).state());assertEquals(1,f.uses);
        f.dayTime+=24000;f.machineStates.put(good,f.state(good,true,false));module.reset();
        assertEquals(WorkResult.State.DEFERRED,f.run(module,700).state());assertEquals(2,f.uses);assertEquals(0,f.machineUses(blocked));
        assertEquals(List.of(437L),List.copyOf(f.profile.nextEligibleDay.values()));assertEquals(2,f.stored(f.output,f.recipe.outputId()));
    }
    @Test void targetSkipCannotPassGlobalUncertaintyBorrowedSlotsDirtyMenusOrAirborneState() {
        for(int guard=0;guard<7;guard++) {
            Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,2);f.inventory[1]=item(f.recipe.inputId(),12,0);
            f.uncertainTargets.put(f.machineStates.keySet().iterator().next(),"Target uncertainty");
            switch(guard) {
                case 0 -> f.actionFence="Unresolved inventory acknowledgement";
                case 1 -> f.actionBusy=true;
                case 2 -> f.cursor=item("minecraft:bread",1,0);
                case 3 -> f.opened=f.input;
                case 4 -> f.grounded=false;
                case 5 -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,2,item("minecraft:bread",1,0),"private-signature");
                case 6 -> f.profile.pendingMachineOutputs.put("pending",new PendingMachineOutput("pending",Feature.PRESERVES,new Pos(30,64,0),435,null,1,PendingMachineOutput.Phase.AWAITING_PICKUP));
            }
            WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),100);
            assertEquals(WorkResult.State.BLOCKED,result.state(),"guard="+guard+" "+result.message());
            assertEquals(0,f.uses);assertTrue(f.history.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }
    @Test void aNormalMissingMaterialDeferralStillStopsThatPassRatherThanExpandingJobPolicy() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,2);f.uncertainTargets.put(f.machineStates.keySet().iterator().next(),"First unknown");
        Pos later=new Pos(20,64,0);f.machineStates.put(later,f.state(later,true,false));
        f.profile.artisanJobs.put("later",new ArtisanJob("later",f.recipe.id(),List.of(later),"input","output"));
        WorkResult result=f.run(new ArtisanModule(f.recipe.feature()),300);
        assertEquals(WorkResult.State.DEFERRED,result.state(),result.message());assertEquals(0,f.uses);
        assertTrue(result.message().contains("재료가 부족"));assertTrue(result.message().contains("job: First unknown"));
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void nativeConfirmationAcrossMidnightAnchorsSeedAndJadeReinspectionToTheDispatchDay() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.ANCIENT_SEED,ArtisanRecipe.JADE_CRYSTAL)) {
            Fixture f=new Fixture(recipe,1);f.dayTime=435L*24000+23900;f.inventory[1]=item(recipe.inputId(),recipe.inputCount(),0);f.holdUse=true;
            ArtisanModule module=new ArtisanModule(recipe.feature());f.awaitUse(module);
            assertTrue(f.profile.nextEligibleDay.isEmpty(),"sending a use is not consumption proof");
            f.dayTime=436L*24000+100;f.holdUse=false;
            f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native working and selected-slot confirmation"));
            assertEquals(WorkResult.State.IDLE,f.run(module,300).state());
            assertEquals(List.of(435L+recipe.cycleDays()),List.copyOf(f.profile.nextEligibleDay.values()));assertEquals(1,f.uses);
        }
    }
    @Test void anAlreadyDueConfirmedBatchAllowsReinspectionButWorkingStateStillForbidsAnotherUse() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);f.dayTime=435L*24000+23900;
        f.inventory[1]=item(f.recipe.inputId(),1,0);f.holdUse=true;ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);
        f.dayTime=441L*24000+5000;f.holdUse=false;
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native working and selected-slot confirmation"));
        assertEquals(WorkResult.State.IDLE,f.run(module,300).state());
        assertEquals(List.of(440L),List.copyOf(f.profile.nextEligibleDay.values()));
        assertEquals(WorkResult.State.IDLE,f.run(new ArtisanModule(f.recipe.feature()),200).state());
        assertEquals(1,f.uses);assertEquals(List.of(442L),List.copyOf(f.profile.nextEligibleDay.values()),"working reinspection remains based on the current day");
    }
    @Test void midnightFailedOrStillPendingAckCannotCreateAScheduleFromDispatchDay() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.dayTime=435L*24000+23900;
        f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);
        f.dayTime=436L*24000+5000;
        for(int i=0;i<30;i++){assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());f.now++;}
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(1,f.uses);
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.FAILED,"no native confirmation"));
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(1,f.uses);
    }
    @Test void resetDiscardsTheOldDispatchAnchorAndResumeUsesObservedWorkingOrAFreshUse() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.dayTime=435L*24000+23900;
        f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"old acknowledged feed"));
        module.reset();f.holdUse=false;f.dayTime=436L*24000+5000;
        assertEquals(WorkResult.State.IDLE,f.run(module,300).state());
        assertEquals(1,f.uses);assertEquals(List.of(437L),List.copyOf(f.profile.nextEligibleDay.values()));
        f.dayTime=440L*24000+5000;f.machineStates.replaceAll((p,b)->f.state(p,true,false));module.reset();
        assertEquals(WorkResult.State.IDLE,f.run(module,500).state());
        assertEquals(2,f.uses);assertEquals(List.of(441L),List.copyOf(f.profile.nextEligibleDay.values()));
    }
    @Test void failedCrossMidnightCheckpointRestoresThePreviousSchedule() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.dayTime=435L*24000+23900;
        String key=f.profile.artisanJobs.get("job").scheduleKey(f.machineStates.keySet().iterator().next());f.profile.nextEligibleDay.put(key,434L);
        f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);
        f.dayTime=436L*24000+5000;f.failCheckpoint=true;
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native working and selected-slot confirmation"));
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state());assertEquals(434L,f.profile.nextEligibleDay.get(key));assertEquals(1,f.uses);
    }
    @Test void aMatureBlockWithoutTheExactTicketProgressProofCannotCompleteEvenWithCompatibleConsumption() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"ordinary success without cycle proof"));
        assertEquals(WorkResult.State.BLOCKED,f.run(module,300).state());assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void nativeAdvancedSeedAndJadeProceedToTheNextTargetWithoutReusingTheCompletedOne() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.ANCIENT_SEED,ArtisanRecipe.JADE_CRYSTAL)) {
            Fixture f=new Fixture(recipe,2);f.inventory[1]=item(recipe.inputId(),recipe.inputCount()*4,0);f.dayTime=435L*24000+23900;f.holdUse=true;
            ArtisanModule module=new ArtisanModule(recipe.feature());f.awaitUse(module);Pos first=List.copyOf(f.machineStates.keySet()).get(0),second=List.copyOf(f.machineStates.keySet()).get(1);
            f.dayTime=(435L+recipe.cycleDays())*24000+5000;f.machineStates.put(first,f.state(first,true,false));f.holdUse=false;
            f.outcomes.put(f.lastTicket,advancedOutcome());
            WorkResult result=f.run(module,600);
            assertEquals(WorkResult.State.IDLE,result.state(),result.message());assertEquals(2,f.uses);
            assertEquals(1,f.machineUses(first));assertEquals(1,f.machineUses(second));assertEquals(recipe.inputCount()*2,f.consumed);
            ArtisanJob job=f.profile.artisanJobs.get("job");
            assertEquals(435L+recipe.cycleDays(),f.profile.nextEligibleDay.get(job.scheduleKey(first)));
            assertEquals(435L+2*recipe.cycleDays(),f.profile.nextEligibleDay.get(job.scheduleKey(second)));
            assertTrue(f.machineStates.get(first).flag("mature"));assertTrue(f.machineStates.get(second).flag("working"));
            assertFalse(f.menu().container());assertEquals(recipe.sameInputAndOutput()?recipe.inputCount()*2+recipe.outputCount()*2:recipe.outputCount()*2,f.stored(f.output,recipe.outputId()));
        }
    }
    @Test void advancedProofDoesNotPermitWorkingReversalIdleOrAnotherMachineType() {
        for(int invalid=0;invalid<3;invalid++) {
            Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;
            ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);Pos target=f.machineStates.keySet().iterator().next();
            if(invalid==0)f.machineStates.put(target,f.state(target,false,true));
            if(invalid==1)f.machineStates.put(target,f.state(target,false,false));
            if(invalid==2)f.machineStates.put(target,new BlockData(target,ArtisanRecipe.JADE_CRYSTAL.machineId(),Map.of("mature","true","working","false")));
            f.outcomes.put(f.lastTicket,advancedOutcome());
            assertEquals(WorkResult.State.BLOCKED,f.run(module,300).state(),"invalid="+invalid);
            assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }
    @Test void matureWhilePendingOrCancelledDoesNotInventAProgressProof() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.holdUse=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);f.machineStates.replaceAll((p,b)->f.state(p,true,false));
        for(int i=0;i<30;i++){assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());f.now++;}
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(1,f.uses);
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.CANCELLED,"operator stopped before confirmation"));
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(1,f.uses);
    }
    @Test void anAdvancedProofCannotTurnEmptyHandCollectionIntoAConfirmedFeed() {
        Fixture f=new Fixture(ArtisanRecipe.JADE_CRYSTAL,1);f.holdUse=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);assertEquals(List.of(0),f.handCounts);
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));f.outcomes.put(f.lastTicket,advancedOutcome());
        assertEquals(WorkResult.State.BLOCKED,f.run(module,300).state());assertEquals(0,f.consumed);assertEquals(1,f.uses);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void advancedCycleCheckpointFailureRollsBackAndDoesNotReclick() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),6,0);f.dayTime=435L*24000+23900;f.holdUse=true;
        Pos target=f.machineStates.keySet().iterator().next();String key=f.profile.artisanJobs.get("job").scheduleKey(target);f.profile.nextEligibleDay.put(key,434L);
        ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);f.dayTime=436L*24000+5000;
        f.machineStates.put(target,f.state(target,true,false));f.failCheckpoint=true;f.outcomes.put(f.lastTicket,advancedOutcome());
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state());assertEquals(434L,f.profile.nextEligibleDay.get(key));assertEquals(1,f.uses);
    }
    @Test void resettingAnAdvancedCycleCannotLendItsProofToAFreshTicket() {
        Fixture f=new Fixture(ArtisanRecipe.ANCIENT_SEED,1);f.inventory[1]=item(f.recipe.inputId(),9,0);f.holdUse=true;
        ArtisanModule module=new ArtisanModule(f.recipe.feature());f.awaitUse(module);long old=f.lastTicket;
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));f.outcomes.put(old,advancedOutcome());
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());assertEquals(List.of(436L),List.copyOf(f.profile.nextEligibleDay.values()));
        module.reset();f.dayTime=437L*24000+5000;
        for(int i=0;i<100 && f.uses<2;i++){assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());f.now++;}
        assertEquals(2,f.uses);assertNotEquals(old,f.lastTicket);
        // Even compatible fresh consumption and maturity cannot borrow the old ticket's proof.
        f.machineStates.replaceAll((p,b)->f.state(p,true,false));
        f.outcomes.put(f.lastTicket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"fresh ordinary success without progression proof"));
        assertEquals(WorkResult.State.BLOCKED,f.run(module,300).state());assertEquals(2,f.uses);
        assertEquals(List.of(436L),List.copyOf(f.profile.nextEligibleDay.values()));
    }
    private static ActionOutcome advancedOutcome() {
        return new ActionOutcome(ActionOutcome.State.SUCCEEDED,"retained native working-to-mature and selected-slot proof",0,ActionOutcome.Proof.ARTISAN_CYCLE_ADVANCED);
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final ArtisanRecipe recipe;final Profile profile=new Profile();final ItemData[] inventory=new ItemData[36];
        final SessionState session=new SessionState();
        final Pos input=new Pos(1,64,0),output=new Pos(2,64,0);
        final Map<Pos,ItemData[]> chests=new LinkedHashMap<>();final Map<Pos,BlockData> machineStates=new LinkedHashMap<>();
        final List<Action> history=new ArrayList<>();final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final Map<Pos,Integer> opens=new HashMap<>();final List<Integer> usedGrades=new ArrayList<>(),handCounts=new ArrayList<>();
        final List<Delayed> delayed=new ArrayList<>();
        final List<String> statuses=new ArrayList<>();
        final List<Pos> navigationTargets=new ArrayList<>();
        final Map<Pos,String> uncertainTargets=new LinkedHashMap<>();final Set<Pos> failUncertainUses=new HashSet<>();
        long now=100,dayTime=435L*24000+5000,lastTicket;int selected=1,menuId,uses,consumed,withdrawals,pickupDelay,sleepUses;
        int seedConsumption=-1;
        boolean holdUse,suppressMutation,failCheckpoint,wrongOpen,uncertainArtisan,navigationBlocked,sleeping,grounded=true,upgraded,emitBonus,foreignRecipeReject,actionBusy;Pos opened;
        ItemData cursor=ItemData.EMPTY;String actionFence;
        boolean workspaceProofs,holdPark,holdRestore;
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
        Context context(){return new Context(this,this,new Navigation(){public Result moveTo(Pos p,double reach,Context c){navigationTargets.add(p);return navigationBlocked?Result.BLOCKED:Result.ARRIVED;}public boolean retryableFailure(){return navigationBlocked;}public Failure failureKind(){return navigationBlocked?Failure.NO_PATH:Failure.NONE;}public void reset(){}},profile,session,()->{if(failCheckpoint)throw new IllegalStateException("checkpoint failed");});}
        WorkResult run(ArtisanModule module,int limit){WorkResult r=WorkResult.busy("");for(int i=0;i<limit;i++){for(Iterator<Delayed> it=delayed.iterator();it.hasNext();){Delayed d=it.next();if(now>=d.at){put(inventory,d.item);it.remove();}}r=module.tick(context());statuses.add(r.message());now++;if(r.state()!=WorkResult.State.BUSY)return r;}return r;}
        void awaitUse(ArtisanModule module){for(int i=0;i<100 && uses==0;i++){assertEquals(WorkResult.State.BUSY,module.tick(context()).state());now++;}assertEquals(1,uses);}
        int stored(Pos p,String id){return Arrays.stream(chests.get(p)).filter(i->i.is(id)).mapToInt(ItemData::count).sum();}
        int storedGrade(Pos p,String id,int q){return Arrays.stream(chests.get(p)).filter(i->i.is(id)&&i.quality()==q).mapToInt(ItemData::count).sum();}
        long machineUses(Pos p){return history.stream().filter(a->a instanceof Action.UseBlock use && use.purpose()==Action.Use.ARTISAN && use.pos().equals(p)).count();}
        public long tick(){return now;}public long dayTime(){return dayTime;}
        public PlayerState player(){return new PlayerState(10.5,64,.5,0,0,grounded,sleeping,20,20,selected,true,true);}
        public BlockData block(Pos p){return machineStates.getOrDefault(p,new BlockData(p,chests.containsKey(p)?"minecraft:barrel":"minecraft:air",chests.containsKey(p)?Map.of("container","true"):Map.of()));}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public boolean canInteract(Pos p,double reach){return true;}public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){List<ItemSlot> slots=new ArrayList<>();for(int i=0;i<36;i++)slots.add(new ItemSlot(i,i,true,inventory[i]));return slots;}
        public MenuData menu(){List<ItemSlot> slots=new ArrayList<>();if(opened!=null)for(int i=0;i<27;i++)slots.add(new ItemSlot(i,-1,false,chests.get(opened)[i]));for(int i=0;i<36;i++)slots.add(new ItemSlot(opened==null?i:i<9?54+i:18+i,i,true,inventory[i]));return new MenuData(opened==null?0:menuId,0,slots,cursor,opened!=null);}
        public boolean mayPlace(int slot,ItemData item){return true;}public boolean busy(){return actionBusy;}
        public boolean ownsContainer(){return opened!=null;}
        public String artisanRejection(Pos target){return uncertainTargets.getOrDefault(target,uncertainArtisan ? "Unconfirmed target" : null);}
        public String pauseReason(){return actionFence;}
        public String loggingItemFingerprint(int index){return workspaceProofs?String.format("%064x",Integer.toUnsignedLong(inventory[index].hashCode())):null;}
        public boolean workHotbarParked(HotbarLease lease){return workspaceProofs && lease.original().equals(inventory[lease.sourceIndex()])
            && lease.fingerprint().equals(loggingItemFingerprint(lease.sourceIndex())) && !inventory[lease.hotbarSlot()].is(lease.original().id());}
        public boolean workHotbarRestored(HotbarLease lease){return workspaceProofs && lease.original().equals(inventory[lease.hotbarSlot()])
            && lease.fingerprint().equals(loggingItemFingerprint(lease.hotbarSlot())) && !inventory[lease.sourceIndex()].is(lease.original().id());}
        public long submit(Action action) {
            if(workspaceProofs)assertNull(SafetyPolicy.rejection(action,context()),"Working-slot test sent an unauthorized action: "+action);
            history.add(action);long id=++lastTicket;int count=0;
            if(action instanceof Action.UseBlock use) {
                if(use.purpose()==Action.Use.OPEN_CONTAINER){if(!wrongOpen){opened=use.pos();menuId++;opens.merge(opened,1,Integer::sum);}}
                else if(use.purpose()==Action.Use.SLEEP){sleepUses++;sleeping=true;}
                else if(use.purpose()==Action.Use.ARTISAN){
                    uses++;ItemData held=inventory[selected];handCounts.add(held.count());boolean feed=held.is(recipe.inputId())&&held.count()>=recipe.inputCount();
                    if(feed)usedGrades.add(held.quality());BlockData before=machineStates.get(use.pos());
                    if(failUncertainUses.contains(use.pos())){uncertainTargets.put(use.pos(),"Sent use has no confirmed response");outcomes.put(id,new ActionOutcome(ActionOutcome.State.FAILED,"unconfirmed target response"));return id;}
                    if(foreignRecipeReject){uncertainArtisan=true;outcomes.put(id,new ActionOutcome(ActionOutcome.State.FAILED,"unchanged raw idle block and selected input"));return id;}
                    if(!suppressMutation){if(feed){int cost=seedConsumption>=0 && !before.flag("mature") ? seedConsumption : recipe.inputCount();consumed+=cost;inventory[selected]=withCount(held,held.count()-cost);}
                        if(before.flag("mature")&&before.flag("upgraded")&&emitBonus)put(inventory,item(recipe.sameInputAndOutput()?"society:pristine_jade":recipe.outputId(),1,0));
                        if(before.flag("mature")){ItemData produced=item(recipe.outputId(),recipe.outputCount(),0);if(pickupDelay>0)delayed.add(new Delayed(now+pickupDelay,produced));else put(inventory,produced);}
                        machineStates.put(use.pos(),state(use.pos(),false,feed));}
                    if(holdUse){outcomes.put(id,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return id;}
                }
            } else if(action instanceof Action.CloseContainer){opened=null;}
            else if(action instanceof Action.SelectHotbar select){selected=select.slot();}
            else if(action instanceof Action.SwapHotbar swap){
                ItemData old=inventory[swap.hotbarSlot()];inventory[swap.hotbarSlot()]=inventory[swap.inventoryIndex()];inventory[swap.inventoryIndex()]=old;
                if(profile.workHotbarLease!=null && (holdPark && profile.workHotbarLease.stage()==HotbarLease.Stage.PREPARED
                    || holdRestore && profile.workHotbarLease.stage()==HotbarLease.Stage.RESTORING)) {
                    outcomes.put(id,new ActionOutcome(ActionOutcome.State.PENDING,"swap not yet acknowledged"));return id;
                }
            }
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
