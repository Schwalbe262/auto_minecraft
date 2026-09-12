package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WineProductionModuleTest {
    private static final Pos TOMATO=new Pos(1,64,0),A=new Pos(2,64,0),B=new Pos(3,64,0),SOURCE=new Pos(10,64,0),OUTPUT=new Pos(11,64,0);
    private static ItemData item(String id,int count,int grade) { return count==0 ? ItemData.EMPTY : new ItemData(id,count,grade,null,false,99); }
    @Test void actualWineOneShotFeedsEachLinesExactItemAndIndependentSchedule() {
        Fixture f=new Fixture();f.inventory[9]=item(ItemData.TOMATO,3,0);f.inventory[10]=item(WineProductionRules.ANCIENT_FRUIT,6,2);
        AutomationEngine engine=new AutomationEngine(List.of(new WineProductionModule()));engine.startOnce(f.context,Feature.WINE);
        f.run(engine,200);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(List.of(ItemData.TOMATO,WineProductionRules.ANCIENT_FRUIT,WineProductionRules.ANCIENT_FRUIT),f.fedItems);
        assertEquals(List.of(0,2,2),f.fedGrades);assertEquals(3,f.feedTargets.size());
        assertEquals(16,f.profile.wineBatchSchedule.nextDueDay());assertEquals(16,f.profile.wineProductionSchedules.get("ancient").nextDueDay());
        assertEquals(16L,f.profile.nextEligibleDay.get(WineBatchRules.key(TOMATO)));
        assertEquals(16L,f.profile.nextEligibleDay.get(WineBatchRules.key("ancient",A)));
        assertFalse(f.history.stream().anyMatch(a->a instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER));
    }
    @Test void customLineKeepsItsAllocatedCarriedGradeDespiteLargerNewPickups() {
        Fixture f=new Fixture();f.inventory[1]=item(WineProductionRules.ANCIENT_FRUIT,6,1);f.pickupOtherGradeAfterFirstFeed=true;
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule("ancient"),150).state());
        assertEquals(List.of(1,1),f.fedGrades);assertEquals(64,f.count(WineProductionRules.ANCIENT_FRUIT,0));
        assertFalse(f.history.stream().anyMatch(a->a instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER));
        assertNull(f.profile.wineBatchSchedule);assertTrue(f.profile.nextEligibleDay.keySet().stream().allMatch(k->k.startsWith("wine-line:ancient:")));
    }
    @Test void sourceSurveyCountsOnlyItsFruitAndKeepsMixedItemsAndTomatoesUntouched() {
        Fixture f=new Fixture();f.chest[0]=item(WineProductionRules.ANCIENT_FRUIT,6,3);
        f.chest[1]=item("minecraft:egg",64,0);f.chest[2]=item(ItemData.TOMATO,64,2);f.chest[3]=item(WineProductionRules.ANCIENT_FRUIT,3,1);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule("ancient"),200).state());
        assertEquals(List.of(3,3),f.fedGrades);assertEquals(64,f.chest[1].count());assertEquals(64,f.chest[2].count());assertEquals(3,f.chest[3].count());
        assertEquals(2,f.history.stream().filter(a->a instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER).count());
        assertEquals(1,f.history.stream().filter(Action.QuickMove.class::isInstance).count());assertFalse(f.menu().container());
    }
    @Test void missingCustomFruitCannotUseCarriedTomatoesOrServiceLegacyFacilities() {
        Fixture f=new Fixture();f.inventory[9]=item(ItemData.TOMATO,64,3);
        assertEquals(WorkResult.State.BLOCKED,f.run(new MachineModule("ancient"),100).state());
        assertTrue(f.fedItems.isEmpty());assertEquals(64,f.count(ItemData.TOMATO,3));assertNull(f.profile.wineBatchSchedule);
        assertTrue(f.profile.wineProductionSchedules.get("ancient").active());
    }
    @Test void ancientMatureOutputHasNoVineryYearRequirement() {
        Fixture f=new Fixture();f.inventory[1]=item(WineProductionRules.ANCIENT_FRUIT,6,1);
        f.blocks.put(A,f.keg(A,true,false));
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule("ancient"),150).state());
        assertEquals(1,f.count(WineProductionRules.ANCIENT_WINE,1));
        assertTrue(Arrays.stream(f.inventory).filter(i->i.is(WineProductionRules.ANCIENT_WINE)).allMatch(i->i.year()==null));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertFalse(f.profile.wineProductionSchedules.get("ancient").active());
    }
    @Test void ancientOneShotReturnsUnusedFruitBeforeStoringItsYearlessWineInTheSeparateOutputStore() {
        Fixture f=new Fixture();f.profile.tomatoWineEnabled=false;
        f.inventory[1]=item(WineProductionRules.ANCIENT_FRUIT,9,1);f.inventory[9]=item(ItemData.TOMATO,12,0);
        f.blocks.put(A,f.keg(A,true,false));
        AutomationEngine engine=new AutomationEngine(List.of(new WineProductionModule()));engine.startOnce(f.context,Feature.WINE);
        f.run(engine,250);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(0,f.count(WineProductionRules.ANCIENT_FRUIT,1));assertEquals(0,f.count(WineProductionRules.ANCIENT_WINE,1));
        assertEquals(3,Arrays.stream(f.chest).filter(i->i.is(WineProductionRules.ANCIENT_FRUIT)).mapToInt(ItemData::count).sum());
        assertEquals(1,Arrays.stream(f.outputChest).filter(i->i.is(WineProductionRules.ANCIENT_WINE)).mapToInt(ItemData::count).sum());
        assertEquals(List.of(SOURCE,OUTPUT),f.deposits);assertEquals(12,f.count(ItemData.TOMATO,0));assertFalse(f.open);
    }
    @Test void continuousDispatcherYieldsOneIdleBoundaryBeforeStartingAnotherLine() {
        Fixture f=new Fixture();f.session.oneShotFeature=null;
        Job tomato=new Job(WorkResult.idle()),ancient=new Job(WorkResult.idle());
        WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato")?tomato:ancient);
        assertEquals(WorkResult.State.IDLE,dispatcher.tick(f.context).state());assertEquals(1,tomato.calls);assertEquals(0,ancient.calls);
        assertEquals(WorkResult.State.IDLE,dispatcher.tick(f.context).state());assertEquals(1,ancient.calls);assertEquals(1,tomato.calls);
        assertEquals(WorkResult.State.IDLE,dispatcher.tick(f.context).state());assertEquals(1,ancient.calls);assertEquals(1,tomato.calls);
        assertTrue(f.history.isEmpty());assertEquals(0,f.navigationMoves);
    }
    @Test void actualEngineFutureOnlyLinesRemainPureIdleAndNeverStarveSleep() {
        Fixture f=new Fixture();f.profile.wineBatchSchedule=new WineBatchSchedule(16,false,List.of(),10L);
        f.profile.wineProductionSchedules.put("ancient",new WineBatchSchedule(16,false,List.of(),10L));
        WineBatchSchedule tomato=f.profile.wineBatchSchedule,ancient=f.profile.wineProductionSchedules.get("ancient");
        int[] sleepCalls={0};
        AutomationModule sleep=new AutomationModule() {
            public Feature feature(){return Feature.SLEEP;}public int priority(){return 100;}
            public WorkResult tick(Context c){sleepCalls[0]++;return WorkResult.idle();}public void reset(){}
        };
        AutomationEngine engine=new AutomationEngine(List.of(new WineProductionModule(),sleep));engine.start(f.context);
        f.run(engine,45);
        assertTrue(engine.running(),engine.status());assertTrue(sleepCalls[0]>=3,"Empty wine sweeps must reach the lower-priority sleep module");
        assertTrue(f.history.isEmpty(),"A future production date must not open stores or dispatch actions");
        assertEquals(0,f.navigationMoves);assertEquals(0,f.blockReads);
        assertSame(tomato,f.profile.wineBatchSchedule);assertSame(ancient,f.profile.wineProductionSchedules.get("ancient"));
    }
    @Test void noDueCustomLineWithoutInventoryDoesNotInventCleanupBusyPhases() {
        Fixture f=new Fixture();f.profile.tomatoWineEnabled=false;
        f.profile.wineProductionSchedules.put("ancient",new WineBatchSchedule(16,false,List.of(),10L));
        WineProductionModule dispatcher=new WineProductionModule();
        assertEquals(WorkResult.State.IDLE,dispatcher.tick(f.context).state());
        assertTrue(f.history.isEmpty());assertEquals(0,f.navigationMoves);assertEquals(0,f.blockReads);
    }
    @Test void continuousWineStorageOffLeavesCustomOutputUntouchedButOneShotCanCompleteItsOwnCleanup() {
        Fixture f=new Fixture();f.session.oneShotFeature=null;f.profile.tomatoWineEnabled=false;
        f.profile.enabled.put(Feature.WINE_STORAGE,false);f.inventory[9]=item(WineProductionRules.ANCIENT_WINE,1,2);
        f.profile.wineProductionSchedules.put("ancient",new WineBatchSchedule(16,false,List.of(),10L));
        assertEquals(WorkResult.State.IDLE,new WineProductionModule().tick(f.context).state());
        assertEquals(1,f.count(WineProductionRules.ANCIENT_WINE,2));assertTrue(f.history.isEmpty());
        AutomationEngine engine=new AutomationEngine(List.of(new WineProductionModule()));engine.startOnce(f.context,Feature.WINE);
        f.run(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());assertEquals(0,f.count(WineProductionRules.ANCIENT_WINE,2));
        assertEquals(List.of(OUTPUT),f.deposits);assertFalse(f.profile.enabled(Feature.WINE_STORAGE));
    }
    @Test void blockedOrCoolingLineDoesNotStarveAnotherLineAndOneShotVisitsBothBeforePausing() {
        for(WorkResult first:List.of(WorkResult.blocked("missing fruit"),WorkResult.cooldown("normal wait"))) {
            Fixture f=new Fixture();Job tomato=new Job(first),ancient=new Job(WorkResult.idle());
            WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato") ? tomato : ancient);
            WorkResult result=f.run(dispatcher,20);
            assertEquals(first.state(),result.state());assertEquals(1,tomato.calls);assertEquals(1,ancient.calls);
            f.ticks+=20;f.run(dispatcher,20);assertEquals(1,tomato.calls,"Per-line delay survives another scheduler sweep");assertEquals(2,ancient.calls);
        }
        Fixture f=new Fixture();Job tomato=new Job(WorkResult.blocked("missing")),ancient=new Job(WorkResult.idle());
        AutomationEngine engine=new AutomationEngine(List.of(new WineProductionModule(id->id.equals("tomato") ? tomato : ancient)));
        engine.startOnce(f.context,Feature.WINE);f.run(engine,30);
        assertEquals(1,ancient.calls);assertEquals(AutomationEngine.State.PAUSED,engine.state());
    }
    @Test void busyOwnerAndUnresolvedNativeBoundaryNeverStartAnotherLine() {
        Fixture f=new Fixture();Job tomato=new Job(WorkResult.busy("pending")),ancient=new Job(WorkResult.idle());
        WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato") ? tomato : ancient);
        for(int i=0;i<300;i++){assertEquals(WorkResult.State.BUSY,dispatcher.tick(f.context).state());f.ticks++;}
        assertEquals(0,ancient.calls);
        tomato.result=WorkResult.blocked("uncertain");f.fence="unconfirmed native swap";
        assertEquals(WorkResult.State.BLOCKED,dispatcher.tick(f.context).state());assertEquals(0,ancient.calls);
        f.fence=null;f.run(dispatcher,20);assertEquals(1,ancient.calls);
    }
    @Test void sourceClosureIsAcknowledgedBeforeTheDispatcherReleasesALine() {
        Fixture f=new Fixture();Job tomato=new Job(WorkResult.blocked("source empty")),ancient=new Job(WorkResult.idle());
        tomato.hook=()->f.open=true;
        WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato") ? tomato : ancient);
        dispatcher.tick(f.context);dispatcher.tick(f.context);
        assertInstanceOf(Action.CloseContainer.class,f.pending);assertEquals(0,ancient.calls);
        for(int i=0;i<100;i++){dispatcher.tick(f.context);f.ticks++;}assertEquals(0,ancient.calls);assertEquals(1,f.history.size());
        f.advance();f.run(dispatcher,20);assertEquals(1,ancient.calls);assertFalse(f.open);
    }
    @Test void disablingLegacyTomatoLeavesTheIndependentAncientLineEnabled() {
        Fixture f=new Fixture();f.profile.tomatoWineEnabled=false;Job tomato=new Job(WorkResult.idle()),ancient=new Job(WorkResult.idle());
        f.run(new WineProductionModule(id->id.equals("tomato") ? tomato : ancient),20);
        assertEquals(0,tomato.calls);assertEquals(1,ancient.calls);
    }
    @Test void dispatcherNeverClosesAnUnownedManualMenuToStartAnotherLine() {
        Fixture f=new Fixture();f.containerOwned=false;Job tomato=new Job(WorkResult.idle()),ancient=new Job(WorkResult.idle());
        tomato.hook=()->f.open=true;
        WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato") ? tomato : ancient);
        assertEquals(WorkResult.State.BLOCKED,dispatcher.tick(f.context).state());
        assertTrue(f.history.isEmpty());assertEquals(0,ancient.calls);assertTrue(f.open);
    }
    @Test void actualClosedSurveyEmitsTypedShortageWithoutChangingRemainingMachines() {
        Fixture f=continuousShortageFixture();MachineModule machine=new MachineModule("ancient");
        assertEquals(WorkResult.State.BLOCKED,f.run(machine,100).state());
        assertEquals(new MachineModule.InputShortage(WineProductionRules.ANCIENT_FRUIT,3,2),machine.inputShortage());
        assertEquals(1,sourceOpens(f));assertFalse(f.open);assertNull(f.pending);
        WineBatchSchedule schedule=f.profile.wineProductionSchedules.get("ancient");
        assertTrue(schedule.active());assertEquals(List.of(A,B),schedule.remaining());assertNull(schedule.latestFeedDay());
        assertTrue(schedule.skipped().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(f.fedItems.isEmpty());
        machine.reset();assertNull(machine.inputShortage());
        f.profile.wineProductionLines.clear();
        assertEquals(WorkResult.State.BLOCKED,machine.tick(f.context).state());assertNull(machine.inputShortage());
    }
    @Test void confirmedShortageWaitsForTheWholeSameDayWithoutAnotherVisitOrFailure() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();
        assertEquals(WorkResult.State.COOLDOWN,untilCooldown(f,dispatcher).state());
        WineBatchSchedule before=f.profile.wineProductionSchedules.get("ancient");
        int actions=f.history.size(),moves=f.navigationMoves,reads=f.blockReads;
        f.inventory[9]=item(ItemData.TOMATO,64,0);
        f.inventory[10]=item(WineProductionRules.ANCIENT_FRUIT,1,0);
        f.inventory[11]=item(WineProductionRules.ANCIENT_FRUIT,1,1);
        f.inventory[12]=item(WineProductionRules.ANCIENT_FRUIT,1,2);
        f.inventory[13]=item(WineProductionRules.ANCIENT_FRUIT,64,4);
        for(int i=0;i<5000;i++) {
            assertEquals(WorkResult.State.COOLDOWN,dispatcher.tick(f.context).state());f.advance();
        }
        assertTrue(dispatcher.sleepSafeDeferred(f.context));assertEquals(actions,f.history.size());
        assertEquals(moves,f.navigationMoves);assertEquals(reads,f.blockReads);assertSame(before,f.profile.wineProductionSchedules.get("ancient"));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertNull(f.profile.loggingHotbarLease);assertNull(f.profile.workHotbarLease);
    }
    @Test void continuousEngineKeepsOtherWineAndSleepRunningWithoutShortageFailureHistory() {
        Fixture f=new Fixture();f.session.oneShotFeature=null;f.inventory[1]=item(ItemData.TOMATO,3,0);
        int[] sleepCalls={0};
        AutomationModule sleep=new AutomationModule() {
            public Feature feature(){return Feature.SLEEP;}public int priority(){return 100;}
            public WorkResult tick(Context c){sleepCalls[0]++;return WorkResult.idle();}public void reset(){}
        };
        AutomationEngine engine=new AutomationEngine(List.of(new WineProductionModule(),sleep));engine.start(f.context);
        f.run(engine,5000);
        assertTrue(engine.running(),engine.status());assertTrue(sleepCalls[0]>10);
        assertEquals(List.of(ItemData.TOMATO),f.fedItems);assertEquals(1,sourceOpens(f));
        assertTrue(engine.failureHistory().isEmpty(),engine.failureHistory().toString());
        assertEquals(List.of(A,B),f.profile.wineProductionSchedules.get("ancient").remaining());
    }
    @Test void newlyCarriedOneGradeBatchWakesOnlyItsShortageAndFeedsNormally() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();untilCooldown(f,dispatcher);
        f.inventory[1]=item(WineProductionRules.ANCIENT_FRUIT,6,2);
        for(int i=0;i<150;i++) {dispatcher.tick(f.context);f.advance();}
        assertEquals(List.of(2,2),f.fedGrades);assertEquals(1,sourceOpens(f),"Use carried fruit before any source recount");
        assertFalse(f.profile.wineProductionSchedules.get("ancient").active());
    }
    @Test void dayChangesInEitherDirectionWakeTheShortageButNotEvery1200Ticks() {
        for(int dayChange:List.of(-1,1)) {
            Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();untilCooldown(f,dispatcher);
            f.ticks+=2400;assertEquals(WorkResult.State.COOLDOWN,dispatcher.tick(f.context).state());assertEquals(1,sourceOpens(f));
            f.dayTime+=dayChange*24000L;
            untilCooldown(f,dispatcher);assertEquals(2,sourceOpens(f));
            assertEquals(List.of(A,B),f.profile.wineProductionSchedules.get("ancient").remaining());
            assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(f.fedItems.isEmpty());
        }
    }
    @Test void resolvedSourceAndLineChangesWakeButEqualConfigurationDoesNot() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();untilCooldown(f,dispatcher);
        CommodityStore source=f.profile.commodityStores.get("fruit");
        f.profile.commodityStores.put("fruit",new CommodityStore(source.id(),source.name(),source.items(),source.containers()));
        assertEquals(WorkResult.State.COOLDOWN,dispatcher.tick(f.context).state());assertEquals(1,sourceOpens(f));
        f.profile.commodityStores.put("fruit",new CommodityStore(source.id(),"Renamed source",source.items(),source.containers()));
        untilCooldown(f,dispatcher);assertEquals(2,sourceOpens(f));
        WineProductionLine line=f.profile.wineProductionLines.get("ancient");
        f.profile.wineProductionLines.put("ancient",new WineProductionLine(line.id(),"Renamed line",line.inputItemId(),line.outputItemId(),
            line.inputStoreId(),line.outputStoreId(),line.machines(),line.cycleDays(),line.enabled()));
        untilCooldown(f,dispatcher);assertEquals(3,sourceOpens(f));
    }
    @Test void explicitResetRetriesTheSameDayAndOneShotStillPausesForRealShortage() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();untilCooldown(f,dispatcher);
        dispatcher.reset();untilCooldown(f,dispatcher);assertEquals(2,sourceOpens(f));
        AutomationEngine engine=new AutomationEngine(List.of(dispatcher));engine.startOnce(f.context,Feature.WINE);f.run(engine,150);
        assertEquals(AutomationEngine.State.PAUSED,engine.state());assertTrue(engine.status().contains("Not enough"),engine.status());
        assertEquals(3,sourceOpens(f));assertEquals(List.of(A,B),f.profile.wineProductionSchedules.get("ancient").remaining());
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(f.fedItems.isEmpty());
    }
    @Test void genericFailureWithShortageWordsKeepsItsOrdinaryBackoffAndSleepFence() {
        Fixture f=continuousShortageFixture();Job job=new Job(WorkResult.blocked("Not enough society:ancient_fruit: add ingredients and retry"));
        WineProductionModule dispatcher=new WineProductionModule(id->job);
        assertEquals(WorkResult.State.IDLE,dispatcher.tick(f.context).state());
        assertEquals(WorkResult.State.BLOCKED,dispatcher.tick(f.context).state());assertFalse(dispatcher.sleepSafeDeferred(f.context));
        f.dayTime+=24000;f.inventory[1]=item(WineProductionRules.ANCIENT_FRUIT,6,0);
        assertEquals(WorkResult.State.BLOCKED,dispatcher.tick(f.context).state());assertEquals(1,job.calls);
        f.ticks+=1200;dispatcher.tick(f.context);assertEquals(2,job.calls);
    }
    @Test void shortageHoldDoesNotMakeNewCustodyOrNativeUncertaintySleepSafe() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();untilCooldown(f,dispatcher);
        f.fence="unconfirmed native action";assertFalse(dispatcher.sleepSafeDeferred(f.context));f.fence=null;
        HotbarLease lease=new HotbarLease(Feature.CRYSTAL_COPY,9,1,item("minecraft:stone",1,0),"a".repeat(64));
        f.profile.workHotbarLease=lease;assertFalse(dispatcher.sleepSafeDeferred(f.context));
        f.inventory[1]=item(WineProductionRules.ANCIENT_FRUIT,6,0);
        int actions=f.history.size();assertEquals(WorkResult.State.BLOCKED,dispatcher.tick(f.context).state());
        assertSame(lease,f.profile.workHotbarLease);assertEquals(actions,f.history.size());assertTrue(f.fedItems.isEmpty());
    }
    @Test void changedRecipeAuthorityCannotHideBehindAnExistingShortageHold() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();untilCooldown(f,dispatcher);
        f.profile.commodityStores.put("overlap",new CommodityStore("overlap","Conflicting storage",Set.of("minecraft:stone"),List.of(A)));
        WorkResult result=dispatcher.tick(f.context);
        if(result.state()==WorkResult.State.IDLE)result=dispatcher.tick(f.context);
        assertEquals(WorkResult.State.BLOCKED,result.state());assertFalse(dispatcher.sleepSafeDeferred(f.context));
        assertEquals(1,sourceOpens(f));assertTrue(f.fedItems.isEmpty());
    }
    @Test void missingSourcesAndUnknownGradesNeverEmitConfirmedShortage() {
        Fixture noSource=new Fixture();noSource.session.oneShotFeature=null;MachineModule tomato=new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.BLOCKED,noSource.run(tomato,100).state());assertNull(tomato.inputShortage());
        Fixture invalid=continuousShortageFixture();invalid.chest[0]=item(WineProductionRules.ANCIENT_FRUIT,3,9);
        MachineModule ancient=new MachineModule("ancient");
        assertEquals(WorkResult.State.BLOCKED,invalid.run(ancient,100).state());assertNull(ancient.inputShortage());
    }
    @Test void surveyCrossingMidnightMustBeRepeatedBeforePublishingTheNewDaysWait() {
        Fixture f=continuousShortageFixture();WineProductionModule dispatcher=new WineProductionModule();
        for(int i=0;i<100 && !(f.pending instanceof Action.CloseContainer);i++) {
            dispatcher.tick(f.context);
            if(!(f.pending instanceof Action.CloseContainer))f.advance();
        }
        assertInstanceOf(Action.CloseContainer.class,f.pending);assertEquals(1,sourceOpens(f));
        f.dayTime+=24000;f.advance();
        untilCooldown(f,dispatcher);assertEquals(2,sourceOpens(f),"The prior day's empty source cannot become today's evidence");
        int actions=f.history.size();f.ticks+=5000;
        assertEquals(WorkResult.State.COOLDOWN,dispatcher.tick(f.context).state());assertEquals(actions,f.history.size());
        assertEquals(List.of(A,B),f.profile.wineProductionSchedules.get("ancient").remaining());
    }
    @Test void nativeSourceFailureCannotBecomeATypedOrSleepSafeShortage() {
        Fixture f=continuousShortageFixture();MachineModule machine=new MachineModule("ancient");
        for(int i=0;i<100 && f.pending==null;i++){machine.tick(f.context);if(f.pending==null)f.advance();}
        assertInstanceOf(Action.UseBlock.class,f.pending);
        f.pending=null;f.outcome=new ActionOutcome(ActionOutcome.State.CANCELLED,"source response missing");
        assertEquals(WorkResult.State.BLOCKED,machine.tick(f.context).state());assertNull(machine.inputShortage());
        assertTrue(f.fedItems.isEmpty());assertEquals(List.of(A,B),f.profile.wineProductionSchedules.get("ancient").remaining());
    }
    @Test void anotherLinesDeferredEngineRetryRetainsOnlyTheVerifiedShortageWait() {
        Fixture f=new Fixture();f.session.oneShotFeature=null;
        Job tomato=new Job(WorkResult.deferred("temporary navigation obstruction"));
        WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato") ? tomato : new MachineModule(id));
        AutomationEngine engine=new AutomationEngine(List.of(dispatcher));engine.start(f.context);f.run(engine,5000);
        assertTrue(engine.running(),engine.status());assertTrue(tomato.calls>=3,"The ordinary deferred line must retry normally");
        assertEquals(1,sourceOpens(f),"Other-line retries must not discard the same-day empty-stock observation");
        assertTrue(engine.failureHistory().stream().allMatch(entry->entry.state()==EngineFailureHistory.Kind.DEFERRED
            && entry.message().contains("navigation obstruction")),engine.failureHistory().toString());
        assertEquals(List.of(A,B),f.profile.wineProductionSchedules.get("ancient").remaining());assertTrue(f.fedItems.isEmpty());
        engine.stop(f.context,AutomationEngine.State.PAUSED,"F8");engine.start(f.context);f.run(engine,150);
        assertEquals(2,sourceOpens(f),"An explicit stop/start intentionally permits another source survey");
    }
    @Test void retryResetDoesNotRetainOrdinaryPerLineBackoffOrAnActiveOwner() {
        Fixture f=new Fixture();f.session.oneShotFeature=null;Job tomato=new Job(WorkResult.deferred("navigation wait"));
        WineProductionModule dispatcher=new WineProductionModule(id->id.equals("tomato") ? tomato : new MachineModule(id));
        WorkResult result=null;
        for(int i=0;i<150;i++) {
            result=dispatcher.tick(f.context);f.advance();if(result.state()==WorkResult.State.DEFERRED)break;
        }
        assertNotNull(result);assertEquals(WorkResult.State.DEFERRED,result.state());assertEquals(1,sourceOpens(f));
        assertEquals(1,tomato.calls);dispatcher.resetForRetry();
        assertEquals(WorkResult.State.IDLE,dispatcher.tick(f.context).state());assertEquals(2,tomato.calls);
        assertEquals(WorkResult.State.DEFERRED,dispatcher.tick(f.context).state());assertEquals(1,sourceOpens(f));
    }
    private static Fixture continuousShortageFixture() {
        Fixture f=new Fixture();f.session.oneShotFeature=null;f.profile.tomatoWineEnabled=false;return f;
    }
    private static long sourceOpens(Fixture f) {
        return f.history.stream().filter(a->a instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER && use.pos().equals(SOURCE)).count();
    }
    private static WorkResult untilCooldown(Fixture f,WineProductionModule dispatcher) {
        for(int i=0;i<300;i++) {
            WorkResult result=dispatcher.tick(f.context);f.advance();
            if(result.state()==WorkResult.State.COOLDOWN)return result;
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
        }
        throw new AssertionError("Shortage did not reach a clean cooldown");
    }
    private static final class Job implements AutomationModule {
        WorkResult result;int calls;Runnable hook=()->{};Job(WorkResult result){this.result=result;}
        public Feature feature(){return Feature.WINE;}public int priority(){return 60;}
        public WorkResult tick(Context c){calls++;hook.run();return result;}public void reset(){}
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final ItemData[] inventory=new ItemData[36],chest=new ItemData[27],outputChest=new ItemData[27];final Map<Pos,BlockData> blocks=new HashMap<>();
        final List<Action> history=new ArrayList<>();final List<String> fedItems=new ArrayList<>();final List<Integer> fedGrades=new ArrayList<>();final List<Pos> feedTargets=new ArrayList<>();
        final Context context=new Context(this,this,this,profile,session);long ticks,sequence,dayTime=10*24000+1000;int selected,navigationMoves,blockReads;boolean open,pickupOtherGradeAfterFirstFeed,containerOwned=true;String fence;
        Pos opened;final List<Pos> deposits=new ArrayList<>();
        Action pending;ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture(){
            Arrays.fill(inventory,ItemData.EMPTY);Arrays.fill(chest,ItemData.EMPTY);Arrays.fill(outputChest,ItemData.EMPTY);inventory[0]=new ItemData("minecraft:diamond_hoe",1,0,null,true,99);
            session.oneShotFeature=Feature.WINE;
            profile.pois.add(new Poi(TOMATO,PoiKind.WINE_KEG,"Tomato",null));
            profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit",Set.of(WineProductionRules.ANCIENT_FRUIT),List.of(SOURCE)));
            profile.commodityStores.put("wine",new CommodityStore("wine","Wine",Set.of(WineProductionRules.ANCIENT_WINE),List.of(OUTPUT)));
            profile.wineProductionLines.put("ancient",new WineProductionLine("ancient","Ancient wine",WineProductionRules.ANCIENT_FRUIT,
                WineProductionRules.ANCIENT_WINE,"fruit","wine",List.of(A,B),6,true));
            for(Pos pos:List.of(TOMATO,A,B))blocks.put(pos,keg(pos,false,false));
        }
        BlockData keg(Pos pos,boolean mature,boolean working){return new BlockData(pos,"society:wine_keg",Map.of("mature",""+mature,"working",""+working,"facing","north"));}
        WorkResult run(AutomationModule module,int limit){
            WorkResult result=null;for(int i=0;i<limit;i++){result=module.tick(context);if(result.state()!=WorkResult.State.BUSY)return result;advance();}
            fail("Module did not settle: "+result);return result;
        }
        void run(AutomationEngine engine,int limit){for(int i=0;i<limit && engine.running();i++){engine.tick(context);advance();}}
        int count(String id,int grade){return Arrays.stream(inventory).filter(i->i.is(id)&&i.quality()==grade).mapToInt(ItemData::count).sum();}
        void add(ItemData value){
            add(inventory,value);
        }
        void add(ItemData[] slots,ItemData value){
            for(int i=0;i<slots.length;i++)if(slots[i].is(value.id()) && slots[i].quality()==value.quality() && slots[i].count()+value.count()<=64){slots[i]=item(value.id(),slots[i].count()+value.count(),value.quality());return;}
            for(int i=0;i<slots.length;i++)if(slots[i].empty()){slots[i]=value;return;}fail("fixture inventory full");
        }
        ItemData[] storage(){return OUTPUT.equals(opened)?outputChest:chest;}
        void advance(){
            ticks++;if(pending==null)return;Action action=pending;pending=null;outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"server");
            if(action instanceof Action.SelectHotbar select)selected=select.slot();
            else if(action instanceof Action.SwapHotbar swap){ItemData before=inventory[swap.hotbarSlot()];inventory[swap.hotbarSlot()]=inventory[swap.inventoryIndex()];inventory[swap.inventoryIndex()]=before;}
            else if(action instanceof Action.CloseContainer){open=false;opened=null;}
            else if(action instanceof Action.QuickMove move){
                assertTrue(open);
                if(move.slot()<27){ItemData before=storage()[move.slot()];add(before);storage()[move.slot()]=ItemData.EMPTY;}
                else {ItemData before=inventory[move.slot()-27];add(storage(),before);inventory[move.slot()-27]=ItemData.EMPTY;deposits.add(opened);outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"stored",before.count());}
            }
            else if(action instanceof Action.UseBlock use){
                if(use.purpose()==Action.Use.OPEN_CONTAINER){assertTrue(List.of(SOURCE,OUTPUT).contains(use.pos()));open=true;opened=use.pos();}
                else {
                    assertEquals(Action.Use.MACHINE,use.purpose());WineProductionLine line=WineProductionRules.at(profile,use.pos());
                    ItemData before=inventory[selected];assertEquals(line.inputItemId(),before.id());assertTrue(before.count()>=3);
                    inventory[selected]=item(before.id(),before.count()-3,before.quality());
                    if(blocks.get(use.pos()).flag("mature"))add(item(line.outputItemId(),1,before.quality()));
                    blocks.put(use.pos(),keg(use.pos(),false,true));fedItems.add(before.id());fedGrades.add(before.quality());feedTargets.add(use.pos());
                    if(pickupOtherGradeAfterFirstFeed && fedItems.size()==1)add(item(WineProductionRules.ANCIENT_FRUIT,64,0));
                    outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"exact feed",3,ActionOutcome.Proof.WINE_FULL_FEED);
                }
            } else throw new AssertionError("Unexpected fixture action "+action);
        }
        public long tick(){return ticks;}public long dayTime(){return dayTime;}
        public Integer wineYear(){return 10;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,selected,true,true);}
        public BlockData block(Pos p){blockReads++;return blocks.getOrDefault(p,new BlockData(p,"minecraft:chest",Map.of("container","true")));}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos p,int a,int b){return List.of();}
        public List<ItemSlot> inventory(){List<ItemSlot> result=new ArrayList<>();for(int i=0;i<36;i++)result.add(new ItemSlot(i,i,true,inventory[i]));return result;}
        public MenuData menu(){List<ItemSlot> result=new ArrayList<>();if(open)for(int i=0;i<27;i++)result.add(new ItemSlot(i,-1,false,storage()[i]));for(ItemSlot slot:inventory())result.add(new ItemSlot((open?27:0)+slot.index(),slot.inventoryIndex(),true,slot.item()));return new MenuData(open?1:0,0,result,ItemData.EMPTY,open);}
        public boolean mayPlace(int slot,ItemData value){return open&&slot<27;}
        public boolean busy(){return pending!=null;}public String pauseReason(){return fence;}
        public boolean ownsContainer(){return open && containerOwned;}
        public long submit(Action action){assertNull(pending);pending=action;history.add(action);outcome=new ActionOutcome(ActionOutcome.State.PENDING,"");return ++sequence;}
        public ActionOutcome outcome(long ticket){return outcome;}public void move(Movement movement){}public void stopMovement(){}
        public void cancel(){pending=null;outcome=new ActionOutcome(ActionOutcome.State.CANCELLED,"");}
        public Result moveTo(Pos p,double reach,Context c){navigationMoves++;return Result.ARRIVED;}public void reset(){}
    }
}
