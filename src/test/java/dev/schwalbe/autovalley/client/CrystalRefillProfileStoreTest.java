package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrystalRefillProfileStoreTest {
    private static final Pos TARGET=new Pos(10,64,10),OTHER=new Pos(11,64,10);
    private static final String FIRE="society:fire_quartz";
    @TempDir Path directory;

    @Test void absentContinuationKeepsLegacyProfilesAtSixWithoutRewritingFilesOrEnablingCrystals() throws Exception {
        ProfileStore store=new ProfileStore(directory);
        assertEquals(6,new Profile().schemaVersion);assertTrue(new Profile().crystalRefills.isEmpty());
        for(int schema=1;schema<=6;schema++) {
            String key=ProfileStore.key("legacy crystal continuation "+schema);Path file=directory.resolve(key+".json");
            String json="{\"schemaVersion\":"+schema+",\"enabled\":{\"CRYSTAL_COPY\":false},\"nextEligibleDay\":{\"unrelated\":777}}";
            Files.writeString(file,json);Profile loaded=store.load(key);
            assertEquals(6,loaded.schemaVersion);assertTrue(loaded.crystalRefills.isEmpty());
            assertFalse(loaded.enabled(Feature.CRYSTAL_COPY));assertEquals(777L,loaded.nextEligibleDay.get("unrelated").longValue());
            assertEquals(json,Files.readString(file));assertFalse(Files.exists(directory.resolve(key+".json.bak")));
        }
    }

    @Test void all56PendingOriginalsRoundTripAtSchemaEightWhileOffEvenWithoutTheirRemovedJobs() throws Exception {
        Profile profile=new Profile();profile.enabled.put(Feature.CRYSTAL_COPY,false);profile.nextEligibleDay.put("unrelated",777L);
        int index=0;for(String input:CrystalCollection.BASE_OUTPUT_IDS) {
            Pos pos=new Pos(index++,64,0);profile.crystalRefills.put(key(pos),new CrystalRefill("removed_crystals",pos,input,321));
        }
        ProfileStore store=new ProfileStore(directory);String identity=ProfileStore.key("all pending crystal origins");
        store.save(identity,profile);assertEquals(8,profile.schemaVersion);
        Path file=directory.resolve(identity+".json");String bytes=Files.readString(file);Profile loaded=store.load(identity);
        assertEquals(8,loaded.schemaVersion);assertEquals(profile.crystalRefills,loaded.crystalRefills);
        assertEquals(56,loaded.crystalRefills.size());assertTrue(loaded.artisanJobs.isEmpty());
        assertFalse(loaded.enabled(Feature.CRYSTAL_COPY));assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
        assertEquals(bytes,Files.readString(file));
        assertFalse(bytes.contains("crystalInspection"));assertFalse(bytes.contains("rawNbt"));assertFalse(bytes.contains("generation"));
    }

    @Test void loadingExistingPendingIntentUpgradesOnlyInMemoryAndNeverInfersAReplacementOriginal() throws Exception {
        Profile profile=configured();profile.schemaVersion=6;
        CrystalRefill pending=new CrystalRefill(job(profile).id(),TARGET,FIRE,321);profile.crystalRefills.put(key(TARGET),pending);
        String identity=ProfileStore.key("pending in old schema"),json=new Gson().toJson(profile);
        Path file=directory.resolve(identity+".json");Files.writeString(file,json);
        Profile loaded=new ProfileStore(directory).load(identity);
        assertEquals(8,loaded.schemaVersion);assertEquals(Map.of(key(TARGET),pending),loaded.crystalRefills);
        assertEquals(CrystalRecipe.forInput(FIRE),CrystalRefillRules.pendingRecipe(loaded,job(loaded),TARGET));
        assertFalse(loaded.enabled(Feature.CRYSTAL_COPY));assertEquals(json,Files.readString(file));
        assertEquals(profile.artisanJobs,loaded.artisanJobs);assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
    }

    @Test void savePromotesSixToEightKeepsBackupAndNeverDowngradesAfterExplicitCompletion() throws Exception {
        Profile profile=configured();ProfileStore store=new ProfileStore(directory);String identity=ProfileStore.key("crystal schema upgrade");
        store.save(identity,profile);Path file=directory.resolve(identity+".json");String original=Files.readString(file);
        assertEquals(6,profile.schemaVersion);profile.crystalRefills.put(key(TARGET),new CrystalRefill(job(profile).id(),TARGET,FIRE,321));
        store.save(identity,profile);assertEquals(8,profile.schemaVersion);
        assertEquals(original,Files.readString(directory.resolve(identity+".json.bak")));
        Profile loaded=store.load(identity);String pendingBytes=Files.readString(file);
        CrystalRefillRules.complete(context(loaded,()->save(store,identity,loaded)),job(loaded),TARGET,323);
        Profile completed=store.load(identity);
        assertTrue(completed.crystalRefills.isEmpty());assertEquals(8,completed.schemaVersion);
        assertEquals(323L,completed.nextEligibleDay.get(CrystalCollection.scheduleKey(job(completed),TARGET)).longValue());
        assertFalse(completed.enabled(Feature.CRYSTAL_COPY));assertEquals(pendingBytes,Files.readString(directory.resolve(identity+".json.bak")));
    }

    @Test void failedAtomicCompletionPreservesBothOnDiskAndInMemoryIntentAndSchedule() throws Exception {
        Profile profile=configured();ArtisanJob job=job(profile);CrystalRefill pending=new CrystalRefill(job.id(),TARGET,FIRE,321);
        profile.crystalRefills.put(key(TARGET),pending);String schedule=CrystalCollection.scheduleKey(job,TARGET);
        profile.nextEligibleDay.put(schedule,999L);ProfileStore normal=new ProfileStore(directory);
        String identity=ProfileStore.key("crystal completion refusal");normal.save(identity,profile);
        Path file=directory.resolve(identity+".json");String bytes=Files.readString(file);
        ProfileStore refused=new ProfileStore(directory,new ProfileStore.FileOperations(){
            @Override public void replace(Path source,Path target) throws IOException {
                if(target.equals(file))throw new IOException("test atomic replacement refused");
                ProfileStore.FileOperations.super.replace(source,target);
            }
        },ignored->{});
        Context context=context(profile,()->save(refused,identity,profile));
        assertThrows(IllegalStateException.class,()->CrystalRefillRules.complete(context,job,TARGET,323));
        assertSame(pending,profile.crystalRefills.get(key(TARGET)));assertEquals(999L,profile.nextEligibleDay.get(schedule).longValue());
        assertEquals(8,profile.schemaVersion);assertEquals(bytes,Files.readString(file));
        Profile recovered=normal.load(identity);assertEquals(profile.crystalRefills,recovered.crystalRefills);
        assertEquals(profile.nextEligibleDay,recovered.nextEligibleDay);assertFalse(recovered.enabled(Feature.CRYSTAL_COPY));
        assertEquals(ProfileStore.Stage.REPLACE,refused.lastDiagnostic().stage());assertFalse(refused.lastDiagnostic().committed());
    }

    @Test void pendingIntentAndSeparateParkedWorkCustodyBothSurviveOffAndReload() throws Exception {
        Profile profile=configured();profile.crystalRefills.put(key(TARGET),new CrystalRefill(job(profile).id(),TARGET,FIRE,321));
        profile.workHotbarLease=new HotbarLease(Feature.CRYSTAL_COPY,12,1,
            new ItemData("minecraft:diamond_sword",1,0,null,false,900),"a".repeat(64),HotbarLease.Stage.PARKED);
        ProfileStore store=new ProfileStore(directory);String identity=ProfileStore.key("pending crystal plus borrowed hand");
        store.save(identity,profile);Profile loaded=store.load(identity);
        assertEquals(8,loaded.schemaVersion);assertEquals(profile.crystalRefills,loaded.crystalRefills);
        assertEquals(profile.workHotbarLease,loaded.workHotbarLease);assertFalse(loaded.enabled(Feature.CRYSTAL_COPY));
        assertTrue(loaded.manualWorkHotbarResolutions.isEmpty());
        loaded.artisanJobs.clear();store.save(identity,loaded);
        Profile removed=store.load(identity);assertEquals(profile.crystalRefills,removed.crystalRefills);
        assertEquals(profile.workHotbarLease,removed.workHotbarLease);assertTrue(removed.artisanJobs.isEmpty());
    }

    @Test void malformedPersistedRecordsAndNullLedgerPreserveOriginalBytesOnLoadFailure() throws Exception {
        String identity=ProfileStore.key("malformed crystal continuation"),position=key(TARGET);
        Path file=directory.resolve(identity+".json");ProfileStore store=new ProfileStore(directory);
        List<String> malformed=new ArrayList<>(List.of("{\"schemaVersion\":8,\"crystalRefills\":null}",
            "{\"schemaVersion\":8,\"crystalRefills\":{\""+position+"\":null}}"));
        for(CrystalRefill record:List.of(new CrystalRefill("crystals",TARGET,"society:unknown",321),
                new CrystalRefill("crystals",TARGET,"society:pristine_fire_quartz",321),
                new CrystalRefill("crystals",TARGET,FIRE,-1),new CrystalRefill("",TARGET,FIRE,321),
                new CrystalRefill("crystals",OTHER,FIRE,321),new CrystalRefill("crystals",new Pos(0,2048,0),FIRE,321))) {
            Profile profile=new Profile();profile.schemaVersion=8;profile.crystalRefills.put(position,record);
            malformed.add(new Gson().toJson(profile));
        }
        for(String json:malformed) {
            Files.writeString(file,json);assertThrows(IOException.class,()->store.load(identity));
            assertEquals(json,Files.readString(file));assertFalse(Files.exists(directory.resolve(identity+".json.bak")));
        }
    }

    @Test void invalidSaveCannotOverwriteThePreviouslyDurableSameOriginRecord() throws Exception {
        Profile profile=configured();CrystalRefill pending=new CrystalRefill(job(profile).id(),TARGET,FIRE,321);
        profile.crystalRefills.put(key(TARGET),pending);ProfileStore store=new ProfileStore(directory);
        String identity=ProfileStore.key("reject invalid crystal replacement");store.save(identity,profile);
        Path file=directory.resolve(identity+".json");String bytes=Files.readString(file);
        for(CrystalRefill invalid:Arrays.asList(null,new CrystalRefill(job(profile).id(),TARGET,"society:pristine_fire_quartz",321),
                new CrystalRefill(job(profile).id(),OTHER,FIRE,321),new CrystalRefill(job(profile).id(),TARGET,FIRE,-1))) {
            profile.crystalRefills.put(key(TARGET),invalid);
            assertThrows(IllegalArgumentException.class,()->store.save(identity,profile));
            assertEquals(bytes,Files.readString(file));assertEquals(Map.of(key(TARGET),pending),store.load(identity).crystalRefills);
            assertEquals(ProfileStore.Stage.VALIDATE,store.lastDiagnostic().stage());
        }
    }

    private static String key(Pos pos){return Profile.positionKey(pos);}
    private static ArtisanJob job(Profile profile){return profile.artisanJobs.get("crystals");}
    private static Profile configured() {
        Profile profile=new Profile();profile.enabled.put(Feature.CRYSTAL_COPY,false);profile.nextEligibleDay.put("unrelated",777L);
        profile.commodityStores.put("output",new CommodityStore("output","Exact crystals",CrystalCollection.BASE_OUTPUT_IDS,List.of(new Pos(12,64,10))));
        ArtisanJob job=new ArtisanJob("crystals",ArtisanRecipe.CRYSTAL_COLLECTION.id(),List.of(TARGET),"unused_input","output");
        profile.artisanJobs.put(job.id(),job);return profile;
    }
    private static Context context(Profile profile,Runnable checkpoint){return new Context(null,null,null,profile,new SessionState(),checkpoint);}
    private static void save(ProfileStore store,String key,Profile profile) {
        try{store.save(key,profile);}catch(IOException failure){throw new IllegalStateException("test checkpoint persistence failed",failure);}
    }
}
