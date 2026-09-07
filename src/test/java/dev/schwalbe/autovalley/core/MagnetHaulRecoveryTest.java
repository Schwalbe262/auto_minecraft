package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MagnetHaulRecoveryTest {
    @Test void absenceAloneDoesNotClearAnything() {
        Fixture f=new Fixture();
        assertNull(MagnetHaulRecovery.rejection(f.context()));
        assertEquals(Map.of(ItemData.ROTTEN,50),f.session.magnetHaulRemaining);
        assertTrue(f.session.magnetHaulPending);
    }
    @Test void explicitConfirmationClearsOnlyHaulAndKeepsAnAudit() {
        Fixture f=new Fixture();
        MagnetHaulRecovery.acknowledgeByUser(f.context());
        assertFalse(f.session.magnetHaulPending);
        assertTrue(f.session.magnetHaulRemaining.isEmpty());
        assertEquals(Map.of(ItemData.ROTTEN,50),f.session.lastManuallyResolvedHaul);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }
    @Test void carriedInventoryAndGroundHarvestAllRefuseConfirmation() {
        for (int reason=0;reason<5;reason++) {
            Fixture f=new Fixture();
            switch(reason) {
                case 0 -> f.inventory=List.of(new ItemSlot(9,9,true,item(ItemData.ROTTEN)));
                case 1 -> f.inventory=List.of(new ItemSlot(9,9,true,item(ItemData.TOMATO)));
                case 2 -> f.ground=List.of(new GroundItem(1,0,64,0,item(ItemData.ROTTEN)));
                case 3 -> f.cursor=item(ItemData.ROTTEN);
                case 4 -> f.container=true;
            }
            assertThrows(IllegalStateException.class,() -> MagnetHaulRecovery.acknowledgeByUser(f.context()));
            assertTrue(f.session.magnetHaulPending);
            assertEquals(50,f.session.magnetHaulRemaining.get(ItemData.ROTTEN));
        }
    }
    @Test void otherInventoryItemsAreNotRemoved() {
        Fixture f=new Fixture();
        f.inventory=List.of(new ItemSlot(9,9,true,item("minecraft:diamond")));
        MagnetHaulRecovery.acknowledgeByUser(f.context());
        assertEquals("minecraft:diamond",f.inventory.get(0).item().id());
    }
    @Test void separateMachineDebtCannotBeClearedByManualHaulConfirmation() {
        Fixture f=new Fixture();
        var debt=new PendingMachineOutput("pending",Feature.WINE,new Pos(1,64,0),1,1,1,
            PendingMachineOutput.Phase.AWAITING_PICKUP);
        f.profile.pendingMachineOutputs.put(debt.id(),debt);
        assertThrows(IllegalStateException.class,() -> MagnetHaulRecovery.acknowledgeByUser(f.context()));
        assertEquals(debt,f.profile.pendingMachineOutputs.get(debt.id()));
        assertTrue(f.session.magnetHaulPending);
    }
    private static ItemData item(String id) { return new ItemData(id,1,0,null,false,0); }
    private static final class Fixture {
        final SessionState session=new SessionState();
        final Profile profile=new Profile();
        List<ItemSlot> inventory=List.of(); List<GroundItem> ground=List.of();
        ItemData cursor=ItemData.EMPTY; boolean container;
        Fixture() { session.magnetHaulPending=true; session.magnetHaulRemaining.put(ItemData.ROTTEN,50); }
        Context context() {
            WorldAccess world=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class[]{WorldAccess.class},(proxy,method,args) -> switch(method.getName()) {
                case "player" -> new PlayerState(0,64,0,0,0,true,false,20,20,0,true,true);
                case "inventory" -> inventory;
                case "groundItems" -> ground;
                case "menu" -> new MenuData(0,0,List.of(),cursor,container);
                default -> throw new UnsupportedOperationException(method.getName());
            });
            return new Context(world,null,null,profile,session);
        }
    }
}
