package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The native use was already confirmed; a failed pickup save is not a failed native use. */
class PreservesCheckpointRecoveryTest {
    private static final Pos JAR=new Pos(2,64,0);

    @Test void failedPickupSaveRetainsSameSessionProofAcrossStopAndNormalStartReconcilesIt() {
        for(boolean nestedStop:new boolean[]{false,true}) {
            Fixture f=new Fixture();PendingMachineOutput confirmed=f.prepareConfirmed132ndOutput();
            f.failPickupSave(nestedStop);
            assertEquals(AutomationEngine.State.ERROR,f.engine.state());
            assertSame(confirmed,f.profile.pendingMachineOutputs.get(confirmed.id()));
            assertEquals(PendingMachineOutput.Phase.AWAITING_PICKUP,confirmed.phase());
            assertEquals(Set.of(confirmed.id()),f.session.liveMachineOutputs);
            assertNull(f.session.activeMachineOutputId,"outer stop revokes active ownership, not live pickup proof");
            assertTrue(f.profile.machineOutputResolutions.isEmpty());assertEquals(132,f.jars);assertEquals(0,f.consumer.calls);

            f.failCheckpoint=false;f.engine.start(f.context);
            assertTrue(f.engine.running());
            assertTrue(f.profile.pendingMachineOutputs.isEmpty(),"ordinary start saves pickup reconciliation first");
            assertTrue(f.session.liveMachineOutputs.isEmpty());assertNull(f.session.activeMachineOutputId);
            assertEquals(1,f.profile.machineOutputResolutions.size());
            var resolution=f.profile.machineOutputResolutions.get(0);
            assertSame(confirmed,resolution.output());
            assertEquals(MachineOutputLedger.Resolution.AUTOMATIC_PICKUP,resolution.resolution());
            assertEquals(789L,f.profile.nextEligibleDay.get("preserves:2:64:0"),"recovery cannot advance production twice");
            assertEquals(0,f.consumer.calls,"no storage or gameplay occurs during start reconciliation");
            f.engine.tick(f.context);
            assertEquals(1,f.consumer.calls);assertEquals(0,f.jars);
            assertEquals(1,f.profile.machineOutputResolutions.size());
            assertEquals(4,f.checkpoints,"prepare, confirmation, failed pickup save, successful pickup save");
            assertEquals(0,f.submits,"recovery must never replay the machine use");
        }
    }

    @Test void manualOutputInteractionAfterFailedSaveStillRequiresExplicitReview() {
        Fixture f=new Fixture();PendingMachineOutput confirmed=f.prepareConfirmed132ndOutput();
        f.failPickupSave(true);
        // Exact API called by ClientRuntime.manualOutputInteraction().
        MachineOutputLedger.invalidateLiveEvidence(f.context);
        f.failCheckpoint=false;f.engine.start(f.context);f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state());assertFalse(f.engine.running());
        assertSame(confirmed,f.profile.pendingMachineOutputs.get(confirmed.id()));
        assertTrue(f.profile.machineOutputResolutions.isEmpty());assertTrue(f.session.liveMachineOutputs.isEmpty());
        assertNull(f.session.activeMachineOutputId);assertEquals(132,f.jars);
        assertEquals(3,f.checkpoints,"current inventory alone must not cause a pickup save");
        assertEquals(0,f.consumer.calls);assertEquals(0,f.submits);
    }

    @Test void newConnectionSessionCannotReconstructProofFromConfirmedPhaseAnd132Jars() {
        Fixture f=new Fixture();PendingMachineOutput confirmed=f.prepareConfirmed132ndOutput();
        f.failPickupSave(true);f.session=new SessionState();f.context=f.newContext();
        f.failCheckpoint=false;f.engine.start(f.context);f.engine.tick(f.context);
        assertFalse(f.engine.running());assertSame(confirmed,f.profile.pendingMachineOutputs.get(confirmed.id()));
        assertEquals(PendingMachineOutput.Phase.AWAITING_PICKUP,confirmed.phase());
        assertTrue(f.profile.machineOutputResolutions.isEmpty());assertTrue(f.session.liveMachineOutputs.isEmpty());
        assertEquals(132,f.jars);assertEquals(3,f.checkpoints);
        assertEquals(0,f.consumer.calls);assertEquals(0,f.submits);
    }

    @Test void repeatedStartSaveFailureRetainsProofAndEventuallyResolvesOnlyOnce() {
        Fixture f=new Fixture();PendingMachineOutput confirmed=f.prepareConfirmed132ndOutput();
        f.failPickupSave(true);f.engine.start(f.context);
        assertEquals(AutomationEngine.State.ERROR,f.engine.state());
        assertSame(confirmed,f.profile.pendingMachineOutputs.get(confirmed.id()));
        assertEquals(Set.of(confirmed.id()),f.session.liveMachineOutputs);
        assertNull(f.session.activeMachineOutputId);assertTrue(f.profile.machineOutputResolutions.isEmpty());
        assertEquals(0,f.consumer.calls);
        f.failCheckpoint=false;f.engine.start(f.context);
        assertTrue(f.engine.running());assertTrue(f.profile.pendingMachineOutputs.isEmpty());
        assertEquals(1,f.profile.machineOutputResolutions.size());
        f.engine.stop(f.context,AutomationEngine.State.PAUSED,"explicit pause after successful save");
        f.engine.start(f.context);
        assertEquals(1,f.profile.machineOutputResolutions.size());assertEquals(5,f.checkpoints);
        assertEquals(0,f.consumer.calls);assertEquals(0,f.submits);
    }

    @Test void alternateCheckpointContextUsesOnlyExistingLiveProofAndLeavesOriginalRuntimeBoundaryUntouched() {
        for(boolean saveFails:new boolean[]{false,true}) {
            Fixture f=new Fixture();PendingMachineOutput confirmed=f.prepareConfirmed132ndOutput();
            f.failPickupSave(true);
            Context original=f.context;Runnable originalCheckpoint=original.checkpoint();
            int[] directWrites={0};
            List<MachineOutputLedger.ResolutionEntry> savedHistory=new ArrayList<>();
            Context checkpointOnly=new Context(original.world(),original.actions(),original.navigation(),
                original.profile(),original.session(),()->{
                    directWrites[0]++;
                    assertSame(original,f.context);
                    assertTrue(f.failCheckpoint,"the original persistence-error callback remains unchanged");
                    assertFalse(f.engine.running());
                    assertTrue(f.profile.pendingMachineOutputs.isEmpty(),"the ordinary ledger mutation is what gets saved");
                    assertEquals(1,f.profile.machineOutputResolutions.size());
                    if(saveFails)throw new IllegalStateException("Direct writer still unavailable");
                    savedHistory.addAll(f.profile.machineOutputResolutions);
                });
            assertSame(original.world(),checkpointOnly.world());assertSame(original.actions(),checkpointOnly.actions());
            assertSame(original.navigation(),checkpointOnly.navigation());assertSame(original.profile(),checkpointOnly.profile());
            assertSame(original.session(),checkpointOnly.session());

            if(saveFails) {
                assertThrows(IllegalStateException.class,()->MachineOutputLedger.reconcile(checkpointOnly));
                assertSame(confirmed,f.profile.pendingMachineOutputs.get(confirmed.id()));
                assertEquals(Set.of(confirmed.id()),f.session.liveMachineOutputs);
                assertTrue(f.profile.machineOutputResolutions.isEmpty());assertTrue(savedHistory.isEmpty());
            } else {
                assertEquals(1,MachineOutputLedger.reconcile(checkpointOnly));
                assertTrue(f.profile.pendingMachineOutputs.isEmpty());assertTrue(f.session.liveMachineOutputs.isEmpty());
                assertEquals(1,savedHistory.size());assertSame(confirmed,savedHistory.get(0).output());
                assertEquals(MachineOutputLedger.Resolution.AUTOMATIC_PICKUP,savedHistory.get(0).resolution());
            }
            assertSame(original,f.context);assertSame(originalCheckpoint,f.context.checkpoint());
            assertTrue(f.failCheckpoint,"no persistence latch is cleared by this ledger-only operation");
            assertEquals(AutomationEngine.State.ERROR,f.engine.state());assertNull(f.session.activeMachineOutputId);
            assertEquals(132,f.jars,"no inventory or storage operation accompanies the checkpoint");
            assertEquals(789L,f.profile.nextEligibleDay.get("preserves:2:64:0"));
            assertEquals(1,directWrites[0]);assertEquals(3,f.checkpoints);
            assertEquals(0,f.consumer.calls);assertEquals(0,f.submits);
        }
    }

    private static final class Consumer implements AutomationModule {
        final Fixture fixture;int calls;
        Consumer(Fixture fixture){this.fixture=fixture;}
        public Feature feature(){return Feature.SHIPPING;}
        public int priority(){return 1;}
        public WorkResult tick(Context c){
            calls++;assertTrue(c.profile().pendingMachineOutputs.isEmpty(),"storage cannot consume unresolved jars");
            assertEquals(1,c.profile().machineOutputResolutions.size());fixture.jars=0;return WorkResult.idle();
        }
        public void reset(){}
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();SessionState session=new SessionState();
        final Consumer consumer=new Consumer(this);
        final AutomationEngine engine=new AutomationEngine(List.of(consumer));
        Context context=newContext();
        int jars=131,checkpoints,submits;boolean failCheckpoint,stopInsideCheckpoint;
        Context newContext(){return new Context(this,this,this,profile,session,this::checkpoint);}
        private void checkpoint(){
            checkpoints++;if(!failCheckpoint)return;
            // Exercise nested stop before ledger rollback, and a plain throwing persistence callback.
            if(stopInsideCheckpoint)engine.stop(context,AutomationEngine.State.ERROR,"nested persistence failure");
            throw new IllegalStateException("REPLACE AccessDeniedException fixture");
        }
        PendingMachineOutput prepareConfirmed132ndOutput(){
            profile.enabled.put(Feature.SHIPPING,true);engine.start(context);assertTrue(engine.running());
            PendingMachineOutput prepared=MachineOutputLedger.prepare(context,Feature.PRESERVES,JAR);
            assertEquals(132,prepared.minimumInventoryCount());
            MachineOutputLedger.confirmMachine(context,prepared.id());
            profile.nextEligibleDay.put("preserves:2:64:0",789L);jars=132;
            return profile.pendingMachineOutputs.get(prepared.id());
        }
        void failPickupSave(boolean nestedStop){
            stopInsideCheckpoint=nestedStop;failCheckpoint=true;
            assertThrows(IllegalStateException.class,()->MachineOutputLedger.reconcile(context));
            assertEquals(1,profile.pendingMachineOutputs.size());assertEquals(1,session.liveMachineOutputs.size());
            assertNotNull(session.activeMachineOutputId,"rollback restores the pre-save token even after nested stop");
            // Runtime's reconcile catch performs the outer stop after ledger rollback.
            engine.stop(context,AutomationEngine.State.ERROR,"Could not save machine output verification");
        }
        public long tick(){return 158872;}
        public long dayTime(){return 787L*24000+6000;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true);}
        public BlockData block(Pos p){return new BlockData(p,"society:preserves_jar",Map.of("mature","false","working","true"));}
        public boolean loaded(Pos p){return true;}
        public boolean canStand(Pos p){return true;}
        public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos p,int radius,int vertical){return List.of();}
        public List<ItemSlot> inventory(){
            List<ItemSlot> result=new ArrayList<>();
            for(int count=jars,index=0;count>0;count-=64,index++)
                result.add(new ItemSlot(index,index,true,new ItemData(ItemData.PRESERVES,Math.min(64,count),0,null,false,64)));
            return result;
        }
        public MenuData menu(){return new MenuData(0,0,inventory(),ItemData.EMPTY,false);}
        public boolean mayPlace(int slot,ItemData item){return true;}
        public boolean busy(){return false;}
        public long submit(Action action){submits++;fail("Recovery must not dispatch a native action");return 0;}
        public ActionOutcome outcome(long ticket){fail("No old native success may be fabricated or reread");return null;}
        public void move(Movement movement){fail("No navigation is part of pickup checkpoint recovery");}
        public void stopMovement(){}
        public void cancel(){}
        public Navigation.Result moveTo(Pos p,double range,Context c){fail("No navigation is part of pickup recovery");return Navigation.Result.BLOCKED;}
        public void reset(){}
    }
}
