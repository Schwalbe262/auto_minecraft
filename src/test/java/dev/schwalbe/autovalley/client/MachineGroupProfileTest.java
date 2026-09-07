package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MachineGroupProfileTest {
    @TempDir Path directory;
    @Test void legacyGroupsDefaultEmptyAndDoNotRewriteOriginalFile() throws Exception {
        String key=ProfileStore.key("legacy-groups"); Path file=directory.resolve(key+".json");
        String old="{\"schemaVersion\":2}"; Files.writeString(file,old);
        Profile p=new ProfileStore(directory).load(key);
        assertNotNull(p.machineGroups); assertTrue(p.machineGroups.isEmpty()); assertEquals(old,Files.readString(file));
    }
    @Test void groupsAndNamesRoundTripWithoutChangingPerMachineLabelsOrDeadlines() throws Exception {
        Profile p=new Profile(); Pos first=new Pos(1,64,2),second=new Pos(2,64,2);
        p.pois.add(new Poi(first,PoiKind.PRESERVES_JAR,"Original one",null));
        p.pois.add(new Poi(second,PoiKind.PRESERVES_JAR,"Original two",null));
        p.machineGroups.put("group-one",new MachineGroup("절임구역 1",PoiKind.PRESERVES_JAR,List.of(first,second)));
        p.nextEligibleDay.put("preserves:1:64:2",21L);
        String key=ProfileStore.key("group-roundtrip"); ProfileStore store=new ProfileStore(directory);
        store.save(key,p); Profile loaded=store.load(key);
        assertEquals(p.machineGroups,loaded.machineGroups); assertEquals(p.pois,loaded.pois);
        assertEquals(p.nextEligibleDay,loaded.nextEligibleDay);
        loaded.machineGroups.put("group-one",new MachineGroup("Renamed",PoiKind.PRESERVES_JAR,List.of(first,second)));
        store.save(key,loaded); assertEquals("Renamed",store.load(key).machineGroups.get("group-one").name());
        assertEquals(p.pois,store.load(key).pois);
    }
    @Test void invalidReferencesCannotOverwritePreviouslySavedProfile() throws Exception {
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("invalid-group");
        store.save(key,new Profile()); Path file=directory.resolve(key+".json"); String original=Files.readString(file);
        Profile invalid=new Profile();
        invalid.machineGroups.put("one",new MachineGroup("Missing",PoiKind.WINE_KEG,List.of(new Pos(1,64,0))));
        assertThrows(IllegalArgumentException.class,() -> store.save(key,invalid)); assertEquals(original,Files.readString(file));
    }
    @Test void explicitlyNullGroupMapIsPreservedAndRejected() throws Exception {
        String key=ProfileStore.key("null-group"); Path file=directory.resolve(key+".json");
        String malformed="{\"schemaVersion\":2,\"machineGroups\":null}"; Files.writeString(file,malformed);
        assertThrows(IOException.class,() -> new ProfileStore(directory).load(key)); assertEquals(malformed,Files.readString(file));
    }
}
