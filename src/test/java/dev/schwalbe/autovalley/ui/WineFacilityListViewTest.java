package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WineFacilityListViewTest {
    private static Pos pos(int x) {return new Pos(x,64,0);}
    private static WineProductionLine line(String id,String name,List<Pos> machines,int days,boolean enabled) {
        return new WineProductionLine(id,name,ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_WINE,"fruit","wine",machines,days,enabled);
    }
    private static Profile profile() {
        Profile profile=new Profile();
        profile.pois.add(new Poi(pos(0),PoiKind.WINE_KEG,"Tomato keg",null));
        profile.machineGroups.put("tomato_group",new MachineGroup("Tomato kegs",PoiKind.WINE_KEG,List.of(pos(0))));
        profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit storage",Set.of(ItemData.ANCIENT_FRUIT),List.of(pos(90))));
        profile.commodityStores.put("wine",new CommodityStore("wine","Wine storage",Set.of(ItemData.ANCIENT_WINE),List.of(pos(91))));
        List<Pos> machines=new ArrayList<>();for(int i=1;i<=64;i++)machines.add(pos(i));
        profile.wineProductionLines.put("ancient",line("ancient","Ancient fruit wine",machines,6,true));
        return profile;
    }

    @Test void customKegsAppearAsOneNamedFacilityRowWithoutBeingLegacyPois() {
        Profile profile=profile();List<Poi> pois=List.copyOf(profile.pois);
        Map<String,MachineGroup> machineGroups=Map.copyOf(profile.machineGroups);
        Map<String,CommodityStore> stores=Map.copyOf(profile.commodityStores);
        WineBatchSchedule legacy=new WineBatchSchedule(80,false,List.of(),74L);
        WineBatchSchedule active=new WineBatchSchedule(82,true,List.of(pos(1)),76L);
        profile.wineBatchSchedule=legacy;profile.wineProductionSchedules.put("ancient",active);
        profile.nextEligibleDay.put("unchanged",99L);
        List<WineProductionLine> rows=WineFacilityListView.snapshot(profile);
        assertEquals(1,rows.size());WineProductionLine row=rows.get(0);
        assertEquals("ancient",row.id());assertEquals("Ancient fruit wine",row.name());assertEquals(64,row.machines().size());
        assertEquals(6,row.cycleDays());assertTrue(row.enabled());
        assertSame(profile.wineProductionLines.get("ancient"),row);
        assertEquals(pois,profile.pois);assertEquals(machineGroups,profile.machineGroups);assertEquals(stores,profile.commodityStores);
        assertSame(legacy,profile.wineBatchSchedule);assertSame(active,profile.wineProductionSchedules.get("ancient"));
        assertEquals(Map.of("unchanged",99L),profile.nextEligibleDay);
        assertEquals(List.of(pos(0)),WineProductionRules.line(profile,WineProductionRules.LEGACY_ID).machines());
        assertThrows(UnsupportedOperationException.class,()->rows.clear());
        assertThrows(UnsupportedOperationException.class,()->row.machines().clear());
    }

    @Test void disabledFacilitiesRemainVisibleButInvalidOrMismatchedDefinitionsDoNotInventRows() {
        Profile profile=profile();WineProductionLine original=profile.wineProductionLines.get("ancient");
        WineProductionLine disabled=line(original.id(),original.name(),original.machines(),6,false);
        profile.wineProductionLines.put(disabled.id(),disabled);
        profile.wineProductionLines.put("mismatched",line("different","Different",List.of(pos(70)),6,true));
        profile.wineProductionLines.put("empty",line("empty","Empty",List.of(),6,true));
        profile.wineProductionLines.put("unsupported",new WineProductionLine("unsupported","Unsupported","minecraft:stone",ItemData.ANCIENT_WINE,
            "fruit","wine",List.of(pos(71)),6,true));
        profile.wineProductionLines.put(WineProductionRules.LEGACY_ID,line(WineProductionRules.LEGACY_ID,"Not a replacement",List.of(pos(72)),6,true));
        assertEquals(List.of(disabled),WineFacilityListView.snapshot(profile));
        assertTrue(WineFacilityListView.current(profile,profile,disabled));
        assertEquals(List.of(pos(0)),WineProductionRules.line(profile,WineProductionRules.LEGACY_ID).machines());
        assertTrue(WineFacilityListView.snapshot(null).isEmpty());
    }

    @Test void staleRowsCannotOpenOrEditAnotherProfileOrAChangedLine() {
        Profile profile=profile();WineProductionLine selected=WineFacilityListView.snapshot(profile).get(0);
        assertTrue(WineFacilityListView.current(profile,profile,selected));
        Profile other=profile();assertEquals(selected,other.wineProductionLines.get(selected.id()));
        assertFalse(WineFacilityListView.current(profile,other,selected));
        assertFalse(WineFacilityListView.current(null,profile,selected));assertFalse(WineFacilityListView.current(profile,profile,null));
        for(WineProductionLine changed:List.of(
            line(selected.id(),"Renamed",selected.machines(),6,true),
            line(selected.id(),selected.name(),List.of(pos(1)),6,true),
            line(selected.id(),selected.name(),selected.machines(),7,true),
            line(selected.id(),selected.name(),selected.machines(),6,false))) {
            profile.wineProductionLines.put(selected.id(),changed);
            assertFalse(WineFacilityListView.current(profile,profile,selected));
            assertSame(changed,profile.wineProductionLines.get(selected.id()));
        }
        profile.wineProductionLines.clear();assertFalse(WineFacilityListView.current(profile,profile,selected));
    }

    @Test void directDetailsSelectionFollowsTheLineIdAcrossReorderAndNeverFallsBackAfterRemoval() {
        Profile profile=profile();WineProductionLine selected=profile.wineProductionLines.get("ancient");
        WineProductionLine other=line("other","Other wine",List.of(pos(70)),6,false);
        assertEquals(1,WineFacilityListView.indexOf(WineProductionRules.lines(profile),selected.id()));
        profile.wineProductionLines.clear();profile.wineProductionLines.put(other.id(),other);profile.wineProductionLines.put(selected.id(),selected);
        var reordered=WineProductionRules.lines(profile);
        int index=WineFacilityListView.indexOf(reordered,selected.id());assertEquals(2,index);assertSame(selected,reordered.get(index));
        profile.wineProductionLines.remove(selected.id());
        assertEquals(-1,WineFacilityListView.indexOf(WineProductionRules.lines(profile),selected.id()));
        assertEquals(-1,WineFacilityListView.indexOf(List.of(),selected.id()));
        assertEquals(-1,WineFacilityListView.indexOf(null,selected.id()));assertEquals(-1,WineFacilityListView.indexOf(reordered,null));
        assertEquals(0,WineFacilityListView.indexOf(reordered,WineProductionRules.LEGACY_ID));
    }

    @Test void aListedFacilityExpandsOnlyItsCustomLineAndRetainsBothProductionSchedules() {
        Profile profile=profile();WineProductionLine row=WineFacilityListView.snapshot(profile).get(0);
        WineBatchSchedule legacy=new WineBatchSchedule(120,false,List.of(),114L);
        WineBatchSchedule active=new WineBatchSchedule(122,true,List.of(pos(1)),116L);
        profile.wineBatchSchedule=legacy;profile.wineProductionSchedules.put(row.id(),active);
        var snapshot=GroupExpansionRules.capture(profile);var target=GroupExpansionRules.wineLine(snapshot,row.id());
        assertEquals(GroupExpansionRules.Kind.WINE_LINE,target.kind());assertEquals(row.name(),target.name());
        assertEquals(row.machines(),target.members());assertEquals(PoiKind.WINE_KEG,target.machineKind());
        var added=new GroupExpansionRules.Candidate(new BlockData(pos(80),"society:wine_keg",Map.of()),List.of(pos(80)));
        var changes=GroupExpansionRules.expand(snapshot,target,List.of(added),Set.of(pos(80)),List.of(added),false);
        assertEquals(65,changes.wineLines().get(row.id()).machines().size());
        assertEquals(snapshot.pois(),changes.pois());assertEquals(snapshot.machines(),changes.machines());
        assertEquals(snapshot.stores(),changes.stores());assertEquals(64,row.machines().size());
        assertSame(legacy,profile.wineBatchSchedule);assertSame(active,profile.wineProductionSchedules.get(row.id()));
        assertEquals(List.of(pos(1)),active.remaining());assertEquals(122,active.nextDueDay());
    }
}
