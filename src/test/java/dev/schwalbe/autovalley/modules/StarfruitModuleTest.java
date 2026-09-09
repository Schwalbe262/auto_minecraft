package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StarfruitModuleTest {
    private static final Pos FRUIT=new Pos(3,1,0),STORE=new Pos(20,0,0);

    @Test void nearbyCandidateQueryDoesNotSelectMoveOrSubmitAndKeepsSameDayEvidence() {
        Fixture f=new Fixture();for(int i=0;i<10;i++)assertTrue(f.module.hasNearbyWork(f.context));
        assertTrue(f.submitted.isEmpty());assertTrue(f.travel.isEmpty());assertEquals(0,f.stops);assertEquals(0,f.resets);
        f.run();f.fruit(FRUIT,7);assertFalse(f.module.hasNearbyWork(f.context));
        f.day+=24000;assertTrue(f.module.hasNearbyWork(f.context));
        assertEquals(1,f.clicked().size());assertEquals(1,f.stored);
    }
    @Test void nearbyTriggerPicksAllSevenRegisteredRipeFruitBeyondItsInitialRadiusThenStoresAndResumesTheExactOrigin() {
        Fixture f=new Fixture();List<Pos> cohort=List.of(FRUIT,new Pos(4,1,0),new Pos(5,1,0),new Pos(6,1,0),
            new Pos(7,1,0),new Pos(8,1,0),new Pos(9,1,0));
        assertTrue(cohort.stream().anyMatch(p->f.player().distance(p)>6));
        f.patch(cohort.toArray(Pos[]::new));cohort.forEach(p->f.fruit(p,7));f.travelYield=true;f.followArrivals=true;
        TravelJob original=new TravelJob(Feature.WINE);AutomationEngine engine=f.engine(original);
        f.engineTick(engine);f.engineTick(engine);assertEquals(2,original.ticks);
        int resets=original.resets;
        for(int i=0;i<200 && original.ticks==2;i++)f.engineTick(engine);
        assertEquals(3,original.ticks);assertEquals(resets,original.resets);assertEquals(7,f.stored);
        assertEquals(7,f.clicked().size());assertEquals(Set.copyOf(cohort),Set.copyOf(f.clicked()));
        assertEquals(List.of(7),f.fruitUsesAtStoreOpen);assertEquals(1,f.storeOpens());
        assertEquals(1,f.submitted.stream().filter(Action.QuickMove.class::isInstance).count());
        assertEquals(1,f.submitted.stream().filter(Action.CloseContainer.class::isInstance).count());
        cohort.forEach(p->f.fruit(p,7));f.x=.5;
        assertFalse(f.module.hasNearbyWork(f.context),"Every confirmed member remains excluded even if it appears ripe again");
        for(int i=0;i<1100;i++)f.engineTick(engine);
        assertEquals(7,f.clicked().size());assertTrue(original.ticks>1000);assertTrue(engine.running());
    }
    @Test void oneShotAndNonOptedInOrUnconsumedActionStagesCannotBePreempted() {
        for(int mode=0;mode<4;mode++) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(mode==1?Feature.HARVEST:Feature.WINE);
            if(mode==2)original.allowYield=false;if(mode==3)original.unconsumed=true;
            AutomationEngine engine=f.engine(original);
            if(mode==0)engine.startOnce(f.context,Feature.WINE);
            for(int i=0;i<30;i++)f.engineTick(engine);
            assertEquals(30,original.ticks);assertTrue(f.submitted.isEmpty());
            assertTrue(f.travel.stream().allMatch(TravelJob.DESTINATION::equals));
        }
    }
    @Test void unsafeNavigationMenuCursorOrNativeActionNeverGrantsANearbyPass() {
        for(int guard=0;guard<5;guard++) {
            Fixture f=new Fixture();f.travelYield=guard!=0;TravelJob original=new TravelJob(Feature.WINE);
            AutomationEngine engine=f.engine(original);
            if(guard==1)f.externalBusy=true;if(guard==2)f.open=true;
            if(guard==3)f.cursor=f.product(1);if(guard==4)f.actionFence="uncertain native reply";
            for(int i=0;i<10;i++){engine.tick(f.context);f.now++;}
            assertTrue(f.submitted.isEmpty());assertTrue(f.travel.stream().allMatch(TravelJob.DESTINATION::equals));
        }
    }
    @Test void alreadyGrantedDisabledLoggingSuspensionAllowsPassWithoutChangingTheQueue() {
        Fixture f=new Fixture();f.travelYield=true;f.profile.enabled.put(Feature.LOGGING,false);
        f.profile.loggingRunActive=true;f.profile.loggingRemainingPlots.add(new Pos(50,0,0));
        f.profile.loggingReplantingPlots.add(new Pos(50,0,0));
        TravelJob original=new TravelJob(Feature.WINE);AutomationEngine engine=f.engine(original);
        for(int i=0;i<100 && f.stored==0;i++)f.engineTick(engine);
        assertEquals(1,f.stored);assertTrue(f.profile.loggingRunActive);
        assertEquals(List.of(new Pos(50,0,0)),f.profile.loggingRemainingPlots);
        assertEquals(f.profile.loggingRemainingPlots,f.profile.loggingReplantingPlots);assertTrue(engine.running());
    }
    @Test void grantedLoggingWaitAllowsFruitStorageAndReturnsToTheExactWinePreservesOrSleepInstance() {
        for(Feature feature:List.of(Feature.WINE,Feature.PRESERVES,Feature.SLEEP)) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(feature);LoggingWaitJob logging=new LoggingWaitJob();
            AutomationEngine engine=resourceWaitEngine(f,original,logging);
            Object grant=engineField(engine,"resourceWaitGrant");Object deadline=engineField(engine,"resourceCheckAt");
            List<Pos> remaining=List.copyOf(f.profile.loggingRemainingPlots);int resets=original.resets;
            f.engineTick(engine);assertSame(original,engineField(engine,"nearbyOrigin"));assertSame(f.module,engineField(engine,"active"));
            for(int i=0;i<150 && engineField(engine,"nearbyOrigin")!=null;i++)f.engineTick(engine);
            assertTrue(engine.running(),engine.status());assertEquals(1,f.stored);assertEquals(List.of(FRUIT),f.clicked());
            assertSame(original,engineField(engine,"active"));assertEquals(2,original.ticks);assertEquals(resets,original.resets);
            assertSame(logging,engineField(engine,"resourceWaiting"));assertSame(grant,engineField(engine,"resourceWaitGrant"));
            assertEquals(deadline,engineField(engine,"resourceCheckAt"));assertEquals(1,logging.ticks);
            assertEquals(remaining,f.profile.loggingRemainingPlots);assertTrue(f.profile.loggingReplantingPlots.isEmpty());
            assertEquals(99L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));assertTrue(f.profile.loggingRunActive);
            assertFalse(f.open);assertTrue(f.cursor.empty());f.engineTick(engine);assertEquals(3,original.ticks);
        }
    }
    @Test void aFruitNativePendingReplyDoesNotRecheckOrPreemptItsGrantedLoggingWait() {
        Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);LoggingWaitJob logging=new LoggingWaitJob();
        AutomationEngine engine=resourceWaitEngine(f,original,logging);f.engineTick(engine);
        for(int i=0;i<30&&!f.busy();i++){engine.tick(f.context);f.now++;}
        assertTrue(f.busy());int checks=logging.checks,sent=f.submitted.size();
        f.now+=1300; // Even an expired resource-wait timer cannot interrupt an owned reply.
        for(int i=0;i<20;i++){engine.tick(f.context);f.now++;}
        assertTrue(engine.running(),engine.status());assertEquals(checks,logging.checks);assertEquals(sent,f.submitted.size());
        assertEquals(1,logging.ticks);assertEquals(2,original.ticks);assertSame(logging,engineField(engine,"resourceWaiting"));
        f.complete(true);
        for(int i=0;i<150&&engineField(engine,"nearbyOrigin")!=null;i++)f.engineTick(engine);
        assertSame(original,engineField(engine,"active"));assertEquals(1,f.stored);assertTrue(logging.checks>checks);
    }
    @Test void anUnsafeTravelBoundaryOrAnUngrantableLoggingWaitCannotStartTheDetour() {
        for(String unsafe:List.of("busy","fence","lease","cursor","container","ready","unsafe","navigation")) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);LoggingWaitJob logging=new LoggingWaitJob();
            AutomationEngine engine=resourceWaitEngine(f,original,logging);
            switch(unsafe) {
                case "busy" -> f.externalBusy=true;
                case "fence" -> f.actionFence="unconfirmed native action";
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,2,f.food,"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "cursor" -> f.cursor=f.product(1);
                case "container" -> f.open=true;
                case "ready" -> logging.readiness=AutomationModule.ResourceReadiness.READY;
                case "unsafe" -> logging.readiness=AutomationModule.ResourceReadiness.UNSAFE;
                case "navigation" -> f.travelYield=false;
                default -> throw new AssertionError(unsafe);
            }
            engine.tick(f.context);f.now++;
            assertNull(engineField(engine,"nearbyOrigin"),unsafe);assertTrue(f.submitted.isEmpty(),unsafe);
            assertTrue(f.profile.loggingRunActive);assertEquals(1,f.profile.loggingRemainingPlots.size());
        }
    }
    @Test void changedLoggingGrantOrDurableOwnershipStopsBeforeAnyNewFruitAction() throws Exception {
        for(String changed:List.of("grant","owner","off","inactive","remaining","replant","registration","due","lease")) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);LoggingWaitJob logging=new LoggingWaitJob();
            AutomationEngine engine=resourceWaitEngine(f,original,logging);f.engineTick(engine);
            switch(changed) {
                case "grant" -> setEngineField(engine,"resourceWaitGrant",new Object());
                case "owner" -> setEngineField(engine,"resourceWaiting",new LoggingWaitJob());
                case "off" -> f.profile.enabled.put(Feature.LOGGING,false);
                case "inactive" -> f.profile.loggingRunActive=false;
                case "remaining" -> f.profile.loggingRemainingPlots.clear();
                case "replant" -> f.profile.loggingReplantingPlots.add(new Pos(50,0,0));
                case "registration" -> f.profile.loggingPlots.clear();
                case "due" -> f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,100L);
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,2,f.food,"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                default -> throw new AssertionError(changed);
            }
            engine.tick(f.context);
            assertEquals(AutomationEngine.State.PAUSED,engine.state(),changed);assertTrue(f.submitted.isEmpty(),changed);
            assertEquals(2,original.ticks);assertEquals(1,logging.ticks);
        }
    }
    @Test void loggingWaitGeometryIsRevalidatedOnlyAfterTheFruitPassReturnsToACleanBoundary() {
        Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);LoggingWaitJob logging=new LoggingWaitJob();
        AutomationEngine engine=resourceWaitEngine(f,original,logging);f.engineTick(engine);int checks=logging.checks;
        logging.readiness=AutomationModule.ResourceReadiness.UNSAFE;
        for(int i=0;i<150&&engine.running();i++)f.engineTick(engine);
        assertEquals(1,f.stored);assertEquals(AutomationEngine.State.PAUSED,engine.state());
        assertTrue(logging.checks>checks);assertEquals(2,original.ticks);assertEquals(1,logging.ticks);
        assertTrue(f.profile.loggingRunActive);assertEquals(1,f.profile.loggingRemainingPlots.size());
    }
    @Test void activeLoggingAndLoggingOneShotStillCannotGrantNearbyPreemption() {
        for(boolean once:List.of(false,true)) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);LoggingWaitJob logging=new LoggingWaitJob();
            AutomationEngine engine=resourceWaitEngine(f,original,logging);
            if(once)engine.startOnce(f.context,Feature.LOGGING);
            else {logging.waiting=false;engine.start(f.context);}
            for(int i=0;i<30;i++)f.engineTick(engine);
            assertTrue(engine.running(),engine.status());assertTrue(f.submitted.isEmpty());
            assertNull(engineField(engine,"nearbyOrigin"));assertEquals(1,original.ticks);
        }
    }
    private static AutomationEngine resourceWaitEngine(Fixture f,TravelJob original,LoggingWaitJob logging) {
        f.profile.enabled.put(Feature.LOGGING,true);f.profile.enabled.put(original.feature(),true);f.profile.loggingRunActive=true;
        Pos corner=new Pos(50,0,0);f.profile.loggingPlots.add(new LoggingPlot("waiting",corner));
        f.profile.loggingRemainingPlots.add(corner);f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,99L);
        AutomationEngine engine=new AutomationEngine(List.of(original,logging,f.module));engine.start(f.context);f.engineTick(engine);
        assertEquals(1,logging.ticks);assertEquals(1,original.ticks);assertSame(logging,engineField(engine,"resourceWaiting"));return engine;
    }
    private static final class LoggingWaitJob implements AutomationModule {
        int ticks,checks;boolean waiting=true;ResourceReadiness readiness=ResourceReadiness.WAITING;
        public Feature feature(){return Feature.LOGGING;}public int priority(){return 80;}
        public WorkResult tick(Context c){ticks++;return waiting?WorkResult.resourceWait("verified hidden registered tree"):WorkResult.busy("active logging");}
        public ResourceReadiness resourceReadiness(Context c){checks++;assertFalse(c.actions().busy(),"Never query logging readiness during another job's native pending action");return readiness;}
        public void reset(){}
    }
    private static Object engineField(AutomationEngine engine,String name) {
        try{var field=AutomationEngine.class.getDeclaredField(name);field.setAccessible(true);return field.get(engine);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void setEngineField(AutomationEngine engine,String name,Object value) throws Exception {
        var field=AutomationEngine.class.getDeclaredField(name);field.setAccessible(true);field.set(engine,value);
    }
    @Test void stopFeatureDisableIdentityChangeAndLoggingOwnershipChangeClearSuspensionWithoutClicks() {
        for(int change=0;change<7;change++) {
            Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);
            AutomationEngine engine=f.engine(original);f.engineTick(engine);f.engineTick(engine);
            assertEquals(2,original.ticks);assertTrue(f.submitted.isEmpty());
            Context next=f.context;
            switch(change) {
                case 0 -> engine.stop(f.context,AutomationEngine.State.PAUSED,"operator stop");
                case 1 -> f.profile.enabled.put(Feature.WINE,false);
                case 2 -> f.profile.enabled.put(Feature.STARFRUIT,false);
                case 3 -> next=new Context(f,f,f,new Profile());
                case 4 -> next=new Context(f,f,f,f.profile,new SessionState());
                case 5 -> {f.profile.loggingRunActive=true;f.profile.enabled.put(Feature.LOGGING,true);}
                case 6 -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,2,f.food,"test");
            }
            engine.tick(next);f.now++;
            assertEquals(AutomationEngine.State.PAUSED,engine.state(),"change="+change);
            assertEquals(2,original.ticks);assertTrue(f.submitted.isEmpty());assertTrue(original.resets>=2);
        }
    }
    @Test void failedFruitPassStillResumesTravelButCannotImmediatelyRepeatItsFailure() {
        Fixture f=new Fixture();f.travelYield=true;TravelJob original=new TravelJob(Feature.WINE);AutomationEngine engine=f.engine(original);
        f.engineTick(engine);f.engineTick(engine);
        for(int i=0;i<40 && f.clicked().isEmpty();i++) {
            engine.tick(f.context);if(f.busy())f.complete(!(f.submitted.get(f.submitted.size()-1) instanceof Action.UseBlock));f.now++;
        }
        for(int i=0;i<100;i++)f.engineTick(engine);
        assertEquals(1,f.clicked().size());assertEquals(0,f.stored);assertTrue(original.ticks>50);assertTrue(engine.running());
    }
    @Test void realProductionAndSleepModulesOptInOnlyWhileActuallyTravelling() {
        Fixture f=new Fixture();f.travelYield=true;f.profile.enabled.put(Feature.PRESERVES,true);
        f.profile.pois.add(new Poi(TravelJob.DESTINATION,PoiKind.PRESERVES_JAR,"Jar",null));
        MachineModule machine=new MachineModule(Feature.PRESERVES);assertFalse(machine.canYieldForNearbyWork(f.context));
        assertEquals(WorkResult.State.BUSY,machine.tick(f.context).state());assertFalse(machine.canYieldForNearbyWork(f.context));
        assertEquals(WorkResult.State.BUSY,machine.tick(f.context).state());assertTrue(machine.canYieldForNearbyWork(f.context));
        machine.reset();assertFalse(machine.canYieldForNearbyWork(f.context));
        f.profile.pois.add(new Poi(TravelJob.DESTINATION,PoiKind.BED,"Bed",null));f.day=13000;
        SleepModule sleep=new SleepModule();assertFalse(sleep.canYieldForNearbyWork(f.context));
        assertEquals(WorkResult.State.BUSY,sleep.tick(f.context).state());assertTrue(sleep.canYieldForNearbyWork(f.context));
        sleep.reset();assertFalse(sleep.canYieldForNearbyWork(f.context));
    }
    @Test void normalSchedulerCanSleepAfterBlockedOrTimedOutFruitWithoutRepeatingTheApproach() {
        for(Navigation.Result nav:List.of(Navigation.Result.BLOCKED,Navigation.Result.MOVING)) {
            Fixture f=new Fixture();f.day=13000;f.profile.enabled.put(Feature.SLEEP,true);
            Pos bed=new Pos(40,0,0);f.profile.pois.add(new Poi(bed,PoiKind.BED,"Bed",null));
            f.blocks.put(bed,new BlockData(bed,"minecraft:red_bed",Map.of()));
            f.targetNavigation.put(FRUIT,nav);
            AutomationEngine engine=new AutomationEngine(List.of(f.module,new SleepModule()));engine.start(f.context);
            for(int i=0;i<180 && f.sleepUses==0;i++)f.engineTick(engine);
            assertEquals(1,f.sleepUses);assertTrue(f.clicked().isEmpty());
            assertEquals(nav==Navigation.Result.BLOCKED?1:100,f.travel.stream().filter(FRUIT::equals).count());
        }
    }
    @Test void skippedTargetDoesNotDisableOtherNearbyFruitAndItsCooldownSurvivesReset() {
        Fixture f=new Fixture();f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);
        assertEquals(WorkResult.State.IDLE,f.step().state());f.targetNavigation.clear();f.module.reset();
        assertFalse(f.module.hasNearbyWork(f.context));
        Pos other=new Pos(5,1,0);f.patch(FRUIT,other);f.fruit(other,7);
        // Changing this same patch is an explicit registration change; first
        // re-establish the old target's skip under the new registration.
        f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);f.run();f.targetNavigation.clear();f.module.reset();
        assertEquals(List.of(other),f.clicked());assertEquals(1,f.stored);
        assertFalse(f.module.hasNearbyWork(f.context),"the skipped first target and confirmed second target both remain excluded");
    }
    @Test void preUseSkipIsRevalidatedOnDeadlineDayTickRewindAndProfileChange() {
        for(int change=0;change<4;change++) {
            Fixture f=new Fixture();f.now=100;f.targetNavigation.put(FRUIT,Navigation.Result.BLOCKED);
            assertEquals(WorkResult.State.IDLE,f.step().state());assertFalse(f.module.hasNearbyWork(f.context));
            Context c=f.context;
            if(change==0)f.now+=1200;if(change==1)f.day+=24000;if(change==2)f.now=1;
            if(change==3){Profile p=new Profile();p.enabled.put(Feature.STARFRUIT,true);p.fruitPatches=List.copyOf(f.profile.fruitPatches);p.commodityStores.putAll(f.profile.commodityStores);c=new Context(f,f,f,p);}
            assertTrue(f.module.hasNearbyWork(c),"change="+change);assertTrue(f.submitted.isEmpty());
        }
    }
    @Test void starfruitOneShotVisitsTheActivatedPatchButDoesNotClaimUnreachableFruitWasHarvested() {
        Fixture f=new Fixture();Pos other=new Pos(8,1,0),unreachable=new Pos(50,1,0);
        f.patch(FRUIT,other,unreachable);f.fruit(other,7);f.fruit(unreachable,7);f.followArrivals=true;
        f.targetNavigation.put(unreachable,Navigation.Result.BLOCKED);
        AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.startOnce(f.context,Feature.STARFRUIT);
        for(int i=0;i<100&&engine.running();i++)f.engineTick(engine);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertEquals(List.of(FRUIT,other),f.clicked());
        assertEquals(2,f.stored);assertEquals(1,f.storeOpens());assertEquals(7,f.blocks.get(unreachable).number("age",-1));
        assertFalse(engine.status().contains("no eligible work remains"));assertTrue(engine.status().contains("registered patch fruit/storage pass"));
    }
    @Test void failedSideDepositDoesNotForceSleepingOrPauseOtherSafeWork() {
        Fixture f=new Fixture();f.travelYield=true;f.day=13000;f.profile.enabled.put(Feature.SLEEP,true);
        f.profile.enabled.put(Feature.DISPOSAL,true);f.fruit(FRUIT,6);
        Pos bed=TravelJob.DESTINATION;f.profile.pois.add(new Poi(bed,PoiKind.BED,"Bed",null));f.blocks.put(bed,new BlockData(bed,"minecraft:red_bed",Map.of()));
        int[] safeTicks={0};AutomationModule safe=new AutomationModule(){
            public Feature feature(){return Feature.DISPOSAL;}public int priority(){return 90;}
            public WorkResult tick(Context c){safeTicks[0]++;return WorkResult.idle();}public void reset(){}
        };
        AutomationEngine engine=new AutomationEngine(List.of(f.module,safe,new SleepModule()));engine.start(f.context);
        f.engineTick(engine);f.fruit(FRUIT,7);f.engineTick(engine);int before=safeTicks[0];
        // The original bed becomes reachable while the fruit's owned store rejects
        // opening. Returning directly to Sleep would now emit the forbidden use.
        f.targetNavigation.put(bed,Navigation.Result.ARRIVED);
        for(int i=0;i<100;i++) {
            engine.tick(f.context);
            if(f.busy()) {Action action=f.submitted.get(f.submitted.size()-1);f.complete(!(action instanceof Action.UseBlock use&&use.purpose()==Action.Use.OPEN_CONTAINER));}
            f.now++;
        }
        assertEquals(1,f.clicked().size());assertEquals(0,f.sleepUses);assertEquals(0,f.stored);
        assertTrue(safeTicks[0]>before);assertTrue(engine.running());assertNotEquals(AutomationEngine.State.PAUSED,engine.state());
    }
    private static final class TravelJob implements AutomationModule {
        static final Pos DESTINATION=new Pos(100,0,0);
        final Feature feature;int ticks,resets;boolean allowYield=true,unconsumed;
        TravelJob(Feature feature){this.feature=feature;}
        public Feature feature(){return feature;}public int priority(){return 60;}
        public WorkResult tick(Context c){ticks++;c.navigation().moveTo(DESTINATION,4,c);return WorkResult.busy("original travel");}
        public boolean canYieldForNearbyWork(Context c){return allowYield&&!unconsumed;}
        public void reset(){resets++;}
    }

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

    @Test void activatedPatchNeverScansUnknownChunksOrExpandsToUnregisteredForestFruit() {
        Fixture f=new Fixture();Pos other=new Pos(5,1,0),unloaded=new Pos(200,1,0),forest=new Pos(199,1,0);
        f.patch(FRUIT,other,unloaded);f.fruit(other,7);f.fruit(unloaded,7);f.fruit(forest,7);f.unloaded.add(unloaded);
        f.run();assertEquals(List.of(FRUIT,other),f.clicked());assertTrue(f.travel.stream().allMatch(p -> p.equals(FRUIT)||p.equals(other)||p.equals(STORE)));
        assertEquals(0,f.blocks.get(other).number("age",-1));assertFalse(f.blockReads.contains(unloaded));assertFalse(f.blockReads.contains(forest));
        assertEquals(0,f.scans);
        assertEquals(2,f.stored);assertEquals(List.of(2),f.fruitUsesAtStoreOpen);
    }

    @Test void activatedPatchIncludesItsInitiallyDistantFruitButNotNewlyRipeLoadedOrOtherPatchFruit() {
        Fixture f=new Fixture();Pos included=new Pos(5,1,0),far=new Pos(8,1,0),unripe=new Pos(4,1,1),
            unloaded=new Pos(4,1,-1),otherPatch=new Pos(5,1,1);
        f.patch(FRUIT,included,far,unripe,unloaded);f.fruit(included,7);f.fruit(far,7);f.fruit(unripe,6);
        f.fruit(unloaded,7);f.unloaded.add(unloaded);f.fruit(otherPatch,7);
        f.profile.fruitPatches=List.of(f.profile.fruitPatches.get(0),new FruitPatch("other","fruit",List.of(otherPatch)));
        f.followArrivals=true;assertEquals(WorkResult.State.BUSY,f.step().state());
        f.x=3.5;f.fruit(unripe,7);f.unloaded.clear();
        f.run();assertEquals(List.of(FRUIT,included,far),f.clicked());assertEquals(3,f.stored);assertEquals(List.of(3),f.fruitUsesAtStoreOpen);
        for(Pos excluded:List.of(unripe,unloaded,otherPatch)) {
            assertEquals(7,f.blocks.get(excluded).number("age",-1));assertFalse(f.travel.contains(excluded));
        }
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
            f.run();assertEquals(List.of(FRUIT),f.clicked(),change);assertEquals(1,f.stored);assertEquals(1,f.storeOpens());
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
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void fruitArrivingAfterTheWholeCohortWindowUsesOnlyTheExistingActualCleanupPath() {
        Fixture f=new Fixture();Pos second=new Pos(5,1,0);f.patch(FRUIT,second);f.fruit(second,7);f.deliver=false;f.run();
        assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(0,f.stored);assertEquals(0,f.storeOpens());
        f.x=100;f.put(9,f.product(2));int priorTravel=f.travel.size();f.run();
        assertEquals(2,f.stored);assertEquals(List.of(FRUIT,second),f.clicked());assertEquals(1,f.storeOpens());
        assertTrue(f.travel.subList(priorTravel,f.travel.size()).stream().allMatch(STORE::equals));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
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
        assertEquals(WorkResult.State.IDLE,f.step().state());assertTrue(f.clicked().isEmpty());
        assertTrue(f.travel.stream().allMatch(FRUIT::equals));
    }

    @Test void actualInteractionReachAndEachDistantApproachRemainBoundedBeforeOneFinalDeposit() {
        for(boolean missingReach:List.of(false,true)) {
            Fixture f=new Fixture();Pos second=new Pos(8,1,0),third=new Pos(9,1,0);
            f.patch(FRUIT,second,third);f.fruit(second,7);f.fruit(third,7);f.followArrivals=true;
            f.targetNavigation.put(FRUIT,Navigation.Result.MOVING);
            if(missingReach)f.uninteractable.add(second);else f.targetNavigation.put(second,Navigation.Result.MOVING);
            f.run();assertEquals(List.of(third),f.clicked());assertEquals(1,f.stored);assertEquals(1,f.storeOpens());
            assertEquals(100,f.travel.stream().filter(FRUIT::equals).count());
            assertTrue(f.travel.stream().anyMatch(second::equals));assertTrue(f.now<=220);
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
        final Map<Pos,BlockData> blocks=new HashMap<>();final Set<Pos> unloaded=new HashSet<>(),uninteractable=new HashSet<>();
        final Map<Pos,Navigation.Result> targetNavigation=new HashMap<>();
        final List<Pos> blockReads=new ArrayList<>(),travel=new ArrayList<>();
        final List<ItemSlot> inventory=new ArrayList<>();final List<Action> submitted=new ArrayList<>();
        final List<Integer> fruitUsesAtStoreOpen=new ArrayList<>();
        final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final ItemData tool=new ItemData("minecraft:iron_hoe",1,0,null,true,100),food=new ItemData("farmersdelight:fruit_salad",57,0,null,false,0);
        long now,day=5000,current;int selected,stored,stops,resets,scans,sleepUses;
        double x=.5;boolean open,externalBusy,changeAge=true,deliver=true,travelYield,sleeping,followArrivals;
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
        List<Pos> clicked(){return submitted.stream().filter(a->a instanceof Action.UseBlock use&&use.purpose()==Action.Use.FRUIT).map(a->((Action.UseBlock)a).pos()).toList();}
        long storeOpens(){return submitted.stream().filter(a->a instanceof Action.UseBlock use&&use.purpose()==Action.Use.OPEN_CONTAINER).count();}
        WorkResult step(){result=module.tick(context);now++;return result;}
        AutomationEngine engine(AutomationModule original){profile.enabled.put(original.feature(),true);AutomationEngine e=new AutomationEngine(List.of(original,module));e.start(context);return e;}
        void engineTick(AutomationEngine engine){engine.tick(context);if(busy()&&!externalBusy)complete(true);now++;}
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
                    else if(use.purpose()==Action.Use.SLEEP){sleepUses++;sleeping=true;}
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
        public PlayerState player(){return new PlayerState(x,0,.5,0,0,true,sleeping,20,20,selected,true,true);}
        public BlockData block(Pos pos){blockReads.add(pos);return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of()));}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos pos,int horizontal,int vertical){scans++;throw new AssertionError("Opportunistic fruit must not scan a tree");}
        public List<ItemSlot> inventory(){return inventory;}
        public MenuData menu(){
            if(!open)return new MenuData(0,0,inventory,cursor,false);
            List<ItemSlot> slots=new ArrayList<>();for(int index=0;index<27;index++)slots.add(new ItemSlot(index,-1,false,index==0&&stored>0?product(stored):ItemData.EMPTY));
            for(ItemSlot slot:inventory)slots.add(new ItemSlot(slot.inventoryIndex()+27,slot.inventoryIndex(),true,slot.item()));
            return new MenuData(12,1,slots,cursor,true);
        }
        public boolean mayPlace(int slot,ItemData item){return slot>=0&&slot<27;}
        public boolean canInteract(Pos pos,double reach){return !uninteractable.contains(pos)&&(!followArrivals||player().distance(pos)<=reach);}
        public boolean busy(){return externalBusy||current>0&&!outcomes.get(current).done();}
        public String pauseReason(){return actionFence;}
        public long submit(Action action){assertFalse(busy());assertNull(SafetyPolicy.rejection(action,context));
            if(action instanceof Action.UseBlock use&&use.purpose()==Action.Use.OPEN_CONTAINER)fruitUsesAtStoreOpen.add(clicked().size());
            submitted.add(action);outcomes.put(++current,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return current;}
        public ActionOutcome outcome(long ticket){return outcomes.get(ticket);}
        public void move(Movement movement){}public void stopMovement(){stops++;}public void cancel(){}
        public boolean canYieldTravel(Context c){return travelYield;}
        public Result moveTo(Pos target,double reach,Context c){
            travel.add(target);Result result=targetNavigation.getOrDefault(target,target.equals(TravelJob.DESTINATION)?Result.MOVING:navigationResult);
            // Detached navigation arrival, never a production position or velocity write.
            if(followArrivals&&result==Result.ARRIVED)x=target.x()+.5;
            return result;
        }
        public void reset(){resets++;}
    }
}
