package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingModuleTest {
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

    @Test void missingSaplingsKeepTheDurableReplantObligationAndNeverTrashOrCraft() {
        Fixture f=new Fixture(1); f.saplingDrops=0;
        WorkResult result=f.finish(); assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("묘목이 부족"));
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingReplantingPlots.size());
        assertEquals(0,f.trashed); assertEquals(0,f.crafted); assertTrue(f.profile.nextEligibleDay.isEmpty());
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

    @Test void absentSaplingsBlockExactlyAtTheAdditionalFourHundredTickDeadline() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        int sent=f.actions.size(),moves=f.moves;
        // Repeated calls in one client tick cannot consume or extend elapsed time.
        for(int i=0;i<500;i++) assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=firstWait+399; assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=firstWait+400;
        WorkResult stopped=f.step(); assertEquals(WorkResult.State.BLOCKED,stopped.state());
        assertTrue(stopped.message().contains("20초 추가 대기"));
        assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves); assertEquals(2,f.chops);
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
        assertEquals(1,f.profile.loggingReplantingPlots.size()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.add(LoggingRules.SAPLING,4); f.ticks++;
        assertEquals(WorkResult.State.BLOCKED,f.step().state(),"a timed-out run does not silently restart when drops arrive later");
        f.restart(); assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(2,f.chops); assertEquals(4,f.plants);
    }

    @Test void partialSeedArrivalDoesNotPlantEarlyOrRenewThePlotsDeadline() {
        Fixture f=new Fixture(1); long firstWait=startSeedWait(f);
        f.ticks=firstWait+300; f.add(LoggingRules.SAPLING,2);
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertNull(f.pending);
        f.ticks=firstWait+400;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(2,f.chops); assertEquals(0,f.plants); assertEquals(0,f.trashed); assertEquals(0,f.crafted);
        assertEquals(1,f.profile.loggingReplantingPlots.size());
    }

    @Test void twoArrivalsWaitForTheOtherTwoAndAllFourPlantingsPrecedeTheNextTree() {
        Fixture f=new Fixture(2); long firstWait=startSeedWait(f);
        Pos first=f.profile.loggingPlots.get(0).corner();
        f.ticks=firstWait+100; f.add(LoggingRules.SAPLING,2);
        int sent=f.actions.size(),moves=f.moves;
        assertEquals(WorkResult.State.BUSY,f.step().state());
        f.ticks=firstWait+200; assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(0,f.plants); assertEquals(2,f.chops); assertEquals(sent,f.actions.size()); assertEquals(moves,f.moves);
        assertEquals(2,f.profile.loggingRemainingPlots.size()); assertTrue(f.profile.loggingReplantingPlots.contains(first));
        // Fragmented inventory stacks count as stock but no native use is fabricated.
        f.inventory[10]=item(LoggingRules.SAPLING,2);
        f.until(() -> f.profile.loggingRemainingPlots.size()==1);
        assertEquals(4,f.plants); assertEquals(2,f.chops);
        assertFalse(f.profile.loggingRemainingPlots.contains(first));
        assertTrue(f.profile.loggingPlots.get(0).plantingPositions().stream().allMatch(p -> f.id(p).equals(LoggingRules.SAPLING)));
        f.saplingDrops=8;
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(4,f.chops); assertEquals(8,f.plants);
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
        assertEquals(WorkResult.State.BLOCKED,f.finish().state());
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
        f.ticks=resumed+400; assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(2,f.chops); assertEquals(0,f.plants); assertTrue(f.profile.loggingRunActive);
        assertEquals(1,f.profile.loggingReplantingPlots.size()); assertEquals(0,f.trashed);
    }

    private static long startSeedWait(Fixture f) {
        f.saplingDrops=0; f.until(() -> f.chops==2); f.step();
        long fell=f.ticks; f.ticks=fell+79;
        assertEquals(WorkResult.State.BUSY,f.step().state()); assertTrue(f.plantingReaches.isEmpty());
        f.ticks=fell+80; assertEquals(WorkResult.State.BUSY,f.step().state());
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

    @Test void fullStorageOrMissingShippingRetainsAnActiveCleanupBatchForRetryWithoutRecutting() {
        Fixture f=new Fixture(1); Arrays.fill(f.chests.get(f.woodPos),item(LoggingRules.FIRE_LOG,64));
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertTrue(f.profile.loggingRunActive);
        assertTrue(f.profile.loggingRemainingPlots.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.chests.get(f.woodPos)[0]=ItemData.EMPTY; f.closeManually(); f.restart();
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
        final Set<Pos> loggingTargets=new HashSet<>(); int loggingMoves;
        final Map<Pos,Integer> nativeChops=new HashMap<>(); int strokesPerTree=2;
        long ticks,day=10,sequence; int selected=4,moves,chops,plants,trashed,crafted,craftCalls,swaps,saplingDrops=8;
        double playerX=.5;
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
                confirmed=Math.min(64,count(LoggingRules.LOG)/6); consume(LoggingRules.LOG,confirmed*6); add(LoggingRules.FIRE_LOG,confirmed);
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
        public PlayerState player() { return new PlayerState(playerX,64,.5,0,0,true,false,20,20,selected,true,true); }
        public BlockData block(Pos pos) { return new BlockData(pos,id(pos),Map.of()); }
        public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        public boolean canStand(Pos pos) { return true; }
        public boolean canTraverse(Pos a,Pos b) { return true; }
        public boolean canInteract(Pos pos,double reach) { return loaded(pos); }
        public List<BlockData> scan(Pos pos,int h,int v) { return List.of(); }
        public String loggingTreeRejection(Pos pos,List<LoggingPlot> plots) { return null; }
        public boolean canPlantLoggingSapling(Pos pos) { return id(pos).equals("minecraft:air") && loaded(pos); }
        public boolean canPlantLoggingSapling(Pos pos,double reach) {
            assertEquals(3.25,reach);
            return canPlantLoggingSapling(pos) && !occludedPlanting.contains(pos);
        }
        public boolean loggingAxe(int index) { return inventory[index].is(LoggingRules.AXE); }
        public boolean loggingCraftingMenu() { return Objects.equals(opened,tablePos); }
        public boolean loggingCraftingGridEmpty() { return craftingGridEmpty; }
        public String loggingItemFingerprint(int index) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inventory[index].toString().getBytes(StandardCharsets.UTF_8))); }
            catch (Exception e) { throw new AssertionError(e); }
        }
        public List<ItemSlot> inventory() { List<ItemSlot> slots=new ArrayList<>(); for(int i=0;i<36;i++) slots.add(new ItemSlot(i,i,true,inventory[i])); return slots; }
        public MenuData menu() {
            List<ItemSlot> slots=new ArrayList<>(); int size=opened==null ? 0 : Objects.equals(opened,tablePos) ? 10 : chests.get(opened).length;
            for(int i=0;i<size;i++) slots.add(new ItemSlot(i,-1,false,Objects.equals(opened,tablePos) ? ItemData.EMPTY : chests.get(opened)[i]));
            for(int i=0;i<36;i++) slots.add(new ItemSlot(size+i,i,true,inventory[i]));
            return new MenuData(containerId,0,slots,ItemData.EMPTY,opened!=null);
        }
        public boolean mayPlace(int index,ItemData item) { return opened!=null && !opened.equals(tablePos) && index<chests.get(opened).length; }
        public boolean busy() { return pending!=null; }
        public boolean supportsInventoryTrash() { return true; }
        public long submit(Action action) { assertNull(pending); actions.add(action); pending=action; outcome=new ActionOutcome(ActionOutcome.State.PENDING,""); return ++sequence; }
        public ActionOutcome outcome(long ticket) { return outcome; }
        public void move(Movement movement) { throw new AssertionError("Module must use the navigation contract"); }
        public void stopMovement() { }
        public void cancel() { pending=null; outcome=new ActionOutcome(ActionOutcome.State.CANCELLED,"cancelled"); }
        public Result moveTo(Pos pos,double reach,Context c) {
            moves++;
            if(profile.loggingPlots.stream().flatMap(p -> p.plantingPositions().stream()).anyMatch(p -> p.offset(0,-1,0).equals(pos))) plantingReaches.add(reach);
            return loaded(pos) ? Result.ARRIVED : Result.BLOCKED;
        }
        public Result moveToLogging(Pos pos,double reach,Context c) {
            assertTrue(profile.loggingRunActive); assertTrue(session.allows(profile,Feature.LOGGING));
            loggingMoves++; loggingTargets.add(pos); return moveTo(pos,reach,c);
        }
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
