package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WineLineExpansionRulesTest {
    private static Pos pos(int x) {return new Pos(x,64,0);}
    private static GroupExpansionRules.Candidate candidate(int x,String id,List<Pos> cells) {
        return new GroupExpansionRules.Candidate(new BlockData(pos(x),id,Map.of("container","true")),cells);
    }
    private static GroupExpansionRules.Candidate keg(int x) {return candidate(x,"society:wine_keg",List.of(pos(x)));}
    private static WineProductionLine line(String id,List<Pos> machines,boolean enabled) {
        return new WineProductionLine(id,"Ancient fruit wine",ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_WINE,
            "fruit","wine",machines,6,enabled);
    }
    private static Profile profile() {
        Profile profile=new Profile();
        profile.pois.add(new Poi(pos(0),PoiKind.WINE_KEG,"Legacy tomato keg",null));
        profile.machineGroups.put("wine_main",new MachineGroup("Legacy tomato",PoiKind.WINE_KEG,List.of(pos(0))));
        profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit",Set.of(ItemData.ANCIENT_FRUIT),List.of(pos(30))));
        profile.commodityStores.put("wine",new CommodityStore("wine","Wine",Set.of(ItemData.ANCIENT_WINE),List.of(pos(31))));
        profile.wineProductionLines.put("ancient",line("ancient",List.of(pos(1),pos(2)),true));
        return profile;
    }

    @Test void selectedExpansionChangesOnlyThatLinesMembershipAndNeverTheCurrentPass() {
        Profile profile=profile();WineProductionLine original=profile.wineProductionLines.get("ancient");
        WineProductionLine other=line("other",List.of(pos(40)),false);profile.wineProductionLines.put(other.id(),other);
        WineBatchSchedule active=new WineBatchSchedule(120,true,List.of(pos(1)),114L,
            List.of(new WineBatchSchedule.SkippedMember(pos(2),"still working",113)));
        WineBatchSchedule legacy=new WineBatchSchedule(118,false,List.of(),112L);
        profile.wineProductionSchedules.put("ancient",active);profile.wineBatchSchedule=legacy;
        profile.nextEligibleDay.put("unrelated",300L);
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.wineLine(snapshot,"ancient");
        var scanned=List.of(keg(10),keg(11));
        var changes=GroupExpansionRules.expand(snapshot,target,scanned,Set.of(pos(10)),scanned,false);
        var expanded=changes.wineLines().get("ancient");
        assertEquals(new WineProductionLine(original.id(),original.name(),original.inputItemId(),original.outputItemId(),
            original.inputStoreId(),original.outputStoreId(),List.of(pos(1),pos(2),pos(10)),6,true),expanded);
        assertFalse(expanded.machines().contains(pos(11)));
        assertEquals(snapshot.pois(),changes.pois());assertEquals(snapshot.machines(),changes.machines());assertEquals(snapshot.stores(),changes.stores());
        assertSame(other,changes.wineLines().get("other"));assertSame(original,profile.wineProductionLines.get("ancient"));
        assertSame(active,profile.wineProductionSchedules.get("ancient"));assertSame(legacy,profile.wineBatchSchedule);
        assertEquals(List.of(pos(1)),active.remaining());assertEquals(120,active.nextDueDay());assertEquals(114L,active.latestFeedDay());
        assertEquals(List.of(pos(2)),active.skipped().stream().map(WineBatchSchedule.SkippedMember::pos).toList());
        assertEquals(Map.of("unrelated",300L),profile.nextEligibleDay);
        assertThrows(UnsupportedOperationException.class,()->changes.wineLines().clear());
    }

    @Test void exactSingleCellKegsAreRequiredAndRipenessChangesDoNotChangeRegistration() {
        var snapshot=GroupExpansionRules.capture(profile());var target=GroupExpansionRules.wineLine(snapshot,"ancient");
        assertEquals(GroupExpansionRules.Kind.WINE_LINE,target.kind());assertEquals(PoiKind.WINE_KEG,target.machineKind());assertTrue(target.machine());
        for(var invalid:List.of(candidate(10,"minecraft:barrel",List.of(pos(10))),
            candidate(10,"other:wine_keg",List.of(pos(10))),candidate(10,"society:preserves_jar",List.of(pos(10))),
            candidate(10,"society:wine_keg",List.of(pos(10),pos(11)))))assertFalse(GroupExpansionRules.accepts(target,invalid));
        var working=new GroupExpansionRules.Candidate(new BlockData(pos(10),"society:wine_keg",Map.of("working","true","mature","false")),List.of(pos(10)));
        assertTrue(GroupExpansionRules.sameGeometry(target,keg(10),working));
        assertEquals(3,GroupExpansionRules.expand(snapshot,target,List.of(keg(10)),Set.of(pos(10)),List.of(working),false).wineLines().get("ancient").machines().size());
    }

    @Test void everyExistingFacilityOwnerExcludesCandidatesIncludingDisabledLinesAndArtisanMachines() {
        Profile profile=profile();
        profile.wineProductionLines.put("disabled",line("disabled",List.of(pos(20)),false));
        profile.artisanJobs.put("seeds",new ArtisanJob("seeds","ancient_seed",List.of(pos(21)),"fruit","wine"));
        profile.machineGroups.put("orphan_group",new MachineGroup("Reserved group",PoiKind.WINE_KEG,List.of(pos(22))));
        profile.pois.add(new Poi(pos(23),PoiKind.SHIPPING_BIN,"Shipping",null));
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.wineLine(snapshot,"ancient");
        var scanned=List.of(keg(0),keg(1),keg(2),keg(20),keg(21),keg(22),keg(23),keg(30),keg(31),keg(50));
        assertEquals(List.of(keg(50)),GroupExpansionRules.available(snapshot,target,scanned));
        for(int reserved:List.of(0,1,2,20,21,22,23,30,31))assertThrows(IllegalArgumentException.class,
            ()->GroupExpansionRules.expand(snapshot,target,scanned,Set.of(pos(reserved)),scanned,false));
    }

    @Test void ownershipChangesInvalidateCapturedPreviewsWithoutTouchingSchedules() {
        for(boolean artisanChange:List.of(false,true)) {
            Profile profile=profile();var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.wineLine(snapshot,"ancient");
            if(artisanChange)profile.artisanJobs.put("seeds",new ArtisanJob("seeds","ancient_seed",List.of(pos(10)),"fruit","wine"));
            else profile.wineProductionLines.put("other",line("other",List.of(pos(10)),false));
            assertFalse(snapshot.current(profile));
            assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,List.of(keg(10)),Set.of(pos(10)),List.of(keg(10)),false));
            assertEquals(List.of(pos(1),pos(2)),profile.wineProductionLines.get("ancient").machines());
        }
    }

    @Test void scanAndBatchPresentationNeverSelectAndConfirmationRequiresFreshPreviewedMembers() {
        Profile profile=profile();var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.wineLine(snapshot,"ancient");
        var candidates=GroupExpansionRules.available(snapshot,target,List.of(keg(10),keg(11)));
        assertEquals(1,GroupExpansionRules.batches(candidates).size());
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,candidates,Set.of(),candidates,false));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,candidates,Set.of(pos(12)),List.of(keg(12)),false));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,target,candidates,Set.of(pos(10)),List.of(),false));
        assertEquals(List.of(pos(1),pos(2)),profile.wineProductionLines.get("ancient").machines());
    }

    @Test void lineTargetRejectsMissingAndLegacyIdsAndPreservesDisabledSetting() {
        Profile profile=profile();profile.wineProductionLines.put("disabled",line("disabled",List.of(pos(20)),false));
        var snapshot=GroupExpansionRules.capture(profile);
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.wineLine(snapshot,"missing"));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.wineLine(snapshot,WineProductionRules.LEGACY_ID));
        var target=GroupExpansionRules.wineLine(snapshot,"disabled");
        assertFalse(GroupExpansionRules.expand(snapshot,target,List.of(keg(21)),Set.of(pos(21)),List.of(keg(21)),false).wineLines().get("disabled").enabled());
    }

    @Test void existingMachineAndCommodityExpansionCannotConsumeWineLineOwnedCells() {
        Profile profile=profile();var snapshot=GroupExpansionRules.capture(profile);
        var machine=GroupExpansionRules.machine(snapshot,"wine_main");
        assertTrue(GroupExpansionRules.available(snapshot,machine,List.of(keg(1))).isEmpty());
        var machineChanges=GroupExpansionRules.expand(snapshot,machine,List.of(keg(10)),Set.of(pos(10)),List.of(keg(10)),false);
        assertEquals(snapshot.wineLines(),machineChanges.wineLines());assertEquals(2,machineChanges.pois().size());
        var commodity=GroupExpansionRules.commodity(snapshot,"fruit");
        var barrel=candidate(1,"minecraft:barrel",List.of(pos(1)));
        assertTrue(GroupExpansionRules.available(snapshot,commodity,List.of(barrel)).isEmpty());
        var addition=candidate(11,"minecraft:barrel",List.of(pos(11)));
        assertThrows(IllegalArgumentException.class,()->GroupExpansionRules.expand(snapshot,commodity,List.of(addition),Set.of(pos(11)),List.of(addition),false));
        var storageChanges=GroupExpansionRules.expand(snapshot,commodity,List.of(addition),Set.of(pos(11)),List.of(addition),true);
        assertEquals(snapshot.wineLines(),storageChanges.wineLines());assertEquals(List.of(pos(30),pos(11)),storageChanges.stores().get("fruit").containers());
    }

    @Test void expansionRequiresAnObservedEmptyCursor() {
        assertFalse(GroupExpansionRules.emptyCursor(null));
        assertFalse(GroupExpansionRules.emptyCursor(new MenuData(0,0,List.of(),null,false)));
        assertFalse(GroupExpansionRules.emptyCursor(new MenuData(0,0,List.of(),new ItemData(ItemData.ANCIENT_WINE,1,0,null,false,0),false)));
        assertTrue(GroupExpansionRules.emptyCursor(new MenuData(0,0,List.of(),ItemData.EMPTY,false)));
    }
}
