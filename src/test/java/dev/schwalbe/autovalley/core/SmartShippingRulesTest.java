package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.SmartShippingRules.*;

class SmartShippingRulesTest {
    @Test void exactInstalledMetadataHasFiftyFourSlots() {
        assertEquals(54,expectedSlots(BLOCK_ID,ENTITY_CLASS,INVENTORY_FIELD_TYPE,ATTACHMENT_CLASS,true,true,54));
    }
    @Test void otherScriptedBlocksBanksAndNormalChestsDoNotReceiveThisPermission() {
        for(String id:Arrays.asList(null,"minecraft:chest","numismatics:bank","shippingbin:shipping_bin","other:smart_shipping_bin"))
            assertEquals(0,expectedSlots(id,ENTITY_CLASS,INVENTORY_FIELD_TYPE,ATTACHMENT_CLASS,true,true,54));
    }
    @Test void UnknownEntityOrInventoryImplementationsFailClosed() {
        assertEquals(0,expectedSlots(BLOCK_ID,"other.BlockEntityJS",INVENTORY_FIELD_TYPE,ATTACHMENT_CLASS,true,true,54));
        assertEquals(0,expectedSlots(BLOCK_ID,ENTITY_CLASS,"net.minecraft.world.Container",ATTACHMENT_CLASS,true,true,54));
        assertEquals(0,expectedSlots(BLOCK_ID,ENTITY_CLASS,INVENTORY_FIELD_TYPE,"net.minecraft.world.SimpleContainer",true,true,54));
        assertFalse(supportedOwner(BLOCK_ID,null));
    }
    @Test void NativeContainerOwnershipAndExactCapacityAreRequired() {
        assertEquals(0,expectedSlots(BLOCK_ID,ENTITY_CLASS,INVENTORY_FIELD_TYPE,ATTACHMENT_CLASS,false,true,54));
        assertEquals(0,expectedSlots(BLOCK_ID,ENTITY_CLASS,INVENTORY_FIELD_TYPE,ATTACHMENT_CLASS,true,false,54));
        for(int capacity:new int[]{-1,0,27,53,55,90})
            assertEquals(0,expectedSlots(BLOCK_ID,ENTITY_CLASS,INVENTORY_FIELD_TYPE,ATTACHMENT_CLASS,true,true,capacity));
    }
    @Test void RecordedFiftyFourStoragePlusThirtySixPlayerLayoutMatches() {
        MenuData menu=menu(); assertTrue(matchesMenu(menu));
        assertEquals(11,menu.slot(56).inventoryIndex()); assertTrue(menu.slot(56).player());
        assertFalse(menu.slot(0).player());
    }
    @Test void ContentsDoNotDetermineShapeOrAuthorizeAnyProduct() {
        List<ItemSlot> slots=new ArrayList<>(menu().slots());
        slots.set(0,new ItemSlot(0,-1,false,new ItemData("minecraft:diamond",64,0,null,false,0)));
        slots.set(56,new ItemSlot(56,11,true,new ItemData(ItemData.PINE_TAR,54,0,null,false,0)));
        assertTrue(matchesMenu(new MenuData(3,1,slots,ItemData.EMPTY,true)));
    }
    @Test void TruncatedExtraAndNonContainerMenusAreRejected() {
        MenuData menu=menu();
        assertFalse(matchesMenu(null)); assertFalse(matchesMenu(new MenuData(0,0,menu.slots(),ItemData.EMPTY,false)));
        assertFalse(matchesMenu(new MenuData(3,0,menu.slots().subList(0,89),ItemData.EMPTY,true)));
        List<ItemSlot> extra=new ArrayList<>(menu.slots()); extra.add(new ItemSlot(90,36,true,ItemData.EMPTY));
        assertFalse(matchesMenu(new MenuData(3,0,extra,ItemData.EMPTY,true)));
    }
    @Test void DuplicateSlotOrPlayerInventoryIndicesCannotImpersonateFullShape() {
        List<ItemSlot> duplicate=new ArrayList<>(menu().slots()); duplicate.set(1,duplicate.get(0));
        assertFalse(matchesMenu(new MenuData(3,0,duplicate,ItemData.EMPTY,true)));
        duplicate=new ArrayList<>(menu().slots()); duplicate.set(55,new ItemSlot(55,9,true,ItemData.EMPTY));
        assertFalse(matchesMenu(new MenuData(3,0,duplicate,ItemData.EMPTY,true)));
    }
    @Test void ShiftedStoragePlayerBoundaryIsRejected() {
        List<ItemSlot> shifted=new ArrayList<>(menu().slots());
        shifted.set(53,new ItemSlot(53,9,true,ItemData.EMPTY)); shifted.set(54,new ItemSlot(54,-1,false,ItemData.EMPTY));
        assertFalse(matchesMenu(new MenuData(3,0,shifted,ItemData.EMPTY,true)));
    }
    private MenuData menu() {
        List<ItemSlot> slots=new ArrayList<>();
        for(int i=0;i<54;i++) slots.add(new ItemSlot(i,-1,false,ItemData.EMPTY));
        for(int i=0;i<36;i++) slots.add(new ItemSlot(54+i,i<27?i+9:i-27,true,ItemData.EMPTY));
        return new MenuData(3,0,slots,ItemData.EMPTY,true);
    }
}
