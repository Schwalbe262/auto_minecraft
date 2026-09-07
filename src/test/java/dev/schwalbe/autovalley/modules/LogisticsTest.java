package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsTest {
    private static ItemData tomato(int grade, int count) { return new ItemData(ItemData.TOMATO,count,grade,null,false,999); }
    private static ItemData wine(Integer year) { return new ItemData(ItemData.WINE,1,0,year,false,999); }
    private static ItemData preserves(int count) { return new ItemData(ItemData.PRESERVES,count,0,null,false,99); }
    private static ItemData wine(int year,int count,int grade) { return new ItemData(ItemData.WINE,count,grade,year,false,999); }

    @Test void obsoleteOrMissingSourceClassifierNeverBlocksActualTomatoes() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) for (Integer oldClassifier:Arrays.asList(3,null)) {
            Fixture f=new Fixture(); f.equipHoe(); int cost=feature==Feature.WINE ? 3 : 5;
            Pos source=f.chest(PoiKind.TOMATO_CHEST,0,oldClassifier,tomato(0,cost));
            f.profile.tomatoStorageTargets.put(Profile.positionKey(source),2);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,false,false,false);
            assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(feature),100).state());
            assertEquals(List.of(0),f.usedGrades); assertEquals(cost,f.consumed); assertEquals(1,f.tomatoWithdrawals);
            assertTrue(f.chests.get(source)[0].empty()); assertTrue(f.inventory[0].hoe());
            assertEquals(oldClassifier,f.profile.pois.stream().filter(poi -> poi.pos().equals(source)).findFirst().orElseThrow().classifier());
        }
    }

    @Test void mixedTomatoWarehousesAndHeldInventoryAllContributeToLargestActualGrade() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(1,2);
            Pos first=f.chest(PoiKind.TOMATO_CHEST,0,3,tomato(0,20)); f.chests.get(first)[1]=tomato(1,32);
            Pos second=f.chest(PoiKind.TOMATO_CHEST,1,null,tomato(0,48)); f.chests.get(second)[1]=tomato(1,35);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,false,false,false);
            assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(feature),100).state());
            assertEquals(List.of(1),f.usedGrades,"at supply time actual q1 warehouse67 + held2 beats actual q0 warehouse68");
            assertEquals(2,f.opens.get(first)); assertEquals(1,f.opens.get(second)); assertEquals(1,f.tomatoWithdrawals);
            assertEquals(20,f.chests.get(first)[0].count()); assertEquals(35,f.chests.get(second)[1].count());
        }
    }

    @Test void mixedSourceWithdrawsOnlyTheAllocatedActualGradeDespiteItsHistoricalClassifier() {
        Fixture f=new Fixture(); f.equipHoe();
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,3,tomato(0,64)); f.chests.get(source)[1]=tomato(1,60);
        f.chest(PoiKind.TOMATO_CHEST,1,0,tomato(3,63)); f.machine(PoiKind.WINE_KEG,10,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),100).state());
        assertEquals(List.of(0),f.usedGrades); assertEquals(List.of(source),f.tomatoWithdrawalSources);
        assertEquals(60,f.chests.get(source)[1].count()); assertEquals(1,f.tomatoWithdrawals);
    }

    @Test void gradeChangedBeforeSourceReopenIsRecountedWithoutClassifierRepairOrWrongWithdrawal() {
        Fixture f=new Fixture(); Pos source=f.chest(PoiKind.TOMATO_CHEST,0,3,tomato(0,64));
        f.machine(PoiKind.WINE_KEG,10,false,false,false); MachineModule module=new MachineModule(Feature.WINE);
        for (int n=0;n<100 && f.opens.getOrDefault(source,0)<2;n++) { module.tick(f.context()); f.advance(); }
        assertEquals(2,f.opens.get(source)); assertEquals(0,f.tomatoWithdrawals);
        f.chests.get(source)[0]=tomato(1,64);
        assertEquals(WorkResult.State.IDLE,f.run(module,100).state());
        assertEquals(List.of(1),f.usedGrades); assertEquals(1,f.tomatoWithdrawals); assertEquals(3,f.profile.pois.get(0).classifier());
    }

    @Test void outputWithoutAnySafeAlternateHotbarScratchSkipsMergingAndStillCompletesProduction() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,12);
            for (int index=2;index<9;index++) f.inventory[index]=tomato(0,1);
            f.inventory[9]=feature==Feature.WINE ? wine(f.wineClockYear,1,0) : preserves(1);
            f.inventory[10]=feature==Feature.WINE ? wine(f.wineClockYear,50,0) : preserves(50);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,true,true,false);
            AutomationEngine engine=isolatedMachineEngine(feature); engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status()); assertEquals(1,f.machineClicks());
            assertTrue(f.history.stream().noneMatch(Action.ConsolidateInventory.class::isInstance));
            assertTrue(f.inventory[1].is(ItemData.TOMATO)); assertTrue(f.inventory[0].hoe());
        }
    }

    @Test void lastRecipeInHandIsReplacedByALargerHeldSameGradeStackBeforeMachineUse() {
        for (int recipe=0;recipe<3;recipe++) {
            Feature feature=recipe==0 ? Feature.WINE : Feature.PRESERVES;
            int cost=recipe==1 ? 5 : 3;
            Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,cost); f.inventory[9]=tomato(2,60);
            f.inventory[10]=tomato(0,58);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,true,true,recipe==2);
            AutomationEngine engine=isolatedMachineEngine(feature); engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
            assertEquals(List.of(60),f.handCountsAtMachineUse); assertEquals(List.of(2),f.usedGrades);
            assertEquals(60-cost,f.inventory[1].count()); assertTrue(f.inventory[1].is(ItemData.TOMATO));
            assertEquals(List.of(new Action.SwapHotbar(9,1)),f.history.stream().filter(Action.SwapHotbar.class::isInstance).toList());
            assertTrue(f.inventory[0].hoe()); assertEquals(58,f.inventory[10].count()); assertEquals(0,f.tomatoWithdrawals);
        }
    }

    @Test void storedIngredientsAreFetchedOnlyAfterTheLastCarriedRecipeIsUsed() {
        Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,3);
        f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64));
        f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,200);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(1,f.tomatoWithdrawals); assertEquals(2,f.machineClicks()); assertEquals(6,f.consumed);
        assertEquals(List.of(3,64),f.handCountsAtMachineUse);
        assertTrue(f.inventory[1].is(ItemData.TOMATO)); assertTrue(f.inventory[0].hoe());
        assertTrue(f.history.stream().noneMatch(a -> a instanceof Action.SwapHotbar swap && swap.inventoryIndex()==swap.hotbarSlot()));
    }

    @Test void twoHeldExactRecipesMergeOnceAndDoNotCreateAnEquipShuffleLoop() {
        Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,3); f.inventory[9]=tomato(2,3);
        f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(List.of(6,3),f.handCountsAtMachineUse); assertEquals(6,f.consumed);
        assertEquals(1,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
        assertTrue(f.history.stream().noneMatch(a -> a instanceof Action.SwapHotbar swap && swap.inventoryIndex()==swap.hotbarSlot()));
        assertEquals(0,f.tomatoWithdrawals); assertTrue(f.inventory[0].hoe());
    }

    @Test void genuinelyFinalExactRecipeStillCompletesWithoutARefillOrAnotherJob() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            Fixture f=new Fixture(); f.equipHoe(); int cost=feature==Feature.WINE ? 3 : 5; f.inventory[1]=tomato(2,cost);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,true,true,false);
            AutomationEngine engine=isolatedMachineEngine(feature); engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status()); assertEquals(cost,f.consumed);
            assertEquals(List.of(cost),f.handCountsAtMachineUse); assertEquals(1,f.machineClicks());
            assertTrue(f.history.stream().noneMatch(a -> a instanceof Action.SwapHotbar || a instanceof Action.QuickMove || a instanceof Action.ConsolidateInventory));
            assertTrue(f.inventory[0].hoe());
        }
    }

    @Test void historicalLayoutDoesNotOverrideLargestActualGradeOrNearestActualSource() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            Fixture f=new Fixture();
            Pos correct=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,20));
            Pos otherGrade=f.chest(PoiKind.TOMATO_CHEST,1,3,tomato(3,64)); f.chests.get(otherGrade)[1]=tomato(3,6);
            Pos legacy=f.chest(PoiKind.TOMATO_CHEST,5,0,tomato(0,60));
            f.profile.tomatoStorageTargets.put(Profile.positionKey(correct),0);
            f.profile.tomatoStorageTargets.put(Profile.positionKey(otherGrade),0);
            f.profile.tomatoStorageTargets.put(Profile.positionKey(legacy),3);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,8,false,false,false);
            assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(feature),250).state());
            assertEquals(List.of(0),f.usedGrades,"actual q0 total80 beats actual q3 total70 regardless of desired layout");
            assertEquals(List.of(correct),f.tomatoWithdrawalSources,"historical layout never overrides the nearest actual source");
            assertEquals(60,f.chests.get(legacy)[0].count()); assertTrue(f.chests.get(correct)[0].empty());
            assertEquals(70,Arrays.stream(f.chests.get(otherGrade)).mapToInt(ItemData::count).sum());
            assertEquals(0,f.profile.pois.stream().filter(p -> p.pos().equals(legacy)).findFirst().orElseThrow().classifier());
        }
    }

    @Test void bulkHaulUsesNearestActualStockWithoutLegacyLayoutPriority() {
        Fixture f=new Fixture();
        Pos correct=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,30));
        Pos legacyOne=f.chest(PoiKind.TOMATO_CHEST,4,2,tomato(2,3));
        Pos legacyTwo=f.chest(PoiKind.TOMATO_CHEST,5,2,tomato(2,3));
        f.profile.tomatoStorageTargets.put(Profile.positionKey(correct),2);
        f.profile.tomatoStorageTargets.put(Profile.positionKey(legacyOne),0);
        f.profile.tomatoStorageTargets.put(Profile.positionKey(legacyTwo),1);
        for (int n=0;n<4;n++) f.machine(PoiKind.WINE_KEG,10+n,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),300).state());
        assertEquals(List.of(correct),f.tomatoWithdrawalSources);
        assertEquals(12,f.consumed); assertEquals(3,f.chests.get(legacyOne)[0].count()); assertEquals(3,f.chests.get(legacyTwo)[0].count());
    }

    @Test void tomatoDepositsUseNearestCommodityStorageRegardlessOfTheHistoricalLayout() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,7);
        Pos legacy=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,9));
        Pos intended=f.chest(PoiKind.TOMATO_CHEST,3,0,tomato(0,10));
        f.profile.tomatoStorageTargets.put(Profile.positionKey(legacy),1);
        f.profile.tomatoStorageTargets.put(Profile.positionKey(intended),0);
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule(),100).state());
        assertEquals(16,f.chests.get(legacy)[0].count()); assertTrue(f.opens.containsKey(legacy));
        assertEquals(10,f.chests.get(intended)[0].count()); assertTrue(f.inventory[0].empty());
    }

    @Test void mixedTomatoDepositUsesEmptySpaceWithoutReclassifyingOrSavingMetadata() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,7);
        Pos occupied=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,9));
        Pos empty=f.chest(PoiKind.TOMATO_CHEST,3,3,ItemData.EMPTY);
        f.profile.tomatoStorageTargets.put(Profile.positionKey(occupied),0);
        f.profile.tomatoStorageTargets.put(Profile.positionKey(empty),0);
        List<Poi> registrations=List.copyOf(f.profile.pois);
        f.checkpointHook=() -> fail("commodity storage has no grade metadata to rewrite");
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule(),150).state());
        assertEquals(9,f.chests.get(occupied)[0].count()); assertEquals(2,f.chests.get(occupied)[0].quality());
        assertEquals(7,f.chests.get(occupied)[1].count()); assertEquals(0,f.chests.get(occupied)[1].quality());
        assertTrue(Arrays.stream(f.chests.get(empty)).allMatch(ItemData::empty)); assertEquals(registrations,f.profile.pois);
        assertEquals(1,f.history.stream().filter(Action.QuickMove.class::isInstance).count()); assertNull(f.open);
    }

    @Test void tomatoCommodityStorageInspectsEverySlotAndClosesForeignContentsWithoutTransfer() {
        for (ItemData contaminant:List.of(wine(8),new ItemData("minecraft:stone",1,0,null,false,999))) {
            Fixture f=new Fixture(); f.inventory[0]=tomato(0,7);
            Pos destination=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,4));
            f.chests.get(destination)[1]=contaminant;
            f.profile.tomatoStorageTargets.put(Profile.positionKey(destination),0);
            WorkResult result=f.run(new TomatoStorageModule(),100);
            assertEquals(WorkResult.State.BLOCKED,result.state());
            assertEquals(4,f.chests.get(destination)[0].count()); assertEquals(contaminant,f.chests.get(destination)[1]);
            assertEquals(7,f.inventory[0].count()); assertNull(f.open);
            assertTrue(f.history.stream().noneMatch(Action.QuickMove.class::isInstance));
        }
    }

    @Test void consumedTomatoStorageAcceptsAnotherActualGradeWithoutChangingItsHistoricalMetadata() {
        Fixture f=new Fixture(); Pos legacy=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,3));
        f.profile.tomatoStorageTargets.put(Profile.positionKey(legacy),0);
        f.machine(PoiKind.WINE_KEG,2,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),150).state());
        assertEquals(3,f.consumed); assertTrue(Arrays.stream(f.chests.get(legacy)).allMatch(ItemData::empty));
        assertEquals(2,f.profile.pois.stream().filter(p -> p.pos().equals(legacy)).findFirst().orElseThrow().classifier());
        f.inventory[0]=tomato(0,6);
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule(),100).state());
        assertEquals(2,f.profile.pois.stream().filter(p -> p.pos().equals(legacy)).findFirst().orElseThrow().classifier());
        assertEquals(6,f.chests.get(legacy)[0].count()); assertEquals(0,f.chests.get(legacy)[0].quality());
    }

    @Test void emptyCommodityStorageNeedsNoMetadataCheckpointOrClassifierConversion() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,6);
        Pos destination=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        Poi original=f.profile.pois.get(0); f.profile.tomatoStorageTargets.put(Profile.positionKey(destination),3);
        f.checkpointHook=() -> { throw new IllegalStateException("disk unavailable"); };
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule(),100).state());
        assertEquals(original,f.profile.pois.get(0)); assertEquals(3,f.profile.tomatoStorageTargets.get(Profile.positionKey(destination)));
        assertTrue(f.inventory[0].empty()); assertEquals(6,f.chests.get(destination)[0].count());
        assertTrue(f.savedOutputs.isEmpty()); assertEquals(1,f.history.stream().filter(Action.QuickMove.class::isInstance).count());
    }

    @Test void profilesWithoutALayoutKeepNearestActualGradeStorageAndWithdrawalRules() {
        Fixture f=new Fixture(); Pos near=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,9));
        Pos far=f.chest(PoiKind.TOMATO_CHEST,4,2,tomato(2,9)); f.machine(PoiKind.WINE_KEG,6,false,false,false);
        assertTrue(f.profile.tomatoStorageTargets.isEmpty());
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),150).state());
        assertEquals(List.of(near),f.tomatoWithdrawalSources); assertEquals(9,f.chests.get(far)[0].count());
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule(),100).state());
        assertEquals(6,f.chests.get(near)[0].count()); assertTrue(f.savedOutputs.isEmpty());
    }

    @Test void instantRefillDoesNotHideConfirmedStorageTransfers() {
        for (boolean confirmedCount:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.inventory[0]=tomato(0,64); f.tomatoRefills.add(tomato(0,64)); f.reportConfirmedCount=confirmedCount;
            Pos chest=f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); TomatoStorageModule module=new TomatoStorageModule();
            while (f.history.stream().noneMatch(Action.QuickMove.class::isInstance)) { module.tick(f.context()); f.advance(); }
            assertEquals(64,f.inventory[0].count(),"automatic pickup instantly replaced the deposited stack");
            module.tick(f.context());
            module.tick(f.context());
            f.advance(); assertEquals(WorkResult.State.IDLE,f.run(module,30).state());
            assertEquals(128,Arrays.stream(f.chests.get(chest)).mapToInt(ItemData::count).sum());
        }
    }

    @Test void groundItemsDoNotPreventProductionOrSleep() {
        Fixture f=new Fixture(); f.dayTime=13000; f.inventory[0]=tomato(0,3);
        f.ground.add(new GroundItem(1,.5,64,.5,tomato(0,61)));
        f.machine(PoiKind.WINE_KEG,0,false,false,false); f.machine(PoiKind.PRESERVES_JAR,1,false,false,false); f.poi(PoiKind.BED,2,null);
        AutomationEngine engine=new AutomationEngine(List.of(new MachineModule(Feature.WINE),new MachineModule(Feature.PRESERVES),new SleepModule()));
        engine.start(f.context()); engine.tick(f.context()); f.ground.clear();
        for (int i=0;i<200;i++) { f.advance(); engine.tick(f.context()); }
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed); assertTrue(f.sleeping);
    }

    @Test void heldStorageRunsBeforeProductionAndSleep() {
        Fixture f=new Fixture(); f.dayTime=13000; f.inventory[0]=tomato(0,64); f.tomatoRefills.add(tomato(0,64)); f.reportConfirmedCount=true;
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); f.machine(PoiKind.WINE_KEG,1,false,false,false); f.poi(PoiKind.BED,2,null);
        AutomationEngine engine=new AutomationEngine(List.of(new TomatoStorageModule(),new MachineModule(Feature.WINE),new SleepModule()));
        engine.start(f.context());
        for (int i=0;i<250 && !f.sleeping;i++) {
            engine.tick(f.context());
            f.advance();
        }
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed); assertTrue(f.sleeping);
    }

    @Test void failedStorageAcknowledgementCannotCompleteTheDeposit() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,64);
        Pos destination=f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY);
        TomatoStorageModule module=new TomatoStorageModule();
        while (!(f.action instanceof Action.QuickMove)) { module.tick(f.context()); if (!(f.action instanceof Action.QuickMove)) f.advance(); }
        f.cancel();
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertEquals(64,f.inventory[0].count()); assertTrue(Arrays.stream(f.chests.get(destination)).allMatch(ItemData::empty));
    }

    @Test void surplusShipsOnlyHeldFreshWineAfterEveryBirthCohortReserveIsCompletelyFull() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0);
        Pos barrel=f.fullWineReserve(0,8,27), doubleChest=f.fullWineReserve(1,8,54);
        f.chest(PoiKind.WINE_CHEST,2,7,ItemData.EMPTY); // Another current-age group is irrelevant.
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,3,null,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,f.run(new WineSurplusShippingModule(),100).state());
        assertEquals(5,f.soldWine); assertEquals(0,f.withdrawnWine); assertEquals(5,f.chests.get(bin)[0].count());
        assertEquals(1728,Arrays.stream(f.chests.get(barrel)).mapToInt(ItemData::count).sum());
        assertEquals(3456,Arrays.stream(f.chests.get(doubleChest)).mapToInt(ItemData::count).sum());
        assertEquals(1,f.opens.get(barrel)); assertEquals(1,f.opens.get(doubleChest)); assertFalse(f.opens.containsKey(new Pos(2,64,0)));
        assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void onePartialStackOfAnotherQualityPreventsSaleEvenWhenEverySlotIsOccupied() {
        Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); f.fullWineReserve(0,8,27);
        Pos partial=f.fullWineReserve(1,8,27); f.chests.get(partial)[26]=wine(8,63,3);
        f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        WorkResult result=f.run(new WineSurplusShippingModule(),100);
        assertEquals(WorkResult.State.IDLE,result.state()); assertTrue(result.message().contains("retained 5"));
        assertEquals(0,f.soldWine); assertEquals(0,f.withdrawnWine); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void missingOrConflictingWineReserveContentsNeverAuthorizeSurplus() {
        for (ItemData conflict:new ItemData[]{ItemData.EMPTY,wine(9,64,0),tomato(0,64)}) {
            Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); Pos barrel=f.fullWineReserve(0,8,27);
            f.chests.get(barrel)[3]=conflict; f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
            assertEquals(conflict.empty() ? WorkResult.State.IDLE : WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),60).state());
            assertEquals(0,f.soldWine); assertTrue(f.session.wineSalePermits.isEmpty());
        }
    }

    @Test void unknownWineClockUnknownBirthAndFutureBirthNeverAuthorizeSurplus() {
        for (int scenario=0;scenario<3;scenario++) {
            Fixture f=new Fixture(); f.wineClockYear=scenario==0 ? null : 8;
            f.inventory[0]=scenario==1 ? wine((Integer)null) : wine(scenario==2 ? 9 : 8,5,0);
            f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
            assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),60).state());
            assertEquals(0,f.soldWine); assertTrue(f.history.isEmpty());
        }
    }

    @Test void absentUnloadedUnreachableOrUnsupportedReservePreventsSurplusSale() {
        for (int scenario=0;scenario<4;scenario++) {
            Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0);
            if (scenario!=0) {
                Pos barrel=f.fullWineReserve(0,8,scenario==3 ? 2 : 27);
                if (scenario==1) f.unloaded.add(barrel);
                if (scenario==2) f.blockedPaths.add(barrel);
            }
            f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
            assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),60).state());
            assertEquals(0,f.soldWine); assertTrue(f.session.wineSalePermits.isEmpty());
        }
    }

    @Test void expiryDayChangeOrAddedReserveInvalidatesTheVerifiedSaleAllowance() {
        for (int scenario=0;scenario<3;scenario++) {
            Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
            WineSurplusShippingModule module=new WineSurplusShippingModule();
            while (!f.session.wineSalePermits.containsKey(8)) { module.tick(f.context()); f.advance(); }
            if (scenario==0) f.ticks+=1200;
            else if (scenario==1) f.dayTime+=24000;
            else f.chest(PoiKind.WINE_CHEST,2,8,ItemData.EMPTY);
            assertEquals(WorkResult.State.BLOCKED,f.run(module,30).state());
            assertEquals(0,f.soldWine); assertTrue(f.session.wineSalePermits.isEmpty());
        }
    }

    @Test void cancellingSurplusClearsPermitsAndRequiresNewReserveVerification() {
        Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); Pos barrel=f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
        WineSurplusShippingModule module=new WineSurplusShippingModule();
        while (!f.session.wineSalePermits.containsKey(8)) { module.tick(f.context()); f.advance(); }
        int actions=f.history.size(); module.reset(); f.cancel();
        assertTrue(f.session.wineSalePermits.isEmpty()); assertEquals(actions,f.history.size());
        f.chests.get(barrel)[0]=wine(8,63,0);
        assertEquals(WorkResult.State.IDLE,f.run(module,60).state()); assertEquals(0,f.soldWine);
    }

    @Test void destinationAcknowledgementConsumesAllowanceDespiteInstantInstantPickupRefill() {
        Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); f.refillAfterWineSale=wine(8,5,0);
        f.fullWineReserve(0,8,27); Pos bin=f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
        assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),100).state());
        assertEquals(5,f.soldWine); assertEquals(5,f.chests.get(bin)[0].count()); assertEquals(5,f.inventory[0].count());
        assertTrue(f.session.wineSalePermits.isEmpty(),"newly picked-up wine needs a new reserve verification");
    }

    @Test void enlargedHeldStackCannotExceedThePreviouslyVerifiedSurplus() {
        Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
        WineSurplusShippingModule module=new WineSurplusShippingModule();
        while (!f.session.wineSalePermits.containsKey(8)) { module.tick(f.context()); f.advance(); }
        f.inventory[0]=wine(8,9,0);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,30).state()); assertEquals(0,f.soldWine);
    }

    @Test void surplusOneShotChecksAndSellsEveryInitiallyHeldCohortWithFullReserves() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(7,7,2);
        Pos fresh=f.fullWineReserve(0,8,27), aged=f.fullWineReserve(1,7,54);
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        AutomationEngine engine=isolatedSurplusEngine(); engine.startOnce(f.context(),Feature.WINE_SURPLUS_SHIPPING);
        f.runUntilStopped(engine,250);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(12,f.soldWine); assertEquals(0,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
        assertEquals(1,f.opens.get(fresh)); assertEquals(1,f.opens.get(aged)); assertEquals(2,f.opens.get(bin));
        assertEquals(1728,Arrays.stream(f.chests.get(fresh)).mapToInt(ItemData::count).sum());
        assertEquals(3456,Arrays.stream(f.chests.get(aged)).mapToInt(ItemData::count).sum());
        assertEquals(0,f.withdrawnWine); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void nonFullFirstCohortIsRetainedWhileLaterFullCohortStillShips() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(7,7,2);
        Pos fresh=f.fullWineReserve(0,8,27); f.chests.get(fresh)[0]=wine(8,63,0);
        Pos aged=f.fullWineReserve(1,7,27); f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        WorkResult result=f.run(new WineSurplusShippingModule(),200);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertTrue(result.message().contains("Checked 2")); assertTrue(result.message().contains("sold 7")); assertTrue(result.message().contains("retained 5"));
        assertEquals(7,f.soldWine); assertEquals(5,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
        assertEquals(5,f.inventory[0].count()); assertTrue(f.inventory[1].empty());
        assertEquals(1,f.opens.get(fresh)); assertEquals(1,f.opens.get(aged)); assertEquals(63,f.chests.get(fresh)[0].count());
        assertEquals(0,f.withdrawnWine); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void surplusOneShotCompletionReportsRetainedWineInsteadOfImplyingAllWasSold() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(7,7,0);
        Pos partial=f.fullWineReserve(0,8,27); f.chests.get(partial)[0]=wine(8,63,0);
        f.fullWineReserve(1,7,27); f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        AutomationEngine engine=isolatedSurplusEngine(); engine.startOnce(f.context(),Feature.WINE_SURPLUS_SHIPPING);
        f.runUntilStopped(engine,250);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertTrue(engine.status().contains("sold 7")); assertTrue(engine.status().contains("retained 5"));
        assertEquals(7,f.soldWine); assertEquals(5,f.inventory[0].count());
    }

    @Test void retainedWineYieldsToLowerPrioritiesThenGetsFreshlyVerifiedOnTheNextSweep() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0);
        Pos reserve=f.fullWineReserve(0,8,27); f.chests.get(reserve)[0]=wine(8,63,0);
        f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
        int[] lowerPriorityTicks={0};
        AutomationModule lower=new AutomationModule() {
            @Override public Feature feature() { return Feature.SHIPPING; }
            @Override public int priority() { return 40; }
            @Override public WorkResult tick(Context c) { lowerPriorityTicks[0]++; return WorkResult.idle(); }
            @Override public void reset() { }
        };
        AutomationEngine engine=new AutomationEngine(List.of(new WineSurplusShippingModule(),lower)); engine.start(f.context());
        for (int n=0;n<150 && lowerPriorityTicks[0]==0;n++) { engine.tick(f.context()); f.advance(); }
        assertTrue(lowerPriorityTicks[0]>0,"an immediate re-scan of retained wine must not starve the next scheduler priority");
        assertEquals(1,f.opens.get(reserve)); assertEquals(0,f.soldWine); assertEquals(5,f.inventory[0].count());
        f.chests.get(reserve)[0]=wine(8,64,0); f.ticks+=25;
        for (int n=0;n<150 && f.soldWine<5;n++) { engine.tick(f.context()); f.advance(); }
        assertEquals(5,f.soldWine); assertEquals(2,f.opens.get(reserve),"next sweep must freshly reopen the changed reserve");
    }

    @Test void laterCohortIsFreshlyRecheckedInsteadOfBorrowingThePreviousCohortsVerification() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(7,7,0);
        f.fullWineReserve(0,8,27); Pos later=f.fullWineReserve(1,7,27); f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        WineSurplusShippingModule module=new WineSurplusShippingModule();
        while (!f.session.wineSalePermits.containsKey(8)) { module.tick(f.context()); f.advance(); }
        f.chests.get(later)[10]=wine(7,63,0);
        WorkResult result=f.run(module,200);
        assertEquals(WorkResult.State.IDLE,result.state()); assertEquals(5,f.soldWine);
        assertEquals(7,f.inventory[1].count()); assertTrue(result.message().contains("retained 7"));
        assertEquals(1,f.opens.get(later)); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void changedSecondCohortRegistrationAfterItsPermitStopsBeforeAnySaleOfThatCohort() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(7,7,0);
        f.fullWineReserve(0,8,27); f.fullWineReserve(1,7,27); f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        WineSurplusShippingModule module=new WineSurplusShippingModule();
        for (int n=0;n<200 && !f.session.wineSalePermits.containsKey(7);n++) { module.tick(f.context()); f.advance(); }
        assertTrue(f.session.wineSalePermits.containsKey(7)); assertFalse(f.session.wineSalePermits.containsKey(8)); assertEquals(5,f.soldWine);
        f.chest(PoiKind.WINE_CHEST,3,7,ItemData.EMPTY);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,30).state());
        assertEquals(5,f.soldWine); assertEquals(7,f.inventory[1].count()); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void unknownWineInAnyInitialSlotBlocksTheWholeSaleRunBeforeOpeningContainers() {
        for (Integer invalid:new Integer[]{null,-1,9}) {
            Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(invalid);
            f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
            assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),30).state());
            assertEquals(0,f.soldWine); assertTrue(f.history.isEmpty()); assertTrue(f.session.wineSalePermits.isEmpty());
        }
    }

    @Test void laterUnsafeCohortPausesInsteadOfSilentlyClaimingAllWineWasHandled() {
        for (boolean missingYear:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0); f.inventory[1]=wine(7,7,0);
            f.fullWineReserve(0,8,27); Pos unsafe=f.fullWineReserve(1,7,27); f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
            if (missingYear) f.chests.get(unsafe)[0]=wine((Integer)null); else f.unloaded.add(unsafe);
            assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),200).state());
            assertEquals(5,f.soldWine); assertEquals(7,f.inventory[1].count()); assertEquals(0,f.withdrawnWine);
            assertTrue(f.session.wineSalePermits.isEmpty());
        }
    }

    @Test void newCohortArrivingDuringTheRunIsNotSoldWithoutANewBoundedVerificationPass() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0);
        f.fullWineReserve(0,8,27); f.fullWineReserve(1,7,27); f.chest(PoiKind.SHIPPING_BIN,2,null,ItemData.EMPTY);
        WineSurplusShippingModule module=new WineSurplusShippingModule();
        while (!f.session.wineSalePermits.containsKey(8)) { module.tick(f.context()); f.advance(); }
        f.inventory[1]=wine(7,7,0);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state()); assertEquals(5,f.soldWine); assertEquals(7,f.inventory[1].count());
        assertEquals(WorkResult.State.IDLE,f.run(module,100).state()); assertEquals(12,f.soldWine);
        assertEquals(0,f.withdrawnWine); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void unknownWineArrivingAfterAPermitRevokesItBeforeAnyFurtherSale() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0);
        f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
        WineSurplusShippingModule module=new WineSurplusShippingModule();
        while (!f.session.wineSalePermits.containsKey(8)) { module.tick(f.context()); f.advance(); }
        f.inventory[1]=wine((Integer)null);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,30).state());
        assertEquals(0,f.soldWine); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void pendingMachineOutputBlocksSurplusBeforeTheCohortQueueStarts() {
        Fixture f=new Fixture(); f.wineClockYear=8; f.inventory[0]=wine(8,5,0);
        f.fullWineReserve(0,8,27); f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
        MachineOutputLedger.prepare(f.context(),Feature.WINE,new Pos(10,64,0));
        assertEquals(WorkResult.State.BLOCKED,new WineSurplusShippingModule().tick(f.context()).state());
        assertTrue(f.history.isEmpty()); assertEquals(0,f.soldWine); assertEquals(1,f.profile.pendingMachineOutputs.size());
    }

    private static AutomationEngine isolatedSurplusEngine() {
        List<AutomationModule> modules=new ArrayList<>(); modules.add(new WineSurplusShippingModule());
        for (Feature other:Feature.values()) if (other!=Feature.WINE_SURPLUS_SHIPPING) modules.add(new AutomationModule() {
            @Override public Feature feature() { return other; }
            @Override public int priority() { return 0; }
            @Override public WorkResult tick(Context context) { fail("Surplus-only run invoked another feature: "+other); return WorkResult.idle(); }
            @Override public void reset() { }
        });
        return new AutomationEngine(modules);
    }

    @Test void largeFarmServices397KegsBefore144JarsWithoutCountingEveryChestForEveryMachine() {
        Fixture f=new Fixture(); f.dayTime=1000; f.equipHoe();
        for (int grade=0;grade<4;grade++) {
            Pos chest=f.chest(PoiKind.TOMATO_CHEST,grade,grade,ItemData.EMPTY);
            ItemData[] stacks=new ItemData[grade==2 ? 54 : 2]; Arrays.fill(stacks,tomato(grade,64)); f.chests.put(chest,stacks);
        }
        Set<Pos> kegs=new HashSet<>();
        for (int i=0;i<397;i++) kegs.add(f.machine(PoiKind.WINE_KEG,20+i,false,false,false));
        for (int i=0;i<144;i++) f.machine(PoiKind.PRESERVES_JAR,500+i,false,false,false);
        AutomationEngine engine=new AutomationEngine(List.of(new MachineModule(Feature.PRESERVES),new MachineModule(Feature.WINE)));
        engine.start(f.context());
        for (int i=0;i<30000 && f.profile.nextEligibleDay.size()<541;i++) { engine.tick(f.context()); f.advance(); }
        List<Action.UseBlock> uses=f.history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose()==Action.Use.MACHINE).map(a -> (Action.UseBlock)a).toList();
        assertEquals(541,uses.size(),() -> engine.status()+"; "+f.tomatoFragments()); assertEquals(397*3+144*5,f.consumed);
        assertTrue(uses.subList(0,397).stream().allMatch(u -> kegs.contains(u.pos())),"all due wine batches have priority over jars");
        assertTrue(f.usedGrades.stream().allMatch(g -> g==2),"the largest total stock is selected throughout this workload");
        int sourceOpens=f.opens.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(sourceOpens<=30,"bulk hauls and bounded refreshes must replace per-stack underground trips; actual="+sourceOpens);
        assertEquals(541,f.profile.nextEligibleDay.size());
    }

    @Test void carriedBatchStaysOnItsAllocatedGradeUntilNearlyGoneThenUsesOtherHeldGrade() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,6); f.inventory[1]=tomato(2,7);
        f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,64)); f.chest(PoiKind.TOMATO_CHEST,1,3,tomato(3,64));
        for (int i=0;i<3;i++) f.machine(PoiKind.WINE_KEG,10+i,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),150).state());
        assertEquals(List.of(2,2,0),f.usedGrades);
        assertTrue(f.opens.isEmpty(),"usable carried ingredients do not require any warehouse count");
    }

    @Test void allFourExactRecipeRemnantsFinishWithoutAlternatingOrOpeningStorage() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            Fixture f=new Fixture(); f.equipHoe(); int cost=feature==Feature.WINE ? 3 : 5;
            for (int grade=0;grade<4;grade++) {
                f.inventory[grade+9]=tomato(grade,cost);
                f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10+grade,false,false,false);
            }
            f.chest(PoiKind.TOMATO_CHEST,0,3,tomato(3,64));
            AutomationEngine engine=isolatedMachineEngine(feature); engine.startOnce(f.context(),feature); f.runUntilStopped(engine,150);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
            assertEquals(List.of(0,1,2,3),f.usedGrades); assertEquals(4*cost,f.consumed); assertTrue(f.opens.isEmpty());
            assertTrue(f.inventory[0].hoe()); assertEquals(0,f.tomatoWithdrawals);
            assertTrue(f.history.stream().noneMatch(a -> a instanceof Action.ConsolidateInventory
                || a instanceof Action.SwapHotbar swap && swap.inventoryIndex()==swap.hotbarSlot()));
        }
    }

    @Test void firstSupplyUsesTheLargestActualGradeOnceInsteadOfBalancingEveryRecipe() {
        Fixture f=new Fixture(); f.equipHoe();
        int[] amounts={1000,1200,1400,800}; List<Pos> sources=new ArrayList<>();
        for (int grade=0;grade<4;grade++) {
            Pos source=f.chest(PoiKind.TOMATO_CHEST,grade,3-grade,ItemData.EMPTY); sources.add(source);
            ItemData[] stacks=new ItemData[(amounts[grade]+63)/64];
            for (int index=0;index<stacks.length;index++) stacks[index]=tomato(grade,Math.min(64,amounts[grade]-index*64));
            f.chests.put(source,stacks);
        }
        for (int index=0;index<80;index++) f.machine(PoiKind.WINE_KEG,10+index,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,1500);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status()); assertEquals(240,f.consumed);
        assertTrue(f.usedGrades.stream().allMatch(grade -> grade==2),"q2 remains the allocated batch even after its total drops below q1");
        assertEquals(5,f.opens.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(f.tomatoWithdrawalSources.stream().allMatch(source -> source.equals(sources.get(2))));
        assertEquals(1200,Arrays.stream(f.chests.get(sources.get(1))).mapToInt(ItemData::count).sum());
    }

    @Test void allocatedBatchPast1200TicksDoesNotRescanAnyOf32Warehouses() {
        Fixture f=new Fixture(); f.equipHoe(); List<Pos> sources=new ArrayList<>();
        for (int index=0;index<32;index++) sources.add(f.chest(PoiKind.TOMATO_CHEST,index,null,index==0 ? tomato(2,64) : ItemData.EMPTY));
        for (int index=0;index<20;index++) f.machine(PoiKind.WINE_KEG,50+index,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        for (int tick=0;tick<300 && f.machineClicks()==0;tick++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(33,f.opens.values().stream().mapToInt(Integer::intValue).sum());
        f.ticks+=1300; f.chests.get(sources.get(31))[0]=tomato(3,64);
        assertEquals(WorkResult.State.IDLE,f.run(module,400).state());
        assertEquals(20,f.machineClicks()); assertEquals(60,f.consumed); assertEquals(1,f.tomatoWithdrawals);
        assertEquals(33,f.opens.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(f.usedGrades.stream().allMatch(grade -> grade==2));
        assertEquals(64,f.chests.get(sources.get(31))[0].count());
    }

    @Test void dayChangesDuringPartialHaulCloseTheMenuAndUseAlreadyWithdrawnMaterial() {
        Fixture f=new Fixture(); f.equipHoe();
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,null,tomato(2,64)); f.chests.get(source)[1]=tomato(2,64);
        for (int index=0;index<10;index++) f.machine(PoiKind.WINE_KEG,10+index,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        for (int tick=0;tick<100 && f.tomatoWithdrawals==0;tick++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertEquals(1,f.tomatoWithdrawals); assertNotNull(f.open); assertEquals(0,f.machineClicks());
        f.dayTime+=24000;
        assertEquals(WorkResult.State.IDLE,f.run(module,200).state());
        assertEquals(10,f.machineClicks()); assertEquals(30,f.consumed); assertEquals(2,f.opens.get(source));
        assertEquals(1,f.tomatoWithdrawals); assertNull(f.open);
        assertEquals(64,Arrays.stream(f.chests.get(source)).mapToInt(ItemData::count).sum());
    }

    @Test void depletedCarriedBatchTriggersOneNewCountAndAllocatesTheThenLargestGrade() {
        Fixture f=new Fixture(); f.equipHoe();
        Pos previous=f.chest(PoiKind.TOMATO_CHEST,0,null,tomato(2,9));
        Pos next=f.chest(PoiKind.TOMATO_CHEST,1,null,tomato(1,6));
        for (int index=0;index<5;index++) f.machine(PoiKind.WINE_KEG,10+index,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        for (int tick=0;tick<100 && f.machineClicks()<3;tick++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertEquals(List.of(2,2,2),f.usedGrades); assertEquals(3,f.opens.values().stream().mapToInt(Integer::intValue).sum());
        f.chests.get(previous)[0]=tomato(0,8); f.chests.get(next)[0]=tomato(1,12);
        assertEquals(WorkResult.State.IDLE,f.run(module,150).state());
        assertEquals(List.of(2,2,2,1,1),f.usedGrades); assertEquals(List.of(previous,next),f.tomatoWithdrawalSources);
        assertEquals(6,f.opens.values().stream().mapToInt(Integer::intValue).sum()); assertEquals(15,f.consumed);
    }

    @Test void sourceCountingFinishesTheFirstWallBeforeCrossingToTheSecondWall() {
        Fixture f=new Fixture(); f.equipHoe(); Set<Pos> firstWall=new HashSet<>(), secondWall=new HashSet<>();
        for (int wall=0;wall<2;wall++) for (int x=0;x<4;x++) for (int y=0;y<4;y++) {
            Pos source=new Pos(wall*6+x,64+y,0); (wall==0 ? firstWall : secondWall).add(source);
            f.profile.pois.add(new Poi(source,PoiKind.TOMATO_CHEST,"source",null));
            f.chests.put(source,new ItemData[]{tomato(2,3),ItemData.EMPTY});
        }
        f.machine(PoiKind.WINE_KEG,20,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),250).state());
        List<Pos> visits=f.history.stream().filter(a -> a instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER)
            .map(a -> ((Action.UseBlock)a).pos()).toList();
        assertEquals(33,visits.size()); assertEquals(firstWall,new HashSet<>(visits.subList(0,16)));
        assertEquals(secondWall,new HashSet<>(visits.subList(16,32))); assertEquals(1,f.tomatoWithdrawals);
    }

    @Test void nextMachineUsesCurrentPositionOnlyAfterLockedHaulAndNativeAcknowledgement() {
        Fixture f=new Fixture(); f.equipHoe(); f.chest(PoiKind.TOMATO_CHEST,0,null,tomato(2,64));
        Pos first=f.machine(PoiKind.WINE_KEG,10,false,false,false);
        Pos oldNearest=f.machine(PoiKind.WINE_KEG,11,false,false,false);
        Pos nowNearest=f.machine(PoiKind.WINE_KEG,100,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        for (int tick=0;tick<100 && f.open==null;tick++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertNotNull(f.open); f.playerX=100.5;
        for (int tick=0;tick<100;tick++) {
            assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
            if (f.action instanceof Action.UseBlock use && use.purpose()==Action.Use.MACHINE) break;
            f.advance();
        }
        assertEquals(new Action.UseBlock(first,Action.Use.MACHINE),f.action,"a new current position cannot replace the target already supplied by this haul");
        int visits=f.navigationCalls;
        for (int tick=0;tick<3;tick++) assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        assertEquals(visits,f.navigationCalls); assertEquals(1,f.machineClicks(),"pending native Use is not reordered or replayed");
        f.advance(); assertEquals(WorkResult.State.IDLE,f.run(module,150).state());
        assertEquals(List.of(first,nowNearest,oldNearest),f.history.stream()
            .filter(a -> a instanceof Action.UseBlock use && use.purpose()==Action.Use.MACHINE)
            .map(a -> ((Action.UseBlock)a).pos()).toList());
        assertEquals(9,f.consumed); assertEquals(1,f.tomatoWithdrawals);
    }

    @Test void bulkHaulReservesTwoRealOutputSlotsAndReturnsWhenAlreadyFunded() {
        Fixture f=new Fixture(); f.dayTime=1000;
        Arrays.fill(f.inventory,new ItemData("minecraft:dirt",64,0,null,false,999));
        for (int i=31;i<36;i++) f.inventory[i]=ItemData.EMPTY;
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        ItemData[] stock=new ItemData[20]; Arrays.fill(stock,tomato(2,64)); f.chests.put(source,stock);
        for (int n=0;n<144;n++) f.machine(PoiKind.PRESERVES_JAR,10+n,false,false,false);
        MachineModule module=new MachineModule(Feature.PRESERVES);
        for (int n=0;n<150 && f.machineClicks()==0;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(192,f.tomatoesAtMachineUse.get(0));
        assertEquals(List.of(4,3,2),f.emptySlotsAfterWithdraw);
        assertEquals(2,f.opens.get(source)); assertEquals(3,f.tomatoWithdrawals);
        assertEquals(20*64-192,Arrays.stream(f.chests.get(source)).mapToInt(ItemData::count).sum());
    }

    @Test void oneExistingFreeSlotAndFundedMachineDoesNotStartAnExtraHaul() {
        Fixture f=new Fixture(); f.dayTime=1000;
        Arrays.fill(f.inventory,new ItemData("minecraft:dirt",64,0,null,false,999));
        f.inventory[0]=tomato(0,3); f.inventory[35]=ItemData.EMPTY;
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,64));
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state()); assertEquals(1,f.machineClicks());
        assertEquals(0,f.tomatoWithdrawals); assertEquals(64,f.chests.get(source)[0].count());
    }

    @Test void bulkHaulRefusesToSpendItsLastTwoOutputSlotsOnIngredients() {
        Fixture f=new Fixture(); f.dayTime=1000;
        Arrays.fill(f.inventory,new ItemData("minecraft:dirt",64,0,null,false,999));
        f.inventory[34]=ItemData.EMPTY; f.inventory[35]=ItemData.EMPTY;
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,64));
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertTrue(engine.status().contains("two empty"));
        assertEquals(0,f.tomatoWithdrawals); assertEquals(0,f.machineClicks()); assertEquals(64,f.chests.get(source)[0].count());
    }

    @Test void indivisibleFinalStackUsesTheSmallestExcessAndNeverWithdrawsAnotherStack() {
        Fixture f=new Fixture(); f.equipHoe();
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64)); f.chests.get(source)[1]=tomato(2,4);
        f.machine(PoiKind.WINE_KEG,10,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state()); assertEquals(3,f.consumed);
        assertEquals(4,f.tomatoesAtMachineUse.get(0)); assertEquals(1,f.tomatoWithdrawals);
        assertEquals(64,f.chests.get(source)[0].count()); assertTrue(f.chests.get(source)[1].empty());
        assertEquals(1,ModuleSupport.count(f.context(),i -> i.is(ItemData.TOMATO)));
    }

    @Test void bulkHaulUsesActualMixedJarCostsAndSkipsFutureOrWorkingTargets() {
        Fixture f=new Fixture(); f.dayTime=1000; f.equipHoe();
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        f.chests.put(source,new ItemData[]{tomato(2,60),tomato(2,60),tomato(2,60),tomato(2,60)});
        for (int n=0;n<12;n++) f.machine(PoiKind.PRESERVES_JAR,10+n,false,false,false);
        for (int n=0;n<20;n++) f.machine(PoiKind.PRESERVES_JAR,30+n,false,false,true);
        f.machine(PoiKind.PRESERVES_JAR,60,true,false,false);
        f.machine(PoiKind.PRESERVES_JAR,61,false,false,false); f.profile.nextEligibleDay.put("preserves:61:64:0",3L);
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,800);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),() -> engine.status()+"; "+f.tomatoFragments());
        assertEquals(32,f.machineClicks()); assertEquals(120,f.consumed);
        assertEquals(120,f.tomatoesAtMachineUse.get(0)); assertEquals(2,f.opens.get(source));
        assertEquals(120,Arrays.stream(f.chests.get(source)).mapToInt(ItemData::count).sum());
    }

    @Test void bulkHaulVisitsEachSameGradeSourceBeforeReturningToTheMachines() {
        Fixture f=new Fixture(); f.dayTime=1000; f.equipHoe();
        Pos first=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,60));
        Pos second=f.chest(PoiKind.TOMATO_CHEST,1,2,tomato(2,60));
        for (int n=0;n<24;n++) f.machine(PoiKind.PRESERVES_JAR,10+n,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,600);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),() -> engine.status()+"; "+f.tomatoFragments());
        assertEquals(120,f.tomatoesAtMachineUse.get(0)); assertEquals(120,f.consumed);
        assertEquals(2,f.opens.get(first)); assertEquals(2,f.opens.get(second));
        assertEquals(2,f.tomatoWithdrawals); assertEquals(0,f.soldWine);
    }

    @Test void initialSupplyLocksTheLargestGradeForTheWholeCarriedBatchWithoutBalancingTotals() {
        Fixture f=new Fixture(); f.dayTime=1000;
        Pos high=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        f.chests.put(high,new ItemData[]{tomato(2,30),tomato(2,30)});
        f.chest(PoiKind.TOMATO_CHEST,1,0,tomato(0,57));
        for (int n=0;n<20;n++) f.machine(PoiKind.WINE_KEG,10+n,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,800);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(60,f.tomatoesAtMachineUse.get(0),"the supply visit loads the allocated batch rather than a grade-crossover slice");
        assertTrue(f.usedGrades.stream().allMatch(grade -> grade==2),"stored totals crossing over cannot interrupt the carried batch");
        assertEquals(3,f.opens.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(60,f.consumed);
    }

    private static Fixture nearTiedGradeFixture(int initiallyFreeSlots) {
        Fixture f=new Fixture(); f.dayTime=1000; f.equipHoe();
        for (int n=2;n<37-initiallyFreeSlots;n++) f.inventory[n]=new ItemData("minecraft:tool_"+n,1,0,null,false,999);
        Pos lead=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        ItemData[] leading=new ItemData[32]; Arrays.fill(leading,tomato(2,64)); leading[31]=tomato(2,16); f.chests.put(lead,leading);
        Pos runner=f.chest(PoiKind.TOMATO_CHEST,1,1,ItemData.EMPTY);
        ItemData[] following=new ItemData[30]; Arrays.fill(following,tomato(1,64)); following[29]=tomato(1,44); f.chests.put(runner,following);
        for (int n=0;n<384;n++) f.machine(PoiKind.WINE_KEG,10+n,false,false,false);
        return f;
    }

    @Test void nearTiedLargeGradesComplete384EmptyKegsWithEightInitiallyFreeSlots() {
        Fixture f=nearTiedGradeFixture(8);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,10000);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),() -> engine.status()+"; "+f.tomatoFragments());
        assertEquals(384,f.machineClicks()); assertEquals(1152,f.consumed);
        assertEquals(384,f.tomatoesAtMachineUse.get(0),"capacity determines this haul, not the100-tomato grade lead");
        assertTrue(f.usedGrades.subList(0,128).stream().allMatch(g -> g==2)); assertEquals(1,f.usedGrades.get(128));
        assertTrue(f.opens.values().stream().mapToInt(Integer::intValue).sum()<=9,"only three supply visits are needed");
        assertTrue(f.emptySlotsAfterWithdraw.stream().allMatch(n -> n>=2));
        assertEquals(0,f.soldWine); assertFalse(f.sleeping);
    }

    @Test void fiveFreeSlotsKeepTheAllocatedGradeThroughoutTheFirstCarriedBatch() {
        Fixture f=nearTiedGradeFixture(5);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE);
        for (int n=0;n<1000 && f.machineClicks()<35 && engine.running();n++) { engine.tick(f.context()); f.advance(); }
        assertTrue(engine.running(),engine.status()); assertEquals(35,f.machineClicks());
        assertEquals(192,f.tomatoesAtMachineUse.get(0)); assertTrue(f.usedGrades.stream().allMatch(grade -> grade==2));
        assertTrue(f.emptySlotsAfterWithdraw.stream().allMatch(n -> n>=2));
        // This deliberately checks the first carried batch, not the eventual capacity of
        // an exceptionally packed inventory over all384 operations.
        engine.stop(f.context(),AutomationEngine.State.OFF,"end of carried-batch prefix test");
    }

    @Test void wineRefillsAllMachinesEvenWhenUnmergeablePickupHasFilledTheInventory() {
        // Native Vinery tags new ground wine only after pickup. This fixture mode
        // deliberately does not credit the ordinary fake add() auto-merge behavior.
        Fixture f=new Fixture(); f.separateFreshWineSlots=true; f.consolidationNoProgress=true;
        Arrays.fill(f.inventory,new ItemData("minecraft:dirt",64,0,null,false,999));
        f.inventory[0]=tomato(0,12); f.inventory[34]=ItemData.EMPTY; f.inventory[35]=ItemData.EMPTY;
        for (int n=0;n<3;n++) f.machine(PoiKind.WINE_KEG,10+n,true,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertEquals(3,f.machineClicks()); assertEquals(9,f.consumed); assertNotNull(f.dropped);
        assertEquals(2,Arrays.stream(f.inventory).filter(i -> i.is(ItemData.WINE)).count());
        assertTrue(Arrays.stream(f.inventory).filter(i -> i.is(ItemData.WINE)).allMatch(i -> i.count()==1 && i.year().equals(f.wineClockYear)));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty(),"wine output is no longer tracked or used as a production stop condition");
    }

    @Test void firstOutputConsolidationPrefersTheNewOrIncreasedProductSlotOverAnOlderPartial() {
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) for (boolean newSlot:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.equipHoe(); f.pickup=false;
            for (int n=2;n<9;n++) f.inventory[n]=new ItemData("minecraft:tool_"+n,1,0,null,false,999);
            f.inventory[2]=ItemData.EMPTY; // Output scratch must never borrow the material slot1.
            f.inventory[1]=tomato(2,feature==Feature.WINE ? 6 : 10);
            ItemData old=feature==Feature.WINE ? wine(f.wineClockYear,20,0) : new ItemData(ItemData.PRESERVES,20,0,null,false,99);
            f.inventory[9]=old; f.inventory[10]=old;
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,true,true,false);
            MachineModule module=new MachineModule(feature);
            if (feature==Feature.PRESERVES) f.awaitPickup(module);
            else for (int n=0;f.dropped==null;n++) {
                assertTrue(n<100,"wine never received a native machine acknowledgement");
                assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance();
            }
            int receivedSlot=newSlot ? 11 : 10;
            f.inventory[receivedSlot]=newSlot ? f.dropped : new ItemData(old.id(),21,old.quality(),old.year(),old.hoe(),old.durability());
            f.dropped=null;
            assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
            Action.ConsolidateInventory first=f.history.stream().filter(Action.ConsolidateInventory.class::isInstance)
                .map(a -> (Action.ConsolidateInventory)a).findFirst().orElseThrow();
            assertEquals(receivedSlot,first.plan().sourceIndex(),"the first equally bounded plan must use the actual pickup delta, not the older partial at slot9");
            assertEquals(newSlot ? 1 : 21,first.plan().expectedItems().get(receivedSlot).count());
            assertNotEquals(1,first.plan().scratchHotbar()); assertTrue(f.inventory[1].is(ItemData.TOMATO));
            assertTrue(f.profile.pendingMachineOutputs.isEmpty()); assertEquals(1,f.machineClicks());
        }
    }

    @Test void bulkWine384UsesOneIngredientHaulAndConsolidatesSeparatelyPickedUpBottles() {
        Fixture f=new Fixture(); f.dayTime=1000; f.separateFreshWineSlots=true;
        // This is a composition simulation of late wine tagging plus acknowledged
        // consolidation, not a claim that the ordinary fake auto-merge matches Vinery.
        for (int n=1;n<9;n++) f.inventory[n]=new ItemData("minecraft:tool_"+n,1,0,null,n==4,999);
        f.profile.hoeHotbarSlot=4; f.inventory[5]=ItemData.EMPTY;
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        ItemData[] supplies=new ItemData[18]; Arrays.fill(supplies,tomato(2,64)); f.chests.put(source,supplies);
        for (int n=0;n<384;n++) f.machine(PoiKind.WINE_KEG,10+n,true,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,10000);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(384,f.machineClicks()); assertEquals(1152,f.consumed); assertEquals(1152,f.tomatoesAtMachineUse.get(0));
        assertEquals(18,f.tomatoWithdrawals); assertTrue(f.opens.get(source)<=6,"periodic stock refreshes are allowed, per-stack underground hauls are not");
        assertEquals(384,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
        assertEquals(6,Arrays.stream(f.inventory).filter(i -> i.is(ItemData.WINE)).count());
        assertTrue(f.emptySlotsAfterWithdraw.stream().allMatch(n -> n>=2));
        assertTrue(Arrays.stream(f.chests.get(source)).allMatch(ItemData::empty));
        assertTrue(f.history.stream().anyMatch(Action.ConsolidateInventory.class::isInstance));
        assertEquals("minecraft:tool_4",f.inventory[4].id()); assertEquals(0,f.soldWine); assertFalse(f.sleeping);
    }

    @Test void incompatibleTomatoFragmentsDoNotTriggerMoreHaulsOrShuffleBasedMergeRetries() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(2,1); f.inventory[9]=tomato(2,2);
        f.profile.hoeHotbarSlot=4; f.consolidationNoProgress=true;
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64)); f.machine(PoiKind.WINE_KEG,10,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state());
        long attempts=f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count();
        assertEquals(1,attempts); assertEquals(0,f.tomatoWithdrawals); assertEquals(64,f.chests.get(source)[0].count());
        ItemData first=f.inventory[0]; f.inventory[0]=f.inventory[9]; f.inventory[9]=first;
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state());
        assertEquals(attempts,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count(),"moving identical fragments between slots cannot bypass the no-progress bound");
        assertEquals(0,f.machineClicks());
    }

    @Test void incompatibleLastRecipePlusOneUsesItsRealStackBeforeAnySupplyVisit() {
        for (Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            int cost=feature==Feature.WINE ? 3 : 5;
            PoiKind kind=feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR;
            Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,cost); f.inventory[9]=tomato(2,1);
            f.consolidationNoProgress=true;
            f.chest(PoiKind.TOMATO_CHEST,0,null,ItemData.EMPTY);
            f.machine(kind,10,false,false,false); f.machine(kind,11,false,false,false);
            AutomationEngine engine=isolatedMachineEngine(feature); engine.startOnce(f.context(),feature);
            for (int tick=0;tick<100 && f.machineClicks()==0 && engine.running();tick++) { engine.tick(f.context()); f.advance(); }
            assertTrue(engine.running(),engine.status()); assertEquals(List.of(cost),f.handCountsAtMachineUse);
            assertEquals(cost,f.consumed); assertTrue(f.opens.isEmpty(),"an acknowledged no-op merge cannot force a supply visit while a recipe is held");
            assertEquals(1,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
            assertEquals(1,ModuleSupport.count(f.context(),i -> i.is(ItemData.TOMATO)));
            f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertTrue(engine.status().contains("Not enough tomatoes"));
            assertEquals(1,f.machineClicks()); assertEquals(1,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
            assertEquals(0,f.tomatoWithdrawals); assertTrue(f.inventory[0].hoe());
        }
    }

    @Test void lockedIncompatibleFragmentsFallBackToRealCarriedRecipesWithoutRescanning() {
        for (boolean selectedGradeStillFunded:new boolean[]{true,false}) {
            Fixture f=new Fixture(); f.equipHoe();
            f.inventory[1]=tomato(2,selectedGradeStillFunded ? 6 : 5);
            f.inventory[9]=tomato(2,selectedGradeStillFunded ? 1 : 2);
            f.inventory[10]=tomato(1,6); f.consolidationNoProgress=true;
            f.chest(PoiKind.TOMATO_CHEST,0,null,tomato(3,64));
            int count=selectedGradeStillFunded ? 4 : 3;
            for (int index=0;index<count;index++) f.machine(PoiKind.WINE_KEG,10+index,false,false,false);
            AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,200);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
            assertEquals(selectedGradeStillFunded ? List.of(2,2,1,1) : List.of(2,1,1),f.usedGrades);
            assertEquals(count*3,f.consumed); assertEquals(selectedGradeStillFunded ? 1 : 4,ModuleSupport.count(f.context(),i -> i.is(ItemData.TOMATO)));
            assertEquals(1,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
            assertTrue(f.opens.isEmpty()); assertEquals(0,f.tomatoWithdrawals); assertTrue(f.inventory[0].hoe());
            assertTrue(f.history.stream().noneMatch(a -> a instanceof Action.SwapHotbar swap && swap.inventoryIndex()==swap.hotbarSlot()));
        }
    }

    @Test void noSafeIngredientMergePlanStillAllowsASufficientSingleStack() {
        Fixture f=new Fixture(); f.equipHoe();
        // MAIN-only fragments have neither a compatible hotbar destination nor
        // a safe scratch: actual hoes may never be borrowed by a merge plan.
        for (int index=1;index<9;index++) f.inventory[index]=new ItemData("minecraft:iron_hoe",1,0,null,true,999);
        f.inventory[9]=tomato(2,3); f.inventory[10]=tomato(2,1);
        f.chest(PoiKind.TOMATO_CHEST,0,null,ItemData.EMPTY);
        f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
        assertTrue(ProductionMergePlanner.planTomatoes(f.inventory(),Feature.WINE,f.profile.hoeHotbarSlot,2,null).isEmpty());
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE);
        for (int tick=0;tick<100 && f.machineClicks()==0 && engine.running();tick++) { engine.tick(f.context()); f.advance(); }
        assertTrue(engine.running(),engine.status()); assertEquals(List.of(2),f.usedGrades); assertTrue(f.opens.isEmpty());
        f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertTrue(engine.status().contains("Not enough tomatoes"));
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed); assertEquals(0,f.tomatoWithdrawals);
        assertEquals(9,Arrays.stream(f.inventory).filter(ItemData::hoe).count());
        assertTrue(f.history.stream().noneMatch(Action.ConsolidateInventory.class::isInstance));
    }

    @Test void failedInventoryConsolidationPausesTheProductionOnlyRunWithoutFallbackActions() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(2,1); f.inventory[9]=tomato(2,2);
        f.profile.hoeHotbarSlot=4; f.consolidationFails=true; f.machine(PoiKind.WINE_KEG,10,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertTrue(engine.status().contains("consolidation interrupted"));
        assertEquals(0,f.machineClicks()); assertEquals(0,f.consumed); assertEquals(0,f.tomatoWithdrawals);
        assertEquals(1,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
    }

    @Test void hotbarOnlyTomatoFragmentsCanBridgeOnceThenReallyMergeWithoutAnotherHaul() {
        Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,2); f.inventory[2]=tomato(2,1);
        f.machine(PoiKind.WINE_KEG,10,false,false,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        List<Action.ConsolidateInventory> merges=f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).map(a -> (Action.ConsolidateInventory)a).toList();
        assertEquals(2,merges.size()); assertTrue(merges.get(0).plan().reposition()); assertFalse(merges.get(1).plan().reposition());
        assertEquals(3,f.consumed); assertEquals(1,f.machineClicks()); assertEquals(0,f.tomatoWithdrawals);
        assertTrue(f.inventory[0].hoe());
    }

    @Test void hotbarBridgeDoesNotTurnNativeMergeNoProgressIntoARepositionLoop() {
        Fixture f=new Fixture(); f.equipHoe(); f.inventory[1]=tomato(2,2); f.inventory[2]=tomato(2,1); f.consolidationNoProgress=true;
        f.machine(PoiKind.WINE_KEG,10,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state());
        assertEquals(2,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
        ItemData moved=f.inventory[9]; f.inventory[9]=ItemData.EMPTY; f.inventory[1]=moved;
        assertEquals(WorkResult.State.BLOCKED,f.run(module,100).state());
        assertEquals(2,f.history.stream().filter(Action.ConsolidateInventory.class::isInstance).count());
        assertEquals(0,f.machineClicks()); assertEquals(0,f.tomatoWithdrawals);
    }

    @Test void contaminatedSourceBlocksARequiredSupplyVisitBeforeAnyWithdrawal() {
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) {
            Fixture f=new Fixture(); f.inventory[0]=tomato(2,2);
            Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64));
            f.chests.get(source)[1]=wine(8,5,0);
            ItemData[] before=f.chests.get(source).clone();
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,false,false,false);
            WorkResult result=f.run(new MachineModule(feature),100);
            assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("non-tomato"));
            assertArrayEquals(before,f.chests.get(source)); assertEquals(2,f.inventory[0].count());
            assertEquals(0,f.tomatoWithdrawals); assertEquals(0,f.withdrawnWine); assertEquals(0,f.machineClicks());
            assertTrue(f.history.stream().noneMatch(Action.QuickMove.class::isInstance)); assertTrue(f.profile.nextEligibleDay.isEmpty());
        }
    }

    @Test void whollyWrongTomatoSourceBlocksInsteadOfReportingAnIngredientShortage() {
        Fixture f=new Fixture();
        ItemData unrelated=new ItemData("minecraft:cobblestone",64,0,null,false,999);
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,unrelated);
        f.machine(PoiKind.WINE_KEG,10,false,false,false);
        WorkResult result=f.run(new MachineModule(Feature.WINE),100);
        assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("non-tomato"));
        assertEquals(unrelated,f.chests.get(source)[0]); assertEquals(0,f.tomatoWithdrawals); assertEquals(0,f.machineClicks());
        assertTrue(f.history.stream().noneMatch(Action.QuickMove.class::isInstance)); assertTrue(f.profile.nextEligibleDay.isEmpty());
    }

    @Test void unknownTomatoSourceGradeStillBlocksTheEntireSnapshot() {
        for (int wrongGrade:new int[]{-1,4}) {
            Fixture f=new Fixture(); Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64));
            f.chests.get(source)[1]=tomato(wrongGrade,4); ItemData[] before=f.chests.get(source).clone();
            f.machine(PoiKind.WINE_KEG,10,false,false,false);
            WorkResult result=f.run(new MachineModule(Feature.WINE),100);
            assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("grade"));
            assertArrayEquals(before,f.chests.get(source)); assertEquals(0,f.tomatoWithdrawals); assertEquals(0,f.machineClicks());
            assertTrue(f.history.stream().noneMatch(Action.QuickMove.class::isInstance));
        }
    }

    @Test void pureTomatoSourceWithEmptySlotsAllowsWithdrawalAndIgnoresPlayerTools() {
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) {
            Fixture f=new Fixture(); f.equipHoe();
            ItemData personalItem=new ItemData("minecraft:cobblestone",64,0,null,false,999); f.inventory[20]=personalItem;
            int batch=feature==Feature.WINE ? 3 : 5;
            Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,batch));
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,false,false,false);
            WorkResult result=f.run(new MachineModule(feature),100);
            assertEquals(WorkResult.State.IDLE,result.state(),result.message()); assertEquals(1,f.tomatoWithdrawals);
            assertEquals(batch,f.consumed); assertEquals(1,f.machineClicks()); assertTrue(Arrays.stream(f.chests.get(source)).allMatch(ItemData::empty));
            assertEquals(personalItem,f.inventory[20]); assertTrue(f.inventory[f.profile.hoeHotbarSlot].hoe());
        }
    }

    @Test void sourceContaminationAfterInitialCountIsRejectedOnReopenBeforeWithdrawal() {
        Fixture f=new Fixture(); Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64));
        f.machine(PoiKind.WINE_KEG,10,false,false,false); MachineModule module=new MachineModule(Feature.WINE);
        for (int n=0;n<100 && f.opens.getOrDefault(source,0)<2;n++) { module.tick(f.context()); f.advance(); }
        assertEquals(2,f.opens.get(source)); assertEquals(0,f.tomatoWithdrawals);
        ItemData contamination=new ItemData(ItemData.ROTTEN,1,0,null,false,999); f.chests.get(source)[1]=contamination;
        WorkResult result=module.tick(f.context());
        assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("non-tomato"));
        assertEquals(64,f.chests.get(source)[0].count()); assertEquals(contamination,f.chests.get(source)[1]);
        assertEquals(0,f.tomatoWithdrawals); assertEquals(0,f.machineClicks());
        assertTrue(f.history.stream().noneMatch(Action.QuickMove.class::isInstance));
    }

    @Test void bulkWithdrawalWaitsForAcknowledgementAndRechecksTheNextSourceStack() {
        Fixture f=new Fixture(); f.equipHoe();
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(2,64)); f.chests.get(source)[1]=tomato(2,64);
        for (int n=0;n<30;n++) f.machine(PoiKind.PRESERVES_JAR,10+n,false,false,false);
        MachineModule module=new MachineModule(Feature.PRESERVES);
        for (int n=0;n<100 && !(f.action instanceof Action.QuickMove);n++) { module.tick(f.context()); if (!(f.action instanceof Action.QuickMove)) f.advance(); }
        assertTrue(f.action instanceof Action.QuickMove); int actions=f.history.size();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); assertEquals(actions,f.history.size());
        f.advance(); f.chests.get(source)[1]=tomato(3,64);
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        assertEquals(1,f.tomatoWithdrawals); assertEquals(64,ModuleSupport.count(f.context(),i -> ModuleSupport.tomatoGrade(i,2)));
        assertEquals(0,f.machineClicks());
        for (int n=0;n<100 && f.machineClicks()==0;n++) { module.tick(f.context()); f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(List.of(2),f.usedGrades);
        assertEquals(1,f.tomatoWithdrawals,"recount the now-different grade; do not withdraw it as the old selected grade");
        assertEquals(tomato(3,64),f.chests.get(source)[1]);
    }

    @Test void cancellationDiscardsChestCacheBeforeTheNextMachine() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3);
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); Pos richer=f.chest(PoiKind.TOMATO_CHEST,1,2,ItemData.EMPTY);
        f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        while (f.machineClicks()==0) { module.tick(f.context()); f.advance(); }
        module.reset(); f.cancel(); f.chests.get(richer)[0]=tomato(2,30);
        assertEquals(WorkResult.State.IDLE,f.run(module,100).state());
        assertEquals(List.of(0,2),f.usedGrades);
        assertEquals(2,f.opens.get(richer),"after carried material is gone, resume counts sources and reopens the selected source");
    }

    @Test void elapsedTimeOrDayChangeDoesNotInterruptUsableCarriedIngredients() {
        for (boolean nextDay:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.dayTime=1000; f.inventory[0]=tomato(0,9);
            f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); Pos richer=f.chest(PoiKind.TOMATO_CHEST,1,2,ItemData.EMPTY);
            f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
            MachineModule module=new MachineModule(Feature.WINE);
            while (!f.profile.nextEligibleDay.containsKey("wine:10:64:0")) { module.tick(f.context()); f.advance(); }
            f.chests.get(richer)[0]=tomato(2,30);
            if (nextDay) f.dayTime+=24000; else f.ticks+=1201;
            assertEquals(WorkResult.State.IDLE,f.run(module,100).state());
            assertEquals(List.of(0,0),f.usedGrades,"stored changes and elapsed time cannot interrupt a carried batch");
            assertTrue(f.opens.isEmpty());
        }
    }

    @Test void fullTomatoStorageYieldsToProductionAndSleepsOnlyAfterTomatoesAreUsed() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.dayTime=13000;
        Pos chest=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,64)); f.chests.get(chest)[1]=tomato(0,64);
        f.machine(PoiKind.WINE_KEG,1,false,false,false); f.poi(PoiKind.BED,2,null);
        AutomationEngine engine=new AutomationEngine(List.of(new TomatoStorageModule(),new MachineModule(Feature.WINE),new SleepModule()));
        engine.start(f.context());
        for (int i=0;i<300 && !f.sleeping;i++) { engine.tick(f.context()); f.advance(); }
        assertEquals(1,f.machineClicks(),"a full destination must not starve production that consumes carried tomatoes");
        assertEquals(3,f.consumed); assertTrue(f.sleeping,"sleep becomes valid once the storage blocker is resolved");
        assertEquals(128,Arrays.stream(f.chests.get(chest)).mapToInt(ItemData::count).sum());
    }

    @Test void unknownWineYearDoesNotStarveShippingButStillPreventsSleep() {
        Fixture f=new Fixture(); f.inventory[0]=wine(null); f.dayTime=13000;
        f.inventory[1]=new ItemData(ItemData.PRESERVES,2,0,null,false,999);
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY); f.poi(PoiKind.BED,1,null);
        AutomationEngine engine=new AutomationEngine(List.of(new WineStorageModule(),new ShippingModule(),new SleepModule()));
        engine.start(f.context());
        for (int i=0;i<350;i++) { engine.tick(f.context()); f.advance(); }
        assertEquals(2,f.chests.get(bin)[0].count(),"a metadata wait that times out must yield to unrelated shipping");
        assertEquals(ItemData.PRESERVES,f.chests.get(bin)[0].id()); assertFalse(f.sleeping);
        assertTrue(f.inventory[0].is(ItemData.WINE));
    }

    @Test void wineStorageRelievesInventoryBetweenMachineBatchesBeforeSleep() {
        Fixture f=new Fixture(); f.dayTime=13000; f.inventory[0]=tomato(0,9); f.wineClockYear=2;
        for (int i=1;i<35;i++) f.inventory[i]=new ItemData("minecraft:dirt",64,0,null,false,999);
        Pos storage=f.chest(PoiKind.WINE_CHEST,0,2,ItemData.EMPTY);
        f.machine(PoiKind.WINE_KEG,1,true,true,false); f.machine(PoiKind.WINE_KEG,2,true,true,false); f.poi(PoiKind.BED,3,null);
        AutomationEngine engine=new AutomationEngine(List.of(new WineStorageModule(),new MachineModule(Feature.WINE),new SleepModule()));
        engine.start(f.context());
        for (int i=0;i<400 && !f.sleeping;i++) { engine.tick(f.context()); f.advance(); }
        assertEquals(2,f.machineClicks()); assertEquals(6,f.consumed); assertTrue(f.sleeping);
        assertEquals(2,Arrays.stream(f.chests.get(storage)).mapToInt(ItemData::count).sum());
        assertEquals(6L,f.profile.nextEligibleDay.get("wine:1:64:0")); assertEquals(6L,f.profile.nextEligibleDay.get("wine:2:64:0"));
    }

    @Test void largestGradeWinsAndTiesPreferLowerGrade() {
        assertEquals(2,MachineModule.chooseGrade(new int[]{20,3,21,0},3));
        assertEquals(0,MachineModule.chooseGrade(new int[]{20,20,20,0},3));
        assertEquals(-1,MachineModule.chooseGrade(new int[]{2,2,2,2},3));
        assertTrue(new MachineModule(Feature.WINE).priority() < new MachineModule(Feature.PRESERVES).priority());
    }

    @Test void disposalUsesOnlyExactRottenItemAndWaitsForAcknowledgement() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,7); f.inventory[1] = new ItemData(ItemData.ROTTEN,4,0,null,false,999);
        f.poi(PoiKind.DISPOSAL,0,null);
        DisposalModule module = new DisposalModule();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        assertInstanceOf(Action.ThrowRotten.class,f.action);
        module.tick(f.context());
        assertEquals(1,f.history.size(),"pending throw must not be resubmitted");
        f.advance();
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(7,f.inventory[0].count()); assertTrue(f.inventory[1].empty());
    }

    @Test void menuSelectionNeverUsesOffhandOrArmorSlots() {
        Fixture f = new Fixture(); f.inventory[3] = tomato(0,5);
        f.extraMenuSlot = new ItemSlot(100,40,true,tomato(0,20));
        assertEquals(3,ModuleSupport.menuPlayerItem(f.context(),i -> i.is(ItemData.TOMATO)).inventoryIndex());
    }

    @Test void tomatoStorageMayDepositDifferentActualGradesInTheSameRegisteredContainer() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(2,15); f.inventory[1] = tomato(1,9);
        Pos first = f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        Pos second = f.chest(PoiKind.TOMATO_CHEST,1,1,ItemData.EMPTY);
        WorkResult result = f.run(new TomatoStorageModule(),80);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(15,f.chests.get(first)[0].count()); assertEquals(2,f.chests.get(first)[0].quality());
        assertEquals(9,f.chests.get(first)[1].count()); assertEquals(1,f.chests.get(first)[1].quality());
        assertTrue(Arrays.stream(f.chests.get(second)).allMatch(ItemData::empty));
    }

    @Test void tomatoStorageRefusesForeignProductsButDoesNotJudgeTomatoGrades() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(2,15);
        f.chest(PoiKind.TOMATO_CHEST,0,2,wine(8));
        WorkResult result = f.run(new TomatoStorageModule(),20);
        assertEquals(WorkResult.State.BLOCKED,result.state());
        assertFalse(f.history.stream().anyMatch(Action.QuickMove.class::isInstance));
    }

    @Test void wineWaitsForActualYearAndUsesThatYearOnly() {
        Fixture f = new Fixture(); f.inventory[0] = wine(null);
        WineStorageModule module = new WineStorageModule();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        f.ticks = 101;
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertTrue(f.history.isEmpty());
        f.inventory[0] = wine(12);
        Pos chest = f.chest(PoiKind.WINE_CHEST,0,12,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,f.run(module,30).state());
        assertEquals(12,f.chests.get(chest)[0].year());
    }

    @Test void unregisteredWineYearDoesNotEnterDifferentYearChest() {
        Fixture f = new Fixture(); f.inventory[0] = wine(12);
        f.chest(PoiKind.WINE_CHEST,0,11,ItemData.EMPTY);
        assertEquals(WorkResult.State.BLOCKED,f.run(new WineStorageModule(),10).state());
        assertTrue(f.history.isEmpty());
    }

    @Test void standardShippingOneShotDeliversPreservesAndExactPineTarWithoutOtherJobsOrReserveWithdrawals() {
        Fixture f=new Fixture(); f.reportConfirmedCount=true; f.profile.enabled.put(Feature.SHIPPING,false);
        f.inventory[0]=new ItemData(ItemData.PINE_TAR,5,0,null,false,999);
        f.inventory[1]=new ItemData(ItemData.PRESERVES,3,0,null,false,999);
        f.inventory[2]=new ItemData(ItemData.PINE_TAR,64,0,null,false,999);
        f.inventory[3]=wine(8,7,0); f.inventory[4]=tomato(2,9);
        f.inventory[5]=new ItemData("society:oak_resin",4,0,null,false,999);
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        ItemData[] slots=new ItemData[6]; Arrays.fill(slots,ItemData.EMPTY); f.chests.put(bin,slots);
        Pos reserve=f.chest(PoiKind.WINE_CHEST,1,8,wine(8,64,0));
        AutomationEngine engine=isolatedStandardShippingEngine();
        engine.startOnce(f.context(),Feature.SHIPPING); f.runUntilStopped(engine,200);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(69,Arrays.stream(slots).filter(i -> i.is(ItemData.PINE_TAR)).mapToInt(ItemData::count).sum());
        assertEquals(3,Arrays.stream(slots).filter(i -> i.is(ItemData.PRESERVES)).mapToInt(ItemData::count).sum());
        assertEquals(0,ModuleSupport.count(f.context(),ItemData::standardShippingProduct));
        assertEquals(7,f.inventory[3].count()); assertEquals(9,f.inventory[4].count()); assertEquals(4,f.inventory[5].count());
        assertEquals(64,f.chests.get(reserve)[0].count()); assertFalse(f.opens.containsKey(reserve));
        assertEquals(0,f.withdrawnWine); assertEquals(0,f.soldWine); assertFalse(f.sleeping); assertEquals(0,f.machineClicks());
        assertFalse(f.profile.enabled(Feature.SHIPPING)); assertNull(f.session.oneShotFeature);
    }

    @Test void pineTarOnlyShipsButOtherTreeProductsBucketsAndOffhandRemainUntouched() {
        Fixture f=new Fixture(); f.inventory[0]=new ItemData(ItemData.PINE_TAR,12,0,null,false,999);
        f.inventory[1]=new ItemData("society:maple_syrup",6,0,null,false,999);
        f.inventory[2]=new ItemData("society:oak_resin",7,0,null,false,999);
        f.inventory[3]=new ItemData("society:pine_tar_bucket",1,0,null,false,999);
        f.extraMenuSlot=new ItemSlot(999,40,true,new ItemData(ItemData.PINE_TAR,8,0,null,false,999));
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,f.run(new ShippingModule(),100).state());
        assertEquals(ItemData.PINE_TAR,f.chests.get(bin)[0].id()); assertEquals(12,f.chests.get(bin)[0].count());
        assertTrue(f.inventory[0].empty()); assertEquals(6,f.inventory[1].count()); assertEquals(7,f.inventory[2].count());
        assertEquals(1,f.inventory[3].count()); assertEquals(8,f.extraMenuSlot.item().count());
        assertTrue(f.history.stream().filter(Action.QuickMove.class::isInstance).map(a -> (Action.QuickMove)a).noneMatch(a -> a.slot()==999));
        assertTrue(f.history.stream().allMatch(a -> a instanceof Action.QuickMove || a instanceof Action.CloseContainer
            || a instanceof Action.UseBlock u && u.purpose()==Action.Use.OPEN_CONTAINER));
    }

    @Test void unrelatedTreeByproductsAloneDoNotOpenShippingOrBecomeSaleCandidates() {
        Fixture f=new Fixture(); f.inventory[0]=new ItemData("society:maple_syrup",6,0,null,false,999);
        f.inventory[1]=new ItemData("society:oak_resin",7,0,null,false,999);
        f.inventory[2]=new ItemData("society:pine_tar_bucket",1,0,null,false,999);
        f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,new ShippingModule().tick(f.context()).state());
        assertTrue(f.history.isEmpty()); assertEquals(6,f.inventory[0].count()); assertEquals(7,f.inventory[1].count());
    }

    @Test void continuousShippingHonoursDisabledToggleThenDeliversBothAuthorizedProductsWhenEnabled() {
        Fixture f=new Fixture(); f.profile.enabled.put(Feature.SHIPPING,false);
        f.inventory[0]=new ItemData(ItemData.PINE_TAR,2,0,null,false,999);
        f.inventory[1]=new ItemData(ItemData.PRESERVES,3,0,null,false,999);
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        AutomationEngine engine=new AutomationEngine(List.of(new ShippingModule())); engine.start(f.context());
        for (int n=0;n<50;n++) { engine.tick(f.context()); f.advance(); }
        assertTrue(f.history.isEmpty()); assertEquals(5,ModuleSupport.count(f.context(),ItemData::standardShippingProduct));
        f.profile.enabled.put(Feature.SHIPPING,true); engine.start(f.context());
        for (int n=0;n<100;n++) { engine.tick(f.context()); f.advance(); }
        assertEquals(0,ModuleSupport.count(f.context(),ItemData::standardShippingProduct));
        assertEquals(5,Arrays.stream(f.chests.get(bin)).mapToInt(ItemData::count).sum());
    }

    @Test void pineTarDeliveryWaitsForServerAckAndARejectedTransferCannotCompleteOneShot() {
        Fixture f=new Fixture(); f.inventory[0]=new ItemData(ItemData.PINE_TAR,4,0,null,false,999);
        Pos bin=f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        AutomationEngine engine=isolatedStandardShippingEngine(); engine.startOnce(f.context(),Feature.SHIPPING);
        for (int n=0;n<50 && !(f.action instanceof Action.QuickMove);n++) { engine.tick(f.context()); if (!(f.action instanceof Action.QuickMove)) f.advance(); }
        assertTrue(f.action instanceof Action.QuickMove); int sent=f.history.size();
        engine.tick(f.context()); assertEquals(sent,f.history.size()); assertTrue(engine.running());
        assertEquals(4,f.inventory[0].count()); assertTrue(f.chests.get(bin)[0].empty());
        f.cancel(); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(4,f.inventory[0].count());
        assertTrue(f.chests.get(bin)[0].empty()); assertNull(f.session.oneShotFeature);
    }

    private static AutomationEngine isolatedStandardShippingEngine() {
        List<AutomationModule> modules=new ArrayList<>(); modules.add(new ShippingModule());
        for (Feature feature:Feature.values()) if (feature!=Feature.SHIPPING) modules.add(new AutomationModule() {
            @Override public Feature feature() { return feature; }
            @Override public int priority() { return 1; }
            @Override public WorkResult tick(Context c) { fail("Standard shipping must not run "+feature); return WorkResult.idle(); }
            @Override public void reset() { }
        });
        return new AutomationEngine(modules);
    }

    @Test void standardShippingDoesNotSellTomatoesOrWine() {
        Fixture f = new Fixture(); f.inventory[0] = wine(4); f.inventory[1] = tomato(3,5);
        f.inventory[2] = new ItemData(ItemData.PRESERVES,3,0,null,false,999);
        Pos bin = f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,f.run(new ShippingModule(),30).state());
        assertEquals(ItemData.PRESERVES,f.chests.get(bin)[0].id());
        assertEquals(ItemData.WINE,f.inventory[0].id()); assertEquals(ItemData.TOMATO,f.inventory[1].id());
    }

    @Test void changedMenuCancelsDepositBeforeNextClick() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,8);
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY);
        TomatoStorageModule module = new TomatoStorageModule();
        module.tick(f.context()); module.tick(f.context()); f.advance();
        module.tick(f.context()); // first quick move submitted
        f.open = null; f.advance();
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertEquals(1,f.history.stream().filter(Action.QuickMove.class::isInstance).count());
    }

    @Test void requiredSupplyCountsChestAndInsufficientCarriedStockThenReopensBeforeWithdrawal() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,1); f.inventory[8] = new ItemData("minecraft:diamond_hoe",1,0,null,true,100);
        f.profile.hoeHotbarSlot = 8;
        Pos grade0 = f.chest(PoiKind.TOMATO_CHEST,1,0,tomato(0,10));
        Pos grade2 = f.chest(PoiKind.TOMATO_CHEST,2,2,tomato(2,30));
        Pos keg = f.machine(PoiKind.WINE_KEG,3,false,false,false);
        WorkResult result = f.run(new MachineModule(Feature.WINE),120);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(2,f.usedGrades.get(0)); assertEquals(3,f.consumed);
        assertEquals(10,f.chests.get(grade0)[0].count());
        assertTrue(f.chests.get(grade2)[0].empty());
        long selectedOpens = f.history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose() == Action.Use.OPEN_CONTAINER && u.pos().equals(grade2)).count();
        assertEquals(2,selectedOpens,"source must be opened once to count and again before withdrawing");
        assertTrue(f.blocks.get(keg).flag("working")); assertTrue(f.inventory[8].hoe());
    }

    @Test void stockChangedOnReopenIsRecountedBeforeWithdrawal() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,2);
        Pos selected = f.chest(PoiKind.TOMATO_CHEST,1,2,tomato(2,30));
        f.chest(PoiKind.TOMATO_CHEST,3,0,tomato(0,1));
        f.machine(PoiKind.WINE_KEG,2,false,false,false);
        f.emptyOnSecondOpen = selected;
        WorkResult result = f.run(new MachineModule(Feature.WINE),120);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(List.of(0),f.usedGrades);
        assertEquals(1,f.tomatoWithdrawals); assertTrue(f.chests.get(selected)[0].empty());
    }

    @Test void readyMachineCollectsAndRefillsOnceThenWaitsForPickup() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.PRESERVES_JAR,0,true,true,true);
        f.pickup = false;
        MachineModule module = new MachineModule(Feature.PRESERVES);
        for (int i = 0; i < 30; i++) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(0,ModuleSupport.count(f.context(),i -> i.is(ItemData.PRESERVES)));
        f.pickup = true; f.advance();
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void elevatedOutputWaitsForObservedFallAndTemporaryPathFailureWithoutRepeatingUse() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false;
        Pos keg=f.machine(PoiKind.PRESERVES_JAR,1,66,true,true,true);
        f.minimumReaches.put(keg,4.0);
        Pos high=keg.offset(0,0,-1), landed=new Pos(1,64,-1);
        f.unstandable.add(high); f.blockedPaths.add(high);
        MachineModule module=new MachineModule(Feature.PRESERVES);
        while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        f.ground.add(new GroundItem(1,1.5,66.2,-.5,preserves(1)));
        // Unrelated nearby products must not become a guessed floor target for preserves.
        f.ground.add(new GroundItem(2,1.5,64.1,-.5,wine(f.wineClockYear)));
        for (int n=0;n<12;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25),"no path to the machine-height output cell");
        f.ground.set(0,new GroundItem(1,1.5,64.1,-.5,preserves(1))); f.blockedPaths.add(landed);
        for (int n=0;n<3;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertTrue(f.navigationHistory.stream().anyMatch(v -> v.target().equals(landed) && v.reach()==.9));
        f.blockedPaths.remove(landed);
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        f.pickup=true; f.advance();
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(1,ModuleSupport.count(f.context(),i -> i.is(ItemData.PRESERVES)));
        assertTrue(f.navigationHistory.stream().filter(v -> v.target().equals(keg)).allMatch(v -> v.reach()==4.0));
    }

    @Test void automaticPickupCanCollectElevatedOutputWithoutAnyGroundLevelNavigation() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false;
        Pos keg=f.machine(PoiKind.PRESERVES_JAR,1,66,true,true,true);
        f.minimumReaches.put(keg,4.0); f.unstandable.add(keg.offset(0,0,-1));
        MachineModule module=new MachineModule(Feature.PRESERVES);
        while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        f.ground.add(new GroundItem(1,1.5,66.2,-.5,preserves(1)));
        for (int n=0;n<10;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        f.pickup=true; f.advance(); // Inventory acknowledgement, with no observed landing, is sufficient.
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state()); assertEquals(1,f.machineClicks());
        assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25));
    }

    @Test void elevatedOutputThatNeverArrivesTimesOutAndLatchesAfterExactlyOneUse() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false; f.profile.interactionTimeoutTicks=12;
        Pos keg=f.machine(PoiKind.PRESERVES_JAR,1,66,true,true,true);
        f.unstandable.add(keg.offset(0,0,-1)); f.blockedPaths.add(keg.offset(0,0,-1));
        MachineModule module=new MachineModule(Feature.PRESERVES);
        while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        long collectedAt=f.ticks;
        f.ground.add(new GroundItem(1,1.5,66.2,-.5,preserves(1)));
        WorkResult result=f.run(module,40);
        assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("not picked up"));
        assertTrue(f.ticks-collectedAt>f.profile.interactionTimeoutTicks,"falling output gets the full bounded confirmation window");
        for (int n=0;n<5;n++) { f.advance(); assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); }
        assertEquals(1,f.machineClicks()); assertNotNull(f.dropped);
        assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25));
    }

    @Test void highRackMachineUsesNormalFourBlockReachButIngredientChestKeepsItsExistingReach() {
        Fixture f=new Fixture(); Pos source=f.chest(PoiKind.TOMATO_CHEST,0,0,tomato(0,3));
        Pos keg=f.machine(PoiKind.WINE_KEG,1,66,false,false,false); f.minimumReaches.put(keg,4.0);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),100).state());
        assertEquals(1,f.machineClicks());
        assertTrue(f.navigationHistory.stream().filter(v -> v.target().equals(keg)).allMatch(v -> v.reach()==4.0));
        assertTrue(f.navigationHistory.stream().filter(v -> v.target().equals(source)).allMatch(v -> v.reach()==2.5));
    }

    @Test void observedOutputOnPartialHeightSupportUsesTheVerifiedSurfaceCell() {
        for (double surface:new double[]{63.5,63.9375}) {
            Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false;
            f.machine(PoiKind.PRESERVES_JAR,1,66,true,true,true);
            Pos occupiedSupport=new Pos(1,63,-1), standingCell=occupiedSupport.offset(0,1,0);
            f.unstandable.add(occupiedSupport); f.standingHeights.put(standingCell,surface);
            MachineModule module=new MachineModule(Feature.PRESERVES);
            while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
            f.ground.add(new GroundItem(1,1.5,surface+.01,-.5,preserves(1)));
            for (int n=0;n<10;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
            assertTrue(f.navigationHistory.stream().anyMatch(v -> v.target().equals(standingCell) && v.reach()==.9));
            assertTrue(f.navigationHistory.stream().noneMatch(v -> v.target().equals(occupiedSupport) && v.reach()==.9));
            f.pickup=true; f.advance();
            assertEquals(WorkResult.State.IDLE,f.run(module,10).state()); assertEquals(1,f.machineClicks());
        }
    }

    @Test void unverifiedOrMismatchedUpperSurfaceNeverBecomesAnInventedPickupFloor() {
        for (double surface:new double[]{Double.NaN,64.0,63.2}) {
            Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false; f.profile.interactionTimeoutTicks=15;
            f.machine(PoiKind.PRESERVES_JAR,1,66,true,true,true);
            Pos occupiedSupport=new Pos(1,63,-1), above=occupiedSupport.offset(0,1,0);
            f.unstandable.add(occupiedSupport); f.standingHeights.put(above,surface);
            MachineModule module=new MachineModule(Feature.PRESERVES);
            while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
            f.ground.add(new GroundItem(1,1.5,63.51,-.5,preserves(1)));
            assertEquals(WorkResult.State.BLOCKED,f.run(module,40).state());
            assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25));
            assertEquals(1,f.machineClicks());
        }
    }

    @Test void preexistingWineOfAnotherCohortDoesNotBlockOrRequireGroundPickup() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.wineClockYear=8; f.pickup=false;
        Pos keg=f.machine(PoiKind.WINE_KEG,1,true,true,false);
        GroundItem old=new GroundItem(7,1.5,64.1,-.5,wine(7)); f.ground.add(old);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),40).state());
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed); assertNotNull(f.dropped);
        assertFalse(f.blocks.get(keg).flag("mature")); assertEquals(List.of(old),f.ground);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty()); assertTrue(f.savedOutputs.isEmpty());
        assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25));
    }

    @Test void uncollectedOutputKeepsBlockingAcrossSchedulerPasses() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.PRESERVES_JAR,0,true,true,true); f.pickup = false;
        MachineModule module = new MachineModule(Feature.PRESERVES);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,150).state());
        int actions = f.history.size();
        for (int i = 0; i < 5; i++) {
            f.ticks += 20;
            assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state(),"next-day schedule must not hide a missing product");
        }
        assertEquals(actions,f.history.size()); assertEquals(1,f.machineClicks());
        module.reset(); // Restart is not evidence that the product was recovered.
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertEquals(1,f.profile.pendingMachineOutputs.size());
    }

    @Test void noIngredientsAndProcessingMachinesAreIdle() {
        Fixture f = new Fixture(); f.machine(PoiKind.WINE_KEG,0,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),20).state());
        Fixture processing = new Fixture(); processing.machine(PoiKind.PRESERVES_JAR,0,true,false,false);
        assertEquals(WorkResult.State.IDLE,processing.run(new MachineModule(Feature.PRESERVES),20).state());
        assertEquals(0,processing.machineClicks());
        assertEquals(0,processing.navigationCalls,"a loaded working machine must not cause repeated visits");
    }

    @Test void oneShotIngredientShortagePausesWithoutSchedulingAndCanRetryFromSourcesTheSameDay() {
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) {
            Fixture f=new Fixture(); f.dayTime=13000; f.profile.enabled.put(feature,false);
            PoiKind kind=feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR;
            Pos target=f.machine(kind,10,false,false,false);
            Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
            AutomationEngine engine=isolatedMachineEngine(feature);
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.PAUSED,engine.state());
            assertTrue(engine.status().contains("tomatoes")); assertTrue(engine.status().contains("1 machine(s) remaining"));
            assertTrue(f.profile.nextEligibleDay.isEmpty(),"an unfilled machine must remain eligible today");
            assertEquals(0,f.machineClicks()); assertFalse(f.sleeping); assertEquals(0,f.soldWine);
            int batch=feature==Feature.WINE ? 3 : 5;
            f.chests.get(source)[0]=tomato(2,batch);
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state()); assertEquals(batch,f.consumed);
            assertEquals(1,f.machineClicks()); assertTrue(f.blocks.get(target).flag("working"));
            assertEquals(13000,f.dayTime); assertFalse(f.profile.enabled(feature),"one-shot must not change saved feature toggles");
            assertEquals(0,f.soldWine); assertFalse(f.sleeping);
        }
    }

    @Test void partiallyFinishedOneShotDoesNotCollectOrScheduleTheUnfundedMatureMachine() {
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) {
            Fixture f=new Fixture(); f.dayTime=13000;
            int batch=feature==Feature.WINE ? 3 : 5;
            PoiKind kind=feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR;
            f.inventory[0]=tomato(0,batch);
            f.machine(kind,10,false,false,false); Pos waiting=f.machine(kind,11,true,true,false);
            AutomationEngine engine=isolatedMachineEngine(feature);
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.PAUSED,engine.state());
            assertTrue(engine.status().contains("1 machine(s) remaining"));
            assertEquals(1,f.machineClicks()); assertEquals(batch,f.consumed);
            assertEquals(1,f.profile.nextEligibleDay.size());
            assertTrue(f.blocks.get(waiting).flag("mature"),"output stays safe in its machine until a refill is funded");
            f.inventory[0]=tomato(0,batch);
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state());
            assertEquals(2,f.machineClicks()); assertEquals(2*batch,f.consumed);
            assertEquals(2,f.profile.nextEligibleDay.size()); assertFalse(f.blocks.get(waiting).flag("mature"));
            assertEquals(0,f.soldWine); assertFalse(f.sleeping);
        }
    }

    @Test void oneShotFullInventoryBlocksBeforeCollectionAndRetriesWithoutRunningStorageOrSales() {
        for (Feature feature:new Feature[]{Feature.PRESERVES}) {
            Fixture f=new Fixture(); f.dayTime=13000;
            Arrays.fill(f.inventory,new ItemData("minecraft:cobblestone",64,0,null,false,999));
            int batch=feature==Feature.WINE ? 3 : 5; f.inventory[0]=tomato(0,batch+1);
            Pos target=f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,true,true,false);
            AutomationEngine engine=isolatedMachineEngine(feature);
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.PAUSED,engine.state());
            assertTrue(engine.status().contains("inventory slot"));
            assertEquals(0,f.machineClicks()); assertTrue(f.profile.nextEligibleDay.isEmpty());
            assertTrue(f.blocks.get(target).flag("mature")); assertEquals(0,f.soldWine);
            f.inventory[35]=ItemData.EMPTY;
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state());
            assertEquals(1,f.machineClicks()); assertEquals(batch,f.consumed);
            assertEquals(0,f.soldWine); assertFalse(f.sleeping);
            assertTrue(f.history.stream().noneMatch(Action.QuickMove.class::isInstance),"no storage/shipping fallback is authorized by a production-only run");
        }
    }

    @Test void oneShotMissingCompletedOutputPausesInsteadOfClaimingCompletion() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.pickup=false; f.profile.interactionTimeoutTicks=10;
        f.machine(PoiKind.PRESERVES_JAR,10,true,true,true);
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.PAUSED,engine.state());
        assertTrue(engine.status().contains("not picked up")); assertEquals(1,f.machineClicks());
        assertNotNull(f.dropped); assertEquals(3,f.consumed); assertEquals(0,f.soldWine);
        PendingMachineOutput pending=f.profile.pendingMachineOutputs.values().iterator().next();
        assertEquals(PendingMachineOutput.Phase.AWAITING_PICKUP,pending.phase());
        assertEquals(3L,f.profile.nextEligibleDay.get("preserves:10:64:0"));
        int actions=f.history.size(), visits=f.navigationCalls;
        engine.startOnce(f.context(),Feature.PRESERVES);
        assertEquals(AutomationEngine.State.PAUSED,engine.state(),"future cooldown cannot conceal the outstanding output");
        assertEquals(actions,f.history.size()); assertEquals(visits,f.navigationCalls);
        engine.stop(f.context(),AutomationEngine.State.OFF,"manual takeover");
        f.pickup=true; f.advance();
        assertEquals(1,MachineOutputLedger.reconcile(f.context()),"same-session delayed pickup may settle while OFF");
        assertEquals(AutomationEngine.State.OFF,engine.state());
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,10);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertEquals(actions,f.history.size()); assertEquals(1,f.machineClicks());
    }

    @Test void matureUseIsCheckpointedBeforeImmediateDispatchAndPickupIsCheckpointedSeparately() {
        for (Feature feature:new Feature[]{Feature.PRESERVES}) {
            Fixture f=new Fixture(); f.immediateMachine=true; f.requireSavedBeforeMachine=true;
            f.inventory[0]=tomato(2,feature==Feature.WINE ? 3 : 5);
            f.inventory[2]=feature==Feature.WINE ? wine(f.wineClockYear,7,3) : new ItemData(ItemData.PRESERVES,7,3,null,false,999);
            f.machine(feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,10,true,true,false);
            AutomationEngine engine=isolatedMachineEngine(feature);
            engine.startOnce(f.context(),feature); f.runUntilStopped(engine,100);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
            assertEquals(1,f.machineClicks()); assertEquals(3,f.savedOutputs.size());
            PendingMachineOutput prepared=f.savedOutputs.get(0).values().iterator().next();
            assertEquals(8,prepared.minimumInventoryCount());
            assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,prepared.phase());
            assertEquals(PendingMachineOutput.Phase.AWAITING_PICKUP,f.savedOutputs.get(1).get(prepared.id()).phase());
            assertTrue(f.savedOutputs.get(2).isEmpty()); assertTrue(f.profile.pendingMachineOutputs.isEmpty());
            assertEquals(MachineOutputLedger.Resolution.AUTOMATIC_PICKUP,f.profile.machineOutputResolutions.get(0).resolution());
        }
    }

    @Test void failedPrepareCheckpointDispatchesNoMachineUseAndCanRetrySafely() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.immediateMachine=true;
        Pos keg=f.machine(PoiKind.PRESERVES_JAR,10,true,true,true);
        f.checkpointHook=() -> { throw new IllegalStateException("disk unavailable"); };
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES);
        assertThrows(IllegalStateException.class,() -> f.runUntilStopped(engine,100));
        assertEquals(AutomationEngine.State.ERROR,engine.state());
        assertEquals(0,f.machineClicks()); assertEquals(0,f.consumed); assertTrue(f.blocks.get(keg).flag("mature"));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty()); assertTrue(f.session.liveMachineOutputs.isEmpty());
        assertTrue(f.savedOutputs.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.checkpointHook=() -> { };
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state()); assertEquals(1,f.machineClicks());
    }

    @Test void failedRefillCheckpointKeepsUncertainObligationAndNeverRepeatsTheUse() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.pickup=false;
        f.machine(PoiKind.PRESERVES_JAR,10,true,true,true);
        f.checkpointHook=() -> {
            if (f.profile.pendingMachineOutputs.values().stream().anyMatch(p -> p.phase()==PendingMachineOutput.Phase.AWAITING_PICKUP))
                throw new IllegalStateException("confirmation could not reach disk");
        };
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES);
        assertThrows(IllegalStateException.class,() -> f.runUntilStopped(engine,100));
        assertEquals(AutomationEngine.State.ERROR,engine.state()); assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,f.profile.pendingMachineOutputs.values().iterator().next().phase());
        f.checkpointHook=() -> { }; f.pickup=true; f.advance();
        assertEquals(0,MachineOutputLedger.reconcile(f.context()),"uncertain Use acknowledgement is never inferred from inventory alone");
        engine.startOnce(f.context(),Feature.PRESERVES);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,f.machineClicks());
    }

    @Test void failedPickupCheckpointRetainsObligationUntilSavedAndPreventsTheNextMachine() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,6); f.pickup=false;
        f.machine(PoiKind.PRESERVES_JAR,10,true,true,true); f.machine(PoiKind.PRESERVES_JAR,11,true,true,true);
        MachineModule module=new MachineModule(Feature.PRESERVES);
        PendingMachineOutput pending=f.awaitPickup(module);
        f.pickup=true; f.advance();
        f.checkpointHook=() -> {
            if (f.profile.pendingMachineOutputs.isEmpty()) throw new IllegalStateException("resolution could not reach disk");
        };
        assertThrows(IllegalStateException.class,() -> module.tick(f.context()));
        assertEquals(pending,f.profile.pendingMachineOutputs.get(pending.id()));
        assertTrue(f.profile.machineOutputResolutions.isEmpty()); assertEquals(1,f.machineClicks());
        assertFalse(f.savedOutputs.get(f.savedOutputs.size()-1).isEmpty());
        f.checkpointHook=() -> { };
        assertEquals(WorkResult.State.IDLE,f.run(module,100).state());
        assertEquals(2,f.machineClicks()); assertEquals(2,f.profile.machineOutputResolutions.size());
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }

    @Test void resetAndNewSessionCannotMistakePersistedCountsForAnObservedPickup() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.pickup=false;
        f.machine(PoiKind.PRESERVES_JAR,10,true,true,true);
        MachineModule module=new MachineModule(Feature.PRESERVES);
        PendingMachineOutput pending=f.awaitPickup(module);
        module.reset(); f.cancel();
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        f.inventory[35]=preserves(1);
        Context reconnected=new Context(f,f,f,f.profile,new SessionState(),f::checkpoint);
        assertEquals(0,MachineOutputLedger.reconcile(reconnected));
        assertEquals(pending,f.profile.pendingMachineOutputs.get(pending.id()));
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES); engine.startOnce(reconnected,Feature.PRESERVES);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,f.machineClicks());
        assertEquals(WorkResult.State.BLOCKED,new MachineModule(Feature.PRESERVES).tick(reconnected).state());
    }

    @Test void wineItemsAndDisappearedGroundCannotSatisfyPreservesPickupBaseline() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.inventory[2]=preserves(5); f.pickup=false;
        f.machine(PoiKind.PRESERVES_JAR,10,true,true,true);
        MachineModule module=new MachineModule(Feature.PRESERVES);
        PendingMachineOutput pending=f.awaitPickup(module);
        assertEquals(6,pending.minimumInventoryCount()); assertNull(pending.expectedWineYear());
        f.ground.add(new GroundItem(1,10.5,64,-.5,preserves(1))); f.ground.clear();
        for (ItemData unrelated:new ItemData[]{wine(7,64,0),wine((Integer)null)}) {
            f.inventory[35]=unrelated;
            assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
            assertEquals(pending,f.profile.pendingMachineOutputs.get(pending.id())); assertEquals(1,f.machineClicks());
        }
        f.inventory[35]=ItemData.EMPTY; f.pickup=true; f.advance();
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state()); assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }

    @Test void cancellationBeforeMachineAcknowledgementRequiresReviewEvenIfOutputLaterAppears() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.pickup=false;
        f.machine(PoiKind.PRESERVES_JAR,10,true,true,true);
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES); engine.startOnce(f.context(),Feature.PRESERVES);
        for (int n=0;n<100 && f.machineClicks()==0;n++) { engine.tick(f.context()); if (f.machineClicks()==0) f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(0,f.consumed);
        PendingMachineOutput pending=f.profile.pendingMachineOutputs.values().iterator().next();
        assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,pending.phase());
        engine.stop(f.context(),AutomationEngine.State.OFF,"cancelled before ACK");
        f.inventory[35]=preserves(1); f.advance();
        assertEquals(0,MachineOutputLedger.reconcile(f.context()));
        engine.startOnce(f.context(),Feature.PRESERVES);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,f.machineClicks()); assertEquals(0,f.consumed);
        assertEquals(pending,f.profile.pendingMachineOutputs.get(pending.id()));
    }

    @Test void nativeWineClockMayBeUnknownWithoutBlockingMatureMachineDispatch() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.wineClockYear=null;
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),100).state());
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed); assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }

    private static AutomationEngine isolatedMachineEngine(Feature selected) {
        List<AutomationModule> modules=new ArrayList<>(); modules.add(new MachineModule(selected));
        for (Feature other:Feature.values()) if (other!=selected) modules.add(new AutomationModule() {
            @Override public Feature feature() { return other; }
            @Override public int priority() { return 0; }
            @Override public WorkResult tick(Context context) { fail("One-shot "+selected+" invoked another feature: "+other); return WorkResult.idle(); }
            @Override public void reset() { }
        });
        return new AutomationEngine(modules);
    }

    @Test void resourcesRetryNextDayAndProductionScheduleSurvivesReset() {
        Fixture f = new Fixture(); Pos keg = f.machine(PoiKind.WINE_KEG,0,false,false,false);
        MachineModule module = new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.IDLE,f.run(module,20).state());
        int visits = f.navigationCalls;
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(visits,f.navigationCalls);
        f.inventory[0] = tomato(0,9); f.dayTime = 24000;
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(1,f.machineClicks());
        assertEquals(7L,f.profile.nextEligibleDay.get("wine:0:64:0"));
        visits = f.navigationCalls;
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(visits,f.navigationCalls);
        f.blocks.put(keg,new BlockData(keg,"society:wine_keg",Map.of("working","true","mature","true","upgraded","false","facing","north")));
        module.reset();
        for (int day = 2; day < 7; day++) {
            f.dayTime = day*24000L;
            assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
            assertEquals(visits,f.navigationCalls,"no travel in intervening production days");
        }
        f.dayTime = 7*24000L;
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(2,f.machineClicks(),"mature target is serviced on its scheduled game day");
        assertEquals(13L,f.profile.nextEligibleDay.get("wine:0:64:0"));
    }

    @Test void dueButStillWorkingReschedulesOneDayWithoutVisiting() {
        Fixture f = new Fixture(); f.machine(PoiKind.WINE_KEG,0,true,false,false);
        f.profile.nextEligibleDay.put("wine:0:64:0",6L); f.dayTime = 6*24000L+220;
        assertEquals(WorkResult.State.IDLE,new MachineModule(Feature.WINE).tick(f.context()).state());
        assertEquals(7L,f.profile.nextEligibleDay.get("wine:0:64:0"));
        assertEquals(0,f.navigationCalls);
    }

    @Test void dueMachineWaitsForMorningProcessingWithoutSkippingItsDueDay() {
        Fixture f = new Fixture(); Pos keg=f.machine(PoiKind.WINE_KEG,0,true,false,false);
        f.profile.nextEligibleDay.put("wine:0:64:0",6L); f.dayTime=6*24000L;
        MachineModule module=new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(6L,f.profile.nextEligibleDay.get("wine:0:64:0")); assertEquals(0,f.navigationCalls);
        f.dayTime+=220;
        f.blocks.put(keg,new BlockData(keg,"society:wine_keg",Map.of("working","true","mature","true","upgraded","false","facing","north")));
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void matureMachineCanCollectWithEmptyHandWithoutIngredients() {
        Fixture f = new Fixture(); f.machine(PoiKind.WINE_KEG,0,true,true,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),40).state());
        assertEquals(0,f.consumed); assertEquals(1,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
    }

    @Test void preservesRecipeUsesFiveOrThreeWhenUpgraded() {
        for (boolean upgrade : new boolean[]{false,true}) {
            Fixture f = new Fixture(); f.inventory[0] = tomato(0,10);
            f.machine(PoiKind.PRESERVES_JAR,0,false,false,upgrade);
            assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.PRESERVES),40).state());
            assertEquals(upgrade ? 3 : 5,f.consumed);
        }
    }

    @Test void recordedScale144NormalJarsCollects144OutputsConsumes720TomatoesAndSchedulesThreeDays() {
        Fixture f=new Fixture(); f.dayTime=10*24000L+1000; f.equipHoe();
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        ItemData[] supplies=new ItemData[12];
        Arrays.fill(supplies,tomato(2,64)); supplies[11]=tomato(2,16); f.chests.put(source,supplies);
        for (int i=0;i<144;i++) f.machine(PoiKind.PRESERVES_JAR,10+i,false,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,5000);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),() -> engine.status()+"; "+f.tomatoFragments());
        assertEquals(144,f.machineClicks()); assertEquals(720,f.consumed);
        assertEquals(144,ModuleSupport.count(f.context(),i -> i.is(ItemData.PRESERVES)));
        assertEquals(0,ModuleSupport.count(f.context(),i -> i.is(ItemData.TOMATO)));
        assertTrue(Arrays.stream(f.chests.get(source)).allMatch(ItemData::empty));
        assertTrue(f.usedGrades.stream().allMatch(grade -> grade==2));
        assertEquals(144,f.profile.nextEligibleDay.size());
        assertTrue(f.profile.nextEligibleDay.values().stream().allMatch(day -> day==13L));
        assertEquals(720,f.tomatoesAtMachineUse.get(0)); assertEquals(12,f.tomatoWithdrawals);
        assertTrue(f.opens.get(source)<=4,"144 jars must not require twelve separate ingredient hauls");
        int actions=f.history.size(), visits=f.navigationCalls;
        for (int day=11;day<13;day++) {
            f.dayTime=day*24000L+1000; engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,5);
            assertEquals(AutomationEngine.State.COMPLETE,engine.state());
            assertEquals(actions,f.history.size()); assertEquals(visits,f.navigationCalls);
        }
    }

    @Test void cancellationProducesNoCleanupClickAndRestartObservesState() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.WINE_KEG,0,false,false,false);
        MachineModule module = new MachineModule(Feature.WINE);
        while (f.machineClicks() == 0) { module.tick(f.context()); f.advance(); }
        int actions = f.history.size();
        module.reset(); f.cancel();
        assertEquals(actions,f.history.size());
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void sleepRequiresThresholdActualSleepingAndNextDay() {
        Fixture f = new Fixture(); f.poi(PoiKind.BED,0,null); f.dayTime = 12583;
        SleepModule module = new SleepModule();
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state()); assertTrue(f.history.isEmpty());
        f.dayTime = 12584;
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        f.dayTime = 24000; f.sleeping = false;
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(1,f.history.size());
    }

    @Test void rejectedSleepHasThreeAttemptLimit() {
        Fixture f = new Fixture(); f.poi(PoiKind.BED,0,null); f.dayTime = 13000; f.rejectSleep = true;
        WorkResult result = f.run(new SleepModule(),500);
        assertEquals(WorkResult.State.BLOCKED,result.state());
        assertEquals(3,f.history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose() == Action.Use.SLEEP).count());
    }

    /** Normally applies server changes on advance(); an explicit immediate mode tests write-ahead ordering. */
    private static final class Fixture implements WorldAccess, ActionPort, Navigation {
        private record NavVisit(Pos target,double reach) { }
        final Profile profile = new Profile();
        final SessionState session = new SessionState();
        final ItemData[] inventory = new ItemData[36];
        final Map<Pos,ItemData[]> chests = new HashMap<>();
        final Map<Pos,BlockData> blocks = new HashMap<>();
        final Map<Pos,Integer> opens = new HashMap<>();
        final List<Action> history = new ArrayList<>();
        final List<Map<String,PendingMachineOutput>> savedOutputs=new ArrayList<>();
        final List<Integer> usedGrades = new ArrayList<>();
        final List<Pos> tomatoWithdrawalSources = new ArrayList<>();
        final List<Integer> tomatoesAtMachineUse=new ArrayList<>(), emptySlotsAfterWithdraw=new ArrayList<>();
        final List<Integer> handCountsAtMachineUse=new ArrayList<>();
        final List<NavVisit> navigationHistory=new ArrayList<>();
        final Map<Pos,Double> minimumReaches=new HashMap<>();
        final Map<Pos,Double> standingHeights=new HashMap<>();
        final List<GroundItem> ground=new ArrayList<>();
        final Deque<ItemData> tomatoRefills=new ArrayDeque<>();
        final Set<Pos> unloaded=new HashSet<>(), blockedPaths=new HashSet<>(), unstandable=new HashSet<>();
        Integer wineClockYear=20;
        long ticks, dayTime, sequence;
        double playerX=.5;
        int selected, consumed, navigationCalls, soldWine, withdrawnWine, tomatoWithdrawals;
        Pos open, emptyOnSecondOpen;
        boolean sleeping, rejectSleep, pickup = true;
        boolean reportConfirmedCount;
        boolean immediateMachine, requireSavedBeforeMachine, separateFreshWineSlots;
        boolean consolidationNoProgress, consolidationFails;
        Runnable checkpointHook=() -> { };
        ItemData dropped;
        ItemData refillAfterWineSale;
        ItemSlot extraMenuSlot;
        Action action;
        ActionOutcome outcome = new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture() { Arrays.fill(inventory,ItemData.EMPTY); }
        void equipHoe() { inventory[profile.hoeHotbarSlot]=new ItemData("minecraft:golden_hoe",1,0,null,true,999); }
        String tomatoFragments() { return inventory().stream().filter(s -> s.item().is(ItemData.TOMATO))
            .map(s -> s.inventoryIndex()+"="+s.item().count()).reduce("tomatoes",(a,b) -> a+" "+b); }
        Context context() { return new Context(this,this,this,profile,session,this::checkpoint); }
        void checkpoint() { checkpointHook.run(); savedOutputs.add(Map.copyOf(profile.pendingMachineOutputs)); }
        Pos poi(PoiKind kind,int x,Integer group) { Pos p = new Pos(x,64,0); profile.pois.add(new Poi(p,kind,"test",group)); return p; }
        Pos chest(PoiKind kind,int x,Integer group,ItemData initial) { Pos p = poi(kind,x,group); chests.put(p,new ItemData[]{initial,ItemData.EMPTY}); return p; }
        Pos fullWineReserve(int x,int birth,int size) {
            Pos p=chest(PoiKind.WINE_CHEST,x,birth,ItemData.EMPTY); ItemData[] slots=new ItemData[size];
            for (int i=0;i<size;i++) slots[i]=wine(birth,64,i%4);
            chests.put(p,slots); return p;
        }
        Pos machine(PoiKind kind,int x,boolean working,boolean mature,boolean upgraded) {
            return machine(kind,x,64,working,mature,upgraded);
        }
        Pos machine(PoiKind kind,int x,int y,boolean working,boolean mature,boolean upgraded) {
            Pos p = new Pos(x,y,0); profile.pois.add(new Poi(p,kind,"test",null));
            blocks.put(p,new BlockData(p,kind == PoiKind.WINE_KEG ? "society:wine_keg" : "society:preserves_jar",
                Map.of("working",""+working,"mature",""+mature,"upgraded",""+upgraded,"facing","north")));
            return p;
        }
        WorkResult run(AutomationModule module,int limit) {
            WorkResult result = null;
            for (int i = 0; i < limit; i++) { result = module.tick(context()); if (result.state() != WorkResult.State.BUSY) return result; advance(); }
            fail("Module did not finish within " + limit + " ticks: " + result); return result;
        }
        void runUntilStopped(AutomationEngine engine,int limit) {
            for (int i=0;i<limit && engine.running();i++) { engine.tick(context()); advance(); }
            assertFalse(engine.running(),"One-shot did not stop: "+engine.status());
        }
        PendingMachineOutput awaitPickup(AutomationModule module) {
            for (int n=0;n<100;n++) {
                assertEquals(WorkResult.State.BUSY,module.tick(context()).state()); advance();
                PendingMachineOutput output=profile.pendingMachineOutputs.values().stream().findFirst().orElse(null);
                if (output!=null && output.phase()==PendingMachineOutput.Phase.AWAITING_PICKUP) return output;
            }
            throw new AssertionError("Machine never reached the pickup phase");
        }
        int machineClicks() { return (int)history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose() == Action.Use.MACHINE).count(); }
        void advance() {
            ticks++;
            if (action != null) {
                Action next = action; action = null;
                outcome = new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
                if (next instanceof Action.UseBlock use) {
                    if (use.purpose() == Action.Use.OPEN_CONTAINER) {
                        open = use.pos(); opens.merge(open,1,Integer::sum);
                        if (open.equals(emptyOnSecondOpen) && opens.get(open) == 2) Arrays.fill(chests.get(open),ItemData.EMPTY);
                    } else if (use.purpose() == Action.Use.MACHINE) {
                        applyMachine(use);
                    } else if (use.purpose() == Action.Use.SLEEP) {
                        if (rejectSleep) outcome = new ActionOutcome(ActionOutcome.State.FAILED,"server rejected sleep");
                        else sleeping = true;
                    }
                } else if (next instanceof Action.CloseContainer) open = null;
                else if (next instanceof Action.ConsolidateInventory merge) applyConsolidation(merge.plan());
                else if (next instanceof Action.SelectHotbar select) selected = select.slot();
                else if (next instanceof Action.SwapHotbar swap) { ItemData prior = inventory[swap.hotbarSlot()]; inventory[swap.hotbarSlot()] = inventory[swap.inventoryIndex()]; inventory[swap.inventoryIndex()] = prior; }
                else if (next instanceof Action.ThrowRotten drop) {
                    ItemSlot slot = menu().slot(drop.slot());
                    if (slot != null && slot.item().is(ItemData.ROTTEN)) inventory[slot.inventoryIndex()] = ItemData.EMPTY;
                    else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"not rotten");
                } else if (next instanceof Action.QuickMove move) {
                    if (open == null || move.containerId() != menu().id()) outcome = new ActionOutcome(ActionOutcome.State.FAILED,"menu changed");
                    else {
                        ItemSlot slot = menu().slot(move.slot());
                        if (slot.player()) {
                            if (add(chests.get(open),slot.item())) {
                                inventory[slot.inventoryIndex()] = ItemData.EMPTY;
                                if (reportConfirmedCount) outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"confirmed destination transfer",slot.item().count());
                                if (slot.item().is(ItemData.TOMATO) && !tomatoRefills.isEmpty()) inventory[slot.inventoryIndex()]=tomatoRefills.removeFirst();
                                boolean wineSale=slot.item().is(ItemData.WINE) && profile.pois.stream().anyMatch(p -> p.pos().equals(open) && p.kind()==PoiKind.SHIPPING_BIN);
                                if (wineSale) {
                                    soldWine+=slot.item().count();
                                    session.wineSalePermits.computeIfPresent(slot.item().year(),(year,permit) -> permit.consumed(slot.item().count()));
                                    if (refillAfterWineSale!=null) inventory[slot.inventoryIndex()]=refillAfterWineSale;
                                }
                            }
                            else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"full chest");
                        } else if (add(inventory,slot.item())) {
                            chests.get(open)[slot.index()] = ItemData.EMPTY;
                            if (slot.item().is(ItemData.WINE)) withdrawnWine+=slot.item().count();
                            if (slot.item().is(ItemData.TOMATO)) {
                                tomatoWithdrawals++;
                                tomatoWithdrawalSources.add(open);
                                emptySlotsAfterWithdraw.add((int)Arrays.stream(inventory).filter(ItemData::empty).count());
                            }
                        }
                        else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"full inventory");
                    }
                }
            }
            pickupDropped();
        }
        private void pickupDropped() {
            if (!pickup || dropped==null) return;
            if (separateFreshWineSlots && dropped.is(ItemData.WINE)) {
                for (int n=0;n<inventory.length;n++) if (inventory[n].empty()) { inventory[n]=dropped; dropped=null; return; }
            } else if (add(inventory,dropped)) dropped=null;
        }
        private void applyConsolidation(ProductionMergePlanner.Plan plan) {
            assertTrue(profile.pendingMachineOutputs.isEmpty(),"no consumer may rearrange output before durable pickup reconciliation");
            assertTrue(ProductionMergePlanner.matchesSnapshot(plan,inventory()));
            if (consolidationFails) { outcome=new ActionOutcome(ActionOutcome.State.FAILED,"native consolidation interrupted"); return; }
            if (plan.reposition()) {
                int destination=plan.destinations().get(0); assertTrue(inventory[destination].empty());
                inventory[destination]=inventory[plan.sourceIndex()]; inventory[plan.sourceIndex()]=ItemData.EMPTY;
                outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"hotbar ingredient moved into main inventory",0); return;
            }
            if (consolidationNoProgress) { outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native tags were incompatible",0); return; }
            // Net effect of the separately tested native SWAP/QUICK_MOVE/restore protocol.
            // This fake assumes compatible native tags; the no-progress mode models rejection.
            ItemData item=inventory[plan.sourceIndex()]; int left=item.count();
            for (int destination:plan.destinations()) {
                ItemData before=inventory[destination]; assertTrue(ModuleSupport.same(before,item));
                int transferred=Math.min(left,64-before.count());
                inventory[destination]=new ItemData(before.id(),before.count()+transferred,before.quality(),before.year(),before.hoe(),before.durability());
                left-=transferred;
            }
            assertEquals(0,left,"the whole source fits before a composite is dispatched");
            inventory[plan.sourceIndex()]=ItemData.EMPTY;
            outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"confirmed inventory consolidation",1);
        }
        private void applyMachine(Action.UseBlock use) {
            tomatoesAtMachineUse.add(ModuleSupport.count(context(),i -> i.is(ItemData.TOMATO)));
            handCountsAtMachineUse.add(inventory[selected].count());
            BlockData block=blocks.get(use.pos());
            boolean wineMachine=block.id().equals("society:wine_keg");
            int batch=wineMachine || block.flag("upgraded") ? 3 : 5;
            if (block.flag("mature")) dropped=wineMachine ? wine(wineClockYear) : new ItemData(ItemData.PRESERVES,1,0,null,false,99);
            boolean feed=inventory[selected].is(ItemData.TOMATO) && inventory[selected].count()>=batch;
            if (feed) { consumed+=batch; usedGrades.add(inventory[selected].quality()); inventory[selected]=tomato(inventory[selected].quality(),inventory[selected].count()-batch); }
            blocks.put(use.pos(),new BlockData(block.pos(),block.id(),Map.of("working",""+feed,"mature","false","upgraded",""+block.flag("upgraded"),"facing","north")));
        }
        private static boolean add(ItemData[] slots,ItemData item) {
            int capacity=0;
            for (ItemData slot:slots) if (slot.empty()) capacity+=64;
                else if (ModuleSupport.same(slot,item)) capacity+=64-slot.count();
            if (capacity<item.count()) return false;
            int remaining=item.count();
            // Vanilla first tops up compatible stacks, even when the whole source stack
            // does not fit there, then places the remainder in an empty slot.
            for (int pass=0;pass<2 && remaining>0;pass++) for (int i=0;i<slots.length && remaining>0;i++) {
                boolean empty=slots[i].empty();
                if (pass==0 && (empty || !ModuleSupport.same(slots[i],item)) || pass==1 && !empty) continue;
                int previous=empty ? 0 : slots[i].count(), moved=Math.min(remaining,64-previous);
                if (moved==0) continue;
                slots[i]=new ItemData(item.id(),previous+moved,item.quality(),item.year(),item.hoe(),item.durability());
                remaining-=moved;
            }
            return remaining==0;
        }
        @Override public long tick() { return ticks; }
        @Override public long dayTime() { return dayTime; }
        @Override public Integer wineYear() { return wineClockYear; }
        @Override public List<GroundItem> groundItems() { return List.copyOf(ground); }
        @Override public PlayerState player() { return new PlayerState(playerX,64,.5,0,0,true,sleeping,20,20,selected,true,true); }
        @Override public BlockData block(Pos pos) { return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of())); }
        @Override public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        @Override public boolean canStand(Pos feet) { return !unstandable.contains(feet); }
        @Override public double standingY(Pos feet) { return standingHeights.getOrDefault(feet,Double.NaN); }
        @Override public boolean canTraverse(Pos from,Pos to) { return true; }
        @Override public boolean canInteract(Pos pos,double reach) { return !blockedPaths.contains(pos) && reach>=minimumReaches.getOrDefault(pos,0.0); }
        @Override public List<BlockData> scan(Pos center,int horizontalRadius,int verticalRadius) { return List.of(); }
        @Override public List<ItemSlot> inventory() { List<ItemSlot> list = new ArrayList<>(); for (int i = 0; i < 36; i++) list.add(new ItemSlot(i,i,true,inventory[i])); return list; }
        @Override public MenuData menu() {
            List<ItemSlot> slots = new ArrayList<>(); int offset = 0;
            if (extraMenuSlot != null) slots.add(extraMenuSlot);
            if (open != null) { ItemData[] chest = chests.get(open); for (int i = 0; i < chest.length; i++) slots.add(new ItemSlot(i,-1,false,chest[i])); offset = chest.length; }
            for (int i = 0; i < 36; i++) slots.add(new ItemSlot(offset+i,i,true,inventory[i]));
            return new MenuData(open == null ? 0 : open.x()+10,(int)ticks,slots,ItemData.EMPTY,open != null);
        }
        @Override public boolean mayPlace(int menuSlot,ItemData item) { return open != null && menuSlot < chests.get(open).length; }
        @Override public boolean busy() { return action != null; }
        @Override public long submit(Action next) {
            assertNull(action,"one action in flight");
            boolean machineUse=next instanceof Action.UseBlock use && use.purpose()==Action.Use.MACHINE;
            if (machineUse && requireSavedBeforeMachine && blocks.get(((Action.UseBlock)next).pos()).flag("mature")) {
                assertFalse(savedOutputs.isEmpty(),"the checkpoint must finish before submit can dispatch");
                assertEquals(profile.pendingMachineOutputs,savedOutputs.get(savedOutputs.size()-1));
                assertEquals(1,profile.pendingMachineOutputs.size());
                assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,profile.pendingMachineOutputs.values().iterator().next().phase());
            }
            history.add(next);
            if (machineUse && immediateMachine) {
                applyMachine((Action.UseBlock)next);
                pickupDropped();
                outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
            } else { action=next; outcome=new ActionOutcome(ActionOutcome.State.PENDING,""); }
            return ++sequence;
        }
        @Override public ActionOutcome outcome(long ticket) { return outcome; }
        @Override public void move(Movement movement) { }
        @Override public void stopMovement() { }
        @Override public void cancel() { action = null; outcome = new ActionOutcome(ActionOutcome.State.CANCELLED,""); }
        @Override public Navigation.Result moveTo(Pos target,double reach,Context context) {
            navigationCalls++; navigationHistory.add(new NavVisit(target,reach));
            return blockedPaths.contains(target) || reach<minimumReaches.getOrDefault(target,0.0) ? Navigation.Result.BLOCKED : Navigation.Result.ARRIVED;
        }
        @Override public void reset() { }
    }
}
