package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MagnetCapacityPolicyTest {
    @Test void twentyContinuousFreeCapacityTicksReleaseOnlyEphemeralTrackingWithoutClaimingDelivery() {
        Fixture f=new Fixture(); Map<String,Integer> haul=Map.copyOf(f.session.magnetHaulRemaining);
        f.session.lastManuallyResolvedHaul=Map.of(ItemData.ROTTEN,9);
        for(int n=0;n<19;n++) { assertFalse(f.next()); assertTrue(f.session.magnetHaulPending); }
        assertTrue(f.next()); assertFalse(f.session.magnetHaulPending); assertTrue(f.session.magnetHaulRemaining.isEmpty());
        assertEquals(haul,f.session.lastCapacityReleasedHaul); assertEquals(20,f.session.lastCapacityReleaseTick);
        assertEquals(Map.of(ItemData.ROTTEN,9),f.session.lastManuallyResolvedHaul);
        assertEquals("free_capacity_released_ephemeral_tracking_not_delivery",f.session.magnetCapacityStatus);
        assertFalse(f.next(),"a released haul cannot be counted twice");
    }

    @Test void fullInventoryHoldsTrackingAndOnlyAnActualEmptyNormalPlayerSlotCounts() {
        for (ItemSlot excluded:List.of(new ItemSlot(40,40,true,ItemData.EMPTY),new ItemSlot(36,36,true,ItemData.EMPTY),
            new ItemSlot(0,0,false,ItemData.EMPTY),new ItemSlot(1,-1,true,ItemData.EMPTY))) {
            Fixture f=new Fixture(); f.inventory=new ArrayList<>();
            for(int n=0;n<36;n++) f.inventory.add(new ItemSlot(n,n,true,item("minecraft:stone",64)));
            f.inventory.add(excluded);
            for(int n=0;n<60;n++) assertFalse(f.next());
            assertTrue(f.session.magnetHaulPending); assertFalse(f.session.magnetHaulRemaining.isEmpty());
            f.inventory.set(35,new ItemSlot(35,35,true,ItemData.EMPTY));
            for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
        }
    }

    @Test void occupiedOrMissingCursorEvidenceResetsTheEntireSettlingWindow() {
        for(boolean unavailable:List.of(false,true)) {
            Fixture f=new Fixture(); for(int n=0;n<19;n++) assertFalse(f.next());
            f.menuAvailable=!unavailable; f.cursor=item(ItemData.TOMATO,1); assertFalse(f.next());
            f.menuAvailable=true; f.cursor=ItemData.EMPTY;
            for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
        }
    }

    @Test void disconnectTickGapAndClockRollbackCannotReuseAnEarlierPartialWindow() {
        for(String interruption:List.of("disconnect","gap","rollback")) {
            Fixture f=new Fixture(); for(int n=0;n<19;n++) assertFalse(f.next());
            if(interruption.equals("disconnect")) { f.connected=false; assertFalse(f.next()); f.connected=true; }
            if(interruption.equals("gap")) f.tick+=100;
            if(interruption.equals("rollback")) f.tick=0;
            for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
        }
    }

    @Test void repeatedPollingWithinOneTickDoesNotManufactureTwentyTicks() {
        Fixture f=new Fixture(); assertFalse(f.next());
        for(int n=0;n<100;n++) assertFalse(MagnetCapacityPolicy.observe(f.context()));
        assertTrue(f.session.magnetHaulPending);
        for(int n=0;n<18;n++) assertFalse(f.next()); assertTrue(f.next());
    }

    @Test void reconnectWithANewSessionStartsAFreshWindowEvenWhenTheClockIsContinuous() {
        Fixture f=new Fixture(); for(int n=0;n<19;n++) assertFalse(f.next());
        SessionState replacement=new SessionState(); replacement.magnetHaulPending=true;
        replacement.magnetHaulRemaining.putAll(f.session.magnetHaulRemaining); f.session=replacement;
        for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
    }

    @Test void activeHarvestAndObservedGroundItemsDoNotOverrideTheUserCapacityPolicy() {
        Fixture f=new Fixture(); f.session.oneShotFeature=Feature.HARVEST; f.container=true;
        f.inventory=List.of(new ItemSlot(0,0,true,item(ItemData.TOMATO,64)),new ItemSlot(1,1,true,ItemData.EMPTY));
        // The world proxy rejects groundItems(): this policy must not infer anything from disappearing drops or their count.
        for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
        assertEquals(Feature.HARVEST,f.session.oneShotFeature); assertTrue(f.container);
        assertEquals(64,f.inventory.get(0).item().count());
    }

    @Test void durableMachineDebtSalePermissionsAndActionAcknowledgementsAreOutsideThisPolicy() {
        Fixture f=new Fixture();
        PendingMachineOutput debt=new PendingMachineOutput(UUID.randomUUID().toString(),Feature.WINE,new Pos(1,64,0),4,2,1,PendingMachineOutput.Phase.AWAITING_PICKUP);
        f.profile.pendingMachineOutputs.put(debt.id(),debt); f.session.liveMachineOutputs.add(debt.id()); f.session.activeMachineOutputId=debt.id();
        WineSalePermit permit=new WineSalePermit(2,5,1,4,Set.of(new Pos(2,64,0))); f.session.wineSalePermits.put(2,permit);
        f.profile.nextEligibleDay.put("wine:1:64:0",10L);
        for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
        assertEquals(Map.of(debt.id(),debt),f.profile.pendingMachineOutputs); assertEquals(Set.of(debt.id()),f.session.liveMachineOutputs);
        assertEquals(debt.id(),f.session.activeMachineOutputId); assertEquals(Map.of(2,permit),f.session.wineSalePermits);
        assertEquals(Map.of("wine:1:64:0",10L),f.profile.nextEligibleDay); assertTrue(f.profile.machineOutputResolutions.isEmpty());
        assertEquals(0,f.checkpoints,"capacity tracking is ephemeral and must not checkpoint durable state");
    }

    @Test void eachNewOverflowStartsItsOwnWindowAndFullInventoryResetsAnExistingWindow() {
        Fixture f=new Fixture(); for(int n=0;n<20;n++) f.next();
        for(int n=0;n<50;n++) assertFalse(f.next());
        f.session.magnetHaulPending=true; f.session.magnetHaulRemaining.put(ItemData.ROTTEN,3);
        for(int n=0;n<19;n++) assertFalse(f.next());
        f.inventory=List.of(new ItemSlot(0,0,true,item("minecraft:stone",64))); assertFalse(f.next());
        f.inventory=List.of(new ItemSlot(0,0,true,ItemData.EMPTY));
        for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
        assertEquals(Map.of(ItemData.ROTTEN,3),f.session.lastCapacityReleasedHaul);
    }

    @Test void mapOnlyAndFlagOnlyTrackingBothRespectTheSameCapacityWindow() {
        for(boolean mapOnly:List.of(false,true)) {
            Fixture f=new Fixture(); if(mapOnly)f.session.magnetHaulPending=false;else f.session.magnetHaulRemaining.clear();
            for(int n=0;n<19;n++) assertFalse(f.next()); assertTrue(f.next());
            assertFalse(f.session.magnetHaulPending); assertTrue(f.session.magnetHaulRemaining.isEmpty());
        }
    }

    private static ItemData item(String id,int count) { return new ItemData(id,count,0,null,false,999); }
    private static final class Fixture {
        SessionState session=new SessionState(); final Profile profile=new Profile();
        List<ItemSlot> inventory=List.of(new ItemSlot(0,0,true,ItemData.EMPTY));
        ItemData cursor=ItemData.EMPTY; boolean connected=true,menuAvailable=true,container;
        long tick; int checkpoints;
        Fixture() { session.magnetHaulPending=true; session.magnetHaulRemaining.put(ItemData.TOMATO,52); session.magnetHaulRemaining.put(ItemData.ROTTEN,4); }
        boolean next() { tick++; return MagnetCapacityPolicy.observe(context()); }
        Context context() {
            WorldAccess world=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class[]{WorldAccess.class},(proxy,method,args) -> switch(method.getName()) {
                case "tick" -> tick;
                case "player" -> new PlayerState(0,64,0,0,0,true,false,20,20,0,connected,true);
                case "inventory" -> inventory;
                case "menu" -> menuAvailable ? new MenuData(0,0,List.of(),cursor,container) : null;
                default -> throw new AssertionError("Capacity policy must not access "+method.getName());
            });
            return new Context(world,null,null,profile,session,() -> checkpoints++);
        }
    }
}
