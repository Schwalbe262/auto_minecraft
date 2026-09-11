package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The real scheduler must resume only after a separate, explicit manual-record resolution. */
class ManualWorkHotbarResumeTest {
    private static final ItemData ORIGINAL=new ItemData("minecraft:diamond_sword",1,0,null,false,99);
    private static final HotbarLease ORPHANED=new HotbarLease(Feature.CRYSTAL_COPY,9,1,ORIGINAL,
        "a".repeat(64),HotbarLease.Stage.PARKED);

    @Test void manuallyHandledMissingOriginalCanReleaseTheRecordButOnlyOrdinaryStartResumesWork() {
        Fixture f=new Fixture(); f.failOrdinaryStartOnMissingOriginal();
        assertFalse(f.profile.enabled(Feature.CRYSTAL_COPY),"An OFF former owner still retains its restoration obligation");
        String key=ManualWorkHotbarResolution.confirmationKey(ORPHANED);
        List<ItemSlot> inventory=f.inventory(); MenuData menu=f.menu();
        Map<Feature,Boolean> enabled=new EnumMap<>(f.profile.enabled);
        Map<String,Long> schedules=new HashMap<>(f.profile.nextEligibleDay);
        Map<Long,ActionOutcome> receipts=new LinkedHashMap<>(f.receipts);
        int cancellations=f.cancellations,resets=f.navigationResets,stops=f.movementStops;
        int startChecks=f.startChecks,pauseChecks=f.pauseChecks;
        assertNull(ManualWorkHotbarResolution.rejection(f.context,key));
        assertTrue(ManualWorkHotbarResolution.confirm(f.context,key));
        assertNull(f.profile.workHotbarLease); assertNull(f.session.workHotbarOwner);
        assertEquals(1,f.checkpoints); assertEquals(1,f.profile.manualWorkHotbarResolutions.size());
        var resolution=f.profile.manualWorkHotbarResolutions.get(0);
        assertEquals(ORPHANED,resolution.lease()); assertEquals(key,resolution.confirmationKey());
        assertEquals(ManualWorkHotbarResolution.Resolution.CONFIRMED_MANUALLY_HANDLED,resolution.resolution());
        assertEquals(700L,resolution.resolvedDay());
        assertEquals(inventory,f.inventory()); assertEquals(menu,f.menu()); assertEquals(receipts,f.receipts);
        assertEquals(enabled,f.profile.enabled); assertEquals(schedules,f.profile.nextEligibleDay);
        assertEquals(cancellations,f.cancellations); assertEquals(resets,f.navigationResets); assertEquals(stops,f.movementStops);
        assertEquals(startChecks,f.startChecks,"Manual confirmation must not call the mutable native start gate");
        assertEquals(pauseChecks,f.pauseChecks,"Manual confirmation must not reconcile native fences through pauseReason");
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertEquals(0,f.worker.calls);
        f.tick++; f.engine.tick(f.context);
        assertEquals(0,f.worker.calls,"Confirming the record does not silently restart automation");
        assertTrue(f.sent.isEmpty());

        f.engine.start(f.context); f.tick++; f.engine.tick(f.context);
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(1,f.worker.calls);
        f.tick++; f.engine.tick(f.context);
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(2,f.worker.calls);
        assertNull(f.profile.workHotbarLease); assertEquals(inventory,f.inventory()); assertEquals(receipts,f.receipts);
        assertTrue(f.sent.isEmpty()); assertEquals(1,f.checkpoints);
    }

    @Test void aConfirmationForAnOlderDisplayedLeaseCannotReleaseItsReplacementOrUnblockStart() {
        Fixture f=new Fixture(); f.failOrdinaryStartOnMissingOriginal();
        String staleKey=ManualWorkHotbarResolution.confirmationKey(ORPHANED);
        HotbarLease replacement=new HotbarLease(Feature.CRYSTAL_COPY,10,3,ORIGINAL,
            "b".repeat(64),HotbarLease.Stage.PARKED);
        f.profile.workHotbarLease=replacement;
        List<ItemSlot> inventory=f.inventory(); Map<Long,ActionOutcome> receipts=new LinkedHashMap<>(f.receipts);
        assertNotEquals(staleKey,ManualWorkHotbarResolution.confirmationKey(replacement));
        assertNotNull(ManualWorkHotbarResolution.rejection(f.context,staleKey));
        assertFalse(ManualWorkHotbarResolution.confirm(f.context,staleKey));
        assertSame(replacement,f.profile.workHotbarLease); assertEquals(0,f.checkpoints);
        assertTrue(f.profile.manualWorkHotbarResolutions.isEmpty()); assertEquals(inventory,f.inventory());
        assertEquals(receipts,f.receipts); assertTrue(f.sent.isEmpty());
        f.engine.start(f.context); f.tick++; f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertEquals(0,f.worker.calls);
        assertSame(replacement,f.profile.workHotbarLease); assertTrue(f.sent.isEmpty());
    }

    @Test void realUnconfirmedNativeStateCannotBeErasedByManualRecordConfirmationOrRestart() {
        for(boolean pending:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.failOrdinaryStartOnMissingOriginal();
            if(pending) f.nativeBusy=true; else f.nativeFence="Unconfirmed inventory swap still owns its native receipt";
            f.receipts.put(18L,new ActionOutcome(ActionOutcome.State.PENDING,"awaiting the real server reply"));
            String key=ManualWorkHotbarResolution.confirmationKey(ORPHANED);
            Map<Long,ActionOutcome> receipts=new LinkedHashMap<>(f.receipts); List<ItemSlot> inventory=f.inventory();
            int cancellations=f.cancellations,resets=f.navigationResets;
            assertNotNull(ManualWorkHotbarResolution.rejection(f.context,key));
            assertFalse(ManualWorkHotbarResolution.confirm(f.context,key));
            assertSame(ORPHANED,f.profile.workHotbarLease); assertEquals(0,f.checkpoints);
            assertTrue(f.profile.manualWorkHotbarResolutions.isEmpty()); assertEquals(receipts,f.receipts);
            assertEquals(inventory,f.inventory()); assertEquals(cancellations,f.cancellations); assertEquals(resets,f.navigationResets);
            assertEquals(pending,f.nativeBusy); assertEquals(pending?null:"Unconfirmed inventory swap still owns its native receipt",f.nativeFence);
            f.engine.start(f.context); f.tick++; f.engine.tick(f.context);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertEquals(0,f.worker.calls);
            assertSame(ORPHANED,f.profile.workHotbarLease); assertEquals(receipts,f.receipts);
            assertEquals(pending,f.nativeBusy); assertTrue(f.sent.isEmpty());
        }
    }

    private static final class Worker implements AutomationModule {
        int calls;
        @Override public Feature feature() { return Feature.HARVEST; }
        @Override public int priority() { return 50; }
        @Override public WorkResult tick(Context c) {
            assertNull(c.profile().workHotbarLease,"Ordinary work must never run while the old custody record remains");
            calls++; return WorkResult.busy("ordinary registered work entered");
        }
        @Override public void reset() { }
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final Worker worker=new Worker(); final AutomationEngine engine=new AutomationEngine(List.of(worker));
        final List<Action> sent=new ArrayList<>(); final Map<Long,ActionOutcome> receipts=new LinkedHashMap<>();
        long tick; int checkpoints,cancellations,navigationResets,movementStops,startChecks,pauseChecks;
        boolean nativeBusy; String nativeFence;
        final Context context=new Context(this,this,this,profile,session,()->checkpoints++);
        Fixture() {
            profile.enabled.replaceAll((feature,on)->false); profile.enabled.put(Feature.HARVEST,true);
            profile.hoeHotbarSlot=4; profile.loggingAxeHotbarSlot=2; profile.workHotbarLease=ORPHANED;
            profile.nextEligibleDay.put("harvest:existing",712L);
            receipts.put(17L,new ActionOutcome(ActionOutcome.State.CANCELLED,"historical request remains cancelled"));
        }
        void failOrdinaryStartOnMissingOriginal() {
            assertTrue(inventory().stream().allMatch(slot->slot.item().empty()));
            engine.start(context); assertTrue(engine.running()); tick++; engine.tick(context);
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertSame(ORPHANED,profile.workHotbarLease);
            assertEquals(0,worker.calls); assertEquals(0,checkpoints); assertTrue(sent.isEmpty());
        }
        @Override public long tick() { return tick; }
        @Override public long dayTime() { return 700*24000L+1000; }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,4,true,true); }
        @Override public BlockData block(Pos pos) { return new BlockData(pos,"minecraft:air",Map.of()); }
        @Override public boolean loaded(Pos pos) { return true; }
        @Override public boolean canStand(Pos pos) { return true; }
        @Override public boolean canTraverse(Pos from,Pos to) { return true; }
        @Override public List<BlockData> scan(Pos center,int horizontal,int vertical) { return List.of(); }
        @Override public List<ItemSlot> inventory() {
            List<ItemSlot> slots=new ArrayList<>();
            for(int index=0;index<36;index++) slots.add(new ItemSlot(index,index,true,ItemData.EMPTY));
            return List.copyOf(slots);
        }
        @Override public MenuData menu() {
            List<ItemSlot> slots=new ArrayList<>();
            for(int index=0;index<46;index++) {
                int inventoryIndex=index>=9 && index<=35?index:index>=36 && index<=44?index-36
                    :index==45?40:index>=5 && index<=8?44-index:-1;
                slots.add(new ItemSlot(index,inventoryIndex,inventoryIndex>=0,ItemData.EMPTY));
            }
            return new MenuData(0,0,slots,ItemData.EMPTY,false);
        }
        @Override public boolean mayPlace(int slot,ItemData item) { return false; }
        @Override public boolean busy() { return nativeBusy; }
        @Override public String pauseReason() { pauseChecks++; return nativeBusy?"Unconfirmed native operation":nativeFence; }
        @Override public String startRejection() { startChecks++; return nativeBusy?"Unconfirmed native operation":nativeFence; }
        @Override public String manualWorkHotbarResolutionRejection() { return nativeBusy?"Unconfirmed native operation":nativeFence; }
        @Override public long submit(Action action) { sent.add(action); throw new AssertionError("Manual resolution must not send "+action); }
        @Override public ActionOutcome outcome(long ticket) { throw new AssertionError("No historical native acknowledgement may be polled or fabricated"); }
        @Override public void move(Movement movement) { throw new AssertionError("Manual resolution does not move the player"); }
        @Override public void stopMovement() { movementStops++; }
        @Override public void cancel() { cancellations++; /* A real unconfirmed receipt survives scheduler cancellation. */ }
        @Override public Result moveTo(Pos target,double reach,Context c) { throw new AssertionError("Manual resolution does not navigate"); }
        @Override public void reset() { navigationResets++; }
    }
}
