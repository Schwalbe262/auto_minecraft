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

    static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final ItemData[] inv=new ItemData[36];final Map<Pos,ItemData[]> contents=new LinkedHashMap<>();
        final List<Pos> opens=new ArrayList<>();final Pos bin=new Pos(0,64,5);Pos open,unloaded;
        final Context c=new Context(this,this,this,profile,session,()->{});final TomatoStorageModule module=new TomatoStorageModule();
        long tick,id;boolean holdOpen;ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture(){Arrays.fill(inv,ItemData.EMPTY);contents.put(bin,empty());profile.pois.add(new Poi(bin,PoiKind.SHIPPING_BIN,"sell",null));}
        ItemData[] empty(){ItemData[] items=new ItemData[27];Arrays.fill(items,ItemData.EMPTY);return items;}
        Pos store(int count){Pos p=new Pos(contents.size(),64,0);ItemData[] items=empty();for(int n=0;count>0;n++){int moved=Math.min(count,64);items[n]=tomato(0,moved);count-=moved;}
            contents.put(p,items);profile.pois.add(new Poi(p,PoiKind.TOMATO_CHEST,"tomatoes",null));return p;}
        int count(Pos p){return Arrays.stream(contents.get(p)).filter(i->i.is(ItemData.TOMATO)).mapToInt(ItemData::count).sum();}
        int held(){return Arrays.stream(inv).filter(i->i.is(ItemData.TOMATO)).mapToInt(ItemData::count).sum();}
        void permit(){session.tomatoSalePermit=new TomatoSalePermit(64,0,0,90,1728,1728,Set.of(profile.pois(PoiKind.TOMATO_CHEST).get(0).pos()));}
        WorkResult run(){for(int i=0;i<1100;i++){tick++;WorkResult r=module.tick(c);if(r.state()!=WorkResult.State.BUSY)return r;}throw new AssertionError("did not finish");}
        public long tick(){return tick;}public long dayTime(){return 5000;}public boolean loaded(Pos p){return !p.equals(unloaded);}
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
                if(open.equals(bin))assertTrue(TomatoSaleRules.permitted(source.item(),c));
                ItemData item=source.item();int left=item.count();ItemData[] dest=contents.get(open);
                for(int pass=0;pass<2;pass++)for(int i=0;i<27 && left>0;i++){ItemData old=dest[i];if(pass==0 && (old.empty() || !ModuleSupport.same(old,item)) || pass==1 && !old.empty())continue;
                    int before=old.empty()?0:old.count(),moved=Math.min(left,64-before);if(moved>0){dest[i]=tomato(item.quality(),before+moved);left-=moved;}}
                int moved=item.count()-left;assertTrue(moved>0);inv[source.inventoryIndex()]=left==0?ItemData.EMPTY:tomato(item.quality(),left);
                if(open.equals(bin))TomatoSaleRules.consume(moved,c);outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native acknowledged transfer",moved);
            }else throw new AssertionError(action);return ++id;}
        public ActionOutcome outcome(long ticket){return outcome;}public void move(Movement m){throw new AssertionError();}public void stopMovement(){}public void cancel(){}
        public Navigation.Result moveTo(Pos p,double reach,Context c){return Navigation.Result.ARRIVED;}public void reset(){}
    }
}
