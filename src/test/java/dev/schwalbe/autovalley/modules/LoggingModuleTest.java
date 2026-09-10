package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingModuleTest {
    @Test void completedLoggingStorageNavigationFailureRetainsItsBatchAndRetriesWithoutAnotherF8() {
        Fixture f=cleanupFixture(); f.add(LoggingRules.FIRE_LOG,2); f.blockedNavigation.add(f.woodPos);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        long waitingAt=f.ticks;int routes=f.loggingMoves,requests=f.actions.size();
        assertTrue(f.profile.loggingRunActive);assertTrue(f.profile.loggingRemainingPlots.isEmpty());
        assertTrue(f.profile.loggingReplantingPlots.isEmpty());assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(2,f.count(LoggingRules.FIRE_LOG));assertFalse(f.module.sleepSafeResourceWait(f.context));
        f.ticks=waitingAt+1199;assertEquals(WorkResult.State.RESOURCE_WAIT,f.step().state());
        assertEquals(routes,f.loggingMoves);assertEquals(requests,f.actions.size());
        f.blockedNavigation.clear();f.ticks++;
        assertEquals(AutomationModule.ResourceReadiness.READY,f.module.resourceReadiness(f.context));
        assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(2,f.stored(LoggingRules.FIRE_LOG));
        assertFalse(f.profile.loggingRunActive);assertEquals(11L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        assertEquals(0,f.chops);assertEquals(0,f.plants);
    }
    @Test void berriesDeliveryNavigationFailureIsAlsoRetriedWithoutFabricatedDelivery() {
        Fixture f=cleanupFixture();f.add(LoggingRules.BERRY,8);f.blockedNavigation.add(f.shippingPos);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(0,f.shipped(LoggingRules.BERRY));assertEquals(8,f.count(LoggingRules.BERRY));
        f.ticks+=1200;f.blockedNavigation.clear();
        assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(8,f.shipped(LoggingRules.BERRY));
        assertEquals(0,f.count(LoggingRules.BERRY));assertFalse(f.profile.loggingRunActive);
    }
    @Test void eightHoursOfStorageNavigationFailuresKeepNeighboursRunningAndRecoverWithoutAnotherF8() {
        Fixture f=cleanupFixture();f.continuous();f.add(LoggingRules.FIRE_LOG,3);f.blockedNavigation.add(f.woodPos);
        int[] other={0},sleeps={0};f.profile.enabled.put(Feature.CRYSTAL_COPY,true);f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.CRYSTAL_COPY,85,c->{other[0]++;return WorkResult.idle();}),
            resourceNeighbour(Feature.SLEEP,100,c->{sleeps[0]++;return WorkResult.idle();})));
        engine.start(f.context);engineUntil(f,engine,()->other[0]>0);
        assertTrue(engine.running(),engine.status());assertEquals(0,sleeps[0]);int routes=f.loggingMoves;
        long started=f.ticks;
        for(int retry=0;retry<480;retry++) {
            f.ticks+=1201;int previous=other[0];engine.tick(f.context);
            engineUntil(f,engine,()->other[0]>previous);
            assertTrue(engine.running(),engine.status());assertTrue(f.profile.loggingRunActive);
            assertEquals(0,f.stored(LoggingRules.FIRE_LOG));assertEquals(0,sleeps[0]);
        }
        assertTrue(f.ticks-started>=8*60*60*20L,"At least eight simulated hours at 20 TPS");
        assertEquals(routes+480,f.loggingMoves,"Only one fresh route attempt per wait, never a busy loop");
        f.blockedNavigation.clear();f.ticks+=1201;
        engineUntil(f,engine,()->!f.profile.loggingRunActive);
        assertTrue(engine.running(),engine.status());assertEquals(3,f.stored(LoggingRules.FIRE_LOG));
    }
    @Test void storageNativeFailureCannotMasqueradeAsTheUnsentNavigationWait() {
        Fixture f=cleanupFixture();f.add(LoggingRules.FIRE_LOG,2);
        f.until(()->f.pending instanceof Action.UseBlock);int requests=f.actions.size();
        f.reject("Registered storage cannot be reached");
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context));
        f.ticks+=5000;assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(requests,f.actions.size());assertTrue(f.profile.loggingRunActive);
    }
    @Test void uncertainLaunchOrUnconsumedNavigatorDoorReceiptCannotBeResetIntoAWait() {
        for(String cause:List.of("jump","safety","invalid start","door pending","door failed","door succeeded")) {
            Fixture f=cleanupFixture();f.add(LoggingRules.FIRE_LOG,2);f.blockedNavigation.add(f.woodPos);
            switch(cause) {
                case "jump" -> f.navigationFailure=Navigation.Failure.JUMP_UNCERTAIN;
                case "safety" -> f.navigationFailure=Navigation.Failure.SAFETY;
                case "invalid start" -> f.navigationFailure=Navigation.Failure.INVALID_START;
                case "door pending" -> f.navigationOutcome=new ActionOutcome(ActionOutcome.State.PENDING,"unconsumed door");
                case "door failed" -> f.navigationOutcome=new ActionOutcome(ActionOutcome.State.FAILED,"unconsumed door");
                case "door succeeded" -> f.navigationOutcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"unconsumed door");
            }
            assertEquals(WorkResult.State.BLOCKED,f.finish().state(),cause);
            assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context));
            assertTrue(f.profile.loggingRunActive);assertTrue(f.actions.isEmpty());
        }
        Fixture proved=cleanupFixture();proved.add(LoggingRules.FIRE_LOG,2);proved.blockedNavigation.add(proved.woodPos);
        proved.navigationFailure=Navigation.Failure.SAFETY;proved.safeNavigationFailure=true;
        assertEquals(WorkResult.State.RESOURCE_WAIT,proved.finish().state());assertTrue(proved.actions.isEmpty());
    }
    @Test void fullStorageMustReallyCloseBeforeAnotherRoutineCanUseItsRetainedWait() {
        for(boolean succeeds:List.of(false,true)) {
            Fixture f=cleanupFixture();f.add(LoggingRules.FIRE_LOG,2);Arrays.fill(f.chests.get(f.woodPos),item(LoggingRules.FIRE_LOG,64));
            f.until(()->f.pending instanceof Action.CloseContainer);int sent=f.actions.size();
            assertTrue(f.menu().container());assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context));
            for(int i=0;i<20;i++) {f.ticks++;assertEquals(WorkResult.State.BUSY,f.step().state());}
            assertEquals(sent,f.actions.size());assertEquals(2,f.count(LoggingRules.FIRE_LOG));
            if(succeeds)f.advance();else f.reject("late close not confirmed");
            assertEquals(succeeds?WorkResult.State.RESOURCE_WAIT:WorkResult.State.BLOCKED,f.step().state());
            assertEquals(succeeds?AutomationModule.ResourceReadiness.WAITING:AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context));
            assertTrue(f.profile.loggingRunActive);assertTrue(f.profile.nextEligibleDay.isEmpty());assertEquals(sent,f.actions.size());
        }
    }
    @Test void missingShippingRegistrationRetainsProductsAndWaitsWithoutSendingAnything() {
        Fixture f=cleanupFixture();f.add(LoggingRules.BERRY,8);f.profile.pois.removeIf(p->p.kind()==PoiKind.SHIPPING_BIN);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());assertTrue(f.actions.isEmpty());
        assertEquals(8,f.count(LoggingRules.BERRY));assertTrue(f.profile.loggingRunActive);
        f.ticks+=1200;assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());assertTrue(f.actions.isEmpty());
        assertEquals(8,f.count(LoggingRules.BERRY));
    }
    @Test void aMissingAxeWaitsAndResumesOnlyAfterTheCorrectUsableToolIsPresent() {
        Fixture f=new Fixture(1);f.inventory[2]=ItemData.EMPTY;
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());assertTrue(f.actions.isEmpty());
        assertTrue(f.profile.loggingRunActive);assertEquals(1,f.profile.loggingRemainingPlots.size());
        f.inventory[2]=item("minecraft:diamond_pickaxe",1);f.ticks+=1200;
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());assertTrue(f.actions.isEmpty());
        f.inventory[2]=item(LoggingRules.AXE,1);f.ticks+=1200;
        assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(2,f.chops);assertEquals(4,f.plants);
    }
    @Test void unsettledCleanupWithoutBorrowedItemsDefersWithoutPrematureTrashOrCraft() {
        Fixture f=cleanupFixture();f.add(LoggingRules.TWIG,3);enterCleanup(f);WorkResult result=null;
        for(int i=1;i<=400;i++) {
            f.ticks++;f.inventory[10]=item(LoggingRules.LOG,i%2==0?1:2);result=f.step();
        }
        assertEquals(WorkResult.State.RESOURCE_WAIT,result.state());assertTrue(f.actions.isEmpty());
        assertTrue(f.profile.loggingRunActive);assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.ticks+=1200;assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(3,f.trashed);
    }
    @Test void aGrantedNavigationWaitStillStopsAtGenuineInventoryOrAuthorityUncertainty() {
        for(String change:List.of("cursor","menu","busy","fence","airborne","lease","plots","remaining","due","facility")) {
            Fixture f=cleanupFixture();f.add(LoggingRules.FIRE_LOG,2);f.blockedNavigation.add(f.woodPos);
            assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());int sent=f.actions.size();
            switch(change) {
                case "cursor" -> f.cursor=item(LoggingRules.FIRE_LOG,1);
                case "menu" -> {f.opened=f.woodPos;f.containerId=1;}
                case "busy" -> f.forcedNativeBusy=true;
                case "fence" -> f.nativeFence="real pending receipt";
                case "airborne" -> f.grounded=false;
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item("minecraft:torch",5),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "plots" -> f.profile.loggingPlots.clear();
                case "remaining" -> f.profile.loggingRemainingPlots.add(f.profile.loggingPlots.get(0).corner());
                case "due" -> f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,55L);
                case "facility" -> f.profile.pois.removeIf(p->p.kind()==PoiKind.WOOD_CHEST);
            }
            assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context),change);
            assertEquals(WorkResult.State.BLOCKED,f.step().state(),change);assertEquals(sent,f.actions.size(),change);
        }
    }
    @Test void manualStopRevokesNavigationWaitWithoutErasingTheDurableCleanupObligation() {
        Fixture f=cleanupFixture();f.add(LoggingRules.FIRE_LOG,2);f.blockedNavigation.add(f.woodPos);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());f.module.reset();
        assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context));
        assertTrue(f.profile.loggingRunActive);assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.blockedNavigation.clear();assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(2,f.stored(LoggingRules.FIRE_LOG));
    }
    @Test void residualThreeDimensionalCoastCannotSelectOrStartMiningUntilTwoQuietTicks() {
        Fixture f=atMiningArrival(false);int routes=f.loggingMoves;
        for(double[] pose:List.of(new double[]{.46,64,.5},new double[]{.43,64,.53},new double[]{.42,64.01,.53},new double[]{.419,64.006,.531})) {
            f.playerX=pose[0];f.playerY=pose[1];f.playerZ=pose[2];f.ticks++;
            assertTrue(f.step().message().contains("이동 잔여속도/정지 확인 대기"));assertTrue(f.actions.isEmpty());
        }
        assertEquals(routes,f.loggingMoves,"Stopping visible coast must not re-arm navigation every tick");
        f.ticks++;f.step();assertTrue(f.actions.isEmpty());
        f.ticks++;f.step();assertInstanceOf(Action.SelectHotbar.class,f.pending);
        f.advance();f.step();assertInstanceOf(Action.ChopTree.class,f.pending);
    }
    @Test void stationaryAcknowledgedStrokesDoNotPayTwoNewFixedTicksPerChop() {
        Fixture f=new Fixture(1);f.strokesPerTree=6;f.until(()->f.pending instanceof Action.ChopTree);
        for(int i=0;i<4;i++) {
            int sent=f.actions.size();f.advance();f.step();
            assertInstanceOf(Action.ChopTree.class,f.pending);assertEquals(sent+1,f.actions.size());
        }
        assertEquals(4,f.chops);assertEquals(5,f.actions.stream().filter(a->a instanceof Action.ChopTree).count());
    }
    @Test void MotionObservedDuringPendingNativeAckRevokesTheNextStrokeWithoutResendingPending() {
        Fixture f=new Fixture(1);f.strokesPerTree=6;f.until(()->f.pending instanceof Action.ChopTree);int sent=f.actions.size();
        f.playerZ+=.04;f.ticks++;f.step();assertEquals(sent,f.actions.size());assertInstanceOf(Action.ChopTree.class,f.pending);
        f.advance();f.step();assertNull(f.pending);assertEquals(sent,f.actions.size());assertEquals(1,f.chops);
        f.ticks++;f.step();assertInstanceOf(Action.ChopTree.class,f.pending);assertEquals(sent+1,f.actions.size());
    }
    @Test void leafRemovalAlsoWaitsForActualQuietPoseBeforeSelectingOrBreaking() {
        Fixture f=atMiningArrival(true);int visits=f.leafStanceMoves;
        for(int i=0;i<5;i++){f.playerX+=.01;f.ticks++;f.step();assertTrue(f.actions.isEmpty());}
        assertEquals(visits,f.leafStanceMoves,"A stopped arrival is observed, not repeatedly steered");
        f.ticks++;f.step();assertTrue(f.actions.isEmpty());f.ticks++;f.step();assertInstanceOf(Action.SelectHotbar.class,f.pending);
        f.advance();f.step();assertInstanceOf(Action.ClearLoggingLeaf.class,f.pending);
    }
    @Test void lostActualSightDuringQuietWaitReturnsToNavigationBeforeAnyStart() {
        Fixture f=atMiningArrival(false);int routes=f.loggingMoves;
        f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions());f.holdChopMovement=true;
        for(int i=0;i<40 && f.loggingMoves==routes;i++){f.ticks++;f.step();}
        assertTrue(f.loggingMoves>routes);assertTrue(f.actions.isEmpty());
        for(int i=0;i<5;i++){f.ticks++;f.step();assertTrue(f.actions.isEmpty());}
        f.holdChopMovement=false;f.movementExposesChop=true;
        f.until(()->f.pending instanceof Action.ChopTree);assertEquals(0,f.chops);
    }
    @Test void duplicatePollsAndDateChangesCannotSupplyQuietTicksAndTickGapsResetThem() {
        Fixture f=atMiningArrival(true);f.ticks++;f.step();assertTrue(f.actions.isEmpty());
        for(int i=0;i<8;i++){f.day++;f.step();assertTrue(f.actions.isEmpty());}
        f.ticks+=2;f.step();assertTrue(f.actions.isEmpty());f.ticks++;f.step();assertTrue(f.actions.isEmpty());
        f.ticks++;f.step();assertInstanceOf(Action.SelectHotbar.class,f.pending);
    }
    @Test void airborneNonfiniteAndResetCannotRetainPriorQuietApproval() {
        for(String changed:List.of("airborne","nonfinite","reset","clock")) {
            Fixture f=atMiningArrival(false);f.selected=2;f.ticks++;f.step();assertTrue(f.actions.isEmpty());
            switch(changed) {
                case "airborne" -> f.grounded=false;
                case "nonfinite" -> f.playerY=Double.NaN;
                case "reset" -> f.module.reset();
                case "clock" -> f.ticks-=10;
            }
            f.step();assertTrue(f.actions.isEmpty(),changed);f.grounded=true;f.playerY=64;
            if(f.ticks<0)f.ticks=0;
            f.until(()->f.pending instanceof Action.ChopTree);assertEquals(0,f.chops,changed);
        }
    }
    @Test void continuouslyMovingAtAnArrivedTargetDefersUnsentAfterOneHundredTicks() {
        Fixture f=atMiningArrival(false);long start=f.ticks;WorkResult result=null;
        for(int i=0;i<101;i++){
            f.playerX+=i%2==0?.01:-.01;f.ticks++;result=f.step();if(result.state()!=WorkResult.State.BUSY)break;
            for(int duplicate=0;duplicate<3;duplicate++)assertEquals(WorkResult.State.BUSY,f.step().state());
        }
        assertNotNull(result);assertEquals(WorkResult.State.RESOURCE_WAIT,result.state());assertTrue(result.message().contains("이동 잔여속도/정지 확인 대기"));
        assertEquals(100,f.ticks-start);assertTrue(f.actions.isEmpty());assertTrue(f.profile.loggingRunActive);
        assertEquals(1,f.profile.loggingRemainingPlots.size());assertTrue(f.profile.nextEligibleDay.isEmpty());assertNull(f.nativeFence);
        f.ticks+=1200;assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(2,f.chops);assertEquals(4,f.plants);
    }
    private static Fixture atMiningArrival(boolean leaf) {
        Fixture f=leaf?leafFixture(1):new Fixture(1);f.until(()->f.loggingMoves>0);
        assertTrue(f.actions.isEmpty());assertNull(f.pending);return f;
    }

    @Test void explicitLeafPermissionClearsOneConfirmedObstructionThenRescansTheSameUnfinishedTree() {
        Fixture f=leafFixture(1); Pos leaf=f.nextLeaf(f.profile.loggingPlots.get(0).corner());
        f.until(() -> f.pending instanceof Action.ClearLoggingLeaf);
        Action.ClearLoggingLeaf action=(Action.ClearLoggingLeaf)f.pending;
        assertEquals(leaf,action.pos()); assertEquals(f.profile.loggingPlots.get(0).corner(),action.stump());
        assertEquals(2,f.selected); assertTrue(f.chopRays>0); assertTrue(f.leafRays>0);
        assertEquals(0,f.chops); assertEquals(0,f.plants); assertEquals(0,f.leavesCleared);
        assertEquals(List.of(action.stump()),f.profile.loggingRemainingPlots); assertTrue(f.profile.nextEligibleDay.isEmpty());
        int submitted=f.actions.size();
        for(int i=0;i<40;i++) { f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state()); }
        assertEquals(submitted,f.actions.size(),"One in-flight leaf cannot cause another leaf, chop or planting");
        f.advance(); f.step();
        assertEquals(1,f.leavesCleared); assertEquals(0,f.chops); assertEquals(0,f.plants);
        assertTrue(f.profile.loggingRunActive); assertEquals(List.of(action.stump()),f.profile.loggingRemainingPlots);
        assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops); assertEquals(4,f.plants);
        assertEquals(1,f.actions.stream().filter(a->a instanceof Action.ClearLoggingLeaf).count());
    }

    @Test void normalLoggingVisibilityTakesPriorityOverLeafRemovalEvenWhenExplicitlyEnabled() {
        Fixture f=leafFixture(1); f.occludedChopping.remove(f.profile.loggingPlots.get(0).plantingPositions().get(3));
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertEquals(0,f.leafRays); assertEquals(0,f.leavesCleared);
        assertTrue(f.actions.stream().noneMatch(a->a instanceof Action.ClearLoggingLeaf));
    }

    @Test void reachingTheNearLeafDoesNotReplaceReachingTheVerifiedStumpVisibilityStance() {
        Fixture f=leafFixture(1);Pos base=f.profile.loggingPlots.get(0).corner(),leaf=f.nextLeaf(base);
        f.playerX=-4;f.requireLeafStance=true;f.holdLeafStanceMovement=true;f.leafProofStance=new Pos(-2,64,0);
        assertTrue(f.player().distance(leaf)<4,"The near leaf is already in ordinary reach");
        assertTrue(f.player().distance(base)>4,"The tree base is still outside reach");
        assertTrue(f.canInteract(leaf,4));assertNull(f.loggingLeafObstruction(base,4));
        f.until(() -> f.leafStanceMoves>0);
        assertEquals(f.leafProofStance,f.lastLeafStance);assertEquals(.1,f.leafStanceTolerance);
        assertTrue(f.actions.isEmpty(),"Selecting an axe or breaking a leaf must wait for the stance arrival");
        for(int i=0;i<20;i++) { f.ticks++;assertEquals(WorkResult.State.BUSY,f.step().state()); }
        assertTrue(f.actions.isEmpty());assertFalse(f.leafStanceArrived);
        f.holdLeafStanceMovement=false;f.until(() -> f.pending instanceof Action.ClearLoggingLeaf);
        assertTrue(f.leafStanceArrived);assertTrue(f.player().distance(base)<4);
        assertEquals(leaf,((Action.ClearLoggingLeaf)f.pending).pos());assertEquals(base,((Action.ClearLoggingLeaf)f.pending).stump());
        assertFalse(f.loggingTargets.contains(leaf),"Leaf-radius interaction navigation must not substitute for literal stance travel");
        assertEquals(0,f.leavesCleared);assertEquals(0,f.chops);
    }

    @Test void anUnreachableLeafStanceDoesNotSendALeafUseEvenWhenTheLeafItselfIsVisible() {
        Fixture f=leafFixture(1);f.blockLeafStanceMovement=true;
        WorkResult result=f.finish();
        assertEquals(WorkResult.State.RESOURCE_WAIT,result.state(),result.message());
        assertFalse(f.module.sleepSafeResourceWait(f.context));
        assertTrue(f.leafStanceMoves>0);assertTrue(f.actions.isEmpty());assertEquals(0,f.leavesCleared);assertEquals(0,f.chops);
        assertTrue(f.profile.loggingRunActive);assertEquals(1,f.profile.loggingRemainingPlots.size());
    }

    @Test void actualEyeMismatchAtTheFirstLeafStanceTriesTheNextCandidateBeforeAnyResourceWait() {
        Fixture f=leafFixture(1);Pos first=new Pos(0,64,0),second=new Pos(1,64,0);
        f.leafProofStances.addAll(List.of(first,second));f.rejectedActualLeafStances.add(first);
        List<String> messages=untilLeafActionWithoutWait(f);
        assertTrue(messages.stream().anyMatch(s -> s.contains("실제 도착 위치")));
        assertEquals(List.of(first,second),f.leafStanceVisits.stream().distinct().toList());
        assertEquals(second,f.lastLeafStance);assertEquals(1,f.actions.stream().filter(a -> a instanceof Action.SelectHotbar).count());
        assertEquals(0,f.leavesCleared);assertEquals(0,f.chops);assertEquals(0,f.plants);
        assertEquals(1,f.profile.loggingRemainingPlots.size());assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void changedPredictedLeafEndpointAdvancesItsCursorInsteadOfAbandoningAllOtherCandidates() {
        Fixture f=leafFixture(1);Pos first=new Pos(0,64,0),second=new Pos(1,64,0);
        f.leafProofStances.addAll(List.of(first,second));f.holdLeafStanceMovement=true;
        f.until(() -> f.leafStanceMoves>0);assertEquals(first,f.lastLeafStance);assertTrue(f.actions.isEmpty());
        f.rejectedPredictedLeafStances.add(first);f.holdLeafStanceMovement=false;
        List<String> messages=untilLeafActionWithoutWait(f);
        assertTrue(messages.stream().anyMatch(s -> s.contains("예상 잎 접근 위치의 지형·시야")));
        assertEquals(List.of(first,second),f.leafStanceVisits.stream().distinct().toList());
        assertEquals(second,f.lastLeafStance);assertEquals(0,f.leavesCleared);assertEquals(0,f.chops);
    }

    @Test void protectedFirstLeafCandidateCannotHideAnotherAuthorizedLeafStance() {
        Fixture f=leafFixture(2);Pos first=new Pos(0,64,0),second=new Pos(1,64,0),base=f.profile.loggingPlots.get(0).corner();
        List<Pos> leaves=List.copyOf(f.leafObstructions.get(base));
        f.leafProofStances.addAll(List.of(first,second));f.leafAtStance.put(first,leaves.get(0));f.leafAtStance.put(second,leaves.get(1));
        f.profile.pois.add(new Poi(leaves.get(0),PoiKind.STORAGE_CANDIDATE,"protected",null));
        List<String> messages=untilLeafActionWithoutWait(f);
        assertTrue(messages.stream().anyMatch(s -> s.contains("제거 권한이 바뀜")));
        assertEquals(List.of(second),f.leafStanceVisits.stream().distinct().toList());
        assertEquals(leaves.get(1),((Action.ClearLoggingLeaf)f.pending).pos());
        assertTrue(f.actions.stream().noneMatch(a -> a instanceof Action.ClearLoggingLeaf leaf && leaf.pos().equals(leaves.get(0))));
        assertEquals(0,f.leavesCleared);assertEquals(1,f.profile.loggingRemainingPlots.size());
    }

    @Test void allRejectedLeafStancesExhaustOnceThenKeepTheOrdinaryBoundedVisibilityWait() {
        Fixture f=leafFixture(1);Pos first=new Pos(0,64,0),second=new Pos(1,64,0);
        f.leafProofStances.addAll(List.of(first,second));f.rejectedActualLeafStances.addAll(List.of(first,second));
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(List.of(first,second),f.leafStanceVisits);assertTrue(f.actions.isEmpty());
        int rays=f.leafRays;
        for(int n=0;n<100;n++){f.advance();assertEquals(WorkResult.State.RESOURCE_WAIT,f.step().state());}
        assertEquals(rays,f.leafRays,"A failed finite sweep cannot restart its first stance every tick");
        assertEquals(0,f.leavesCleared);assertEquals(0,f.chops);assertEquals(0,f.plants);
        assertEquals(1,f.profile.loggingRemainingPlots.size());assertTrue(f.profile.loggingReplantingPlots.isEmpty());
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    private static List<String> untilLeafActionWithoutWait(Fixture f) {
        List<String> messages=new ArrayList<>();
        for(int n=0;n<4000 && !(f.pending instanceof Action.ClearLoggingLeaf);n++) {
            WorkResult result=f.step();messages.add(result.message());assertEquals(WorkResult.State.BUSY,result.state(),result.message());
            if(!(f.pending instanceof Action.ClearLoggingLeaf))f.advance();
        }
        assertInstanceOf(Action.ClearLoggingLeaf.class,f.pending);return messages;
    }

    @Test void defaultOffNeverSearchesOrClearsLeavesAndPreservesItsVisibilityWait() {
        Fixture f=leafFixture(1); f.profile.loggingClearObstructingLeaves=false;
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(0,f.leafRays); assertEquals(0,f.leavesCleared); assertTrue(f.actions.isEmpty());
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingRunActive);
    }

    @Test void twoObstructionsAreClearedAsSeparateNativeAcknowledgementsWithFreshVisibilityBetweenThem() {
        Fixture f=leafFixture(2); f.strokesPerTree=24;
        f.until(() -> f.pending instanceof Action.ClearLoggingLeaf); Pos first=((Action.ClearLoggingLeaf)f.pending).pos();
        f.advance(); int rays=f.chopRays;
        f.until(() -> f.pending instanceof Action.ClearLoggingLeaf); Pos second=((Action.ClearLoggingLeaf)f.pending).pos();
        assertNotEquals(first,second); assertEquals(1,f.leavesCleared); assertEquals(0,f.chops); assertTrue(f.chopRays>rays);
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingReplantingPlots.isEmpty());
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.leavesCleared);
        assertEquals(24,f.chops,"Leaf acknowledgements must not substitute for any of the 24 native chops"); assertEquals(4,f.plants);
    }

    @Test void failedOrUnquantifiedLeafAcknowledgementDoesNotCutOrForgetTheTree() {
        for(int count:List.of(-1,0,2)) {
            Fixture f=leafFixture(1); f.until(() -> f.pending instanceof Action.ClearLoggingLeaf);
            int sent=f.actions.size();
            if(count<0) f.reject("native leaf removal not confirmed");
            else { f.pending=null; f.outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"invalid count",count); }
            assertEquals(WorkResult.State.BLOCKED,f.step().state());
            for(int i=0;i<3;i++) assertEquals(WorkResult.State.BLOCKED,f.step().state());
            assertEquals(sent,f.actions.size()); assertEquals(0,f.chops); assertEquals(0,f.plants);
            assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
            assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void leafAuthorityIsRecheckedAfterAxeSelectionBeforeAnyRemoval() {
        for(String changed:List.of("disabled","first obstruction gone","stone","sapling","farm","poi","whole tree rejected")) {
            Fixture f=leafFixture(1); f.until(() -> f.pending instanceof Action.SelectHotbar); f.advance();
            Pos leaf=f.nextLeaf(f.profile.loggingPlots.get(0).corner());
            switch(changed) {
                case "disabled" -> f.profile.loggingClearObstructingLeaves=false;
                case "first obstruction gone" -> f.actualLeafObstructionAvailable=false;
                case "stone" -> f.blocks.put(leaf,"minecraft:stone");
                case "sapling" -> f.blocks.put(leaf,LoggingRules.SAPLING);
                case "farm" -> f.profile.farms.add(new Farm("protected",leaf,leaf));
                case "poi" -> f.profile.pois.add(new Poi(leaf,PoiKind.STORAGE_CANDIDATE,"protected",null));
                case "whole tree rejected" -> f.treeRejection="tree reaches a protected structure";
                default -> throw new AssertionError(changed);
            }
            WorkResult result=assertDoesNotThrow(f::finish,changed);
            assertTrue(result.state()==WorkResult.State.RESOURCE_WAIT || result.state()==WorkResult.State.BLOCKED,changed+": "+result);
            assertEquals(0,f.leavesCleared,changed); assertEquals(0,f.chops,changed); assertEquals(0,f.plants,changed);
            assertTrue(f.actions.stream().noneMatch(a->a instanceof Action.ClearLoggingLeaf),changed);
            assertEquals(1,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingRunActive);
        }
    }

    @Test void anOutsideOrNonSpruceLeafCandidateCannotBecomeAnAction() {
        for(String candidate:List.of("outside","normal block","sapling","other leaves")) {
            Fixture f=leafFixture(1); Pos base=f.profile.loggingPlots.get(0).corner(),leaf=f.nextLeaf(base);
            if(candidate.equals("outside")) { f.leafObstructions.get(base).clear(); leaf=base.offset(-3,1,0); f.leafObstructions.get(base).add(leaf); }
            f.blocks.put(leaf,switch(candidate) { case "normal block" -> "minecraft:stone"; case "sapling" -> LoggingRules.SAPLING;
                case "other leaves" -> "minecraft:oak_leaves"; default -> LoggingLeafRules.LEAVES; });
            assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state(),candidate);
            assertEquals(0,f.leavesCleared); assertEquals(0,f.chops); assertTrue(f.actions.isEmpty());
        }
    }

    @Test void unsafeLeafSearchBoundaryCannotSendAnyActionOrForgetTheBatch() {
        for(String unsafe:List.of("busy","fence","airborne","cursor","container","output")) {
            Fixture f=leafFixture(1); f.until(() -> f.leafRays>0);
            switch(unsafe) {
                case "busy" -> f.forcedNativeBusy=true;
                case "fence" -> f.nativeFence="unconfirmed action";
                case "airborne" -> f.grounded=false;
                case "cursor" -> f.cursor=item("minecraft:diamond",1);
                case "container" -> { f.opened=f.woodPos;f.containerId=10; }
                case "output" -> {
                    String id="11111111-1111-1111-1111-111111111111";
                    f.profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(30,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
                }
                default -> throw new AssertionError(unsafe);
            }
            int sent=f.actions.size();
            for(int i=0;i<5;i++) { f.ticks++; f.step(); }
            assertEquals(sent,f.actions.size(),unsafe); assertEquals(0,f.leavesCleared); assertEquals(0,f.chops);
            assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
        }
    }

    @Test void restartingAfterAConfirmedLeafRemovalDoesNotReplayItOrCompleteTheTree() {
        Fixture f=leafFixture(1); f.until(() -> f.pending instanceof Action.ClearLoggingLeaf);f.advance();
        assertEquals(1,f.leavesCleared);assertEquals(0,f.chops);f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state());assertEquals(1,f.leavesCleared);assertEquals(2,f.chops);assertEquals(4,f.plants);
        assertEquals(1,f.actions.stream().filter(a->a instanceof Action.ClearLoggingLeaf).count());
    }

    @Test void oneTreeCannotConsumeAnUnboundedNumberOfLeafRemovalActions() {
        Fixture f=leafFixture(LoggingLeafRules.MAX_CLEARS_PER_PLOT+1);
        WorkResult result=null;
        for(int i=0;i<20000;i++) { result=f.step();if(result.state()!=WorkResult.State.BUSY)break;f.advance(); }
        assertNotNull(result);assertEquals(WorkResult.State.RESOURCE_WAIT,result.state(),result.message());
        assertEquals(LoggingLeafRules.MAX_CLEARS_PER_PLOT,f.leavesCleared);assertEquals(0,f.chops);assertEquals(0,f.plants);
        assertEquals(1,f.profile.loggingRemainingPlots.size());assertTrue(f.profile.loggingRunActive);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    private static Fixture leafFixture(int count) {
        Fixture f=new Fixture(1);f.profile.loggingClearObstructingLeaves=true;
        LoggingPlot plot=f.profile.loggingPlots.get(0);f.occludedChopping.addAll(plot.plantingPositions());f.blockAllChopRays=true;
        ArrayDeque<Pos> leaves=new ArrayDeque<>();
        for(int y=0;y<=2 && leaves.size()<count;y++) for(int x=-1;x<=2 && leaves.size()<count;x++) for(int z=-1;z<=2 && leaves.size()<count;z++) {
            Pos leaf=plot.corner().offset(x,y,z);if(LoggingLeafRules.inShell(plot,leaf)) { leaves.add(leaf);f.blocks.put(leaf,LoggingLeafRules.LEAVES); }
        }
        assertEquals(count,leaves.size());f.leafObstructions.put(plot.corner(),leaves);return f;
    }

    @Test void anOccludedFirstBaseDoesNotHideAnotherVisibleBaseOfTheSameRegisteredTree() {
        Fixture f=new Fixture(1); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.occludedChopping.add(bases.get(0));
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertEquals(bases.get(1),((Action.ChopTree)f.pending).pos());
        assertFalse(f.loggingTargets.contains(bases.get(0)));
    }

    @Test void fullyOccludedTreeWaitsWithoutLaunchingWorldWideTerrainSearchOrDiscardingPlots() {
        Fixture f=new Fixture(2);
        f.profile.loggingPlots.forEach(plot -> f.occludedChopping.addAll(plot.plantingPositions())); f.blockAllChopRays=true;
        WorkResult result=f.finish();
        assertEquals(WorkResult.State.RESOURCE_WAIT,result.state()); assertTrue(result.message().contains("시야") || result.message().contains("볼 수"));
        assertEquals(0,f.moves); assertTrue(f.actions.isEmpty()); assertTrue(f.chopRays>0); assertTrue(f.chopRays<16000);
        assertTrue(f.profile.loggingPlots.stream().allMatch(plot -> plot.plantingPositions().stream().anyMatch(f.chopRayTargets::contains)));
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void anInvisibleFirstPlotCannotPreventTheOtherFourRegisteredTreesBeingProcessed() {
        Fixture f=new Fixture(5); LoggingPlot first=f.profile.loggingPlots.get(0);
        f.occludedChopping.addAll(first.plantingPositions()); f.blockAllChopRays=true;
        WorkResult result=f.finish();
        assertEquals(WorkResult.State.RESOURCE_WAIT,result.state(),result.message());
        assertEquals(8,f.chops); assertEquals(16,f.plants);
        assertFalse(f.actions.stream().filter(a -> a instanceof Action.ChopTree)
            .map(a -> ((Action.ChopTree)a).pos()).anyMatch(first.plantingPositions()::contains));
        assertEquals(List.of(first.corner()),f.profile.loggingRemainingPlots);
        assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.nextEligibleDay.isEmpty()); assertEquals(0,f.trashed); assertEquals(8,f.crafted);
        assertEquals(16,f.stored(LoggingRules.FIRE_LOG)); assertEquals(4,f.shipped(LoggingRules.BERRY));
        assertEquals(16,f.count(LoggingRules.SAPLING)); assertEquals(12,f.count(LoggingRules.TWIG));
    }

    @Test void hiddenLastPlotStoresItsHeldHaulWithoutFinishingOrDiscardingAnyFuturePlantingMaterials() {
        for(boolean continuous:List.of(false,true)) {
            Fixture f=partialCleanupFixture(); if(continuous) f.continuous();
            List<Pos> remaining=List.copyOf(f.profile.loggingRemainingPlots);
            Map<String,Long> due=Map.copyOf(f.profile.nextEligibleDay);
            WorkResult result=f.finish();
            assertEquals(WorkResult.State.RESOURCE_WAIT,result.state(),result.message());
            assertEquals(2,f.crafted); assertEquals(5,f.stored(LoggingRules.FIRE_LOG));
            assertEquals(2,f.stored(LoggingRules.LOG)); assertEquals(5,f.shipped(LoggingRules.BERRY));
            assertEquals(0,f.count(LoggingRules.LOG)); assertEquals(0,f.count(LoggingRules.FIRE_LOG)); assertEquals(0,f.count(LoggingRules.BERRY));
            assertEquals(7,f.count(LoggingRules.SAPLING)); assertEquals(13,f.count(LoggingRules.TWIG)); assertEquals(0,f.trashed);
            assertEquals(remaining,f.profile.loggingRemainingPlots); assertTrue(f.profile.loggingReplantingPlots.isEmpty());
            assertTrue(f.profile.loggingRunActive); assertEquals(due,f.profile.nextEligibleDay);
            assertEquals(0,f.chops); assertEquals(0,f.plants); assertFalse(f.menu().container()); assertTrue(f.cursor.empty());
            assertTrue(f.actions.stream().noneMatch(a -> a instanceof Action.TrashLogging));
            int sent=f.actions.size(); f.ticks+=1200;
            assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
            assertEquals(sent,f.actions.size(),"Retained saplings and twigs must not cause an endless cleanup loop");
        }
    }

    @Test void seedShortPartialCleanupPreservesTheExactReplantFifoAndAllowsLaterFourSeedRepair() {
        Fixture f=resourceFixture(); f.profile.loggingSaplingReserve=0;
        f.add(LoggingRules.LOG,12); f.add(LoggingRules.FIRE_LOG,2); f.add(LoggingRules.BERRY,3); f.add(LoggingRules.TWIG,9);
        f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,37L);
        List<Pos> remaining=List.copyOf(f.profile.loggingRemainingPlots),replanting=List.copyOf(f.profile.loggingReplantingPlots);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(2,f.crafted); assertEquals(4,f.stored(LoggingRules.FIRE_LOG)); assertEquals(3,f.shipped(LoggingRules.BERRY));
        assertEquals(2,f.count(LoggingRules.SAPLING)); assertEquals(9,f.count(LoggingRules.TWIG)); assertEquals(0,f.trashed);
        assertEquals(remaining,f.profile.loggingRemainingPlots); assertEquals(replanting,f.profile.loggingReplantingPlots);
        assertEquals(37L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY)); assertTrue(f.profile.loggingRunActive);
        assertEquals(0,f.chops); assertEquals(0,f.plants);
        f.add(LoggingRules.SAPLING,2);
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.plants); assertEquals(0,f.chops);
        assertEquals(2,f.crafted,"Stored logs must not be withdrawn or crafted again after replanting resumes");
    }

    @Test void heldProductsDoNotCauseCleanupUntilEveryCurrentlyProcessablePlotHasBeenTried() {
        Fixture f=new Fixture(2); f.add(LoggingRules.LOG,12); f.add(LoggingRules.BERRY,3);
        f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions()); f.blockAllChopRays=true;
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertTrue(f.profile.loggingPlots.get(1).plantingPositions().contains(((Action.ChopTree)f.pending).pos()));
        assertTrue(f.actions.stream().noneMatch(a -> a instanceof Action.UseBlock || a instanceof Action.CraftFireLogs
            || a instanceof Action.QuickMove || a instanceof Action.TrashLogging));
        f.until(() -> f.plants==4); assertEquals(0,f.crafted); assertEquals(0,f.trashed);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(4,f.crafted); assertEquals(6,f.stored(LoggingRules.FIRE_LOG)); assertEquals(4,f.shipped(LoggingRules.BERRY));
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void unsafeOrUnknownVisibilityBoundariesCannotStartPartialCleanupEvenWithHeldProducts() {
        for(String unsafe:List.of("busy","fence","cursor","container","airborne","lease","output","unloaded")) {
            Fixture f=partialCleanupFixture();
            switch(unsafe) {
                case "busy" -> f.forcedNativeBusy=true;
                case "fence" -> f.nativeFence="unconfirmed native action";
                case "cursor" -> f.cursor=item(LoggingRules.SAPLING,1);
                case "container" -> { f.opened=f.woodPos; f.containerId=10; }
                case "airborne" -> f.grounded=false;
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(35,0,item("minecraft:torch",5),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "output" -> {
                    String id="11111111-1111-1111-1111-111111111111";
                    f.profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(30,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
                }
                case "unloaded" -> f.unloaded.add(new Pos(-2,64,-2));
                default -> throw new AssertionError(unsafe);
            }
            WorkResult result=f.finish();
            if(unsafe.equals("unloaded")) {
                assertEquals(WorkResult.State.RESOURCE_WAIT,result.state());assertFalse(f.module.sleepSafeResourceWait(f.context));
            } else assertTrue(result.state()==WorkResult.State.BLOCKED || result.state()==WorkResult.State.DEFERRED,unsafe+": "+result);
            assertTrue(f.actions.isEmpty(),unsafe); assertEquals(14,f.count(LoggingRules.LOG)); assertEquals(0,f.crafted);
            assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
            assertEquals(37L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        }
    }

    @Test void partialCleanupNativePendingAndFailureNeverPermitAnotherMutationOrDiscardTheBatch() {
        for(Class<? extends Action> kind:List.of(Action.CraftFireLogs.class,Action.QuickMove.class,Action.CloseContainer.class)) {
            Fixture f=partialCleanupFixture(); f.until(() -> kind.isInstance(f.pending));
            int sent=f.actions.size();
            for(int i=0;i<100;i++) { f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state()); }
            assertEquals(sent,f.actions.size()); assertTrue(f.profile.loggingRunActive);
            assertEquals(1,f.profile.loggingRemainingPlots.size()); assertEquals(37L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
            f.reject("unconfirmed partial cleanup operation");
            assertEquals(WorkResult.State.BLOCKED,f.step().state());
            for(int i=0;i<5;i++) assertEquals(WorkResult.State.BLOCKED,f.step().state());
            assertEquals(sent,f.actions.size()); assertEquals(0,f.trashed);
            f.closeManually(); f.nativeFence="retained unconfirmed operation"; f.restart();
            assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(sent,f.actions.size());
            assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
        }
    }

    @Test void restartAfterActualPartialCraftAckUsesOnlyRemainingInventoryWithoutReplayingCraftOrCompletingThePlot() {
        Fixture f=partialCleanupFixture(); f.until(() -> f.pending instanceof Action.CraftFireLogs);
        f.advance(); assertEquals(2,f.crafted); assertEquals(2,f.count(LoggingRules.LOG));
        // The native response was applied, but the module has not consumed its outcome.
        f.closeManually(); f.restart();
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(1,f.craftCalls); assertEquals(2,f.crafted); assertEquals(5,f.stored(LoggingRules.FIRE_LOG));
        assertEquals(2,f.stored(LoggingRules.LOG)); assertEquals(5,f.shipped(LoggingRules.BERRY));
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertEquals(37L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        assertTrue(f.profile.loggingRunActive); assertEquals(0,f.chops); assertEquals(0,f.trashed);
    }

    @Test void aChangedDurableLoggingCheckpointDuringPartialCleanupStopsBeforeTheNextUnsentAction() {
        for(String changed:List.of("remaining","replanting","due","registration","lease")) {
            Fixture f=partialCleanupFixture(); f.until(() -> f.pending instanceof Action.UseBlock);
            f.advance(); int sent=f.actions.size();
            switch(changed) {
                case "remaining" -> f.profile.loggingRemainingPlots.clear();
                case "replanting" -> f.profile.loggingReplantingPlots.add(f.profile.loggingRemainingPlots.get(0));
                case "due" -> f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,38L);
                case "registration" -> f.profile.loggingPlots.set(0,new LoggingPlot("changed",f.profile.loggingPlots.get(0).corner()));
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(35,0,item("minecraft:torch",5),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                default -> throw new AssertionError(changed);
            }
            assertEquals(WorkResult.State.BLOCKED,f.step().state(),changed);
            assertEquals(sent,f.actions.size()); assertEquals(0,f.crafted); assertEquals(0,f.trashed);
            assertTrue(f.profile.loggingRunActive); assertTrue(f.menu().container(),"No fabricated close acknowledgement");
        }
    }

    @Test void aLateHeldProductDuringAnAlreadyGrantedLoggingOneShotWaitCleansAndReturnsToThatWait() {
        for(int delay:List.of(20,1200)) {
            Fixture f=partialCleanupFixture(); f.consume(LoggingRules.LOG,14); f.consume(LoggingRules.FIRE_LOG,3); f.consume(LoggingRules.BERRY,5);
            int[] neighbours={0};
            AutomationEngine engine=new AutomationEngine(List.of(f.module,
                resourceNeighbour(Feature.SHIPPING,40,c -> { neighbours[0]++; return WorkResult.idle(); })));
            engine.startOnce(f.context,Feature.LOGGING); engineUntil(f,engine,() -> engine.state()==AutomationEngine.State.WAITING);
            assertTrue(f.actions.isEmpty()); f.add(LoggingRules.FIRE_LOG,4);
            assertEquals(AutomationModule.ResourceReadiness.READY,f.module.resourceReadiness(f.context));
            // The next ordinary check, even before the 1200-tick timeout, releases
            // the old wait grant before cleanup owns a crafting/storage menu.
            f.ticks+=delay;
            engineUntil(f,engine,() -> f.stored(LoggingRules.FIRE_LOG)==4 && engine.state()==AutomationEngine.State.WAITING && !f.menu().container());
            assertTrue(engine.running(),engine.status()); assertEquals(0,neighbours[0]); assertEquals(0,f.trashed);
            assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
            assertEquals(37L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        }
    }

    private static Fixture partialCleanupFixture() {
        Fixture f=new Fixture(1); f.profile.loggingRunActive=true;
        f.profile.loggingRemainingPlots.add(f.profile.loggingPlots.get(0).corner());
        f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,37L); f.profile.loggingSaplingReserve=0;
        f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions()); f.blockAllChopRays=true;
        f.add(LoggingRules.LOG,14); f.add(LoggingRules.FIRE_LOG,3); f.add(LoggingRules.BERRY,5);
        f.add(LoggingRules.SAPLING,7); f.add(LoggingRules.TWIG,13);
        return f;
    }

    @Test void allInvisiblePlotsAreCheckedOnceBeforeWaitingAndOneShotDoesNotRunNeighbours() {
        Fixture f=new Fixture(3);
        f.profile.loggingPlots.forEach(plot -> f.occludedChopping.addAll(plot.plantingPositions())); f.blockAllChopRays=true;
        int[] neighbours={0}; f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { neighbours[0]++; return WorkResult.idle(); })));
        engine.startOnce(f.context,Feature.LOGGING);
        engineUntil(f,engine,() -> engine.state()==AutomationEngine.State.WAITING);
        assertTrue(engine.running(),engine.status()); assertEquals(0,neighbours[0]); assertTrue(f.actions.isEmpty());
        assertEquals(12,f.actualChopQueries,"Each plot gets one actual-eye pass in the bounded sweep");
        assertTrue(f.profile.loggingPlots.stream().allMatch(plot -> plot.plantingPositions().stream().anyMatch(f.chopRayTargets::contains)));
        int rays=f.chopRays;
        for(int i=0;i<10;i++) { f.ticks+=20; engine.tick(f.context); }
        assertEquals(rays,f.chopRays); assertEquals(12,f.actualChopQueries);
        assertEquals(3,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void invisibleTreesAndAnOlderSeedShortPlotWaitWithoutReadyPlotPingPong() {
        Fixture f=new Fixture(3); f.continuous(); f.profile.loggingRunActive=true;
        f.profile.loggingRemainingPlots.addAll(f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList());
        LoggingPlot oldest=f.profile.loggingPlots.get(0); f.setPlot(0,"minecraft:air");
        f.profile.loggingReplantingPlots.add(oldest.corner()); f.add(LoggingRules.SAPLING,2);
        f.profile.loggingPlots.subList(1,3).forEach(plot -> f.occludedChopping.addAll(plot.plantingPositions()));
        f.blockAllChopRays=true;
        int[] sleeps={0}; f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SLEEP,100,c -> { sleeps[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> sleeps[0]>0);
        assertEquals(AutomationEngine.State.WAITING,engine.state()); assertEquals(0,f.plants); assertTrue(f.actions.isEmpty());
        int rays=f.chopRays;
        for(int i=0;i<10;i++) { f.ticks+=20; engine.tick(f.context); }
        assertTrue(engine.running(),engine.status()); assertEquals(rays,f.chopRays); assertTrue(sleeps[0]>1);
        f.add(LoggingRules.SAPLING,2); f.ticks+=20;
        engineUntil(f,engine,() -> f.plants==4);
        assertEquals(0,f.chops);
        assertTrue(f.actions.stream().filter(a -> a instanceof Action.PlantSapling)
            .map(a -> ((Action.PlantSapling)a).pos()).allMatch(oldest.plantingPositions()::contains));
        assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void allInvisibleRetryChecksALaterNewlyVisiblePlotWithoutRepeatingTheFirstForever() {
        Fixture f=new Fixture(2); f.continuous();
        f.profile.loggingPlots.forEach(plot -> f.occludedChopping.addAll(plot.plantingPositions())); f.blockAllChopRays=true;
        int[] sleeps={0}; f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SLEEP,100,c -> { sleeps[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> sleeps[0]>0);
        LoggingPlot later=f.profile.loggingPlots.get(1); f.occludedChopping.removeAll(later.plantingPositions());
        f.ticks+=1200;
        engineUntil(f,engine,() -> f.pending instanceof Action.ChopTree);
        assertTrue(later.plantingPositions().contains(((Action.ChopTree)f.pending).pos()));
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingReplantingPlots.isEmpty());
    }

    @Test void invisibleFirstPlotCannotBypassUnknownSecondGeometryOrWholeTreeRejection() {
        for(boolean unknown:List.of(false,true)) {
            Fixture f=new Fixture(2); f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions());
            f.profile.loggingRunActive=true;
            f.profile.loggingRemainingPlots.addAll(f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList());
            f.blockAllChopRays=true;
            if(unknown) f.unloaded.add(f.profile.loggingPlots.get(1).corner());
            else f.treeRejection="connected structure outside registered 2x2";
            assertEquals(unknown ? WorkResult.State.RESOURCE_WAIT : WorkResult.State.BLOCKED,f.finish().state());
            if(unknown)assertFalse(f.module.sleepSafeResourceWait(f.context));
            assertFalse(f.actions.stream().anyMatch(a -> a instanceof Action.ChopTree));
            assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void unloadingANonCurrentNegativeWakesOrdinaryObservationWithoutReadingUnknownBlocks() {
        Fixture f=new Fixture(2); f.continuous();
        f.profile.loggingPlots.forEach(plot -> f.occludedChopping.addAll(plot.plantingPositions())); f.blockAllChopRays=true;
        int[] sleeps={0}; f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SLEEP,100,c -> { sleeps[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> sleeps[0]>0);
        Pos other=f.profile.loggingPlots.get(1).corner(); f.unloaded.add(other);
        f.rejectUnloadedBlockReads=true; f.observeLoads=true;
        assertEquals(AutomationModule.ResourceReadiness.READY,f.module.resourceReadiness(f.context));
        f.ticks+=20; engineUntil(f,engine,() -> f.observed.contains(other));
        assertTrue(engine.running(),engine.status()); assertTrue(f.actions.isEmpty());
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void visibilitySkipRestoresTheBorrowedHotbarBeforeSelectingAnotherPlotWithoutCleanup() {
        Fixture f=parkedVisibilityFixture(); ItemData original=f.profile.loggingHotbarLease.original();
        f.until(() -> f.pending instanceof Action.SwapHotbar);
        assertEquals(1,f.actions.size()); assertEquals(LoggingHotbarLease.Stage.RESTORING,f.profile.loggingHotbarLease.stage());
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingReplantingPlots.isEmpty());
        f.advance(); f.step();
        assertNull(f.profile.loggingHotbarLease); assertEquals(original,f.inventory[0]);
        assertTrue(f.profile.loggingRunActive); assertEquals(2,f.profile.loggingRemainingPlots.size());
        assertEquals(0,f.trashed); assertEquals(0,f.crafted); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertTrue(f.profile.loggingPlots.get(1).plantingPositions().contains(((Action.ChopTree)f.pending).pos()));
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertNull(f.profile.loggingHotbarLease); assertEquals(original,f.inventory[0]);
        assertEquals(2,f.chops); assertEquals(4,f.plants); assertEquals(1,f.profile.loggingRemainingPlots.size());
        assertEquals(0,f.trashed); assertEquals(2,f.crafted); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(4,f.stored(LoggingRules.FIRE_LOG)); assertEquals(1,f.shipped(LoggingRules.BERRY));
    }

    @Test void visibilityRestoreMustAwaitItsSingleNativeAckAndCannotReplayAFailedInverse() {
        Fixture f=parkedVisibilityFixture(); f.until(() -> f.pending instanceof Action.SwapHotbar);
        int sent=f.actions.size();
        for(int i=0;i<100;i++) { f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state()); }
        assertEquals(sent,f.actions.size()); assertEquals(LoggingHotbarLease.Stage.RESTORING,f.profile.loggingHotbarLease.stage());
        assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context));
        f.reject("unconfirmed inverse"); assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertNotNull(f.profile.loggingHotbarLease); f.restart();
        assertEquals(WorkResult.State.BLOCKED,f.step().state()); assertEquals(sent,f.actions.size());
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void visibilityRestoreCannotSendAcrossChangedIdentityNativeUncertaintyOrUnsafeBoundary() {
        for(String unsafe:List.of("identity","fingerprint","busy","fence","cursor","container","airborne","output","save")) {
            Fixture f=parkedVisibilityFixture();
            switch(unsafe) {
                case "identity" -> f.inventory[9]=item("minecraft:diamond",1);
                case "fingerprint" -> f.fingerprintEpoch++;
                case "busy" -> f.forcedNativeBusy=true;
                case "fence" -> f.nativeFence="unconfirmed native action";
                case "cursor" -> f.cursor=item(LoggingRules.SAPLING,1);
                case "container" -> { f.opened=f.woodPos; f.containerId=10; }
                case "airborne" -> f.grounded=false;
                case "output" -> {
                    String id="11111111-1111-1111-1111-111111111111";
                    f.profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(30,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
                }
                case "save" -> f.failNextCheckpoint=true;
                default -> throw new AssertionError(unsafe);
            }
            assertEquals(WorkResult.State.BLOCKED,f.finish().state(),unsafe);
            assertTrue(f.actions.isEmpty(),unsafe); assertNotNull(f.profile.loggingHotbarLease,unsafe);
            assertEquals(LoggingHotbarLease.Stage.PARKED,f.profile.loggingHotbarLease.stage(),unsafe);
            assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    private static Fixture parkedVisibilityFixture() {
        Fixture f=new Fixture(2); f.fillHotbar(); ItemData original=f.inventory[0];
        f.inventory[9]=original; f.inventory[0]=item(LoggingRules.SAPLING,4);
        f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,original,f.loggingItemFingerprint(9),LoggingHotbarLease.Stage.PARKED);
        f.profile.loggingRunActive=true;
        f.profile.loggingRemainingPlots.addAll(f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList());
        f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions()); f.blockAllChopRays=true;
        return f;
    }

    @Test void nativeVisibleApproachCanChooseAnotherBaseButStillRequiresWholeTreeSafetyProof() {
        Fixture f=new Fixture(1); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
        f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
        f.treeRejection="test connected structure outside registered tree";
        assertEquals(WorkResult.State.BLOCKED,f.finish().state());
        assertTrue(f.loggingTargets.contains(bases.get(3))); assertFalse(f.loggingTargets.contains(bases.get(0)));
        assertFalse(f.actions.stream().anyMatch(a -> a instanceof Action.ChopTree));
        assertEquals(List.of(bases.get(0)),f.profile.loggingRemainingPlots);
    }

    @Test void sameTickPreflightPollsCannotLaunchNavigationAndResetRechecksChangedVisibility() {
        Fixture f=new Fixture(1); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
        f.step(); f.step(); f.step(); int rays=f.chopRays;
        for(int i=0;i<200;i++) assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(rays,f.chopRays); assertEquals(0,f.moves);
        f.restart(); f.occludedChopping.remove(bases.get(2));
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertEquals(bases.get(2),((Action.ChopTree)f.pending).pos());
    }

    @Test void aGoalLosingItsOutlineAfterAxeSelectionCannotSubmitAChop() {
        Fixture f=new Fixture(1); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
        f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
        f.until(() -> f.pending instanceof Action.SelectHotbar);
        f.advance(); f.occludedChopping.addAll(bases); f.chopGoalTarget=null; f.movementExposesChop=false;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        assertFalse(f.actions.stream().anyMatch(a -> a instanceof Action.ChopTree));
        assertTrue(f.profile.loggingRunActive); assertEquals(List.of(bases.get(0)),f.profile.loggingRemainingPlots);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertFalse(f.actions.stream().anyMatch(a -> a instanceof Action.ChopTree));
    }

    @Test void twelveAcknowledgedPartialChopsMayInvalidateTheOutlineAndResumeThroughFreshGeometry() {
        Fixture f=new Fixture(1); f.strokesPerTree=24;
        List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.until(() -> f.chops==11);
        f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
        f.chopGoalTarget=bases.get(0); f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
        f.until(() -> f.pending instanceof Action.ChopTree); f.advance(); assertEquals(12,f.chops);
        int sent=f.actions.size();
        f.occludedChopping.addAll(bases); f.chopGoalTarget=null; f.movementExposesChop=false;
        WorkResult replan=f.step(); assertEquals(WorkResult.State.BUSY,replan.state()); assertTrue(replan.message().contains("시야를 새로"));
        assertEquals(sent,f.actions.size()); assertEquals(12,f.chops); assertEquals(0,f.plants);
        f.chopGoalTarget=bases.get(2); f.movementExposesChop=true;
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertEquals(bases.get(2),((Action.ChopTree)f.pending).pos()); assertEquals(12,f.chops);
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(24,f.chops); assertEquals(4,f.plants);
    }

    @Test void repeatedlyUnstableFoundGoalsHaveAFiniteBudgetWithoutAnyNativeChop() {
        Fixture f=new Fixture(1); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
        f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.alternateChopGoal=true;
        WorkResult result=f.finish(); assertEquals(WorkResult.State.BLOCKED,result.state());
        assertTrue(result.message().contains("재탐색 한도")); assertEquals(6,f.chopGoalChecks);
        assertTrue(f.actions.isEmpty()); assertEquals(0,f.chops); assertEquals(1,f.profile.loggingRemainingPlots.size());
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void aRealPartialChopAckRenewsTheStaleBudgetWithoutCompletingOrReplayingTheTree() {
        Fixture f=new Fixture(1); f.strokesPerTree=24;
        List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
        f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.alternateChopGoal=true;
        f.until(() -> f.chopGoalChecks==4); // Two stale endpoints, no native progress yet.
        assertTrue(f.actions.isEmpty());
        f.alternateChopGoal=false; f.movementExposesChop=true;
        f.until(() -> f.pending instanceof Action.ChopTree); f.advance();
        assertEquals(1,f.chops); f.occludedChopping.addAll(bases); f.movementExposesChop=false;
        f.until(() -> f.chopGoalChecks>=6); // Fresh FOUND snapshot of the partially chopped block.
        f.chopGoalTarget=null; int sent=f.actions.size();
        WorkResult result=f.step(); assertEquals(WorkResult.State.BUSY,result.state(),result.message());
        assertTrue(result.message().contains("시야를 새로")); assertEquals(sent,f.actions.size());
        assertEquals(1,f.chops); assertEquals(0,f.plants); assertTrue(f.profile.loggingRunActive);
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void staleGoalWithAParkedLoanUsesNormalRestoreBeforeResumingPlotSelection() {
        Fixture f=parkedVisibilityFixture(); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
        f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
        ItemData original=f.profile.loggingHotbarLease.original();
        f.until(() -> f.pending instanceof Action.SelectHotbar); f.advance();
        f.occludedChopping.addAll(bases); f.chopGoalTarget=null; f.movementExposesChop=false;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        f.until(() -> f.pending instanceof Action.SwapHotbar);
        assertEquals(LoggingHotbarLease.Stage.RESTORING,f.profile.loggingHotbarLease.stage()); assertEquals(0,f.chops);
        f.advance(); f.step(); assertNull(f.profile.loggingHotbarLease); assertEquals(original,f.inventory[0]);
        f.until(() -> f.pending instanceof Action.ChopTree);
        assertTrue(f.profile.loggingPlots.get(1).plantingPositions().contains(((Action.ChopTree)f.pending).pos()));
        assertEquals(0,f.trashed); assertEquals(0,f.crafted); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void staleGoalReplanCannotRunAcrossANativeFenceOrUnsafeBoundary() {
        for(String unsafe:List.of("busy","fence","airborne","cursor","changed lease")) {
            Fixture f=new Fixture(1); List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
            f.occludedChopping.addAll(bases); f.blockAllChopRays=true;
            f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
            f.until(() -> f.pending instanceof Action.SelectHotbar); f.advance();
            f.occludedChopping.addAll(bases); f.chopGoalTarget=null; f.movementExposesChop=false;
            switch(unsafe) {
                case "busy" -> f.forcedNativeBusy=true;
                case "fence" -> f.nativeFence="unconfirmed native action";
                case "airborne" -> f.grounded=false;
                case "cursor" -> f.cursor=item(LoggingRules.SAPLING,1);
                case "changed lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item("minecraft:torch",5),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                default -> throw new AssertionError(unsafe);
            }
            int sent=f.actions.size(); assertEquals(WorkResult.State.BLOCKED,f.step().state(),unsafe);
            assertEquals(sent,f.actions.size()); assertEquals(0,f.chops); assertTrue(f.profile.loggingRunActive);
        }
    }

    @Test void searchingDoesNotRepeatActualEyeQueriesOutsideThePreflightSlice() {
        Fixture f=visibilityFixture();
        f.until(() -> f.chopRays>0);
        assertEquals(4,f.actualChopQueries);
        int initial=f.chopRays;
        for(int i=0;i<12;i++) {
            f.advance();
            assertEquals(WorkResult.State.BUSY,f.step().state());
        }
        assertTrue(f.chopRays>initial,"The bounded candidate search still progresses");
        assertEquals(4,f.actualChopQueries,"A stopped SEARCHING phase does not repeat four extra eye probes every tick");
        assertEquals(0,f.moves); assertTrue(f.actions.isEmpty());
        f.restart();
        f.until(() -> f.actualChopQueries>4);
        assertEquals(8,f.actualChopQueries,"A genuine restart must check the actual eye afresh");
    }

    @Test void anOpenMenuDuringVisibilityPreflightStopsWithoutNavigationOrInventoryActions() {
        Fixture f=new Fixture(1); f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions());
        f.blockAllChopRays=true; f.step(); f.step(); f.step();
        f.opened=f.woodPos; f.containerId=10;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(0,f.moves); assertTrue(f.actions.isEmpty()); assertTrue(f.profile.loggingRunActive);
    }

    @Test void actualVisibilityWaitLetsOtherWorkAndSleepRunWithBothCutAndPlantingObligationsPreserved() {
        Fixture f=visibilityFixture(); int[] consumers={0},sleeps={0};
        f.profile.enabled.put(Feature.SHIPPING,true); f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); }),
            resourceNeighbour(Feature.SLEEP,100,c -> { sleeps[0]++; return WorkResult.idle(); })));
        List<Pos> remaining=List.copyOf(f.profile.loggingRemainingPlots),replanting=List.copyOf(f.profile.loggingReplantingPlots);
        engine.start(f.context); engineUntil(f,engine,() -> sleeps[0]>0);
        assertTrue(engine.running(),engine.status()); assertEquals(AutomationEngine.State.WAITING,engine.state());
        assertTrue(engine.status().contains("시야 대기")); assertTrue(consumers[0]>0); assertTrue(f.actions.isEmpty());
        int rays=f.chopRays;
        for(int i=0;i<10;i++) { f.ticks+=20; engine.tick(f.context); }
        assertTrue(engine.running(),engine.status()); assertTrue(sleeps[0]>1); assertEquals(rays,f.chopRays);
        assertEquals(remaining,f.profile.loggingRemainingPlots); assertEquals(replanting,f.profile.loggingReplantingPlots);
        assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty()); assertTrue(f.actions.isEmpty());
    }

    @Test void visibilityWaitRetriesFreshNativeGeometryAfter1200TicksInsteadOfReusingNoVisibleCache() {
        Fixture f=visibilityFixture(); int[] consumers={0}; f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> consumers[0]>0);
        long granted=f.ticks-1; int rays=f.chopRays;
        f.ticks=granted+1180; engine.tick(f.context); assertEquals(rays,f.chopRays);
        f.chopGoalTarget=f.profile.loggingPlots.get(0).corner().offset(1,0,1);
        f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
        f.ticks=granted+1200;
        engineUntil(f,engine,() -> f.pending instanceof Action.ChopTree || f.chops>0);
        assertTrue(engine.running(),engine.status()); assertTrue(f.chopRays>rays);
        assertTrue(f.actions.stream().filter(a -> a instanceof Action.ChopTree)
            .allMatch(a -> ((Action.ChopTree)a).pos().equals(f.chopGoalTarget)));
    }

    @Test void aRepeatedOccludedRetryYieldsAgainWithoutTurningContinuousAutomationOff() {
        Fixture f=visibilityFixture(); int[] sleeps={0}; f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SLEEP,100,c -> { sleeps[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> sleeps[0]>0);
        int rays=f.chopRays,previous=sleeps[0]; f.ticks+=1200;
        engineUntil(f,engine,() -> sleeps[0]>previous);
        assertTrue(engine.running(),engine.status()); assertTrue(f.chopRays>rays); assertTrue(f.actions.isEmpty());
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertEquals(1,f.profile.loggingReplantingPlots.size());
    }

    @Test void aVisibilityRetryCannotPreemptAnotherConsumersUnconfirmedNativeAction() {
        Fixture f=visibilityFixture(); boolean[] sent={false}; f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationModule consumer=resourceNeighbour(Feature.SHIPPING,40,c -> {
            if (!sent[0]) { sent[0]=true; c.actions().submit(new Action.SelectHotbar(4)); }
            return c.actions().busy() ? WorkResult.busy("owned acknowledgement") : WorkResult.idle();
        });
        AutomationEngine engine=new AutomationEngine(List.of(f.module,consumer)); engine.start(f.context);
        for(int i=0;i<1000 && !sent[0];i++) { engine.tick(f.context); if(!sent[0]) f.advance(); }
        assertTrue(sent[0]); assertNotNull(f.pending); int rays=f.chopRays,actions=f.actions.size(); f.ticks+=1300;
        for(int i=0;i<5;i++) { engine.tick(f.context); f.ticks++; }
        assertTrue(engine.running(),engine.status()); assertEquals(rays,f.chopRays); assertEquals(actions,f.actions.size());
        f.advance(); engine.tick(f.context); engine.tick(f.context);
        assertTrue(engine.running(),engine.status());
    }

    @Test void visibilityOneShotWaitsWithoutRunningNeighboursOrClaimingCompletion() {
        Fixture f=visibilityFixture(); int[] consumers={0}; f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
        engine.startOnce(f.context,Feature.LOGGING); engineUntil(f,engine,() -> engine.state()==AutomationEngine.State.WAITING);
        assertTrue(engine.running(),engine.status()); assertEquals(0,consumers[0]); assertTrue(f.profile.loggingRunActive);
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.actions.isEmpty());
    }

    @Test void visibilityWaitReadinessRejectsUncertaintyOrChangedBaseBeforeAnotherConsumerRuns() {
        for(String unsafe:List.of("busy","lease","cursor","airborne","native fence","changed plot","unloaded plot")) {
            Fixture f=visibilityFixture(); assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
            switch(unsafe) {
                case "busy" -> f.forcedNativeBusy=true;
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item("minecraft:torch",5),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "cursor" -> f.cursor=item(LoggingRules.SAPLING,1);
                case "airborne" -> f.grounded=false;
                case "native fence" -> f.nativeFence="unconfirmed action";
                case "changed plot" -> f.blocks.put(f.profile.loggingPlots.get(0).corner(),LoggingRules.CHOPPED_LOG);
                case "unloaded plot" -> f.unloaded.add(f.profile.loggingPlots.get(0).corner());
                default -> throw new AssertionError(unsafe);
            }
            assertEquals(AutomationModule.ResourceReadiness.UNSAFE,f.module.resourceReadiness(f.context),unsafe);
            assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.actions.isEmpty());
        }
    }

    @Test void aChangedWaitingBaseRevokesTheEngineGrantBeforeOtherWorkResumes() {
        Fixture f=visibilityFixture(); int[] consumers={0}; f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> consumers[0]>0); int before=consumers[0];
        f.blocks.put(f.profile.loggingPlots.get(0).corner(),LoggingRules.CHOPPED_LOG); f.ticks+=20;
        engine.tick(f.context); assertEquals(AutomationEngine.State.PAUSED,engine.state());
        assertEquals(before,consumers[0]); assertTrue(f.profile.loggingRunActive); assertEquals(2,f.profile.loggingRemainingPlots.size());
    }

    @Test void unloadedPreflightGeometryAllowsOnlyDelayedReobservationAndNeverClaimsSafeVisibilityOrSleep() {
        Fixture f=visibilityFixture(); int[] consumers={0}; f.profile.enabled.put(Feature.SHIPPING,true);
        f.unloaded.add(new Pos(-2,64,-2));
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> consumers[0]>0);
        assertTrue(engine.running(),engine.status());assertEquals(AutomationEngine.State.WAITING,engine.state());
        assertFalse(f.module.sleepSafeResourceWait(f.context));
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.actions.isEmpty());
    }

    private static Fixture visibilityFixture() {
        Fixture f=new Fixture(2); f.continuous(); f.profile.loggingRunActive=true;
        f.profile.loggingRemainingPlots.addAll(f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList());
        f.setPlot(1,"minecraft:air"); f.profile.loggingReplantingPlots.add(f.profile.loggingPlots.get(1).corner());
        f.occludedChopping.addAll(f.profile.loggingPlots.get(0).plantingPositions()); f.blockAllChopRays=true;
        return f;
    }

    @Test void loggingOptsIntoItsOwnNavigationForTreesPlantingCraftWoodAndShipping() {
        Fixture f=new Fixture(1);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertTrue(f.loggingMoves>0); assertEquals(f.moves,f.loggingMoves);
        assertTrue(f.loggingTargets.containsAll(List.of(f.tablePos,f.woodPos,f.shippingPos)));
        for(Pos p:f.profile.loggingPlots.get(0).plantingPositions()) assertTrue(f.loggingTargets.contains(p.offset(0,-1,0)));
    }

    @Test void oneShotOwnsCutReplantTrashCraftStoreAndBerryShippingWhenEveryFeatureIsOff() {
        Fixture f=new Fixture(2); f.inventory[8]=item(ItemData.PINE_TAR,20); f.inventory[7]=item("minecraft:stick",12);
        WorkResult result=f.finish();
        assertEquals(WorkResult.State.IDLE,result.state()); assertTrue(result.message().contains("완료"));
        assertEquals(4,f.chops); assertEquals(8,f.plants); assertEquals(4,f.crafted);
        assertEquals(8,f.stored(LoggingRules.FIRE_LOG)); assertEquals(2,f.shipped(LoggingRules.BERRY));
        assertEquals(20,f.count(ItemData.PINE_TAR)); assertEquals(12,f.count("minecraft:stick"));
        assertEquals(0,f.count(LoggingRules.TWIG)); assertEquals(0,f.count(LoggingRules.SAPLING));
        assertFalse(f.profile.loggingRunActive); assertTrue(f.profile.loggingRemainingPlots.isEmpty());
        assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertEquals(11L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        assertTrue(f.profile.loggingPlots.stream().flatMap(p -> p.plantingPositions().stream()).allMatch(p -> f.id(p).equals(LoggingRules.SAPLING)));
        assertTrue(f.events.indexOf("plant:8")<f.events.indexOf("trash:"+LoggingRules.SAPLING));
        assertEquals(LoggingRules.AXE,f.inventory[2].id()); assertTrue(f.inventory[4].hoe());
        assertTrue(f.actions.stream().allMatch(a -> a instanceof Action.ChopTree || a instanceof Action.PlantSapling
            || a instanceof Action.SelectHotbar || a instanceof Action.SwapHotbar || a instanceof Action.TrashLogging
            || a instanceof Action.CraftFireLogs || a instanceof Action.UseBlock || a instanceof Action.QuickMove || a instanceof Action.CloseContainer));
    }

    @Test void allGrownModeWaitsWithoutCuttingTheEarlyTreeAndDailyModeCanSelectIt() {
        Fixture f=new Fixture(2); f.continuous(); f.setPlot(1,LoggingRules.SAPLING);
        assertEquals(WorkResult.State.IDLE,f.step().state()); assertEquals(0,f.moves); assertTrue(f.actions.isEmpty());
        f.profile.loggingMode=LoggingMode.DAILY_GROWN; f.module.reset(); f.ticks+=f.profile.loggingCheckTicks;
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops); assertEquals(4,f.plants);
    }

    @Test void oneShotOnlyModeDoesNotRunContinuouslyButExplicitOnceRepairsAnEmptyPlot() {
        Fixture f=new Fixture(1); f.continuous(); f.profile.loggingMode=LoggingMode.ONCE_ONLY; f.setPlot(0,"minecraft:air");
        f.inventory[9]=item(LoggingRules.SAPLING,4);
        assertEquals(WorkResult.State.IDLE,f.step().state()); assertTrue(f.actions.isEmpty());
        f.session.oneShotFeature=Feature.LOGGING;
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(0,f.chops); assertEquals(4,f.plants);
    }

    @Test void futureContinuousDeadlinePreventsMovementButAnActiveBatchResumesAcrossDays() {
        Fixture f=new Fixture(1); f.continuous(); f.profile.loggingMode=LoggingMode.DAILY_GROWN; f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,12L);
        assertEquals(WorkResult.State.IDLE,f.step().state()); assertTrue(f.actions.isEmpty());
        f.profile.loggingRunActive=true; f.profile.loggingRemainingPlots.add(f.profile.loggingPlots.get(0).corner());
        f.day=11;
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops);
        assertEquals(12L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
    }

    @Test void batchIsCheckpointedBeforeFirstNativeCutAndFailedSaveSendsNothing() {
        Fixture f=new Fixture(1); f.failNextCheckpoint=true;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertFalse(f.profile.loggingRunActive); assertTrue(f.profile.loggingRemainingPlots.isEmpty()); assertTrue(f.actions.isEmpty());
        f.module.reset(); assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertTrue(f.events.indexOf("checkpoint:active")<f.events.indexOf("chop:1"));
    }

    @Test void pendingOrFailedChopIsNeverReplayedAndDoesNotScheduleCompletion() {
        Fixture f=new Fixture(1); f.until(() -> f.pending instanceof Action.ChopTree);
        int sent=f.actions.size();
        for (int i=0;i<30;i++) { assertEquals(WorkResult.State.BUSY,f.step().state()); f.ticks++; }
        assertEquals(sent,f.actions.size());
        f.reject("native chop rejected");
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        for (int i=0;i<5;i++) assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(sent,f.actions.size()); assertTrue(f.profile.loggingRunActive);
        assertFalse(f.profile.nextEligibleDay.containsKey(LoggingRules.DUE_KEY));
    }

    @Test void repeatedNativeStumpProgressDoesNotPretendTheFirstAcknowledgementFelledTheTree() {
        Fixture f=new Fixture(1); f.until(() -> f.chops==1); f.step();
        assertEquals(LoggingRules.CHOPPED_LOG,f.id(f.profile.loggingPlots.get(0).corner())); assertEquals(0,f.plants);
        assertFalse(f.profile.loggingRemainingPlots.isEmpty());
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops); assertEquals(4,f.plants);
    }

    @Test void restartingAtSixOfTwentyFourChopsResumesUntilTheTreeActuallyFalls() {
        Fixture f=new Fixture(1); f.strokesPerTree=24;
        f.until(() -> f.chops==6);
        Pos tree=f.profile.loggingPlots.get(0).corner();
        assertEquals(LoggingRules.CHOPPED_LOG,f.id(tree)); assertEquals(0,f.plants);
        assertTrue(f.profile.loggingRunActive); assertEquals(List.of(tree),f.profile.loggingRemainingPlots);
        assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(24,f.chops); assertEquals(4,f.plants);
        assertFalse(f.profile.loggingRunActive); assertTrue(f.profile.loggingRemainingPlots.isEmpty());
    }

    @Test void twentyFourIsNotAHardcodedCompletionLimitForLargerTrees() {
        Fixture f=new Fixture(1); f.strokesPerTree=31;
        f.until(() -> f.chops==24);
        assertEquals(LoggingRules.CHOPPED_LOG,f.id(f.profile.loggingPlots.get(0).corner()));
        assertEquals(0,f.plants); assertTrue(f.profile.loggingRunActive);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(31,f.chops); assertEquals(4,f.plants);
    }

    @Test void restartAfterPartialReplantBlocksSmallTreeMixUntilOperatorRestoresFourTrunks() {
        Fixture f=new Fixture(2); f.fillHotbar(); f.until(() -> f.plants==1);
        Pos first=f.actions.stream().filter(a -> a instanceof Action.PlantSapling).map(a -> ((Action.PlantSapling)a).pos()).findFirst().orElseThrow();
        assertTrue(f.profile.loggingReplantingPlots.contains(f.profile.loggingPlots.get(0).corner())); f.blocks.put(first,LoggingRules.LOG);
        LoggingHotbarLease lease=f.profile.loggingHotbarLease; assertNotNull(lease);
        f.restart();
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(1,f.plants); assertEquals(2,f.chops);
        assertEquals(lease,f.profile.loggingHotbarLease); assertEquals(0,f.trashed); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(2,f.profile.loggingRemainingPlots.size());
        f.setPlot(0,LoggingRules.LOG); f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.chops,"Regrown first planting is not recut");
        assertEquals(5,f.plants); assertEquals(LoggingRules.LOG,f.id(first));
    }

    @Test void oneSmallTrunkWithThreeSaplingsCannotCountAsCompletedReplantingOrPermitWaste() {
        Fixture f=new Fixture(1); Pos corner=f.profile.loggingPlots.get(0).corner();
        f.profile.loggingRunActive=true; f.profile.loggingRemainingPlots.add(corner); f.profile.loggingReplantingPlots.add(corner);
        f.setPlot(0,LoggingRules.SAPLING); f.blocks.put(corner,LoggingRules.LOG);
        f.inventory[9]=item(LoggingRules.SAPLING,8); f.inventory[10]=item(LoggingRules.TWIG,3);
        assertFalse(LoggingRules.completePlanting(f,f.profile.loggingPlots.get(0)));
        assertNotNull(LoggingRules.trashRejection(new Action.TrashLogging(9,f.inventory[9]),f.context));
        assertNotNull(LoggingRules.trashRejection(new Action.TrashLogging(10,f.inventory[10]),f.context));
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertTrue(f.actions.isEmpty());
        assertEquals(List.of(corner),f.profile.loggingRemainingPlots); assertEquals(List.of(corner),f.profile.loggingReplantingPlots);
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void aCompletedPlotTurningMixedBeforeWasteIsNotSilentlyFinalized() {
        Fixture f=new Fixture(1); f.until(() -> f.profile.loggingRemainingPlots.isEmpty() && f.plants==4);
        f.blocks.put(f.profile.loggingPlots.get(0).corner(),LoggingRules.LOG);
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(0,f.trashed);
        assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void coherentFourSaplingsOrFourTrunksAreAcceptedButUnloadedOrMissingBasesAreNot() {
        Fixture f=new Fixture(1); LoggingPlot plot=f.profile.loggingPlots.get(0);
        assertTrue(LoggingRules.completePlanting(f,plot));
        f.setPlot(0,LoggingRules.SAPLING); assertTrue(LoggingRules.completePlanting(f,plot));
        f.blocks.put(plot.corner(),"minecraft:air"); assertFalse(LoggingRules.completePlanting(f,plot));
        f.setPlot(0,LoggingRules.SAPLING); f.unloaded.add(plot.corner()); assertFalse(LoggingRules.completePlanting(f,plot));
    }

    @Test void aLateMixedPatternCannotWriteTheFinalCompletionCheckpoint() throws Exception {
        Fixture f=new Fixture(1); f.profile.loggingRunActive=true; f.setPlot(0,LoggingRules.SAPLING);
        f.blocks.put(f.profile.loggingPlots.get(0).corner(),LoggingRules.LOG);
        var stage=LoggingModule.class.getDeclaredField("stage"); stage.setAccessible(true);
        stage.set(f.module,Arrays.stream(stage.getType().getEnumConstants()).filter(v -> v.toString().equals("FINISH")).findFirst().orElseThrow());
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty()); assertTrue(f.actions.isEmpty());
    }

    @Test void completedPlotIsNotRecutAfterResetWhileTheRemainingPlotIsStillPending() {
        Fixture f=new Fixture(2); f.until(() -> f.profile.loggingRemainingPlots.size()==1 && f.plants==4);
        f.setPlot(0,LoggingRules.LOG); f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.chops); assertEquals(8,f.plants);
    }

    @Test void missingSaplingsKeepTheDurableReplantObligationWhileHeldWoodIsStoredWithoutTrashingMaterials() {
        Fixture f=new Fixture(1); f.saplingDrops=0;
        WorkResult result=f.finish(); assertEquals(WorkResult.State.RESOURCE_WAIT,result.state()); assertTrue(result.message().contains("0/4"));
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingReplantingPlots.size());
        assertEquals(0,f.trashed); assertEquals(2,f.crafted); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(4,f.stored(LoggingRules.FIRE_LOG)); assertEquals(1,f.shipped(LoggingRules.BERRY));
        assertEquals(3,f.count(LoggingRules.TWIG));
        f.inventory[9]=item(LoggingRules.SAPLING,4); f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops); assertEquals(4,f.plants);
    }

    @Test void saplingsArrivingAfterTheEightyTickFallDelayResumeWithoutAnyAdditionalChop() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        int sent=f.actions.size(),moves=f.moves;
        f.ticks=firstWait+160;
        WorkResult waiting=f.step();
        assertEquals(WorkResult.State.BUSY,waiting.state()); assertTrue(waiting.message().contains("묘목 도착 대기"));
        assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves); assertEquals(2,f.chops);
        assertEquals(0,f.plants); assertEquals(0,f.trashed); assertEquals(0,f.crafted);
        f.add(LoggingRules.SAPLING,9);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(2,f.chops); assertEquals(4,f.plants); assertFalse(f.profile.loggingRunActive);
    }

    @Test void absentSaplingsBeginHeldOutputCleanupExactlyAtTheFourHundredTickDeadlineThenYieldWithoutReplay() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        int sent=f.actions.size(),moves=f.moves;
        // Repeated calls in one client tick cannot consume or extend elapsed time.
        for(int i=0;i<500;i++) assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=firstWait+399; assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=firstWait+400;
        WorkResult cleaning=f.step(); assertEquals(WorkResult.State.BUSY,cleaning.state());
        assertTrue(cleaning.message().contains("수거"));
        assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves); assertEquals(2,f.chops);
        WorkResult stopped=f.finish(); assertEquals(WorkResult.State.RESOURCE_WAIT,stopped.state());
        assertTrue(stopped.message().contains("0/4")); assertEquals(2,f.crafted); assertEquals(0,f.trashed);
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
        assertEquals(1,f.profile.loggingReplantingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.add(LoggingRules.SAPLING,4); f.ticks++;
        assertEquals(AutomationModule.ResourceReadiness.READY,f.module.resourceReadiness(f.context));
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(2,f.chops); assertEquals(4,f.plants);
    }

    @Test void partialSeedArrivalDoesNotPlantEarlyOrRenewThePlotsDeadline() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        f.ticks=firstWait+300; f.add(LoggingRules.SAPLING,2);
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertNull(f.pending);
        f.ticks=firstWait+400;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(2,f.chops); assertEquals(0,f.plants); assertEquals(0,f.trashed); assertEquals(0,f.crafted);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(2,f.crafted); assertEquals(0,f.plants); assertEquals(0,f.trashed); assertEquals(2,f.count(LoggingRules.SAPLING));
        assertEquals(1,f.profile.loggingReplantingPlots.size());
    }

    @Test void actualTwoOfFourShortageLetsConsumersAndBedtimeRunWithoutEscalationOrFalseCompletion() {
        Fixture f=resourceFixture(); int[] consumers={0},sleeps={0};
        f.profile.enabled.put(Feature.SHIPPING,true); f.profile.enabled.put(Feature.SLEEP,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); }),
            resourceNeighbour(Feature.SLEEP,100,c -> { sleeps[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> sleeps[0]>0);
        assertTrue(engine.running()); assertEquals(2,f.count(LoggingRules.SAPLING)); assertEquals(0,f.plants);
        int actions=f.actions.size();
        for (int i=0;i<10;i++) { f.ticks+=1201; engine.tick(f.context); }
        assertTrue(engine.running(),engine.status()); assertTrue(consumers[0]>=10); assertTrue(sleeps[0]>=10);
        assertEquals(actions,f.actions.size()); assertTrue(f.profile.loggingRunActive);
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertEquals(1,f.profile.loggingReplantingPlots.size());
        assertTrue(f.profile.nextEligibleDay.isEmpty()); assertEquals(0,f.trashed); assertEquals(0,f.crafted);
    }

    @Test void replenishmentWaitsForAnActiveConsumersAcknowledgementThenResumesAllFourPlantings() {
        Fixture f=resourceFixture(); int[] consumerTicks={0}; boolean[] waiting={false};
        f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationModule consumer=resourceNeighbour(Feature.SHIPPING,40,c -> {
            consumerTicks[0]++;
            if (!waiting[0]) { waiting[0]=true; c.actions().submit(new Action.SelectHotbar(4)); return WorkResult.busy("other native action"); }
            return c.actions().busy() ? WorkResult.busy("waiting for acknowledgement") : WorkResult.idle();
        });
        AutomationEngine engine=new AutomationEngine(List.of(f.module,consumer)); engine.start(f.context);
        for (int i=0;i<600 && !waiting[0];i++) { engine.tick(f.context); if(!waiting[0]) f.advance(); }
        assertTrue(waiting[0]); assertNotNull(f.pending); f.add(LoggingRules.SAPLING,2);
        int sent=f.actions.size();
        for(int i=0;i<5;i++) { f.ticks++; engine.tick(f.context); }
        assertEquals(sent,f.actions.size()); assertEquals(0,f.plants); assertNotNull(f.pending);
        f.advance(); engine.tick(f.context);
        engineUntil(f,engine,() -> f.plants==4);
        assertEquals(0,f.chops); assertTrue(consumerTicks[0]>=6);
        assertTrue(f.profile.loggingPlots.get(0).plantingPositions().stream().allMatch(p -> f.id(p).equals(LoggingRules.SAPLING)));
    }

    @Test void oneShotResourceWaitRunsNoNeighboursAndCanCompleteAfterOnlyTheMissingSeedsAreSupplied() {
        Fixture f=resourceFixture(); int[] neighbours={0};
        f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { neighbours[0]++; return WorkResult.idle(); })));
        engine.startOnce(f.context,Feature.LOGGING);
        engineUntil(f,engine,() -> engine.state()==AutomationEngine.State.WAITING);
        assertEquals(0,neighbours[0]); assertEquals(0,f.plants); assertTrue(f.profile.loggingRunActive);
        f.add(LoggingRules.SAPLING,2);
        engineUntil(f,engine,() -> !engine.running());
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(0,neighbours[0]); assertEquals(4,f.plants); assertEquals(0,f.chops);
        assertFalse(f.profile.loggingRunActive);
    }

    @Test void manualRestartRevokesResourceGrantAndRevalidatesChangedPlantingBlocksBeforeConsumers() {
        Fixture f=resourceFixture(); int[] consumers={0};
        f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engineUntil(f,engine,() -> consumers[0]>0);
        engine.stop(f.context,AutomationEngine.State.PAUSED,"manual pause"); int before=consumers[0];
        f.blocks.put(f.profile.loggingPlots.get(0).corner(),"minecraft:stone_bricks");
        engine.start(f.context); engineUntil(f,engine,() -> !engine.running());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(before,consumers[0]);
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingReplantingPlots.size());
        assertEquals(0,f.plants); assertEquals(0,f.chops);
    }

    @Test void livePartialGrowthOrBorrowedHotbarInvalidatesTheGrantAtTheNextSafeBoundary() {
        for(boolean lease:List.of(false,true)) {
            Fixture f=resourceFixture(); int[] consumers={0}; f.profile.enabled.put(Feature.SHIPPING,true);
            AutomationEngine engine=new AutomationEngine(List.of(f.module,
                resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
            engine.start(f.context); engineUntil(f,engine,() -> consumers[0]>0); int before=consumers[0];
            if(lease) f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item("minecraft:torch",5),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
            else f.blocks.put(f.profile.loggingPlots.get(0).corner(),LoggingRules.LOG);
            f.ticks+=20; engine.tick(f.context);
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(before,consumers[0]);
            assertEquals(0,f.plants); assertEquals(0,f.chops); assertTrue(f.profile.loggingRunActive);
        }
    }

    @Test void resourceWaitDoesNotPromoteAnUnsupportedModuleIntoLoggingPermission() {
        Fixture f=resourceFixture(); int[] consumers={0};
        AutomationModule fake=resourceNeighbour(Feature.LOGGING,80,c -> WorkResult.resourceWait("unverified"));
        f.profile.enabled.put(Feature.SHIPPING,true);
        AutomationEngine engine=new AutomationEngine(List.of(fake,
            resourceNeighbour(Feature.SHIPPING,40,c -> { consumers[0]++; return WorkResult.idle(); })));
        engine.start(f.context); engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(0,consumers[0]);
        assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void ordinaryNavigationDeferralDuringASaplingWaitDoesNotBecomeAGlobalLoggingFailure() {
        Fixture f=resourceFixture(); int[] attempts={0}; f.profile.enabled.put(Feature.TOMATO_STORAGE,true);
        AutomationEngine engine=new AutomationEngine(List.of(f.module,
            resourceNeighbour(Feature.TOMATO_STORAGE,20,c -> { attempts[0]++; return WorkResult.deferred("warehouse route"); })));
        engine.start(f.context); engineUntil(f,engine,() -> attempts[0]>0);
        assertTrue(engine.running(),engine.status()); assertEquals(AutomationEngine.State.WAITING,engine.state());
        int actions=f.actions.size(); f.ticks+=1201; engine.tick(f.context);
        assertTrue(engine.running(),engine.status()); assertEquals(2,attempts[0]); assertEquals(actions,f.actions.size());
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingReplantingPlots.size());
    }

    @Test void anUnloadedWaitingPlotIsObservedAgainBeforeResumedPlanting() {
        Fixture f=resourceFixture(); assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        Pos corner=f.profile.loggingPlots.get(0).corner(); f.unloaded.add(corner);
        assertEquals(AutomationModule.ResourceReadiness.WAITING,f.module.resourceReadiness(f.context));
        f.add(LoggingRules.SAPLING,2); f.observeLoads=true;
        assertEquals(AutomationModule.ResourceReadiness.READY,f.module.resourceReadiness(f.context));
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertEquals(List.of(corner),f.observed);
        assertEquals(0,f.plants); assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.plants);
    }

    @Test void aTwoSeedFirstTreeIsQueuedUntilTheNextTreeProvidesSixAndTheOldestPlotIsReplantedFirst() {
        Fixture f=new Fixture(2); f.saplingDrops=2; f.until(() -> f.chops==2);
        Pos first=f.profile.loggingPlots.get(0).corner(),second=f.profile.loggingPlots.get(1).corner();
        f.saplingDrops=6; f.until(() -> f.chops==4);
        assertEquals(0,f.plants); assertEquals(List.of(first,second),f.profile.loggingRemainingPlots);
        f.step(); // Confirm the final chop and enter its full falling-animation delay.
        assertEquals(List.of(first,second),f.profile.loggingReplantingPlots);
        long fell=f.ticks;
        for(int elapsed=0;elapsed<80;elapsed++) { f.ticks=fell+elapsed; assertEquals(WorkResult.State.BUSY,f.step().state()); assertEquals(0,f.plants); }
        f.ticks=fell+80;
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.chops); assertEquals(8,f.plants);
        List<Pos> planted=f.actions.stream().filter(a -> a instanceof Action.PlantSapling).map(a -> ((Action.PlantSapling)a).pos()).toList();
        assertTrue(planted.subList(0,4).stream().allMatch(f.profile.loggingPlots.get(0).plantingPositions()::contains));
        assertTrue(planted.subList(4,8).stream().allMatch(f.profile.loggingPlots.get(1).plantingPositions()::contains));
        assertTrue(f.profile.loggingRemainingPlots.isEmpty()); assertTrue(f.profile.loggingReplantingPlots.isEmpty());
    }

    @Test void repeatedTwoSeedDropsExhaustOnlyExistingTreesAndStoreWoodWithoutPartialPlantingOrWaste() {
        Fixture f=new Fixture(3); f.saplingDrops=2;
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(6,f.chops); assertEquals(4,f.plants); assertEquals(2,f.count(LoggingRules.SAPLING));
        List<Pos> remaining=List.of(f.profile.loggingPlots.get(1).corner(),f.profile.loggingPlots.get(2).corner());
        assertEquals(remaining,f.profile.loggingRemainingPlots); assertEquals(remaining,f.profile.loggingReplantingPlots);
        assertEquals(0,f.trashed); assertEquals(6,f.crafted); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertEquals(12,f.stored(LoggingRules.FIRE_LOG)); assertEquals(3,f.shipped(LoggingRules.BERRY)); assertEquals(9,f.count(LoggingRules.TWIG));
        f.add(LoggingRules.SAPLING,6); f.restart();
        assertEquals(remaining,f.profile.loggingReplantingPlots);
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(6,f.chops); assertEquals(12,f.plants);
    }

    @Test void aManuallyFelledUnmarkedBatchMemberBecomesAnObligationWithoutReceivingAnyChop() {
        Fixture f=new Fixture(3); f.continuous(); f.profile.loggingRunActive=true;
        List<Pos> corners=f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList();
        f.profile.loggingRemainingPlots.addAll(corners); f.profile.loggingReplantingPlots.add(corners.get(0));
        f.setPlot(0,"minecraft:air"); f.setPlot(2,"minecraft:air"); f.add(LoggingRules.SAPLING,2); f.saplingDrops=6;
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(List.of(corners.get(0),corners.get(2)),f.profile.loggingReplantingPlots);
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(2,f.chops); assertEquals(8,f.plants); assertEquals(List.of(corners.get(1)),f.profile.loggingReplantingPlots);
        assertTrue(f.actions.stream().filter(a -> a instanceof Action.ChopTree).map(a -> ((Action.ChopTree)a).pos())
            .allMatch(f.profile.loggingPlots.get(1).plantingPositions()::contains));
        f.add(LoggingRules.SAPLING,4); assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(12,f.plants);
    }

    @Test void strictOldestObligationCannotBeBypassedByANewerSmallerMissingCount() {
        Fixture f=new Fixture(2); f.profile.loggingRunActive=true;
        List<Pos> corners=f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList();
        f.profile.loggingRemainingPlots.addAll(corners); f.profile.loggingReplantingPlots.addAll(corners);
        f.setPlot(0,"minecraft:air"); f.setPlot(1,"minecraft:air");
        f.blocks.put(corners.get(1),LoggingRules.SAPLING); f.blocks.put(corners.get(1).offset(1,0,0),LoggingRules.SAPLING);
        f.add(LoggingRules.SAPLING,2); assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state()); assertEquals(0,f.plants);
        assertEquals(corners,f.profile.loggingReplantingPlots); f.add(LoggingRules.SAPLING,4);
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(6,f.plants); assertEquals(0,f.chops);
        List<Pos> planted=f.actions.stream().filter(a -> a instanceof Action.PlantSapling).map(a -> ((Action.PlantSapling)a).pos()).toList();
        assertTrue(planted.subList(0,4).stream().allMatch(f.profile.loggingPlots.get(0).plantingPositions()::contains));
    }

    @Test void unsafePartialGrowthOrConstructionInAnotherQueuedPlotPreventsAnyFurtherFelling() {
        for(String changed:List.of(LoggingRules.LOG,"minecraft:stone_bricks")) {
            Fixture f=new Fixture(2); f.profile.loggingRunActive=true;
            List<Pos> corners=f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList();
            f.profile.loggingRemainingPlots.addAll(corners); f.profile.loggingReplantingPlots.add(corners.get(0));
            f.setPlot(0,"minecraft:air"); f.blocks.put(corners.get(0),changed); f.add(LoggingRules.SAPLING,2);
            assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(0,f.chops); assertEquals(0,f.plants);
            assertEquals(corners,f.profile.loggingRemainingPlots); assertEquals(1,f.profile.loggingReplantingPlots.size());
        }
    }

    @Test void savingAManuallyEmptyPlotsNewObligationFailsBeforeAnyNewTreeActionAndRollsBack() {
        Fixture f=new Fixture(2); f.profile.loggingRunActive=true;
        List<Pos> corners=f.profile.loggingPlots.stream().map(LoggingPlot::corner).toList();
        f.profile.loggingRemainingPlots.addAll(corners); f.setPlot(0,"minecraft:air");
        assertEquals(WorkResult.State.BUSY,f.step().state()); f.failNextCheckpoint=true;
        assertEquals(WorkResult.State.BLOCKED,f.step().state()); assertTrue(f.profile.loggingReplantingPlots.isEmpty());
        assertEquals(corners,f.profile.loggingRemainingPlots); assertTrue(f.actions.isEmpty());
    }

    private static Fixture resourceFixture() {
        Fixture f=new Fixture(1); f.continuous(); f.setPlot(0,"minecraft:air");
        Pos corner=f.profile.loggingPlots.get(0).corner(); f.profile.loggingRunActive=true;
        f.profile.loggingRemainingPlots.add(corner); f.profile.loggingReplantingPlots.add(corner);
        f.add(LoggingRules.SAPLING,2); return f;
    }
    private static AutomationModule resourceNeighbour(Feature feature,int priority,java.util.function.Function<Context,WorkResult> work) {
        return new AutomationModule() {
            public Feature feature() { return feature; } public int priority() { return priority; }
            public WorkResult tick(Context c) { return work.apply(c); } public void reset() { }
        };
    }
    private static void engineUntil(Fixture f,AutomationEngine engine,BooleanSupplier done) {
        for(int i=0;i<2000 && !done.getAsBoolean();i++) { engine.tick(f.context); if(!done.getAsBoolean()) f.advance(); }
        assertTrue(done.getAsBoolean(),engine.status());
    }

    @Test void twoArrivalsWaitForTheOtherTwoWhenNoOtherUnprocessedTreeRemains() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        Pos first=f.profile.loggingPlots.get(0).corner();
        f.ticks=firstWait+100; f.add(LoggingRules.SAPLING,2);
        int sent=f.actions.size(),moves=f.moves;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=firstWait+200; assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(0,f.plants); assertEquals(2,f.chops); assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves);
        assertEquals(1,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingReplantingPlots.contains(first));
        // Fragmented inventory stacks count as stock but no native use is fabricated.
        f.inventory[10]=item(LoggingRules.SAPLING,2);
        f.until(() -> f.profile.loggingRemainingPlots.isEmpty());
        assertEquals(4,f.plants); assertEquals(2,f.chops);
        assertFalse(f.profile.loggingRemainingPlots.contains(first));
        assertTrue(f.profile.loggingPlots.get(0).plantingPositions().stream().allMatch(p -> f.id(p).equals(LoggingRules.SAPLING)));
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops); assertEquals(4,f.plants);
    }

    @Test void protectedSlotsDoNotFalselySatisfyTheFourSaplingRequirement() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        f.add(LoggingRules.SAPLING,2); f.inventory[f.profile.hoeHotbarSlot]=item(LoggingRules.SAPLING,2);
        f.ticks=firstWait+100; assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(0,f.plants); assertNull(f.pending); assertEquals(2,f.chops);
    }

    @Test void jointUpFaceApproachUsesOneStanceAndStableFarToNearPlantingOrder() {
        Fixture f=new Fixture(1); Pos corner=f.profile.loggingPlots.get(0).corner();
        f.until(() -> f.plants==1);
        assertEquals(1,f.plantingApproaches.size()); assertEquals(4,f.plantingApproaches.get(0).size());
        f.playerX=.49; // Tiny drift reverses the middle-distance tie, not the saved batch order.
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(List.of(corner.offset(1,0,1),corner.offset(0,0,1),corner.offset(1,0,0),corner),
            f.actions.stream().filter(a -> a instanceof Action.PlantSapling).map(a -> ((Action.PlantSapling)a).pos()).toList());
        assertEquals(1,f.plantingApproaches.size(),"no re-navigation while every remaining UP face remains visible");
    }

    @Test void newlyOccludedSoilReplansOnlyTheThreeRemainingPlantings() {
        Fixture f=new Fixture(1); f.until(() -> f.plants==1);
        Pos planted=f.actions.stream().filter(a -> a instanceof Action.PlantSapling).map(a -> ((Action.PlantSapling)a).pos()).findFirst().orElseThrow();
        Pos remaining=f.profile.loggingPlots.get(0).plantingPositions().stream().filter(p -> !p.equals(planted)).findFirst().orElseThrow();
        f.occludedPlanting.add(remaining);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(2,f.plantingApproaches.size()); assertEquals(3,f.plantingApproaches.get(1).size());
        assertFalse(f.plantingApproaches.get(1).contains(planted)); assertEquals(4,f.plants); assertEquals(2,f.chops);
    }

    @Test void genericSoilReachCannotReplaceTheDedicatedAllUpFaceGoal() {
        Fixture f=new Fixture(1); f.blockPlantingApproach=true;
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertFalse(f.module.sleepSafeResourceWait(f.context));
        assertEquals(0,f.plants); assertEquals(2,f.chops); assertEquals(0,f.trashed); assertEquals(0,f.crafted);
        assertEquals(1,f.profile.loggingReplantingPlots.size()); assertTrue(f.profile.loggingRunActive);
    }

    @Test void claimedArrivalStillRequiresEveryActualUpFaceBeforePlantUse() {
        Fixture f=new Fixture(1); f.keepPlantingOccluded=true;
        Pos blocked=f.profile.loggingPlots.get(0).corner(); f.occludedPlanting.add(blocked);
        f.until(() -> !f.plantingApproaches.isEmpty());
        for(int i=0;i<5;i++) { f.advance(); assertEquals(WorkResult.State.BUSY,f.step().state()); }
        assertEquals(0,f.plants); assertNull(f.pending);
        assertTrue(f.actions.stream().noneMatch(a -> a instanceof Action.PlantSapling));
        assertEquals(1,f.profile.loggingReplantingPlots.size());
    }

    @Test void explicitResetDuringSeedWaitRestoresOnlyReplantAndStartsAFreshBoundedWait() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        f.ticks=firstWait+390; f.module.reset();
        assertEquals(WorkResult.State.BUSY,f.step().state()); // Restore durable active batch.
        assertEquals(WorkResult.State.BUSY,f.step().state()); // Restore durable replant phase.
        assertEquals(WorkResult.State.BUSY,f.step().state()); // New bounded wait, no mining.
        long resumed=f.ticks;
        f.ticks=resumed+399; assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=resumed+400; assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state());
        assertEquals(2,f.chops); assertEquals(0,f.plants); assertTrue(f.profile.loggingRunActive);
        assertEquals(1,f.profile.loggingReplantingPlots.size()); assertEquals(0,f.trashed);
    }

    private static long startSeedWait(Fixture f) {
        f.saplingDrops=0; f.until(() -> f.chops==2); f.step();
        long fell=f.ticks; f.ticks=fell+79;
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertTrue(f.plantingReaches.isEmpty());
        f.ticks=fell+80; assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state()); // Select the oldest funded/remaining obligation.
        f.ticks++; WorkResult waiting=f.step();
        assertEquals(WorkResult.State.BUSY,waiting.state()); assertTrue(waiting.message().contains("묘목 도착 대기"));
        assertNull(f.pending); assertEquals(0,f.plants); assertEquals(1,f.profile.loggingReplantingPlots.size());
        return f.ticks;
    }

    @Test void reserveIsNeverRoundedDownByWholeStackTrashAndUnrelatedItemsAreUntouched() {
        Fixture f=new Fixture(1); f.profile.loggingSaplingReserve=5; f.saplingDrops=12;
        f.inventory[10]=item("minecraft:oak_sapling",30); f.inventory[11]=item("minecraft:stick",49);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(8,f.count(LoggingRules.SAPLING),"A whole eight-stack cannot be deleted when five must remain");
        assertEquals(30,f.count("minecraft:oak_sapling")); assertEquals(49,f.count("minecraft:stick"));
    }

    @Test void fullHotbarBorrowsAnOrdinarySlotAndRestoresItsExactItemBeforeCrafting() {
        Fixture f=new Fixture(1); f.fillHotbar(); ItemData original=f.inventory[0];
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(original,f.inventory[0]); assertEquals(LoggingRules.AXE,f.inventory[2].id()); assertTrue(f.inventory[4].hoe());
        assertNull(f.profile.loggingHotbarLease); assertEquals(2,f.swaps);
        assertTrue(f.events.indexOf("lease:PREPARED")<f.events.indexOf("swap:1"));
        assertTrue(f.events.indexOf("lease:RESTORING")<f.events.indexOf("swap:2"));
        assertTrue(f.events.indexOf("swap:2")<f.events.indexOf("craft:2"));
    }

    @Test void depletedBorrowedSaplingSlotMayContainKnownLoggingPickupsWhenAStaleGoalRestoresIt() {
        for (String picked:List.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.TWIG,LoggingRules.BERRY)) {
            Fixture f=parkedVisibilityFixture(); LoggingHotbarLease lease=f.profile.loggingHotbarLease;
            List<Pos> bases=f.profile.loggingPlots.get(0).plantingPositions();
            f.chopGoalTarget=bases.get(3); f.chopGoalFeet=new Pos(3,63,0); f.movementExposesChop=true;
            f.until(() -> f.pending instanceof Action.SelectHotbar); f.advance();
            ItemData pickup=item(picked,34); f.inventory[lease.hotbarSlot()]=pickup;
            f.occludedChopping.addAll(bases); f.chopGoalTarget=null; f.movementExposesChop=false;

            assertEquals(WorkResult.State.BUSY,f.step().state(),picked);
            f.until(() -> f.pending instanceof Action.SwapHotbar);
            assertEquals(new Action.SwapHotbar(lease.sourceIndex(),lease.hotbarSlot()),f.pending);
            assertEquals(LoggingHotbarLease.Stage.RESTORING,f.profile.loggingHotbarLease.stage());
            assertEquals(lease.original(),f.inventory[lease.sourceIndex()]);
            assertEquals(lease.fingerprint(),f.loggingItemFingerprint(lease.sourceIndex()));
            f.advance(); assertEquals(WorkResult.State.BUSY,f.step().state());

            assertNull(f.profile.loggingHotbarLease); assertEquals(lease.original(),f.inventory[lease.hotbarSlot()]);
            assertEquals(lease.fingerprint(),f.loggingItemFingerprint(lease.hotbarSlot()));
            assertEquals(pickup,f.inventory[lease.sourceIndex()]);
            assertEquals(0,f.chops); assertEquals(0,f.plants); assertEquals(0,f.trashed); assertEquals(0,f.crafted);
            assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void pickedLogsInFullBorrowedHotbarRestoreBeforeFreshSaplingsAreBorrowedAndPlantingResumes() {
        Fixture f=parkedPickupReplantFixture(); LoggingHotbarLease first=f.profile.loggingHotbarLease;
        ItemData pickup=f.inventory[first.hotbarSlot()];
        f.until(() -> f.pending instanceof Action.SwapHotbar);
        Action inverse=f.pending; long firstTicket=f.sequence;
        assertEquals(new Action.SwapHotbar(first.sourceIndex(),first.hotbarSlot()),inverse);
        assertEquals(LoggingHotbarLease.Stage.RESTORING,f.profile.loggingHotbarLease.stage());
        for (int i=0;i<1200;i++) { f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state()); }
        assertEquals(List.of(inverse),f.actions); assertEquals(firstTicket,f.sequence); assertEquals(0,f.plants);
        assertEquals(first.original(),f.inventory[first.sourceIndex()]); assertTrue(f.profile.nextEligibleDay.isEmpty());

        f.advance(); assertEquals(WorkResult.State.BUSY,f.step().state());
        assertNull(f.profile.loggingHotbarLease); assertEquals(first.original(),f.inventory[first.hotbarSlot()]);
        assertEquals(pickup,f.inventory[first.sourceIndex()]);
        f.until(() -> f.pending instanceof Action.SwapHotbar);
        LoggingHotbarLease second=f.profile.loggingHotbarLease;
        assertEquals(LoggingHotbarLease.Stage.PREPARED,second.stage()); assertEquals(9,second.sourceIndex());
        assertEquals(first.original(),second.original()); assertEquals(first.fingerprint(),second.fingerprint());
        assertEquals(new Action.SwapHotbar(9,first.hotbarSlot()),f.pending);
        assertEquals(pickup,f.inventory[first.sourceIndex()],"The previous protected source is not reused for seed replenishment");
        assertEquals(0,f.plants); assertEquals(1,f.swaps);

        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(first.original(),f.inventory[first.hotbarSlot()]); assertNull(f.profile.loggingHotbarLease);
        assertEquals(first.fingerprint(),f.loggingItemFingerprint(first.hotbarSlot()));
        assertEquals(3,f.swaps); assertEquals(4,f.plants); assertEquals(0,f.chops);
        assertFalse(f.profile.loggingRunActive); assertTrue(f.profile.loggingRemainingPlots.isEmpty());
        assertTrue(f.profile.loggingReplantingPlots.isEmpty()); assertEquals(11L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
    }

    @Test void failedPickupSlotRestorationNeverReplaysOrStartsTheNextSaplingBorrow() {
        Fixture f=parkedPickupReplantFixture(); LoggingHotbarLease lease=f.profile.loggingHotbarLease;
        f.until(() -> f.pending instanceof Action.SwapHotbar); Action inverse=f.pending;
        f.reject("inverse server receipt missing");
        assertEquals(WorkResult.State.BLOCKED,f.step().state()); f.restart();
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(List.of(inverse),f.actions); assertEquals(0,f.swaps); assertEquals(0,f.plants);
        assertEquals(LoggingHotbarLease.Stage.RESTORING,f.profile.loggingHotbarLease.stage());
        assertEquals(lease.original(),f.inventory[lease.sourceIndex()]); assertEquals(item(LoggingRules.LOG,34),f.inventory[lease.hotbarSlot()]);
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingReplantingPlots.size());
        assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void pickupSlotReplenishmentCannotRelaxOriginalIdentityUnknownItemsOrRestorationSafety() {
        for (String unsafe:List.of("original count","fingerprint","foreign","oversized","busy","fence","cursor","airborne","output","save")) {
            Fixture f=parkedPickupReplantFixture(); LoggingHotbarLease lease=f.profile.loggingHotbarLease;
            assertEquals(WorkResult.State.BUSY,f.step().state()); // Recover the original PARKED lease.
            assertEquals(WorkResult.State.BUSY,f.step().state()); // Resume only the durable replant phase.
            switch (unsafe) {
                case "original count" -> f.inventory[lease.sourceIndex()]=new ItemData(lease.original().id(),2,0,null,false,3000);
                case "fingerprint" -> f.fingerprintEpoch++;
                case "foreign" -> f.inventory[lease.hotbarSlot()]=item("minecraft:diamond",1);
                case "oversized" -> f.inventory[lease.hotbarSlot()]=item(LoggingRules.LOG,65);
                case "busy" -> f.forcedNativeBusy=true;
                case "fence" -> f.nativeFence="unconfirmed native action";
                case "cursor" -> f.cursor=item(LoggingRules.SAPLING,1);
                case "airborne" -> f.grounded=false;
                case "output" -> {
                    String id="11111111-1111-1111-1111-111111111111";
                    f.profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(30,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
                }
                case "save" -> f.failNextCheckpoint=true;
                default -> throw new AssertionError(unsafe);
            }
            ItemData[] before=f.inventory.clone();
            assertEquals(WorkResult.State.BLOCKED,f.finish().state(),unsafe);
            assertArrayEquals(before,f.inventory,unsafe); assertTrue(f.actions.isEmpty(),unsafe);
            assertSame(lease,f.profile.loggingHotbarLease,unsafe); assertEquals(0,f.plants);
            assertEquals(1,f.profile.loggingRemainingPlots.size()); assertEquals(1,f.profile.loggingReplantingPlots.size());
            assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void freshProofOfManuallyRestoredOriginalCanRetainKnownLoggingPickupsInTheOldSource() {
        for (String picked:List.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.TWIG,LoggingRules.BERRY)) {
            Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PARKED);
            LoggingHotbarLease lease=f.profile.loggingHotbarLease; ItemData pickup=item(picked,34);
            f.inventory[lease.sourceIndex()]=pickup; f.authoritativeRestoredLease=lease;
            ActionOutcome previous=f.outcome;
            assertEquals(WorkResult.State.BUSY,f.step().state());
            assertNull(f.profile.loggingHotbarLease); assertEquals(lease.original(),f.inventory[lease.hotbarSlot()]);
            assertEquals(pickup,f.inventory[lease.sourceIndex()]); assertSame(previous,f.outcome);
            assertTrue(f.actions.isEmpty()); assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    private static Fixture parkedPickupReplantFixture() {
        Fixture f=new Fixture(1); f.fillHotbar();
        ItemData original=new ItemData("society:galaxy_sword",1,0,null,false,3000);
        f.inventory[12]=original; f.inventory[0]=item(LoggingRules.LOG,34); f.inventory[9]=item(LoggingRules.SAPLING,4);
        f.profile.loggingHotbarLease=new LoggingHotbarLease(12,0,original,f.loggingItemFingerprint(12),LoggingHotbarLease.Stage.PARKED);
        f.profile.loggingRunActive=true; Pos corner=f.profile.loggingPlots.get(0).corner();
        f.profile.loggingRemainingPlots.add(corner); f.profile.loggingReplantingPlots.add(corner);
        f.setPlot(0,"minecraft:air");
        return f;
    }

    @Test void parkedOriginalTwigIsExcludedFromWasteAndRestoredInsteadOfDeleted() {
        Fixture f=new Fixture(1); f.fillHotbar(); f.inventory[0]=item(LoggingRules.TWIG,64);
        ItemData original=f.inventory[0];
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(original,f.inventory[0]); assertEquals(64,f.count(LoggingRules.TWIG));
        assertNull(f.profile.loggingHotbarLease); assertEquals(2,f.swaps);
        assertTrue(f.actions.stream().filter(a -> a instanceof Action.TrashLogging)
            .map(a -> (Action.TrashLogging)a).noneMatch(a -> a.expected().equals(original)));
    }

    @Test void fallingTreeSettlesForEightyActualTicksBeforeApproachingTheSoilForPlanting() {
        Fixture f=new Fixture(1); f.until(() -> f.chops==2); f.step();
        long fell=f.ticks; int sent=f.actions.size(),moves=f.moves;
        for(int elapsed=0;elapsed<80;elapsed++) {
            f.ticks=fell+elapsed;
            assertEquals(WorkResult.State.BUSY,f.step().state());
            assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves); assertEquals(0,f.plants);
        }
        f.ticks=fell+80;
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(4,f.plants);
        assertTrue(f.plantingReaches.stream().allMatch(reach -> reach==3.25));
        assertFalse(f.plantingReaches.isEmpty());
        assertTrue(f.actions.stream().filter(a -> a instanceof Action.PlantSapling).allMatch(a -> f.profile.loggingPlots.stream()
            .anyMatch(p -> p.plantingPositions().contains(((Action.PlantSapling)a).pos()))));
    }

    @Test void allGrownRepeatsOnTheSameDayOnlyAfterThePollIntervalEvenAcrossSchedulerResets() {
        Fixture f=new Fixture(1); f.continuous(); f.profile.loggingCheckTicks=120;
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(11L,f.profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        f.setPlot(0,LoggingRules.LOG); long finished=f.ticks; int sent=f.actions.size(),moves=f.moves;
        for(int elapsed=0;elapsed<120;elapsed++) {
            f.ticks=finished+elapsed; f.module.reset();
            assertEquals(WorkResult.State.IDLE,f.step().state());
            assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves);
        }
        f.ticks=finished+120; f.module.reset();
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(10,f.day); assertEquals(4,f.chops); assertEquals(8,f.plants);
    }

    @Test void unripeAllGrownPollSurvivesResetButExplicitOnceAndClockRollbackCanRecheck() {
        Fixture f=new Fixture(1); f.continuous(); f.setPlot(0,LoggingRules.SAPLING); f.ticks=1000;
        assertEquals(WorkResult.State.IDLE,f.step().state()); f.setPlot(0,LoggingRules.LOG);
        f.module.reset(); f.ticks++;
        assertEquals(WorkResult.State.IDLE,f.step().state()); assertTrue(f.actions.isEmpty());
        f.ticks=1; f.module.reset();
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertTrue(f.profile.loggingRunActive);
        Fixture once=new Fixture(1); once.continuous(); once.setPlot(0,LoggingRules.SAPLING);
        assertEquals(WorkResult.State.IDLE,once.step().state()); once.setPlot(0,LoggingRules.LOG); once.session.oneShotFeature=Feature.LOGGING;
        once.module.reset(); assertEquals(WorkResult.State.IDLE,once.finish().state()); assertEquals(2,once.chops);
    }

    @Test void allSixPlotsAndTwentyFourPlantingsCompleteBeforeAnyWasteIsDeleted() {
        Fixture f=new Fixture(6); f.fillHotbar(); ItemData original=f.inventory[0];
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(12,f.chops); assertEquals(24,f.plants); assertEquals(12,f.crafted);
        assertEquals(24,f.stored(LoggingRules.FIRE_LOG)); assertEquals(6,f.shipped(LoggingRules.BERRY));
        assertEquals(original,f.inventory[0]); assertEquals(2,f.swaps);
        assertTrue(f.events.indexOf("plant:24")<f.events.indexOf("trash:"+LoggingRules.SAPLING));
        assertTrue(f.events.indexOf("plant:24")<f.events.indexOf("trash:"+LoggingRules.TWIG));
    }

    @Test void parkedHotbarLeaseSurvivesReconnectAndManualSlotChangesBlockAnInverseSwap() {
        Fixture f=new Fixture(1); f.fillHotbar();
        f.until(() -> f.profile.loggingHotbarLease!=null && f.profile.loggingHotbarLease.stage()==LoggingHotbarLease.Stage.PARKED);
        int source=f.profile.loggingHotbarLease.sourceIndex(); f.advance(); f.restart();
        f.inventory[source]=item("minecraft:diamond",1);
        int sent=f.actions.size(); WorkResult result=f.step();
        assertEquals(WorkResult.State.BLOCKED,result.state()); assertEquals(sent,f.actions.size());
        assertNotNull(f.profile.loggingHotbarLease); assertEquals("minecraft:diamond",f.inventory[source].id());
    }

    @Test void preparedLeaseWithoutAnObservedSwapIsNotReplayedOnRestart() {
        Fixture f=new Fixture(1); f.fillHotbar();
        f.until(() -> f.pending instanceof Action.SwapHotbar && f.profile.loggingHotbarLease!=null);
        assertEquals(LoggingHotbarLease.Stage.PREPARED,f.profile.loggingHotbarLease.stage());
        f.pending=null; f.restart(); int sent=f.actions.size();
        assertEquals(WorkResult.State.BLOCKED,f.step().state()); assertEquals(sent,f.actions.size());
    }

    @Test void serverProvedManualRestorationSettlesPreparedAndParkedCustodyWithoutReplayingTheSwap() {
        for (LoggingHotbarLease.Stage stage:List.of(LoggingHotbarLease.Stage.PREPARED,LoggingHotbarLease.Stage.PARKED)) {
            for (int saplings:new int[]{0,4}) {
                Fixture f=manuallyRestoredLeaseFixture(stage); LoggingHotbarLease lease=f.profile.loggingHotbarLease;
                f.inventory[lease.sourceIndex()]=item(LoggingRules.SAPLING,saplings);
                f.profile.loggingSaplingReserve=saplings;
                f.authoritativeRestoredLease=lease;
                ActionOutcome cancelled=f.outcome;

                assertEquals(WorkResult.State.BUSY,f.step().state());
                assertNull(f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
                assertTrue(f.actions.isEmpty()); assertSame(cancelled,f.outcome);
                assertTrue(f.profile.nextEligibleDay.isEmpty(),"Custody proof is not logging completion");
                assertEquals(List.of("checkpoint:active"),f.events);

                assertEquals(WorkResult.State.IDLE,f.finish().state());
                assertFalse(f.profile.loggingRunActive); assertEquals(0,f.swaps); assertTrue(f.actions.isEmpty());
                assertEquals(lease.original(),f.inventory[lease.hotbarSlot()]);
                assertEquals(saplings,f.count(LoggingRules.SAPLING)); assertSame(cancelled,f.outcome);
            }
        }
    }

    @Test void physicalManualRestorationWithoutServerProofLeavesPreparedAndParkedLeasesUnchanged() {
        for (LoggingHotbarLease.Stage stage:List.of(LoggingHotbarLease.Stage.PREPARED,LoggingHotbarLease.Stage.PARKED)) {
            Fixture f=manuallyRestoredLeaseFixture(stage); LoggingHotbarLease lease=f.profile.loggingHotbarLease;
            assertEquals(WorkResult.State.BLOCKED,f.step().state());
            assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
            assertTrue(f.actions.isEmpty()); assertTrue(f.events.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void supportedRestorationRefreshKeepsOnePendingTicketUntilALateFullProvesCustody() {
        for (LoggingHotbarLease.Stage stage:List.of(LoggingHotbarLease.Stage.PREPARED,LoggingHotbarLease.Stage.PARKED)) {
            Fixture f=manuallyRestoredLeaseFixture(stage); LoggingHotbarLease lease=f.profile.loggingHotbarLease;
            f.inventoryRefreshSupported=true;
            assertEquals(WorkResult.State.BUSY,f.step().state());
            Action.RefreshInventory refresh=assertInstanceOf(Action.RefreshInventory.class,f.pending);
            long ticket=f.sequence;
            for (int i=0;i<f.profile.interactionTimeoutTicks+1200;i++) {
                f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state());
            }
            assertSame(refresh,f.pending); assertEquals(ticket,f.sequence);
            assertEquals(ActionOutcome.State.PENDING,f.outcome.state());
            assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
            assertEquals(List.of(refresh),f.actions); assertTrue(f.events.isEmpty()); assertEquals(0,f.swaps);

            f.advance(); // The fixture now delivers the actual refreshed server inventory.
            assertEquals(WorkResult.State.BUSY,f.step().state());
            assertNull(f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
            assertTrue(f.profile.nextEligibleDay.isEmpty()); assertEquals(ticket,f.sequence);
            assertEquals(List.of(refresh),f.actions); assertEquals(0,f.swaps);
            assertEquals(List.of("refresh:confirmed","checkpoint:active"),f.events);
            assertEquals(WorkResult.State.IDLE,f.finish().state());
            assertEquals(List.of(refresh),f.actions); assertEquals(lease.original(),f.inventory[lease.hotbarSlot()]);
        }
    }

    @Test void acknowledgedRefreshWithoutRestorationProofFailsClosedWithoutAnotherRefresh() {
        Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PREPARED);
        LoggingHotbarLease lease=f.profile.loggingHotbarLease;
        f.inventoryRefreshSupported=true; f.refreshProvesRestoration=false;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        Action.RefreshInventory refresh=assertInstanceOf(Action.RefreshInventory.class,f.pending);
        f.advance();
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        for (int i=0;i<1200;i++) { f.ticks++; assertEquals(WorkResult.State.BLOCKED,f.step().state()); }
        assertEquals(List.of(refresh),f.actions); assertEquals(0,f.swaps);
        assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.nextEligibleDay.isEmpty()); assertEquals(List.of("refresh:confirmed"),f.events);
    }

    @Test void rejectedRefreshCannotResendOrReplaceTheUnfinishedLease() {
        Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PARKED);
        LoggingHotbarLease lease=f.profile.loggingHotbarLease; f.inventoryRefreshSupported=true;
        f.step(); Action.RefreshInventory refresh=assertInstanceOf(Action.RefreshInventory.class,f.pending);
        f.reject("refresh unavailable");
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(List.of(refresh),f.actions); assertSame(lease,f.profile.loggingHotbarLease);
        assertTrue(f.events.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void refreshedInventoryWithAnUnrestoredOrReplacedLeaseCannotBecomeANewSwap() {
        for (boolean replaceLease:new boolean[]{false,true}) {
            Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PREPARED);
            LoggingHotbarLease originalLease=f.profile.loggingHotbarLease; f.inventoryRefreshSupported=true;
            f.step(); Action.RefreshInventory refresh=assertInstanceOf(Action.RefreshInventory.class,f.pending);
            f.advance();
            if (replaceLease) f.profile.loggingHotbarLease=originalLease.withStage(LoggingHotbarLease.Stage.PARKED);
            else {
                f.inventory[originalLease.sourceIndex()]=f.inventory[originalLease.hotbarSlot()];
                f.inventory[originalLease.hotbarSlot()]=ItemData.EMPTY;
            }
            LoggingHotbarLease retained=f.profile.loggingHotbarLease;
            assertEquals(WorkResult.State.BLOCKED,f.step().state());
            assertEquals(WorkResult.State.BLOCKED,f.step().state());
            assertEquals(List.of(refresh),f.actions); assertEquals(0,f.swaps);
            assertSame(retained,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
            assertTrue(f.profile.nextEligibleDay.isEmpty()); assertEquals(List.of("refresh:confirmed"),f.events);
        }
    }

    @Test void manualStopDuringRefreshDoesNotResumeWhenServerCustodyProofArrivesLater() {
        Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PREPARED);
        LoggingHotbarLease lease=f.profile.loggingHotbarLease; f.inventoryRefreshSupported=true;
        AutomationEngine engine=new AutomationEngine(List.of(f.module));
        engine.startOnce(f.context,Feature.LOGGING); engine.tick(f.context);
        Action.RefreshInventory refresh=assertInstanceOf(Action.RefreshInventory.class,f.pending);
        long ticket=f.sequence;
        engine.stop(f.context,AutomationEngine.State.PAUSED,"manual F8 OFF");
        assertEquals(ActionOutcome.State.CANCELLED,f.outcome.state());
        f.authoritativeRestoredLease=lease; // Late passive evidence never starts the engine.
        for (int i=0;i<1200;i++) { f.ticks++; engine.tick(f.context); }

        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals("manual F8 OFF",engine.status());
        assertEquals(ActionOutcome.State.CANCELLED,f.outcome.state()); assertEquals(ticket,f.sequence);
        assertEquals(List.of(refresh),f.actions); assertNull(f.pending); assertEquals(0,f.swaps);
        assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.events.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void serverRestorationProofCannotOverrideChangedLiveOriginalFingerprintOrForeignSourceItem() {
        for (int changed=0;changed<3;changed++) {
            Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PREPARED);
            LoggingHotbarLease lease=f.profile.loggingHotbarLease; f.authoritativeRestoredLease=lease;
            if (changed==0) f.inventory[lease.hotbarSlot()]=item("minecraft:torch",54);
            else if (changed==1) f.fingerprintEpoch++;
            else f.inventory[lease.sourceIndex()]=item("minecraft:diamond",1);

            assertEquals(WorkResult.State.BLOCKED,f.step().state());
            assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
            assertTrue(f.actions.isEmpty()); assertTrue(f.events.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void failedManualRestorationCheckpointPreservesTheLeaseAndCancelledAction() {
        Fixture f=manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage.PREPARED);
        LoggingHotbarLease lease=f.profile.loggingHotbarLease; f.authoritativeRestoredLease=lease;
        ActionOutcome cancelled=f.outcome; f.failNextCheckpoint=true;

        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.actions.isEmpty()); assertTrue(f.events.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertSame(cancelled,f.outcome);
    }

    private static Fixture manuallyRestoredLeaseFixture(LoggingHotbarLease.Stage stage) {
        Fixture f=cleanupFixture(); f.inventory[0]=item("minecraft:torch",55);
        f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,f.inventory[0],f.loggingItemFingerprint(0),stage);
        f.outcome=new ActionOutcome(ActionOutcome.State.CANCELLED,"old borrow cancelled");
        return f;
    }

    @Test void reconnectAfterBorrowAcknowledgementUsesParkedEvidenceAndEventuallyRestoresTheItem() {
        Fixture f=new Fixture(1); f.fillHotbar(); ItemData original=f.inventory[0];
        f.until(() -> f.pending instanceof Action.SwapHotbar && f.profile.loggingHotbarLease!=null);
        f.advance(); // native ACK applied, module has not yet checkpointed PARKED
        assertEquals(LoggingHotbarLease.Stage.PREPARED,f.profile.loggingHotbarLease.stage()); f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(original,f.inventory[0]); assertEquals(2,f.swaps);
    }

    @Test void craftRunsMultipleSixLogBatchesAndClosesOnlyAfterTheGridIsEmpty() {
        Fixture f=new Fixture(1); f.setPlot(0,LoggingRules.SAPLING); f.add(LoggingRules.LOG,472); f.add(LoggingRules.FIRE_LOG,11);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(78,f.crafted); assertEquals(2,f.craftCalls); assertEquals(89,f.stored(LoggingRules.FIRE_LOG)); assertEquals(4,f.stored(LoggingRules.LOG));
        assertTrue(f.craftingGridEmpty); assertEquals(0,f.chops);
    }

    @Test void manualFragmentedElevenLogHaulsAndOneRealPickupCanCompleteBeyondSixtyFourBatches() {
        Fixture f=cleanupFixture(); f.manualCraftBatches=true;
        Arrays.fill(f.inventory,item(LoggingRules.LOG,11)); f.inventory[35]=ItemData.EMPTY;
        // The first output needs one REAL empty slot. Individual placement later
        // empties ingredient slots; a subsequent pickup may safely occupy one.
        f.until(() -> f.craftCalls==36);
        assertTrue(Arrays.stream(f.inventory).anyMatch(ItemData::empty)); f.add(LoggingRules.LOG,11);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(65,f.craftCalls); assertEquals(66,f.crafted); assertEquals(66,f.stored(LoggingRules.FIRE_LOG));
        assertEquals(0,f.count(LoggingRules.LOG)); assertEquals(0,f.chops); assertEquals(0,f.plants);
    }

    @Test void failedCraftOrNonemptyGridCannotCompleteCloseOrSendProducts() {
        Fixture f=new Fixture(1); f.setPlot(0,LoggingRules.SAPLING); f.add(LoggingRules.LOG,12);
        f.until(() -> f.pending instanceof Action.CraftFireLogs); f.reject("recipe unknown");
        assertEquals(WorkResult.State.BLOCKED,f.step().state()); assertEquals(0,f.stored(LoggingRules.FIRE_LOG));
        assertTrue(f.profile.loggingRunActive); assertTrue(f.profile.nextEligibleDay.isEmpty());
        Fixture dirty=new Fixture(1); dirty.setPlot(0,LoggingRules.SAPLING); dirty.add(LoggingRules.LOG,12);
        dirty.until(() -> dirty.pending instanceof Action.CraftFireLogs); dirty.advance(); dirty.craftingGridEmpty=false;
        assertEquals(WorkResult.State.BLOCKED,dirty.step().state());
        assertTrue(dirty.actions.stream().noneMatch(a -> a instanceof Action.CloseContainer));
    }

    @Test void contaminatedWoodStorageIsSkippedWithoutWithdrawingOrMovingForeignItems() {
        Fixture f=new Fixture(1); f.setPlot(0,LoggingRules.SAPLING); f.add(LoggingRules.FIRE_LOG,5);
        f.chests.get(f.woodPos)[0]=item("minecraft:diamond",3); f.addWoodChest(new Pos(20,64,0));
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(5,f.stored(LoggingRules.FIRE_LOG));
        assertEquals(item("minecraft:diamond",3),f.chests.get(f.woodPos)[0]);
        assertTrue(f.actions.stream().filter(a -> a instanceof Action.QuickMove).map(a -> (Action.QuickMove)a).allMatch(a -> a.slot()>=27));
    }

    @Test void fullStorageRetriesAfterAnAcknowledgedCloseWithoutRecuttingOrManualRestart() {
        Fixture f=new Fixture(1); Arrays.fill(f.chests.get(f.woodPos),item(LoggingRules.FIRE_LOG,64));
        assertEquals(WorkResult.State.RESOURCE_WAIT,f.finish().state()); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.loggingRemainingPlots.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        assertFalse(f.menu().container());assertNull(f.pending);
        f.chests.get(f.woodPos)[0]=ItemData.EMPTY; f.ticks+=1200;
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops);
    }

    @Test void completionSaveFailureRollsBackTheDueDateAndKeepsTheCleanupBatchActive() {
        Fixture f=new Fixture(1); f.failCompletionCheckpoint=true;
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.nextEligibleDay.isEmpty()); assertTrue(f.profile.loggingRemainingPlots.isEmpty());
        f.failCompletionCheckpoint=false; f.restart();
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(2,f.chops);
    }

    @Test void unloadedOrForeignPlantingCellsFailBeforeAnyActionAndNeverExpandThePlot() {
        Fixture f=new Fixture(1); f.unloaded.add(f.profile.loggingPlots.get(0).corner());
        assertEquals(WorkResult.State.BLOCKED,f.step().state()); assertTrue(f.actions.isEmpty());
        Fixture foreign=new Fixture(1); foreign.blocks.put(foreign.profile.loggingPlots.get(0).corner(),"minecraft:oak_log");
        assertEquals(WorkResult.State.BLOCKED,foreign.step().state()); assertTrue(foreign.actions.isEmpty());
    }

    @Test void loggingObservesAnUnloadedPlotBeforeCreatingItsDurableCutBatch() {
        Fixture f=new Fixture(2); f.observeLoads=true;
        Pos far=f.profile.loggingPlots.get(1).corner(); f.unloaded.add(far);
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertEquals(List.of(far),f.observed);
        assertFalse(f.profile.loggingRunActive); assertTrue(f.actions.isEmpty());
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.chops); assertEquals(8,f.plants);
    }

    @Test void dailyLoggingFutureGateNeverVisitsItsUnloadedPlots() {
        Fixture f=new Fixture(1); f.continuous(); f.profile.loggingMode=LoggingMode.DAILY_GROWN; f.observeLoads=true;
        f.profile.nextEligibleDay.put(LoggingRules.DUE_KEY,11L); f.unloaded.add(f.profile.loggingPlots.get(0).corner());
        assertEquals(WorkResult.State.IDLE,f.step().state()); assertTrue(f.observed.isEmpty()); assertTrue(f.actions.isEmpty());
    }

    @Test void retryableObservationFailureDuringDurableReplantWaitsWithoutPausingTheEngine() {
        Fixture f=new Fixture(1); Pos corner=f.profile.loggingPlots.get(0).corner(); f.setPlot(0,"minecraft:air");
        f.profile.loggingRunActive=true;f.profile.loggingRemainingPlots.add(corner);f.profile.loggingReplantingPlots.add(corner);
        f.unloaded.add(corner);f.retryableObserve=true;
        AutomationEngine engine=new AutomationEngine(List.of(f.module));engine.startOnce(f.context,Feature.LOGGING);
        engine.tick(f.context);f.ticks++;engine.tick(f.context);
        assertEquals(AutomationEngine.State.WAITING,engine.state());assertTrue(f.profile.loggingRunActive);
        assertEquals(List.of(corner),f.profile.loggingReplantingPlots);assertTrue(f.actions.isEmpty());
        assertFalse(f.module.sleepSafeResourceWait(f.context));
    }

    @Test void cleanupWaitsForTwentyUnchangedTicksAndUsesTheLateThirtyFourSaplings() {
        Fixture f=cleanupFixture(); f.add(LoggingRules.SAPLING,28); enterCleanup(f);
        quietTicks(f,19); assertTrue(f.actions.isEmpty());
        f.add(LoggingRules.SAPLING,6); f.advance(); assertEquals(WorkResult.State.BUSY,f.step().state());
        quietTicks(f,19); assertTrue(f.actions.isEmpty());
        f.advance(); f.step();
        Action.TrashLogging trash=assertInstanceOf(Action.TrashLogging.class,f.pending);
        assertEquals(34,trash.expected().count()); assertEquals(1,f.actions.size());
        // Waiting for this real request must never dispatch another deletion.
        for(int i=0;i<30;i++) { f.ticks++; assertEquals(WorkResult.State.BUSY,f.step().state()); }
        assertEquals(1,f.actions.size()); assertTrue(f.profile.loggingRunActive);
    }

    @Test void continuouslyChangingCleanupInventoryTimesOutWithoutTrashRestoreOrCraft() {
        Fixture f=cleanupFixture(); f.inventory[9]=item("minecraft:torch",55);
        LoggingHotbarLease lease=new LoggingHotbarLease(9,0,f.inventory[9],f.loggingItemFingerprint(9),LoggingHotbarLease.Stage.PARKED);
        f.profile.loggingHotbarLease=lease; f.inventory[10]=item(LoggingRules.SAPLING,28); enterCleanup(f);
        WorkResult result=null;
        for(int i=1;i<=400;i++) {
            f.ticks++; f.inventory[10]=item(LoggingRules.SAPLING,i%2==0 ? 28 : 34); result=f.step();
            assertEquals(i<400 ? WorkResult.State.BUSY : WorkResult.State.BLOCKED,result.state(),result.message());
        }
        assertTrue(result.message().contains("20초")); assertTrue(f.actions.isEmpty());
        assertSame(lease,f.profile.loggingHotbarLease); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.nextEligibleDay.isEmpty()); assertEquals(item("minecraft:torch",55),f.inventory[9]);
    }

    @Test void cleanupResetTickGapAndRepeatedTickCannotBorrowEarlierQuietTime() {
        Fixture f=cleanupFixture(); f.add(LoggingRules.TWIG,3); enterCleanup(f); quietTicks(f,10);
        f.restart(); enterCleanup(f);
        for(int i=0;i<30;i++) assertEquals(WorkResult.State.BUSY,f.step().state());
        quietTicks(f,19); assertTrue(f.actions.isEmpty());
        f.ticks+=2; f.step(); quietTicks(f,19); assertTrue(f.actions.isEmpty());
        f.advance(); f.step(); assertInstanceOf(Action.TrashLogging.class,f.pending);
    }

    @Test void aConfirmedTrashChangeRequiresANewQuietWindowBeforeAnotherStack() {
        Fixture f=cleanupFixture(); f.add(LoggingRules.SAPLING,28); f.add(LoggingRules.TWIG,3); enterCleanup(f);
        quietTicks(f,20); assertInstanceOf(Action.TrashLogging.class,f.pending);
        f.advance(); f.step(); assertEquals(1,f.actions.size());
        quietTicks(f,19); assertEquals(1,f.actions.size());
        f.advance(); f.step(); assertEquals(2,f.actions.size());
        assertInstanceOf(Action.TrashLogging.class,f.pending);
    }

    @Test void quietFingerprintsAreSampledAtEndpointsAndUnsupportedBackendsUseReducedInventory() {
        Fixture f=cleanupFixture(); f.add(LoggingRules.TWIG,3); enterCleanup(f);
        int baselineCalls=f.fingerprintCalls;
        quietTicks(f,19); assertEquals(baselineCalls,f.fingerprintCalls,"Do not hash 36 slots every tick");
        f.fingerprintEpoch++; f.advance(); f.step(); assertTrue(f.actions.isEmpty());
        quietTicks(f,19); assertTrue(f.actions.isEmpty());
        f.advance(); f.step(); assertInstanceOf(Action.TrashLogging.class,f.pending);
        Fixture fallback=cleanupFixture(); fallback.fingerprintSupported=false; fallback.add(LoggingRules.TWIG,3);
        enterCleanup(fallback); quietTicks(fallback,20); assertInstanceOf(Action.TrashLogging.class,fallback.pending);
    }

    @Test void incompleteOrDuplicateNormalInventoryMappingBlocksCleanupWithoutActions() {
        Fixture missing=cleanupFixture(); missing.add(LoggingRules.TWIG,3); missing.omittedInventorySlot=35;
        assertEquals(WorkResult.State.BLOCKED,missing.finish().state()); assertTrue(missing.actions.isEmpty());
        Fixture duplicate=cleanupFixture(); duplicate.add(LoggingRules.TWIG,3); duplicate.duplicateInventorySlot=true;
        assertEquals(WorkResult.State.BLOCKED,duplicate.finish().state()); assertTrue(duplicate.actions.isEmpty());
    }

    @Test void newPickupsBeforeRestoreAndInsideTheCraftingMenuRequireFreshQuietWindows() {
        Fixture f=cleanupFixture(); f.inventory[9]=item("minecraft:torch",55); f.inventory[10]=item(LoggingRules.LOG,6);
        f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,f.inventory[9],f.loggingItemFingerprint(9),LoggingHotbarLease.Stage.PARKED);
        enterCleanup(f); quietTicks(f,20); assertNull(f.pending); // WASTE -> RESTORE, no mutation.
        f.add(LoggingRules.LOG,6); f.advance(); f.step(); quietTicks(f,19);
        assertTrue(f.actions.isEmpty()); assertEquals(LoggingHotbarLease.Stage.PARKED,f.profile.loggingHotbarLease.stage());
        f.advance(); f.step(); assertInstanceOf(Action.SwapHotbar.class,f.pending);
        f.advance(); f.step(); assertNull(f.profile.loggingHotbarLease); assertEquals(item("minecraft:torch",55),f.inventory[0]);
        f.until(() -> f.pending instanceof Action.UseBlock); f.advance(); f.add(LoggingRules.LOG,6); f.step();
        int actionsBeforeCraft=f.actions.size(); quietTicks(f,19); assertEquals(actionsBeforeCraft,f.actions.size());
        f.advance(); f.step(); assertInstanceOf(Action.CraftFireLogs.class,f.pending);
        assertEquals(0,f.crafted,"Preparing a request is not a native crafting acknowledgement");
    }

    private static Fixture cleanupFixture() {
        Fixture f=new Fixture(1); f.setPlot(0,LoggingRules.SAPLING); f.profile.loggingRunActive=true;
        return f;
    }
    private static void enterCleanup(Fixture f) {
        assertEquals(WorkResult.State.BUSY,f.step().state()); f.advance(); // START -> PLOT.
        assertEquals(WorkResult.State.BUSY,f.step().state()); f.advance(); // PLOT -> WASTE.
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertNull(f.pending); // First quiet sample.
    }
    private static void quietTicks(Fixture f,int count) {
        for(int i=0;i<count;i++) { f.advance(); WorkResult result=f.step(); assertEquals(WorkResult.State.BUSY,result.state(),result.message()); }
    }

    private static ItemData item(String id,int count) { return count==0 ? ItemData.EMPTY : new ItemData(id,count,0,null,false,1000); }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); SessionState session=new SessionState(); LoggingModule module=new LoggingModule();
        Context context;
        final ItemData[] inventory=new ItemData[36]; final Map<Pos,String> blocks=new HashMap<>(); final Set<Pos> unloaded=new HashSet<>();
        final Map<Pos,ItemData[]> chests=new LinkedHashMap<>(); final Pos tablePos=new Pos(10,64,0),woodPos=new Pos(12,64,0),shippingPos=new Pos(14,64,0);
        final List<Action> actions=new ArrayList<>(); final List<String> events=new ArrayList<>();
        final List<Double> plantingReaches=new ArrayList<>();
        final List<List<Pos>> plantingApproaches=new ArrayList<>();
        final Set<Pos> occludedPlanting=new HashSet<>();
        final Set<Pos> occludedChopping=new HashSet<>();
        boolean blockAllChopRays,movementExposesChop,alternateChopGoal,holdChopMovement; int chopRays,actualChopQueries,chopGoalChecks;
        final Set<Pos> chopRayTargets=new HashSet<>();
        Pos chopGoalTarget,chopGoalFeet; String treeRejection;
        final Map<Pos,ArrayDeque<Pos>> leafObstructions=new LinkedHashMap<>();
        boolean actualLeafObstructionAvailable=true; int leafRays,leavesCleared;
        boolean requireLeafStance,holdLeafStanceMovement,blockLeafStanceMovement,leafStanceArrived;
        Pos leafProofStance,lastLeafStance;int leafStanceMoves;double leafStanceTolerance;
        final Set<Pos> leafProofStances=new HashSet<>(),rejectedActualLeafStances=new HashSet<>(),rejectedPredictedLeafStances=new HashSet<>();
        final Map<Pos,Pos> leafAtStance=new HashMap<>();final List<Pos> leafStanceVisits=new ArrayList<>();
        boolean grounded=true,forcedNativeBusy; String nativeFence; ItemData cursor=ItemData.EMPTY;
        LoggingHotbarLease authoritativeRestoredLease;
        boolean inventoryRefreshSupported,refreshProvesRestoration=true;
        final Set<Pos> loggingTargets=new HashSet<>(),blockedNavigation=new HashSet<>(); int loggingMoves;
        Navigation.Failure navigationFailure=Navigation.Failure.NONE;ActionOutcome navigationOutcome;boolean safeNavigationFailure;
        final Map<Pos,Integer> nativeChops=new HashMap<>(); int strokesPerTree=2;
        long ticks,day=10,sequence; int selected=4,moves,chops,plants,trashed,crafted,craftCalls,swaps,saplingDrops=8;
        double playerX=.5,playerY=64,playerZ=.5;
        int fingerprintCalls,fingerprintEpoch,omittedInventorySlot=-1;
        boolean fingerprintSupported=true,duplicateInventorySlot,manualCraftBatches,observeLoads,retryableObserve,rejectUnloadedBlockReads;
        final List<Pos> observed=new ArrayList<>();
        int containerId,nextContainer=1; Pos opened;
        Action pending; ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        boolean craftingGridEmpty=true,failNextCheckpoint,failCompletionCheckpoint,blockPlantingApproach,keepPlantingOccluded;
        Fixture(int plots) {
            Arrays.fill(inventory,ItemData.EMPTY); inventory[2]=item(LoggingRules.AXE,1);
            inventory[4]=new ItemData("minecraft:golden_hoe",1,0,null,true,1000); profile.hoeHotbarSlot=4; profile.loggingAxeHotbarSlot=2;
            for (Feature feature:Feature.values()) profile.enabled.put(feature,false); session.oneShotFeature=Feature.LOGGING;
            for (int i=0;i<plots;i++) { profile.loggingPlots.add(new LoggingPlot("plot"+i,new Pos(i*6,64,0))); setPlot(i,LoggingRules.LOG); }
            profile.pois.add(new Poi(tablePos,PoiKind.LOGGING_CRAFTING_TABLE,"table",null)); addWoodChest(woodPos);
            profile.pois.add(new Poi(shippingPos,PoiKind.SHIPPING_BIN,"shipping",null)); chests.put(shippingPos,empty(54)); reconnectContext();
        }
        void reconnectContext() { context=new Context(this,this,this,profile,session,this::checkpoint); }
        void continuous() { session.oneShotFeature=null; profile.enabled.put(Feature.LOGGING,true); }
        void restart() { assertNull(pending,"Native in-flight operations require the action layer's fence, not module replay"); module.reset(); module=new LoggingModule(); session=new SessionState(); session.oneShotFeature=Feature.LOGGING; reconnectContext(); }
        void checkpoint() {
            if (failNextCheckpoint || failCompletionCheckpoint && !profile.loggingRunActive) { failNextCheckpoint=false; throw new IllegalStateException("test disk unavailable"); }
            events.add("checkpoint:"+(profile.loggingRunActive ? "active" : "complete"));
            if (profile.loggingHotbarLease!=null) events.add("lease:"+profile.loggingHotbarLease.stage());
        }
        void fillHotbar() { for(int i=0;i<9;i++) if(i!=2 && i!=4) inventory[i]=item("minecraft:test_tool_"+i,1); }
        void setPlot(int i,String id) { for(Pos p:profile.loggingPlots.get(i).plantingPositions()) blocks.put(p,id); }
        String id(Pos pos) { return blocks.getOrDefault(pos,"minecraft:air"); }
        WorkResult step() { return module.tick(context); }
        void until(BooleanSupplier condition) {
            for(int i=0;i<2000 && !condition.getAsBoolean();i++) { WorkResult r=step(); assertNotEquals(WorkResult.State.BLOCKED,r.state(),r.message()); if(!condition.getAsBoolean()) advance(); }
            assertTrue(condition.getAsBoolean(),"bounded fixture condition did not occur");
        }
        WorkResult finish() {
            WorkResult result=null;
            for(int i=0;i<4000;i++) { result=step(); if(result.state()!=WorkResult.State.BUSY) return result; advance(); }
            fail("Logging did not terminate: "+result); return result;
        }
        void reject(String reason) { pending=null; outcome=new ActionOutcome(ActionOutcome.State.FAILED,reason); }
        void advance() {
            ticks++; if(pending==null) return;
            Action action=pending; pending=null; int confirmed=0;
            if(action instanceof Action.SelectHotbar select) selected=select.slot();
            else if(action instanceof Action.SwapHotbar swap) {
                assertNotEquals(profile.hoeHotbarSlot,swap.hotbarSlot()); assertNotEquals(profile.loggingAxeHotbarSlot,swap.hotbarSlot());
                ItemData old=inventory[swap.hotbarSlot()]; inventory[swap.hotbarSlot()]=inventory[swap.inventoryIndex()]; inventory[swap.inventoryIndex()]=old;
                events.add("swap:"+(++swaps));
            } else if(action instanceof Action.RefreshInventory) {
                if (refreshProvesRestoration) authoritativeRestoredLease=profile.loggingHotbarLease;
                events.add("refresh:confirmed");
            } else if(action instanceof Action.ClearLoggingLeaf leaf) {
                assertEquals(2,selected);assertEquals(LoggingRules.AXE,inventory[selected].id());
                assertEquals(leaf.pos(),nextLeaf(leaf.stump()));assertEquals(LoggingLeafRules.LEAVES,id(leaf.pos()));
                assertTrue(LoggingLeafRules.authorised(context,leaf.stump(),leaf.pos()));
                blocks.put(leaf.pos(),"minecraft:air");leafObstructions.get(leaf.stump()).removeFirst();
                if(nextLeaf(leaf.stump())==null) profile.loggingPlots.stream().filter(p->p.plantingPositions().contains(leaf.stump()))
                    .forEach(p->occludedChopping.removeAll(p.plantingPositions()));
                confirmed=1;events.add("leaf:"+(++leavesCleared));
            } else if(action instanceof Action.ChopTree chop) {
                assertTrue(profile.loggingRunActive); assertTrue(profile.loggingRemainingPlots.stream().anyMatch(p -> profile.loggingPlots.stream()
                    .anyMatch(plot -> plot.corner().equals(p) && plot.plantingPositions().contains(chop.pos()))));
                assertEquals(2,selected); assertEquals(LoggingRules.AXE,inventory[selected].id());
                LoggingPlot plot=profile.loggingPlots.stream().filter(p -> p.plantingPositions().contains(chop.pos())).findFirst().orElseThrow();
                if(nativeChops.merge(plot.corner(),1,Integer::sum)<strokesPerTree) blocks.put(chop.pos(),LoggingRules.CHOPPED_LOG);
                else {
                    nativeChops.remove(plot.corner());
                    for(Pos p:plot.plantingPositions()) blocks.put(p,"minecraft:air");
                    add(LoggingRules.LOG,12); add(LoggingRules.FIRE_LOG,2); add(LoggingRules.SAPLING,saplingDrops); add(LoggingRules.TWIG,3); add(LoggingRules.BERRY,1);
                }
                confirmed=1; events.add("chop:"+(++chops));
            } else if(action instanceof Action.PlantSapling plant) {
                assertEquals("minecraft:air",id(plant.pos())); assertTrue(inventory[selected].is(LoggingRules.SAPLING));
                assertTrue(profile.loggingReplantingPlots.stream().anyMatch(p -> profile.loggingPlots.stream()
                    .anyMatch(plot -> plot.corner().equals(p) && plot.plantingPositions().contains(plant.pos()))));
                remove(selected,1); blocks.put(plant.pos(),LoggingRules.SAPLING); confirmed=1; events.add("plant:"+(++plants));
            } else if(action instanceof Action.TrashLogging trash) {
                assertEquals(trash.expected(),inventory[trash.inventoryIndex()]);
                assertTrue(profile.loggingPlots.stream().flatMap(p -> p.plantingPositions().stream()).allMatch(p -> id(p).equals(LoggingRules.SAPLING) || id(p).equals(LoggingRules.LOG)));
                assertTrue(LoggingRules.waste(trash.expected()));
                if(trash.expected().is(LoggingRules.SAPLING)) assertTrue(count(LoggingRules.SAPLING)-trash.expected().count()>=profile.loggingSaplingReserve);
                confirmed=trash.expected().count(); inventory[trash.inventoryIndex()]=ItemData.EMPTY; trashed+=confirmed; events.add("trash:"+trash.expected().id());
            } else if(action instanceof Action.UseBlock use) {
                opened=use.pos(); containerId=nextContainer++;
                assertTrue(use.purpose()==Action.Use.OPEN_CRAFTING || use.purpose()==Action.Use.OPEN_CONTAINER);
                if(use.purpose()==Action.Use.OPEN_CRAFTING) assertEquals(tablePos,use.pos());
            } else if(action instanceof Action.CraftFireLogs craft) {
                assertEquals(tablePos,opened); assertEquals(tablePos,craft.table()); assertTrue(craftingGridEmpty);
                if(manualCraftBatches) {
                    List<InventoryConsolidation.Stack> before=new ArrayList<>(Collections.nCopies(46,InventoryConsolidation.Stack.EMPTY));
                    Set<Integer> sources=new HashSet<>();
                    for(int i=0;i<36;i++) if(!inventory[i].empty()) {
                        before.set(10+i,new InventoryConsolidation.Stack(inventory[i].id(),inventory[i].count(),64));
                        if(inventory[i].is(LoggingRules.LOG)) sources.add(10+i);
                    }
                    var fire=new InventoryConsolidation.Stack(LoggingRules.FIRE_LOG,1,64);
                    var plan=LoggingCraftPlan.create(before,InventoryConsolidation.Stack.EMPTY,sources,fire,
                        menu -> menu.subList(1,10).stream().filter(s -> !s.empty()).count()==6 ? fire : InventoryConsolidation.Stack.EMPTY);
                    confirmed=plan.quantity();
                    for(int i=0;i<36;i++) { var after=plan.placed().items().get(10+i); inventory[i]=item(after.identity(),after.count()); }
                } else { confirmed=Math.min(64,count(LoggingRules.LOG)/6); consume(LoggingRules.LOG,confirmed*6); }
                add(LoggingRules.FIRE_LOG,confirmed);
                crafted+=confirmed; craftCalls++; events.add("craft:"+confirmed);
            } else if(action instanceof Action.CloseContainer close) {
                assertEquals(containerId,close.containerId()); if(Objects.equals(opened,tablePos)) assertTrue(craftingGridEmpty);
                closeManually();
            } else if(action instanceof Action.QuickMove move) {
                assertEquals(containerId,move.containerId()); ItemSlot slot=menu().slot(move.slot()); assertNotNull(slot); assertTrue(slot.player(),"Never withdraw stored wood or other items");
                ItemData source=slot.item();
                assertTrue(opened.equals(shippingPos) ? source.is(LoggingRules.BERRY) : LoggingRules.wood(source));
                confirmed=put(chests.get(opened),source); remove(slot.inventoryIndex(),confirmed);
            } else throw new AssertionError("Unexpected logging action "+action);
            outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native acknowledged",confirmed);
        }
        void addWoodChest(Pos pos) { profile.pois.add(new Poi(pos,PoiKind.WOOD_CHEST,"wood"+pos.x(),null)); chests.put(pos,empty(27)); }
        void closeManually() { opened=null; containerId=0; }
        int count(String id) { return Arrays.stream(inventory).filter(i -> i.is(id)).mapToInt(ItemData::count).sum(); }
        int stored(String id) { return chests.entrySet().stream().filter(e -> !e.getKey().equals(shippingPos)).flatMap(e -> Arrays.stream(e.getValue())).filter(i -> i.is(id)).mapToInt(ItemData::count).sum(); }
        int shipped(String id) { return Arrays.stream(chests.get(shippingPos)).filter(i -> i.is(id)).mapToInt(ItemData::count).sum(); }
        void consume(String id,int count) { for(int i=0;i<36 && count>0;i++) if(inventory[i].is(id)) { int n=Math.min(count,inventory[i].count()); remove(i,n); count-=n; } assertEquals(0,count); }
        void remove(int slot,int count) { if(count>0) inventory[slot]=item(inventory[slot].id(),inventory[slot].count()-count); }
        void add(String id,int count) { if(count>0) assertEquals(count,put(inventory,item(id,count)),"Fixture output must fit real 36 slots"); }
        static ItemData[] empty(int count) { ItemData[] data=new ItemData[count]; Arrays.fill(data,ItemData.EMPTY); return data; }
        static int put(ItemData[] data,ItemData source) {
            int left=source.count();
            for(int pass=0;pass<2;pass++) for(int i=0;i<data.length && left>0;i++) {
                if(pass==0 && data[i].is(source.id()) && data[i].count()<64) { int n=Math.min(left,64-data[i].count()); data[i]=item(source.id(),data[i].count()+n); left-=n; }
                else if(pass==1 && data[i].empty()) { int n=Math.min(left,64); data[i]=item(source.id(),n); left-=n; }
            }
            return source.count()-left;
        }
        public long tick() { return ticks; }
        public long dayTime() { return day*24000+1000; }
        public PlayerState player() { return new PlayerState(playerX,playerY,playerZ,0,0,grounded,false,20,20,selected,true,true); }
        public BlockData block(Pos pos) {
            if(rejectUnloadedBlockReads) assertTrue(loaded(pos),"An unknown cell must be observed before reading its block");
            return new BlockData(pos,id(pos),Map.of());
        }
        public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        public boolean canStand(Pos pos) { return true; }
        public boolean canTraverse(Pos a,Pos b) { return true; }
        public boolean canInteract(Pos pos,double reach) {
            if (LoggingRules.stump(block(pos))) actualChopQueries++;
            return loaded(pos) && !occludedChopping.contains(pos);
        }
        public boolean canInteractFrom(Pos feet,Pos target,double reach) {
            chopRays++; chopRayTargets.add(target);
            if(leafObstructions.values().stream().anyMatch(leaves->leaves.contains(target)))
                return WorldAccess.super.canInteractFrom(feet,target,reach);
            if (Objects.equals(feet,chopGoalFeet) && Objects.equals(target,chopGoalTarget)) {
                chopGoalChecks++; return !alternateChopGoal || chopGoalChecks%2==1;
            }
            return !blockAllChopRays && WorldAccess.super.canInteractFrom(feet,target,reach);
        }
        public List<BlockData> scan(Pos pos,int h,int v) { return List.of(); }
        public String loggingTreeRejection(Pos pos,List<LoggingPlot> plots) { return treeRejection; }
        Pos nextLeaf(Pos base) { ArrayDeque<Pos> leaves=leafObstructions.get(base);return leaves==null ? null : leaves.peekFirst(); }
        public Pos loggingLeafObstruction(Pos base,double reach) {
            return actualLeafObstructionAvailable && (!requireLeafStance || leafStanceArrived) && !rejectedActualLeafStances.contains(lastLeafStance)
                ? leafAtStance.getOrDefault(lastLeafStance,nextLeaf(base)) : null;
        }
        public Pos loggingLeafObstructionFrom(Pos feet,Pos base,double reach) {
            leafRays++;
            if(rejectedPredictedLeafStances.contains(feet) || !leafProofStances.isEmpty() && !leafProofStances.contains(feet))return null;
            return leafProofStance==null || leafProofStance.equals(feet) ? leafAtStance.getOrDefault(feet,nextLeaf(base)) : null;
        }
        public boolean canPlantLoggingSapling(Pos pos) { return id(pos).equals("minecraft:air") && loaded(pos); }
        public boolean canPlantLoggingSapling(Pos pos,double reach) {
            assertEquals(3.25,reach);
            return canPlantLoggingSapling(pos) && !occludedPlanting.contains(pos);
        }
        public boolean loggingAxe(int index) { return inventory[index].is(LoggingRules.AXE); }
        public boolean loggingCraftingMenu() { return Objects.equals(opened,tablePos); }
        public boolean loggingCraftingGridEmpty() { return craftingGridEmpty; }
        public String loggingItemFingerprint(int index) {
            fingerprintCalls++; if(!fingerprintSupported) return null;
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((inventory[index].toString()+fingerprintEpoch).getBytes(StandardCharsets.UTF_8))); }
            catch (Exception e) { throw new AssertionError(e); }
        }
        public List<ItemSlot> inventory() {
            List<ItemSlot> slots=new ArrayList<>();
            for(int i=0;i<36;i++) if(i!=omittedInventorySlot) slots.add(new ItemSlot(i,duplicateInventorySlot && i==35 ? 34 : i,true,inventory[i]));
            return slots;
        }
        public MenuData menu() {
            List<ItemSlot> slots=new ArrayList<>(); int size=opened==null ? 0 : Objects.equals(opened,tablePos) ? 10 : chests.get(opened).length;
            for(int i=0;i<size;i++) slots.add(new ItemSlot(i,-1,false,Objects.equals(opened,tablePos) ? ItemData.EMPTY : chests.get(opened)[i]));
            for(int i=0;i<36;i++) slots.add(new ItemSlot(size+i,i,true,inventory[i]));
            return new MenuData(containerId,0,slots,cursor,opened!=null);
        }
        public boolean mayPlace(int index,ItemData item) { return opened!=null && !opened.equals(tablePos) && index<chests.get(opened).length; }
        public boolean busy() { return pending!=null || forcedNativeBusy; }
        public String pauseReason() { return nativeFence; }
        public boolean loggingHotbarRestored(LoggingHotbarLease lease) {
            return authoritativeRestoredLease!=null ? authoritativeRestoredLease.equals(lease) : ActionPort.super.loggingHotbarRestored(lease);
        }
        public boolean supportsInventoryRefresh() {
            return inventoryRefreshSupported || ActionPort.super.supportsInventoryRefresh();
        }
        public boolean supportsInventoryTrash() { return true; }
        public long submit(Action action) { assertNull(pending); actions.add(action); pending=action; outcome=new ActionOutcome(ActionOutcome.State.PENDING,""); return ++sequence; }
        public ActionOutcome outcome(long ticket) { return outcome; }
        public void move(Movement movement) { throw new AssertionError("Module must use the navigation contract"); }
        public void stopMovement() { }
        public void cancel() { pending=null; outcome=new ActionOutcome(ActionOutcome.State.CANCELLED,"cancelled"); }
        public Result moveTo(Pos pos,double reach,Context c) {
            moves++;
            if(profile.loggingPlots.stream().flatMap(p -> p.plantingPositions().stream()).anyMatch(p -> p.offset(0,-1,0).equals(pos))) plantingReaches.add(reach);
            return loaded(pos) && !blockedNavigation.contains(pos) ? Result.ARRIVED : Result.BLOCKED;
        }
        public Result moveToLogging(Pos pos,double reach,Context c) {
            assertTrue(profile.loggingRunActive); assertTrue(session.allows(profile,Feature.LOGGING));
            if (movementExposesChop) occludedChopping.remove(pos);
            loggingMoves++; loggingTargets.add(pos); return holdChopMovement?Result.MOVING:moveTo(pos,reach,c);
        }
        public Result moveToLoggingPosition(Pos stance,double tolerance,Context c) {
            assertTrue(profile.loggingRunActive);assertTrue(session.allows(profile,Feature.LOGGING));assertEquals(.1,tolerance);
            leafStanceMoves++;loggingMoves++;moves++;lastLeafStance=stance;leafStanceTolerance=tolerance;
            leafStanceVisits.add(stance);
            if(blockLeafStanceMovement)return Result.BLOCKED;
            if(holdLeafStanceMovement)return Result.MOVING;
            leafStanceArrived=true;if(requireLeafStance)playerX=stance.x()+.5;
            return Result.ARRIVED;
        }
        public Result moveToObserve(Pos pos,double reach,Context c) {
            if(!observeLoads)return Result.BLOCKED;
            observed.add(pos);unloaded.remove(pos);return Result.MOVING;
        }
        public boolean retryableFailure(){return retryableObserve;}
        public Navigation.Failure failureKind(){return navigationFailure;}
        public ActionOutcome pendingInteractionOutcome(Context c){return navigationOutcome;}
        public boolean safeFailureRetry(Context c){return safeNavigationFailure;}
        public Result moveToLoggingPlanting(List<Pos> targets,Context c) {
            assertTrue(profile.loggingRunActive); assertTrue(session.allows(profile,Feature.LOGGING));
            assertFalse(targets.isEmpty()); assertTrue(targets.size()<=4);
            assertTrue(profile.loggingPlots.stream().anyMatch(plot -> profile.loggingReplantingPlots.contains(plot.corner())
                && plot.plantingPositions().containsAll(targets)));
            plantingApproaches.add(List.copyOf(targets)); loggingMoves++; moves++;
            for(Pos target:targets) { loggingTargets.add(target.offset(0,-1,0)); plantingReaches.add(3.25); }
            if (blockPlantingApproach || targets.stream().anyMatch(p -> !loaded(p))) return Result.BLOCKED;
            if (!keepPlantingOccluded) occludedPlanting.removeAll(targets);
            return Result.ARRIVED;
        }
        public void reset() { }
    }
}
