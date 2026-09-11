package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManualLoggingHotbarProfileStoreTest {
    private static final String HASH="a".repeat(64);
    private static final ItemData ORIGINAL=new ItemData(LoggingRules.FIRE_LOG,6,0,null,false,Integer.MAX_VALUE);
    private static final LoggingHotbarLease LEASE=new LoggingHotbarLease(11,0,ORIGINAL,HASH,LoggingHotbarLease.Stage.PARKED);
    private static final Pos PLOT=new Pos(10,64,10);
    @TempDir Path directory;
    private static ManualLoggingHotbarResolution.Entry entry(LoggingHotbarLease lease) {
        return new ManualLoggingHotbarResolution.Entry(lease,ManualLoggingHotbarResolution.confirmationKey(lease),
            ManualLoggingHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,777);
    }
    private static Profile retained() {
        Profile p=new Profile();p.hoeHotbarSlot=8;p.loggingPlots.add(new LoggingPlot("remaining",PLOT));
        p.loggingRunActive=true;p.loggingRemainingPlots.add(PLOT);p.loggingReplantingPlots.add(PLOT);
        p.nextEligibleDay.put(LoggingRules.DUE_KEY,888L);return p;
    }

    @Test void absentManualLoggingHistoryLoadsEverySupportedLegacySchemaWithoutRewritingOrInventingConsent() throws Exception {
        ProfileStore store=new ProfileStore(directory);assertTrue(new Profile().manualLoggingHotbarResolutions.isEmpty());
        for(int schema=1;schema<=8;schema++) {
            String key=ProfileStore.key("manual logging legacy "+schema);Path file=directory.resolve(key+".json");
            String bytes="{\"schemaVersion\":"+schema+",\"enabled\":{\"LOGGING\":false}}";Files.writeString(file,bytes);
            Profile loaded=store.load(key);assertTrue(loaded.manualLoggingHotbarResolutions.isEmpty());
            assertEquals(Math.max(schema,6),loaded.schemaVersion);assertFalse(loaded.enabled(Feature.LOGGING));
            assertEquals(bytes,Files.readString(file));
        }
    }

    @Test void everyArchivedStageRoundTripsWithOriginalFingerprintAndAllUnfinishedPlotsIntact() throws Exception {
        ProfileStore store=new ProfileStore(directory);
        for(var stage:LoggingHotbarLease.Stage.values()) {
            Profile p=retained();p.manualLoggingHotbarResolutions.add(entry(LEASE.withStage(stage)));
            String key=ProfileStore.key("manual logging "+stage);store.save(key,p);assertEquals(9,p.schemaVersion);
            Path file=directory.resolve(key+".json");String bytes=Files.readString(file);Profile loaded=store.load(key);
            assertEquals(p.manualLoggingHotbarResolutions,loaded.manualLoggingHotbarResolutions);
            assertEquals(9,loaded.schemaVersion);assertNull(loaded.loggingHotbarLease);assertTrue(loaded.loggingRunActive);
            assertEquals(p.loggingRemainingPlots,loaded.loggingRemainingPlots);assertEquals(p.loggingReplantingPlots,loaded.loggingReplantingPlots);
            assertEquals(p.nextEligibleDay,loaded.nextEligibleDay);assertFalse(loaded.enabled(Feature.LOGGING));
            assertEquals(bytes,Files.readString(file));assertTrue(bytes.contains(HASH));assertFalse(bytes.contains("rawNbt"));
        }
    }

    @Test void loadingAnOldSchemaWithAuditUpgradesOnlyInMemoryAndNeverClearsALaterIdenticalLease() throws Exception {
        Profile p=retained();p.loggingHotbarLease=LEASE;p.manualLoggingHotbarResolutions.add(entry(LEASE));
        String key=ProfileStore.key("manual logging audit migration");Path file=directory.resolve(key+".json");
        String bytes=new Gson().toJson(p);Files.writeString(file,bytes);ProfileStore store=new ProfileStore(directory);
        Profile loaded=store.load(key);assertEquals(9,loaded.schemaVersion);assertEquals(LEASE,loaded.loggingHotbarLease);
        assertTrue(loaded.loggingRunActive);assertEquals(List.of(PLOT),loaded.loggingRemainingPlots);assertEquals(bytes,Files.readString(file));
        loaded.manualLoggingHotbarResolutions.clear();store.save(key,loaded);assertEquals(9,store.load(key).schemaVersion);
    }

    @Test void invalidAuditNeverOverwritesThePreviouslySavedLeaseAndBatch() throws Exception {
        ProfileStore store=new ProfileStore(directory);Profile p=retained();p.loggingHotbarLease=LEASE;
        String key=ProfileStore.key("manual logging invalid audit");store.save(key,p);Path file=directory.resolve(key+".json");String bytes=Files.readString(file);
        var valid=entry(LEASE);
        for(var invalid:Arrays.asList(null,new ManualLoggingHotbarResolution.Entry(null,valid.confirmationKey(),valid.resolution(),777),
                new ManualLoggingHotbarResolution.Entry(LEASE,"b".repeat(64),valid.resolution(),777),
                new ManualLoggingHotbarResolution.Entry(LEASE,valid.confirmationKey(),null,777),
                new ManualLoggingHotbarResolution.Entry(LEASE,valid.confirmationKey(),valid.resolution(),-1),
                new ManualLoggingHotbarResolution.Entry(LEASE.withStage(null),valid.confirmationKey(),valid.resolution(),777))) {
            p.manualLoggingHotbarResolutions=new ArrayList<>(Arrays.asList(invalid));
            assertThrows(IllegalArgumentException.class,()->store.save(key,p));assertEquals(bytes,Files.readString(file));
            assertEquals(LEASE,store.load(key).loggingHotbarLease);
        }
        p.manualLoggingHotbarResolutions=null;assertThrows(IllegalArgumentException.class,()->store.save(key,p));
        p.manualLoggingHotbarResolutions=new ArrayList<>(Collections.nCopies(33,valid));assertThrows(IllegalArgumentException.class,()->store.save(key,p));
        assertEquals(bytes,Files.readString(file));
    }

    @Test void malformedPersistedHistoryAndUnsupportedFutureSchemaPreserveTheirExactBytes() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("malformed manual logging");Path file=directory.resolve(key+".json");
        for(String bytes:List.of("{\"schemaVersion\":10}","{\"schemaVersion\":9,\"manualLoggingHotbarResolutions\":null}",
                "{\"schemaVersion\":9,\"manualLoggingHotbarResolutions\":[null]}")) {
            Files.writeString(file,bytes);assertThrows(IOException.class,()->store.load(key));assertEquals(bytes,Files.readString(file));
        }
    }
}
