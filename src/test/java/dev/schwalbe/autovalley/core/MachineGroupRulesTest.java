package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineGroupRulesTest {
    private Poi add(Profile p,int x,int y,int z,PoiKind kind) {
        Poi poi=new Poi(new Pos(x,y,z),kind,"Original "+p.pois.size(),null); p.pois.add(poi); return poi;
    }
    private MachineGroup group(String name,PoiKind kind,Poi... members) {
        return new MachineGroup(name,kind,Arrays.stream(members).map(Poi::pos).toList());
    }
    @Test void oneHundredFortyFourLegacyJarsCollapseWithoutChangingAnyRegistrationOrDeadline() {
        Profile p=new Profile();
        for(int x=0;x<12;x++) for(int z=0;z<12;z++) add(p,x,64,z,PoiKind.PRESERVES_JAR);
        p.nextEligibleDay.put("preserves:0:64:0",31L);
        List<Poi> before=List.copyOf(p.pois); Map<String,Long> deadlines=Map.copyOf(p.nextEligibleDay);
        List<List<Poi>> collapsed=MachineGroupRules.legacyGroups(p);
        assertEquals(1,collapsed.size()); assertEquals(144,collapsed.get(0).size());
        assertEquals(before,p.pois); assertEquals(deadlines,p.nextEligibleDay); assertTrue(p.machineGroups.isEmpty());
    }
    @Test void threeHundredEightyFourWineMachinesCollapseSeparatelyFromNearbyJars() {
        Profile p=new Profile();
        for(int x=0;x<16;x++) for(int z=0;z<24;z++) add(p,x,64,z,PoiKind.WINE_KEG);
        add(p,0,65,0,PoiKind.PRESERVES_JAR);
        var groups=MachineGroupRules.legacyGroups(p); assertEquals(2,groups.size());
        assertEquals(384,groups.get(0).size()); assertEquals(1,groups.get(1).size());
    }
    @Test void legacyConnectionsUseThreeBlockManhattanDistanceAndNeverIncludeStorageOrUnregisteredGaps() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.PRESERVES_JAR), b=add(p,0,67,0,PoiKind.PRESERVES_JAR);
        Poi c=add(p,4,67,0,PoiKind.PRESERVES_JAR); add(p,2,67,0,PoiKind.STORAGE_CANDIDATE);
        var groups=MachineGroupRules.legacyGroups(p); assertEquals(List.of(a,b),groups.get(0));
        assertEquals(List.of(c),groups.get(1)); assertEquals(2,groups.size());
    }
    @Test void savedMembershipDoesNotGrowWhenNearbyMachinesAreAdded() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.PRESERVES_JAR), b=add(p,1,64,0,PoiKind.PRESERVES_JAR);
        p.machineGroups.put("fixed",group("Area",PoiKind.PRESERVES_JAR,a));
        MachineGroupRules.validate(p); assertEquals(List.of(List.of(b)),MachineGroupRules.legacyGroups(p));
        assertEquals(List.of(a.pos()),p.machineGroups.get("fixed").members());
    }
    @Test void zeroNewRegistrationNamedGroupIsValidAndKeepsAllOriginalLabels() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.WINE_KEG), b=add(p,1,64,0,PoiKind.WINE_KEG);
        List<Poi> originals=List.copyOf(p.pois);
        p.machineGroups.put("saved-existing",group("Original row",PoiKind.WINE_KEG,a,b));
        MachineGroupRules.validate(p); assertEquals(originals,p.pois); assertTrue(MachineGroupRules.legacyGroups(p).isEmpty());
    }
    @Test void duplicateMembershipCannotSilentlyMergeSavedGroups() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.PRESERVES_JAR);
        p.machineGroups.put("one",group("One",PoiKind.PRESERVES_JAR,a));
        p.machineGroups.put("two",group("Two",PoiKind.PRESERVES_JAR,a));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
        p.machineGroups.clear(); p.machineGroups.put("one",group("One",PoiKind.PRESERVES_JAR,a,a));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
    }
    @Test void missingMembersDifferentKindsAndStorageCannotBeGrouped() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.WINE_KEG);
        p.machineGroups.put("one",group("Wrong kind",PoiKind.PRESERVES_JAR,a));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
        p.machineGroups.put("one",new MachineGroup("Missing",PoiKind.WINE_KEG,List.of(new Pos(10,64,0))));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
        p.machineGroups.put("one",group("Unsupported",PoiKind.WINE_CHEST,a));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
    }
    @Test void groupMembersAreDefensivelyCopied() {
        List<Pos> positions=new ArrayList<>(List.of(new Pos(0,64,0)));
        MachineGroup group=new MachineGroup("Area",PoiKind.WINE_KEG,positions); positions.clear();
        assertEquals(1,group.members().size()); assertThrows(UnsupportedOperationException.class,() -> group.members().clear());
    }
    @Test void metadataValidationRejectsInvalidNamesIdsEmptyAndNullMaps() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.WINE_KEG);
        for(String name:List.of(""," ","bad\nname","x".repeat(65))) {
            p.machineGroups.put("one",group(name,PoiKind.WINE_KEG,a));
            assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
        }
        p.machineGroups.clear(); p.machineGroups.put("../bad",group("Fine",PoiKind.WINE_KEG,a));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
        p.machineGroups.clear(); p.machineGroups.put("one",new MachineGroup("Fine",PoiKind.WINE_KEG,List.of()));
        assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
        p.machineGroups=null; assertThrows(IllegalArgumentException.class,() -> MachineGroupRules.validate(p));
    }
    @Test void detachOnlyChangesGroupMetadataAndRetainsProductionHistory() {
        Profile p=new Profile(); Poi a=add(p,0,64,0,PoiKind.WINE_KEG), b=add(p,1,64,0,PoiKind.WINE_KEG);
        p.machineGroups.put("one",group("Area",PoiKind.WINE_KEG,a,b)); p.nextEligibleDay.put("wine:0:64:0",9L);
        MachineGroupRules.detach(p,a.pos()); assertEquals(List.of(b.pos()),p.machineGroups.get("one").members());
        assertEquals(2,p.pois.size()); assertEquals(9L,p.nextEligibleDay.get("wine:0:64:0"));
        MachineGroupRules.detach(p,b.pos()); assertTrue(p.machineGroups.isEmpty());
    }
}
