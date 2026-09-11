package dev.schwalbe.autovalley.ui;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualWorkHotbarConfirmationTest {
    private static final LoggingLeafSettings.Boundary SAFE=new LoggingLeafSettings.Boundary(false,false,true,true,true,true,true,true);
    private static Profile retained() {
        Profile profile=new Profile();
        profile.workHotbarLease=new HotbarLease(Feature.CRYSTAL_COPY,9,1,
            new ItemData("society:fire_quartz",2,0,null,false,99),"a".repeat(64),HotbarLease.Stage.PARKED);
        return profile;
    }
    @Test void captureIsReadOnlyAndKeepsTheExactProfileAndSelectedRecord() {
        Profile profile=retained();String before=new Gson().toJson(profile);
        var selected=ManualWorkHotbarConfirmation.capture(profile);
        assertNotNull(selected);assertSame(profile,selected.owner());assertSame(profile.workHotbarLease,selected.lease());
        assertTrue(selected.key().matches("[0-9a-f]{64}"));assertTrue(selected.matches(profile));
        assertEquals(before,new Gson().toJson(profile));assertNull(ManualWorkHotbarConfirmation.capture(null));
        assertNull(ManualWorkHotbarConfirmation.capture(new Profile()));
    }
    @Test void switchingProfilesOrChangingAnySelectedCustodyRecordCannotAcknowledgeANewRecord() {
        Profile profile=retained();var selected=ManualWorkHotbarConfirmation.capture(profile);
        assertFalse(selected.matches(retained()),"An equivalent record from another world/profile is not this selection");
        HotbarLease lease=profile.workHotbarLease;
        for(HotbarLease replacement:List.of(lease.withStage(HotbarLease.Stage.RESTORING),
                new HotbarLease(lease.owner(),10,lease.hotbarSlot(),lease.original(),lease.fingerprint(),lease.stage()),
                new HotbarLease(lease.owner(),lease.sourceIndex(),2,lease.original(),lease.fingerprint(),lease.stage()),
                new HotbarLease(lease.owner(),lease.sourceIndex(),lease.hotbarSlot(),lease.original(),"b".repeat(64),lease.stage()))) {
            profile.workHotbarLease=replacement;assertFalse(selected.matches(profile));
        }
        profile.workHotbarLease=null;assertFalse(selected.matches(profile));
    }
    @Test void everyExistingSettingsBoundaryMustBeQuietWithoutImplicitlyPausingOrStarting() {
        Profile profile=retained();String before=new Gson().toJson(profile);
        assertTrue(ManualWorkHotbarConfirmation.editable(profile,SAFE));
        for(int index=0;index<8;index++) {
            boolean[] flags={false,false,true,true,true,true,true,true};flags[index]=!flags[index];
            var boundary=new LoggingLeafSettings.Boundary(flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],flags[6],flags[7]);
            assertFalse(ManualWorkHotbarConfirmation.editable(profile,boundary),"boundary "+index);
        }
        assertFalse(ManualWorkHotbarConfirmation.editable(profile,null));assertFalse(ManualWorkHotbarConfirmation.editable(null,SAFE));
        assertEquals(before,new Gson().toJson(profile));
    }
    @Test void anotherLeaseOrPendingOutputIsNotResolvedByThisUiPermission() {
        Profile profile=retained();
        profile.loggingHotbarLease=new LoggingHotbarLease(10,2,profile.workHotbarLease.original(),"b".repeat(64));
        assertFalse(ManualWorkHotbarConfirmation.editable(profile,SAFE));
        profile.loggingHotbarLease=null;String id="11111111-1111-1111-1111-111111111111";
        profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.WINE,new Pos(10,64,0),1,1,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
        assertFalse(ManualWorkHotbarConfirmation.editable(profile,SAFE));assertNotNull(profile.workHotbarLease);
        assertEquals(1,profile.pendingMachineOutputs.size());
    }
    @Test void bothLanguagesExplainManualResolutionWithoutInventingItemMovementOrAnAutomaticStart() throws Exception {
        JsonObject english=language("en_us"),korean=language("ko_kr");
        for(String suffix:List.of("manual_open","manual_hint","selected","slots","manual_statement","manual_effect",
                "no_actions","manual_confirm","manual_saved","manual_blocked","changed")) {
            String key="autovalley.work_hotbar."+suffix;assertTrue(english.has(key),key);assertTrue(korean.has(key),key);
            String en=english.get(key).getAsString(),ko=korean.get(key).getAsString();assertFalse(en.isBlank());assertFalse(ko.isBlank());
            assertEquals(en.split("%s",-1).length,ko.split("%s",-1).length,key);
        }
        assertTrue(english.get("autovalley.work_hotbar.manual_saved").getAsString().contains("OFF"));
        assertTrue(korean.get("autovalley.work_hotbar.manual_statement").getAsString().contains("직접 아이템을 정리"));
        assertTrue(korean.get("autovalley.work_hotbar.no_actions").getAsString().contains("하지 않습니다"));
    }
    private static JsonObject language(String name)throws Exception {
        try(InputStream input=ManualWorkHotbarConfirmationTest.class.getResourceAsStream("/assets/autovalley/lang/"+name+".json")) {
            assertNotNull(input);return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
