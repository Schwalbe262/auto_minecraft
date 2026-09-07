package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TomatoCommodityStorageTest {
    private static ItemData tomato(int quality,int count) { return new ItemData(ItemData.TOMATO,count,quality,null,false,999); }
    private static ItemData wine(int year) { return new ItemData(ItemData.WINE,1,0,year,false,999); }

    @Test void nullAndStaleChestClassifiersAndLegacyTargetsCannotRouteTomatoesByGrade() {
        for(Integer classifier:Arrays.asList(null,0,3)) {
            Fixture f=new Fixture(); Pos chest=f.add(PoiKind.TOMATO_CHEST,classifier,tomato(1,5),tomato(3,7));
            f.profile.tomatoStorageTargets.put(Profile.positionKey(chest),2);
            f.inventory[9]=tomato(0,4); f.inventory[10]=tomato(2,6);
            List<Poi> registrations=List.copyOf(f.profile.pois);
            WorkResult result=f.run(new TomatoStorageModule());
            assertEquals(WorkResult.State.IDLE,result.state(),result.message());
            assertEquals(0,f.held(ItemData.TOMATO)); assertEquals(22,f.stored(chest,ItemData.TOMATO));
            assertEquals(registrations,f.profile.pois); assertEquals(2,f.profile.tomatoStorageTargets.get(Profile.positionKey(chest)));
            assertEquals(0,f.checkpoints,"Commodity deposit does not rewrite old grade metadata");
        }
    }

    @Test void unknownQualityTomatoesCanBeStoredWithoutInventingOrChangingTheirGrade() {
        Fixture f=new Fixture(); Pos chest=f.add(PoiKind.TOMATO_CHEST,null);
        f.inventory[9]=tomato(-1,8); f.inventory[10]=tomato(99,3);
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule()).state());
        assertEquals(0,f.held(ItemData.TOMATO));
        assertTrue(Arrays.asList(f.contents.get(chest)).contains(tomato(-1,8)));
        assertTrue(Arrays.asList(f.contents.get(chest)).contains(tomato(99,3)));
    }

    @Test void normalContainerTransferFillsMatchingPartialBeforeAnEarlierEmptySlot() {
        Fixture f=new Fixture(); Pos chest=f.add(PoiKind.TOMATO_CHEST,3,
            ItemData.EMPTY,tomato(0,48),tomato(1,16));
        f.inventory[9]=tomato(0,16); f.inventory[10]=tomato(1,48);
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule()).state());
        assertTrue(f.contents.get(chest)[0].empty());
        assertEquals(tomato(0,64),f.contents.get(chest)[1]);
        assertEquals(tomato(1,64),f.contents.get(chest)[2]);
        assertEquals(0,f.held(ItemData.TOMATO));
    }

    @Test void foreignContentsAreLeftUntouchedAndAnotherPureTomatoDestinationIsUsed() {
        Fixture f=new Fixture(); ItemData foreign=new ItemData("minecraft:diamond",7,0,null,false,999);
        Pos mixed=f.add(PoiKind.TOMATO_CHEST,0,tomato(0,2),foreign);
        Pos pure=f.add(PoiKind.TOMATO_CHEST,3,tomato(1,4));
        List<ItemData> original=List.copyOf(Arrays.asList(f.contents.get(mixed)));
        f.inventory[9]=tomato(2,9);
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule()).state());
        assertEquals(original,Arrays.asList(f.contents.get(mixed))); assertFalse(f.transfers.contains(mixed));
        assertEquals(List.of(pure),f.transfers); assertEquals(13,f.stored(pure,ItemData.TOMATO));
    }

    @Test void allForeignDestinationsBlockWithoutAnyTransferOrItemLoss() {
        Fixture f=new Fixture(); f.add(PoiKind.TOMATO_CHEST,null,wine(9));
        f.inventory[9]=tomato(2,9);
        assertEquals(WorkResult.State.BLOCKED,f.run(new TomatoStorageModule()).state());
        assertTrue(f.transfers.isEmpty()); assertEquals(9,f.held(ItemData.TOMATO));
    }

    @Test void aFullTomatoDestinationFallsBackToAnotherRegisteredTomatoChest() {
        Fixture f=new Fixture(); Pos full=f.add(PoiKind.TOMATO_CHEST,3,
            tomato(0,64),tomato(1,64),tomato(2,64),tomato(3,64));
        Pos available=f.add(PoiKind.TOMATO_CHEST,null); f.inventory[9]=tomato(2,5);
        assertEquals(WorkResult.State.IDLE,f.run(new TomatoStorageModule()).state());
        assertFalse(f.transfers.contains(full)); assertEquals(5,f.stored(available,ItemData.TOMATO));
    }

    @Test void wineCohortConflictAndWrongCommodityDestinationsAreNotRelaxed() {
        Fixture wineFixture=new Fixture(); wineFixture.add(PoiKind.WINE_CHEST,9,wine(8)); wineFixture.inventory[9]=wine(9);
        assertEquals(WorkResult.State.BLOCKED,wineFixture.run(new WineStorageModule()).state());
        assertTrue(wineFixture.transfers.isEmpty()); assertEquals(1,wineFixture.held(ItemData.WINE));
        Fixture tomatoFixture=new Fixture(); tomatoFixture.add(PoiKind.WINE_CHEST,9); tomatoFixture.inventory[9]=tomato(0,5);
        assertEquals(WorkResult.State.BLOCKED,tomatoFixture.run(new TomatoStorageModule()).state());
        assertTrue(tomatoFixture.transfers.isEmpty()); assertEquals(5,tomatoFixture.held(ItemData.TOMATO));
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final ItemData[] inventory=new ItemData[36];
        final Map<Pos,ItemData[]> contents=new LinkedHashMap<>(); final List<Pos> transfers=new ArrayList<>();
        final Context context=new Context(this,this,this,profile,session,() -> this.checkpoints++);
        Pos open; int checkpoints; long tick,ticket;
        ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture() { Arrays.fill(inventory,ItemData.EMPTY); }
        Pos add(PoiKind kind,Integer classifier,ItemData... items) {
            Pos pos=new Pos(contents.size()*2,64,0); ItemData[] slots=new ItemData[4]; Arrays.fill(slots,ItemData.EMPTY);
            System.arraycopy(items,0,slots,0,items.length); contents.put(pos,slots);
            profile.pois.add(new Poi(pos,kind,"warehouse",classifier)); return pos;
        }
        int held(String id) { return Arrays.stream(inventory).filter(i -> i.is(id)).mapToInt(ItemData::count).sum(); }
        int stored(Pos pos,String id) { return Arrays.stream(contents.get(pos)).filter(i -> i.is(id)).mapToInt(ItemData::count).sum(); }
        WorkResult run(AutomationModule module) {
            for(int n=0;n<120;n++) { tick++; WorkResult result=module.tick(context); if(result.state()!=WorkResult.State.BUSY)return result; }
            throw new AssertionError("Storage did not finish within the test bound");
        }
        public long tick() { return tick; }
        public long dayTime() { return 5000; }
        public Integer wineYear() { return 9; }
        public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true); }
        public boolean loaded(Pos pos) { return true; }
        public boolean canStand(Pos pos) { return true; }
        public boolean canTraverse(Pos from,Pos to) { return true; }
        public boolean canInteract(Pos pos,double reach) { return true; }
        public BlockData block(Pos pos) { return new BlockData(pos,"minecraft:barrel",Map.of("container","true")); }
        public List<BlockData> scan(Pos center,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() {
            List<ItemSlot> result=new ArrayList<>(); for(int n=0;n<36;n++)result.add(new ItemSlot(n,n,true,inventory[n])); return result;
        }
        public MenuData menu() {
            List<ItemSlot> result=new ArrayList<>(); int offset=open==null ? 0 : 4;
            if(open!=null)for(int n=0;n<4;n++)result.add(new ItemSlot(n,-1,false,contents.get(open)[n]));
            for(int n=0;n<36;n++)result.add(new ItemSlot(n+offset,n,true,inventory[n]));
            return new MenuData(open==null ? 0 : open.x()+10,(int)tick,result,ItemData.EMPTY,open!=null);
        }
        public boolean mayPlace(int slot,ItemData item) { return open!=null && slot>=0 && slot<4; }
        public boolean busy() { return false; }
        public long submit(Action action) {
            outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
            if(action instanceof Action.UseBlock use) { assertEquals(Action.Use.OPEN_CONTAINER,use.purpose()); open=use.pos(); }
            else if(action instanceof Action.CloseContainer)open=null;
            else if(action instanceof Action.QuickMove move) {
                ItemSlot slot=menu().slot(move.slot()); assertTrue(slot.player(),"Deposit must not withdraw any chest contents");
                ItemData item=slot.item(); int remaining=item.count(); ItemData[] destination=contents.get(open);
                for(int pass=0;pass<2;pass++)for(int n=0;n<destination.length && remaining>0;n++) {
                    ItemData old=destination[n];
                    if(pass==0 && (old.empty() || !ModuleSupport.same(old,item)) || pass==1 && !old.empty())continue;
                    int previous=old.empty()?0:old.count(),moved=Math.min(remaining,64-previous);
                    if(moved>0) { destination[n]=new ItemData(item.id(),previous+moved,item.quality(),item.year(),item.hoe(),item.durability()); remaining-=moved; }
                }
                int moved=item.count()-remaining; assertTrue(moved>0);
                inventory[slot.inventoryIndex()]=remaining==0 ? ItemData.EMPTY : new ItemData(item.id(),remaining,item.quality(),item.year(),item.hoe(),item.durability());
                transfers.add(open); outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"server inventory ACK",moved);
            } else throw new AssertionError("Unexpected storage action: "+action);
            return ++ticket;
        }
        public ActionOutcome outcome(long ticket) { return outcome; }
        public void move(Movement movement) { throw new AssertionError(); }
        public void stopMovement() { }
        public void cancel() { }
        public Result moveTo(Pos target,double reach,Context context) { return Result.ARRIVED; }
        public void reset() { }
    }
}
