package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TomatoStockProfileStoreTest {
    @TempDir Path directory;
    @Test void missingFieldKeepsThreeDayDefaultWithoutRewritingTheProfile() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("stock refresh legacy");Path file=directory.resolve(key+".json");
        String original="{\"schemaVersion\":5,\"nextEligibleDay\":{\"farm:kept\":30}}";Files.writeString(file,original);
        Profile profile=store.load(key);assertEquals(3,profile.tomatoStockRefreshDays);assertEquals(6,profile.schemaVersion);
        assertEquals(original,Files.readString(file));assertFalse(Files.exists(directory.resolve(key+".json.bak")));
    }
    @Test void refreshRangeRoundTripsWithoutSerializingRamStock() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("stock refresh range");
        for(int days:new int[]{1,3,28}) {
            Profile profile=new Profile();profile.tomatoStockRefreshDays=days;store.save(key,profile);
            assertEquals(days,store.load(key).tomatoStockRefreshDays);
            String json=Files.readString(directory.resolve(key+".json"));assertFalse(json.contains("tomatoStockCache"));assertFalse(json.contains("fullSurveyTick"));
        }
    }
    @Test void invalidRangeCannotReplaceAnExistingSavedProfile() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("stock refresh invalid");store.save(key,new Profile());
        Path file=directory.resolve(key+".json");String original=Files.readString(file);
        for(int days:new int[]{-1,0,29,Integer.MAX_VALUE}) {
            Profile invalid=new Profile();invalid.tomatoStockRefreshDays=days;assertThrows(IllegalArgumentException.class,()->store.save(key,invalid));
            assertEquals(original,Files.readString(file));
        }
    }
}
