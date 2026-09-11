package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualLoggingHotbarResolutionTest {
    private static final ItemData ORIGINAL=new ItemData(LoggingRules.FIRE_LOG,6,0,null,false,Integer.MAX_VALUE);
    private static final String HASH="a".repeat(64);
    private static final Pos PLOT=new Pos(10,64,10);
    private static LoggingHotbarLease lease() {return new LoggingHotbarLease(11,0,ORIGINAL,HASH,LoggingHotbarLease.Stage.PARKED);}

    @Test void explicitManualReleaseArchivesExactObligationAndKeepsUnfinishedLoggingWithoutConsultingInventory() {
        for(var stage:LoggingHotbarLease.Stage.values()) {
            Fixture f=new Fixture();f.profile.loggingHotbarLease=lease().withStage(stage);
            LoggingHotbarLease before=f.profile.loggingHotbarLease;String key=ManualLoggingHotbarResolution.confirmationKey(before);
            List<Pos> remaining=f.profile.loggingRemainingPlots,replanting=f.profile.loggingReplantingPlots;
            Map<String,Long> dates=f.profile.nextEligibleDay;Map<Feature,Boolean> enabled=f.profile.enabled;
            f.session.oneShotFeature=Feature.WINE;f.session.workHotbarOwner=Feature.SEED_MAKER;
            f.session.liveMachineOutputs.add("unrelated");f.session.activeMachineOutputId="unrelated";
            assertNull(ManualLoggingHotbarResolution.rejection(f.context,key));assertSame(before,f.profile.loggingHotbarLease);
            assertEquals(0,f.checkpoints);assertTrue(f.profile.manualLoggingHotbarResolutions.isEmpty());
            assertTrue(ManualLoggingHotbarResolution.confirm(f.context,key));
            assertNull(f.profile.loggingHotbarLease);assertTrue(f.profile.loggingRunActive);
            assertSame(remaining,f.profile.loggingRemainingPlots);assertSame(replanting,f.profile.loggingReplantingPlots);
            assertEquals(List.of(PLOT),remaining);assertEquals(List.of(PLOT),replanting);
            assertSame(dates,f.profile.nextEligibleDay);assertEquals(Map.of(LoggingRules.DUE_KEY,888L,"other",999L),dates);
            assertSame(enabled,f.profile.enabled);assertFalse(f.profile.enabled(Feature.LOGGING));
            assertEquals(Feature.WINE,f.session.oneShotFeature);assertEquals(Feature.SEED_MAKER,f.session.workHotbarOwner);
            assertEquals(Set.of("unrelated"),f.session.liveMachineOutputs);assertEquals("unrelated",f.session.activeMachineOutputId);
            assertEquals(9,f.profile.schemaVersion);assertEquals(1,f.checkpoints);
            assertEquals(List.of(new ManualLoggingHotbarResolution.Entry(before,key,
                ManualLoggingHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,777)),f.profile.manualLoggingHotbarResolutions);
            assertFalse(ManualLoggingHotbarResolution.confirm(f.context,key));assertEquals(1,f.checkpoints);
        }
    }

    @Test void confirmationKeyBindsSlotsFullFingerprintStageAndEverySupportedItemField() {
        LoggingHotbarLease base=lease();String key=ManualLoggingHotbarResolution.confirmationKey(base);
        assertTrue(key.matches("[a-f0-9]{64}"));
        List<LoggingHotbarLease> changed=new ArrayList<>(List.of(new LoggingHotbarLease(12,0,ORIGINAL,HASH,base.stage()),
            new LoggingHotbarLease(11,1,ORIGINAL,HASH,base.stage()),new LoggingHotbarLease(11,0,ORIGINAL,"b".repeat(64),base.stage()),
            base.withStage(LoggingHotbarLease.Stage.PREPARED),base.withStage(LoggingHotbarLease.Stage.RESTORING)));
        for(ItemData item:List.of(new ItemData(LoggingRules.LOG,6,0,null,false,Integer.MAX_VALUE),
                new ItemData(ORIGINAL.id(),9,0,null,false,Integer.MAX_VALUE),new ItemData(ORIGINAL.id(),6,1,null,false,Integer.MAX_VALUE),
                new ItemData(ORIGINAL.id(),6,0,0,false,Integer.MAX_VALUE),new ItemData(ORIGINAL.id(),6,0,null,false,123)))
            changed.add(new LoggingHotbarLease(11,0,item,HASH,base.stage()));
        Set<String> keys=new HashSet<>(Set.of(key));
        for(var alternative:changed) {
            assertTrue(keys.add(ManualLoggingHotbarResolution.confirmationKey(alternative)));
            Fixture f=new Fixture();f.profile.loggingHotbarLease=alternative;
            assertFalse(ManualLoggingHotbarResolution.confirm(f.context,key));assertSame(alternative,f.profile.loggingHotbarLease);
            assertEquals(0,f.checkpoints);assertTrue(f.profile.manualLoggingHotbarResolutions.isEmpty());
        }
    }

    @Test void invalidLeaseAndMalformedOrCaseChangedKeyCannotAuthorizeRelease() {
        Fixture f=new Fixture();String key=ManualLoggingHotbarResolution.confirmationKey(lease());
        for(String wrong:Arrays.asList(null,"","a".repeat(63),"a".repeat(65),"z".repeat(64),key.toUpperCase(Locale.ROOT)," "+key,key+" "))
            assertFalse(ManualLoggingHotbarResolution.confirm(f.context,wrong));
        List<LoggingHotbarLease> invalid=new ArrayList<>();invalid.add(null);invalid.add(lease().withStage(null));
        for(int source:List.of(-1,0,8,36))invalid.add(new LoggingHotbarLease(source,0,ORIGINAL,HASH));
        for(int slot:List.of(-1,9))invalid.add(new LoggingHotbarLease(11,slot,ORIGINAL,HASH));
        for(String fingerprint:Arrays.asList(null,"","g".repeat(64)))invalid.add(new LoggingHotbarLease(11,0,ORIGINAL,fingerprint));
        for(ItemData item:Arrays.asList(null,ItemData.EMPTY,new ItemData(null,6,0,null,false,1),
                new ItemData("invalid|id",6,0,null,false,1),new ItemData(ORIGINAL.id(),65,0,null,false,1),
                new ItemData(ORIGINAL.id(),0,0,null,false,1),new ItemData(ORIGINAL.id(),-1,0,null,false,1),
                new ItemData(LoggingRules.AXE,1,0,null,false,1),new ItemData("minecraft:iron_hoe",1,0,null,true,1)))
            invalid.add(new LoggingHotbarLease(11,0,item,HASH));
        for(var lease:invalid) {
            assertFalse(ManualLoggingHotbarResolution.validLease(lease));
            assertThrows(IllegalArgumentException.class,()->ManualLoggingHotbarResolution.confirmationKey(lease));
            f.profile.loggingHotbarLease=lease;assertFalse(ManualLoggingHotbarResolution.confirm(f.context,key));
        }
        assertEquals(0,f.checkpoints);assertTrue(f.profile.manualLoggingHotbarResolutions.isEmpty());
    }

    @Test void everyUnsafeBoundaryPreservesLeaseBatchHistoryAndDates() {
        for(int obstruction=0;obstruction<17;obstruction++) {
            Fixture f=new Fixture();
            switch(obstruction) {
                case 0 -> f.connected=false;
                case 1 -> f.grounded=false;
                case 2 -> f.sleeping=true;
                case 3 -> f.menuId=1;
                case 4 -> f.container=true;
                case 5 -> f.carried=ORIGINAL;
                case 6 -> f.busy=true;
                case 7 -> f.nativeFence="Unresolved native swap or request";
                case 8 -> f.navigationPending=new ActionOutcome(ActionOutcome.State.PENDING,"door");
                case 9 -> f.navigationPending=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"unconsumed door");
                case 10 -> f.profile.workHotbarLease=new HotbarLease(Feature.CRYSTAL_COPY,12,2,ORIGINAL,HASH);
                case 11 -> f.profile.pendingMachineOutputs.put("pending",new PendingMachineOutput("pending",Feature.PRESERVES,new Pos(1,64,0),0,null,1,PendingMachineOutput.Phase.AWAITING_PICKUP));
                case 12 -> f.dayTime=-1;
                case 13 -> f.profile.loggingRunActive=false;
                case 14 -> f.profile.loggingRemainingPlots=List.of(new Pos(90,64,90));
                case 15 -> f.profile.pendingMachineOutputs=null;
                case 16 -> f.profile.manualLoggingHotbarResolutions=null;
            }
            LoggingHotbarLease before=f.profile.loggingHotbarLease;var history=f.profile.manualLoggingHotbarResolutions;
            var remaining=f.profile.loggingRemainingPlots;var replanting=f.profile.loggingReplantingPlots;
            var dates=f.profile.nextEligibleDay;boolean active=f.profile.loggingRunActive;
            assertFalse(ManualLoggingHotbarResolution.confirm(f.context,ManualLoggingHotbarResolution.confirmationKey(before)),"case "+obstruction);
            assertSame(before,f.profile.loggingHotbarLease);assertSame(history,f.profile.manualLoggingHotbarResolutions);
            assertSame(remaining,f.profile.loggingRemainingPlots);assertSame(replanting,f.profile.loggingReplantingPlots);
            assertSame(dates,f.profile.nextEligibleDay);assertEquals(active,f.profile.loggingRunActive);
            assertEquals(6,f.profile.schemaVersion);assertEquals(0,f.checkpoints);
        }
    }

    @Test void unknownNativeAdapterCannotTreatNullPauseReasonAsSafeManualConsent() {
        Fixture f=new Fixture();Context unknown=new Context(f,new UnknownActions(),f,f.profile,f.session);
        assertFalse(ManualLoggingHotbarResolution.confirm(unknown,ManualLoggingHotbarResolution.confirmationKey(lease())));
        assertEquals(lease(),f.profile.loggingHotbarLease);assertTrue(f.profile.manualLoggingHotbarResolutions.isEmpty());
    }

    @Test void oldAuditCannotAutomaticallyClearAnIdenticalFutureLeaseAndHistoryStaysBounded() {
        Fixture f=new Fixture();String key=ManualLoggingHotbarResolution.confirmationKey(lease());
        for(int day=0;day<32;day++)f.profile.manualLoggingHotbarResolutions.add(entry(day));
        List<ManualLoggingHotbarResolution.Entry> previous=f.profile.manualLoggingHotbarResolutions;
        assertTrue(ManualLoggingHotbarResolution.confirm(f.context,key));
        assertEquals(32,previous.size());assertEquals(0,previous.get(0).resolvedDay());
        assertEquals(32,f.profile.manualLoggingHotbarResolutions.size());assertEquals(1,f.profile.manualLoggingHotbarResolutions.get(0).resolvedDay());
        assertEquals(777,f.profile.manualLoggingHotbarResolutions.get(31).resolvedDay());
        f.profile.loggingHotbarLease=lease();ManualLoggingHotbarResolution.validate(f.profile);
        assertNull(ManualLoggingHotbarResolution.rejection(f.context,key));assertNotNull(f.profile.loggingHotbarLease);assertEquals(1,f.checkpoints);
    }

    @Test void failedCheckpointRestoresExactLeaseHistorySchemaAndRetainsAllUnfinishedPlots() {
        Fixture f=new Fixture();String key=ManualLoggingHotbarResolution.confirmationKey(lease());
        for(int day=0;day<32;day++)f.profile.manualLoggingHotbarResolutions.add(entry(day));
        var before=f.profile.loggingHotbarLease;var history=f.profile.manualLoggingHotbarResolutions;
        var entries=List.copyOf(history);var remaining=f.profile.loggingRemainingPlots;var replanting=f.profile.loggingReplantingPlots;
        f.failCheckpoint=true;
        assertThrows(IllegalStateException.class,()->ManualLoggingHotbarResolution.confirm(f.context,key));
        assertSame(before,f.profile.loggingHotbarLease);assertSame(history,f.profile.manualLoggingHotbarResolutions);assertEquals(entries,history);
        assertSame(remaining,f.profile.loggingRemainingPlots);assertSame(replanting,f.profile.loggingReplantingPlots);
        assertTrue(f.profile.loggingRunActive);assertEquals(6,f.profile.schemaVersion);assertEquals(1,f.checkpoints);
        f.failCheckpoint=false;assertTrue(ManualLoggingHotbarResolution.confirm(f.context,key));assertEquals(2,f.checkpoints);
    }

    private static ManualLoggingHotbarResolution.Entry entry(long day) {
        return new ManualLoggingHotbarResolution.Entry(lease(),ManualLoggingHotbarResolution.confirmationKey(lease()),
            ManualLoggingHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,day);
    }
    private static class UnknownActions implements ActionPort {
        public boolean busy(){return false;}
        public String startRejection(){throw new AssertionError("Manual consent must not clear failures");}
        public String pauseReason(){throw new AssertionError("Manual consent must not reconcile late replies");}
        public long submit(Action action){throw new AssertionError("No game action is authorized");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No action ACK may be read or changed");}
        public boolean loggingHotbarRestored(LoggingHotbarLease lease){throw new AssertionError("Manual consent is not restoration proof");}
        public LoggingHotbarLease loggingHotbarGrowth(LoggingHotbarLease lease){throw new AssertionError("Manual consent cannot rebase custody");}
        public void move(Movement movement){throw new AssertionError();}public void stopMovement(){throw new AssertionError();}
        public void cancel(){throw new AssertionError("Manual consent cannot cancel an action");}
    }
    private static final class Fixture extends UnknownActions implements WorldAccess,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        boolean connected=true,grounded=true,sleeping,container,busy,failCheckpoint;int menuId,checkpoints;
        String nativeFence;ItemData carried=ItemData.EMPTY;ActionOutcome navigationPending;long dayTime=777*24000L+123;
        final Context context=new Context(this,this,this,profile,session,()->{
            checkpoints++;assertNull(profile.loggingHotbarLease);assertTrue(profile.loggingRunActive);
            assertEquals(List.of(PLOT),profile.loggingRemainingPlots);assertEquals(List.of(PLOT),profile.loggingReplantingPlots);
            assertFalse(profile.manualLoggingHotbarResolutions.isEmpty());
            if(failCheckpoint)throw new IllegalStateException("save failed");
        });
        Fixture() {
            profile.hoeHotbarSlot=8;profile.loggingHotbarLease=lease();profile.loggingRunActive=true;
            profile.loggingPlots.add(new LoggingPlot("retained",PLOT));profile.loggingRemainingPlots.add(PLOT);profile.loggingReplantingPlots.add(PLOT);
            profile.nextEligibleDay.put(LoggingRules.DUE_KEY,888L);profile.nextEligibleDay.put("other",999L);
        }
        public boolean busy(){return busy;}public String manualWorkHotbarResolutionRejection(){return nativeFence;}
        public long tick(){return 1;}public long dayTime(){return dayTime;}
        public PlayerState player(){return new PlayerState(0,64,0,0,0,grounded,sleeping,20,20,0,connected,true);}
        public MenuData menu(){return new MenuData(menuId,0,List.of(),carried,container);}
        public List<ItemSlot> inventory(){throw new AssertionError("Item absence does not prove or authorize manual consent");}
        public BlockData block(Pos pos){throw new AssertionError();}public boolean loaded(Pos pos){throw new AssertionError();}
        public boolean canStand(Pos pos){throw new AssertionError();}public boolean canTraverse(Pos a,Pos b){throw new AssertionError();}
        public List<BlockData> scan(Pos pos,int h,int v){throw new AssertionError();}public boolean mayPlace(int slot,ItemData item){throw new AssertionError();}
        public Result moveTo(Pos target,double reach,Context c){throw new AssertionError();}public void reset(){throw new AssertionError();}
        public ActionOutcome pendingInteractionOutcome(Context c){return navigationPending;}
    }
}
