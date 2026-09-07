package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WineSaleRulesTest {
    private static final Pos FIRST=new Pos(0,64,0), SECOND=new Pos(1,64,0);
    private static ItemData wine(int year,int count) { return new ItemData(ItemData.WINE,count,2,year,false,999); }

    @Test void validVerifiedAllowanceAcceptsOnlyTheCoveredHeldWineQuantity() {
        Fixture f=new Fixture();
        assertTrue(WineSaleRules.permitted(wine(8,64),f.context));
        assertTrue(WineSaleRules.permitted(wine(8,1),f.context));
        assertFalse(WineSaleRules.permitted(wine(8,65),f.context));
        assertFalse(WineSaleRules.permitted(new ItemData(ItemData.TOMATO,1,0,null,false,999),f.context));
    }

    @Test void absentAllowanceDisabledFeatureAndWrongPermitCohortRejectSales() {
        Fixture absent=new Fixture(); absent.session.wineSalePermits.clear();
        assertFalse(WineSaleRules.permitted(wine(8,1),absent.context));
        Fixture disabled=new Fixture(); disabled.profile.enabled.put(Feature.WINE_SURPLUS_SHIPPING,false);
        assertFalse(WineSaleRules.permitted(wine(8,1),disabled.context));
        Fixture wrong=new Fixture(); wrong.session.wineSalePermits.put(8,new WineSalePermit(7,64,100,294,Set.of(FIRST,SECOND)));
        assertFalse(WineSaleRules.permitted(wine(8,1),wrong.context));
    }

    @Test void everyRegisteredCohortReserveMustMatchTheVerifiedSetAndRemainLoaded() {
        Fixture added=new Fixture(); added.profile.pois.add(new Poi(new Pos(2,64,0),PoiKind.WINE_CHEST,"new",8));
        assertFalse(WineSaleRules.permitted(wine(8,1),added.context));
        Fixture removed=new Fixture(); removed.profile.pois.remove(0);
        assertFalse(WineSaleRules.permitted(wine(8,1),removed.context));
        Fixture absent=new Fixture(); absent.profile.pois.clear();
        assertFalse(WineSaleRules.permitted(wine(8,1),absent.context));
        Fixture unloaded=new Fixture(); unloaded.unloaded.add(SECOND);
        assertFalse(WineSaleRules.permitted(wine(8,1),unloaded.context));
        Fixture changed=new Fixture(); changed.profile.pois.set(1,new Poi(SECOND,PoiKind.WINE_CHEST,"aged differently",7));
        assertFalse(WineSaleRules.permitted(wine(8,1),changed.context));
    }

    @Test void anotherAgeGroupRegistrationDoesNotInvalidateTheCoveredCohort() {
        Fixture f=new Fixture(); f.profile.pois.add(new Poi(new Pos(2,64,0),PoiKind.WINE_CHEST,"other age",7));
        assertTrue(WineSaleRules.permitted(wine(8,1),f.context));
    }

    @Test void expirationTickRollbackAndDayChangeAllInvalidateTheAllowance() {
        Fixture expired=new Fixture(); expired.ticks=1300;
        assertFalse(WineSaleRules.permitted(wine(8,1),expired.context),"1200tick allowance has expired at its deadline");
        Fixture rollback=new Fixture(); rollback.ticks=99;
        assertFalse(WineSaleRules.permitted(wine(8,1),rollback.context));
        Fixture day=new Fixture(); day.dayTime+=24000;
        assertFalse(WineSaleRules.permitted(wine(8,1),day.context));
    }

    @Test void nativeWineClockIsRequiredAndFutureBirthCannotMasqueradeAsFreshWine() {
        Fixture unknown=new Fixture(); unknown.wineClock=null;
        assertFalse(WineSaleRules.permitted(wine(8,1),unknown.context));
        Fixture future=new Fixture(); future.wineClock=7;
        assertFalse(WineSaleRules.permitted(wine(8,1),future.context));
        Fixture missing=new Fixture();
        assertFalse(WineSaleRules.permitted(new ItemData(ItemData.WINE,1,0,null,false,999),missing.context));
        assertFalse(WineSaleRules.permitted(wine(-1,1),missing.context));
        assertTrue(WineSaleRules.permitted(wine(8,1),missing.context),"dayTime294 still uses native Vinery year8 for fresh age0 wine");
    }

    @Test void consumingConfirmedQuantityCannotIncreaseOrMakeAllowanceNegative() {
        Fixture f=new Fixture(); WineSaleRules.consume(wine(8,64),7,f.context);
        assertEquals(57,f.session.wineSalePermits.get(8).inventoryLimit());
        WineSaleRules.consume(wine(8,64),0,f.context); WineSaleRules.consume(wine(8,64),-3,f.context);
        WineSaleRules.consume(wine(7,64),5,f.context);
        WineSaleRules.consume(new ItemData(ItemData.TOMATO,64,0,null,false,999),10,f.context);
        assertEquals(57,f.session.wineSalePermits.get(8).inventoryLimit());
        assertFalse(WineSaleRules.permitted(wine(8,58),f.context));
        WineSaleRules.consume(wine(8,64),1000,f.context);
        assertFalse(f.session.wineSalePermits.containsKey(8));
        assertFalse(WineSaleRules.permitted(wine(8,1),f.context));
    }

    private static final class Fixture implements WorldAccess {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final Set<Pos> unloaded=new HashSet<>(); final Context context=new Context(this,null,null,profile,session);
        long ticks=110, dayTime=294*24000L+13000; Integer wineClock=8;
        Fixture() {
            profile.pois.add(new Poi(FIRST,PoiKind.WINE_CHEST,"reserve1",8));
            profile.pois.add(new Poi(SECOND,PoiKind.WINE_CHEST,"reserve2",8));
            session.wineSalePermits.put(8,new WineSalePermit(8,64,100,294,Set.of(FIRST,SECOND)));
        }
        @Override public long tick() { return ticks; }
        @Override public long dayTime() { return dayTime; }
        @Override public Integer wineYear() { return wineClock; }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true); }
        @Override public BlockData block(Pos p) { return new BlockData(p,"minecraft:barrel",Map.of()); }
        @Override public boolean loaded(Pos p) { return !unloaded.contains(p); }
        @Override public boolean canStand(Pos p) { return true; }
        @Override public boolean canTraverse(Pos a,Pos b) { return true; }
        @Override public List<BlockData> scan(Pos p,int r,int v) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return List.of(); }
        @Override public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        @Override public boolean mayPlace(int slot,ItemData item) { return true; }
    }
}
