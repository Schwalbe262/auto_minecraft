package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HotbarLeaseProfileStoreTest {
    private static final ItemData ORIGINAL=new ItemData("minecraft:diamond_sword",1,0,null,false,937);
    private static final String FINGERPRINT="0123456789abcdef".repeat(4);
    @TempDir Path directory;
    private static HotbarLease lease(){return new HotbarLease(Feature.CRYSTAL_COPY,12,1,ORIGINAL,FINGERPRINT);}

    @Test void absentLeaseKeepsEveryLegacySchemaCompatibleWithoutRewritingItsFile() throws Exception {
        assertNull(new Profile().workHotbarLease);assertEquals(6,new Profile().schemaVersion);
        ProfileStore store=new ProfileStore(directory);
        for(int version=1;version<=6;version++) {
            String key=ProfileStore.key("legacy no work lease "+version);Path file=directory.resolve(key+".json");
            String json="{\"schemaVersion\":"+version+",\"enabled\":{\"WINE\":false}}";Files.writeString(file,json);
            Profile loaded=store.load(key);assertNull(loaded.workHotbarLease);assertEquals(6,loaded.schemaVersion);
            assertFalse(loaded.enabled(Feature.WINE));assertFalse(loaded.enabled(Feature.CRYSTAL_COPY));assertEquals(json,Files.readString(file));
        }
    }

    @Test void defaultStageAndStageChangesPreserveEveryCustodyField() {
        HotbarLease prepared=lease();assertTrue(prepared.valid());assertEquals(HotbarLease.Stage.PREPARED,prepared.stage());
        for(HotbarLease.Stage stage:HotbarLease.Stage.values()) {
            HotbarLease changed=prepared.withStage(stage);assertTrue(changed.valid());assertEquals(stage,changed.stage());
            assertEquals(prepared.owner(),changed.owner());assertEquals(prepared.sourceIndex(),changed.sourceIndex());
            assertEquals(prepared.hotbarSlot(),changed.hotbarSlot());assertSame(ORIGINAL,changed.original());assertEquals(FINGERPRINT,changed.fingerprint());
        }
        assertEquals(HotbarLease.Stage.PREPARED,prepared.stage());assertFalse(prepared.withStage(null).valid());
    }

    @Test void everyOwnerAndStageRoundTripsWhileItsFeatureIsOffAndItsFacilitiesAreAbsent() throws Exception {
        ProfileStore store=new ProfileStore(directory);
        for(Feature owner:List.of(Feature.SEED_MAKER,Feature.CRYSTAL_COPY,Feature.STARFRUIT))for(HotbarLease.Stage stage:HotbarLease.Stage.values()) {
            Profile p=new Profile();p.enabled.put(owner,false);p.nextEligibleDay.put("unchanged",579L);
            p.workHotbarLease=new HotbarLease(owner,35,8,ORIGINAL,FINGERPRINT,stage);
            String key=ProfileStore.key(owner.name()+stage);store.save(key,p);
            assertEquals(7,p.schemaVersion);String bytes=Files.readString(directory.resolve(key+".json"));
            Profile loaded=store.load(key);assertEquals(p.workHotbarLease,loaded.workHotbarLease);assertEquals(7,loaded.schemaVersion);
            assertFalse(loaded.enabled(owner));assertTrue(loaded.artisanJobs.isEmpty());assertTrue(loaded.fruitPatches.isEmpty());
            assertEquals(p.nextEligibleDay,loaded.nextEligibleDay);assertEquals(bytes,Files.readString(directory.resolve(key+".json")));
            assertTrue(bytes.contains(FINGERPRINT));assertFalse(bytes.contains("rawNbt"));
        }
    }

    @Test void firstLeaseSaveUpgradesToSevenAndClearingItNeverDowngradesTheProfile() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("work lease upgrade");Profile p=new Profile();
        store.save(key,p);Path file=directory.resolve(key+".json");String original=Files.readString(file);assertEquals(6,p.schemaVersion);
        p.workHotbarLease=lease();store.save(key,p);assertEquals(7,p.schemaVersion);
        assertEquals(original,Files.readString(directory.resolve(key+".json.bak")));
        Profile parked=store.load(key);assertNotNull(parked.workHotbarLease);parked.workHotbarLease=null;
        store.save(key,parked);Profile restored=store.load(key);assertEquals(7,restored.schemaVersion);assertNull(restored.workHotbarLease);
        assertTrue(Files.readString(file).contains("\"schemaVersion\": 7"));
    }

    @Test void structuralBoundsOwnerFingerprintAndOriginalStackAreFailClosed() {
        List<HotbarLease> invalid=new ArrayList<>();
        for(Feature owner:Feature.values())if(!Set.of(Feature.SEED_MAKER,Feature.CRYSTAL_COPY,Feature.STARFRUIT).contains(owner))
            invalid.add(new HotbarLease(owner,12,1,ORIGINAL,FINGERPRINT));
        invalid.add(new HotbarLease(null,12,1,ORIGINAL,FINGERPRINT));
        for(int source:List.of(-1,0,8,36,Integer.MAX_VALUE))invalid.add(new HotbarLease(Feature.CRYSTAL_COPY,source,1,ORIGINAL,FINGERPRINT));
        for(int slot:List.of(-1,9,Integer.MAX_VALUE))invalid.add(new HotbarLease(Feature.CRYSTAL_COPY,12,slot,ORIGINAL,FINGERPRINT));
        for(String fingerprint:Arrays.asList(null,"","a".repeat(63),"a".repeat(65),"g".repeat(64)))
            invalid.add(new HotbarLease(Feature.CRYSTAL_COPY,12,1,ORIGINAL,fingerprint));
        for(ItemData original:Arrays.asList(null,ItemData.EMPTY,new ItemData(null,1,0,null,false,99),
            new ItemData("minecraft:diamond",0,0,null,false,99),new ItemData("minecraft:diamond",65,0,null,false,99),
            new ItemData("minecraft:air",1,0,null,false,99),new ItemData("invalid item id",1,0,null,false,99)))
            invalid.add(new HotbarLease(Feature.CRYSTAL_COPY,12,1,original,FINGERPRINT));
        invalid.add(lease().withStage(null));
        for(HotbarLease value:invalid) {
            assertFalse(value.valid(),String.valueOf(value));Profile p=new Profile();p.workHotbarLease=value;
            assertThrows(IllegalArgumentException.class,()->ProfileStore.validate(p));
        }
        assertTrue(new HotbarLease(Feature.STARFRUIT,9,0,new ItemData("minecraft:bricks",64,-1,null,false,Integer.MAX_VALUE),"A".repeat(64)).valid());
    }

    @Test void configuredHoeAndLoggingAxeSlotsCannotBecomeWorkSlots() {
        for(boolean hoe:new boolean[]{false,true}) {
            Profile p=new Profile();p.hoeHotbarSlot=4;p.loggingAxeHotbarSlot=5;
            p.workHotbarLease=new HotbarLease(Feature.SEED_MAKER,9,hoe?4:5,ORIGINAL,FINGERPRINT);
            assertThrows(IllegalArgumentException.class,()->ProfileStore.validate(p));assertEquals(6,p.schemaVersion);
        }
    }

    @Test void aValidLoggingLeaseAndWorkLeaseCannotCoexistEvenInDifferentSlots() {
        Profile p=new Profile();p.loggingRunActive=true;
        p.loggingHotbarLease=new LoggingHotbarLease(14,2,ORIGINAL,FINGERPRINT,LoggingHotbarLease.Stage.PARKED);
        assertDoesNotThrow(()->ProfileStore.validate(p));p.workHotbarLease=lease();
        assertThrows(IllegalArgumentException.class,()->ProfileStore.validate(p));
        assertNotNull(p.loggingHotbarLease);assertNotNull(p.workHotbarLease);
    }

    @Test void invalidReplacementCannotOverwriteThePreviouslySavedCustodyRecord() throws Exception {
        Profile p=new Profile();p.workHotbarLease=lease().withStage(HotbarLease.Stage.PARKED);
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("preserve valid work lease");store.save(key,p);
        Path file=directory.resolve(key+".json");String before=Files.readString(file);
        p.workHotbarLease=new HotbarLease(Feature.WINE,12,1,ORIGINAL,FINGERPRINT);
        assertThrows(IllegalArgumentException.class,()->store.save(key,p));assertEquals(before,Files.readString(file));
        assertEquals(lease().withStage(HotbarLease.Stage.PARKED),store.load(key).workHotbarLease);
        assertEquals(ProfileStore.Stage.VALIDATE,store.lastDiagnostic().stage());
    }

    @Test void malformedPersistedLeaseOrFutureSchemaPreservesItsOriginalBytesOnLoadFailure() throws Exception {
        Gson json=new Gson();ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("malformed work lease");Path file=directory.resolve(key+".json");
        for(int cause=0;cause<3;cause++) {
            Profile p=new Profile();p.workHotbarLease=cause==0 ? lease().withStage(null) : cause==1
                ? new HotbarLease(Feature.HARVEST,12,1,ORIGINAL,FINGERPRINT) : lease();
            p.schemaVersion=cause==2 ? 9 : 7;String bytes=json.toJson(p);Files.writeString(file,bytes);
            assertThrows(IOException.class,()->store.load(key));assertEquals(bytes,Files.readString(file));
        }
    }
}
