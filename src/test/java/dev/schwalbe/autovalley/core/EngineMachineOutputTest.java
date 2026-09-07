package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class EngineMachineOutputTest {
    private static final Pos KEG=new Pos(2,64,0);

    @Test void retainedOutputBlocksEveryNewJobEvenWhenProductionIsDisabledAndNotDue() {
        for (Feature feature:Feature.values()) {
            Fixture f=new Fixture();
            f.confirmedDebt();
            f.profile.enabled.put(Feature.WINE,false);
            f.profile.nextEligibleDay.put("wine:2:64:0",999L);
            f.session.activeMachineOutputId=null;
            Job job=new Job(feature,c -> WorkResult.idle());
            AutomationEngine engine=new AutomationEngine(List.of(job));
            engine.startOnce(f.context(),feature); engine.tick(f.context());
            assertEquals(AutomationEngine.State.PAUSED,engine.state(),feature.name());
            assertEquals(0,job.calls); assertEquals(1,f.profile.pendingMachineOutputs.size());
            assertEquals(999L,f.profile.nextEligibleDay.get("wine:2:64:0"));
            assertNull(f.session.oneShotFeature);
        }
    }

    @Test void pickupWhilePausedIsReconciledBeforeAStorageConsumerCanRemoveTheBottle() {
        Fixture f=new Fixture(); f.confirmedDebt(); f.session.activeMachineOutputId=null;
        f.wines=1;
        Job storage=new Job(Feature.WINE_STORAGE,c -> {
            assertTrue(c.profile().pendingMachineOutputs.isEmpty());
            assertEquals(MachineOutputLedger.Resolution.AUTOMATIC_PICKUP,c.profile().machineOutputResolutions.get(0).resolution());
            assertEquals(3,f.checkpoints,"prepare, machine confirmation and pickup must all be saved first");
            f.wines=0;
            return WorkResult.idle();
        });
        AutomationEngine engine=new AutomationEngine(List.of(storage));
        engine.startOnce(f.context(),Feature.WINE_STORAGE); engine.tick(f.context());
        assertEquals(1,storage.calls); assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }

    @Test void reconnectCannotUseUnrelatedCurrentInventoryToResolveAPersistedBaseline() {
        Fixture f=new Fixture(); f.confirmedDebt(); f.session=new SessionState(); f.wines=40;
        Job storage=new Job(Feature.WINE_STORAGE,c -> { fail("Unverified output was consumed after reconnect"); return WorkResult.idle(); });
        AutomationEngine engine=new AutomationEngine(List.of(storage));
        engine.start(f.context()); engine.tick(f.context());
        assertFalse(engine.running()); assertEquals(0,storage.calls);
        assertEquals(1,f.profile.pendingMachineOutputs.size()); assertTrue(f.profile.machineOutputResolutions.isEmpty());
    }

    @Test void activeOwnerCanFinishItsAcknowledgementAndPickupWithoutBeingBlockedByItsOwnDebt() {
        Fixture f=new Fixture(); String[] id={null}; int[] stage={0};
        Job wine=new Job(Feature.WINE,c -> switch (stage[0]++) {
            case 0 -> { id[0]=MachineOutputLedger.prepare(c,Feature.WINE,KEG).id(); yield WorkResult.busy("dispatched"); }
            case 1 -> { MachineOutputLedger.confirmMachine(c,id[0]); yield WorkResult.busy("pickup"); }
            default -> { assertFalse(MachineOutputLedger.isPending(c,id[0])); yield WorkResult.idle(); }
        });
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context()); engine.tick(f.context());
        assertTrue(engine.running()); assertEquals(2,wine.calls);
        f.wines=1; engine.tick(f.context());
        assertEquals(AutomationEngine.State.COMPLETE,engine.state()); assertEquals(3,wine.calls);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty()); assertNull(f.session.activeMachineOutputId);
    }

    @Test void anIdleResultCannotOverrideAnUnresolvedOutput() {
        Fixture f=new Fixture();
        Job wine=new Job(Feature.WINE,c -> {
            MachineOutputLedger.prepare(c,Feature.WINE,KEG);
            return WorkResult.idle();
        });
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state());
        assertEquals(1,f.profile.pendingMachineOutputs.size());
    }

    @Test void blockedOwnerCannotYieldToShippingOrSleepAndStopDoesNotEraseTheDebt() {
        Fixture f=new Fixture(); int[] stage={0};
        Job wine=new Job(Feature.WINE,c -> {
            if (stage[0]++==0) { MachineOutputLedger.prepare(c,Feature.WINE,KEG); return WorkResult.busy("use"); }
            return WorkResult.blocked("missing output");
        });
        Job shipping=new Job(Feature.SHIPPING,c -> { fail("shipping must not consume unresolved output"); return WorkResult.idle(); });
        shipping.priority=100;
        Job sleep=new Job(Feature.SLEEP,c -> { fail("sleep must not hide unresolved output"); return WorkResult.idle(); });
        sleep.priority=101;
        AutomationEngine engine=new AutomationEngine(List.of(wine,shipping,sleep));
        engine.start(f.context()); engine.tick(f.context()); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(0,shipping.calls); assertEquals(0,sleep.calls);
        assertEquals(1,f.profile.pendingMachineOutputs.size()); assertEquals(1,f.session.liveMachineOutputs.size());
        assertNull(f.session.activeMachineOutputId);
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(2,wine.calls);
    }

    @Test void f8AndFeatureDisableRetainConfirmationDebtEvenWhenTheBottleAppears() {
        for (boolean disable:new boolean[]{false,true}) {
            Fixture f=new Fixture();
            Job wine=new Job(Feature.WINE,c -> { MachineOutputLedger.prepare(c,Feature.WINE,KEG); return WorkResult.busy("unconfirmed"); });
            AutomationEngine engine=new AutomationEngine(List.of(wine));
            engine.start(f.context()); engine.tick(f.context());
            if (disable) { f.profile.enabled.put(Feature.WINE,false); engine.tick(f.context()); }
            else engine.stop(f.context(),AutomationEngine.State.PAUSED,"F8");
            f.wines=1;
            engine.start(f.context()); engine.tick(f.context());
            assertFalse(engine.running()); assertEquals(1,wine.calls);
            assertEquals(1,f.profile.pendingMachineOutputs.size()); assertTrue(f.profile.machineOutputResolutions.isEmpty());
        }
    }

    @Test void failedPickupCheckpointPreventsStartAndRestoresTheUnresolvedDebt() {
        Fixture f=new Fixture(); f.confirmedDebt(); f.wines=1; f.failCheckpoint=true;
        Job storage=new Job(Feature.WINE_STORAGE,c -> WorkResult.idle());
        AutomationEngine engine=new AutomationEngine(List.of(storage));
        engine.startOnce(f.context(),Feature.WINE_STORAGE); engine.tick(f.context());
        assertEquals(AutomationEngine.State.ERROR,engine.state()); assertEquals(0,storage.calls);
        assertEquals(1,f.profile.pendingMachineOutputs.size()); assertTrue(f.profile.machineOutputResolutions.isEmpty());
        assertEquals(1,f.session.liveMachineOutputs.size());
    }

    private static final class Job implements AutomationModule {
        final Feature feature; final Function<Context,WorkResult> body;
        int calls,priority;
        Job(Feature feature,Function<Context,WorkResult> body) { this.feature=feature; this.body=body; }
        public Feature feature() { return feature; }
        public int priority() { return priority; }
        public WorkResult tick(Context c) { calls++; return body.apply(c); }
        public void reset() { }
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();
        SessionState session=new SessionState();
        int wines,checkpoints; boolean failCheckpoint;
        Context context() { return new Context(this,this,this,profile,session,() -> { checkpoints++; if (failCheckpoint) throw new IllegalStateException("disk unavailable"); }); }
        PendingMachineOutput confirmedDebt() {
            PendingMachineOutput output=MachineOutputLedger.prepare(context(),Feature.WINE,KEG);
            MachineOutputLedger.confirmMachine(context(),output.id()); return output;
        }
        public long tick() { return 200; }
        public long dayTime() { return 13000; }
        public Integer wineYear() { return 8; }
        public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,false); }
        public BlockData block(Pos p) { return new BlockData(p,"society:wine_keg",Map.of("mature","true","working","false")); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return true; }
        public boolean canTraverse(Pos a,Pos b) { return true; }
        public List<BlockData> scan(Pos p,int r,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(new ItemSlot(0,0,true,new ItemData(ItemData.WINE,wines,0,8,false,999))); }
        public MenuData menu() { return new MenuData(0,0,inventory(),ItemData.EMPTY,false); }
        public boolean mayPlace(int s,ItemData i) { return true; }
        public boolean busy() { return false; }
        public long submit(Action a) { fail("Scheduler test must not issue gameplay"); return 0; }
        public ActionOutcome outcome(long t) { return new ActionOutcome(ActionOutcome.State.PENDING,""); }
        public void move(Movement m) { }
        public void stopMovement() { }
        public void cancel() { }
        public Navigation.Result moveTo(Pos p,double r,Context c) { return Navigation.Result.ARRIVED; }
        public void reset() { }
    }
}
