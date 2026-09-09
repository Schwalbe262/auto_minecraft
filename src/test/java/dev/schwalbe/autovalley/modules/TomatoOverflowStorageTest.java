package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TomatoOverflowStorageTest {
    static ItemData tomato(int q,int count){return new ItemData(ItemData.TOMATO,count,q,null,false,999);}
    @Test void fullWarehouseShipsCarriedTomatoesWithoutWithdrawingAnyReserve() {
        Fixture f=new Fixture();Pos a=f.store(1728),b=f.store(1728);f.inv[9]=tomato(2,64);f.inv[10]=tomato(0,12);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(0,f.held());assertEquals(76,f.count(f.bin));
        assertEquals(1728,f.count(a));assertEquals(1728,f.count(b));assertEquals(1,Collections.frequency(f.opens,a));
        assertEquals(1,Collections.frequency(f.opens,b));assertNull(f.session.tomatoSalePermit);assertNull(f.open);
    }
    @Test void belowThresholdStoresEverythingAndDoesNotOpenShipping() {
        Fixture f=new Fixture();Pos a=f.store(100);f.inv[9]=tomato(1,64);assertEquals(WorkResult.State.IDLE,f.run().state());
        assertEquals(164,f.count(a));assertEquals(0,f.count(f.bin));assertFalse(f.opens.contains(f.bin));
    }
    @Test void reserveIsFilledWithWholeStacksBeforeRemainingTomatoesAreShipped() {
        Fixture f=new Fixture();Pos a=f.store(1500);f.inv[9]=tomato(0,64);f.inv[10]=tomato(2,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(1564,f.count(a));assertEquals(64,f.count(f.bin));
        assertEquals(0,f.held());assertTrue(f.count(a)>=TomatoSaleRules.reserve(1728,90));
    }
    @Test void eightyPercentSettingRoutesSurplusEarlierThanNinety() {
        for(int percent:List.of(80,90)){
            Fixture f=new Fixture();f.profile.tomatoStorageLimitPercent=percent;Pos a=f.store(1450);f.inv[9]=tomato(0,64);
            assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(percent==80?64:0,f.count(f.bin));
            assertEquals(percent==80?1450:1514,f.count(a));
        }
    }
    @Test void allStoresAreCountedNotJustOneFullBarrel() {
        Fixture f=new Fixture();f.store(1728);Pos empty=f.store(0);f.inv[9]=tomato(3,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(64,f.count(empty));assertEquals(0,f.count(f.bin));
    }
    @Test void anyHeldGradeThatFitsCanBeStoredEvenWhenFirstGradeDoesNotFitThisBarrel() {
        Fixture f=new Fixture();f.profile.tomatoStorageLimitPercent=100;Pos a=f.store(1728);f.contents.get(a)[0]=tomato(1,48);
        Pos b=f.store(0);f.inv[9]=tomato(0,64);f.inv[10]=tomato(1,16);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(1728,f.count(a));assertEquals(64,f.count(b));
    }
    @Test void mixedForeignContentsBlockSaleWithoutMovingAnything() {
        Fixture f=new Fixture();Pos a=f.store(1728);f.contents.get(a)[0]=new ItemData("minecraft:diamond",64,0,null,false,999);
        f.inv[9]=tomato(1,64);assertEquals(WorkResult.State.BLOCKED,f.run().state());assertEquals(64,f.held());assertEquals(0,f.count(f.bin));
    }
    @Test void shippingFullKeepsCarriedTomatoesAndClosesMenu() {
        Fixture f=new Fixture();f.store(1728);Arrays.fill(f.contents.get(f.bin),tomato(0,64));f.inv[9]=tomato(1,64);
        assertEquals(WorkResult.State.BLOCKED,f.run().state());assertEquals(64,f.held());assertNull(f.open);
    }
    @Test void disabledSurplusRetainsLegacyStorageAndNeverSells() {
        Fixture f=new Fixture();f.profile.tomatoSurplusShippingEnabled=false;Pos a=f.store(1500);f.inv[9]=tomato(0,64);f.inv[10]=tomato(1,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(1628,f.count(a));assertEquals(0,f.count(f.bin));
    }
    @Test void harvestOneShotMayCompleteItsOwnStorageAndSurplusButUnrelatedJobCannotSell() {
        Fixture f=new Fixture();f.session.oneShotFeature=Feature.HARVEST;f.store(1728);f.inv[9]=tomato(0,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(64,f.count(f.bin));
        f.session.oneShotFeature=Feature.WINE;assertFalse(TomatoSaleRules.enabled(f.c));
    }
    @Test void permitRejectsWrongItemExpiredChangedSettingMissingStoreUnloadedAndExcessQuantity() {
        Fixture f=new Fixture();Pos a=f.store(1728);f.permit();assertTrue(TomatoSaleRules.permitted(tomato(1,64),f.c));
        assertFalse(TomatoSaleRules.permitted(tomato(1,65),f.c));assertFalse(TomatoSaleRules.permitted(ItemData.EMPTY,f.c));
        f.tick=1200;assertFalse(TomatoSaleRules.permitted(tomato(1,64),f.c));f.tick=0;
        f.profile.tomatoStorageLimitPercent=80;assertFalse(TomatoSaleRules.permitted(tomato(1,64),f.c));f.profile.tomatoStorageLimitPercent=90;
        f.unloaded=a;assertFalse(TomatoSaleRules.permitted(tomato(1,64),f.c));f.unloaded=null;
        f.profile.pois.removeIf(p->p.kind()==PoiKind.TOMATO_CHEST);assertFalse(TomatoSaleRules.permitted(tomato(1,64),f.c));
    }
    @Test void pendingActionsAreNotResentAndUnconfirmedTransfersCannotComplete() {
        Fixture f=new Fixture();f.store(1728);f.inv[9]=tomato(0,64);f.holdOpen=true;
        for(int i=0;i<20;i++){f.tick++;assertEquals(WorkResult.State.BUSY,f.module.tick(f.c).state());}
        assertEquals(1,f.opens.size());assertEquals(64,f.held());
    }
    @Test void full32BarrelWarehouseDoesOneSurveyThenShipsAllGrades() {
        Fixture f=new Fixture();for(int i=0;i<32;i++)f.store(1728);for(int i=9;i<35;i++)f.inv[i]=tomato(i%4,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(1664,f.count(f.bin));assertEquals(33,f.opens.size());assertEquals(0,f.held());
    }
    @Test void secondHaulReusesAll32ConfirmedBarrelsWithoutAnotherWarehouseVisit() {
        Fixture f=new Fixture();for(int i=0;i<32;i++)f.store(1728);f.inv[9]=tomato(0,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());
        TomatoStockCache.View first=f.session.tomatoStockCache.reusable(f.c).orElseThrow();
        f.module.reset();f.opens.clear();f.dayTime+=24000;f.inv[9]=tomato(2,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(List.of(f.bin),f.opens);
        assertEquals(128,f.count(f.bin));assertEquals(first.fullSurveyTick(),f.session.tomatoStockCache.reusable(f.c).orElseThrow().fullSurveyTick());
    }
    @Test void expiredCacheDoesNoIdleTravelAndRefreshesInsideTheNextActualHaul() {
        Fixture f=new Fixture();Pos a=f.store(1728),b=f.store(1728);f.inv[9]=tomato(0,64);f.run();
        f.module.reset();f.opens.clear();f.routes.clear();f.dayTime+=3*24000;
        assertEquals(WorkResult.State.IDLE,f.run().state());assertTrue(f.opens.isEmpty());assertTrue(f.routes.isEmpty());
        f.inv[9]=tomato(1,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(1,Collections.frequency(f.opens,a));
        assertEquals(1,Collections.frequency(f.opens,b));assertEquals(1,Collections.frequency(f.opens,f.bin));
        assertEquals(3,f.session.tomatoStockCache.reusable(f.c).orElseThrow().fullSurveyDay());
    }
    @Test void nativeDepositReplacesCachedSlotsWithoutRenewingWholeWarehouseDate() {
        Fixture f=new Fixture();Pos a=f.store(100);f.inv[9]=tomato(1,64);f.run();
        TomatoStockCache.View first=f.session.tomatoStockCache.reusable(f.c).orElseThrow();
        assertEquals(164,first.total());f.module.reset();f.opens.clear();f.dayTime+=24000;f.inv[9]=tomato(2,32);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(List.of(a),f.opens);
        TomatoStockCache.View after=f.session.tomatoStockCache.reusable(f.c).orElseThrow();
        assertEquals(196,after.total());assertEquals(first.fullSurveyTick(),after.fullSurveyTick());assertEquals(first.fullSurveyDay(),after.fullSurveyDay());
        assertArrayEquals(new int[]{100,64,32,0},after.qualityCounts());
    }
    @Test void dirtyBarrelRequiresANewFullSurveyRatherThanReusingRemainingCleanBarrels() {
        Fixture f=new Fixture();Pos a=f.store(1728),b=f.store(1728);f.inv[9]=tomato(0,64);f.run();
        f.module.reset();f.opens.clear();f.session.tomatoStockCache.invalidate(a);f.inv[9]=tomato(2,64);
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(1,Collections.frequency(f.opens,a));
        assertEquals(1,Collections.frequency(f.opens,b));assertEquals(3,f.opens.size());
    }
    @Test void fiftyRemainingPermitCanShipAnActualSixtyFourStackWithoutWarehouseReturn() {
        Fixture f=new Fixture();Pos a=f.store(1728);for(int i=9;i<23;i++)f.inv[i]=tomato(0,64); // Original batch: 896.
        f.afterTransfer=()->{if(f.sales.size()==13){f.inv[22]=tomato(0,14);f.inv[23]=tomato(0,64);}};
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(910,f.count(f.bin));assertEquals(0,f.held());
        assertEquals(List.of(a,f.bin),f.opens);assertEquals(15,f.sales.size());
        TomatoSalePermit first=f.sales.get(0),last=f.sales.get(14);
        assertEquals(896,first.inventoryLimit());assertEquals(64,last.inventoryLimit());
        assertEquals(first.verifiedTick(),last.verifiedTick());assertEquals(first.gameDay(),last.gameDay());
        assertEquals(first.cacheEpoch(),last.cacheEpoch());assertEquals(first.storages(),last.storages());
    }
    @Test void completelyConsumedPermitMayCoverNewActualPickupButNeverMovesAnInventedQuantity() {
        Fixture f=new Fixture();Pos a=f.store(1728);f.inv[9]=tomato(0,64);
        f.afterTransfer=()->{if(f.sales.size()==1)f.inv[9]=tomato(3,12);};
        assertEquals(WorkResult.State.IDLE,f.run().state());assertEquals(76,f.count(f.bin));assertEquals(List.of(a,f.bin),f.opens);
        assertEquals(12,f.sales.get(1).inventoryLimit());assertEquals(f.sales.get(0).verifiedTick(),f.sales.get(1).verifiedTick());
    }
    @Test void repeatedGroundPickupsHaveAnExplicitFinitePermitRenewalBudget() {
        Fixture f=new Fixture();f.store(1728);f.inv[9]=tomato(0,1);f.afterTransfer=()->f.inv[9]=tomato(0,1);
        assertEquals(WorkResult.State.BLOCKED,f.run().state());assertEquals(1+TomatoOverflowStorageModule.MAX_PERMIT_RENEWALS,f.sales.size());
        assertEquals(1,f.held());assertEquals(2,f.opens.size());assertNull(f.session.tomatoSalePermit);assertNull(f.open);
    }
    @Test void renewedPermitCannotResetHardDeadlineDayEpochPercentOrRegistration() {
        for(String change:List.of("deadline","day","epoch","percent","registration","reserve","permit")) {
            Fixture f=new Fixture();Pos a=f.store(1728);f.inv[9]=tomato(0,64);
            f.afterTransfer=()->{
                f.inv[9]=tomato(1,64);
                switch(change) {
                    case "deadline" -> f.tick=f.sales.get(0).verifiedTick()+1200;
                    case "day" -> f.dayTime+=24000;
                    case "epoch" -> f.session.tomatoStockCache.invalidate(a);
                    case "percent" -> f.profile.tomatoStorageLimitPercent=80;
                    case "registration" -> f.store(1728);
                    case "permit" -> f.permit(); // A different/legacy permission is not this original batch.
                    case "reserve" -> {Arrays.fill(f.contents.get(a),ItemData.EMPTY);assertTrue(f.session.tomatoStockCache.observeVerified(f.c,a,List.of(f.contents.get(a))));}
                }
            };
            assertEquals(WorkResult.State.BLOCKED,f.run().state(),change);assertEquals(1,f.sales.size(),change);
            assertEquals(64,f.held(),change);assertEquals(2,f.opens.size(),change);assertNull(f.session.tomatoSalePermit,change);
        }
    }
    @Test void freshCacheIsOnlyATravelHintUntilAllStoresLoadBeforeShippingOpen() {
        for(boolean loadOnArrival:List.of(true,false)) {
            Fixture f=new Fixture();Pos a=f.store(1728);f.inv[9]=tomato(0,64);f.run();
            f.module.reset();f.opens.clear();f.routes.clear();f.inv[9]=tomato(2,64);f.unloaded=a;
            f.onRoute=()->{if(loadOnArrival)f.unloaded=null;};
            assertEquals(loadOnArrival?WorkResult.State.IDLE:WorkResult.State.BLOCKED,f.run().state());
            assertEquals(List.of(f.bin),f.routes);assertEquals(loadOnArrival?List.of(f.bin):List.of(),f.opens);
            assertEquals(loadOnArrival?0:64,f.held());
        }
    }
    @Test void failedOrCancelledDepositCannotLeavePreviouslyReusableStockProof() {
        for(boolean reset:List.of(true,false)) {
            Fixture f=new Fixture();f.store(100);f.inv[9]=tomato(0,64);f.run();f.module.reset();f.inv[9]=tomato(1,64);
            f.afterTransfer=()->f.outcome=new ActionOutcome(ActionOutcome.State.PENDING,"");
            for(int i=0;i<20 && !f.busy();i++){f.tick++;f.module.tick(f.c);}assertTrue(f.busy());
            if(reset)f.module.reset();
            else {f.outcome=new ActionOutcome(ActionOutcome.State.FAILED,"unconfirmed");assertEquals(WorkResult.State.BLOCKED,f.run().state());}
            assertTrue(f.session.tomatoStockCache.reusable(f.c).isEmpty());assertEquals(0,f.count(f.bin));
        }
    }
    @Test void invalidatedCompleteSurveyCannotFallBackToALegacySalePermit() {
        Fixture f=new Fixture();Pos a=f.store(1728);f.store(1728);f.inv[9]=tomato(1,64);
        for(int i=0;i<30 && (f.opens.size()!=2 || f.open!=null);i++){f.tick++;assertEquals(WorkResult.State.BUSY,f.module.tick(f.c).state());}
        assertEquals(2,f.opens.size());assertNull(f.open);f.session.tomatoStockCache.invalidate(a);
        assertEquals(WorkResult.State.BLOCKED,f.run().state());assertEquals(64,f.held());assertEquals(0,f.sales.size());
        assertNull(f.session.tomatoSalePermit);assertTrue(f.session.tomatoStockCache.reusable(f.c).isEmpty());
    }
    @Test void nominalTransferSuccessWithoutPositiveNativeCountInvalidatesTheCache() {
        Fixture f=new Fixture();Pos a=f.store(100);f.inv[9]=tomato(0,64);f.run();f.module.reset();f.inv[9]=tomato(1,64);
        f.afterTransfer=()->f.outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"no quantity proof");
        assertEquals(WorkResult.State.BLOCKED,f.run().state());assertTrue(f.session.tomatoStockCache.reusable(f.c).isEmpty());
        assertEquals(228,f.count(a),"fixture applied real contents, but the module must not infer the missing ACK");assertTrue(f.sales.isEmpty());
    }

    static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final ItemData[] inv=new ItemData[36];final Map<Pos,ItemData[]> contents=new LinkedHashMap<>();
        final List<Pos> opens=new ArrayList<>(),routes=new ArrayList<>();final List<TomatoSalePermit> sales=new ArrayList<>();
        final Pos bin=new Pos(0,64,5);Pos open,unloaded;Runnable afterTransfer=()->{},onRoute=()->{};
        final Context c=new Context(this,this,this,profile,session,()->{});final TomatoStorageModule module=new TomatoStorageModule();
        long tick,id,dayTime=5000;boolean holdOpen;ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture(){Arrays.fill(inv,ItemData.EMPTY);contents.put(bin,empty());profile.pois.add(new Poi(bin,PoiKind.SHIPPING_BIN,"sell",null));}
        ItemData[] empty(){ItemData[] items=new ItemData[27];Arrays.fill(items,ItemData.EMPTY);return items;}
        Pos store(int count){Pos p=new Pos(contents.size(),64,0);ItemData[] items=empty();for(int n=0;count>0;n++){int moved=Math.min(count,64);items[n]=tomato(0,moved);count-=moved;}
            contents.put(p,items);profile.pois.add(new Poi(p,PoiKind.TOMATO_CHEST,"tomatoes",null));return p;}
        int count(Pos p){return Arrays.stream(contents.get(p)).filter(i->i.is(ItemData.TOMATO)).mapToInt(ItemData::count).sum();}
        int held(){return Arrays.stream(inv).filter(i->i.is(ItemData.TOMATO)).mapToInt(ItemData::count).sum();}
        void permit(){session.tomatoSalePermit=new TomatoSalePermit(64,0,0,90,1728,1728,Set.of(profile.pois(PoiKind.TOMATO_CHEST).get(0).pos()));}
        WorkResult run(){for(int i=0;i<1100;i++){tick++;WorkResult r=module.tick(c);if(r.state()!=WorkResult.State.BUSY)return r;}throw new AssertionError("did not finish");}
        public long tick(){return tick;}public long dayTime(){return dayTime;}public boolean loaded(Pos p){return !p.equals(unloaded);}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true);}
        public BlockData block(Pos p){return new BlockData(p,p.equals(bin)?"society:smart_shipping_bin":"minecraft:barrel",Map.of());}
        public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){List<ItemSlot> rows=new ArrayList<>();for(int i=0;i<36;i++)rows.add(new ItemSlot(i,i,true,inv[i]));return rows;}
        public MenuData menu(){List<ItemSlot> rows=new ArrayList<>();int offset=open==null?0:27;if(open!=null)for(int i=0;i<27;i++)rows.add(new ItemSlot(i,-1,false,contents.get(open)[i]));
            for(int i=0;i<36;i++)rows.add(new ItemSlot(i+offset,i,true,inv[i]));return new MenuData(open==null?0:10,(int)tick,rows,ItemData.EMPTY,open!=null);}
        public boolean mayPlace(int slot,ItemData item){return open!=null && slot>=0 && slot<27;}
        public boolean busy(){return outcome.state()==ActionOutcome.State.PENDING;}
        public long submit(Action action){outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
            if(action instanceof Action.UseBlock use){open=use.pos();opens.add(open);if(holdOpen)outcome=new ActionOutcome(ActionOutcome.State.PENDING,"");}
            else if(action instanceof Action.CloseContainer)open=null;
            else if(action instanceof Action.QuickMove move){ItemSlot source=menu().slot(move.slot());assertTrue(source.player(),"never withdraw reserves");assertTrue(source.item().is(ItemData.TOMATO));
                if(open.equals(bin)){assertTrue(TomatoSaleRules.permitted(source.item(),c));sales.add(session.tomatoSalePermit);}
                ItemData item=source.item();int left=item.count();ItemData[] dest=contents.get(open);
                for(int pass=0;pass<2;pass++)for(int i=0;i<27 && left>0;i++){ItemData old=dest[i];if(pass==0 && (old.empty() || !ModuleSupport.same(old,item)) || pass==1 && !old.empty())continue;
                    int before=old.empty()?0:old.count(),moved=Math.min(left,64-before);if(moved>0){dest[i]=tomato(item.quality(),before+moved);left-=moved;}}
                int moved=item.count()-left;assertTrue(moved>0);inv[source.inventoryIndex()]=left==0?ItemData.EMPTY:tomato(item.quality(),left);
                if(open.equals(bin))TomatoSaleRules.consume(moved,c);outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native acknowledged transfer",moved);afterTransfer.run();
            }else throw new AssertionError(action);return ++id;}
        public ActionOutcome outcome(long ticket){return outcome;}public void move(Movement m){throw new AssertionError();}public void stopMovement(){}public void cancel(){}
        public Navigation.Result moveTo(Pos p,double reach,Context c){routes.add(p);onRoute.run();return Navigation.Result.ARRIVED;}public void reset(){}
    }
}
