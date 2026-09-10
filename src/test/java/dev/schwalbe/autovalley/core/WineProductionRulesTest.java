package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WineProductionRulesTest {
    private static final Pos TOMATO=new Pos(1,64,0),ANCIENT=new Pos(2,64,0),SOURCE=new Pos(5,64,0),OUTPUT=new Pos(6,64,0);
    static Profile profile() {
        Profile p=new Profile();p.pois.add(new Poi(TOMATO,PoiKind.WINE_KEG,"Tomato",null));
        p.commodityStores.put("ancient-input",new CommodityStore("ancient-input","Fruit",Set.of(WineProductionRules.ANCIENT_FRUIT),List.of(SOURCE)));
        p.commodityStores.put("ancient-output",new CommodityStore("ancient-output","Wine",Set.of(WineProductionRules.ANCIENT_WINE),List.of(OUTPUT)));
        p.wineProductionLines.put("ancient",new WineProductionLine("ancient","Ancient wine",WineProductionRules.ANCIENT_FRUIT,
            WineProductionRules.ANCIENT_WINE,"ancient-input","ancient-output",List.of(ANCIENT),6,true));
        return p;
    }
    @Test void legacyLineRetainsItsPoisRecipeSwitchAndCycleWhileCustomLineUsesNamedStores() {
        Profile p=profile();p.wineCycleDays=7;WineProductionRules.validate(p);
        WineProductionLine legacy=WineProductionRules.line(p,"tomato"),ancient=WineProductionRules.line(p,"ancient");
        assertEquals(List.of(TOMATO),legacy.machines());assertEquals(ItemData.TOMATO,legacy.inputItemId());
        assertEquals(ItemData.WINE,legacy.outputItemId());assertEquals(7,legacy.cycleDays());assertTrue(legacy.enabled());
        assertEquals("ancient-input",WineProductionRules.inputStore(p,ancient).id());
        assertEquals(List.of(ANCIENT),WineProductionRules.machines(p,ancient).stream().map(Poi::pos).toList());
        assertEquals(ancient,WineProductionRules.at(p,ANCIENT));assertEquals(legacy,WineProductionRules.at(p,TOMATO));
        p.tomatoWineEnabled=false;assertFalse(WineProductionRules.line(p,"tomato").enabled());assertTrue(ancient.enabled());
    }
    @Test void mixedOrUnsupportedRecipesSharedMachinesAndWrongStoresCannotGainAuthority() {
        for(int cause=0;cause<3;cause++) {
            Profile p=profile();WineProductionLine old=p.wineProductionLines.get("ancient");
            p.wineProductionLines.put("ancient",new WineProductionLine(old.id(),old.name(),old.inputItemId(),
                cause==0 ? ItemData.WINE : old.outputItemId(),cause==2 ? "missing" : old.inputStoreId(),old.outputStoreId(),
                cause==1 ? List.of(TOMATO) : old.machines(),6,true));
            assertThrows(IllegalArgumentException.class,()->WineProductionRules.validate(p));
            if(cause==1)assertNull(WineProductionRules.at(p,TOMATO));
        }
    }
    @Test void inputGradesAreExactPerRecipeAndDoNotAdmitAnotherFruitOrUnknownGrade() {
        WineProductionLine line=WineProductionRules.line(profile(),"ancient");
        for(int grade=0;grade<4;grade++)assertTrue(WineProductionRules.input(new ItemData(line.inputItemId(),3,grade,null,false,99),line));
        assertFalse(WineProductionRules.input(new ItemData(ItemData.TOMATO,3,0,null,false,99),line));
        assertFalse(WineProductionRules.input(new ItemData(line.inputItemId(),3,4,null,false,99),line));
    }
    @Test void customMachinesCannotAliasAnyPoiCommodityContainerOrArtisanMachine() {
        for(int kind=0;kind<3;kind++) {
            Profile p=profile();WineProductionLine line=WineProductionRules.line(p,"ancient");
            if(kind==0)p.pois.add(new Poi(ANCIENT,PoiKind.PRESERVES_JAR,"Other",null));
            else if(kind==1)p.commodityStores.put("alias",new CommodityStore("alias","Alias",Set.of("minecraft:stone"),List.of(ANCIENT)));
            else p.artisanJobs.put("seed",new ArtisanJob("seed",ArtisanRecipe.ANCIENT_SEED.id(),List.of(ANCIENT),"ancient-input","seeds"));
            assertFalse(WineProductionRules.configured(p,line));assertThrows(IllegalArgumentException.class,()->WineProductionRules.validate(p));
        }
    }
    @Test void outputReserveRejectsMixedItemsOtherStoreAliasesAndOtherConsumers() {
        for(int kind=0;kind<6;kind++) {
            Profile p=profile();WineProductionLine line=WineProductionRules.line(p,"ancient");
            if(kind==0)p.commodityStores.put("ancient-output",new CommodityStore("ancient-output","Wine",Set.of(line.outputItemId(),"minecraft:egg"),List.of(OUTPUT)));
            else if(kind==1)p.commodityStores.put("alias",new CommodityStore("alias","Alias",Set.of(line.outputItemId()),List.of(OUTPUT)));
            else if(kind==2)p.pois.add(new Poi(OUTPUT,PoiKind.SHIPPING_BIN,"Shipping",null));
            else if(kind==3)p.cropStores.put("fake","ancient-output");
            else if(kind==4)p.fruitPatches.add(new FruitPatch("patch","ancient-output",List.of(new Pos(12,64,0))));
            else p.artisanJobs.put("seed",new ArtisanJob("seed",ArtisanRecipe.ANCIENT_SEED.id(),List.of(new Pos(12,64,0)),"ancient-input","ancient-output"));
            assertFalse(WineProductionRules.configured(p,line));assertThrows(IllegalArgumentException.class,()->WineProductionRules.validate(p));
        }
    }
    @Test void seedMakerMayShareTheAncientFruitInputWithoutSharingWineMachinesOrOutput() {
        Profile p=profile();p.commodityStores.put("seeds",new CommodityStore("seeds","Seeds",Set.of(ArtisanRecipe.ANCIENT_SEED.outputId()),List.of(new Pos(20,64,0))));
        p.artisanJobs.put("seed",new ArtisanJob("seed",ArtisanRecipe.ANCIENT_SEED.id(),List.of(new Pos(21,64,0)),"ancient-input","seeds"));
        assertTrue(WineProductionRules.configured(p,WineProductionRules.line(p,"ancient")));assertDoesNotThrow(()->WineProductionRules.validate(p));
    }
    @Test void recordsDetachMachineListsAndRejectInvalidCoordinatesAndCycles() {
        List<Pos> machines=new ArrayList<>(List.of(ANCIENT));
        WineProductionLine line=new WineProductionLine("a","A",WineProductionRules.ANCIENT_FRUIT,WineProductionRules.ANCIENT_WINE,"in","out",machines,6,true);
        machines.clear();assertEquals(List.of(ANCIENT),line.machines());assertThrows(UnsupportedOperationException.class,()->line.machines().clear());
        assertFalse(new WineProductionLine("a","A",line.inputItemId(),line.outputItemId(),"in","out",List.of(ANCIENT,ANCIENT),6,true).valid());
        assertFalse(new WineProductionLine("a","A",line.inputItemId(),line.outputItemId(),"in","out",List.of(ANCIENT),0,true).valid());
    }
    @Test void independentSchedulesPreserveLegacyKeysAndEachLinesOwnSixDayBoundary() {
        Fixture f=new Fixture();WineBatchRules.open(f.context,List.of(TOMATO));
        WineBatchSchedule tomato=f.profile.wineBatchSchedule;
        WineBatchRules.open(f.context,"ancient",List.of(ANCIENT));
        f.day=12;WineBatchRules.confirmFeed(f.context,"ancient",ANCIENT);WineBatchRules.finish(f.context,"ancient");
        assertSame(tomato,f.profile.wineBatchSchedule);assertFalse(f.profile.nextEligibleDay.containsKey(WineBatchRules.key(TOMATO)));
        assertEquals(18L,f.profile.nextEligibleDay.get(WineBatchRules.key("ancient",ANCIENT)));
        assertEquals(16,f.profile.wineProductionSchedules.get("ancient").nextDueDay());
        WineBatchSchedule ancient=f.profile.wineProductionSchedules.get("ancient");
        WineBatchRules.skip(f.context,TOMATO,"not ready");WineBatchRules.finish(f.context);
        assertSame(ancient,f.profile.wineProductionSchedules.get("ancient"));assertNull(f.profile.wineBatchSchedule.latestFeedDay());
        assertFalse(f.profile.nextEligibleDay.containsKey(WineBatchRules.key(TOMATO)));
    }
    @Test void customCheckpointFailureRollsBackOnlyItsScheduleAndDeadline() {
        Fixture f=new Fixture();WineBatchRules.open(f.context,List.of(TOMATO));WineBatchSchedule tomato=f.profile.wineBatchSchedule;
        f.fail=true;assertThrows(IllegalStateException.class,()->WineBatchRules.ensure(f.context,"ancient"));
        assertFalse(f.profile.wineProductionSchedules.containsKey("ancient"));assertSame(tomato,f.profile.wineBatchSchedule);
        f.fail=false;WineBatchRules.open(f.context,"ancient",List.of(ANCIENT));WineBatchSchedule old=f.profile.wineProductionSchedules.get("ancient");
        f.fail=true;assertThrows(IllegalStateException.class,()->WineBatchRules.confirmFeed(f.context,"ancient",ANCIENT));
        assertSame(old,f.profile.wineProductionSchedules.get("ancient"));assertSame(tomato,f.profile.wineBatchSchedule);
        assertFalse(f.profile.nextEligibleDay.containsKey(WineBatchRules.key("ancient",ANCIENT)));
    }
    private static final class Fixture implements WorldAccess {
        final Profile profile=profile();long day=10;boolean fail;
        final Context context=new Context(this,null,null,profile,new SessionState(),()->{if(fail)throw new IllegalStateException("checkpoint");});
        public long tick(){return 0;}public long dayTime(){return day*24000+1000;}public PlayerState player(){return null;}
        public BlockData block(Pos p){throw new AssertionError("Scheduling cannot inspect blocks");}public boolean loaded(Pos p){return false;}
        public boolean canStand(Pos p){return false;}public boolean canTraverse(Pos a,Pos b){return false;}
        public List<BlockData> scan(Pos p,int a,int b){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return null;}public boolean mayPlace(int s,ItemData i){return false;}
    }
}
