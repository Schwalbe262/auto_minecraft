package dev.schwalbe.autovalley.ui;

import com.google.gson.*;
import dev.schwalbe.autovalley.core.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualLoggingHotbarConfirmationTest {
    private static final LoggingLeafSettings.Boundary SAFE=new LoggingLeafSettings.Boundary(false,false,true,true,true,true,true,true);
    private static Profile retained() {
        Profile profile=new Profile();
        profile.loggingHotbarLease=new LoggingHotbarLease(11,0,
            new ItemData("meadow:fire_log",9,0,null,false,99),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
        profile.loggingRunActive=true;
        profile.loggingRemainingPlots.add(new Pos(10,64,20));
        profile.loggingReplantingPlots.add(new Pos(10,64,20));
        profile.nextEligibleDay.put(LoggingRules.DUE_KEY,740L);
        return profile;
    }
    @Test void captureDisplaysTheExactOriginalWithoutChangingUnfinishedLoggingOrItsSchedule() {
        Profile profile=retained();String before=new Gson().toJson(profile);
        var selected=ManualLoggingHotbarConfirmation.capture(profile);
        assertNotNull(selected);assertSame(profile,selected.owner());assertSame(profile.loggingHotbarLease,selected.lease());
        assertEquals("meadow:fire_log",selected.lease().original().id());assertEquals(9,selected.lease().original().count());
        assertEquals(11,selected.lease().sourceIndex());assertEquals(0,selected.lease().hotbarSlot());
        assertTrue(selected.key().matches("[0-9a-f]{64}"));assertTrue(selected.matches(profile));
        assertTrue(ManualLoggingHotbarConfirmation.editable(profile,SAFE));
        assertEquals(before,new Gson().toJson(profile));
        assertNull(ManualLoggingHotbarConfirmation.capture(null));assertNull(ManualLoggingHotbarConfirmation.capture(new Profile()));
    }
    @Test void secondStageRejectsAnotherProfileAndChangesToEverySelectedLeaseField() {
        Profile profile=retained();var selected=ManualLoggingHotbarConfirmation.capture(profile);
        assertFalse(selected.matches(retained()),"An equal record in another connected profile is not this selection");
        LoggingHotbarLease lease=profile.loggingHotbarLease;ItemData item=lease.original();
        List<LoggingHotbarLease> replacements=new ArrayList<>(List.of(
            lease.withStage(LoggingHotbarLease.Stage.RESTORING),
            new LoggingHotbarLease(12,lease.hotbarSlot(),item,lease.fingerprint(),lease.stage()),
            new LoggingHotbarLease(lease.sourceIndex(),1,item,lease.fingerprint(),lease.stage()),
            new LoggingHotbarLease(lease.sourceIndex(),lease.hotbarSlot(),item,"b".repeat(64),lease.stage())));
        for(ItemData changed:List.of(new ItemData("minecraft:spruce_log",9,0,null,false,99),
                new ItemData(item.id(),10,0,null,false,99),new ItemData(item.id(),9,1,null,false,99),
                new ItemData(item.id(),9,0,5,false,99),new ItemData(item.id(),9,0,null,false,98)))
            replacements.add(new LoggingHotbarLease(lease.sourceIndex(),lease.hotbarSlot(),changed,lease.fingerprint(),lease.stage()));
        for(LoggingHotbarLease replacement:replacements) {
            profile.loggingHotbarLease=replacement;assertFalse(selected.matches(profile),replacement.toString());
        }
        profile.loggingHotbarLease=lease;
        assertFalse(new ManualLoggingHotbarConfirmation.Selection(profile,lease,"c".repeat(64)).matches(profile));
        profile.loggingHotbarLease=null;assertFalse(selected.matches(profile));
    }
    @Test void quietConnectedSettingsBoundaryIsRequiredWithoutImplicitlyPausingOrStarting() {
        Profile profile=retained();String before=new Gson().toJson(profile);
        for(int index=0;index<8;index++) {
            boolean[] flags={false,false,true,true,true,true,true,true};flags[index]=!flags[index];
            var boundary=new LoggingLeafSettings.Boundary(flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],flags[6],flags[7]);
            assertFalse(ManualLoggingHotbarConfirmation.editable(profile,boundary),"boundary "+index);
        }
        assertFalse(ManualLoggingHotbarConfirmation.editable(null,SAFE));
        assertFalse(ManualLoggingHotbarConfirmation.editable(profile,null));
        assertEquals(before,new Gson().toJson(profile));
        profile.enabled.put(Feature.LOGGING,false);
        assertTrue(ManualLoggingHotbarConfirmation.editable(profile,SAFE),"An explicit acknowledgement does not enable logging");
        assertFalse(profile.enabled(Feature.LOGGING));assertTrue(profile.loggingRunActive);
        assertEquals(1,profile.loggingRemainingPlots.size());assertEquals(1,profile.loggingReplantingPlots.size());
    }
    @Test void workCustodyAndPendingMachineOutputsCannotBeWaivedByLoggingConfirmation() {
        Profile profile=retained();
        profile.workHotbarLease=new HotbarLease(Feature.CRYSTAL_COPY,12,1,
            new ItemData("minecraft:torch",1,0,null,false,99),"b".repeat(64),HotbarLease.Stage.PARKED);
        assertFalse(ManualLoggingHotbarConfirmation.editable(profile,SAFE));
        assertFalse(ManualWorkHotbarConfirmation.editable(profile,SAFE));
        profile.workHotbarLease=null;
        String id="11111111-1111-1111-1111-111111111111";
        profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.WINE,new Pos(10,64,0),1,1,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
        assertFalse(ManualLoggingHotbarConfirmation.editable(profile,SAFE));assertNotNull(profile.loggingHotbarLease);
        profile.pendingMachineOutputs=null;assertFalse(ManualLoggingHotbarConfirmation.editable(profile,SAFE));
    }
    @Test void malformedLeaseCannotBeSelectedOrMadeEditable() {
        Profile profile=retained();profile.loggingHotbarLease=profile.loggingHotbarLease.withStage(null);
        assertNull(ManualLoggingHotbarConfirmation.capture(profile));assertFalse(ManualLoggingHotbarConfirmation.editable(profile,SAFE));
    }
    @Test void bothLanguagesExplainThatLoggingAndReplantingRemainAfterManualResolution() throws Exception {
        JsonObject english=language("en_us"),korean=language("ko_kr");
        for(String suffix:List.of("manual_effect","manual_saved")) {
            String key="autovalley.logging_hotbar."+suffix;
            assertTrue(english.has(key),key);assertTrue(korean.has(key),key);
            assertFalse(english.get(key).getAsString().isBlank());assertFalse(korean.get(key).getAsString().isBlank());
            assertEquals(english.get(key).getAsString().split("%s",-1).length,korean.get(key).getAsString().split("%s",-1).length);
        }
        assertTrue(english.get("autovalley.logging_hotbar.manual_saved").getAsString().contains("OFF"));
        assertTrue(english.get("autovalley.logging_hotbar.manual_effect").getAsString().contains("unfinished logging and replanting"));
        assertTrue(korean.get("autovalley.logging_hotbar.manual_effect").getAsString().contains("남은 벌목·재식재는 보존"));
    }
    private static JsonObject language(String name)throws Exception {
        try(InputStream input=ManualLoggingHotbarConfirmationTest.class.getResourceAsStream("/assets/autovalley/lang/"+name+".json")) {
            assertNotNull(input);return JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
