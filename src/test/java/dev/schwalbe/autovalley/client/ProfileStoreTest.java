package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileStoreTest {
    @TempDir Path directory;

    @Test void legacyProfileEnablesBackgroundAndExplicitOptOutPersists() throws Exception {
        String key=ProfileStore.key("legacy-background");
        Files.writeString(directory.resolve(key+".json"),"{\"schemaVersion\":1}");
        ProfileStore store=new ProfileStore(directory);
        Profile loaded=store.load(key);
        assertTrue(loaded.allowBackground);
        loaded.allowBackground=false;
        store.save(key,loaded);
        assertFalse(store.load(key).allowBackground);
    }

    @Test void roundTripPreservesMachineDeadlinesWineYearsAndFeatureSettings() throws Exception {
        Profile profile = new Profile();
        profile.pois.add(new Poi(new Pos(1,64,2),PoiKind.WINE_CHEST,"year12",12));
        profile.nextEligibleDay.put("wine:4:64:2",19L); profile.nextEligibleDay.put("preserves:5:64:2",16L);
        profile.lastSeenDay=13; profile.wineCycleDays=6; profile.preservesCycleDays=3;
        profile.enabled.put(Feature.PRESERVES,false);
        ProfileStore store = new ProfileStore(directory); String key = ProfileStore.key("test-world");
        store.save(key,profile); Profile loaded = store.load(key);
        assertEquals(profile.pois,loaded.pois); assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
        assertEquals(13,loaded.lastSeenDay); assertFalse(loaded.enabled(Feature.PRESERVES));
        assertEquals(6,loaded.wineCycleDays); assertEquals(3,loaded.preservesCycleDays);
    }

    @Test void malformedJsonIsPreservedByteForByte() throws Exception {
        String key = ProfileStore.key("broken"); Path file=directory.resolve(key+".json");
        String original="{\"schemaVersion\": 1, definitely invalid";
        Files.writeString(file,original);
        assertThrows(IOException.class,() -> new ProfileStore(directory).load(key));
        assertEquals(original,Files.readString(file));
    }

    @Test void invalidBoundsCannotOverwriteAnExistingProfile() throws Exception {
        ProfileStore store = new ProfileStore(directory); String key=ProfileStore.key("bounds");
        Profile profile=new Profile(); store.save(key,profile); String original=Files.readString(directory.resolve(key+".json"));
        profile.farms.add(new Farm("tooLarge",new Pos(0,0,0),new Pos(100,100,100)));
        assertThrows(IllegalArgumentException.class,() -> store.save(key,profile));
        assertEquals(original,Files.readString(directory.resolve(key+".json")));
    }

    @Test void invalidGradesDeadlinesAndDuplicateLocationsAreRejected() {
        Profile grade=new Profile(); grade.pois.add(new Poi(new Pos(0,64,0),PoiKind.TOMATO_CHEST,"bad",4));
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(grade));
        Profile date=new Profile(); date.nextEligibleDay.put("wine:0:64:0",-1L);
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(date));
        Profile duplicate=new Profile(); Pos p=new Pos(0,64,0);
        duplicate.pois.add(new Poi(p,PoiKind.WINE_KEG,"a",null)); duplicate.pois.add(new Poi(p,PoiKind.PRESERVES_JAR,"b",null));
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(duplicate));
    }

    @Test void storageKeysHideIdentityAndCannotTraverseDirectories() throws Exception {
        String identity="private-server.example:25565|minecraft:overworld|player";
        String key=ProfileStore.key(identity);
        assertTrue(key.matches("[a-f0-9]{24}")); assertFalse(key.contains("private-server"));
        ProfileStore store=new ProfileStore(directory);
        assertThrows(IllegalArgumentException.class,() -> store.load("../outside"));
        assertThrows(IllegalArgumentException.class,() -> store.save("../outside",new Profile()));
        assertNotNull(store.load(key));
    }
}
