package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileEvidenceSnapshotTest {
    private static final String KEY = "0123456789abcdef01234567", TIME = "2026-09-12T06:00:00Z";
    private static Map<String,Object> persistence() {
        Map<String,Object> state = new LinkedHashMap<>();
        state.put("profileLoaded", true); state.put("memoryProfileMatchesContext", true);
        state.put("errorLatched", false); state.put("recoveryPending", false);
        state.put("lastSaveCommitted", null); state.put("lastSuccessfulCommitAtUtc", null);
        return state;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> child(Map<String,Object> parent, String key) { return (Map<String,Object>) parent.get(key); }
    private static Map<String,Object> capture(Profile profile) { return ProfileEvidenceSnapshot.capture(profile, KEY, TIME, persistence()); }
    private static LoggingHotbarLease lease() {
        return new LoggingHotbarLease(11, 0, new ItemData(LoggingRules.FIRE_LOG, 6, 0, null, false, 100), "a".repeat(64), LoggingHotbarLease.Stage.PARKED);
    }

    @Test void snapshotIsDetachedBoundedMemoryAndDistinguishesRegisteredFromRemaining() {
        Profile p = new Profile();
        p.enabled.put(Feature.LOGGING, true); p.loggingRunActive = true;
        for (int i=0; i<6; i++) p.loggingPlots.add(new LoggingPlot("PRIVATE_LABEL_DO_NOT_EMIT", new Pos(i,64,0)));
        p.loggingRemainingPlots.addAll(List.of(new Pos(0,64,0),new Pos(1,64,0),new Pos(2,64,0)));
        p.loggingReplantingPlots.add(new Pos(2,64,0)); p.loggingHotbarLease = lease();
        p.nextEligibleDay.put("logging:batch", 12L); p.nextEligibleDay.put("wine:1:2:3", 14L);
        Map<String,Object> snapshot = capture(p), logging = child(snapshot,"logging");
        assertEquals(true, snapshot.get("available")); assertEquals("CLIENT_THREAD_MEMORY",snapshot.get("source"));
        assertEquals(KEY,snapshot.get("profileKey")); assertEquals(TIME,snapshot.get("capturedAt"));
        assertNull(snapshot.get("diskStateMatchesMemory")); assertFalse(snapshot.containsKey("readAtUtc")); assertFalse(snapshot.containsKey("fileLastWriteUtc"));
        assertEquals(6,logging.get("registeredPlotCount")); assertEquals(3,logging.get("remainingCount")); assertEquals(1,logging.get("replantingCount"));
        assertEquals(12L,logging.get("dueDay")); assertEquals(2,child(snapshot,"schedule").get("count"));
        String before = new Gson().toJson(snapshot);
        assertFalse(before.contains("PRIVATE_LABEL_DO_NOT_EMIT")); assertFalse(before.contains("loggingPlots"));
        p.loggingRemainingPlots.clear(); p.loggingReplantingPlots.clear(); p.loggingHotbarLease=null;
        p.nextEligibleDay.clear(); p.enabled.put(Feature.LOGGING,false);
        assertEquals(before,new Gson().toJson(snapshot));
    }

    @Test void scheduleAndContinuationDigestsAreOrderIndependentAndChangeWithActualMemory() {
        Profile a = new Profile(), b = new Profile();
        a.nextEligibleDay = new LinkedHashMap<>(Map.of("wine:1",13L,"wine:2",17L,"logging:batch",12L));
        b.nextEligibleDay.put("logging:batch",12L); b.nextEligibleDay.put("wine:2",17L); b.nextEligibleDay.put("wine:1",13L);
        assertEquals(child(capture(a),"schedule").get("digest"),child(capture(b),"schedule").get("digest"));
        var families=(List<?>)child(capture(a),"schedule").get("families");
        assertTrue(families.contains(Map.of("family","wine","count",2L,"minimumDay",13L,"maximumDay",17L)));
        b.nextEligibleDay.put("wine:2",18L);
        assertNotEquals(child(capture(a),"schedule").get("digest"),child(capture(b),"schedule").get("digest"));
        b.loggingRemainingPlots.add(new Pos(1,64,0));
        assertNotEquals(child(capture(a),"logging").get("pendingDigest"),child(capture(b),"logging").get("pendingDigest"));
    }

    @Test void lastAttemptCommitIsNotCurrentRamDiskEqualityAndSafeFieldsAreWhitelisted() {
        Profile p=new Profile(); Map<String,Object> state=persistence();
        state.put("rawProfile",p); state.put("path","PRIVATE_PATH_DO_NOT_EMIT");
        var loaded=ProfileEvidenceSnapshot.capture(p,KEY,TIME,state);
        assertNull(child(loaded,"persistence").get("lastSaveCommitted"));
        state.put("lastSaveCommitted",true); state.put("lastSuccessfulCommitAtUtc",TIME);
        var saved=ProfileEvidenceSnapshot.capture(p,KEY,TIME,state);
        p.nextEligibleDay.put("logging:batch",50L);
        var modifiedRam=ProfileEvidenceSnapshot.capture(p,KEY,TIME,state);
        assertEquals(true,child(modifiedRam,"persistence").get("lastSaveCommitted")); assertNull(modifiedRam.get("diskStateMatchesMemory"));
        assertNotEquals(child(saved,"schedule").get("digest"),child(modifiedRam,"schedule").get("digest"));
        state.put("lastSaveCommitted",false); state.put("errorLatched",true); state.put("recoveryPending",true); state.put("stage","REPLACE");
        var failed=ProfileEvidenceSnapshot.capture(p,KEY,TIME,state);
        assertEquals(true,failed.get("available")); assertEquals(false,child(failed,"persistence").get("lastSaveCommitted"));
        assertEquals(true,child(failed,"persistence").get("errorLatched")); assertEquals("REPLACE",child(failed,"persistence").get("stage"));
        assertFalse(new Gson().toJson(failed).contains("PRIVATE_PATH_DO_NOT_EMIT")); assertFalse(child(failed,"persistence").containsKey("rawProfile"));
        assertEquals(false,child(saved,"persistence").get("errorLatched")); // Detached from later runtime changes.
    }

    @Test void pendingLedgersLeasesAndManualWaiversRemainSeparateEvidence() {
        Profile p=new Profile(); p.loggingHotbarLease=lease();
        p.workHotbarLease=new HotbarLease(Feature.CRYSTAL_COPY,12,1,lease().original(),"b".repeat(64));
        String id="00000000-0000-0000-0000-000000000001";
        var output=new PendingMachineOutput(id,Feature.PRESERVES,new Pos(1,64,0),12,null,3,PendingMachineOutput.Phase.AWAITING_PICKUP);
        p.pendingMachineOutputs.put(id,output);
        p.manualLoggingHotbarResolutions.add(new ManualLoggingHotbarResolution.Entry(lease(),ManualLoggingHotbarResolution.confirmationKey(lease()),ManualLoggingHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,11));
        for(int i=0;i<40;i++) p.crystalRefills.put("crystal:"+i,new CrystalRefill("crystal",new Pos(i,64,0),"society:fire_quartz",12));
        var snapshot=capture(p);
        assertEquals(true,snapshot.get("available")); assertEquals(1,snapshot.get("pendingMachineOutputCount"));
        assertEquals(List.of(output),snapshot.get("pendingMachineOutputs")); assertNotNull(snapshot.get("workHotbarLease"));
        assertNotNull(child(snapshot,"logging").get("lease")); assertEquals(1,snapshot.get("manualLoggingHotbarResolutionsCount"));
        assertEquals(0,snapshot.get("machineOutputResolutionsCount")); assertSame(output,p.pendingMachineOutputs.get(id));
        assertEquals(40,snapshot.get("crystalRefillCount")); assertEquals(true,snapshot.get("crystalRefillsTruncated"));
        assertEquals(32,((List<?>)snapshot.get("crystalRefills")).size());
        p.pendingMachineOutputs.clear();
        assertEquals(List.of(output),snapshot.get("pendingMachineOutputs"));
    }

    @Test void absentMalformedAndOversizedStateFailsClosedWithoutZeroingObligations() {
        assertEquals(false,ProfileEvidenceSnapshot.capture(new Profile(),null,TIME,persistence()).get("available"));
        assertEquals(false,capture(null).get("available"));
        Profile p=new Profile(); p.loggingRemainingPlots=null;
        assertEquals(false,capture(p).get("available")); assertFalse(capture(p).containsKey("logging"));
        p=new Profile(); for(int i=0;i<20001;i++) p.nextEligibleDay.put("wine:"+i,1L);
        assertEquals(false,capture(p).get("available"));
        p=new Profile(); p.pendingMachineOutputs.put("malformed",new PendingMachineOutput("malformed",Feature.PRESERVES,new Pos(1,64,0),12,null,1,PendingMachineOutput.Phase.AWAITING_PICKUP));
        assertEquals(false,capture(p).get("available")); assertEquals(1,p.pendingMachineOutputs.size());
    }

    @Test void growthUsesOnlyBoundedLoadedBaseReadsAndNeverInfersUnknownMaturity() {
        Profile p=new Profile(); p.loggingMode=LoggingMode.ALL_GROWN;
        p.loggingPlots.add(new LoggingPlot("private name",new Pos(0,64,0)));
        Map<Pos,BlockData> blocks=new HashMap<>(); Set<Pos> unloaded=new HashSet<>(); int[] calls={0};
        for(Pos pos:p.loggingPlots.get(0).plantingPositions()) blocks.put(pos,new BlockData(pos,LoggingRules.LOG,Map.of()));
        WorldAccess world=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class<?>[]{WorldAccess.class},(proxy,method,args)->{
            Pos pos=(Pos)args[0];
            if(method.getName().equals("loaded")) return !unloaded.contains(pos);
            if(method.getName().equals("block")) { assertFalse(unloaded.contains(pos)); calls[0]++; return blocks.getOrDefault(pos,new BlockData(pos,LoggingRules.SAPLING,Map.of())); }
            throw new AssertionError("Growth observation must not call "+method.getName());
        });
        var ready=ProfileEvidenceSnapshot.loggingGrowth(world,p,true);
        assertEquals(true,ready.get("allRegisteredReady")); assertEquals(4,calls[0]);
        Pos first=p.loggingPlots.get(0).corner(); blocks.put(first,new BlockData(first,LoggingRules.CHOPPED_LOG,Map.of()));
        assertEquals(false,ProfileEvidenceSnapshot.loggingGrowth(world,p,true).get("allRegisteredReady"));
        unloaded.add(first); calls[0]=0;
        var unknown=ProfileEvidenceSnapshot.loggingGrowth(world,p,true);
        assertEquals(true,unknown.get("unknown")); assertNull(unknown.get("allRegisteredReady")); assertEquals(3,calls[0]);
        calls[0]=0; assertNull(ProfileEvidenceSnapshot.loggingGrowth(world,p,false).get("allRegisteredReady")); assertEquals(0,calls[0]);
        for(int i=1;i<33;i++) p.loggingPlots.add(new LoggingPlot("plot"+i,new Pos(i*3,64,0)));
        unloaded.clear(); calls[0]=0;
        var bounded=ProfileEvidenceSnapshot.loggingGrowth(world,p,true);
        assertEquals(true,bounded.get("truncated")); assertNull(bounded.get("allRegisteredReady"));
        assertEquals(32,((List<?>)bounded.get("plots")).size()); assertEquals(128,calls[0]);
    }
}
