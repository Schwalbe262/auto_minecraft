package dev.schwalbe.autovalley.ui;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageListViewTest {
    @Test void thirtyTwoTomatoPoisBecomeOneDisplayRowWithoutChangingLabelsGradesOrOrder() {
        Profile profile=new Profile();
        for(int i=0;i<32;i++)profile.pois.add(new Poi(new Pos(i,64,0),PoiKind.TOMATO_CHEST,"tomato "+i,i%5));
        profile.nextEligibleDay.put("harvest:tomatoes",42L);profile.tomatoStorageTargets.put("legacy-label",3);
        List<Poi> original=List.copyOf(profile.pois);Map<String,Long> dates=Map.copyOf(profile.nextEligibleDay);
        StorageListView.Snapshot view=StorageListView.snapshot(profile);
        assertEquals(1,view.groups().size());assertTrue(view.standalonePois().isEmpty());
        var group=view.groups().get(0);assertTrue(group.tomato());assertEquals(32,group.members().size());
        assertEquals(List.of(ItemData.TOMATO),group.items());assertEquals(original.stream().map(Poi::pos).toList(),group.members());
        assertEquals(original,profile.pois);assertEquals(dates,profile.nextEligibleDay);
        assertEquals(Map.of("legacy-label",3),profile.tomatoStorageTargets);assertTrue(profile.commodityStores.isEmpty());
    }

    @Test void commodityStoresAppearWithNamesItemsAndCountsWithoutAnyPoiRegistration() {
        Profile profile=new Profile();
        List<Pos> ancient=new ArrayList<>();for(int i=0;i<16;i++)ancient.add(new Pos(i,70,20));
        CommodityStore fruit=new CommodityStore("ancient_store","Ancient fruit storage",Set.of(CropRules.ANCIENT_FRUIT_ITEM),ancient);
        CommodityStore seeds=new CommodityStore("seed_store","Seed storage",Set.of("society:ancient_fruit_seed"),List.of(new Pos(20,70,20)));
        profile.commodityStores.put(fruit.id(),fruit);profile.commodityStores.put(seeds.id(),seeds);
        StorageListView.Snapshot view=StorageListView.snapshot(profile);
        assertEquals(2,view.groups().size());assertTrue(view.standalonePois().isEmpty());assertTrue(profile.pois.isEmpty());
        assertEquals(List.of("ancient_store","seed_store"),view.groups().stream().map(StorageListView.Group::id).toList());
        assertEquals("Ancient fruit storage",view.groups().get(0).name());assertEquals(16,view.groups().get(0).members().size());
        assertEquals(List.of(CropRules.ANCIENT_FRUIT_ITEM),view.groups().get(0).items());
        assertEquals(List.of("society:ancient_fruit_seed"),view.groups().get(1).items());assertEquals(1,view.groups().get(1).members().size());
        assertSame(fruit,profile.commodityStores.get(fruit.id()));assertSame(seeds,profile.commodityStores.get(seeds.id()));
    }

    @Test void namedCommodityCandidateIsFoldedButWineCohortsAndOtherLocationsStayIndividual() {
        Profile profile=new Profile();Pos member=new Pos(1,64,1),unclaimed=new Pos(2,64,1);
        Poi candidate=new Poi(member,PoiKind.STORAGE_CANDIDATE,"existing candidate",null);
        Poi extra=new Poi(unclaimed,PoiKind.STORAGE_CANDIDATE,"unclaimed",null);
        Poi wine=new Poi(new Pos(3,64,1),PoiKind.WINE_CHEST,"wine",14);
        Poi waypoint=new Poi(new Pos(4,64,1),PoiKind.WAYPOINT,"walk",null);
        Poi wood=new Poi(new Pos(5,64,1),PoiKind.WOOD_CHEST,"wood",null);
        profile.pois.addAll(List.of(candidate,extra,wine,waypoint,wood));
        profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit",Set.of(CropRules.ANCIENT_FRUIT_ITEM),List.of(member)));
        var view=StorageListView.snapshot(profile);
        assertEquals(1,view.groups().size());assertEquals(List.of(extra,wine,waypoint,wood),view.standalonePois());
        assertEquals(List.of(candidate,extra,wine,waypoint,wood),profile.pois);assertEquals(14,wine.classifier());
    }

    @Test void groupingDoesNotCreateKegsOrRewriteExistingMachineGroupsSchedulesOrCropLinks() {
        Profile profile=new Profile();List<Pos> kegs=new ArrayList<>();
        for(int i=0;i<48;i++) {Pos position=new Pos(i,64,40);kegs.add(position);profile.pois.add(new Poi(position,PoiKind.WINE_KEG,"keg "+i,null));}
        MachineGroup machines=new MachineGroup("existing kegs",PoiKind.WINE_KEG,kegs);
        profile.machineGroups.put("registered-group",machines);
        profile.pois.add(new Poi(new Pos(0,64,0),PoiKind.TOMATO_CHEST,"tomato",null));
        profile.nextEligibleDay.put("machine:kept",99L);profile.cropStores.put(CropRules.ANCIENT_FRUIT,"fruit");
        List<Poi> before=List.copyOf(profile.pois);
        var view=StorageListView.snapshot(profile);
        assertEquals(48,view.standalonePois().size());assertEquals(1,view.groups().size());assertEquals(before,profile.pois);
        assertEquals(Map.of("registered-group",machines),profile.machineGroups);assertEquals(Map.of("machine:kept",99L),profile.nextEligibleDay);
        assertEquals(Map.of(CropRules.ANCIENT_FRUIT,"fruit"),profile.cropStores);
    }

    @Test void returnedGroupsItemsMembersAndPagesAreImmutableDetachedSnapshots() {
        Profile profile=new Profile();List<Pos> positions=new ArrayList<>(List.of(new Pos(1,64,2),new Pos(2,64,2)));
        Set<String> items=new LinkedHashSet<>(List.of("society:jade","society:ancient_fruit"));
        CommodityStore store=new CommodityStore("products","Products",items,positions);profile.commodityStores.put(store.id(),store);
        var first=StorageListView.snapshot(profile);var group=first.groups().get(0);
        assertEquals(List.of("society:ancient_fruit","society:jade"),group.items());
        assertEquals(first,StorageListView.snapshot(profile));
        assertThrows(UnsupportedOperationException.class,()->first.groups().clear());
        assertThrows(UnsupportedOperationException.class,()->first.standalonePois().clear());
        assertThrows(UnsupportedOperationException.class,()->group.members().clear());
        assertThrows(UnsupportedOperationException.class,()->group.items().clear());
        assertThrows(UnsupportedOperationException.class,()->StorageListView.page(group,0,1).clear());
        positions.clear();items.clear();profile.commodityStores.clear();
        assertEquals(2,group.members().size());assertEquals(2,group.items().size());assertFalse(StorageListView.current(profile,group));
    }

    @Test void boundedPaginationCoversEveryMemberOnceWithoutOverflowOrInventedCoordinates() {
        List<Pos> positions=new ArrayList<>();for(int i=0;i<32;i++)positions.add(new Pos(i,64,0));
        var group=new StorageListView.Group("legacy:tomato","",true,List.of(ItemData.TOMATO),positions);
        List<Pos> visited=new ArrayList<>();for(int page=0;page<5;page++)visited.addAll(StorageListView.page(group,page,7));
        assertEquals(positions,visited);assertEquals(4,StorageListView.page(group,4,7).size());
        assertTrue(StorageListView.page(group,5,7).isEmpty());assertTrue(StorageListView.page(group,-1,7).isEmpty());
        assertTrue(StorageListView.page(group,0,0).isEmpty());assertTrue(StorageListView.page(group,Integer.MAX_VALUE,Integer.MAX_VALUE).isEmpty());
    }

    @Test void detailChecksDetectStoreRenameItemsAndMemberChangesWithoutRepairingAnything() {
        Profile profile=new Profile();CommodityStore original=new CommodityStore("fruit","Fruit",Set.of(CropRules.ANCIENT_FRUIT_ITEM),List.of(new Pos(1,64,1)));
        profile.commodityStores.put(original.id(),original);var group=StorageListView.snapshot(profile).groups().get(0);
        assertTrue(StorageListView.current(profile,group));
        for(CommodityStore replacement:List.of(
            new CommodityStore(original.id(),"Renamed",original.items(),original.containers()),
            new CommodityStore(original.id(),original.name(),Set.of("society:ancient_fruit_seed"),original.containers()),
            new CommodityStore(original.id(),original.name(),original.items(),List.of(new Pos(2,64,1))))) {
            profile.commodityStores.put(original.id(),replacement);assertFalse(StorageListView.current(profile,group));
            assertSame(replacement,profile.commodityStores.get(original.id()));
        }
    }

    @Test void tomatoIntervalAndAncientDefinitionStayIndependentAndUnmodified() {
        Profile profile=new Profile();profile.harvestCycleDays=2;
        CropDefinition ancient=CropRules.definition(profile,CropRules.ANCIENT_FRUIT);
        assertNotNull(ancient);assertEquals(10,ancient.cycleDays());
        var view=StorageListView.snapshot(profile);assertEquals(2,profile.harvestCycleDays);
        assertSame(ancient,CropRules.definition(profile,CropRules.ANCIENT_FRUIT));assertTrue(view.groups().isEmpty());
        CropDefinition changed=new CropDefinition(ancient.key(),ancient.itemId(),ancient.blockIds(),ancient.matureStates(),12);
        profile.crops.put(ancient.key(),changed);
        assertEquals(12,CropRules.definition(profile,CropRules.ANCIENT_FRUIT).cycleDays());assertEquals(2,profile.harvestCycleDays);
    }

    @Test void individualTomatoRemovalRemainsAvailableWithoutWorldReadsButRejectsStaleOrOtherRoles() {
        Profile profile=new Profile();Poi tomato=new Poi(new Pos(1,64,1),PoiKind.TOMATO_CHEST,"original",2);
        profile.pois.add(tomato);var group=StorageListView.snapshot(profile).groups().get(0);
        assertTrue(StorageListView.removableTomato(profile,profile,group,tomato),"unloaded/demolished containers do not need native editing to unregister");
        assertFalse(StorageListView.removableTomato(new Profile(),profile,group,tomato));
        assertFalse(StorageListView.removableTomato(profile,new Profile(),group,tomato));
        assertFalse(StorageListView.removableTomato(profile,profile,group,new Poi(tomato.pos(),PoiKind.WINE_CHEST,"wine",14)));
        assertFalse(StorageListView.removableTomato(profile,profile,new StorageListView.Group("other","other",false,group.items(),group.members()),tomato));
        assertEquals(List.of(tomato),profile.pois);
        profile.pois.set(0,new Poi(tomato.pos(),PoiKind.TOMATO_CHEST,"changed",2));
        assertFalse(StorageListView.removableTomato(profile,profile,group,tomato));
        profile.pois.clear();assertFalse(StorageListView.removableTomato(profile,profile,group,tomato));
    }

    @Test void translatedGroupsAndScheduleLabelsClearlySeparateTomatoFromAncientFruit() throws Exception {
        JsonObject en=language("en_us"),ko=language("ko_kr");
        for(String suffix:List.of("tomato","named","items","view_hint","tomato_member_hint","changed"))
            assertTranslation(en,ko,"autovalley.storage.groups."+suffix);
        for(String suffix:List.of("harvest","tomato_only","ancient_readonly"))assertTranslation(en,ko,"autovalley.schedule."+suffix);
        assertTrue(en.get("autovalley.schedule.harvest").getAsString().contains("Tomato"));
        assertTrue(ko.get("autovalley.schedule.harvest").getAsString().contains("토마토"));
        assertTrue(en.get("autovalley.schedule.ancient_readonly").getAsString().contains("read-only"));
        assertTrue(ko.get("autovalley.schedule.ancient_readonly").getAsString().contains("읽기 전용"));
    }
    private static void assertTranslation(JsonObject en,JsonObject ko,String key) {
        assertTrue(en.has(key),key);assertTrue(ko.has(key),key);
        String english=en.get(key).getAsString(),korean=ko.get(key).getAsString();
        assertFalse(english.isBlank());assertFalse(korean.isBlank());assertEquals(english.split("%s",-1).length,korean.split("%s",-1).length,key);
    }
    private static JsonObject language(String name)throws Exception {
        try(InputStream stream=StorageListViewTest.class.getResourceAsStream("/assets/autovalley/lang/"+name+".json")) {
            assertNotNull(stream);return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
