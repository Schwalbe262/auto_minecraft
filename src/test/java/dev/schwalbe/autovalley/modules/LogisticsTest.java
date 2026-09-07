package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsTest {
    private static ItemData tomato(int grade, int count) { return new ItemData(ItemData.TOMATO,count,grade,null,false,999); }
    private static ItemData wine(Integer year) { return new ItemData(ItemData.WINE,1,0,year,false,999); }
    private static ItemData wine(int year,int count,int grade) { return new ItemData(ItemData.WINE,count,grade,year,false,999); }

    @Test void magnetRefillDrainsOnlyAcknowledgedStoredQuantityAndNeverCountsTheSameAckTwice() {
        for (boolean confirmedCount:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.inventory[0]=tomato(0,64); f.tomatoRefills.add(tomato(0,64)); f.reportConfirmedCount=confirmedCount;
            f.session.magnetHaulPending=true; f.session.magnetHaulRemaining.put(ItemData.TOMATO,128);
            Pos chest=f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); TomatoStorageModule module=new TomatoStorageModule();
            while (f.history.stream().noneMatch(Action.QuickMove.class::isInstance)) { module.tick(f.context()); f.advance(); }
            assertEquals(64,f.inventory[0].count(),"magnet instantly replaced the deposited stack");
            assertEquals(128,f.session.magnetHaulRemaining.get(ItemData.TOMATO),"sending/receiving a packet alone does not settle the ledger");
            module.tick(f.context());
            assertEquals(64,f.session.magnetHaulRemaining.get(ItemData.TOMATO)); assertTrue(f.session.magnetHaulPending);
            module.tick(f.context());
            assertEquals(64,f.session.magnetHaulRemaining.get(ItemData.TOMATO),"polling pending next transfer cannot replay previous ACK");
            f.advance(); assertEquals(WorkResult.State.IDLE,f.run(module,30).state());
            assertFalse(f.session.magnetHaulPending); assertTrue(f.session.magnetHaulRemaining.isEmpty());
            assertEquals(128,Arrays.stream(f.chests.get(chest)).mapToInt(ItemData::count).sum());
        }
    }

    @Test void disappearedGroundItemsNeverClearTheLedgerOrAllowProductionAndSleep() {
        Fixture f=new Fixture(); f.dayTime=13000; f.inventory[0]=tomato(0,3);
        f.session.magnetHaulPending=true; f.session.magnetHaulRemaining.put(ItemData.TOMATO,64);
        f.ground.add(new GroundItem(1,.5,64,.5,tomato(0,61)));
        f.machine(PoiKind.WINE_KEG,0,false,false,false); f.machine(PoiKind.PRESERVES_JAR,1,false,false,false); f.poi(PoiKind.BED,2,null);
        AutomationEngine engine=new AutomationEngine(List.of(new MachineModule(Feature.WINE),new MachineModule(Feature.PRESERVES),new SleepModule()));
        engine.start(f.context()); engine.tick(f.context()); f.ground.clear();
        for (int i=0;i<200;i++) { f.advance(); engine.tick(f.context()); }
        assertTrue(f.session.magnetHaulPending); assertEquals(64,f.session.magnetHaulRemaining.get(ItemData.TOMATO));
        assertEquals(0,f.machineClicks()); assertFalse(f.sleeping); assertTrue(f.history.isEmpty());
    }

    @Test void realStorageMustFinishTheMagnetLedgerBeforeProductionAndSleepStart() {
        Fixture f=new Fixture(); f.dayTime=13000; f.inventory[0]=tomato(0,64); f.tomatoRefills.add(tomato(0,64)); f.reportConfirmedCount=true;
        f.session.magnetHaulPending=true; f.session.magnetHaulRemaining.put(ItemData.TOMATO,128);
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); f.machine(PoiKind.WINE_KEG,1,false,false,false); f.poi(PoiKind.BED,2,null);
        AutomationEngine engine=new AutomationEngine(List.of(new TomatoStorageModule(),new MachineModule(Feature.WINE),new SleepModule()));
        engine.start(f.context());
        for (int i=0;i<250 && !f.sleeping;i++) {
            engine.tick(f.context());
            if (f.session.magnetHaulPending) { assertEquals(0,f.machineClicks()); assertFalse(f.sleeping); }
            f.advance();
        }
        assertTrue(f.session.magnetHaulRemaining.isEmpty()); assertFalse(f.session.magnetHaulPending);
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed); assertTrue(f.sleeping);
    }

    @Test void failedStorageAcknowledgementNeverSettlesTheMagnetLedger() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,64); f.session.magnetHaulPending=true;
        f.session.magnetHaulRemaining.put(ItemData.TOMATO,64); f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY);
        TomatoStorageModule module=new TomatoStorageModule();
        while (!(f.action instanceof Action.QuickMove)) { module.tick(f.context()); if (!(f.action instanceof Action.QuickMove)) f.advance(); }
        f.cancel();
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertEquals(64,f.session.magnetHaulRemaining.get(ItemData.TOMATO)); assertTrue(f.session.magnetHaulPending);
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
        assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),100).state());
        assertEquals(0,f.soldWine); assertEquals(0,f.withdrawnWine); assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void missingOrConflictingWineReserveContentsNeverAuthorizeSurplus() {
        for (ItemData conflict:new ItemData[]{ItemData.EMPTY,wine(9,64,0),tomato(0,64)}) {
            Fixture f=new Fixture(); f.inventory[0]=wine(8,5,0); Pos barrel=f.fullWineReserve(0,8,27);
            f.chests.get(barrel)[3]=conflict; f.chest(PoiKind.SHIPPING_BIN,1,null,ItemData.EMPTY);
            assertEquals(WorkResult.State.BLOCKED,f.run(new WineSurplusShippingModule(),60).state());
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
        assertEquals(WorkResult.State.BLOCKED,f.run(module,60).state()); assertEquals(0,f.soldWine);
    }

    @Test void destinationAcknowledgementConsumesAllowanceDespiteInstantMagnetRefill() {
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

    @Test void largeFarmServices397KegsBefore144JarsWithoutCountingEveryChestForEveryMachine() {
        Fixture f=new Fixture(); f.dayTime=1000;
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
        assertEquals(541,uses.size()); assertEquals(397*3+144*5,f.consumed);
        assertTrue(uses.subList(0,397).stream().allMatch(u -> kegs.contains(u.pos())),"all due wine batches have priority over jars");
        assertTrue(f.usedGrades.stream().allMatch(g -> g==2),"the largest total stock is selected throughout this workload");
        int sourceOpens=f.opens.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(sourceOpens<=200,"source visits must scale with ingredient hauls, not541machines; actual="+sourceOpens);
        assertEquals(541,f.profile.nextEligibleDay.size());
    }

    @Test void cachedChestCountsStillSwitchGradeWhenLiveInventoryTotalsCrossOver() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,6); f.inventory[1]=tomato(2,7);
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); f.chest(PoiKind.TOMATO_CHEST,1,2,ItemData.EMPTY);
        for (int i=0;i<3;i++) f.machine(PoiKind.WINE_KEG,10+i,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),150).state());
        assertEquals(List.of(2,0,2),f.usedGrades);
        assertEquals(2,f.opens.values().stream().mapToInt(Integer::intValue).sum(),"held ingredients need no repeat chest count");
    }

    @Test void cancellationDiscardsChestCacheBeforeTheNextMachine() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9);
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); Pos richer=f.chest(PoiKind.TOMATO_CHEST,1,2,ItemData.EMPTY);
        f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
        MachineModule module=new MachineModule(Feature.WINE);
        while (f.machineClicks()==0) { module.tick(f.context()); f.advance(); }
        module.reset(); f.cancel(); f.chests.get(richer)[0]=tomato(2,30);
        assertEquals(WorkResult.State.IDLE,f.run(module,100).state());
        assertEquals(List.of(0,2),f.usedGrades);
        assertTrue(f.opens.get(richer)>=3,"resume recounts all sources and reopens the selected source before withdrawal");
    }

    @Test void dayChangeOrBoundedRefreshRecountsExternalChestChangesDespiteHeldIngredients() {
        for (boolean nextDay:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.dayTime=1000; f.inventory[0]=tomato(0,9);
            f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY); Pos richer=f.chest(PoiKind.TOMATO_CHEST,1,2,ItemData.EMPTY);
            f.machine(PoiKind.WINE_KEG,10,false,false,false); f.machine(PoiKind.WINE_KEG,11,false,false,false);
            MachineModule module=new MachineModule(Feature.WINE);
            while (!f.profile.nextEligibleDay.containsKey("wine:10:64:0")) { module.tick(f.context()); f.advance(); }
            f.chests.get(richer)[0]=tomato(2,30);
            if (nextDay) f.dayTime+=24000; else f.ticks+=1201;
            assertEquals(WorkResult.State.IDLE,f.run(module,100).state());
            assertEquals(List.of(0,2),f.usedGrades,"newly larger stock must win after day/refresh boundary");
            assertTrue(f.opens.get(richer)>=3);
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

    @Test void tomatoStorageDepositsOnlyItsRegisteredGrade() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(2,15); f.inventory[1] = tomato(1,9);
        Pos first = f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        Pos second = f.chest(PoiKind.TOMATO_CHEST,1,1,ItemData.EMPTY);
        WorkResult result = f.run(new TomatoStorageModule(),80);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(15,f.chests.get(first)[0].count()); assertEquals(2,f.chests.get(first)[0].quality());
        assertEquals(9,f.chests.get(second)[0].count()); assertEquals(1,f.chests.get(second)[0].quality());
    }

    @Test void storageBlocksConflictingChestAndNeverMovesItem() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(2,15);
        f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(1,3));
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

    @Test void shippingMovesPreservesOnly() {
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

    @Test void productionCountsChestAndInventoryThenReopensBeforeWithdrawal() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,10); f.inventory[8] = new ItemData("minecraft:diamond_hoe",1,0,null,true,100);
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
        f.inventory[0] = tomato(0,6);
        Pos selected = f.chest(PoiKind.TOMATO_CHEST,1,2,tomato(2,30));
        f.machine(PoiKind.WINE_KEG,2,false,false,false);
        f.emptyOnSecondOpen = selected;
        WorkResult result = f.run(new MachineModule(Feature.WINE),120);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(List.of(0),f.usedGrades);
        assertFalse(f.history.stream().anyMatch(Action.QuickMove.class::isInstance));
    }

    @Test void readyMachineCollectsAndRefillsOnceThenWaitsForPickup() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.WINE_KEG,0,true,true,false);
        f.pickup = false;
        MachineModule module = new MachineModule(Feature.WINE);
        for (int i = 0; i < 30; i++) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(0,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
        f.pickup = true; f.advance();
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void elevatedOutputWaitsForObservedFallAndTemporaryPathFailureWithoutRepeatingUse() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false;
        Pos keg=f.machine(PoiKind.WINE_KEG,1,66,true,true,false);
        f.minimumReaches.put(keg,4.0);
        Pos high=keg.offset(0,0,-1), landed=new Pos(1,64,-1);
        f.unstandable.add(high); f.blockedPaths.add(high);
        MachineModule module=new MachineModule(Feature.WINE);
        while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        f.ground.add(new GroundItem(1,1.5,66.2,-.5,wine(f.wineClockYear)));
        // Unrelated nearby products must not become a guessed floor target for the wine.
        f.ground.add(new GroundItem(2,1.5,64.1,-.5,new ItemData(ItemData.PRESERVES,1,0,null,false,999)));
        for (int n=0;n<12;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25),"no path to the machine-height output cell");
        f.ground.set(0,new GroundItem(1,1.5,64.1,-.5,wine(f.wineClockYear))); f.blockedPaths.add(landed);
        for (int n=0;n<3;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        assertTrue(f.navigationHistory.stream().anyMatch(v -> v.target().equals(landed) && v.reach()==.9));
        f.blockedPaths.remove(landed);
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        f.pickup=true; f.advance();
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(1,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
        assertTrue(f.navigationHistory.stream().filter(v -> v.target().equals(keg)).allMatch(v -> v.reach()==4.0));
    }

    @Test void magnetCanCollectElevatedOutputWithoutAnyGroundLevelNavigation() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false;
        Pos keg=f.machine(PoiKind.WINE_KEG,1,66,true,true,false);
        f.minimumReaches.put(keg,4.0); f.unstandable.add(keg.offset(0,0,-1));
        MachineModule module=new MachineModule(Feature.WINE);
        while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        f.ground.add(new GroundItem(1,1.5,66.2,-.5,wine(f.wineClockYear)));
        for (int n=0;n<10;n++) { assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance(); }
        f.pickup=true; f.advance(); // Inventory acknowledgement, with no observed landing, is sufficient.
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state()); assertEquals(1,f.machineClicks());
        assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25));
    }

    @Test void elevatedOutputThatNeverArrivesTimesOutAndLatchesAfterExactlyOneUse() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.pickup=false; f.profile.interactionTimeoutTicks=12;
        Pos keg=f.machine(PoiKind.WINE_KEG,1,66,true,true,false);
        f.unstandable.add(keg.offset(0,0,-1)); f.blockedPaths.add(keg.offset(0,0,-1));
        MachineModule module=new MachineModule(Feature.WINE);
        while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        long collectedAt=f.ticks;
        f.ground.add(new GroundItem(1,1.5,66.2,-.5,wine(f.wineClockYear)));
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
            f.machine(PoiKind.WINE_KEG,1,66,true,true,false);
            Pos occupiedSupport=new Pos(1,63,-1), standingCell=occupiedSupport.offset(0,1,0);
            f.unstandable.add(occupiedSupport); f.standingHeights.put(standingCell,surface);
            MachineModule module=new MachineModule(Feature.WINE);
            while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
            f.ground.add(new GroundItem(1,1.5,surface+.01,-.5,wine(f.wineClockYear)));
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
            f.machine(PoiKind.WINE_KEG,1,66,true,true,false);
            Pos occupiedSupport=new Pos(1,63,-1), above=occupiedSupport.offset(0,1,0);
            f.unstandable.add(occupiedSupport); f.standingHeights.put(above,surface);
            MachineModule module=new MachineModule(Feature.WINE);
            while (f.dropped==null) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
            f.ground.add(new GroundItem(1,1.5,63.51,-.5,wine(f.wineClockYear)));
            assertEquals(WorkResult.State.BLOCKED,f.run(module,40).state());
            assertTrue(f.navigationHistory.stream().noneMatch(v -> v.reach()<1.25));
            assertEquals(1,f.machineClicks());
        }
    }

    @Test void preexistingWineOfAnotherCohortBlocksCollectionBeforeAnyMachineClick() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,9); f.wineClockYear=8;
        Pos keg=f.machine(PoiKind.WINE_KEG,1,true,true,false);
        f.ground.add(new GroundItem(7,1.5,64.1,-.5,wine(7)));
        MachineModule module=new MachineModule(Feature.WINE);
        WorkResult result=f.run(module,40);
        assertEquals(WorkResult.State.BLOCKED,result.state()); assertTrue(result.message().contains("previously dropped"));
        assertEquals(0,f.machineClicks()); assertEquals(0,f.consumed); assertNull(f.dropped);
        assertTrue(f.blocks.get(keg).flag("mature")); assertTrue(f.profile.nextEligibleDay.isEmpty());
        // After explicit manual recovery, wine already held must not count as the new pickup.
        f.ground.clear(); f.inventory[2]=wine(7); module.reset();
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(1,f.machineClicks()); assertEquals(2,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
    }

    @Test void uncollectedOutputKeepsBlockingAcrossSchedulerPasses() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.WINE_KEG,0,true,true,false); f.pickup = false;
        MachineModule module = new MachineModule(Feature.WINE);
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
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) {
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
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.PAUSED,engine.state());
        assertTrue(engine.status().contains("not picked up")); assertEquals(1,f.machineClicks());
        assertNotNull(f.dropped); assertEquals(3,f.consumed); assertEquals(0,f.soldWine);
        PendingMachineOutput pending=f.profile.pendingMachineOutputs.values().iterator().next();
        assertEquals(PendingMachineOutput.Phase.AWAITING_PICKUP,pending.phase());
        assertEquals(6L,f.profile.nextEligibleDay.get("wine:10:64:0"));
        int actions=f.history.size(), visits=f.navigationCalls;
        engine.startOnce(f.context(),Feature.WINE);
        assertEquals(AutomationEngine.State.PAUSED,engine.state(),"future cooldown cannot conceal the outstanding output");
        assertEquals(actions,f.history.size()); assertEquals(visits,f.navigationCalls);
        engine.stop(f.context(),AutomationEngine.State.OFF,"manual takeover");
        f.pickup=true; f.advance();
        assertEquals(1,MachineOutputLedger.reconcile(f.context()),"same-session delayed pickup may settle while OFF");
        assertEquals(AutomationEngine.State.OFF,engine.state());
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,10);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertEquals(actions,f.history.size()); assertEquals(1,f.machineClicks());
    }

    @Test void matureUseIsCheckpointedBeforeImmediateDispatchAndPickupIsCheckpointedSeparately() {
        for (Feature feature:new Feature[]{Feature.WINE,Feature.PRESERVES}) {
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
        Pos keg=f.machine(PoiKind.WINE_KEG,10,true,true,false);
        f.checkpointHook=() -> { throw new IllegalStateException("disk unavailable"); };
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE);
        assertThrows(IllegalStateException.class,() -> f.runUntilStopped(engine,100));
        assertEquals(AutomationEngine.State.ERROR,engine.state());
        assertEquals(0,f.machineClicks()); assertEquals(0,f.consumed); assertTrue(f.blocks.get(keg).flag("mature"));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty()); assertTrue(f.session.liveMachineOutputs.isEmpty());
        assertTrue(f.savedOutputs.isEmpty()); assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.checkpointHook=() -> { };
        engine.startOnce(f.context(),Feature.WINE); f.runUntilStopped(engine,100);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state()); assertEquals(1,f.machineClicks());
    }

    @Test void failedRefillCheckpointKeepsUncertainObligationAndNeverRepeatsTheUse() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.pickup=false;
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        f.checkpointHook=() -> {
            if (f.profile.pendingMachineOutputs.values().stream().anyMatch(p -> p.phase()==PendingMachineOutput.Phase.AWAITING_PICKUP))
                throw new IllegalStateException("confirmation could not reach disk");
        };
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE);
        engine.startOnce(f.context(),Feature.WINE);
        assertThrows(IllegalStateException.class,() -> f.runUntilStopped(engine,100));
        assertEquals(AutomationEngine.State.ERROR,engine.state()); assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,f.profile.pendingMachineOutputs.values().iterator().next().phase());
        f.checkpointHook=() -> { }; f.pickup=true; f.advance();
        assertEquals(0,MachineOutputLedger.reconcile(f.context()),"uncertain Use acknowledgement is never inferred from inventory alone");
        engine.startOnce(f.context(),Feature.WINE);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,f.machineClicks());
    }

    @Test void failedPickupCheckpointRetainsObligationUntilSavedAndPreventsTheNextMachine() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,6); f.pickup=false;
        f.machine(PoiKind.WINE_KEG,10,true,true,false); f.machine(PoiKind.WINE_KEG,11,true,true,false);
        MachineModule module=new MachineModule(Feature.WINE);
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
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        MachineModule module=new MachineModule(Feature.WINE);
        PendingMachineOutput pending=f.awaitPickup(module);
        module.reset(); f.cancel();
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        f.inventory[35]=wine(f.wineClockYear);
        Context reconnected=new Context(f,f,f,f.profile,new SessionState(),f::checkpoint);
        assertEquals(0,MachineOutputLedger.reconcile(reconnected));
        assertEquals(pending,f.profile.pendingMachineOutputs.get(pending.id()));
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(reconnected,Feature.WINE);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,f.machineClicks());
        assertEquals(WorkResult.State.BLOCKED,new MachineModule(Feature.WINE).tick(reconnected).state());
    }

    @Test void unknownOrWrongWineCohortAndDisappearedGroundCannotSatisfyPickupBaseline() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.inventory[2]=wine(8,5,0); f.wineClockYear=8; f.pickup=false;
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        MachineModule module=new MachineModule(Feature.WINE);
        PendingMachineOutput pending=f.awaitPickup(module);
        assertEquals(6,pending.minimumInventoryCount()); assertEquals(8,pending.expectedWineYear());
        f.ground.add(new GroundItem(1,10.5,64,-.5,wine(8))); f.ground.clear();
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
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.WINE); engine.startOnce(f.context(),Feature.WINE);
        for (int n=0;n<100 && f.machineClicks()==0;n++) { engine.tick(f.context()); if (f.machineClicks()==0) f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(0,f.consumed);
        PendingMachineOutput pending=f.profile.pendingMachineOutputs.values().iterator().next();
        assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,pending.phase());
        engine.stop(f.context(),AutomationEngine.State.OFF,"cancelled before ACK");
        f.inventory[35]=wine(f.wineClockYear); f.advance();
        assertEquals(0,MachineOutputLedger.reconcile(f.context()));
        engine.startOnce(f.context(),Feature.WINE);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,f.machineClicks()); assertEquals(0,f.consumed);
        assertEquals(pending,f.profile.pendingMachineOutputs.get(pending.id()));
    }

    @Test void nativeWineClockMustBeKnownBeforeMatureMachineDispatch() {
        Fixture f=new Fixture(); f.inventory[0]=tomato(0,3); f.wineClockYear=null;
        f.machine(PoiKind.WINE_KEG,10,true,true,false);
        assertThrows(IllegalStateException.class,() -> f.run(new MachineModule(Feature.WINE),100));
        assertEquals(0,f.machineClicks()); assertEquals(0,f.consumed); assertTrue(f.profile.pendingMachineOutputs.isEmpty());
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
        Fixture f=new Fixture(); f.dayTime=10*24000L+1000;
        Pos source=f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        ItemData[] supplies=new ItemData[12];
        Arrays.fill(supplies,tomato(2,64)); supplies[11]=tomato(2,16); f.chests.put(source,supplies);
        for (int i=0;i<144;i++) f.machine(PoiKind.PRESERVES_JAR,10+i,false,true,false);
        AutomationEngine engine=isolatedMachineEngine(Feature.PRESERVES);
        engine.startOnce(f.context(),Feature.PRESERVES); f.runUntilStopped(engine,5000);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state(),engine.status());
        assertEquals(144,f.machineClicks()); assertEquals(720,f.consumed);
        assertEquals(144,ModuleSupport.count(f.context(),i -> i.is(ItemData.PRESERVES)));
        assertEquals(0,ModuleSupport.count(f.context(),i -> i.is(ItemData.TOMATO)));
        assertTrue(Arrays.stream(f.chests.get(source)).allMatch(ItemData::empty));
        assertTrue(f.usedGrades.stream().allMatch(grade -> grade==2));
        assertEquals(144,f.profile.nextEligibleDay.size());
        assertTrue(f.profile.nextEligibleDay.values().stream().allMatch(day -> day==13L));
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
        final List<NavVisit> navigationHistory=new ArrayList<>();
        final Map<Pos,Double> minimumReaches=new HashMap<>();
        final Map<Pos,Double> standingHeights=new HashMap<>();
        final List<GroundItem> ground=new ArrayList<>();
        final Deque<ItemData> tomatoRefills=new ArrayDeque<>();
        final Set<Pos> unloaded=new HashSet<>(), blockedPaths=new HashSet<>(), unstandable=new HashSet<>();
        Integer wineClockYear=20;
        long ticks, dayTime, sequence;
        int selected, consumed, navigationCalls, soldWine, withdrawnWine;
        Pos open, emptyOnSecondOpen;
        boolean sleeping, rejectSleep, pickup = true;
        boolean reportConfirmedCount;
        boolean immediateMachine, requireSavedBeforeMachine;
        Runnable checkpointHook=() -> { };
        ItemData dropped;
        ItemData refillAfterWineSale;
        ItemSlot extraMenuSlot;
        Action action;
        ActionOutcome outcome = new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture() { Arrays.fill(inventory,ItemData.EMPTY); }
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
                        }
                        else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"full inventory");
                    }
                }
            }
            if (pickup && dropped != null && add(inventory,dropped)) dropped = null;
        }
        private void applyMachine(Action.UseBlock use) {
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
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,sleeping,20,20,selected,true,true); }
        @Override public BlockData block(Pos pos) { return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of())); }
        @Override public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        @Override public boolean canStand(Pos feet) { return !unstandable.contains(feet); }
        @Override public double standingY(Pos feet) { return standingHeights.getOrDefault(feet,Double.NaN); }
        @Override public boolean canTraverse(Pos from,Pos to) { return true; }
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
                if (pickup && dropped!=null && add(inventory,dropped)) dropped=null;
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
