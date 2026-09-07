package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TomatoStorageRulesTest {
    private static ItemData tomato(int grade) { return new ItemData(ItemData.TOMATO,3,grade,null,false,999); }
    private static MenuData menu(ItemData... contents) {
        List<ItemSlot> slots=new ArrayList<>();
        for(int n=0;n<contents.length;n++)slots.add(new ItemSlot(n,-1,false,contents[n]));
        slots.add(new ItemSlot(contents.length,0,true,new ItemData("minecraft:diamond_sword",1,0,null,false,999)));
        return new MenuData(12,1,slots,ItemData.EMPTY,true);
    }
    @Test void allTomatoQualitiesCanTransferIntoEmptyOrMixedGradeTomatoStorage() {
        for(int grade:new int[]{-1,0,1,2,3,99}) {
            assertTrue(TomatoStorageRules.permitsTransfer(tomato(grade),menu(ItemData.EMPTY)));
            assertTrue(TomatoStorageRules.permitsTransfer(tomato(grade),menu(tomato(0),tomato(3),tomato(-1),ItemData.EMPTY)));
        }
    }
    @Test void nonTomatoesAndEmptySourcesAreAlwaysRejected() {
        for(String id:List.of(ItemData.ROTTEN,ItemData.WINE,ItemData.PRESERVES,"example:tomato","minecraft:stone"))
            assertFalse(TomatoStorageRules.permitsTransfer(new ItemData(id,1,0,null,false,999),menu(ItemData.EMPTY)));
        assertFalse(TomatoStorageRules.permitsTransfer(ItemData.EMPTY,menu(ItemData.EMPTY)));
        assertFalse(TomatoStorageRules.permitsTransfer(null,menu(ItemData.EMPTY)));
    }
    @Test void everyStorageSlotMustBeTomatoOrEmptyIncludingTheLastDoubleChestSlot() {
        ItemData[] contents=new ItemData[54]; Arrays.fill(contents,ItemData.EMPTY); contents[0]=tomato(2);
        assertTrue(TomatoStorageRules.permitsTransfer(tomato(0),menu(contents)));
        contents[53]=new ItemData(ItemData.ROTTEN,1,0,null,false,999);
        assertFalse(TomatoStorageRules.permitsTransfer(tomato(0),menu(contents)));
    }
    @Test void closedInventoryAndOccupiedCursorNeverPermitAStorageTransfer() {
        MenuData open=menu(tomato(3));
        assertFalse(TomatoStorageRules.permitsTransfer(tomato(0),null));
        assertFalse(TomatoStorageRules.permitsTransfer(tomato(0),new MenuData(0,0,open.slots(),ItemData.EMPTY,false)));
        assertFalse(TomatoStorageRules.permitsTransfer(tomato(0),new MenuData(12,1,open.slots(),tomato(1),true)));
    }
}
