package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WineBatchProfileStoreTest {
    @TempDir Path directory;
    private static final Pos A=new Pos(1,64,0),B=new Pos(2,64,0);
    @Test void absentOrExplicitNullLegacyScheduleLoadsWithoutRewritingOrInferringBatch() throws Exception {
        String key=ProfileStore.key("legacy-wine-batch");Path file=directory.resolve(key+".json");
        for(String json:List.of("{\"schemaVersion\":2}","{\"schemaVersion\":2,\"wineBatchSchedule\":null}")) {
            Files.writeString(file,json);assertNull(new ProfileStore(directory).load(key).wineBatchSchedule);assertEquals(json,Files.readString(file));
        }
    }
    @Test void activeRemainingOrderAndLatestFeedDaySurviveRoundTrip() throws Exception {
        Profile profile=profile();profile.wineBatchSchedule=new WineBatchSchedule(332,true,List.of(B),333L);
        profile.nextEligibleDay.put(WineBatchRules.key(A),339L);
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("partial-wine-batch");store.save(key,profile);
        Profile loaded=store.load(key);assertEquals(profile.wineBatchSchedule,loaded.wineBatchSchedule);
        assertEquals(profile.pois,loaded.pois);assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
        assertThrows(UnsupportedOperationException.class,() -> loaded.wineBatchSchedule.remaining().clear());
    }
    @Test void legacySchedulesWithoutSkippedFieldLoadWithoutLosingTheirRemainingOrFeedAudit() throws Exception {
        String key=ProfileStore.key("old-wine-schedule");Path file=directory.resolve(key+".json");
        String pos="{\"x\":2,\"y\":64,\"z\":0}";
        for(String skipped:List.of("",",\"skipped\":null")) {
            String json="{\"schemaVersion\":2,\"wineBatchSchedule\":{\"nextDueDay\":332,\"active\":true,\"remaining\":["+pos+"],\"latestFeedDay\":333"+skipped+"}}";
            Files.writeString(file,json);Profile loaded=new ProfileStore(directory).load(key);
            assertEquals(new WineBatchSchedule(332,true,List.of(B),333L),loaded.wineBatchSchedule);
            assertEquals(List.of(),loaded.wineBatchSchedule.skipped());assertEquals(json,Files.readString(file));
        }
    }
    @Test void partialSkippedPassRoundTripsWithoutSyntheticDeadlineOrFeedAudit() throws Exception {
        Profile profile=profile();profile.wineBatchSchedule=new WineBatchSchedule(332,true,List.of(B),null,
                List.of(new WineBatchSchedule.SkippedMember(A,"not_ready",333)));
        profile.nextEligibleDay.put(WineBatchRules.key(A),330L);
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("skipped-member");store.save(key,profile);
        Profile loaded=store.load(key);
        assertEquals(profile.wineBatchSchedule,loaded.wineBatchSchedule);assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
        assertNull(loaded.wineBatchSchedule.latestFeedDay());
        assertThrows(UnsupportedOperationException.class,() -> loaded.wineBatchSchedule.skipped().clear());
    }
    @Test void allSkippedCleanupPendingAndCompletedPassBothRoundTripWithoutInventingAFeed() throws Exception {
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("all-skipped");
        List<WineBatchSchedule.SkippedMember> skipped=List.of(new WineBatchSchedule.SkippedMember(A,"missing",332),
                new WineBatchSchedule.SkippedMember(B,"not_ready",332));
        for(boolean active:List.of(true,false)) {
            Profile profile=profile();profile.wineBatchSchedule=new WineBatchSchedule(active?332:338,active,List.of(),null,skipped);
            store.save(key,profile);Path file=directory.resolve(key+".json");String json=Files.readString(file);
            Profile loaded=store.load(key);assertEquals(profile.wineBatchSchedule,loaded.wineBatchSchedule);
            assertNull(loaded.wineBatchSchedule.latestFeedDay());assertTrue(loaded.nextEligibleDay.isEmpty());assertEquals(json,Files.readString(file));
        }
    }
    @Test void activeAllFedButCleanupPendingIsNotSilentlyCompletedByLoad() throws Exception {
        Profile profile=profile();profile.wineBatchSchedule=new WineBatchSchedule(332,true,List.of(),334L);
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("cleanup-pending");store.save(key,profile);
        Path file=directory.resolve(key+".json");String original=Files.readString(file);
        assertEquals(profile.wineBatchSchedule,store.load(key).wineBatchSchedule);assertEquals(original,Files.readString(file));
    }
    @Test void completedGlobalBoundaryRoundTripsWithoutChangingLegacyDeadlines() throws Exception {
        Profile profile=profile();profile.wineBatchSchedule=new WineBatchSchedule(340,false,List.of(),334L);
        profile.nextEligibleDay.put(WineBatchRules.key(A),338L);profile.nextEligibleDay.put(WineBatchRules.key(B),340L);
        ProfileStore store=new ProfileStore(directory);String key=ProfileStore.key("next-wine-boundary");store.save(key,profile);
        Profile loaded=store.load(key);assertEquals(profile.wineBatchSchedule,loaded.wineBatchSchedule);assertEquals(profile.nextEligibleDay,loaded.nextEligibleDay);
    }
    @Test void malformedOrDuplicatePendingMembersRejectLoadAndPreserveOriginalFile() throws Exception {
        String key=ProfileStore.key("invalid-wine-batch");Path file=directory.resolve(key+".json");
        String pos="{\"x\":1,\"y\":64,\"z\":0}";
        for(String schedule:List.of(
                "{\"nextDueDay\":-1,\"active\":false,\"remaining\":[]}",
                "{\"nextDueDay\":1,\"active\":true,\"remaining\":[]}",
                "{\"nextDueDay\":1,\"active\":false,\"remaining\":["+pos+"]}",
                "{\"nextDueDay\":1,\"active\":true,\"remaining\":["+pos+","+pos+"]}",
                "{\"nextDueDay\":1,\"active\":true,\"remaining\":null}",
                "{\"nextDueDay\":1,\"active\":true,\"remaining\":["+pos+"],\"latestFeedDay\":-1}")) {
            String json="{\"schemaVersion\":2,\"wineBatchSchedule\":"+schedule+"}";Files.writeString(file,json);
            assertThrows(IOException.class,() -> new ProfileStore(directory).load(key));assertEquals(json,Files.readString(file));
        }
    }
    @Test void malformedDuplicateOrOverlappingSkippedMembersRejectLoadAndPreserveOriginalFile() throws Exception {
        String key=ProfileStore.key("invalid-skipped-wine-member");Path file=directory.resolve(key+".json");
        String pos="{\"x\":1,\"y\":64,\"z\":0}",otherPos="{\"x\":2,\"y\":64,\"z\":0}";
        String skipped="{\"pos\":"+pos+",\"reason\":\"missing\",\"day\":332}";
        for(String members:List.of(
                "[null]","["+skipped+","+skipped+"]",
                "[{\"pos\":null,\"reason\":\"missing\",\"day\":332}]",
                "[{\"pos\":"+pos+",\"day\":332}]",
                "[{\"pos\":"+pos+",\"reason\":\" \",\"day\":332}]",
                "[{\"pos\":"+pos+",\"reason\":\""+"x".repeat(161)+"\",\"day\":332}]",
                "[{\"pos\":"+pos+",\"reason\":\"missing\",\"day\":-1}]")) {
            String json="{\"schemaVersion\":2,\"wineBatchSchedule\":{\"nextDueDay\":332,\"active\":true,\"remaining\":["+otherPos+"],\"skipped\":"+members+"}}";
            Files.writeString(file,json);assertThrows(IOException.class,() -> new ProfileStore(directory).load(key));assertEquals(json,Files.readString(file));
        }
        String overlap="{\"schemaVersion\":2,\"wineBatchSchedule\":{\"nextDueDay\":332,\"active\":true,\"remaining\":["+pos+"],\"skipped\":["+skipped+"]}}";
        Files.writeString(file,overlap);assertThrows(IOException.class,() -> new ProfileStore(directory).load(key));assertEquals(overlap,Files.readString(file));
    }
    private static Profile profile() {
        Profile profile=new Profile();profile.pois.add(new Poi(A,PoiKind.WINE_KEG,"A",null));profile.pois.add(new Poi(B,PoiKind.WINE_KEG,"B",null));return profile;
    }
}
