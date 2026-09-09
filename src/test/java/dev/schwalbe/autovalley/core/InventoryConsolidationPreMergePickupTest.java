package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** The fourth proof set represents native evidence before an UNSENT merge,
 * not authority to exempt a receiver from that merge's later exact ACK. */
class InventoryConsolidationPreMergePickupTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static final InventoryConsolidation.Stack TORCH=new InventoryConsolidation.Stack("torch",62,64);
    private static final InventoryConsolidation.Stack HOE=new InventoryConsolidation.Stack("protected_hoe",1,1);

    @Test void aProvenPreMergeWinePickupRetainsTheExactMergeAndBorrowedTorchRestoration() {
        Fixture f=new Fixture();f.swapOut();var merge=f.operation.click();
        f.items.set(13,wine(1));assertTrue(f.rebase(Set.of(13)));
        assertEquals(merge,f.operation.click());assertTrue(f.operation.requiresRestoration());
        assertFalse(f.operation.complete());assertEquals(WAIT,f.operation.acknowledge(f.snapshot()));

        f.merge();var restore=f.operation.click();
        assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.SWAP,9,6),restore);
        assertEquals(wine(31),f.operation.expectedLive().items().get(10));
        assertEquals(wine(1),f.operation.expectedLive().items().get(13));
        assertEquals(TORCH,f.operation.expectedLive().items().get(9));
        assertEquals(WAIT,f.operation.acknowledge(f.snapshot()),"A rebase or merge cannot stand in for restoration");

        Collections.swap(f.items,9,6);
        var wrong=new ArrayList<>(f.items);wrong.set(6,new InventoryConsolidation.Stack("torch",61,64));
        assertEquals(WAIT,f.operation.acknowledge(snapshot(wrong)));
        assertEquals(restore,f.operation.click());assertTrue(f.operation.requiresRestoration());
        assertEquals(COMPLETE,f.operation.acknowledge(f.snapshot()));
        assertEquals(TORCH,f.operation.expectedLive().items().get(6));
        assertEquals(EMPTY,f.operation.expectedLive().items().get(9));
        assertEquals(HOE,f.operation.expectedLive().items().get(4));
        assertEquals(wine(31),f.operation.expectedLive().items().get(10));
        assertEquals(wine(1),f.operation.expectedLive().items().get(13));
        assertFalse(f.operation.requiresRestoration());
    }

    @Test void emptyAndPartialImplicitReceiversCanTakeStrictlyPositiveSameKindAdditions() {
        for(int count:new int[]{0,1,30,63}) {
            Fixture f=new Fixture(count);f.swapOut();
            f.items.set(13,wine(count+1));assertTrue(f.rebase(Set.of(13)),"old receiver="+count);
            f.merge();Collections.swap(f.items,9,6);
            assertEquals(COMPLETE,f.operation.acknowledge(f.snapshot()));
            assertEquals(wine(count+1),f.operation.expectedLive().items().get(13));
        }
        Fixture f=new Fixture();f.swapOut();f.items.set(10,wine(31));
        assertTrue(f.rebase(Set.of(10)),"The advertised partial receiver also needs independent pre-send evidence");
        f.items.set(6,EMPTY);f.items.set(10,wine(32));
        assertEquals(NEXT,f.operation.acknowledge(f.snapshot()),"Only the later extra source item is the merge");
    }

    @Test void legacyOverloadsRemainStrictAndTheNewSetDoesNotAcknowledgeAnything() {
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
        assertTrue(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(),Set.of(),Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(f.snapshot()));
        f.items.set(13,wine(1));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13),Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13),Set.of(13),Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(f.snapshot(),Set.of(),Set.of(13)),
            "Concurrent-ACK proof cannot hide a same-source-kind receiver addition");
        unchanged(f,before,click);
        assertTrue(f.rebase(Set.of(13)));assertEquals(WAIT,f.operation.acknowledge(f.snapshot()));
        assertEquals(click,f.operation.click());assertFalse(f.operation.complete());
        assertTrue(f.operation.requiresRestoration());
    }

    @Test void receiverLossNoopReplacementAndChangedLimitsRemainAtomicRejections() {
        for(var after:List.of(EMPTY,wine(1),wine(2),new InventoryConsolidation.Stack("wine{Year:14}",3,64),
                new InventoryConsolidation.Stack("wine{Year:13}",3,16))) {
            Fixture f=new Fixture(2);f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
            f.items.set(13,after);assertFalse(f.rebase(Set.of(13)));unchanged(f,before,click);
        }
        Fixture empty=new Fixture();empty.swapOut();
        assertFalse(empty.rebase(Set.of(13)),"EMPTY to EMPTY is not a positive pickup");
    }

    @Test void receiverMustMatchTheCurrentMergeSourceAndFitAnOrdinarySlot() {
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
        f.items.set(13,new InventoryConsolidation.Stack("wine{Year:14}",1,64));
        assertFalse(f.rebase(Set.of(13)),"The fourth set is only the current source's exact native kind");
        unchanged(f,before,click);

        for(int count:new int[]{1,65}) {
            Fixture wide=new Fixture(0,128);wide.swapOut();var original=wide.operation.expectedLive();var next=wide.operation.click();
            wide.items.set(13,new InventoryConsolidation.Stack("wine{Year:13}",count,128));
            assertFalse(wide.rebase(Set.of(13)),"Even matching source/receiver limits above 64 are unsupported");
            unchanged(wide,original,next);
        }
    }

    @Test void allThreeProofSetsMustIndependentlyContainEveryNewReceiver() {
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
        f.items.set(13,wine(1));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(),Set.of(13),Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13),Set.of(),Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13),Set.of(13),Set.of()));
        assertFalse(f.operation.rebaseVerifiedUpdates(null,Set.of(13),Set.of(13),Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),null,Set.of(13),Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13),null,Set.of(13)));
        assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),Set.of(13),Set.of(13),null));
        unchanged(f,before,click);
    }

    @Test void malformedIndicesInAnyProofSetCannotChangeBaselineOrProgress() {
        for(Integer invalid:Arrays.asList(-1,36,Integer.MAX_VALUE,null))for(int proof=0;proof<3;proof++) {
            Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
            f.items.set(13,wine(1));Set<Integer> malformed=new HashSet<>(Set.of(13));malformed.add(invalid);
            assertFalse(f.operation.rebaseVerifiedUpdates(f.snapshot(),proof==0?malformed:Set.of(13),
                proof==1?malformed:Set.of(13),proof==2?malformed:Set.of(13)));
            unchanged(f,before,click);
        }
    }

    @Test void originalSourceScratchProtectedHoeAndSameRegionAreNotNewReceivers() {
        for(int index:new int[]{9,6,4,5,8}) {
            Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
            f.items.set(index,index==9?new InventoryConsolidation.Stack("torch",63,64):wine(2));
            assertFalse(f.rebase(Set.of(index)),"Protected/nonreceiver index="+index);unchanged(f,before,click);
        }
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
        f.items.set(13,wine(1));f.items.set(4,EMPTY);
        assertFalse(f.rebase(Set.of(13)),"A claimed pickup cannot hide an unproved protected-slot change");
        unchanged(f,before,click);
    }

    @Test void noPreAckDirectFirstMergePostMergeOrCompletedStageCanUseTheNewException() {
        Fixture f=new Fixture();var before=f.operation.expectedLive();var click=f.operation.click();f.items.set(13,wine(1));
        assertEquals(WAIT,f.operation.acknowledge(f.snapshot()));assertFalse(f.rebase(Set.of(13)));unchanged(f,before,click);

        Fixture direct=new Fixture();
        var directOperation=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,
            9,-1,List.of(5),Collections.nCopies(36,ItemData.EMPTY)),direct.snapshot());
        direct.items.set(13,wine(1));
        assertFalse(directOperation.rebaseVerifiedUpdates(direct.snapshot(),Set.of(13),Set.of(13),Set.of(13)));
        assertEquals(InventoryConsolidation.Type.QUICK_MOVE,directOperation.click().type());

        Fixture merged=new Fixture();merged.swapOut();merged.merge();before=merged.operation.expectedLive();click=merged.operation.click();
        merged.items.set(13,wine(1));assertFalse(merged.rebase(Set.of(13)),"The new proof must not be reused during RESTORE");
        unchanged(merged,before,click);merged.items.set(13,EMPTY);Collections.swap(merged.items,9,6);
        assertEquals(COMPLETE,merged.operation.acknowledge(merged.snapshot()));before=merged.operation.expectedLive();
        merged.items.set(13,wine(1));assertFalse(merged.rebase(Set.of(13)));assertEquals(before,merged.operation.expectedLive());
    }

    @Test void multipleReceiverRefreshesAreAllOrNothingAndPersistIntoTheExactAck() {
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();
        f.items.set(13,wine(1));f.items.set(35,wine(2));
        assertFalse(f.rebase(Set.of(13)));unchanged(f,before,click);
        f.items.set(35,new InventoryConsolidation.Stack("wine{Year:14}",2,64));
        assertFalse(f.rebase(Set.of(13,35)));unchanged(f,before,click);
        f.items.set(35,wine(2));assertTrue(f.rebase(Set.of(13,35)));
        f.merge();Collections.swap(f.items,9,6);
        var missing=new ArrayList<>(f.items);missing.set(13,EMPTY);
        assertEquals(WAIT,f.operation.acknowledge(snapshot(missing)));
        assertEquals(COMPLETE,f.operation.acknowledge(f.snapshot()));
        assertEquals(wine(1),f.operation.expectedLive().items().get(13));
        assertEquals(wine(2),f.operation.expectedLive().items().get(35));
    }

    @Test void mergeAckStillRequiresExactConservationAndCannotRecountTheEarlierPickup() {
        Fixture f=new Fixture();f.swapOut();f.items.set(13,wine(1));assertTrue(f.rebase(Set.of(13)));
        var before=f.operation.expectedLive();var click=f.operation.click();
        for(int receiverCount:new int[]{30,32}) {
            var wrong=new ArrayList<>(f.items);wrong.set(6,EMPTY);wrong.set(10,wine(receiverCount));
            assertEquals(WAIT,f.operation.acknowledge(snapshot(wrong)));unchanged(f,before,click);
        }
        var wrong=new ArrayList<>(f.items);wrong.set(6,EMPTY);wrong.set(10,wine(31));wrong.set(13,EMPTY);
        assertEquals(WAIT,f.operation.acknowledge(snapshot(wrong)));unchanged(f,before,click);
        wrong=new ArrayList<>(f.items);wrong.set(6,EMPTY);wrong.set(10,wine(31));wrong.set(13,wine(2));
        assertEquals(WAIT,f.operation.acknowledge(snapshot(wrong)),"A later same-kind pickup needs its own evidence, not the old set");
        unchanged(f,before,click);f.merge();
    }

    private static InventoryConsolidation.Stack wine(int count){return new InventoryConsolidation.Stack("wine{Year:13}",count,64);}
    private static InventoryConsolidation.Snapshot snapshot(List<InventoryConsolidation.Stack> items){return new InventoryConsolidation.Snapshot(items);}
    private static void unchanged(Fixture f,InventoryConsolidation.Snapshot before,InventoryConsolidation.Click click) {
        assertEquals(before,f.operation.expectedLive());assertEquals(click,f.operation.click());assertFalse(f.operation.complete());
    }
    private static final class Fixture {
        final List<InventoryConsolidation.Stack> items=new ArrayList<>(Collections.nCopies(36,EMPTY));
        final InventoryConsolidation operation;
        Fixture(){this(0);}
        Fixture(int receiverCount){this(receiverCount,64);}
        Fixture(int receiverCount,int sourceLimit) {
            items.set(4,HOE);items.set(6,TORCH);items.set(9,new InventoryConsolidation.Stack("wine{Year:13}",1,sourceLimit));
            items.set(10,wine(30));if(receiverCount>0)items.set(13,wine(receiverCount));
            var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
            visible.set(6,new ItemData("minecraft:torch",62,0,null,false,0));
            operation=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,6,List.of(10),visible),snapshot());
        }
        InventoryConsolidation.Snapshot snapshot(){return InventoryConsolidationPreMergePickupTest.snapshot(items);}
        void swapOut(){Collections.swap(items,9,6);assertEquals(NEXT,operation.acknowledge(snapshot()));}
        void merge(){items.set(6,EMPTY);items.set(10,wine(31));assertEquals(NEXT,operation.acknowledge(snapshot()));}
        boolean rebase(Set<Integer> indices){return operation.rebaseVerifiedUpdates(snapshot(),indices,indices,indices);}
    }
}
