package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.modules.WineLineStorageModule;
import dev.schwalbe.autovalley.modules.WineLineSurplusShippingModule;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WineLineStorageAndSaleTest {
    private static final String LINE="ancient",INPUT=WineProductionRules.ANCIENT_FRUIT,OUTPUT=WineProductionRules.ANCIENT_WINE;
    private static final Pos INPUT_STORE=new Pos(30,64,0),BIN=new Pos(20,64,0),MACHINE=new Pos(15,64,0);
    private static ItemData wine(int count,Integer year,int quality) { return new ItemData(OUTPUT,count,quality,year,false,999); }
    private static ItemData item(String id,int count) { return new ItemData(id,count,0,null,false,999); }

    @Test void everyOneOfEightFullReservesMustBeOpenedAndConfirmedBeforeAnySurplusSale() {
        Fixture f=new Fixture();f.inventory.set(9,wine(19,null,2));
        f.run(new WineLineSurplusShippingModule());
        assertEquals(19,f.sold());assertTrue(f.inventory.get(9).empty());
        int firstSale=f.actions.indexOf(f.actions.stream().filter(a->a instanceof Action.QuickMove).findFirst().orElseThrow());
        Set<Pos> opened=new HashSet<>();
        for(Action action:f.actions.subList(0,firstSale)) if(action instanceof Action.UseBlock use && f.reserves.contains(use.pos())) opened.add(use.pos());
        assertEquals(new HashSet<>(f.reserves),opened);assertEquals(8,opened.size());
        assertTrue(f.session.wineLineSalePermits.isEmpty());assertFalse(f.menu().container());
        assertTrue(f.actions.stream().noneMatch(a->a instanceof Action.SwapHotbar));
    }
    @Test void oneRemainingSlotInTheEighthReserveRetainsAllCarriedWine() {
        Fixture f=new Fixture();f.inventory.set(9,wine(19,null,2));f.contents.get(f.reserves.get(7)).set(26,ItemData.EMPTY);
        f.run(new WineLineSurplusShippingModule());assertEquals(0,f.sold());assertEquals(19,f.inventory.get(9).count());
        assertTrue(f.actions.stream().noneMatch(a->a instanceof Action.QuickMove));assertTrue(f.session.wineLineSalePermits.isEmpty());
    }
    @Test void differentItemFillingEvenOneReserveSlotNeverAuthorizesWineSales() {
        for(String wrong:List.of(ItemData.WINE,INPUT,"minecraft:stone")) {
            Fixture f=new Fixture();f.inventory.set(9,wine(19,null,2));f.contents.get(f.reserves.get(7)).set(26,item(wrong,64));
            WorkResult result=f.run(new WineLineSurplusShippingModule());
            assertEquals(WorkResult.State.BLOCKED,result.state());assertEquals(0,f.sold());assertTrue(f.session.wineLineSalePermits.isEmpty());
        }
    }
    @Test void sharedReserveAllowsDifferentVintagesAndQualitiesInSeparateNativeStacks() {
        Fixture f=new Fixture();f.inventory.set(9,wine(3,null,1));f.inventory.set(10,wine(5,7,3));
        f.contents.get(f.reserves.get(0)).set(0,wine(64,1,0));f.contents.get(f.reserves.get(3)).set(2,wine(64,7,3));
        f.run(new WineLineSurplusShippingModule());assertEquals(8,f.sold());
        assertEquals(64,f.contents.get(f.reserves.get(0)).get(0).count());
    }
    @Test void storageFillsLastReserveBeforeTheNextSaleCanUseOnlyTheRemainingSurplus() {
        Fixture f=new Fixture();f.inventory.set(9,wine(10,null,0));
        f.contents.get(f.reserves.get(7)).set(26,wine(60,null,0));
        f.run(new WineLineStorageModule());assertEquals(6,f.inventory.get(9).count());assertEquals(0,f.sold());
        assertEquals(64,f.contents.get(f.reserves.get(7)).get(26).count());
        f.run(new WineLineSurplusShippingModule());assertEquals(6,f.sold());
    }
    @Test void fullFirstVintageDoesNotStarveAnotherVintageThatFitsRemainingReserveSpace() {
        Fixture f=new Fixture();f.inventory.set(9,wine(3,1,0));f.inventory.set(10,wine(4,2,0));
        f.contents.get(f.reserves.get(7)).set(26,wine(60,2,0));
        f.run(new WineLineStorageModule());assertEquals(3,f.inventory.get(9).count());assertTrue(f.inventory.get(10).empty());
        assertEquals(wine(64,2,0),f.contents.get(f.reserves.get(7)).get(26));
        f.run(new WineLineSurplusShippingModule());assertEquals(3,f.sold());
    }
    @Test void storageSkipsMixedContainersAndPreservesTheirOtherItems() {
        Fixture f=new Fixture();f.inventory.set(9,wine(3,null,0));
        f.contents.get(f.reserves.get(0)).set(0,item("minecraft:stone",1));f.contents.get(f.reserves.get(0)).set(1,ItemData.EMPTY);
        f.contents.get(f.reserves.get(1)).set(0,ItemData.EMPTY);
        f.run(new WineLineStorageModule());assertTrue(f.inventory.get(9).empty());
        assertEquals("minecraft:stone",f.contents.get(f.reserves.get(0)).get(0).id());
        assertTrue(f.contents.get(f.reserves.get(0)).get(1).empty());assertEquals(3,f.contents.get(f.reserves.get(1)).get(0).count());
    }
    @Test void unacknowledgedOpenCannotAdvanceReserveProofAndCancelledModuleRevokesItsPermit() {
        Fixture f=new Fixture();f.inventory.set(9,wine(3,null,0));f.hold=true;
        WineLineSurplusShippingModule module=new WineLineSurplusShippingModule();
        for(int i=0;i<8;i++) { module.tick(f.context);f.ticks++; }
        assertEquals(1,f.actions.size());assertTrue(f.session.wineLineSalePermits.isEmpty());assertEquals(0,f.sold());
        f.hold=false;module.reset();assertTrue(f.session.wineLineSalePermits.isEmpty());
    }
    @Test void missingTransferQuantityOrMissingAllowanceConsumptionCannotCountAsSuccessfulSale() {
        for(boolean missingQuantity:List.of(true,false)) {
            Fixture f=new Fixture();f.inventory.set(9,wine(3,null,0));f.zeroQuantity=missingQuantity;f.consume=!missingQuantity ? false : true;
            WorkResult result=f.run(new WineLineSurplusShippingModule());
            assertEquals(WorkResult.State.BLOCKED,result.state());assertTrue(f.session.wineLineSalePermits.isEmpty());
        }
    }
    @Test void outputPermitNeverBorrowsLegacyTomatoCohortAuthorization() {
        Fixture f=new Fixture();f.session.wineSalePermits.put(7,new WineSalePermit(7,64,f.ticks,0,Set.copyOf(f.reserves)));
        assertFalse(WineLineSaleRules.permitted(wine(1,7,0),f.context));
        f.permit(64);assertTrue(WineLineSaleRules.permitted(wine(1,null,0),f.context));
        assertFalse(WineLineSaleRules.permitted(new ItemData(ItemData.WINE,1,0,7,false,999),f.context));
        assertFalse(WineSaleRules.permitted(wine(1,7,0),f.context));
    }
    @Test void lineOutputStoreRegistrationLoadDayAndTimeRemainBoundToThePermit() {
        for(int change=0;change<7;change++) {
            Fixture f=new Fixture();f.permit(64);
            switch(change) {
                case 0 -> f.profile.wineProductionLines.put(LINE,f.line(false));
                case 1 -> f.profile.enabled.put(Feature.WINE_SURPLUS_SHIPPING,false);
                case 2 -> f.unloaded.add(f.reserves.get(7));
                case 3 -> f.ticks+=WineLineSaleRules.VALID_TICKS;
                case 4 -> f.ticks--;
                case 5 -> f.dayTime=24000;
                case 6 -> f.profile.commodityStores.put("out",new CommodityStore("out","changed",Set.of(OUTPUT),f.reserves.subList(0,7)));
            }
            assertFalse(WineLineSaleRules.permitted(wine(1,null,0),f.context),"change="+change);
        }
    }
    @Test void budgetRejectsNewPickupsAndDebitsActualAcknowledgementsEvenAfterLineRemoval() {
        Fixture f=new Fixture();f.permit(7);assertTrue(WineLineSaleRules.permitted(wine(7,null,0),f.context));
        assertFalse(WineLineSaleRules.permitted(wine(8,null,0),f.context));
        WineLineSaleRules.consume(item(ItemData.WINE,5),5,f.context);assertEquals(7,f.session.wineLineSalePermits.get(LINE).inventoryLimit());
        f.profile.wineProductionLines.clear();WineLineSaleRules.consume(wine(7,null,0),3,f.context);
        assertEquals(4,f.session.wineLineSalePermits.get(LINE).inventoryLimit());
        WineLineSaleRules.consume(wine(7,null,0),999,f.context);assertTrue(f.session.wineLineSalePermits.isEmpty());
    }
    @Test void eachOneShotFeatureGetsOnlyItsExactInputOutputAndReserveInspectionAuthority() {
        Fixture f=new Fixture();
        f.profile.commodityStores.put("in",new CommodityStore("in","input",Set.of(INPUT,OUTPUT,"minecraft:stone"),List.of(INPUT_STORE)));
        f.session.oneShotFeature=Feature.WINE;
        assertTrue(CommodityStorageRules.openAllowed(f.context,INPUT_STORE));
        assertTrue(CommodityStorageRules.withdrawalAllowed(f.context,INPUT_STORE,item(INPUT,3)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT_STORE,item(OUTPUT,3)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT_STORE,new ItemData(INPUT,3,4,null,false,999)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,f.reserves.get(0),item(INPUT,3)));
        assertTrue(CommodityStorageRules.depositAllowed(f.context,f.reserves.get(0),wine(3,null,0)));
        assertFalse(CommodityStorageRules.depositAllowed(f.context,f.reserves.get(0),item("minecraft:stone",3)));
        f.session.oneShotFeature=Feature.WINE_STORAGE;
        assertFalse(CommodityStorageRules.openAllowed(f.context,INPUT_STORE));
        assertTrue(CommodityStorageRules.depositAllowed(f.context,f.reserves.get(0),wine(3,null,0)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT_STORE,item(INPUT,3)));
        f.session.oneShotFeature=Feature.WINE_SURPLUS_SHIPPING;
        assertTrue(CommodityStorageRules.openAllowed(f.context,f.reserves.get(0)));
        assertFalse(CommodityStorageRules.depositAllowed(f.context,f.reserves.get(0),wine(3,null,0)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,f.reserves.get(0),wine(3,null,0)));
        f.profile.wineProductionLines.put(LINE,f.line(false));
        assertFalse(CommodityStorageRules.openAllowed(f.context,f.reserves.get(0)));
    }
    @Test void genericMixedOutputDeclarationCannotAuthorizeAnyWineLineScopeOrSale() {
        Fixture f=new Fixture();f.permit(64);
        f.profile.commodityStores.put("out",new CommodityStore("out","mixed declaration",Set.of(INPUT,OUTPUT,"minecraft:stone"),f.reserves));
        for(Feature scope:List.of(Feature.WINE,Feature.WINE_STORAGE,Feature.WINE_SURPLUS_SHIPPING)) {
            f.session.oneShotFeature=scope;
            assertFalse(CommodityStorageRules.openAllowed(f.context,f.reserves.get(0)));
            assertFalse(CommodityStorageRules.depositAllowed(f.context,f.reserves.get(0),wine(3,null,0)));
            assertFalse(WineLineSaleRules.permitted(wine(3,null,0),f.context));
        }
    }
    @Test void productionOneShotCanStoreItsExactLineOutputWithoutEnablingNeighborFeatures() {
        Fixture f=new Fixture();f.session.oneShotFeature=Feature.WINE;f.profile.enabled.put(Feature.WINE_STORAGE,false);
        f.inventory.set(9,wine(3,null,0));f.inventory.set(10,item(ItemData.WINE,5));
        f.contents.get(f.reserves.get(0)).set(0,ItemData.EMPTY);
        f.run(new WineLineStorageModule(Feature.WINE,LINE));
        assertTrue(f.inventory.get(9).empty());assertEquals(5,f.inventory.get(10).count());assertEquals(0,f.sold());
        assertFalse(f.profile.enabled(Feature.WINE_STORAGE));
        assertThrows(IllegalArgumentException.class,()->new WineLineStorageModule(Feature.WINE,null));
        assertThrows(IllegalArgumentException.class,()->new WineLineStorageModule(Feature.WINE,WineProductionRules.LEGACY_ID));
    }
    @Test void fullReserveRequiresExactOrdinaryMenuShapeFullCountsAndEmptyCursor() {
        for(int size:List.of(0,1,26,28,53,55)) {
            List<ItemSlot> slots=new ArrayList<>();for(int i=0;i<size;i++)slots.add(new ItemSlot(i,-1,false,wine(64,null,0)));
            assertFalse(WineLineSaleRules.fullReserve(new MenuData(1,0,slots,ItemData.EMPTY,true),OUTPUT));
        }
        for(int count:List.of(0,1,63,65)) {
            List<ItemSlot> slots=new ArrayList<>();for(int i=0;i<27;i++)slots.add(new ItemSlot(i,-1,false,wine(i==0?count:64,null,0)));
            assertFalse(WineLineSaleRules.fullReserve(new MenuData(1,0,slots,ItemData.EMPTY,true),OUTPUT));
        }
        List<ItemSlot> slots=new ArrayList<>();for(int i=0;i<54;i++)slots.add(new ItemSlot(i,-1,false,wine(64,null,0)));
        assertTrue(WineLineSaleRules.fullReserve(new MenuData(1,0,slots,ItemData.EMPTY,true),OUTPUT));
        assertFalse(WineLineSaleRules.fullReserve(new MenuData(1,0,slots,wine(1,null,0),true),OUTPUT));
        assertFalse(WineLineSaleRules.fullReserve(new MenuData(1,0,slots,ItemData.EMPTY,false),OUTPUT));
        assertFalse(WineLineSaleRules.fullReserve(new MenuData(1,0,slots,ItemData.EMPTY,true),ItemData.WINE));
    }
    @Test void emptyInventoryFinishesWithoutOpeningAnyContainersAndNeverEnrollsTomatoWine() {
        Fixture f=new Fixture();f.inventory.set(9,new ItemData(ItemData.WINE,3,0,7,false,999));
        f.run(new WineLineStorageModule());f.run(new WineLineSurplusShippingModule());
        assertTrue(f.actions.isEmpty());assertEquals(3,f.inventory.get(9).count());
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final Context context=new Context(this,this,this,profile,session);
        final List<Pos> reserves=new ArrayList<>();final Map<Pos,List<ItemData>> contents=new LinkedHashMap<>();
        final List<ItemData> inventory=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        final List<Action> actions=new ArrayList<>();final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final Set<Pos> unloaded=new HashSet<>();
        long ticks=100,dayTime=0,next;Pos opened;int menuId;boolean hold,zeroQuantity,consume=true;
        Fixture() {
            for(Feature feature:Feature.values())profile.enabled.put(feature,false);
            for(Feature feature:List.of(Feature.WINE,Feature.WINE_STORAGE,Feature.WINE_SURPLUS_SHIPPING))profile.enabled.put(feature,true);
            for(int i=0;i<8;i++){Pos pos=new Pos(i,64,0);reserves.add(pos);contents.put(pos,new ArrayList<>(Collections.nCopies(27,wine(64,null,0))));}
            contents.put(BIN,new ArrayList<>(Collections.nCopies(27,ItemData.EMPTY)));
            profile.commodityStores.put("in",new CommodityStore("in","input",Set.of(INPUT),List.of(INPUT_STORE)));
            profile.commodityStores.put("out",new CommodityStore("out","output",Set.of(OUTPUT),reserves));
            profile.wineProductionLines.put(LINE,line(true));profile.pois.add(new Poi(BIN,PoiKind.SHIPPING_BIN,"shipping",null));
        }
        WineProductionLine line(boolean enabled) { return new WineProductionLine(LINE,"Ancient wine",INPUT,OUTPUT,"in","out",List.of(MACHINE),3,enabled); }
        void permit(int limit) { session.wineLineSalePermits.put(LINE,new WineLineSalePermit(LINE,OUTPUT,"out",limit,ticks,0,Set.copyOf(reserves))); }
        WorkResult run(AutomationModule module) {
            for(int i=0;i<1000;i++){WorkResult result=module.tick(context);ticks++;if(result.state()!=WorkResult.State.BUSY)return result;}
            throw new AssertionError("Bounded wine sweep did not finish");
        }
        int sold() { return contents.get(BIN).stream().filter(item->item.is(OUTPUT)).mapToInt(ItemData::count).sum(); }
        public long submit(Action action) {
            long ticket=++next;actions.add(action);
            if(hold){outcomes.put(ticket,new ActionOutcome(ActionOutcome.State.PENDING,"withheld"));return ticket;}
            int moved=0;
            if(action instanceof Action.UseBlock use){opened=use.pos();menuId++;}
            else if(action instanceof Action.CloseContainer){opened=null;}
            else if(action instanceof Action.QuickMove move) {
                ItemSlot source=menu().slot(move.slot());assertNotNull(source);assertTrue(source.player(),"Reserve withdrawal is forbidden");
                ItemData old=source.item();List<ItemData> destination=contents.get(opened);int left=old.count();
                for(int i=0;i<destination.size() && left>0;i++) {
                    ItemData existing=destination.get(i);boolean same=existing.id().equals(old.id()) && existing.quality()==old.quality() && Objects.equals(existing.year(),old.year());
                    if(!existing.empty() && !same)continue;
                    int current=existing.empty()?0:existing.count(),put=Math.min(64-current,left);if(put<=0)continue;
                    destination.set(i,new ItemData(old.id(),current+put,old.quality(),old.year(),old.hoe(),old.durability()));left-=put;
                }
                moved=old.count()-left;inventory.set(source.inventoryIndex(),left==0?ItemData.EMPTY:new ItemData(old.id(),left,old.quality(),old.year(),old.hoe(),old.durability()));
                if(BIN.equals(opened) && consume)WineLineSaleRules.consume(old,moved,context);
            } else throw new AssertionError("Unexpected action "+action);
            outcomes.put(ticket,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"server fixture",zeroQuantity?0:moved));return ticket;
        }
        public ActionOutcome outcome(long ticket) { return outcomes.get(ticket); }
        public long tick(){return ticks;}public long dayTime(){return dayTime;}public Integer wineYear(){return 10;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,4,true,true);}
        public BlockData block(Pos pos){return new BlockData(pos,BIN.equals(pos)?"shippingbin:smart_shipping_bin":"minecraft:barrel",Map.of("container","true"));}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}public boolean canStand(Pos pos){return true;}
        public boolean canTraverse(Pos a,Pos b){return true;}public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){List<ItemSlot> result=new ArrayList<>();for(int i=0;i<36;i++)result.add(new ItemSlot(i,i,true,inventory.get(i)));return result;}
        public MenuData menu(){
            if(opened==null)return new MenuData(0,0,inventory(),ItemData.EMPTY,false);
            List<ItemSlot> result=new ArrayList<>();List<ItemData> stored=contents.get(opened);
            for(int i=0;i<stored.size();i++)result.add(new ItemSlot(i,-1,false,stored.get(i)));
            for(int i=0;i<36;i++)result.add(new ItemSlot(stored.size()+i,i,true,inventory.get(i)));
            return new MenuData(menuId,0,result,ItemData.EMPTY,true);
        }
        public boolean mayPlace(int slot,ItemData item){return true;}public boolean busy(){return false;}
        public void move(Movement movement){throw new AssertionError();}public void stopMovement(){}public void cancel(){}
        public Navigation.Result moveTo(Pos pos,double reach,Context c){return Navigation.Result.ARRIVED;}public void reset(){}
    }
}
