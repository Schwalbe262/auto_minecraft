package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManualWorkHotbarProfileStoreTest {
    @TempDir Path directory;
    private static final HotbarLease LEASE=new HotbarLease(Feature.CRYSTAL_COPY,9,5,
        new ItemData("society:fire_quartz",2,0,null,false,Integer.MAX_VALUE),"a".repeat(64),HotbarLease.Stage.PARKED);
    private static ManualWorkHotbarResolution.Entry entry() {
        return new ManualWorkHotbarResolution.Entry(LEASE,ManualWorkHotbarResolution.confirmationKey(LEASE),
            ManualWorkHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,777);
    }

    @Test void explicitManualAuditSurvivesRestartWithoutReinstatingAnObligationOrEnablingAnyWork() throws Exception {
        Profile profile=new Profile();profile.enabled.put(Feature.CRYSTAL_COPY,false);
        profile.nextEligibleDay.put("wine:unrelated",999L);profile.manualWorkHotbarResolutions.add(entry());
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("manual custody archive");store.save(key,profile);
        assertEquals(7,profile.schemaVersion);String bytes=Files.readString(directory.resolve(key+".json"));
        Profile loaded=store.load(key);
        assertEquals(List.of(entry()),loaded.manualWorkHotbarResolutions);assertNull(loaded.workHotbarLease);
        assertFalse(loaded.enabled(Feature.CRYSTAL_COPY));assertEquals(999L,loaded.nextEligibleDay.get("wine:unrelated"));
        assertEquals(bytes,Files.readString(directory.resolve(key+".json")));
        assertTrue(bytes.contains("CONFIRMED_MANUALLY_HANDLED"));assertTrue(bytes.contains("society:fire_quartz"));
        assertFalse(bytes.contains("rawNbt"));
    }

    @Test void legacyProfileWithoutHistoryGetsAnEmptyListWithoutAWriteOrSchemaUpgrade() throws Exception {
        String key=ProfileStore.key("old profile absent manual audit");Path file=directory.resolve(key+".json");
        String bytes="{\"schemaVersion\":6}";Files.writeString(file,bytes);
        Profile loaded=new ProfileStore(directory).load(key);
        assertEquals(6,loaded.schemaVersion);assertTrue(loaded.manualWorkHotbarResolutions.isEmpty());
        assertEquals(bytes,Files.readString(file));
    }

    @Test void archivedOriginalMayHaveNoFacilityOrMayNowOverlapAProtectedConfiguredSlot() {
        Profile profile=new Profile();profile.hoeHotbarSlot=5;profile.manualWorkHotbarResolutions.add(entry());
        assertDoesNotThrow(()->ProfileStore.validate(profile));assertNull(profile.workHotbarLease);
        assertTrue(profile.artisanJobs.isEmpty());assertEquals(7,profile.schemaVersion);
        profile.workHotbarLease=LEASE;
        assertThrows(IllegalArgumentException.class,()->ProfileStore.validate(profile));
    }

    @Test void invalidAuditCannotOverwriteTheLastValidProfileAndBadLoadedBytesArePreserved() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("invalid manual audit");Path file=directory.resolve(key+".json");
        Profile good=new Profile();good.manualWorkHotbarResolutions.add(entry());store.save(key,good);
        String validBytes=Files.readString(file);Gson gson=new Gson();
        for(int cause=0;cause<7;cause++) {
            Profile bad=new Profile();
            switch(cause) {
                case 0 -> bad.manualWorkHotbarResolutions=null;
                case 1 -> bad.manualWorkHotbarResolutions=new ArrayList<>(Collections.nCopies(33,entry()));
                case 2 -> bad.manualWorkHotbarResolutions.add(null);
                case 3 -> bad.manualWorkHotbarResolutions.add(new ManualWorkHotbarResolution.Entry(LEASE,"b".repeat(64),entry().resolution(),777));
                case 4 -> bad.manualWorkHotbarResolutions.add(new ManualWorkHotbarResolution.Entry(LEASE,entry().confirmationKey(),null,777));
                case 5 -> bad.manualWorkHotbarResolutions.add(new ManualWorkHotbarResolution.Entry(LEASE,entry().confirmationKey(),entry().resolution(),-1));
                case 6 -> bad.manualWorkHotbarResolutions.add(new ManualWorkHotbarResolution.Entry(LEASE.withStage(null),entry().confirmationKey(),entry().resolution(),777));
            }
            assertThrows(IllegalArgumentException.class,()->store.save(key,bad));assertEquals(validBytes,Files.readString(file));
            String badBytes=gson.toJson(bad);
            if(cause==0)badBytes="{\"schemaVersion\":7,\"manualWorkHotbarResolutions\":null}";
            Files.writeString(file,badBytes);assertThrows(IOException.class,()->store.load(key));assertEquals(badBytes,Files.readString(file));
            Files.writeString(file,validBytes);
        }
    }
}
