package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualTomatoStockTrackerTest {
    @Test void idleInventoryAndAutomatedMenusDoNotInvalidateWarehouse() {
        Fixture f=new Fixture();long epoch=f.cache.invalidationEpoch();
        f.tracker.menu(f.c,new MenuData(0,0,List.of(),ItemData.EMPTY,false),false,null);
        f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(27),true,f.a);
        assertEquals(epoch,f.cache.reusable(f.c).orElseThrow().epoch());
    }
    @Test void manualRegisteredBarrelUseInvalidatesOnlyThatKnownBarrel() {
        Fixture f=new Fixture();f.tracker.use(f.c,f.a);assertTrue(f.cache.reusable(f.c).isEmpty());
        long epoch=f.cache.invalidationEpoch();f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(27),false,null);
        assertEquals(epoch,f.cache.invalidationEpoch());
        assertTrue(f.cache.observeVerified(f.c,f.a,f.items));assertTrue(f.cache.reusable(f.c).isPresent());
    }
    @Test void knownOtherContainerDoesNotClearTomatoStock() {
        Fixture f=new Fixture();f.tracker.use(f.c,new Pos(90,64,0));
        f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(27),false,null);
        assertTrue(f.cache.reusable(f.c).isPresent());
    }
    @Test void unknownOrdinaryMenuInvalidatesAllOnceButSpecialMachineDoesNot() {
        Fixture f=new Fixture();f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(5),false,null);
        assertTrue(f.cache.reusable(f.c).isPresent());
        f.tracker.menu(f.c,null,false,null);f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(27),false,null);
        assertTrue(f.cache.reusable(f.c).isEmpty());long epoch=f.cache.invalidationEpoch();
        f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(27),false,null);assertEquals(epoch,f.cache.invalidationEpoch());
        f.cache.observeVerified(f.c,f.a,f.items);assertTrue(f.cache.reusable(f.c).isEmpty());
        f.cache.observeVerified(f.c,f.b,f.items);assertTrue(f.cache.reusable(f.c).isPresent());
    }
    @Test void manualTakeoverOfPausedAutomationMenuUsesExactOwnedPosition() {
        Fixture f=new Fixture();MenuData menu=TomatoStockSnapshotsTest.menu(27);
        f.tracker.menu(f.c,menu,true,f.a);f.tracker.menu(f.c,menu,true,f.a);
        assertTrue(f.cache.reusable(f.c).isPresent(),"pausing without a slot interaction preserves stock");
        f.tracker.interaction(f.c,menu,f.a);
        assertTrue(f.cache.reusable(f.c).isEmpty());f.cache.observeVerified(f.c,f.a,f.items);
        assertTrue(f.cache.reusable(f.c).isPresent());
    }
    @Test void stalePendingTargetCannotMisidentifyALaterUnknownMenu() {
        Fixture f=new Fixture();f.tracker.use(f.c,new Pos(90,64,0));f.tick+=121;
        f.tracker.menu(f.c,TomatoStockSnapshotsTest.menu(27),false,null);assertTrue(f.cache.reusable(f.c).isEmpty());
    }
    @Test void laterExplicitSlotInteractionRevokesALateAckRefreshInTheSameMenu() {
        Fixture f=new Fixture();MenuData menu=TomatoStockSnapshotsTest.menu(27);
        f.tracker.use(f.c,f.a);f.tracker.menu(f.c,menu,false,null);
        f.cache.observeVerified(f.c,f.a,f.items);assertTrue(f.cache.reusable(f.c).isPresent());
        f.tracker.menu(f.c,menu,false,null);assertTrue(f.cache.reusable(f.c).isPresent(),"polling is deduplicated");
        f.tracker.interaction(f.c,menu,null);assertTrue(f.cache.reusable(f.c).isEmpty(),"explicit manual click is never deduplicated");
        f.cache.observeVerified(f.c,f.a,f.items);assertTrue(f.cache.reusable(f.c).isPresent());
    }
    @Test void unknownSameMenuManualClickRevokesACompleteLateRefreshAgain() {
        Fixture f=new Fixture();MenuData menu=TomatoStockSnapshotsTest.menu(27);f.tracker.menu(f.c,menu,false,null);
        f.cache.observeVerified(f.c,f.a,f.items);f.cache.observeVerified(f.c,f.b,f.items);assertTrue(f.cache.reusable(f.c).isPresent());
        f.tracker.interaction(f.c,menu,null);assertTrue(f.cache.reusable(f.c).isEmpty());
        f.cache.observeVerified(f.c,f.a,f.items);assertTrue(f.cache.reusable(f.c).isEmpty());
    }
    @Test void unknownManualMenuAfterReconnectCannotInheritOldContainerIdentity() {
        Fixture f=new Fixture(),next=new Fixture();f.tracker.use(f.c,new Pos(90,64,0));
        f.tracker.menu(next.c,TomatoStockSnapshotsTest.menu(27),false,null);assertTrue(next.cache.reusable(next.c).isEmpty());
    }
    private static final class Fixture {
        final Pos a=new Pos(0,64,0),b=new Pos(1,64,0);final Profile profile=new Profile();final SessionState session=new SessionState();
        final TomatoStockCache cache=session.tomatoStockCache;final ManualTomatoStockTracker tracker=new ManualTomatoStockTracker();
        final List<ItemData> items=Collections.nCopies(27,ItemData.EMPTY);long tick=100;
        final WorldAccess world=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class<?>[]{WorldAccess.class},(proxy,method,args)->switch(method.getName()) {
            case "tick"->tick;case "dayTime"->24000L;case "loaded"->true;
            case "block"->new BlockData((Pos)args[0],"minecraft:barrel",Map.of());
            default->throw new AssertionError("Manual tracking must not act or read closed inventories: "+method.getName());
        });
        final Context c=new Context(world,null,null,profile,session);
        Fixture() {
            profile.pois.add(new Poi(a,PoiKind.TOMATO_CHEST,"a",null));profile.pois.add(new Poi(b,PoiKind.TOMATO_CHEST,"b",null));
            assertTrue(cache.completeSurvey(c,cache.beginSurvey(c),Map.of(a,items,b,items)));
        }
    }
}
