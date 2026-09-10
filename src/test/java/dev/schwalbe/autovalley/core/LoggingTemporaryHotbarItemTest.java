package dev.schwalbe.autovalley.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingTemporaryHotbarItemTest {
    private static ItemData item(String id,int count){return new ItemData(id,count,0,null,false,999);}
    @Test void onlyVacancyAndTheFiveExistingLoggingProductsMayOccupyItsTemporarySlot() {
        assertTrue(LoggingRules.temporaryHotbarItem(ItemData.EMPTY));
        for(String id:List.of(LoggingRules.SAPLING,LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.TWIG,LoggingRules.BERRY)) {
            assertTrue(LoggingRules.temporaryHotbarItem(item(id,1)));
            assertTrue(LoggingRules.temporaryHotbarItem(item(id,64)));
            assertFalse(LoggingRules.temporaryHotbarItem(item(id,65)));
        }
    }
    @Test void similarSpeciesToolsCropsWineAndArbitraryBlocksRemainOutsideTheAllowance() {
        assertFalse(LoggingRules.temporaryHotbarItem(null));
        for(String id:List.of("minecraft:oak_log","minecraft:birch_sapling",LoggingRules.CHOPPED_LOG,LoggingRules.AXE,
                "society:galaxy_sword","minecraft:stone",ItemData.TOMATO,ItemData.WINE,ItemData.ANCIENT_WINE,FruitRules.ITEM))
            assertFalse(LoggingRules.temporaryHotbarItem(item(id,1)),id);
    }
    @Test void theCustodyAllowanceDoesNotGrantDisposalOrPlantingPermission() {
        for(String id:List.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.BERRY)) {
            ItemData pickedUp=item(id,34);assertTrue(LoggingRules.temporaryHotbarItem(pickedUp));
            assertFalse(LoggingRules.waste(pickedUp));assertFalse(pickedUp.is(LoggingRules.SAPLING));
        }
    }
}
