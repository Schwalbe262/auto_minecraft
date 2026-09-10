package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TomatoSurplusProfileStoreTest {
    @TempDir Path directory;

    @Test void newAndMissingProfilesDefaultToNinetyPercentWithSurplusEnabled() throws Exception {
        Profile direct=new Profile();
        Profile absent=new ProfileStore(directory).load(ProfileStore.key("missing tomato settings"));
        for (Profile profile:List.of(direct,absent)) {
            assertEquals(90,profile.tomatoStorageLimitPercent);
            assertTrue(profile.tomatoSurplusShippingEnabled);
            assertTrue(profile.enabled(Feature.TOMATO_STORAGE));
            assertFalse(profile.enabled(Feature.LOGGING));
        }
        try(var files=Files.list(directory)) {assertEquals(0,files.count());}
    }

    @Test void legacyMissingFieldsUseDefaultsWithoutRewritingOrChangingSchedule() throws Exception {
        ProfileStore store=new ProfileStore(directory);
        for(int schema=1;schema<=5;schema++) {
            String key=ProfileStore.key("legacy tomato settings "+schema);Path path=directory.resolve(key+".json");
            String json="{\"schemaVersion\":"+schema+",\"nextEligibleDay\":{\"farm:kept\":30},\"enabled\":{\"TOMATO_STORAGE\":false}}";
            Files.writeString(path,json);Profile profile=store.load(key);
            assertEquals(90,profile.tomatoStorageLimitPercent);assertTrue(profile.tomatoSurplusShippingEnabled);
            assertFalse(profile.enabled(Feature.TOMATO_STORAGE));assertEquals(Map.of("farm:kept",30L),profile.nextEligibleDay);
            assertEquals(6,profile.schemaVersion);assertEquals(json,Files.readString(path));
            assertFalse(Files.exists(directory.resolve(key+".json.bak")));
        }
    }

    @Test void explicitDisabledEightyPercentRoundTripsWithoutEditingRegistrationsOrSchedule() throws Exception {
        Profile profile=new Profile();profile.tomatoSurplusShippingEnabled=false;profile.tomatoStorageLimitPercent=80;
        profile.pois.add(new Poi(new Pos(1,64,2),PoiKind.TOMATO_CHEST,"tomato store",null));
        profile.pois.add(new Poi(new Pos(3,64,2),PoiKind.SHIPPING_BIN,"shipping",null));
        profile.nextEligibleDay.put("farm:kept",21L);profile.enabled.put(Feature.SHIPPING,false);
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("explicit tomato settings");
        store.save(key,profile);Profile after=store.load(key);
        assertFalse(after.tomatoSurplusShippingEnabled);assertEquals(80,after.tomatoStorageLimitPercent);
        assertEquals(profile.pois,after.pois);assertEquals(profile.nextEligibleDay,after.nextEligibleDay);
        assertEquals(profile.enabled,after.enabled);
        String saved=Files.readString(directory.resolve(key+".json"));
        assertTrue(saved.contains("\"tomatoStorageLimitPercent\": 80"));
        assertTrue(saved.contains("\"tomatoSurplusShippingEnabled\": false"));
    }

    @Test void inclusiveLimitsAndCommonPresetsValidateAndRoundTrip() throws Exception {
        ProfileStore store=new ProfileStore(directory);
        for(int percent:List.of(1,80,90,100))for(boolean enabled:List.of(false,true)) {
            Profile profile=new Profile();profile.tomatoStorageLimitPercent=percent;profile.tomatoSurplusShippingEnabled=enabled;
            String key=ProfileStore.key("tomato boundary "+percent+enabled);store.save(key,profile);
            Profile after=store.load(key);assertEquals(percent,after.tomatoStorageLimitPercent);
            assertEquals(enabled,after.tomatoSurplusShippingEnabled);
        }
    }

    @Test void invalidPercentageCannotReplaceCurrentProfileEvenWhenSurplusIsDisabled() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("protected tomato settings");
        store.save(key,new Profile());Path path=directory.resolve(key+".json");String original=Files.readString(path);
        for(int percent:List.of(Integer.MIN_VALUE,-1,0,101,Integer.MAX_VALUE))for(boolean enabled:List.of(false,true)) {
            Profile profile=new Profile();profile.tomatoStorageLimitPercent=percent;profile.tomatoSurplusShippingEnabled=enabled;
            assertThrows(IllegalArgumentException.class,()->store.save(key,profile));
            assertEquals(original,Files.readString(path));assertFalse(Files.exists(directory.resolve(key+".json.bak")));
            assertEquals(ProfileStore.Stage.VALIDATE,store.lastDiagnostic().stage());
        }
    }

    @Test void invalidStoredPercentageRefusesReadOnlyInsteadOfClampingOrShipping() throws Exception {
        String key=ProfileStore.key("invalid stored tomato percent");Path path=directory.resolve(key+".json");
        for(String number:List.of("0","-1","101","2147483647","90.5")) {
            String json="{\"schemaVersion\":5,\"tomatoStorageLimitPercent\":"+number+"}";
            Files.writeString(path,json);
            assertThrows(IOException.class,()->new ProfileStore(directory).load(key));
            assertEquals(json,Files.readString(path));
        }
    }

    @Test void legacyExplicitValuesAreNotOverriddenByNewDefaults() throws Exception {
        ProfileStore store=new ProfileStore(directory);
        for(int schema=1;schema<=5;schema++) {
            String key=ProfileStore.key("explicit legacy tomato "+schema);
            Files.writeString(directory.resolve(key+".json"),"{\"schemaVersion\":"+schema+",\"tomatoStorageLimitPercent\":77,\"tomatoSurplusShippingEnabled\":false}");
            Profile profile=store.load(key);assertEquals(77,profile.tomatoStorageLimitPercent);assertFalse(profile.tomatoSurplusShippingEnabled);
        }
    }
}
