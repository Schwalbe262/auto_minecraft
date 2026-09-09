package dev.schwalbe.autovalley.ui;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroupExpansionRulesTest {
    private static Pos pos(int x) {return new Pos(x,64,0);}
    private static GroupExpansionRules.Candidate keg(int x) {return candidate(pos(x),"society:wine_keg",Map.of("working","false","mature","false"),List.of(pos(x)));}
    private static GroupExpansionRules.Candidate chest(int x,String type,List<Pos> cells) {return candidate(pos(x),"minecraft:chest",Map.of("container","true","type",type,"facing","north"),cells);}
    private static GroupExpansionRules.Candidate candidate(Pos pos,String id,Map<String,String> props,List<Pos> cells) {return new GroupExpansionRules.Candidate(new BlockData(pos,id,props),cells);}
    private static Profile machineProfile(int count) {
        Profile profile=new Profile();List<Pos> members=new ArrayList<>();
        for(int x=0;x<count;x++){members.add(pos(x));profile.pois.add(new Poi(pos(x),PoiKind.WINE_KEG,"existing "+x,null));}
        profile.machineGroups.put("wine_main",new MachineGroup("Main wine",PoiKind.WINE_KEG,members));return profile;
    }

    @Test void existing384NeverBecomeCandidatesAndUnselectedThirteenNeverBecomeRegistrations() {
        Profile profile=machineProfile(384);profile.nextEligibleDay.put("machine:existing",77L);
        List<Poi> originals=List.copyOf(profile.pois);var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.machine(snapshot,"wine_main");
        List<GroupExpansionRules.Candidate> scanned=new ArrayList<>();for(int i=0;i<384;i++)scanned.add(keg(i));
        for(int i=0;i<13;i++)scanned.add(keg(1000+i));scanned.add(keg(2000));scanned.add(keg(2001));
        var available=GroupExpansionRules.available(snapshot,target,scanned);assertEquals(15,available.size());
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,available,Set.of(),available,false));
        var changes=GroupExpansionRules.expand(snapshot,target,available,Set.of(pos(2000),pos(2001)),available,false);
        assertEquals(386,changes.pois().size());assertEquals(386,changes.machines().get("wine_main").members().size());
        assertEquals(originals,changes.pois().subList(0,384));
        for(int i=0;i<13;i++)assertFalse(changes.machines().get("wine_main").members().contains(pos(1000+i)));
        assertEquals(originals,profile.pois);assertEquals(384,profile.machineGroups.get("wine_main").members().size());
        assertEquals(Map.of("machine:existing",77L),profile.nextEligibleDay);assertNull(profile.wineBatchSchedule);
    }

    @Test void batchSelectionIsOnlyAPresentationAndDoesNotPreselectOrSaveAnything() {
        List<GroupExpansionRules.Candidate> candidates=List.of(keg(10),keg(11),keg(40));
        var groups=GroupExpansionRules.batches(candidates);assertEquals(2,groups.size());assertEquals(2,groups.get(0).size());
        assertEquals(1,groups.get(1).size());assertEquals(candidates,groups.stream().flatMap(List::stream).toList());
        assertThrows(UnsupportedOperationException.class,()->groups.get(0).clear());
        Profile profile=machineProfile(1);var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.machine(snapshot,"wine_main");
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,candidates,Set.of(),candidates,false));
        assertEquals(1,profile.pois.size());
    }

    @Test void tomatoAdditionNeedsContentsConfirmationAndPreservesOldClassifiersAndSchedules() {
        Profile profile=new Profile();Poi old=new Poi(pos(0),PoiKind.TOMATO_CHEST,"legacy",3);profile.pois.add(old);
        profile.nextEligibleDay.put("harvest:tomatoes",27L);profile.tomatoStorageTargets.put("old",2);
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.tomato(snapshot);var addition=chest(10,"single",List.of(pos(10)));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(10)),List.of(addition),false));
        var changes=GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(10)),List.of(addition),true);
        assertEquals(old,changes.pois().get(0));assertEquals(PoiKind.TOMATO_CHEST,changes.pois().get(1).kind());assertNull(changes.pois().get(1).classifier());
        assertEquals(List.of(old),profile.pois);assertEquals(Map.of("harvest:tomatoes",27L),profile.nextEligibleDay);assertEquals(Map.of("old",2),profile.tomatoStorageTargets);
    }

    @Test void commodityAddsOnlyMembersAndKeepsIdNameItemOrderLinksAndUnrelatedStores() {
        Profile profile=new Profile();Set<String> items=new LinkedHashSet<>(List.of("society:ancient_fruit","society:ancient_fruit_seed"));
        CommodityStore old=new CommodityStore("seed_stock","Seeds",items,List.of(pos(0)));profile.commodityStores.put(old.id(),old);
        CommodityStore other=new CommodityStore("jade","Jade",Set.of("society:jade"),List.of(pos(30)));profile.commodityStores.put(other.id(),other);
        profile.cropStores.put(CropRules.ANCIENT_FRUIT,old.id());profile.nextEligibleDay.put("artisan:kept",90L);
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.commodity(snapshot,old.id());var addition=chest(10,"single",List.of(pos(10)));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(10)),List.of(addition),false));
        var changes=GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(10)),List.of(addition),true);var updated=changes.stores().get(old.id());
        assertEquals(List.of(pos(0),pos(10)),updated.containers());assertEquals(old.name(),updated.name());assertEquals(new ArrayList<>(items),new ArrayList<>(updated.items()));
        assertSame(other,changes.stores().get(other.id()));assertTrue(changes.pois().isEmpty());assertSame(old,profile.commodityStores.get(old.id()));
        assertEquals(Map.of(CropRules.ANCIENT_FRUIT,old.id()),profile.cropStores);assertEquals(Map.of("artisan:kept",90L),profile.nextEligibleDay);
    }

    @Test void eitherDoubleChestHalfReservedByAnyPoiOrAnotherStoreRejectsTheCandidate() {
        for(boolean storeReservation:List.of(false,true)) {
            Profile profile=machineProfile(1);profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit",Set.of(CropRules.ANCIENT_FRUIT_ITEM),List.of(pos(40))));
            if(storeReservation)profile.commodityStores.put("other",new CommodityStore("other","Other",Set.of("society:jade"),List.of(pos(11))));
            else profile.pois.add(new Poi(pos(11),PoiKind.WINE_CHEST,"reserved wine",14));
            var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.commodity(snapshot,"fruit");var pair=chest(10,"left",List.of(pos(10),pos(11)));
            assertTrue(GroupExpansionRules.available(snapshot,target,List.of(pair)).isEmpty());
            assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(pair),Set.of(pos(10)),List.of(pair),true));
        }
    }

    @Test void newlyAssignedOwnershipAndChangedProfileIdentityInvalidateTheWholePreview() {
        Profile profile=machineProfile(1);var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.machine(snapshot,"wine_main");var addition=keg(10);
        assertFalse(snapshot.current(machineProfile(1)));
        profile.pois.add(new Poi(pos(10),PoiKind.WINE_KEG,"another group",null));
        assertFalse(snapshot.current(profile));assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(10)),List.of(addition),false));
        assertEquals(2,profile.pois.size());assertEquals(1,profile.machineGroups.get("wine_main").members().size());
    }

    @Test void chestPairTypeFacingPhysicalMembershipAndLostLoadedProofCannotChangeAfterPreview() {
        Profile profile=new Profile();profile.pois.add(new Poi(pos(0),PoiKind.TOMATO_CHEST,"tomato",null));
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.tomato(snapshot);var before=chest(10,"single",List.of(pos(10)));
        for(var after:List.of(chest(10,"left",List.of(pos(10),pos(11))),
            candidate(pos(10),"minecraft:chest",Map.of("container","true","type","single","facing","south"),List.of(pos(10))),
            candidate(pos(10),"minecraft:barrel",Map.of("container","true"),List.of(pos(10)))))
            assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(before),Set.of(pos(10)),List.of(after),true));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(before),Set.of(pos(10)),List.of(),true));
        assertEquals(1,profile.pois.size());
    }

    @Test void overlappingSelectedPhysicalContainersRejectAtomically() {
        Profile profile=new Profile();profile.pois.add(new Poi(pos(0),PoiKind.TOMATO_CHEST,"tomato",null));
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.tomato(snapshot);
        var left=chest(10,"left",List.of(pos(10),pos(11)));var right=chest(11,"right",List.of(pos(10),pos(11)));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(left,right),Set.of(pos(10),pos(11)),List.of(left,right),true));
        assertEquals(1,profile.pois.size());
    }

    @Test void wrongMachineContainerTypeOrUnpreviewedSelectionCannotExpandAuthority() {
        Profile profile=machineProfile(1);var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.machine(snapshot,"wine_main");var addition=keg(10);
        assertTrue(GroupExpansionRules.available(snapshot,target,List.of(chest(20,"single",List.of(pos(20))))).isEmpty());
        assertTrue(GroupExpansionRules.available(snapshot,target,List.of(candidate(pos(20),"society:preserves_jar",Map.of(),List.of(pos(20))))).isEmpty());
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(11)),List.of(keg(11)),false));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.machine(snapshot,"unknown"));
    }

    @Test void legacyOrCommodityWineStorageCannotBypassIndividualCohortRegistration() {
        Profile profile=new Profile();profile.pois.add(new Poi(pos(0),PoiKind.WINE_CHEST,"wine",14));
        profile.commodityStores.put("wine",new CommodityStore("wine","Wine",Set.of(ItemData.WINE),List.of(pos(10))));
        var snapshot=GroupExpansionRules.capture(profile);
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.tomato(snapshot));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.commodity(snapshot,"wine"));assertEquals(14,profile.pois.get(0).classifier());
    }

    @Test void rowLayoutLeavesFeedbackAndPaginationClearAtSupportedScreenHeights() {
        for(int height:List.of(240,360,480,720,1080)) {
            int rows=GroupExpansionRules.rows(height);assertTrue(rows>=1);
            assertTrue(122+(rows-1)*23+20<height-66,"last row overlaps feedback at "+height);
            assertTrue(height-66+9<height-53,"feedback overlaps page controls");
        }
    }

    @Test void bothLanguagesCoverExpansionConfirmationExclusionsAndScreenOnlyRescanKey() throws Exception {
        JsonObject en=language("en_us"),ko=language("ko_kr");int count=0;
        for(String key:en.keySet())if(key.startsWith("autovalley.expansion.")) {
            count++;assertTrue(ko.has(key),key);String english=en.get(key).getAsString(),korean=ko.get(key).getAsString();
            assertFalse(english.isBlank());assertFalse(korean.isBlank());assertEquals(english.split("%s",-1).length,korean.split("%s",-1).length,key);
        }
        assertEquals(23,count);assertTrue(en.get("autovalley.expansion.rescan").getAsString().contains("(R)"));
        assertTrue(en.get("autovalley.expansion.wine_separate").getAsString().contains("cohort"));
    }
    private static JsonObject language(String name)throws Exception {
        try(InputStream stream=GroupExpansionRulesTest.class.getResourceAsStream("/assets/autovalley/lang/"+name+".json")) {
            assertNotNull(stream);return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
