package dev.schwalbe.autovalley.ui;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingLeafSettingsTest {
    private static final Gson GSON=new Gson();
    private static final LoggingLeafSettings.Boundary SAFE=new LoggingLeafSettings.Boundary(false,false,true,true,true,true,true,true);

    @Test void defaultIsOffAndRetainedLoggingDoesNotLockThisOneSetting() {
        Profile profile=retained();
        assertFalse(profile.loggingClearObstructingLeaves);
        assertTrue(LoggingLeafSettings.editable(profile,SAFE));
        assertFalse(LoggingLeafSettings.editable(null,SAFE));
        assertFalse(LoggingLeafSettings.editable(profile,null));
    }

    @Test void onlyTheExplicitOptInBitChangesAndAllWorksitesFlagsQueuesAndDatesArePreserved() {
        Profile profile=retained(); JsonObject before=GSON.toJsonTree(profile).getAsJsonObject(); int[] saves={0};
        List<Pos> remaining=profile.loggingRemainingPlots,replanting=profile.loggingReplantingPlots;
        LoggingLeafSettings.apply(profile,true,SAFE,()->{assertTrue(profile.loggingClearObstructingLeaves);saves[0]++;});
        assertTrue(profile.loggingClearObstructingLeaves); assertEquals(1,saves[0]);
        before.addProperty("loggingClearObstructingLeaves",true);
        assertEquals(before,GSON.toJsonTree(profile));
        assertSame(remaining,profile.loggingRemainingPlots); assertSame(replanting,profile.loggingReplantingPlots);
        assertTrue(profile.loggingRunActive); assertEquals(37L,profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
    }

    @Test void everyUnsafeBoundaryRejectsBothOnAndOffWithoutSavingOrMutatingAnything() {
        for(int index=0;index<8;index++) for(boolean desired:new boolean[]{false,true}) {
            boolean[] flags={false,false,true,true,true,true,true,true}; flags[index]=!flags[index];
            var unsafe=new LoggingLeafSettings.Boundary(flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],flags[6],flags[7]);
            Profile profile=retained(); profile.loggingClearObstructingLeaves=!desired;
            JsonElement before=GSON.toJsonTree(profile); int[] saves={0};
            assertFalse(LoggingLeafSettings.editable(profile,unsafe));
            assertThrows(IllegalStateException.class,()->LoggingLeafSettings.apply(profile,desired,unsafe,()->saves[0]++));
            assertEquals(0,saves[0]); assertEquals(before,GSON.toJsonTree(profile));
        }
    }

    @Test void aBorrowedItemOrUnconfirmedProductionOutputCannotBeBypassedByTheSetting() {
        Profile lease=retained();
        lease.loggingHotbarLease=new LoggingHotbarLease(9,0,new ItemData("minecraft:torch",5,0,null,false,0),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
        Profile output=retained(); String id="11111111-1111-1111-1111-111111111111";
        output.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.WINE,new Pos(5,64,0),1,1,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
        for(Profile profile:List.of(lease,output)) {
            JsonElement before=GSON.toJsonTree(profile);
            assertFalse(LoggingLeafSettings.editable(profile,SAFE));
            assertThrows(IllegalStateException.class,()->LoggingLeafSettings.apply(profile,true,SAFE,()->fail("Unsafe save")));
            assertEquals(before,GSON.toJsonTree(profile));
        }
    }

    @Test void saveFailureRollsBackOnlyTheSettingAndPreservesTheOriginalFailure() {
        for(boolean original:new boolean[]{false,true}) {
            Profile profile=retained(); profile.loggingClearObstructingLeaves=original;
            JsonElement before=GSON.toJsonTree(profile); int[] saves={0};
            RuntimeException failure=new IllegalStateException("save failed");
            RuntimeException actual=assertThrows(RuntimeException.class,()->LoggingLeafSettings.apply(profile,!original,SAFE,()->{saves[0]++;throw failure;}));
            assertSame(failure,actual); assertEquals(1,saves[0]); assertEquals(before,GSON.toJsonTree(profile));
        }
    }

    @Test void repeatingAnExplicitDesiredValueIsNotAToggleOrAStartCommand() {
        Profile profile=retained(); int[] saves={0};
        LoggingLeafSettings.apply(profile,true,SAFE,()->saves[0]++);
        LoggingLeafSettings.apply(profile,true,SAFE,()->saves[0]++);
        assertTrue(profile.loggingClearObstructingLeaves); assertEquals(2,saves[0]);
        LoggingLeafSettings.apply(profile,false,SAFE,()->saves[0]++);
        assertFalse(profile.loggingClearObstructingLeaves); assertTrue(profile.loggingRunActive);
        assertFalse(profile.enabled(Feature.LOGGING)); assertEquals(1,profile.loggingRemainingPlots.size());
    }

    @Test void bothLanguagesExplainTheLimitedScopeAndTheSafeOffBoundary() throws Exception {
        JsonObject english=language("en_us"),korean=language("ko_kr");
        for(String suffix:List.of("leaf_toggle","leaf_hint","leaf_edit_blocked","leaf_saved")) {
            String key="autovalley.logging."+suffix;
            assertTrue(english.has(key),key); assertTrue(korean.has(key),key);
            String en=english.get(key).getAsString(),ko=korean.get(key).getAsString();
            assertFalse(en.isBlank()); assertFalse(ko.isBlank());
            assertEquals(en.split("%s",-1).length,ko.split("%s",-1).length,key);
        }
        assertTrue(english.get("autovalley.logging.leaf_hint").getAsString().contains("registered unfinished 2x2 spruce base"));
        assertTrue(korean.get("autovalley.logging.leaf_hint").getAsString().contains("자동화 OFF"));
    }

    private static Profile retained() {
        Profile profile=new Profile(); Pos corner=new Pos(0,64,0);
        profile.loggingRunActive=true; profile.loggingPlots.add(new LoggingPlot("retained",corner));
        profile.loggingRemainingPlots.add(corner); profile.loggingReplantingPlots.add(corner);
        profile.nextEligibleDay.put(LoggingRules.DUE_KEY,37L);
        profile.enabled.put(Feature.LOGGING,false); profile.enabled.put(Feature.WINE,true);
        profile.pois.add(new Poi(new Pos(10,64,0),PoiKind.WOOD_CHEST,"wood",null));
        return profile;
    }
    private static JsonObject language(String name)throws Exception {
        try(InputStream input=LoggingLeafSettingsTest.class.getResourceAsStream("/assets/autovalley/lang/"+name+".json")) {
            assertNotNull(input); return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
