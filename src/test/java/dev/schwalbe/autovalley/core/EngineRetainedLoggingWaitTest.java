package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A typed, revalidated local wait retains the logging owner without granting a replay or sleep. */
class EngineRetainedLoggingWaitTest {
    @Test void cleanupWaitYieldsOtherWorkAndRetriesAt1200WithoutResettingItsOwnerOrSavedObligations() {
        Fixture f=new Fixture();f.start(false);
        int initialResets=f.logging.resets,initialCancels=f.cancels;
        f.at(0);assertEquals(1,f.logging.calls);assertEquals(1,f.otherCalls);assertEquals(0,f.sleepCalls);
        assertEquals(AutomationEngine.State.WAITING,f.engine.state());assertTrue(f.engine.status().contains("cleanup route"));
        for(int tick=20;tick<1200;tick+=20)f.at(tick);
        assertEquals(1,f.logging.calls);assertTrue(f.otherCalls>1);f.assertPreserved();
        f.at(1200);assertEquals(2,f.logging.calls);assertTrue(f.engine.running(),f.engine.status());
        assertEquals(initialResets,f.logging.resets);assertEquals(initialCancels,f.cancels);f.assertPreserved();
    }

    @Test void oneShotWaitRetriesItsSameOwnerWithoutRunningNeighboursOrClaimingCompletion() {
        Fixture f=new Fixture();f.start(true);int resets=f.logging.resets;
        f.at(0);f.at(1180);f.at(1199);assertEquals(1,f.logging.calls);assertEquals(AutomationEngine.State.WAITING,f.engine.state());
        f.at(1200);assertEquals(2,f.logging.calls);assertEquals(0,f.otherCalls);assertEquals(0,f.sleepCalls);
        assertEquals(resets,f.logging.resets);assertEquals(Feature.LOGGING,f.session.oneShotFeature);f.assertPreserved();
    }

    @Test void retryDeadlineAndNewReadinessCannotPreemptAnotherJobsBusyTransaction() {
        Fixture f=new Fixture();f.otherResult=WorkResult.busy("other owns a native transaction");f.start(false);f.at(0);
        assertEquals(1,f.logging.calls);assertEquals(1,f.otherCalls);
        f.pending=true;f.container=true;f.logging.readiness=AutomationModule.ResourceReadiness.READY;
        f.at(1400);assertEquals(1,f.logging.calls);assertEquals(2,f.otherCalls);assertTrue(f.engine.running());
        f.pending=false;f.container=false;f.otherResult=WorkResult.idle();f.at(1401);
        assertEquals(1,f.logging.calls,"A boundary grants ownership; it does not tick two native owners together");
        f.logging.result=WorkResult.busy("fresh cleanup route observation");f.at(1402);
        assertEquals(2,f.logging.calls);assertEquals(3,f.otherCalls);assertEquals(0,f.sleepCalls);f.assertPreserved();
    }

    @Test void sleepNeedsItsOwnExplicitLiveOptInEvenAfterAValidResourceWaitGrant() {
        Fixture unknown=new Fixture();unknown.start(false);unknown.at(0);
        assertEquals(1,unknown.otherCalls);assertEquals(0,unknown.sleepCalls);
        assertFalse(unknown.logging.sleepSafeResourceWait(unknown.context));
        Fixture material=new Fixture(true);material.sleepSafe=true;material.start(false);material.at(0);
        assertEquals(1,material.otherCalls);assertEquals(1,material.sleepCalls);
        material.sleepSafe=false;material.at(20);
        assertEquals(2,material.otherCalls);assertEquals(1,material.sleepCalls,"A stale sleep grant must not outlive its proof");
        material.assertPreserved();
    }

    @Test void everyUnsafeBoundaryRejectsTheInitialWaitAndAChangedPreviouslyGrantedWait() {
        for(boolean alreadyGranted:new boolean[]{false,true})for(String cause:List.of("busy","cursor","container","lease","airborne","output","native fence","proof")) {
            Fixture f=new Fixture();f.start(false);
            if(alreadyGranted)f.at(0);
            int consumers=f.otherCalls;f.makeUnsafe(cause);f.at(alreadyGranted?20:0);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),cause+" granted="+alreadyGranted);
            assertEquals(consumers,f.otherCalls,cause);assertEquals(0,f.sleepCalls);f.assertPreserved();
            if(cause.equals("lease"))assertNotNull(f.profile.loggingHotbarLease);
            if(cause.equals("output"))assertEquals(1,f.profile.pendingMachineOutputs.size());
            if(cause.equals("native fence"))assertEquals("unconfirmed native request",f.fence);
        }
    }

    @Test void ordinaryBlockedOrDeferredLoggingIsNotPromotedJustBecauseItsPhysicalBoundaryLooksClean() {
        for(WorkResult result:List.of(WorkResult.blocked("untyped failure"),WorkResult.deferred("untyped retry"),WorkResult.idle())) {
            Fixture f=new Fixture();f.logging.result=result;f.start(false);f.at(0);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertTrue(f.engine.status().contains("미완료 벌목"));
            assertEquals(0,f.otherCalls);assertEquals(0,f.sleepCalls);f.assertPreserved();
        }
    }

    @Test void manualF8OffCannotBeUndoneByADeadlineOrChangedReadinessInEitherMode() {
        for(boolean once:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.start(once);f.at(0);
            f.engine.stop(f.context,AutomationEngine.State.OFF,"manual F8 OFF");
            int consumers=f.otherCalls,cancels=f.cancels;
            f.logging.readiness=AutomationModule.ResourceReadiness.READY;f.at(5000);
            assertEquals(AutomationEngine.State.OFF,f.engine.state());assertEquals("manual F8 OFF",f.engine.status());
            assertEquals(1,f.logging.calls);assertEquals(consumers,f.otherCalls);assertEquals(cancels,f.cancels);f.assertPreserved();
        }
    }

    @Test void featureOffSuspendsTheWaitWithoutInspectingChangedProofOrClearingTheDurableBatch() {
        Fixture f=new Fixture();f.start(false);f.at(0);int queries=f.logging.queries;
        f.profile.enabled.put(Feature.LOGGING,false);f.logging.readiness=AutomationModule.ResourceReadiness.UNSAFE;f.at(20);
        assertTrue(f.engine.running(),f.engine.status());assertEquals(1,f.logging.calls);assertEquals(queries,f.logging.queries);
        assertEquals(2,f.otherCalls);assertEquals(1,f.sleepCalls);f.assertPreserved();
        f.at(1500);assertEquals(1,f.logging.calls);assertFalse(f.profile.enabled(Feature.LOGGING));f.assertPreserved();
    }

    @Test void disconnectOrLossOfFocusStillStopsAValidatedWaitWithoutAutomaticRestart() {
        for(boolean disconnect:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.start(false);f.at(0);
            if(disconnect)f.connected=false;else f.focused=false;
            f.at(20);assertEquals(AutomationEngine.State.PAUSED,f.engine.state());
            f.connected=true;f.focused=true;f.logging.readiness=AutomationModule.ResourceReadiness.READY;f.at(5000);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertEquals(1,f.logging.calls);assertEquals(1,f.otherCalls);f.assertPreserved();
        }
    }

    private static class WaitJob implements AutomationModule {
        int calls,resets,queries;
        WorkResult result=WorkResult.resourceWait("cleanup route retained; retry after 1200 ticks");
        ResourceReadiness readiness=ResourceReadiness.WAITING;
        public Feature feature(){return Feature.LOGGING;}public int priority(){return 80;}
        public WorkResult tick(Context c){calls++;return result;}
        public ResourceReadiness resourceReadiness(Context c){queries++;return readiness;}
        public void reset(){resets++;}
    }
    private static ItemData item(String id,int count){return new ItemData(id,count,0,null,false,99);}
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final Context context=new Context(this,this,this,profile,session,()->{throw new AssertionError("Scheduler waits cannot checkpoint logging completion");});
        final WaitJob logging;final AutomationEngine engine;
        long ticks;int otherCalls,sleepCalls,cancels;boolean pending,container,grounded=true,connected=true,focused=true,sleepSafe;
        String fence;ItemData cursor=ItemData.EMPTY;
        WorkResult otherResult=WorkResult.idle();
        Fixture(){this(false);}
        Fixture(boolean sleepOptIn){
            profile.allowBackground=false;
            for(Feature feature:Feature.values())profile.enabled.put(feature,false);
            for(Feature feature:List.of(Feature.LOGGING,Feature.HARVEST,Feature.SLEEP))profile.enabled.put(feature,true);
            profile.loggingRunActive=true;profile.loggingPlots.add(new LoggingPlot("finished plot",new Pos(2,64,0)));
            profile.nextEligibleDay.put(LoggingRules.DUE_KEY,99L);
            logging=sleepOptIn ? new WaitJob(){public boolean sleepSafeResourceWait(Context c){return sleepSafe;}} : new WaitJob();
            AutomationModule other=new AutomationModule(){
                public Feature feature(){return Feature.HARVEST;}public int priority(){return 10;}
                public WorkResult tick(Context c){otherCalls++;return otherResult;}public void reset(){}
            };
            AutomationModule sleep=new AutomationModule(){
                public Feature feature(){return Feature.SLEEP;}public int priority(){return 100;}
                public WorkResult tick(Context c){sleepCalls++;return WorkResult.idle();}public void reset(){}
            };
            engine=new AutomationEngine(List.of(other,logging,sleep));
        }
        void start(boolean once){if(once)engine.startOnce(context,Feature.LOGGING);else engine.start(context);}
        void at(long tick){ticks=tick;engine.tick(context);}
        void assertPreserved(){
            assertTrue(profile.loggingRunActive);assertTrue(profile.loggingRemainingPlots.isEmpty());assertTrue(profile.loggingReplantingPlots.isEmpty());
            assertEquals(99L,profile.nextEligibleDay.get(LoggingRules.DUE_KEY));assertEquals(item("society:mossberry",8),inventory().get(9).item());
        }
        void makeUnsafe(String cause){
            switch(cause){
                case "busy" -> pending=true;
                case "cursor" -> cursor=item("minecraft:diamond",1);
                case "container" -> container=true;
                case "lease" -> profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item(ItemData.TOMATO,2),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "airborne" -> grounded=false;
                case "output" -> {String id="11111111-1111-1111-1111-111111111111";profile.pendingMachineOutputs.put(id,
                    new PendingMachineOutput(id,Feature.PRESERVES,new Pos(20,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));}
                case "native fence" -> fence="unconfirmed native request";
                case "proof" -> logging.readiness=AutomationModule.ResourceReadiness.UNSAFE;
                default -> throw new AssertionError(cause);
            }
        }
        public long tick(){return ticks;}public long dayTime(){return 241000;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,grounded,false,20,20,0,connected,focused);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos p,int h,int v){throw new AssertionError("Scheduler cannot inspect or change terrain");}
        public List<ItemSlot> inventory(){List<ItemSlot> result=new ArrayList<>();for(int i=0;i<36;i++)result.add(new ItemSlot(i,i,true,i==9?item("society:mossberry",8):ItemData.EMPTY));return result;}
        public MenuData menu(){return new MenuData(container?1:0,0,inventory(),cursor,container);}
        public boolean mayPlace(int slot,ItemData item){return false;}public boolean busy(){return pending;}public String pauseReason(){return fence;}
        public long submit(Action action){throw new AssertionError("Scheduler wait cannot submit native actions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("Scheduler wait cannot acknowledge actions");}
        public void move(Movement m){throw new AssertionError("Scheduler wait cannot move");}public void stopMovement(){}public void cancel(){cancels++;}
        public Result moveTo(Pos p,double reach,Context c){throw new AssertionError("Scheduler wait cannot navigate");}public void reset(){}
    }
}
