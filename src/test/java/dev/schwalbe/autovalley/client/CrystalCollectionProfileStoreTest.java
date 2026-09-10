package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CrystalCollectionProfileStoreTest {
    @TempDir Path directory;
    @Test void oldImportLoadsAsGenericCollectionWithoutChangingOriginalFileUntilNormalSave() throws Exception {
        Profile old=new Profile();String id="jade_crystalariums",key=ProfileStore.key("crystal migration fixture");
        ArtisanJob job=new ArtisanJob(id,ArtisanRecipe.JADE_CRYSTAL.id(),List.of(new Pos(2,64,0)),"jade_stock","jade_stock");
        old.artisanJobs.put(id,job);
        old.commodityStores.put("jade_stock",new CommodityStore("jade_stock","옥 결정복제기 재료·산출물",Set.of("society:jade"),List.of(new Pos(1,64,0))));
        old.nextEligibleDay.put(job.scheduleKey(job.machines().get(0)),900L);old.enabled.put(Feature.CRYSTAL_COPY,false);
        ProfileStore store=new ProfileStore(directory);store.save(key,old);Path file=directory.resolve(key+".json");
        byte[] original=Files.readAllBytes(file);Profile migrated=store.load(key);
        assertArrayEquals(original,Files.readAllBytes(file),"read-time migration must not overwrite the on-disk profile");
        assertEquals(ArtisanRecipe.CRYSTAL_COLLECTION,migrated.artisanJobs.get(id).recipe());
        assertEquals(CrystalCollection.BASE_OUTPUT_IDS,migrated.commodityStores.get("jade_stock").items());
        assertEquals(old.nextEligibleDay,migrated.nextEligibleDay);assertFalse(migrated.enabled.get(Feature.CRYSTAL_COPY));
        store.save(key,migrated);
        assertArrayEquals(original,Files.readAllBytes(directory.resolve(key+".json.bak")));
        Profile roundTrip=store.load(key);
        assertEquals(migrated.artisanJobs,roundTrip.artisanJobs);assertEquals(migrated.commodityStores,roundTrip.commodityStores);
    }
}
