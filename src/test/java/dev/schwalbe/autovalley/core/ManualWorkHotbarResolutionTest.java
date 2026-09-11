package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManualWorkHotbarResolutionTest {
    private static final ItemData ORIGINAL=new ItemData("society:fire_quartz",2,0,null,false,Integer.MAX_VALUE);
    private static final String HASH="a".repeat(64);
    private static HotbarLease lease() {return new HotbarLease(Feature.CRYSTAL_COPY,9,5,ORIGINAL,HASH,HotbarLease.Stage.PARKED);}

    @Test void explicitConfirmationArchivesMissingOriginalWithoutReadingInventoryOrTouchingAnyAction() {
        Fixture f=new Fixture();HotbarLease original=f.profile.workHotbarLease;String key=ManualWorkHotbarResolution.confirmationKey(original);
        Map<String,Long> dates=new HashMap<>(f.profile.nextEligibleDay);Map<Feature,Boolean> enabled=new EnumMap<>(f.profile.enabled);
        f.session.oneShotFeature=Feature.WINE;f.session.liveMachineOutputs.add("unrelated");f.session.activeMachineOutputId="unrelated";
        assertNull(ManualWorkHotbarResolution.rejection(f.context,key));assertTrue(f.profile.manualWorkHotbarResolutions.isEmpty());
        assertSame(original,f.profile.workHotbarLease);assertEquals(0,f.checkpoints);
        assertTrue(ManualWorkHotbarResolution.confirm(f.context,key));
        assertNull(f.profile.workHotbarLease);assertNull(f.session.workHotbarOwner);assertEquals(7,f.profile.schemaVersion);
        assertEquals(List.of(new ManualWorkHotbarResolution.Entry(original,key,
            ManualWorkHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,777)),f.profile.manualWorkHotbarResolutions);
        assertEquals(1,f.checkpoints);assertEquals(dates,f.profile.nextEligibleDay);assertEquals(enabled,f.profile.enabled);
        assertEquals(Feature.WINE,f.session.oneShotFeature);assertEquals(Set.of("unrelated"),f.session.liveMachineOutputs);
        assertEquals("unrelated",f.session.activeMachineOutputId);
        assertFalse(ManualWorkHotbarResolution.confirm(f.context,key));assertEquals(1,f.checkpoints);
    }

    @Test void confirmationDigestBindsEveryLeaseFieldIncludingItemMetadataAndStage() {
        HotbarLease base=lease();String key=ManualWorkHotbarResolution.confirmationKey(base);assertTrue(key.matches("[a-f0-9]{64}"));
        List<HotbarLease> alternatives=new ArrayList<>();
        alternatives.add(new HotbarLease(Feature.SEED_MAKER,9,5,ORIGINAL,HASH,base.stage()));
        alternatives.add(new HotbarLease(base.owner(),10,5,ORIGINAL,HASH,base.stage()));
        alternatives.add(new HotbarLease(base.owner(),9,6,ORIGINAL,HASH,base.stage()));
        alternatives.add(new HotbarLease(base.owner(),9,5,ORIGINAL,"b".repeat(64),base.stage()));
        alternatives.add(base.withStage(HotbarLease.Stage.RESTORING));
        for(ItemData changed:List.of(new ItemData("society:jade",2,0,null,false,Integer.MAX_VALUE),
            new ItemData(ORIGINAL.id(),3,0,null,false,Integer.MAX_VALUE),new ItemData(ORIGINAL.id(),2,1,null,false,Integer.MAX_VALUE),
            new ItemData(ORIGINAL.id(),2,0,0,false,Integer.MAX_VALUE),new ItemData(ORIGINAL.id(),2,0,null,true,Integer.MAX_VALUE),
            new ItemData(ORIGINAL.id(),2,0,null,false,999)))
            alternatives.add(new HotbarLease(base.owner(),9,5,changed,HASH,base.stage()));
        Set<String> keys=new HashSet<>(Set.of(key));
        for(HotbarLease changed:alternatives) {
            assertTrue(keys.add(ManualWorkHotbarResolution.confirmationKey(changed)),changed.toString());
            Fixture f=new Fixture();f.profile.workHotbarLease=changed;
            assertFalse(ManualWorkHotbarResolution.confirm(f.context,key));assertSame(changed,f.profile.workHotbarLease);
            assertEquals(0,f.checkpoints);assertTrue(f.profile.manualWorkHotbarResolutions.isEmpty());
        }
        assertThrows(IllegalArgumentException.class,()->ManualWorkHotbarResolution.confirmationKey(null));
        assertThrows(IllegalArgumentException.class,()->ManualWorkHotbarResolution.confirmationKey(base.withStage(null)));
    }

    @Test void missingMalformedOrCaseChangedKeysNeverCountAsExplicitConfirmation() {
        Fixture f=new Fixture();String key=ManualWorkHotbarResolution.confirmationKey(f.profile.workHotbarLease);
        for(String wrong:Arrays.asList(null,"","a".repeat(63),"a".repeat(65),"z".repeat(64),key.toUpperCase(Locale.ROOT)," "+key,key+" "))
            assertFalse(ManualWorkHotbarResolution.confirm(f.context,wrong));
        assertEquals(lease(),f.profile.workHotbarLease);assertEquals(0,f.checkpoints);
        assertTrue(f.profile.manualWorkHotbarResolutions.isEmpty());
    }

    @Test void everyUnsafeBoundaryLeavesLeaseAuditSessionAndDatesUntouched() {
        for(int obstruction=0;obstruction<13;obstruction++) {
            Fixture f=new Fixture();
            switch(obstruction) {
                case 0 -> f.connected=false;
                case 1 -> f.grounded=false;
                case 2 -> f.sleeping=true;
                case 3 -> f.menuId=1;
                case 4 -> f.container=true;
                case 5 -> f.carried=ORIGINAL;
                case 6 -> f.busy=true;
                case 7 -> f.nativeFence="Unresolved native request";
                case 8 -> f.navigationPending=new ActionOutcome(ActionOutcome.State.PENDING,"pending door");
                case 9 -> f.navigationPending=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"unconsumed door outcome");
                case 10 -> f.profile.loggingHotbarLease=new LoggingHotbarLease(12,2,ORIGINAL,HASH);
                case 11 -> f.profile.pendingMachineOutputs.put("pending",new PendingMachineOutput("pending",Feature.PRESERVES,new Pos(1,64,0),0,null,1,PendingMachineOutput.Phase.AWAITING_PICKUP));
                case 12 -> f.dayTime=-1;
            }
            HotbarLease before=f.profile.workHotbarLease;List<ManualWorkHotbarResolution.Entry> history=f.profile.manualWorkHotbarResolutions;
            Map<String,Long> dates=new HashMap<>(f.profile.nextEligibleDay);
            assertFalse(ManualWorkHotbarResolution.confirm(f.context,ManualWorkHotbarResolution.confirmationKey(before)),"case "+obstruction);
            assertSame(before,f.profile.workHotbarLease);assertSame(history,f.profile.manualWorkHotbarResolutions);
            assertEquals(Feature.CRYSTAL_COPY,f.session.workHotbarOwner);assertEquals(dates,f.profile.nextEligibleDay);
            assertEquals(6,f.profile.schemaVersion);assertEquals(0,f.checkpoints);
        }
    }

    @Test void unknownAdapterCannotApproveCleanupThroughDefaultNullStartOrPauseMethods() {
        Fixture f=new Fixture();Context unknown=new Context(f,new UnknownActions(),f,f.profile,f.session);
        assertFalse(ManualWorkHotbarResolution.confirm(unknown,ManualWorkHotbarResolution.confirmationKey(f.profile.workHotbarLease)));
        assertEquals(lease(),f.profile.workHotbarLease);assertTrue(f.profile.manualWorkHotbarResolutions.isEmpty());
    }

    @Test void newestThirtyTwoEntriesAreKeptAndOldAuditNeverAutomaticallyClearsAFutureLease() {
        Fixture f=new Fixture();String key=ManualWorkHotbarResolution.confirmationKey(f.profile.workHotbarLease);
        for(int day=0;day<32;day++)f.profile.manualWorkHotbarResolutions.add(new ManualWorkHotbarResolution.Entry(lease(),key,
            ManualWorkHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,day));
        List<ManualWorkHotbarResolution.Entry> previous=f.profile.manualWorkHotbarResolutions;
        assertTrue(ManualWorkHotbarResolution.confirm(f.context,key));
        assertEquals(32,previous.size());assertEquals(0,previous.get(0).resolvedDay());
        assertEquals(32,f.profile.manualWorkHotbarResolutions.size());assertEquals(1,f.profile.manualWorkHotbarResolutions.get(0).resolvedDay());
        assertEquals(777,f.profile.manualWorkHotbarResolutions.get(31).resolvedDay());
        f.profile.workHotbarLease=lease();f.session.workHotbarOwner=Feature.CRYSTAL_COPY;
        ManualWorkHotbarResolution.validate(f.profile);assertNull(ManualWorkHotbarResolution.rejection(f.context,key));
        assertNotNull(f.profile.workHotbarLease);assertEquals(1,f.checkpoints);
    }

    @Test void failedCheckpointRollsBackExactLeaseHistoryReferenceSchemaAndSessionOwner() {
        Fixture f=new Fixture();String key=ManualWorkHotbarResolution.confirmationKey(f.profile.workHotbarLease);
        for(int day=0;day<32;day++)f.profile.manualWorkHotbarResolutions.add(new ManualWorkHotbarResolution.Entry(lease(),key,
            ManualWorkHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,day));
        HotbarLease before=f.profile.workHotbarLease;List<ManualWorkHotbarResolution.Entry> history=f.profile.manualWorkHotbarResolutions;
        List<ManualWorkHotbarResolution.Entry> entries=List.copyOf(history);Map<String,Long> dates=new HashMap<>(f.profile.nextEligibleDay);
        f.failCheckpoint=true;
        assertThrows(IllegalStateException.class,()->ManualWorkHotbarResolution.confirm(f.context,key));
        assertSame(before,f.profile.workHotbarLease);assertSame(history,f.profile.manualWorkHotbarResolutions);assertEquals(entries,history);
        assertEquals(6,f.profile.schemaVersion);assertEquals(Feature.CRYSTAL_COPY,f.session.workHotbarOwner);
        assertEquals(dates,f.profile.nextEligibleDay);assertEquals(1,f.checkpoints);
        f.failCheckpoint=false;assertTrue(ManualWorkHotbarResolution.confirm(f.context,key));assertEquals(2,f.checkpoints);
    }

    private static class UnknownActions implements ActionPort {
        public boolean busy(){return false;}
        public String startRejection(){throw new AssertionError("Manual resolution must not clear action failures");}
        public String pauseReason(){throw new AssertionError("Manual resolution must not reconcile late replies");}
        public long submit(Action action){throw new AssertionError("Manual resolution must not submit game actions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("Manual resolution must not read or edit tickets");}
        public void move(Movement movement){throw new AssertionError("Manual resolution must not move");}
        public void stopMovement(){throw new AssertionError("Manual resolution must not change movement state");}
        public void cancel(){throw new AssertionError("Manual resolution must not cancel actions");}
    }
    private static final class Fixture extends UnknownActions implements WorldAccess,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        boolean connected=true,grounded=true,sleeping,container,busy,failCheckpoint;int menuId,checkpoints;
        String nativeFence;ItemData carried=ItemData.EMPTY;ActionOutcome navigationPending;long dayTime=777*24000L+123;
        final Context context=new Context(this,this,this,profile,session,()->{
            checkpoints++;assertNull(profile.workHotbarLease);assertNull(session.workHotbarOwner);
            assertFalse(profile.manualWorkHotbarResolutions.isEmpty());
            if(failCheckpoint)throw new IllegalStateException("test save failed");
        });
        Fixture(){profile.workHotbarLease=lease();session.workHotbarOwner=Feature.CRYSTAL_COPY;profile.nextEligibleDay.put("wine:unrelated",888L);}
        public boolean busy(){return busy;}public String manualWorkHotbarResolutionRejection(){return nativeFence;}
        public long tick(){return 1;}public long dayTime(){return dayTime;}
        public PlayerState player(){return new PlayerState(0,64,0,0,0,grounded,sleeping,20,20,5,connected,true);}
        public MenuData menu(){return new MenuData(menuId,0,List.of(),carried,container);}
        public List<ItemSlot> inventory(){throw new AssertionError("Manual cleanup does not require finding the original");}
        public BlockData block(Pos pos){throw new AssertionError();}public boolean loaded(Pos pos){throw new AssertionError();}
        public boolean canStand(Pos pos){throw new AssertionError();}public boolean canTraverse(Pos a,Pos b){throw new AssertionError();}
        public List<BlockData> scan(Pos pos,int h,int v){throw new AssertionError();}public boolean mayPlace(int slot,ItemData item){throw new AssertionError();}
        public Result moveTo(Pos target,double reach,Context c){throw new AssertionError();}public void reset(){throw new AssertionError();}
        public ActionOutcome pendingInteractionOutcome(Context c){return navigationPending;}
    }
}
