package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InventoryAcknowledgementsTest {
    private ItemData tomato(int count) { return new ItemData(ItemData.TOMATO,count,0,null,false,Integer.MAX_VALUE); }
    @Test void immutableReplyProvesTransferEvenIfLiveInventoryWouldBeRefilled() {
        MenuData before=new MenuData(1,3,List.of(new ItemSlot(0,-1,false,ItemData.EMPTY),new ItemSlot(1,0,true,tomato(64))),ItemData.EMPTY,true);
        List<ItemData> reply=List.of(tomato(64),ItemData.EMPTY);
        assertEquals(64,InventoryAcknowledgements.removed(before,reply,1));
        assertEquals(64,InventoryAcknowledgements.destinationIncrease(before,reply,tomato(64)));
        assertTrue(InventoryAcknowledgements.changed(before,reply));
    }
    @Test void partialTransfersUseTheExactAcknowledgedQuantity() {
        MenuData before=new MenuData(1,3,List.of(new ItemSlot(0,-1,false,tomato(48)),new ItemSlot(1,0,true,tomato(64))),ItemData.EMPTY,true);
        List<ItemData> reply=List.of(tomato(64),tomato(48));
        assertEquals(16,InventoryAcknowledgements.removed(before,reply,1));
        assertEquals(16,InventoryAcknowledgements.destinationIncrease(before,reply,tomato(64)));
        assertEquals(0,InventoryAcknowledgements.removed(before,List.of(tomato(64)),1));
    }
    @Test void unrelatedDestinationDepositDoesNotProveOurSourceMoved() {
        MenuData before=new MenuData(1,3,List.of(new ItemSlot(0,-1,false,ItemData.EMPTY),new ItemSlot(1,0,true,tomato(64))),ItemData.EMPTY,true);
        assertEquals(0,InventoryAcknowledgements.removed(before,List.of(tomato(64),tomato(64)),1));
    }
}
