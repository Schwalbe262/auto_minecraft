package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real core transactions; the guard has no ActionPort, send callback, ACK evaluator or mutable recovery API. */
class OptionalConsolidationSkipTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static InventoryConsolidation.Stack nativeItem(String id,int count){return new InventoryConsolidation.Stack(id,count,64);}
    private static ItemData visible(String id,int count){return new ItemData(id,count,0,ItemData.WINE.equals(id)?14:null,id.equals("hoe"),100);}
    private static final class Fixture {
        final List<InventoryConsolidation.Stack> items=new ArrayList<>(Collections.nCopies(36,EMPTY));
        final List<ItemData> projected=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        ProductionMergePlanner.Plan plan;
        InventoryConsolidation transaction;
        OptionalConsolidationSkip.Reason reason=OptionalConsolidationSkip.Reason.UNSENT_BASELINE_MISMATCH;
        boolean optional=true,inFlight=false,sameContext=true,sameProfile=true,sameSession=true,sameWorld=true,
            normalMenu=true,cursor=true,enabled=true,allowed=true,hoe=true,noLease=true,noPending=true,noFence=true,budget=true;
        long generation=5,observedGeneration=5,lastTick=100,tick=101;
        int menuId=0,menuSlots=46,protectedHotbar=4;
        Fixture(boolean borrowed) {
            put(4,"hoe",1);put(5,ItemData.TOMATO,3);put(9,ItemData.WINE,1);put(12,ItemData.WINE,2);
            if(borrowed)put(6,"torch",62);
            plan=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,6,List.of(12),projected);
            transaction=new InventoryConsolidation(plan,new InventoryConsolidation.Snapshot(items));
        }
        void put(int slot,String id,int count){items.set(slot,nativeItem(id,count));projected.set(slot,visible(id,count));}
        void swapAck(){Collections.swap(items,9,6);assertEquals(InventoryConsolidation.Confirmation.NEXT,transaction.acknowledge(new InventoryConsolidation.Snapshot(items)));}
        void mergeAck(){items.set(6,EMPTY);items.set(12,nativeItem(ItemData.WINE,3));transaction.acknowledge(new InventoryConsolidation.Snapshot(items));}
        OptionalConsolidationSkip.Boundary boundary(){return new OptionalConsolidationSkip.Boundary(reason,optional,inFlight,
            sameContext,sameProfile,sameSession,sameWorld,generation,observedGeneration,normalMenu,menuId,menuSlots,cursor,
            enabled,allowed,hoe,protectedHotbar,noLease,noPending,noFence,budget,lastTick,tick);}
        boolean skip(){return OptionalConsolidationSkip.allowed(transaction,plan,boundary());}
    }

    @Test void initialUnsentOptionalWineMismatchIsSkippedWithoutChangingCoreStateOrSendingAnything() {
        Fixture f=new Fixture(false);var before=f.transaction.expectedLive();var click=f.transaction.click();
        assertTrue(f.skip());assertSame(before,f.transaction.expectedLive());assertEquals(click,f.transaction.click());
        assertFalse(f.transaction.complete());assertEquals(0,f.transaction.freedSlots());
    }
    @Test void anAcknowledgedOutwardSwapUsingAnEmptyScratchCanAbandonOnlyItsNextUnsentMerge() {
        Fixture f=new Fixture(false);f.swapAck();assertFalse(f.transaction.requiresRestoration());
        assertEquals(InventoryConsolidation.Type.QUICK_MOVE,f.transaction.click().type());
        var before=f.transaction.expectedLive();assertTrue(f.skip());assertSame(before,f.transaction.expectedLive());
        assertFalse(f.transaction.complete());assertEquals(nativeItem(ItemData.WINE,1),before.items().get(6));
        f.inFlight=true;assertFalse(f.skip(),"The same core stage cannot skip a sent primitive");
    }
    @Test void actualBorrowedCustodyIsNeverDiscardedBeforeOrAfterMergeAcknowledgement() {
        Fixture f=new Fixture(true);
        assertTrue(f.skip(),"A planned but wholly unsent loan has not borrowed any item");
        f.swapAck();assertTrue(f.transaction.requiresRestoration());assertFalse(f.skip());
        f.mergeAck();assertTrue(f.transaction.requiresRestoration());assertFalse(f.skip());
        var before=f.transaction.expectedLive();assertFalse(f.skip());assertSame(before,f.transaction.expectedLive());
    }
    @Test void completedNullAndAbsentTransactionsCannotBeRelabeledSkipped() {
        Fixture f=new Fixture(false);f.swapAck();f.mergeAck();assertTrue(f.transaction.complete());assertFalse(f.skip());
        assertFalse(OptionalConsolidationSkip.allowed(null,f.plan,f.boundary()));
        assertFalse(OptionalConsolidationSkip.allowed(f.transaction,null,f.boundary()));
        assertFalse(OptionalConsolidationSkip.allowed(f.transaction,f.plan,null));
    }
    @Test void everyIdentityPermissionCustodyAndNativeBoundaryMustBePositivelyKnown() {
        for(int guard=0;guard<17;guard++) {
            Fixture f=new Fixture(false);
            switch(guard) {
                case 0->f.optional=false;case 1->f.inFlight=true;
                case 2->f.sameContext=false;case 3->f.sameProfile=false;case 4->f.sameSession=false;case 5->f.sameWorld=false;
                case 6->f.normalMenu=false;case 7->f.cursor=false;case 8->f.enabled=false;case 9->f.allowed=false;
                case 10->f.hoe=false;case 11->f.noLease=false;case 12->f.noPending=false;case 13->f.noFence=false;
                case 14->f.budget=false;case 15->f.reason=null;case 16->f.menuSlots=45;
            }
            assertFalse(f.skip(),"guard="+guard);
        }
    }
    @Test void timeoutsFaultsAndGameEpochOrTickRewindNeverBecomeAnOptionalSkip() {
        for(int failure=0;failure<8;failure++) {
            Fixture f=new Fixture(false);
            switch(failure) {
                case 0->f.reason=OptionalConsolidationSkip.Reason.TIMEOUT;
                case 1->f.reason=OptionalConsolidationSkip.Reason.FAULT;
                case 2->f.observedGeneration++;
                case 3->{f.generation=-1;f.observedGeneration=-1;}
                case 4->f.tick=99;case 5->f.lastTick=-1;case 6->f.menuId=1;case 7->f.menuSlots=90;
            }
            assertFalse(f.skip(),"failure="+failure);
        }
    }
    @Test void realRecoveryCancellationExpiryAndRollbackCannotGrantSkip() {
        for(int failure=0;failure<3;failure++) {
            Fixture f=new Fixture(false);ConsolidationRecoveryBudget budget=new ConsolidationRecoveryBudget();
            assertTrue(budget.awaitProof(100));
            switch(failure) {case 0->budget.cancel();case 1->f.tick=700;case 2->f.tick=99;}
            f.budget=budget.mayContinue(f.tick);assertFalse(f.budget);assertFalse(f.skip());
        }
    }
    @Test void tomatoesPreservesAndMissingOptionalOptInRemainMandatoryEvenIfTheirStageLooksSafe() {
        for(String id:List.of(ItemData.TOMATO,ItemData.PRESERVES)) {
            Fixture f=new Fixture(false);f.plan=new ProductionMergePlanner.Plan(Feature.WINE,id,9,6,List.of(12),f.projected);
            f.transaction=new InventoryConsolidation(f.plan,new InventoryConsolidation.Snapshot(f.items));assertFalse(f.skip());
        }
        Fixture f=new Fixture(false);f.plan=new ProductionMergePlanner.Plan(Feature.PRESERVES,ItemData.WINE,9,6,List.of(12),f.projected);
        f.transaction=new InventoryConsolidation(f.plan,new InventoryConsolidation.Snapshot(f.items));assertFalse(f.skip());
    }
    @Test void changedProtectedHoeOrUnsafePlanCannotAcquireTheSkipPath() {
        for(int change=0;change<5;change++) {
            Fixture f=new Fixture(false);
            switch(change) {
                case 0->f.protectedHotbar=-1;case 1->f.protectedHotbar=9;
                case 2->f.plan=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,4,6,List.of(12),f.projected);
                case 3->f.plan=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,4,List.of(12),f.projected);
                case 4->f.plan=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,6,List.of(4),f.projected);
            }
            assertFalse(f.skip(),"plan="+change);
        }
    }
}
