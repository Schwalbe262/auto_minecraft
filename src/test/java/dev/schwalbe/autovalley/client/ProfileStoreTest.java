package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.io.UncheckedIOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfileStoreTest {
    @TempDir Path directory;

    @Test void legacyHarvestPolicyMarkerIsDetectedWithoutChangingDiskOrInventingHarvestDates() throws Exception {
        String key=ProfileStore.key("legacy-harvest-policy");Path file=directory.resolve(key+".json");
        String json="{\"schemaVersion\":6,\"nextEligibleDay\":{\"harvest:Ancient\":615}}";Files.writeString(file,json);
        Profile loaded=new ProfileStore(directory).load(key);
        assertEquals(0,loaded.strictHarvestTimingVersion);assertEquals(615L,loaded.nextEligibleDay.get("harvest:Ancient"));
        assertEquals(json,Files.readString(file));
    }
    @Test void strictHarvestPolicyMarkerAndDatesSurviveSaveReload() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("strict-harvest-policy");
        Profile p=new Profile();p.nextEligibleDay.put("harvest:Ancient",623L);store.save(key,p);
        Profile loaded=store.load(key);assertEquals(1,loaded.strictHarvestTimingVersion);assertEquals(p.nextEligibleDay,loaded.nextEligibleDay);
    }
    @Test void unsupportedHarvestPolicyVersionCannotBeSaved() {
        for(int version:List.of(-1,2)) {Profile p=new Profile();p.strictHarvestTimingVersion=version;assertThrows(IllegalArgumentException.class,()->ProfileStore.validate(p));}
    }
    @Test void nullStoredHarvestPolicyCannotBypassTheLegacyTransition() throws Exception {
        String key=ProfileStore.key("null-harvest-policy");Path file=directory.resolve(key+".json");
        String json="{\"strictHarvestTimingVersion\":null}";Files.writeString(file,json);
        assertEquals(0,new ProfileStore(directory).load(key).strictHarvestTimingVersion);assertEquals(json,Files.readString(file));
    }

    @Test void fullInventoryHarvestDefaultsEnabledAndExplicitOptOutRoundTrips() throws Exception {
        Profile profile=new Profile(); assertTrue(profile.continueHarvestWhenFull);
        profile.continueHarvestWhenFull=false;
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("full-inventory-harvest-setting");
        store.save(key,profile);
        assertFalse(store.load(key).continueHarvestWhenFull);
        assertTrue(Files.readString(directory.resolve(key+".json")).contains("\"continueHarvestWhenFull\": false"));
    }

    @Test void absentFullInventorySettingUsesDefaultWithoutRewritingOldProfile() throws Exception {
        String key=ProfileStore.key("old-harvest-setting"); Path file=directory.resolve(key+".json");
        String old="{\"schemaVersion\":2,\"obsoleteHarvestOption\":false}";
        Files.writeString(file,old);
        assertTrue(new ProfileStore(directory).load(key).continueHarvestWhenFull);
        assertEquals(old,Files.readString(file));
    }

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

    @Test void legacyProfilesHaveNoTomatoMigrationTargetsAndLoadingDoesNotRewriteThem() throws Exception {
        String key=ProfileStore.key("legacy-tomato-layout"); Path file=directory.resolve(key+".json");
        String legacy="{\"schemaVersion\":2}"; Files.writeString(file,legacy);
        Profile loaded=new ProfileStore(directory).load(key);
        assertNotNull(loaded.tomatoStorageTargets); assertTrue(loaded.tomatoStorageTargets.isEmpty());
        assertEquals(legacy,Files.readString(file));
    }

    @Test void inertLegacyLayoutAndClassifierArePreservedWithoutReclassifyingStorage() throws Exception {
        Profile profile=new Profile(); Pos legacy=new Pos(-3,66,7), empty=new Pos(4,69,-9);
        profile.pois.add(new Poi(legacy,PoiKind.TOMATO_CHEST,"legacy actual grade",3));
        profile.pois.add(new Poi(empty,PoiKind.STORAGE_CANDIDATE,"empty future destination",null));
        profile.tomatoStorageTargets.put(Profile.positionKey(legacy),0);
        profile.tomatoStorageTargets.put(Profile.positionKey(empty),2);
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("tomato-layout-roundtrip");
        store.save(key,profile); Profile loaded=store.load(key);
        assertEquals(profile.tomatoStorageTargets,loaded.tomatoStorageTargets); assertEquals(profile.pois,loaded.pois);
        assertEquals(3,loaded.pois.get(0).classifier(),"loading must preserve inert legacy metadata instead of silently rewriting it");
        assertNull(loaded.pois.get(1).classifier());
    }

    @Test void obsoleteLayoutDoesNotRequireMigrationBeforeSavingCommodityStorage() throws Exception {
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("tomato-layout-ignored");
        Profile profile=new Profile();
        profile.pois.add(new Poi(new Pos(1,64,2),PoiKind.TOMATO_CHEST,"All tomatoes",null));
        profile.tomatoStorageTargets.put("old-layout-label",4);
        profile.tomatoStorageTargets.put("1:64:2",null);
        store.save(key,profile);
        assertEquals(profile.pois,store.load(key).pois);
        profile.tomatoStorageTargets=null;
        assertDoesNotThrow(() -> store.save(key,profile));
    }

    @Test void obsoleteExplicitTomatoTargetMapDoesNotBlockReadOnlyLoad() throws Exception {
        String key=ProfileStore.key("malformed-tomato-layout"); Path file=directory.resolve(key+".json");
        for (String json:List.of("{\"schemaVersion\":2,\"tomatoStorageTargets\":null}",
            "{\"schemaVersion\":2,\"tomatoStorageTargets\":{\"1:64:2\":4}}")) {
            Files.writeString(file,json);
            assertNotNull(new ProfileStore(directory).load(key));
            assertEquals(json,Files.readString(file));
        }
    }

    @Test void commodityTomatoWarehousesAndLegacyGradeMetadataRoundTripTogether() throws Exception {
        Profile profile=new Profile();
        profile.pois.add(new Poi(new Pos(0,64,0),PoiKind.TOMATO_CHEST,"All grades",null));
        int position=1;
        for (int grade:new int[]{0,1,2,3,4,-1,Integer.MIN_VALUE,Integer.MAX_VALUE})
            profile.pois.add(new Poi(new Pos(position++,64,0),PoiKind.TOMATO_CHEST,"Legacy "+grade,grade));
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("commodity-tomatoes");
        store.save(key,profile);assertEquals(profile.pois,store.load(key).pois);
    }

    @Test void wineStorageStillRequiresKnownNonnegativeCohort() {
        for (Integer classifier:Arrays.asList(null,-1)) {
            Profile profile=new Profile();profile.pois.add(new Poi(new Pos(0,64,0),PoiKind.WINE_CHEST,"Wine",classifier));
            assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(profile));
        }
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

    @Test void inertTomatoGradeIsAcceptedButInvalidDeadlinesAndDuplicateLocationsAreRejected() {
        Profile grade=new Profile(); grade.pois.add(new Poi(new Pos(0,64,0),PoiKind.TOMATO_CHEST,"bad",4));
        assertDoesNotThrow(() -> ProfileStore.validate(grade));
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

    @Test void arbitraryFarmListRoundTripsAndKeepsExistingDestinations() throws Exception {
        Profile profile=new Profile();
        for (int n=0;n<25;n++) profile.farms.add(new Farm("field "+n,new Pos(n*10,64,0),new Pos(n*10+5,65,5)));
        profile.pois.add(new Poi(new Pos(1,64,9),PoiKind.BED,"bed",null));
        profile.pois.add(new Poi(new Pos(3,64,9),PoiKind.SHIPPING_BIN,"shipping",null));
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("many-fields");
        store.save(key,profile); Profile loaded=store.load(key);
        assertEquals(profile.farms,loaded.farms); assertEquals(profile.pois,loaded.pois);
        loaded.farms.remove(12); store.save(key,loaded);
        assertEquals(24,store.load(key).farms.size());
        assertEquals(profile.farms.get(24),store.load(key).farms.get(23));
    }

    @Test void legacyProfileStartsWithAnEmptyDurableOutputLedger() throws Exception {
        String key=ProfileStore.key("legacy-output-ledger");
        Files.writeString(directory.resolve(key+".json"),"{\"schemaVersion\":1}");
        Profile loaded=new ProfileStore(directory).load(key);
        assertNotNull(loaded.pendingMachineOutputs); assertTrue(loaded.pendingMachineOutputs.isEmpty());
        assertNotNull(loaded.machineOutputResolutions); assertTrue(loaded.machineOutputResolutions.isEmpty());
    }

    @Test void legacyMigrationIsReadOnlyUntilSaveAndWritesADowngradeGuardedSchema() throws Exception {
        String key=ProfileStore.key("schema-migration"); Path file=directory.resolve(key+".json");
        String legacy="{\"schemaVersion\":1,\"wineCycleDays\":7}";
        Files.writeString(file,legacy);
        ProfileStore store=new ProfileStore(directory); Profile loaded=store.load(key);
        assertEquals(6,loaded.schemaVersion); assertEquals(7,loaded.wineCycleDays);
        assertEquals(legacy,Files.readString(file),"reading must not overwrite the user's original profile");
        PendingMachineOutput output=pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP);
        loaded.pendingMachineOutputs.put(output.id(),output); store.save(key,loaded);
        Profile saved=store.load(key);
        assertEquals(6,saved.schemaVersion,"older schemas must not silently ignore commodity and artisan registrations");
        assertEquals(Map.of(output.id(),output),saved.pendingMachineOutputs);
        assertEquals(legacy,Files.readString(directory.resolve(key+".json.bak")));
    }

    @Test void futureProfileSchemaIsPreservedAndRejected() throws Exception {
        String key=ProfileStore.key("future-schema"); Path file=directory.resolve(key+".json");
        String future="{\"schemaVersion\":9}"; Files.writeString(file,future);
        assertThrows(IOException.class,() -> new ProfileStore(directory).load(key));
        assertEquals(future,Files.readString(file));
    }

    @Test void pendingPhasesWineCohortsAndDistinctAcknowledgementsRoundTrip() throws Exception {
        Profile profile=new Profile();
        PendingMachineOutput wine=pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION);
        PendingMachineOutput jars=pending(Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_PICKUP);
        profile.pendingMachineOutputs.put(wine.id(),wine); profile.pendingMachineOutputs.put(jars.id(),jars);
        for (MachineOutputLedger.Resolution reason:MachineOutputLedger.Resolution.values()) {
            profile.machineOutputResolutions.add(new MachineOutputLedger.ResolutionEntry(
                    pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP),reason,20));
        }
        profile.nextEligibleDay.put("wine:1:64:2",25L);
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("pending-roundtrip");
        store.save(key,profile); Profile loaded=store.load(key);
        assertEquals(profile.pendingMachineOutputs,loaded.pendingMachineOutputs);
        assertEquals(profile.machineOutputResolutions,loaded.machineOutputResolutions);
        assertEquals(12,loaded.pendingMachineOutputs.get(wine.id()).expectedWineYear());
        assertNull(loaded.pendingMachineOutputs.get(jars.id()).expectedWineYear());
        assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
    }

    @Test void restoredPickupCannotClearFromMatchingInventoryButExplicitAcknowledgementPersists() throws Exception {
        Profile profile=new Profile();
        PendingMachineOutput wine=pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP);
        PendingMachineOutput jars=pending(Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION);
        profile.pendingMachineOutputs.put(wine.id(),wine); profile.pendingMachineOutputs.put(jars.id(),jars);
        profile.nextEligibleDay.put("wine:1:64:2",25L);
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("reconnected-output");
        store.save(key,profile); Profile loaded=store.load(key);
        Context context=restoredContext(loaded,() -> {
            try { store.save(key,loaded); } catch (IOException failure) { throw new UncheckedIOException(failure); }
        });
        assertEquals(0,MachineOutputLedger.reconcile(context));
        assertEquals(profile.pendingMachineOutputs,store.load(key).pendingMachineOutputs);
        MachineOutputLedger.resolveByUser(context,wine.id(),MachineOutputLedger.Resolution.RECOVERED_AND_HANDLED);
        Profile after=store.load(key);
        assertEquals(Map.of(jars.id(),jars),after.pendingMachineOutputs);
        assertEquals(profile.nextEligibleDay,after.nextEligibleDay);
        assertEquals(MachineOutputLedger.Resolution.RECOVERED_AND_HANDLED,after.machineOutputResolutions.get(0).resolution());
        MachineOutputLedger.resolveByUser(context,jars.id(),MachineOutputLedger.Resolution.CONFIRMED_LOST);
        assertTrue(store.load(key).pendingMachineOutputs.isEmpty());
        assertEquals(MachineOutputLedger.Resolution.CONFIRMED_LOST,store.load(key).machineOutputResolutions.get(1).resolution());
    }

    @Test void invalidDurableOutputRecordsCannotOverwriteExistingProfile() throws Exception {
        ProfileStore store=new ProfileStore(directory); String key=ProfileStore.key("invalid-output");
        store.save(key,new Profile()); String original=Files.readString(directory.resolve(key+".json"));
        String id=UUID.randomUUID().toString(); Pos machine=new Pos(1,64,2);
        List<PendingMachineOutput> invalid=Arrays.asList(
                null,
                new PendingMachineOutput("not-a-uuid",Feature.WINE,machine,3,12,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.HARVEST,machine,3,12,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,null,3,12,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,machine,-1,12,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,machine,3,null,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,machine,3,-1,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.PRESERVES,machine,3,12,1,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,machine,3,12,0,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,machine,3,12,2305,PendingMachineOutput.Phase.AWAITING_PICKUP),
                new PendingMachineOutput(id,Feature.WINE,machine,3,12,1,null));
        for (PendingMachineOutput output:invalid) {
            Profile profile=new Profile(); profile.pendingMachineOutputs.put(output==null?id:output.id(),output);
            assertThrows(IllegalArgumentException.class,() -> store.save(key,profile));
            assertEquals(original,Files.readString(directory.resolve(key+".json")));
        }
    }

    @Test void ledgerSizeKeysNullCollectionsAndResolutionValidityAreBounded() {
        Profile mismatch=new Profile(); PendingMachineOutput output=pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP);
        mismatch.pendingMachineOutputs.put(UUID.randomUUID().toString(),output);
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(mismatch));
        Profile pendingSize=new Profile();
        for (int i=0;i<17;i++) { PendingMachineOutput next=pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP); pendingSize.pendingMachineOutputs.put(next.id(),next); }
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(pendingSize));
        Profile historySize=new Profile();
        for (int i=0;i<65;i++) historySize.machineOutputResolutions.add(new MachineOutputLedger.ResolutionEntry(
                pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP),MachineOutputLedger.Resolution.CONFIRMED_LOST,20));
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(historySize));
        Profile missingPending=new Profile(); missingPending.pendingMachineOutputs=null;
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(missingPending));
        Profile missingHistory=new Profile(); missingHistory.machineOutputResolutions=null;
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(missingHistory));
        for (MachineOutputLedger.ResolutionEntry entry:Arrays.asList(null,
                new MachineOutputLedger.ResolutionEntry(output,null,20),
                new MachineOutputLedger.ResolutionEntry(output,MachineOutputLedger.Resolution.CONFIRMED_LOST,-1),
                new MachineOutputLedger.ResolutionEntry(null,MachineOutputLedger.Resolution.CONFIRMED_LOST,20),
                new MachineOutputLedger.ResolutionEntry(pending(Feature.WINE,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION),
                        MachineOutputLedger.Resolution.AUTOMATIC_PICKUP,20))) {
            Profile bad=new Profile(); bad.machineOutputResolutions.add(entry);
            assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(bad));
        }
        Profile duplicate=new Profile(); duplicate.pendingMachineOutputs.put(output.id(),output);
        duplicate.machineOutputResolutions.add(new MachineOutputLedger.ResolutionEntry(output,MachineOutputLedger.Resolution.CONFIRMED_LOST,20));
        assertThrows(IllegalArgumentException.class,() -> ProfileStore.validate(duplicate));
    }

    @Test void malformedDurableLedgerIsPreservedInsteadOfSilentlyDiscarded() throws Exception {
        String key=ProfileStore.key("malformed-ledger"); Path file=directory.resolve(key+".json");
        for (String json:List.of("{\"schemaVersion\":1,\"pendingMachineOutputs\":null}",
                "{\"schemaVersion\":1,\"machineOutputResolutions\":null}",
                "{\"schemaVersion\":1,\"pendingMachineOutputs\":{\"invalid\":{\"id\":\"invalid\",\"feature\":\"WINE\"}}}")) {
            Files.writeString(file,json);
            assertThrows(IOException.class,() -> new ProfileStore(directory).load(key));
            assertEquals(json,Files.readString(file));
        }
    }

    private static PendingMachineOutput pending(Feature feature,PendingMachineOutput.Phase phase) {
        return new PendingMachineOutput(UUID.randomUUID().toString(),feature,new Pos(1,64,2),3,
                feature==Feature.WINE?12:null,4,phase);
    }

    private static Context restoredContext(Profile profile,Runnable checkpoint) {
        WorldAccess world=new WorldAccess() {
            @Override public long tick() { return 100; }
            @Override public long dayTime() { return 20*24000; }
            @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true); }
            @Override public BlockData block(Pos pos) { return new BlockData(pos,"minecraft:air",Map.of()); }
            @Override public boolean loaded(Pos pos) { return true; }
            @Override public boolean canStand(Pos feet) { return true; }
            @Override public boolean canTraverse(Pos from,Pos to) { return true; }
            @Override public List<BlockData> scan(Pos center,int radius,int vertical) { return List.of(); }
            @Override public List<ItemSlot> inventory() { return List.of(new ItemSlot(0,0,true,new ItemData(ItemData.WINE,64,0,12,false,0))); }
            @Override public MenuData menu() { return new MenuData(0,0,inventory(),ItemData.EMPTY,false); }
            @Override public boolean mayPlace(int slot,ItemData item) { return true; }
        };
        return new Context(world,null,null,profile,new SessionState(),checkpoint);
    }
}
