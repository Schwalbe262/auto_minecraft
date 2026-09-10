package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarfruitModuleTest {
    private static final Pos FRUIT=new Pos(3,1,0),STORE=new Pos(20,0,0);

    @Test void nearbyQueriesNeverSelectMoveSubmitOrConsumeDailyEligibility() {
        Fixture f=new Fixture();
        for(int i=0;i<10;i++)assertFalse(f.module.hasNearbyWork(f.context));
        assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());assertTrue(f.blockReads.isEmpty());
        assertEquals(0,f.stops);assertEquals(0,f.resets);
        f.run();assertEquals(1,f.stored);assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
        f.fruit(FRUIT,7);f.module.reset();f.run();assertEquals(1,f.clicked().size());
        f.day+=24000;assertFalse(f.module.hasNearbyWork(f.context));f.run();
        assertEquals(2,f.clicked().size());assertEquals(2,f.stored);
    }

    @Test void independentRoutinePicksAllSevenRegisteredFruitFromADistantStartingPosition() {
        Fixture f=new Fixture();List<Pos> cohort=List.of(FRUIT,new Pos(4,1,0),new Pos(5,1,0),new Pos(6,1,0),
            new Pos(7,1,0),new Pos(8,1,0),new Pos(9,1,0));
        f.patch(cohort.toArray(Pos[]::new));cohort.forEach(p->f.fruit(p,7));f.x=200;f.followArrivals=true;
        f.run();assertEquals(7,f.stored);assertEquals(Set.copyOf(cohort),Set.copyOf(f.clicked()));
        assertEquals(List.of(7),f.fruitUsesAtStoreOpen);assertEquals(1,f.storeOpens());
        assertEquals(1,f.submitted.stream().filter(Action.QuickMove.class::isInstance).count());
        assertEquals(1,f.submitted.stream().filter(Action.CloseContainer.class::isInstance).count());
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
        cohort.forEach(p->f.fruit(p,7));f.module.reset();f.run();assertEquals(7,f.clicked().size());
    }

    @Test void activeWinePreservesSleepAndHarvestNeverYieldTravelToOrchardWork() {
        for(Feature feature:List.of(Feature.WINE,Feature.PRESERVES,Feature.SLEEP,Feature.HARVEST)) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(feature);
            AutomationEngine engine=f.engine(original);
            for(int i=0;i<50;i++)f.engineTick(engine);
            assertEquals(50,original.ticks,feature.toString());assertTrue(f.submitted.isEmpty());
            assertTrue(f.travel.stream().allMatch(TravelJob.DESTINATION::equals));assertTrue(engine.running());
        }
    }

    @Test void anotherFeaturesOneShotNeverRunsTheOrchardEvenWhenTravelCanYield() {
        Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);
        AutomationEngine engine=f.engine(original);engine.startOnce(f.context,Feature.WINE);
        for(int i=0;i<30;i++)f.engineTick(engine);
        assertEquals(30,original.ticks);assertTrue(f.submitted.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void orchardRunsAtItsExistingPriorityAfterAHigherPriorityJobYields() {
        Fixture f=new Fixture();TravelJob original=new TravelJob(Feature.WINE);AutomationEngine engine=f.engine(original);
        for(int i=0;i<5;i++)f.engineTick(engine);
        assertTrue(f.clicked().isEmpty());assertEquals(80,f.module.priority());
        original.done=true;
        for(int i=0;i<100&&f.stored==0;i++)f.engineTick(engine);
        assertEquals(1,f.stored);assertEquals(List.of(FRUIT),f.clicked());
    }

    @Test void newlyReadyHigherPriorityHarvestWaitsUntilAllOrchardPatchesAreStored() {
        Fixture f=new Fixture();Pos other=new Pos(60,1,0);
        f.profile.fruitPatches=List.of(new FruitPatch("first","fruit",List.of(FRUIT)),new FruitPatch("second","fruit",List.of(other)));
        f.fruit(other,7);f.followArrivals=true;TravelJob harvest=new TravelJob(Feature.HARVEST);harvest.done=true;
        AutomationEngine engine=f.engine(harvest);f.engineTick(engine);int checks=harvest.ticks;harvest.done=false;
        for(int i=0;i<150&&harvest.ticks==checks;i++)f.engineTick(engine);
        assertEquals(checks+1,harvest.ticks);assertEquals(List.of(FRUIT,other),f.clicked());assertEquals(2,f.stored);
        assertEquals(List.of(1,2),f.fruitUsesAtStoreOpen);
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:first"));assertEquals(1L,f.profile.nextEligibleDay.get("orchard:second"));
    }

    @Test void oneShotVisitsEveryRegisteredPatchAndStoresEachBeforeStartingTheNext() {
        Fixture f=new Fixture();Pos second=new Pos(120,1,0),secondStore=new Pos(150,0,0),unripe=new Pos(170,1,0);
        CommodityStore secondDestination=new CommodityStore("second_store","Second",Set.of(FruitRules.ITEM),List.of(secondStore));
        assertTrue(secondDestination.valid());f.profile.commodityStores.put(secondDestination.id(),secondDestination);
        f.blocks.put(secondStore,new BlockData(secondStore,"minecraft:chest",Map.of("container","true")));
        f.profile.fruitPatches=List.of(new FruitPatch("first","fruit",List.of(FRUIT)),
            new FruitPatch("second",secondDestination.id(),List.of(second)),new FruitPatch("unripe","fruit",List.of(unripe)));
        f.fruit(second,7);f.fruit(unripe,6);f.x=200;f.followArrivals=true;
        f.profile.enabled.put(Feature.STARFRUIT,false);
        AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.startOnce(f.context,Feature.STARFRUIT);
        for(int i=0;i<200&&engine.running();i++)f.engineTick(engine);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertFalse(f.profile.enabled(Feature.STARFRUIT));
        assertEquals(Set.of(FRUIT,second),Set.copyOf(f.clicked()));assertEquals(2,f.stored);
        assertEquals(List.of(1,2),f.fruitUsesAtStoreOpen);assertEquals(2,f.storeOpens());
        assertEquals(3,f.profile.nextEligibleDay.size());
        for(String id:List.of("first","second","unripe"))assertEquals(1L,f.profile.nextEligibleDay.get("orchard:"+id));
        assertEquals(6,f.blocks.get(unripe).number("age",-1));assertFalse(f.open);assertTrue(f.cursor.empty());
    }

    @Test void cleanInspectionOfUnripeAndMissingRegisteredFruitRunsOnlyOncePerGameDay() {
        Fixture f=new Fixture();Pos missing=new Pos(70,1,0);f.patch(FRUIT,missing);f.fruit(FRUIT,6);
        f.run();assertTrue(f.clicked().isEmpty());assertTrue(f.travel.isEmpty());
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));int reads=f.blockReads.size();
        f.module.reset();f.fruit(FRUIT,7);f.fruit(missing,7);f.run();
        assertEquals(reads,f.blockReads.size());assertTrue(f.clicked().isEmpty());
        f.day+=24000;f.run();assertEquals(Set.of(FRUIT,missing),Set.copyOf(f.clicked()));assertEquals(2,f.stored);
        assertEquals(2L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void persistedDailyEligibilityIsRespectedByANewModuleInstance() {
        Fixture f=new Fixture();f.profile.nextEligibleDay.put("orchard:nearby",1L);
        StarfruitModule replacement=new StarfruitModule();
        assertEquals(WorkResult.State.IDLE,replacement.tick(f.context).state());
        assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());assertTrue(f.blockReads.isEmpty());
        f.day+=24000;assertEquals(WorkResult.State.BUSY,replacement.tick(f.context).state());
        assertTrue(f.travel.contains(FRUIT));
    }

    @Test void disabledLoggingSuspensionAllowsOrdinaryOrchardSchedulingWithoutChangingItsQueue() {
        Fixture f=new Fixture();f.profile.enabled.put(Feature.LOGGING,false);f.profile.loggingRunActive=true;
        Pos plot=new Pos(50,0,0);f.profile.loggingRemainingPlots.add(plot);f.profile.loggingReplantingPlots.add(plot);
        AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.start(f.context);
        for(int i=0;i<100&&f.stored==0;i++)f.engineTick(engine);
        assertEquals(1,f.stored);assertTrue(f.profile.loggingRunActive);assertEquals(List.of(plot),f.profile.loggingRemainingPlots);
        assertEquals(List.of(plot),f.profile.loggingReplantingPlots);assertTrue(engine.running());
    }

    @Test void grantedLoggingWaitAllowsAnOrdinaryOrchardPassWithoutChangingDurableLoggingState() {
        Fixture f=new Fixture();LoggingWaitJob logging=new LoggingWaitJob();AutomationEngine engine=resourceWaitEngine(f,logging);
        for(int i=0;i<100&&f.stored==0;i++)f.engineTick(engine);
        assertEquals(1,f.stored);assertEquals(List.of(FRUIT),f.clicked());assertEquals(1,logging.ticks);
        assertTrue(f.profile.loggingRunActive);assertEquals(List.of(new Pos(50,0,0)),f.profile.loggingRemainingPlots);
        assertTrue(f.profile.loggingReplantingPlots.isEmpty());assertEquals(99L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
    }

    @Test void pendingOrchardNativeReplyCannotBeInterruptedByAnExpiredLoggingWait() {
        Fixture f=new Fixture();LoggingWaitJob logging=new LoggingWaitJob();AutomationEngine engine=resourceWaitEngine(f,logging);
        for(int i=0;i<30&&!f.busy();i++){engine.tick(f.context);f.now++;}
        assertTrue(f.busy());int checks=logging.checks,sent=f.submitted.size();f.now+=1300;
        for(int i=0;i<20;i++){engine.tick(f.context);f.now++;}
        assertTrue(engine.running());assertEquals(checks,logging.checks);assertEquals(sent,f.submitted.size());assertEquals(1,logging.ticks);
        f.complete(true);for(int i=0;i<100&&f.stored==0;i++)f.engineTick(engine);
        assertEquals(1,f.stored);
    }

    @Test void loggingWaitReadinessIsRevalidatedAfterTheOrchardReachesACleanBoundary() {
        Fixture f=new Fixture();LoggingWaitJob logging=new LoggingWaitJob();AutomationEngine engine=resourceWaitEngine(f,logging);
        f.engineTick(engine);int checks=logging.checks;logging.readiness=AutomationModule.ResourceReadiness.UNSAFE;
        for(int i=0;i<150&&engine.running();i++)f.engineTick(engine);
        assertEquals(1,f.stored);assertEquals(AutomationEngine.State.PAUSED,engine.state());assertTrue(logging.checks>checks);
        assertEquals(1,logging.ticks);assertTrue(f.profile.loggingRunActive);assertEquals(1,f.profile.loggingRemainingPlots.size());
    }

    @Test void activeLoggingAndLoggingOneShotKeepTheirExclusiveRoutine() {
        for(boolean once:List.of(false,true)) {
            Fixture f=new Fixture();LoggingWaitJob logging=new LoggingWaitJob();logging.waiting=false;
            prepareLoggingWait(f);AutomationEngine engine=new AutomationEngine(List.of(logging,f.module));
            if(once)engine.startOnce(f.context,Feature.LOGGING);else engine.start(f.context);
            for(int i=0;i<30;i++)f.engineTick(engine);
            assertTrue(engine.running());assertEquals(30,logging.ticks);assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());
        }
    }

    @Test void stopOrFeatureDisableAtAnOrchardBoundaryPreventsAnyNewFruitAction() {
        for(boolean disable:List.of(false,true)) {
            Fixture f=new Fixture();AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.start(f.context);f.engineTick(engine);
            if(disable)f.profile.enabled.put(Feature.STARFRUIT,false);else engine.stop(f.context,AutomationEngine.State.PAUSED,"operator stop");
            f.engineTick(engine);assertEquals(AutomationEngine.State.PAUSED,engine.state());assertTrue(f.clicked().isEmpty());
            assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void aFailedNativeUsePausesOneShotAndDoesNotMarkThePatchInspected() {
        Fixture f=new Fixture();AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.startOnce(f.context,Feature.STARFRUIT);
        for(int i=0;i<100&&engine.running();i++) {
            engine.tick(f.context);
            if(f.busy())f.complete(!(f.submitted.get(f.submitted.size()-1) instanceof Action.UseBlock use&&use.purpose()==Action.Use.FRUIT));
            f.now++;
        }
        assertEquals(AutomationEngine.State.PAUSED,engine.state());assertEquals(1,f.clicked().size());assertEquals(0,f.stored);
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(7,f.blocks.get(FRUIT).number("age",-1));
    }

    @Test void normalSchedulerCanSleepAfterBlockedOrTimedOutOrchardApproach() {
        for(Navigation.Result nav:List.of(Navigation.Result.BLOCKED,Navigation.Result.MOVING)) {
            Fixture f=new Fixture();f.day=13000;f.profile.enabled.put(Feature.SLEEP,true);
            Pos bed=new Pos(40,0,0);f.profile.pois.add(new Poi(bed,PoiKind.BED,"Bed",null));
            f.blocks.put(bed,new BlockData(bed,"minecraft:red_bed",Map.of()));f.targetNavigation.put(FRUIT,nav);
            AutomationEngine engine=new AutomationEngine(List.of(f.module,new SleepModule()));engine.start(f.context);
            for(int i=0;i<2600&&f.sleepUses==0;i++)f.engineTick(engine);
            assertEquals(1,f.sleepUses);assertTrue(f.clicked().isEmpty());
            assertEquals(nav==Navigation.Result.BLOCKED?1:2400,f.travel.stream().filter(FRUIT::equals).count());
            assertFalse(f.profile.nextEligibleDay.containsKey("orchard:nearby"));
        }
    }

    @Test void skippedTargetDoesNotDisableOtherRegisteredFruitAndItsCooldownSurvivesReset() {
        Fixture f=new Fixture();f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);f.run();
        f.targetNavigation.clear();f.module.reset();int travel=f.travel.size();f.run();assertEquals(travel,f.travel.size());
        Pos other=new Pos(5,1,0);f.patch(FRUIT,other);f.fruit(other,7);f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);
        f.run();f.targetNavigation.clear();f.module.reset();f.run();
        assertEquals(List.of(other),f.clicked());assertEquals(1,f.stored);assertFalse(f.profile.nextEligibleDay.containsKey("orchard:nearby"));
    }

    @Test void preUseSkipIsRevalidatedOnDeadlineDayTickRewindAndProfileChange() {
        for(int change=0;change<4;change++) {
            Fixture f=new Fixture();f.now=100;f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);f.run();
            f.targetNavigation.clear();f.module.reset();int travel=f.travel.size();f.run();assertEquals(travel,f.travel.size());
            Context c=f.context;
            if(change==0)f.now+=1200;if(change==1)f.day+=24000;if(change==2)f.now=1;
            if(change==3){Profile p=new Profile();p.enabled.put(Feature.STARFRUIT,true);p.fruitPatches=List.copyOf(f.profile.fruitPatches);p.commodityStores.putAll(f.profile.commodityStores);c=new Context(f,f,f,p);}
            assertEquals(WorkResult.State.BUSY,f.module.tick(c).state(),"change="+change);
            assertTrue(f.travel.size()>travel);assertTrue(f.clicked().isEmpty());
        }
    }

    @Test void oneShotStoresReachableFruitWithoutClaimingAnUnreachablePatchCompleted() {
        Fixture f=new Fixture();Pos other=new Pos(8,1,0),unreachable=new Pos(50,1,0);
        f.patch(FRUIT,other,unreachable);f.fruit(other,7);f.fruit(unreachable,7);f.followArrivals=true;
        f.targetNavigation.put(unreachable,Navigation.Result.BLOCKED);
        AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.startOnce(f.context,Feature.STARFRUIT);
        for(int i=0;i<100&&engine.running();i++)f.engineTick(engine);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertEquals(List.of(FRUIT,other),f.clicked());
        assertEquals(2,f.stored);assertEquals(1,f.storeOpens());assertEquals(7,f.blocks.get(unreachable).number("age",-1));
        assertFalse(f.profile.nextEligibleDay.containsKey("orchard:nearby"));
        assertFalse(engine.status().contains("no eligible work remains"));
    }

    @Test void failedDepositLeavesItsPatchUnmarkedAndDoesNotBeginTheNextPatch() {
        Fixture f=new Fixture();Pos second=new Pos(80,1,0);f.fruit(second,7);
        f.profile.fruitPatches=List.of(new FruitPatch("first","fruit",List.of(FRUIT)),new FruitPatch("second","fruit",List.of(second)));
        AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.startOnce(f.context,Feature.STARFRUIT);
        for(int i=0;i<100&&engine.running();i++) {
            engine.tick(f.context);
            if(f.busy())f.complete(!(f.submitted.get(f.submitted.size()-1) instanceof Action.UseBlock use&&use.purpose()==Action.Use.OPEN_CONTAINER));
            f.now++;
        }
        assertEquals(AutomationEngine.State.PAUSED,engine.state());assertEquals(List.of(FRUIT),f.clicked());assertEquals(0,f.stored);
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(7,f.blocks.get(second).number("age",-1));
    }

    @Test void initiallyUnloadedRegisteredFruitIsObservedBeforeItsMaturityCanAuthorizeUse() {
        Fixture f=new Fixture();Pos distant=new Pos(120,1,0);f.patch(distant);f.fruit(distant,7);f.unloaded.add(distant);
        f.targetNavigation.put(distant,Navigation.Result.MOVING);
        for(int i=0;i<150;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(150,f.observations.size());assertTrue(f.observations.stream().allMatch(distant::equals));
        assertFalse(f.blockReads.contains(distant));assertTrue(f.clicked().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.unloaded.clear();f.targetNavigation.clear();f.followArrivals=true;f.run();
        assertEquals(List.of(distant),f.clicked());assertEquals(1,f.stored);assertEquals(0,f.scans);
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void observationArrivalAloneNeverAuthorizesUseOfAnUnloadedFruit() {
        Fixture f=new Fixture();f.unloaded.add(FRUIT);f.run();
        assertEquals(WorkResult.State.IDLE,f.result.state());assertFalse(f.observations.isEmpty());
        assertFalse(f.blockReads.contains(FRUIT));assertTrue(f.clicked().isEmpty());
        assertFalse(f.profile.nextEligibleDay.containsKey("orchard:nearby"));
    }

    @Test void unloadedFruitThatBecomesVisibleButUnripeIsInspectedWithoutInteraction() {
        Fixture f=new Fixture();f.unloaded.add(FRUIT);f.targetNavigation.put(FRUIT,Navigation.Result.MOVING);
        f.step();assertEquals(List.of(FRUIT),f.observations);f.unloaded.clear();f.fruit(FRUIT,6);f.run();
        assertTrue(f.clicked().isEmpty());assertEquals(0,f.stored);assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void approachAndObservationTimeoutsCountActualWorldTicksRatherThanModuleCalls() {
        for(boolean unloaded:List.of(false,true)) {
            Fixture f=new Fixture();f.navigationResult=Navigation.Result.MOVING;if(unloaded)f.unloaded.add(FRUIT);
            for(int i=0;i<3000;i++)assertEquals(WorkResult.State.BUSY,f.module.tick(f.context).state());
            assertEquals(0,f.now);assertTrue(f.clicked().isEmpty());f.now=2400;f.run();
            assertTrue(f.clicked().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(f.now<=2403);
        }
    }

    @Test void aNewlyRegisteredPatchIsNotAddedToAnAlreadyScheduledPass() {
        Fixture f=new Fixture();Pos added=new Pos(80,1,0);f.fruit(added,7);f.untilFruit();
        f.profile.fruitPatches=List.of(f.profile.fruitPatches.get(0),new FruitPatch("added","fruit",List.of(added)));
        f.complete(true);f.run();assertEquals(List.of(FRUIT),f.clicked());assertFalse(f.blockReads.contains(added));
        assertFalse(f.profile.nextEligibleDay.containsKey("orchard:added"));
        f.run();assertEquals(List.of(FRUIT,added),f.clicked());assertEquals(2,f.stored);
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:added"));
    }

    @Test void inspectionDayIsNotSavedUntilBothDepositAndContainerCloseAreAcknowledged() {
        Fixture f=new Fixture();f.untilAction(Action.QuickMove.class);assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.complete(true);f.untilAction(Action.CloseContainer.class);
        for(int i=0;i<8;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertTrue(f.open);assertTrue(f.profile.nextEligibleDay.isEmpty());f.complete(true);f.run();
        assertFalse(f.open);assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void inspectionCheckpointFailureRollsBackTheDailyMarkerAndBlocks() {
        Fixture f=new Fixture();f.fruit(FRUIT,6);int[] saves={0};
        Context c=new Context(f,f,f,f.profile,new SessionState(),()->{saves[0]++;throw new IllegalStateException("disk unavailable");});
        assertEquals(WorkResult.State.BLOCKED,f.module.tick(c).state());assertEquals(1,saves[0]);
        assertTrue(f.profile.nextEligibleDay.isEmpty());assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());
    }

    @Test void navigatorOwnedDoorReplySettlesBeforeAnyNewObservationOrFruitAction() {
        Fixture f=new Fixture();f.doorOnNextMove=true;f.step();assertTrue(f.busy());
        assertEquals(Action.Use.DOOR,((Action.UseBlock)f.submitted.get(0)).purpose());
        int reads=f.blockReads.size(),resets=f.resets;
        for(int i=0;i<30;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(reads,f.blockReads.size());assertEquals(resets,f.resets);assertEquals(1,f.submitted.size());
        f.complete(true);f.run();assertEquals(List.of(FRUIT),f.clicked());assertEquals(1,f.stored);
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void failedNativeDoorCannotBecomeASkipAfterATimeoutOrFruitStateChange() {
        Fixture f=new Fixture();f.doorOnNextMove=true;f.step();f.now+=2500;f.fruit(FRUIT,6);
        int reads=f.blockReads.size(),resets=f.resets;
        for(int i=0;i<8;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(reads,f.blockReads.size());assertEquals(resets,f.resets);assertTrue(f.busy());
        f.complete(false);assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(1,f.submitted.size());assertTrue(f.clicked().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void dayAndRegistrationChangesWaitForTheNavigatorsAlreadySentDoorReply() {
        for(boolean registration:List.of(false,true)) {
            Fixture f=new Fixture();f.doorOnNextMove=true;f.step();
            if(registration)f.profile.fruitPatches=List.of();else f.day+=24000;
            int reads=f.blockReads.size(),resets=f.resets;
            for(int i=0;i<8;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
            assertEquals(reads,f.blockReads.size());assertEquals(resets,f.resets);assertEquals(1,f.submitted.size());
            f.complete(true);
            if(registration)assertEquals(WorkResult.State.BLOCKED,f.step().state());else f.run();
            assertEquals(1,f.submitted.size());assertTrue(f.clicked().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void anUnrelatedBusyActionDuringApproachCannotBorrowNavigatorDoorOwnership() {
        Fixture f=new Fixture();f.navigationResult=Navigation.Result.MOVING;f.step();f.externalBusy=true;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());assertTrue(f.submitted.isEmpty());
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    private static void prepareLoggingWait(Fixture f) {
        f.profile.enabled.put(Feature.LOGGING,true);f.profile.loggingRunActive=true;Pos corner=new Pos(50,0,0);
        f.profile.loggingPlots.add(new LoggingPlot("waiting",corner));f.profile.loggingRemainingPlots.add(corner);
        f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,99L);
    }
    private static AutomationEngine resourceWaitEngine(Fixture f,LoggingWaitJob logging) {
        prepareLoggingWait(f);AutomationEngine engine=new AutomationEngine(List.of(logging,f.module));engine.start(f.context);return engine;
    }
    private static final class LoggingWaitJob implements AutomationModule {
        int ticks,checks;boolean waiting=true;ResourceReadiness readiness=ResourceReadiness.WAITING;
        public Feature feature(){return Feature.LOGGING;}public int priority(){return 70;}
        public WorkResult tick(Context c){ticks++;return waiting?WorkResult.resourceWait("verified hidden registered tree"):WorkResult.busy("active logging");}
        public ResourceReadiness resourceReadiness(Context c){checks++;assertFalse(c.actions().busy(),"Never query logging readiness during another job's native pending action");return readiness;}
        public void reset(){}
    }
    private static final class TravelJob implements AutomationModule {
        static final Pos DESTINATION=new Pos(100,0,0);
        final Feature feature;int ticks,resets;boolean done;
        TravelJob(Feature feature){this.feature=feature;}
        public Feature feature(){return feature;}public int priority(){return 60;}
        public WorkResult tick(Context c){ticks++;if(done)return WorkResult.idle();c.navigation().moveTo(DESTINATION,4,c);return WorkResult.busy("original travel");}
        public boolean canYieldForNearbyWork(Context c){return true;}
        public void reset(){resets++;}
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

    @Test void filledOrUnknownHotbarWithoutNativeFingerprintNeverBorrowsAnItem() {
        for (boolean missing:List.of(false,true)) {
            Fixture f=new Fixture();
            if (missing) f.inventory.removeIf(s -> s.inventoryIndex()<9);
            else for (int index=0;index<9;index++) f.put(index,f.food);
            f.run();assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());
        }
    }

    @Test void fullHotbarWithNativeProofParksOneOriginalThenRestoresItAfterFruitStorage() {
        Fixture f=new Fixture();f.fullHotbar();List<ItemData> before=f.hotbar();
        f.untilAction(Action.SwapHotbar.class);
        HotbarLease lease=f.profile.workHotbarLease;assertNotNull(lease);
        assertEquals(Feature.STARFRUIT,lease.owner());assertEquals(HotbarLease.Stage.PREPARED,lease.stage());
        assertNotEquals(f.profile.hoeHotbarSlot,lease.hotbarSlot());assertNotEquals(f.profile.loggingAxeHotbarSlot,lease.hotbarSlot());
        assertEquals(before,f.hotbar());assertTrue(f.clicked().isEmpty());
        int actions=f.submitted.size();
        for(int i=0;i<8;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(actions,f.submitted.size());assertTrue(f.clicked().isEmpty());
        f.complete(true);f.run();
        assertEquals(before,f.hotbar());assertEquals(1,f.stored);assertEquals(List.of(FRUIT),f.clicked());
        assertNull(f.profile.workHotbarLease);assertNull(f.context.session().workHotbarOwner);
        assertEquals(List.of(new Action.SwapHotbar(lease.sourceIndex(),lease.hotbarSlot()),
            new Action.SwapHotbar(lease.sourceIndex(),lease.hotbarSlot())),f.swaps());
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));assertFalse(f.open);
    }

    @Test void completionCannotReleaseTheWorkspaceBeforeItsRestorationReply() {
        Fixture f=new Fixture();f.fullHotbar();List<ItemData> before=f.hotbar();f.untilWorkspaceRestore();
        HotbarLease lease=f.profile.workHotbarLease;assertEquals(HotbarLease.Stage.RESTORING,lease.stage());
        assertEquals(1,f.stored);assertEquals(2,f.swaps().size());int actions=f.submitted.size();
        for(int i=0;i<8;i++) {
            assertEquals(WorkResult.State.BUSY,f.step().state());assertSame(lease,f.profile.workHotbarLease);
            assertEquals(Feature.STARFRUIT,f.context.session().workHotbarOwner);
        }
        assertEquals(actions,f.submitted.size());assertEquals(lease.original(),f.inventory.get(lease.sourceIndex()).item());
        f.complete(true);assertEquals(WorkResult.State.IDLE,f.step().state());
        assertNull(f.profile.workHotbarLease);assertNull(f.context.session().workHotbarOwner);assertEquals(before,f.hotbar());
    }

    @Test void actualFruitPickupIntoTheBorrowedHandCanStillBeStoredAndRestored() {
        Fixture f=new Fixture();f.fullHotbar();f.pickupIntoWorkHand=true;List<ItemData> before=f.hotbar();
        f.run();assertEquals(1,f.stored);assertEquals(before,f.hotbar());
        assertNull(f.profile.workHotbarLease);assertEquals(2,f.swaps().size());
        assertEquals(List.of(FRUIT),f.clicked());assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void failedParkingRetainsPreparedCustodyAndNeverClicksTheFruitOrResendsTheSwap() {
        Fixture f=new Fixture();f.fullHotbar();List<ItemData> before=f.hotbar();f.untilAction(Action.SwapHotbar.class);
        HotbarLease lease=f.profile.workHotbarLease;f.complete(false);
        for(int i=0;i<5;i++)assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertSame(lease,f.profile.workHotbarLease);assertEquals(HotbarLease.Stage.PREPARED,lease.stage());
        assertEquals(before,f.hotbar());assertEquals(1,f.swaps().size());assertTrue(f.clicked().isEmpty());
        assertTrue(f.profile.nextEligibleDay.isEmpty());f.module.reset();assertSame(lease,f.profile.workHotbarLease);
    }

    @Test void failedRestorationKeepsItsDurableRecordAcrossModuleResetWithoutInverseReplay() {
        Fixture f=new Fixture();f.fullHotbar();f.untilWorkspaceRestore();HotbarLease lease=f.profile.workHotbarLease;
        f.complete(false);int actions=f.submitted.size();
        for(int i=0;i<5;i++)assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertSame(lease,f.profile.workHotbarLease);assertEquals(HotbarLease.Stage.RESTORING,lease.stage());
        assertEquals(lease.original(),f.inventory.get(lease.sourceIndex()).item());assertEquals(actions,f.submitted.size());
        f.module.reset();assertSame(lease,f.profile.workHotbarLease);assertEquals(actions,f.submitted.size());
    }

    @Test void aDayChangeAfterParkingRestoresTheOriginalWithoutHarvestingOrAdvancingTheOldDay() {
        Fixture f=new Fixture();f.fullHotbar();List<ItemData> before=f.hotbar();f.untilAction(Action.SwapHotbar.class);
        f.complete(true);f.day+=24000;f.run();
        assertTrue(f.clicked().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(before,f.hotbar());assertNull(f.profile.workHotbarLease);assertEquals(2,f.swaps().size());
    }

    @Test void aRemovedPatchAfterParkingRestoresFirstThenReturnsTheOriginalBlockedResult() {
        Fixture f=new Fixture();f.fullHotbar();List<ItemData> before=f.hotbar();f.untilAction(Action.SwapHotbar.class);
        f.complete(true);f.profile.fruitPatches=List.of();f.runUntilTerminal();
        assertEquals(WorkResult.State.BLOCKED,f.result.state());assertTrue(f.clicked().isEmpty());
        assertEquals(before,f.hotbar());assertNull(f.profile.workHotbarLease);assertEquals(2,f.swaps().size());
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void anotherOwnersDurableWorkspaceCannotBeAdoptedByTheOrchard() {
        Fixture f=new Fixture();f.fullHotbar();
        HotbarLease lease=new HotbarLease(Feature.SEED_MAKER,9,2,f.food,"a".repeat(64));
        f.profile.workHotbarLease=lease;f.context.session().workHotbarOwner=Feature.SEED_MAKER;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertSame(lease,f.profile.workHotbarLease);assertEquals(Feature.SEED_MAKER,f.context.session().workHotbarOwner);
        assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void aParkedWorkspaceWithoutLiveOwnerRestoresBeforeResumingFruitWork() {
        Fixture f=new Fixture();f.fullHotbar();f.untilAction(Action.SwapHotbar.class);f.complete(true);
        f.untilAction(Action.SelectHotbar.class);f.complete(true);HotbarLease parked=f.profile.workHotbarLease;
        assertEquals(HotbarLease.Stage.PARKED,parked.stage());
        f.module.reset();f.context.session().workHotbarOwner=null;int actions=f.submitted.size();
        assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(actions+1,f.submitted.size());assertTrue(f.submitted.get(actions) instanceof Action.SwapHotbar);
        assertEquals(HotbarLease.Stage.RESTORING,f.profile.workHotbarLease.stage());
        assertTrue(f.clicked().isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void fullInventoryDoesNotTurnTheOrchardHarvestIntoUncollectedOutput() {
        Fixture f=new Fixture();f.fingerprints=true;for (int index=0;index<36;index++) f.put(index,f.food);
        f.selected=2;f.put(2,f.product(63));
        f.run();assertTrue(f.submitted.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void orchardObservesOnlyRegisteredUnloadedFruitAndNeverExpandsToForestFruit() {
        Fixture f=new Fixture();Pos other=new Pos(5,1,0),unloaded=new Pos(200,1,0),forest=new Pos(199,1,0);
        f.patch(FRUIT,other,unloaded);f.fruit(other,7);f.fruit(unloaded,7);f.fruit(forest,7);f.unloaded.add(unloaded);
        f.run();assertEquals(List.of(FRUIT,other),f.clicked());assertTrue(f.travel.stream().allMatch(p -> p.equals(FRUIT)||p.equals(other)||p.equals(STORE)));
        assertEquals(0,f.blocks.get(other).number("age",-1));assertFalse(f.blockReads.contains(unloaded));assertFalse(f.blockReads.contains(forest));
        assertEquals(Set.of(unloaded),Set.copyOf(f.observations));assertEquals(0,f.scans);assertFalse(f.profile.nextEligibleDay.containsKey("orchard:nearby"));
        assertEquals(2,f.stored);assertEquals(List.of(2),f.fruitUsesAtStoreOpen);
    }

    @Test void orchardIncludesInitiallyDistantAndUnloadedFruitThenVisitsTheNextRegisteredPatch() {
        Fixture f=new Fixture();Pos included=new Pos(5,1,0),far=new Pos(8,1,0),unripe=new Pos(4,1,1),
            unloaded=new Pos(4,1,-1),otherPatch=new Pos(5,1,1);
        f.patch(FRUIT,included,far,unripe,unloaded);f.fruit(included,7);f.fruit(far,7);f.fruit(unripe,6);
        f.fruit(unloaded,7);f.unloaded.add(unloaded);f.fruit(otherPatch,7);
        f.profile.fruitPatches=List.of(f.profile.fruitPatches.get(0),new FruitPatch("other","fruit",List.of(otherPatch)));
        f.followArrivals=true;assertEquals(WorkResult.State.BUSY,f.step().state());
        f.x=3.5;f.fruit(unripe,7);f.unloaded.clear();
        f.run();assertEquals(Set.of(FRUIT,included,far,unloaded,otherPatch),Set.copyOf(f.clicked()));assertEquals(5,f.stored);
        assertEquals(List.of(4,5),f.fruitUsesAtStoreOpen);assertEquals(otherPatch,f.clicked().get(4));
        assertEquals(7,f.blocks.get(unripe).number("age",-1));assertFalse(f.travel.contains(unripe),"Already inspected unripe fruit is not revisited during the same daily pass");
        assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));assertEquals(1L,f.profile.nextEligibleDay.get("orchard:other"));
        assertEquals(0,f.scans);
    }

    @Test void anUnreachableFirstCohortMemberDoesNotDiscardReachableMembersOrAddAStoreVisit() {
        Fixture f=new Fixture();Pos second=new Pos(4,1,0),third=new Pos(5,1,0);
        f.patch(FRUIT,second,third);f.fruit(second,7);f.fruit(third,7);
        f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);f.run();
        assertEquals(List.of(second,third),f.clicked());assertEquals(2,f.stored);assertEquals(List.of(2),f.fruitUsesAtStoreOpen);
        assertEquals(1,f.travel.stream().filter(FRUIT::equals).count());assertEquals(7,f.blocks.get(FRUIT).number("age",-1));
        f.targetNavigation.clear();f.module.reset();assertFalse(f.module.hasNearbyWork(f.context));
    }

    @Test void laterPatchMembersAreRecheckedForCurrentMaturityLoadAndExactBlockIdentity() {
        for(String change:List.of("immature","unloaded","wrongBlock","wrongPosition")) {
            Fixture f=new Fixture();Pos second=new Pos(8,1,0);f.patch(FRUIT,second);f.fruit(second,7);f.followArrivals=true;
            f.untilFruit();f.complete(true);
            if(change.equals("immature"))f.fruit(second,6);
            if(change.equals("unloaded"))f.unloaded.add(second);
            if(change.equals("wrongBlock"))f.blocks.put(second,new BlockData(second,"minecraft:air",Map.of()));
            if(change.equals("wrongPosition"))f.blocks.put(second,new BlockData(second.offset(1,0,0),FruitRules.BLOCK,Map.of("age","7")));
            if(change.equals("wrongPosition")) {
                f.runUntilTerminal();assertEquals(WorkResult.State.BLOCKED,f.result.state());
                assertEquals(0,f.stored);assertEquals(0,f.storeOpens());assertTrue(f.profile.nextEligibleDay.isEmpty());
            } else { f.run();assertEquals(1,f.stored);assertEquals(1,f.storeOpens()); }
            assertEquals(List.of(FRUIT),f.clicked(),change);
            assertTrue(f.profile.pendingMachineOutputs.isEmpty());
        }
    }

    @Test void losingPickupCapacityOrTheSafeHotbarStopsTheCohortAndStoresOnlyActualHeldFruit() {
        for(boolean capacity:List.of(false,true)) {
            Fixture f=new Fixture();Pos second=new Pos(5,1,0);f.patch(FRUIT,second);f.fruit(second,7);
            f.untilFruit();f.complete(true);
            if(capacity) {
                for(int index=1;index<36;index++)if(index!=9)f.put(index,f.food);
                f.put(f.selected,f.product(63));
            } else for(int index=1;index<9;index++)f.put(index,f.food);
            f.run();assertEquals(List.of(FRUIT),f.clicked());assertEquals(capacity?64:1,f.stored);
            assertEquals(7,f.blocks.get(second).number("age",-1));assertEquals(1,f.storeOpens());
            assertEquals(f.tool,f.inventory.get(0).item());assertEquals(f.food,f.inventory.get(8).item());
            assertTrue(f.submitted.stream().noneMatch(Action.SwapHotbar.class::isInstance));
        }
    }

    @Test void delayedCohortPickupsAreObservedBeforeOneDepositAndItsActualAckStillControlsCompletion() {
        Fixture f=new Fixture();Pos second=new Pos(5,1,0);f.patch(FRUIT,second);f.fruit(second,7);f.deliver=false;
        f.untilFruit();f.complete(true);f.untilFruit();f.complete(true);
        assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(0,f.storeOpens());
        for(int tick=0;tick<8;tick++)f.step();f.put(9,f.product(2));
        f.untilAction(Action.QuickMove.class);
        for(int tick=0;tick<8;tick++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(0,f.stored);f.complete(true);f.run();
        assertEquals(2,f.stored);assertEquals(List.of(2),f.fruitUsesAtStoreOpen);assertFalse(f.open);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void fruitArrivingAfterTheWholeCohortWindowUsesOnlyTheExistingActualCleanupPath() {
        Fixture f=new Fixture();Pos second=new Pos(5,1,0);f.patch(FRUIT,second);f.fruit(second,7);f.deliver=false;f.run();
        assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(0,f.stored);assertEquals(0,f.storeOpens());
        f.x=100;f.put(9,f.product(2));int priorTravel=f.travel.size();f.run();
        assertEquals(2,f.stored);assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(1,f.storeOpens());
        assertTrue(f.travel.subList(priorTravel,f.travel.size()).stream().allMatch(STORE::equals));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
    }

    @Test void anUnconfirmedSecondCohortUseBlocksWithoutResendingOrTreatingEarlierFruitAsItsAck() {
        Fixture f=new Fixture();Pos second=new Pos(5,1,0),third=new Pos(5,1,1);
        f.patch(FRUIT,second,third);f.fruit(second,7);f.fruit(third,7);
        f.untilFruit();f.complete(true);f.untilFruit();f.fruit(second,0);
        for(int tick=0;tick<8;tick++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(0,f.storeOpens());
        f.complete(false);assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(0,f.storeOpens());assertEquals(7,f.blocks.get(third).number("age",-1));
        assertEquals(1,ModuleSupport.count(f.context,item->item.is(FruitRules.ITEM)));
    }

    @Test void aSameIdStoreReplacementCannotRedirectAnInFlightCohortAfterItsNativeAck() {
        Fixture f=new Fixture();Pos second=new Pos(5,1,0);f.patch(FRUIT,second);f.fruit(second,7);f.untilFruit();
        f.profile.commodityStores.put("fruit",new CommodityStore("fruit","Moved",Set.of(FruitRules.ITEM),List.of(STORE.offset(5,0,0))));
        int actions=f.submitted.size();assertEquals(WorkResult.State.BUSY,f.step().state());assertEquals(actions,f.submitted.size());
        f.complete(true);assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(actions,f.submitted.size());assertEquals(List.of(FRUIT),f.clicked());assertEquals(0,f.storeOpens());
        assertEquals(7,f.blocks.get(second).number("age",-1));assertEquals(1,ModuleSupport.count(f.context,item->item.is(FruitRules.ITEM)));
    }

    @Test void walkingAroundTheActivatedTreeDoesNotDiscardItsOppositeRegisteredRipeFruit() {
        Fixture f=new Fixture();Pos right=new Pos(5,1,0),left=new Pos(-4,1,0);
        f.patch(FRUIT,right,left);f.fruit(right,7);f.fruit(left,7);f.followArrivals=true;
        assertTrue(f.player().distance(left)<6);f.untilFruit();f.complete(true);f.untilFruit();
        assertEquals(List.of(FRUIT,right),f.clicked());assertTrue(f.player().distance(left)>6);
        f.complete(true);f.run();assertEquals(List.of(FRUIT,right,left),f.clicked());
        assertEquals(3,f.stored);assertEquals(List.of(3),f.fruitUsesAtStoreOpen);assertEquals(1,f.storeOpens());
    }

    @Test void anActivatedTargetWhichBecomesImmatureBeforeUseIsStillSkipped() {
        Fixture f=new Fixture();f.step();f.fruit(FRUIT,6);
        f.run();assertTrue(f.clicked().isEmpty());assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
        assertTrue(f.travel.stream().allMatch(FRUIT::equals));
    }

    @Test void actualInteractionReachAndEachDistantApproachRemainBoundedBeforeOneFinalDeposit() {
        for(boolean missingReach:List.of(false,true)) {
            Fixture f=new Fixture();Pos second=new Pos(8,1,0),third=new Pos(9,1,0);
            f.patch(FRUIT,second,third);f.fruit(second,7);f.fruit(third,7);f.followArrivals=true;
            f.targetNavigation.put(FRUIT,Navigation.Result.MOVING);
            if(missingReach)f.uninteractable.add(second);else f.targetNavigation.put(second,Navigation.Result.MOVING);
            f.run();assertEquals(List.of(third),f.clicked());assertEquals(1,f.stored);assertEquals(1,f.storeOpens());
            assertEquals(2400,f.travel.stream().filter(FRUIT::equals).count());
            assertTrue(f.travel.stream().anyMatch(second::equals));assertTrue(f.now<=4850);
            assertEquals(7,f.blocks.get(FRUIT).number("age",-1));assertEquals(7,f.blocks.get(second).number("age",-1));
            int actions=f.submitted.size();f.module.reset();f.step();assertEquals(actions,f.submitted.size());
        }
    }

    @Test void changingDayFinishesOnlyAnAlreadySentFruitAndDoesNotStartTheRemainingPatch() {
        for(String phase:List.of("beforeSelect","selectPending","fruitPending")) {
            Fixture f=new Fixture();Pos second=new Pos(8,1,0);f.patch(FRUIT,second);f.fruit(second,7);f.followArrivals=true;
            boolean fruitSent=phase.equals("fruitPending");
            if(fruitSent)f.untilFruit();else if(phase.equals("selectPending"))f.untilAction(Action.SelectHotbar.class);else f.step();
            f.day+=24000;
            if(!phase.equals("beforeSelect"))f.complete(true);
            f.run();assertEquals(fruitSent?List.of(FRUIT):List.of(),f.clicked(),phase);assertEquals(fruitSent?1:0,f.stored);
            assertEquals(7,f.blocks.get(second).number("age",-1));assertEquals(fruitSent?1:0,f.storeOpens());
        }
    }

    @Test void orchardNavigationUsesAnActualTickDeadlineAndNeverRetriesAUse() {
        Fixture f=new Fixture();f.navigationResult=Navigation.Result.MOVING;f.run();
        assertEquals(WorkResult.State.IDLE,f.result.state());assertTrue(f.clicked().isEmpty());assertTrue(f.now>=2400&&f.now<=2403);
        Fixture blocked=new Fixture();blocked.navigationResult=Navigation.Result.BLOCKED;
        blocked.run();assertTrue(blocked.submitted.isEmpty());assertTrue(blocked.profile.nextEligibleDay.isEmpty());
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
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertEquals(1L,f.profile.nextEligibleDay.get("orchard:nearby"));
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
        f.run(new Context(f,f,f,replacement));
        assertEquals(actions,f.submitted.size());assertEquals(0,f.stored);
    }

    @Test void aUseConfirmedAfterMidnightBelongsToTheActualConfirmationDay() {
        Fixture f=new Fixture();f.untilFruit();f.day+=24000;f.complete(true);f.run();
        f.fruit(FRUIT,7);f.run();assertEquals(1,f.clicked().size());
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
        final Map<Pos,BlockData> blocks=new HashMap<>();final Set<Pos> unloaded=new HashSet<>(),uninteractable=new HashSet<>();
        final Map<Pos,Navigation.Result> targetNavigation=new HashMap<>();
        final List<Pos> blockReads=new ArrayList<>(),travel=new ArrayList<>(),observations=new ArrayList<>();
        final List<ItemSlot> inventory=new ArrayList<>();final List<Action> submitted=new ArrayList<>();
        final List<Integer> fruitUsesAtStoreOpen=new ArrayList<>();
        final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final Map<Integer,ItemData> serverInventory=new HashMap<>();
        final ItemData tool=new ItemData("minecraft:iron_hoe",1,0,null,true,100),food=new ItemData("farmersdelight:fruit_salad",57,0,null,false,0);
        long now,day=5000,current,navigationTicket=-1;int selected,stored,stops,resets,scans,sleepUses;
        double x=.5;boolean open,externalBusy,changeAge=true,deliver=true,travelYield,sleeping,followArrivals,doorOnNextMove;
        boolean fingerprints,workCustodyEvidence,pickupIntoWorkHand;
        ItemData cursor=ItemData.EMPTY;String actionFence;
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
        void fullHotbar(){fingerprints=true;for(int index=1;index<9;index++)put(index,food);}
        List<ItemData> hotbar(){return inventory.stream().filter(s->s.inventoryIndex()<9).map(ItemSlot::item).toList();}
        List<Action.SwapHotbar> swaps(){return submitted.stream().filter(Action.SwapHotbar.class::isInstance).map(Action.SwapHotbar.class::cast).toList();}
        List<Pos> clicked(){return submitted.stream().filter(a->a instanceof Action.UseBlock use&&use.purpose()==Action.Use.FRUIT).map(a->((Action.UseBlock)a).pos()).toList();}
        long storeOpens(){return submitted.stream().filter(a->a instanceof Action.UseBlock use&&use.purpose()==Action.Use.OPEN_CONTAINER).count();}
        WorkResult step(){result=module.tick(context);now++;return result;}
        AutomationEngine engine(AutomationModule original){profile.enabled.put(original.feature(),true);AutomationEngine e=new AutomationEngine(List.of(original,module));e.start(context);return e;}
        void engineTick(AutomationEngine engine){engine.tick(context);if(busy()&&!externalBusy)complete(true);now++;}
        void run(){runUntilTerminal();assertEquals(WorkResult.State.IDLE,result.state(),result.message());}
        void run(Context c){for(int tick=0;tick<5000;tick++){result=module.tick(c);now++;if(busy())complete(true);if(result.state()!=WorkResult.State.BUSY){assertEquals(WorkResult.State.IDLE,result.state(),result.message());return;}}fail("Orchard pass did not terminate");}
        void runUntilTerminal(){for(int tick=0;tick<5000;tick++){step();if(busy())complete(true);if(result.state()!=WorkResult.State.BUSY)return;}fail("Orchard pass did not terminate");}
        void untilFruit(){untilAction(Action.UseBlock.class);assertEquals(Action.Use.FRUIT,((Action.UseBlock)submitted.get(submitted.size()-1)).purpose());}
        void untilAction(Class<? extends Action> type){
            for(int tick=0;tick<100;tick++) {
                step();assertEquals(WorkResult.State.BUSY,result.state(),result.message());
                if(busy()){if(type.isInstance(submitted.get(submitted.size()-1)))return;complete(true);}
            }
            fail("Expected "+type.getSimpleName());
        }
        void untilWorkspaceRestore(){
            for(int tick=0;tick<200;tick++) {
                step();assertEquals(WorkResult.State.BUSY,result.state(),result.message());
                if(busy()) {
                    if(submitted.get(submitted.size()-1) instanceof Action.SwapHotbar
                        && profile.workHotbarLease.stage()==HotbarLease.Stage.RESTORING)return;
                    complete(true);
                }
            }
            fail("Expected the exact workspace restoration swap");
        }
        void deliverFruit(){
            if(pickupIntoWorkHand && profile.workHotbarLease!=null) {
                int index=profile.workHotbarLease.hotbarSlot();ItemData item=inventory.get(index).item();
                assertTrue(item.empty()||item.is(FruitRules.ITEM));put(index,product(item.count()+1));return;
            }
            for(int offset=0;offset<36;offset++) {
                int index=(9+offset)%36;ItemData item=inventory.get(index).item();
                if(item.empty() || item.is(FruitRules.ITEM) && item.count()<64) {put(index,product(item.count()+1));return;}
            }
            fail("The detached pickup model cannot overwrite a full inventory");
        }
        void complete(boolean success) {
            Action action=submitted.get(submitted.size()-1);int moved=0;
            if(success) {
                if(action instanceof Action.SelectHotbar select)selected=select.slot();
                else if(action instanceof Action.SwapHotbar swap) {
                    ItemData source=inventory.get(swap.inventoryIndex()).item(),hand=inventory.get(swap.hotbarSlot()).item();
                    put(swap.inventoryIndex(),hand);put(swap.hotbarSlot(),source);workCustodyEvidence=true;
                }
                else if(action instanceof Action.UseBlock use) {
                    if(use.purpose()==Action.Use.FRUIT){if(changeAge)fruit(use.pos(),0);if(deliver)deliverFruit();}
                    else if(use.purpose()==Action.Use.OPEN_CONTAINER)open=true;
                    else if(use.purpose()==Action.Use.SLEEP){sleepUses++;sleeping=true;}
                    else if(use.purpose()==Action.Use.DOOR)blocks.put(use.pos(),new BlockData(use.pos(),"minecraft:oak_door",Map.of("open","true")));
                    else fail("Unexpected use "+use);
                } else if(action instanceof Action.QuickMove move) {
                    int source=move.slot()-27;ItemData item=inventory.get(source).item();assertTrue(item.is(FruitRules.ITEM));
                    moved=item.count();stored+=moved;put(source,ItemData.EMPTY);
                } else if(action instanceof Action.CloseContainer)open=false;
                else fail("Unexpected action "+action);
                serverInventory.clear();for(ItemSlot slot:inventory)serverInventory.put(slot.inventoryIndex(),slot.item());
            }
            outcomes.put(current,new ActionOutcome(success?ActionOutcome.State.SUCCEEDED:ActionOutcome.State.FAILED,"native ack",moved));
        }
        public long tick(){return now;}public long dayTime(){return day;}
        public PlayerState player(){return new PlayerState(x,0,.5,0,0,true,sleeping,20,20,selected,true,true);}
        public BlockData block(Pos pos){blockReads.add(pos);return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of()));}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos pos,int horizontal,int vertical){scans++;throw new AssertionError("Orchard inspection must not expand the registered fruit mask");}
        public List<ItemSlot> inventory(){return inventory;}
        public String loggingItemFingerprint(int index){
            if(!fingerprints || index<0 || index>=inventory.size())return null;
            ItemData item=inventory.get(index).item();return item.empty()?null:item.is(food.id())?"a".repeat(64):"b".repeat(64);
        }
        private boolean custody(HotbarLease lease,int originalIndex){
            return workCustodyEvidence && !busy() && actionFence==null && lease!=null && lease.valid()
                && lease.original().equals(inventory.get(originalIndex).item())
                && lease.fingerprint().equals(loggingItemFingerprint(originalIndex))
                && Objects.equals(serverInventory.get(lease.sourceIndex()),inventory.get(lease.sourceIndex()).item())
                && Objects.equals(serverInventory.get(lease.hotbarSlot()),inventory.get(lease.hotbarSlot()).item());
        }
        public boolean workHotbarParked(HotbarLease lease){return custody(lease,lease.sourceIndex());}
        public boolean workHotbarRestored(HotbarLease lease){return custody(lease,lease.hotbarSlot());}
        public MenuData menu(){
            if(!open)return new MenuData(0,0,inventory,cursor,false);
            List<ItemSlot> slots=new ArrayList<>();for(int index=0;index<27;index++)slots.add(new ItemSlot(index,-1,false,index==0&&stored>0?product(stored):ItemData.EMPTY));
            for(ItemSlot slot:inventory)slots.add(new ItemSlot(slot.inventoryIndex()+27,slot.inventoryIndex(),true,slot.item()));
            return new MenuData(12,1,slots,cursor,true);
        }
        public boolean mayPlace(int slot,ItemData item){return slot>=0&&slot<27;}
        public boolean canInteract(Pos pos,double reach){return !uninteractable.contains(pos)&&(!followArrivals||player().distance(pos)<=reach);}
        public boolean busy(){return externalBusy||current>0&&!outcomes.get(current).done();}
        public boolean ownsContainer(){return open;}
        public String pauseReason(){return actionFence;}
        public long submit(Action action){assertFalse(busy());assertNull(SafetyPolicy.rejection(action,context));
            if(action instanceof Action.UseBlock use&&use.purpose()==Action.Use.OPEN_CONTAINER)fruitUsesAtStoreOpen.add(clicked().size());
            submitted.add(action);outcomes.put(++current,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return current;}
        public ActionOutcome outcome(long ticket){return outcomes.get(ticket);}
        public void move(Movement movement){}public void stopMovement(){stops++;}public void cancel(){}
        public boolean canYieldTravel(Context c){return travelYield;}
        public Result moveTo(Pos target,double reach,Context c){
            if(navigationTicket>=0&&outcomes.get(navigationTicket).done())navigationTicket=-1;
            if(doorOnNextMove) {
                doorOnNextMove=false;Pos door=new Pos(1,0,0);blocks.put(door,new BlockData(door,"minecraft:oak_door",Map.of("open","false")));
                navigationTicket=submit(new Action.UseBlock(door,Action.Use.DOOR));travel.add(target);return Result.MOVING;
            }
            travel.add(target);Result result=targetNavigation.getOrDefault(target,target.equals(TravelJob.DESTINATION)?Result.MOVING:navigationResult);
            // Detached navigation arrival, never a production position or velocity write.
            if(followArrivals&&result==Result.ARRIVED)x=target.x()+.5;
            return result;
        }
        public Result moveToObserve(Pos target,double reach,Context c){
            observations.add(target);return targetNavigation.getOrDefault(target,navigationResult);
        }
        public ActionOutcome pendingInteractionOutcome(Context c){
            return navigationTicket>=0&&c.world()==this&&c.actions()==this&&c.navigation()==this?outcomes.get(navigationTicket):null;
        }
        public void reset(){resets++;navigationTicket=-1;}
    }
}
