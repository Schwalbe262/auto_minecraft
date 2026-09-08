package dev.schwalbe.autovalley.core;

import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** OFF suspends a durable job; it does not finish it or waive native transaction fences. */
class EngineLoggingSuspensionTest {
    @Test void disabledUnfinishedLoggingAllowsHarvestAndSleepWithoutChangingTheSavedBatch() {
        Fixture f=new Fixture();
        f.engine.start(f.context); f.step();
        assertTrue(f.engine.running(),f.engine.status());
        assertEquals(1,f.harvestTicks); assertEquals(1,f.sleepTicks); assertEquals(0,f.loggingTicks);
        f.assertPreserved();
    }

    @Test void disabledJobDoesNotRequireItsModuleToBeInstalledToSuspendIt() {
        Fixture f=new Fixture(); f.engine=new AutomationEngine(List.of(f.harvest,f.sleep));
        f.engine.start(f.context); f.step();
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(1,f.harvestTicks); f.assertPreserved();
    }

    @Test void aDifferentOneShotIsAllowedWhileLoggingIsOffAndDoesNotRunNeighbours() {
        Fixture f=new Fixture(); f.engine.startOnce(f.context,Feature.HARVEST); f.step();
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());
        assertEquals(1,f.harvestTicks); assertEquals(0,f.sleepTicks); assertEquals(0,f.loggingTicks);
        f.assertPreserved();
    }

    @Test void explicitLoggingOneShotStillResumesTheDisabledJobWithoutEnablingItsToggle() {
        Fixture f=new Fixture(); f.engine.startOnce(f.context,Feature.LOGGING); f.step();
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(1,f.loggingTicks);
        assertEquals(0,f.harvestTicks); assertEquals(0,f.sleepTicks); f.assertPreserved();
    }

    @Test void unsafeInitialBoundaryCannotGrantDisabledLoggingSuspension() {
        for (String unsafe:List.of("lease","busy","container","cursor","airborne","start fence","output")) {
            Fixture f=new Fixture();
            switch (unsafe) {
                case "lease" -> f.addLease();
                case "busy" -> f.busy=true;
                case "container" -> f.container=true;
                case "cursor" -> f.cursor=item(ItemData.TOMATO,1);
                case "airborne" -> f.grounded=false;
                case "start fence" -> f.startFence="late native reply";
                case "output" -> f.addOutput();
                default -> throw new AssertionError(unsafe);
            }
            f.engine.start(f.context); f.step();
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),unsafe);
            assertEquals(0,f.harvestTicks,unsafe); assertEquals(0,f.sleepTicks,unsafe);
            f.assertPreserved();
        }
    }

    @Test void otherOneShotCannotBypassBorrowedHotbarOrUnconfirmedAction() {
        for (boolean lease:List.of(false,true)) {
            Fixture f=new Fixture(); if (lease) f.addLease(); else f.startFence="unconfirmed native request";
            f.engine.startOnce(f.context,Feature.HARVEST); f.step();
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertEquals(0,f.harvestTicks);
            f.assertPreserved();
        }
    }

    @Test void aNewNativeFenceStillStopsAnAlreadySuspendedBatchBeforeAnotherConsumer() {
        Fixture f=new Fixture(); f.harvestResult=WorkResult.busy("owned work");
        f.engine.start(f.context); f.step(); assertEquals(1,f.harvestTicks);
        f.pauseFence="exact native reply missing"; f.step();
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());
        assertEquals(1,f.harvestTicks); assertEquals(0,f.sleepTicks); f.assertPreserved();
    }

    @Test void leaseAppearingDuringSuspensionIsNotPermissionToConsumeItsParkedItem() {
        Fixture f=new Fixture(); f.harvestResult=WorkResult.busy("owned work");
        f.engine.start(f.context); f.step(); f.addLease(); f.step();
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertEquals(1,f.harvestTicks);
        f.assertPreserved();
    }

    @Test void anOrdinaryNavigationDeferralRetriesWithoutTurningAutomationOff() {
        Fixture f=new Fixture(); f.harvestResult=WorkResult.deferred("registered field route unavailable");
        f.engine.start(f.context); f.step();
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(1,f.harvestTicks);
        f.ticks=1199; f.engine.tick(f.context); assertEquals(1,f.harvestTicks);
        f.ticks=1220; f.engine.tick(f.context); assertEquals(2,f.harvestTicks);
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(0,f.loggingTicks); f.assertPreserved();
    }

    @Test void reenablingLoggingWaitsForTheOtherJobsOwnedTransactionThenResumesFirst() {
        Fixture f=new Fixture(); f.harvestResult=WorkResult.busy("harvest transaction");
        f.engine.start(f.context); f.step();
        f.profile.enabled.put(Feature.LOGGING,true); f.busy=true; f.container=true;
        f.step(); assertTrue(f.engine.running(),f.engine.status());
        assertEquals(2,f.harvestTicks); assertEquals(0,f.loggingTicks); assertEquals(0,f.sleepTicks);
        f.busy=false; f.container=false; f.harvestResult=WorkResult.idle();
        f.step(); f.step();
        assertTrue(f.engine.running(),f.engine.status()); assertTrue(f.loggingTicks>0);
        assertEquals(3,f.harvestTicks); assertEquals(0,f.sleepTicks);
        f.assertPreservedExceptToggle();
    }

    @Test void reenablingDuringADifferentOneShotNeverStartsLoggingAsItsNeighbour() {
        Fixture f=new Fixture(); f.harvestResult=WorkResult.busy("one-shot work");
        f.engine.startOnce(f.context,Feature.HARVEST); f.step();
        f.profile.enabled.put(Feature.LOGGING,true); f.busy=true; f.step();
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(0,f.loggingTicks);
        f.busy=false; f.harvestResult=WorkResult.idle(); f.step();
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state());
        assertEquals(3,f.harvestTicks); assertEquals(0,f.loggingTicks); assertEquals(0,f.sleepTicks);
        f.assertPreservedExceptToggle();
    }

    @Test void restartDoesNotReuseTheOffGrantAfterLoggingWasReenabled() {
        Fixture f=new Fixture(); f.engine.start(f.context); f.step();
        f.engine.stop(f.context,AutomationEngine.State.OFF,"manual pause");
        f.profile.enabled.put(Feature.LOGGING,true);
        f.engine.start(f.context); f.step();
        assertEquals(1,f.loggingTicks); assertEquals(1,f.harvestTicks); assertEquals(1,f.sleepTicks);
        f.assertPreservedExceptToggle();
    }

    @Test void turningOffAResourceWaitingJobBeforeAnotherJobDefersDoesNotInspectItsChangedPlot() {
        Fixture f=resourceWaitWithOtherBusy();
        int readinessBefore=f.readinessCalls;
        f.profile.enabled.put(Feature.LOGGING,false);
        f.readiness=AutomationModule.ResourceReadiness.UNSAFE;
        f.harvestResult=WorkResult.deferred("ordinary field route unavailable");
        f.step();
        assertTrue(f.engine.running(),f.engine.status());
        assertEquals(readinessBefore,f.readinessCalls,"Disabled logging grants no tree work and must not inspect its plots");
        assertEquals(1,f.loggingTicks); assertEquals(2,f.harvestTicks);
        f.assertPreserved();
    }

    @Test void resourceWaitToOffTransitionStillRejectsAnUnconfirmedActionOrHotbarLease() {
        for (boolean lease:List.of(false,true)) {
            Fixture f=resourceWaitWithOtherBusy();
            f.profile.enabled.put(Feature.LOGGING,false);
            f.readiness=AutomationModule.ResourceReadiness.UNSAFE;
            f.harvestResult=WorkResult.deferred("ordinary field route unavailable");
            if (lease) f.addLease(); else f.busy=true;
            f.step();
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),lease?"lease":"busy native action");
            f.assertPreserved();
        }
    }

    private static Fixture resourceWaitWithOtherBusy() {
        Fixture f=new Fixture(); f.profile.enabled.put(Feature.LOGGING,true);
        f.loggingResult=WorkResult.resourceWait("two of four saplings; preserve the planting obligation");
        f.readiness=AutomationModule.ResourceReadiness.WAITING;
        f.harvestResult=WorkResult.busy("other owned work");
        f.engine.start(f.context); f.step();
        assertTrue(f.engine.running(),f.engine.status()); assertEquals(1,f.loggingTicks); assertEquals(1,f.harvestTicks);
        assertTrue(f.readinessCalls>0);
        return f;
    }

    private static ItemData item(String id,int count) { return new ItemData(id,count,0,null,false,1000); }
    private static AutomationModule module(Feature feature,int priority,Function<Context,WorkResult> tick) {
        return new AutomationModule() {
            public Feature feature() { return feature; }
            public int priority() { return priority; }
            public WorkResult tick(Context c) { return tick.apply(c); }
            public void reset() { }
        };
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final List<Pos> remaining=List.of(new Pos(2,64,0),new Pos(8,64,0));
        final List<Pos> replants=List.of(remaining.get(0));
        WorkResult harvestResult=WorkResult.idle();
        WorkResult loggingResult=WorkResult.busy("resume logging");
        AutomationModule.ResourceReadiness readiness=AutomationModule.ResourceReadiness.UNSAFE;
        long ticks; int loggingTicks,harvestTicks,sleepTicks,readinessCalls;
        final Context context=new Context(this,this,this,profile,session,() -> { throw new AssertionError("Engine suspension must not save or change the batch"); });
        final AutomationModule logging=new AutomationModule() {
            public Feature feature() { return Feature.LOGGING; }
            public int priority() { return 80; }
            public WorkResult tick(Context c) { loggingTicks++; return loggingResult; }
            public ResourceReadiness resourceReadiness(Context c) { readinessCalls++; return readiness; }
            public void reset() { }
        };
        final AutomationModule harvest=module(Feature.HARVEST,10,c -> { harvestTicks++; return harvestResult; });
        final AutomationModule sleep=module(Feature.SLEEP,100,c -> { sleepTicks++; return WorkResult.idle(); });
        AutomationEngine engine=new AutomationEngine(List.of(harvest,logging,sleep));
        boolean busy,container,grounded=true; ItemData cursor=ItemData.EMPTY; String startFence,pauseFence;
        Fixture() {
            for (Feature f:Feature.values()) profile.enabled.put(f,false);
            profile.enabled.put(Feature.HARVEST,true); profile.enabled.put(Feature.SLEEP,true);
            profile.loggingRunActive=true;
            profile.loggingPlots.add(new LoggingPlot("first",remaining.get(0)));
            profile.loggingPlots.add(new LoggingPlot("second",remaining.get(1)));
            profile.loggingRemainingPlots.addAll(remaining); profile.loggingReplantingPlots.addAll(replants);
            profile.nextEligibleDay.put(LoggingRules.DUE_KEY,99L);
        }
        void step() { engine.tick(context); ticks++; }
        void addLease() { profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item(ItemData.TOMATO,52),"a".repeat(64),LoggingHotbarLease.Stage.PARKED); }
        void addOutput() {
            String id="11111111-1111-1111-1111-111111111111";
            profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(20,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
        }
        void assertPreserved() { assertFalse(profile.enabled(Feature.LOGGING)); assertPreservedExceptToggle(); }
        void assertPreservedExceptToggle() {
            assertTrue(profile.loggingRunActive); assertEquals(remaining,profile.loggingRemainingPlots);
            assertEquals(replants,profile.loggingReplantingPlots); assertEquals(99L,profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        }
        public long tick() { return ticks; }
        public long dayTime() { return 241000; }
        public PlayerState player() { return new PlayerState(.5,64,.5,0,0,grounded,false,20,20,4,true,true); }
        public BlockData block(Pos p) { return new BlockData(p,"minecraft:air",Map.of()); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return true; }
        public boolean canTraverse(Pos a,Pos b) { return true; }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { List<ItemSlot> slots=new ArrayList<>(); for(int i=0;i<36;i++) slots.add(new ItemSlot(i,i,true,ItemData.EMPTY)); return slots; }
        public MenuData menu() { return new MenuData(container?1:0,0,inventory(),cursor,container); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
        public boolean busy() { return busy; }
        public String startRejection() { return startFence!=null ? startFence : pauseFence; }
        public String pauseReason() { return pauseFence; }
        public long submit(Action a) { throw new AssertionError("Policy fixture must not dispatch native actions"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError("Policy fixture must not acknowledge actions"); }
        public void move(Movement m) { throw new AssertionError("Policy fixture must not move"); }
        public void stopMovement() { }
        // Cancellation cannot magically acknowledge an already-sent native request.
        public void cancel() { }
        public Result moveTo(Pos p,double reach,Context c) { throw new AssertionError("Policy fixture must not navigate"); }
        public void reset() { }
    }
}
