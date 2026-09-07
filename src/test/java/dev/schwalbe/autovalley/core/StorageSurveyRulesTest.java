package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.StorageSurveyObservation.Status;

class StorageSurveyRulesTest {
    private static final Pos POS=new Pos(0,64,0);
    private ItemData item(String id,int count,int quality,Integer cohort) {
        return new ItemData(id,count,quality,cohort,false,0);
    }
    private StorageSurveyObservation inspect(ItemData... items) {
        List<ItemSlot> slots=new ArrayList<>();
        for (int i=0;i<items.length;i++) slots.add(new ItemSlot(i,-1,false,items[i]));
        return StorageSurveyRules.inspect(POS,100,slots);
    }
    @Test void pureTomatoAggregatesAllStacks() {
        var result=inspect(item(ItemData.TOMATO,64,2,null),item(ItemData.TOMATO,7,2,null),ItemData.EMPTY);
        assertEquals(Status.TOMATO,result.status()); assertEquals(2,result.classifier());
        assertEquals(PoiKind.TOMATO_CHEST,result.classifiedKind()); assertEquals(71,result.contents().get(0).count());
        assertEquals(3,result.storageSlots()); assertEquals(1,result.emptySlots());
    }
    @Test void knownWineCohortAllowsDifferentQualities() {
        var result=inspect(item(ItemData.WINE,3,1,8),item(ItemData.WINE,4,3,8));
        assertEquals(Status.WINE,result.status()); assertEquals(8,result.classifier());
        assertEquals(PoiKind.WINE_CHEST,result.classifiedKind()); assertEquals(2,result.contents().size());
    }
    @Test void emptyDoesNotGuess() {
        var result=inspect(ItemData.EMPTY); assertEquals(Status.EMPTY,result.status());
        assertNull(result.classifier()); assertNull(result.classifiedKind());
    }
    @Test void differentGradesOrCohortsAreMixed() {
        assertEquals(Status.MIXED,inspect(item(ItemData.TOMATO,1,0,null),item(ItemData.TOMATO,1,1,null)).status());
        assertEquals(Status.MIXED,inspect(item(ItemData.WINE,1,1,7),item(ItemData.WINE,1,1,8)).status());
    }
    @Test void otherItemsAndMixedProductsNeverClassify() {
        var other=inspect(item("minecraft:stone",64,0,null));
        assertEquals(Status.OTHER,other.status()); assertNull(other.classifiedKind());
        var mixed=inspect(item(ItemData.TOMATO,1,1,null),item("minecraft:stone",64,0,null));
        assertEquals(Status.MIXED,mixed.status()); assertNull(mixed.classifiedKind());
    }
    @Test void missingOrMalformedMetadataStaysUnknown() {
        for (var item:List.of(item(ItemData.WINE,1,1,null),item(ItemData.WINE,1,1,-1),
                item(ItemData.TOMATO,1,-1,null),item(ItemData.TOMATO,1,4,null))) {
            var result=inspect(item); assertEquals(Status.UNKNOWN,result.status()); assertNull(result.classifiedKind());
        }
    }
    @Test void playerInventoryIsNotPartOfWarehouse() {
        var result=StorageSurveyRules.inspect(POS,2,List.of(new ItemSlot(0,-1,false,ItemData.EMPTY),
            new ItemSlot(1,0,true,item(ItemData.TOMATO,64,3,null))));
        assertEquals(Status.EMPTY,result.status()); assertEquals(1,result.storageSlots());
    }
    @Test void allFiftyFourSlotsAreReadAndResultIsImmutable() {
        List<ItemSlot> slots=new ArrayList<>();
        for(int i=0;i<54;i++) slots.add(new ItemSlot(i,-1,false,i==53 ? item(ItemData.TOMATO,9,3,null) : ItemData.EMPTY));
        var result=StorageSurveyRules.inspect(POS,2,slots);
        assertEquals(54,result.storageSlots()); assertEquals(53,result.emptySlots()); assertEquals(3,result.classifier());
        assertThrows(UnsupportedOperationException.class,() -> result.contents().clear());
    }
    @Test void malformedMenusCannotProduceClassification() {
        var slot=new ItemSlot(0,-1,false,ItemData.EMPTY);
        assertThrows(IllegalArgumentException.class,() -> StorageSurveyRules.inspect(POS,2,List.of(slot,slot)));
        assertThrows(IllegalArgumentException.class,() -> inspect(item(ItemData.TOMATO,-1,0,null)));
        assertThrows(IllegalArgumentException.class,() -> inspect(item("minecraft:air",2,0,null)));
    }
    @Test void onlyExactOrdinaryStorageCanBeOpened() {
        for(String id:List.of("minecraft:barrel","minecraft:chest","minecraft:trapped_chest"))
            assertTrue(StorageSurveyRules.ordinaryStorage(new BlockData(POS,id,Map.of("container","true"))));
        assertFalse(StorageSurveyRules.ordinaryStorage(new BlockData(POS,"numismatics:bank",Map.of("container","true"))));
        assertFalse(StorageSurveyRules.ordinaryStorage(new BlockData(POS,"minecraft:barrel",Map.of())));
        assertFalse(StorageSurveyRules.ordinaryStorage(null));
    }
}
