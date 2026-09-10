package dev.schwalbe.autovalley.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CrystalCollectionTest {
    @Test void installedCatalogHasExactBaseAndSeparatePristineOutputs() {
        assertEquals(56,CrystalCollection.BASE_OUTPUT_IDS.size());assertEquals(112,CrystalCollection.OUTPUT_IDS.size());
        for(String id:List.of("society:ruby","minecraft:diamond","society:spinel","minecraft:quartz")) {
            assertTrue(CrystalCollection.BASE_OUTPUT_IDS.contains(id));
            assertTrue(CrystalCollection.OUTPUT_IDS.contains("society:pristine_"+id.substring(id.indexOf(':')+1)));
        }
        for(String id:List.of("society:black_opal","minecraft:coal","society:ancient_fruit","other:jade","society:pristine_fake"))
            assertFalse(CrystalCollection.OUTPUT_IDS.contains(id));
        assertFalse(CrystalCollection.BASE_OUTPUT_IDS.contains("society:pristine_jade"));
        assertThrows(UnsupportedOperationException.class,()->CrystalCollection.OUTPUT_IDS.add("minecraft:coal"));
    }
    @Test void actualOutputStorageMembershipStillBoundsDepositItems() {
        Profile p=new Profile();ArtisanJob job=job();
        p.commodityStores.put("jade_stock",new CommodityStore("jade_stock","Custom",Set.of("society:ruby","minecraft:egg","society:pristine_ruby"),List.of(new Pos(1,64,0))));
        assertEquals(Set.of("society:ruby","society:pristine_ruby"),CrystalCollection.outputIds(p,job));
        p.commodityStores.clear();assertTrue(CrystalCollection.outputIds(p,job).isEmpty());
    }
    @Test void inspectionCalendarIsIndependentOfLegacyJadeProductionAndSeedMaker() {
        ArtisanJob old=job();ArtisanJob current=new ArtisanJob(old.id(),ArtisanRecipe.CRYSTAL_COLLECTION.id(),old.machines(),"unused","jade_stock");
        Pos pos=old.machines().get(0);
        assertEquals(CrystalCollection.scheduleKey(old,pos),CrystalCollection.scheduleKey(current,pos));
        assertNotEquals(old.scheduleKey(pos),CrystalCollection.scheduleKey(old,pos));
        assertThrows(IllegalArgumentException.class,()->CrystalCollection.scheduleKey(old,new Pos(99,64,0)));
    }
    @Test void narrowLegacyMigrationKeepsLocationsSchedulesAndManualOff() {
        Profile p=legacy();ArtisanJob old=job();Pos pos=old.machines().get(0);
        p.nextEligibleDay.put(old.scheduleKey(pos),999L);p.nextEligibleDay.put("harvest:ancient",1111L);
        Map<Feature,Boolean> enabled=new EnumMap<>(p.enabled);Map<String,Long> dates=new LinkedHashMap<>(p.nextEligibleDay);
        CrystalCollectionMigration.migrate(p);
        assertEquals(ArtisanRecipe.CRYSTAL_COLLECTION,p.artisanJobs.get(old.id()).recipe());
        assertEquals(old.machines(),p.artisanJobs.get(old.id()).machines());
        assertEquals(CrystalCollection.BASE_OUTPUT_IDS,p.commodityStores.get("jade_stock").items());
        assertEquals(List.of(new Pos(1,64,0)),p.commodityStores.get("jade_stock").containers());
        assertEquals(enabled,p.enabled);assertEquals(dates,p.nextEligibleDay);
        CommodityStore migrated=p.commodityStores.get("jade_stock");CrystalCollectionMigration.migrate(p);
        assertSame(migrated,p.commodityStores.get("jade_stock"));
    }
    @Test void customizedOrSharedStoresAreNotSilentlyBroadened() {
        for(String customization:List.of("name","items","shared-job","physical-alias","possible-chest-half")) {
            Profile p=legacy();CommodityStore old=p.commodityStores.get("jade_stock");
            switch(customization) {
                case "name" -> p.commodityStores.put(old.id(),new CommodityStore(old.id(),"My jade only",old.items(),old.containers()));
                case "items" -> p.commodityStores.put(old.id(),new CommodityStore(old.id(),old.name(),Set.of("society:jade","society:ruby"),old.containers()));
                case "shared-job" -> p.artisanJobs.put("other",new ArtisanJob("other",ArtisanRecipe.JADE_CRYSTAL.id(),List.of(new Pos(3,64,0)),"jade_stock","jade_stock"));
                case "physical-alias" -> p.commodityStores.put("alias",new CommodityStore("alias","Alias",old.items(),old.containers()));
                case "possible-chest-half" -> p.commodityStores.put("alias",new CommodityStore("alias","Other half",old.items(),List.of(new Pos(1,64,1))));
                default -> throw new AssertionError(customization);
            }
            Map<String,CommodityStore> before=new LinkedHashMap<>(p.commodityStores);
            CrystalCollectionMigration.migrate(p);assertEquals(before,p.commodityStores);
            assertEquals(ArtisanRecipe.JADE_CRYSTAL,p.artisanJobs.get("jade_crystalariums").recipe());
        }
    }
    @Test void collectionJobDoesNotNeedIngredientStoreOrPretendAirOutputStorage() {
        Profile p=new Profile();
        p.commodityStores.put("output",new CommodityStore("output","Crystals",Set.of("minecraft:diamond"),List.of(new Pos(1,64,0))));
        p.artisanJobs.put("manual",new ArtisanJob("manual",ArtisanRecipe.CRYSTAL_COLLECTION.id(),List.of(new Pos(2,64,0)),"not_registered","output"));
        assertDoesNotThrow(()->AdditionalWorkRules.validate(p));
        p.commodityStores.put("output",new CommodityStore("output","Unrelated",Set.of("minecraft:egg"),List.of(new Pos(1,64,0))));
        assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(p));
    }
    static ArtisanJob job() { return new ArtisanJob("jade_crystalariums",ArtisanRecipe.JADE_CRYSTAL.id(),List.of(new Pos(2,64,0)),"jade_stock","jade_stock"); }
    static Profile legacy() {
        Profile p=new Profile();p.artisanJobs.put("jade_crystalariums",job());
        p.commodityStores.put("jade_stock",new CommodityStore("jade_stock","옥 결정복제기 재료·산출물",Set.of("society:jade"),List.of(new Pos(1,64,0))));return p;
    }
}
