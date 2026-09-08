package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the exact ACK dispatcher used by MinecraftActions, with detached native-identity fixtures. */
class ConsolidationRecoveryTest {
    @Test void manualRestoreCannotUseOldOrSameEpochInFlightSnapshots() {
        assertFalse(ConsolidationRecoveryFlow.manualRestoreCandidate(false,3,3,3,100,99));
        assertFalse(ConsolidationRecoveryFlow.manualRestoreCandidate(false,3,3,3,100,100));
        assertTrue(ConsolidationRecoveryFlow.manualRestoreCandidate(false,3,3,3,100,101));
        assertFalse(ConsolidationRecoveryFlow.manualRestoreCandidate(true,3,3,3,100,101));
        assertFalse(ConsolidationRecoveryFlow.manualRestoreCandidate(false,3,2,3,100,999));
    }
    @Test void newEpochCanProveExactManualCustodyButCannotAuthorizeResendingAnOldClick() {
        Harness h=new Harness();Collections.swap(h.items,13,6);h.acks=List.of(snap(h.items));h.now=1;h.poll();
        assertTrue(h.inFlight);assertTrue(h.tx.requiresRestoration());h.budget.cancel();
        assertTrue(ConsolidationRecoveryFlow.manualRestoreCandidate(true,3,4,3,100,0));
        assertFalse(h.tx.acknowledgeCancelledRestoration(snap(h.items)),"A new epoch alone proves nothing");
        Collections.swap(h.items,13,6);assertTrue(h.tx.acknowledgeCancelledRestoration(snap(h.items)));
        assertEquals(2,h.sent.size());assertFalse(h.completed);assertFalse(h.tx.requiresRestoration());
        h.now=2;h.poll();assertEquals(2,h.sent.size(),"Custody proof never rearms cancelled automatic execution");
    }
    @Test void repeatedWaitsDoNotRenewTheSingleTransactionDeadline() {
        var budget=new ConsolidationRecoveryBudget();
        assertTrue(budget.awaitProof(100));assertEquals(600,budget.remainingTicks(100));
        for(int tick=101;tick<700;tick++) assertTrue(budget.awaitProof(tick));
        assertFalse(budget.mayContinue(700));assertFalse(budget.awaitProof(700));
    }
    @Test void aLaterPrimitiveKeepsTheFirstRecoveryDeadline() {
        var budget=new ConsolidationRecoveryBudget();assertTrue(budget.awaitProof(50));
        assertTrue(budget.mayContinue(100));assertTrue(budget.awaitProof(500));
        assertEquals(150,budget.remainingTicks(500));assertFalse(budget.mayContinue(650));
    }
    @Test void cancellationAndClockRollbackCannotRearmRecovery() {
        for(boolean rollback:new boolean[]{false,true}) {
            var budget=new ConsolidationRecoveryBudget();assertTrue(budget.awaitProof(100));
            if(rollback) assertFalse(budget.mayContinue(99));else budget.cancel();
            assertFalse(budget.awaitProof(101));assertFalse(budget.mayContinue(1000));
        }
        assertFalse(new ConsolidationRecoveryBudget().mayContinue(-1));
    }
    @Test void timedOutNoopAndMalformedRepliesNeverResendTheCurrentPrimitive() {
        Harness h=new Harness();h.timedOut=true;
        var malformed=new ArrayList<>(h.items);malformed.set(13,stack("wineA",3));
        h.acks=List.of(snap(h.items),snap(malformed));
        for(int tick=100;tick<700;tick++){h.now=tick;h.poll();}
        assertEquals(1,h.sent.size());assertTrue(h.inFlight);assertFalse(h.completed);
        assertEquals(0,h.expirations);h.now=700;h.poll();assertEquals(1,h.expirations);
        assertEquals(1,h.sent.size());assertTrue(h.inFlight,"The unresolved native click remains fenced");
    }
    @Test void lateExactAcksContinueOnlyUnsentMergeAndInverseSwapOnTheSameTransaction() {
        Harness h=new Harness();h.now=100;h.timedOut=true;h.poll();
        Collections.swap(h.items,13,6);h.acks=List.of(snap(h.items));h.now=200;h.poll();
        assertEquals(2,h.sent.size());assertTrue(h.tx.requiresRestoration());
        assertEquals(InventoryConsolidation.Type.QUICK_MOVE,h.sent.get(1).type());
        h.items.set(6,InventoryConsolidation.Stack.EMPTY);h.items.set(14,stack("wineA",3));
        h.items.set(9,stack("wineB",1));h.additions=Set.of(9);h.acks=List.of(snap(h.items));h.now=300;h.poll();
        assertEquals(3,h.sent.size());assertEquals(InventoryConsolidation.Type.SWAP,h.sent.get(2).type());
        Collections.swap(h.items,13,6);h.additions=Set.of();h.acks=List.of(snap(h.items));h.now=400;h.poll();
        assertTrue(h.completed);assertFalse(h.tx.requiresRestoration());
        assertEquals(stack("bread",8),h.items.get(6));assertEquals(stack("wineB",1),h.items.get(9));
        assertEquals(3,h.sent.size());assertEquals(0,h.expirations);
    }
    @Test void anExactAckWithoutItsLiveBaselineHoldsTheUnsentStepWithoutReadingMoreAcks() {
        Harness h=new Harness();Collections.swap(h.items,13,6);h.acks=List.of(snap(h.items));
        h.liveMatches=false;h.now=100;h.poll();assertFalse(h.inFlight);assertEquals(1,h.sent.size());
        int reads=h.ackReads;
        for(int tick=101;tick<110;tick++){h.now=tick;h.poll();}
        assertEquals(reads,h.ackReads);assertEquals(1,h.sent.size());
        h.liveMatches=true;h.now=110;h.poll();assertTrue(h.inFlight);assertEquals(2,h.sent.size());
    }
    @Test void manualOffDuringRecoveryCannotSendEvenAnOtherwiseProvenNextStep() {
        Harness h=new Harness();h.timedOut=true;h.now=100;h.poll();h.budget.cancel();
        Collections.swap(h.items,13,6);h.acks=List.of(snap(h.items));h.now=101;h.poll();
        assertEquals(1,h.sent.size());assertEquals(0,h.ackReads);assertFalse(h.completed);
    }
    @Test void manualCancellationAtTheAckBoundaryWinsBeforeTheNextClick() {
        Harness h=new Harness();h.cancelOnAck=true;Collections.swap(h.items,13,6);
        h.acks=List.of(snap(h.items));h.now=1;h.poll();
        assertEquals(1,h.sent.size());assertTrue(h.tx.requiresRestoration());assertFalse(h.completed);
        h.now=2;h.poll();assertEquals(1,h.sent.size());
    }
    @Test void anExactAckAfterTheDeadlineCannotAutomaticallyRestoreOrResume() {
        Harness h=new Harness();h.now=100;h.timedOut=true;h.poll();
        Collections.swap(h.items,13,6);h.acks=List.of(snap(h.items));h.now=200;h.poll();
        h.items.set(6,InventoryConsolidation.Stack.EMPTY);h.items.set(14,stack("wineA",3));
        h.acks=List.of(snap(h.items));h.now=700;h.poll();
        assertEquals(2,h.sent.size());assertEquals(1,h.expirations);assertTrue(h.tx.requiresRestoration());
    }
    @Test void cancellingAfterTheMergeKeepsTheLoanAndOnlyExactManualInverseCanClearIt() {
        Harness h=new Harness();Collections.swap(h.items,13,6);h.acks=List.of(snap(h.items));h.now=1;h.poll();
        h.items.set(6,InventoryConsolidation.Stack.EMPTY);h.items.set(14,stack("wineA",3));
        h.liveMatches=false;h.acks=List.of(snap(h.items));h.now=2;h.poll();
        assertFalse(h.inFlight);assertTrue(h.tx.requiresRestoration());h.budget.cancel();
        assertFalse(h.tx.acknowledgeCancelledRestoration(snap(h.items)),"No-op is not restoration");
        var wrong=new ArrayList<>(h.items);Collections.swap(wrong,13,6);wrong.set(14,stack("wineA",4));
        assertFalse(h.tx.acknowledgeCancelledRestoration(snap(wrong)));
        Collections.swap(h.items,13,6);assertTrue(h.tx.acknowledgeCancelledRestoration(snap(h.items)));
        assertFalse(h.tx.requiresRestoration());assertFalse(h.completed,"Manual cleanup is not automatic production success");
        assertEquals(2,h.sent.size());
    }

    private static InventoryConsolidation.Stack stack(String id,int count){return new InventoryConsolidation.Stack(id,count,64);}
    private static InventoryConsolidation.Snapshot snap(List<InventoryConsolidation.Stack> items){return new InventoryConsolidation.Snapshot(items);}
    private static final class Harness implements ConsolidationRecoveryFlow.Step<InventoryConsolidation.Snapshot> {
        final List<InventoryConsolidation.Stack> items=new ArrayList<>(Collections.nCopies(36,InventoryConsolidation.Stack.EMPTY));
        final List<InventoryConsolidation.Click> sent=new ArrayList<>();final ConsolidationRecoveryBudget budget=new ConsolidationRecoveryBudget();
        final InventoryConsolidation tx;long now;boolean inFlight,timedOut,liveMatches=true,completed,cancelOnAck;
        int expirations,ackReads;List<InventoryConsolidation.Snapshot> acks=List.of();Set<Integer> additions=Set.of();
        Harness(){
            items.set(6,stack("bread",8));items.set(13,stack("wineA",2));items.set(14,stack("wineA",1));
            var publicItems=items.stream().map(s->s.empty()?ItemData.EMPTY:new ItemData(s.identity().startsWith("wine")?ItemData.WINE:"minecraft:bread",s.count(),0,10,false,1000)).toList();
            tx=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,13,6,List.of(14),publicItems),snap(items));sendUnsent();
        }
        void poll(){ConsolidationRecoveryFlow.poll(budget,now,this);}
        public boolean inFlight(){return inFlight;}public boolean normalTimeout(){return timedOut;}
        public Iterable<InventoryConsolidation.Snapshot> acknowledgements(){return acks;}
        public InventoryConsolidation.Confirmation acknowledge(InventoryConsolidation.Snapshot ack){ackReads++;return tx.acknowledge(ack,Set.of(),additions);}
        public void markAcknowledged(){inFlight=false;if(cancelOnAck)budget.cancel();}
        public void complete(){completed=true;}
        public void sendUnsent(){
            assertFalse(inFlight,"Never replay an in-flight primitive");if(!budget.mayContinue(now)){expired();return;}
            if(!liveMatches){hold();return;}sent.add(tx.click());inFlight=true;
        }
        public void hold(){if(!budget.awaitProof(now))expired();}
        public void expired(){expirations++;budget.cancel();}
    }
}
