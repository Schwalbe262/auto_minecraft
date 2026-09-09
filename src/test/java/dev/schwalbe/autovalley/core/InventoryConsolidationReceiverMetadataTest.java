package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** The fourth ACK proof set represents independently verified native metadata,
 * never authority to ignore a receiver's original count or a missing move. */
class InventoryConsolidationReceiverMetadataTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static final InventoryConsolidation.Stack TORCH=stack("torch",62);
    private static final InventoryConsolidation.Stack HOE=new InventoryConsolidation.Stack("protected_hoe",1,1);
    private static final InventoryConsolidation.Stack UNINITIALIZED=stack("wine_uninitialized",1);

    @Test void aProvenReceiverInitializationConservesItsOldCountAndRestoresTheExactBorrowedTorch() {
        Fixture f=new Fixture();assertFalse(f.operation.allowsReceiverMetadataUpdate(10,wine(2)));
        f.swapOut();var baseline=f.operation.expectedLive();var mergeClick=f.operation.click();
        assertTrue(f.operation.allowsReceiverMetadataUpdate(10,wine(2)));
        assertSame(baseline,f.operation.expectedLive());assertEquals(mergeClick,f.operation.click());
        var merged=f.merge(0,2);
        assertEquals(NEXT,f.operation.acknowledge(merged,Set.of(),Set.of(),Set.of(10)));
        assertSame(merged,f.operation.expectedLive());assertTrue(f.operation.requiresRestoration());
        assertEquals(new InventoryConsolidation.Click(InventoryConsolidation.Type.SWAP,9,6),f.operation.click());
        assertEquals(wine(2),merged.items().get(10));assertEquals(wine(33),merged.items().get(12));
        assertEquals(TORCH,merged.items().get(9));assertEquals(HOE,merged.items().get(4));
        var restored=swap(merged,9,6);var wrong=change(restored,6,stack("torch",61));
        waitUnchanged(f.operation,wrong,Set.of(),Set.of(),Set.of());
        assertEquals(COMPLETE,f.operation.acknowledge(restored));assertFalse(f.operation.requiresRestoration());
        assertEquals(TORCH,f.operation.expectedLive().items().get(6));assertEquals(EMPTY,f.operation.expectedLive().items().get(9));
        assertEquals(1,f.operation.freedSlots());
    }

    @Test void everyLegacyAckOverloadStillRejectsTheChangedReceiverIdentity() {
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();var click=f.operation.click();var after=f.merge(0,2);
        assertEquals(WAIT,f.operation.acknowledge(after));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of(),Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of(10),Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of(),Set.of(10)));
        assertSame(before,f.operation.expectedLive());assertEquals(click,f.operation.click());assertTrue(f.operation.requiresRestoration());
        assertEquals(NEXT,f.operation.acknowledge(after,Set.of(),Set.of(),Set.of(10)));
    }

    @Test void aMetadataProofCannotCreateDropOrRecountTheMovedItem() {
        Fixture f=new Fixture();f.swapOut();
        for(int count:new int[]{0,1,3,64})waitUnchanged(f.operation,f.merge(0,count),Set.of(),Set.of(),Set.of(10));
        assertTrue(f.operation.allowsReceiverMetadataUpdate(10,wine(3)),"structural eligibility alone does not prove exact conservation");
        waitUnchanged(f.operation,change(f.merge(0,2),12,wine(34)),Set.of(),Set.of(),Set.of(10));
        waitUnchanged(f.operation,change(f.merge(0,2),12,wine(32)),Set.of(),Set.of(),Set.of(10));
        assertEquals(NEXT,f.operation.acknowledge(f.merge(0,2),Set.of(),Set.of(),Set.of(10)));
    }

    @Test void metadataOnlyOrUnchangedRepliesNeverAcknowledgeAMerge() {
        Fixture f=new Fixture();f.swapOut();var before=f.operation.expectedLive();
        assertFalse(f.operation.allowsReceiverMetadataUpdate(10,wine(1)));
        waitUnchanged(f.operation,change(before,10,wine(1)),Set.of(),Set.of(),Set.of(10));
        waitUnchanged(f.operation,change(before,10,wine(2)),Set.of(),Set.of(),Set.of(10));
        waitUnchanged(f.operation,before,Set.of(),Set.of(),Set.of());
        waitUnchanged(f.operation,before,Set.of(),Set.of(),Set.of(10));
        assertTrue(f.operation.requiresRestoration());
    }

    @Test void aReceiverMustMatchTheCurrentSourceAndPreserveItsSupportedStackLimit() {
        Fixture f=new Fixture();f.swapOut();
        for(var incompatible:List.of(stack("wine_B",2),new InventoryConsolidation.Stack("wine_A",2,16),EMPTY,UNINITIALIZED)) {
            assertFalse(f.operation.allowsReceiverMetadataUpdate(10,incompatible));
            waitUnchanged(f.operation,change(f.merge(0,2),10,incompatible),Set.of(),Set.of(),Set.of(10));
        }
        Fixture wide=new Fixture(1,new InventoryConsolidation.Stack("wine_uninitialized",1,128),0,128);wide.swapOut();
        var tooWide=new InventoryConsolidation.Stack("wine_A",2,128);
        assertFalse(wide.operation.allowsReceiverMetadataUpdate(10,tooWide));
        waitUnchanged(wide.operation,change(wide.merge(0,2),10,tooWide),Set.of(),Set.of(),Set.of(10));
        assertFalse(f.operation.allowsReceiverMetadataUpdate(10,null));
    }

    @Test void borrowedSourceScratchSameRegionAndEmptySlotsAreNotMetadataReceivers() {
        Fixture f=new Fixture();f.swapOut();
        for(int index:new int[]{9,6,5,4,13,-1,36,Integer.MAX_VALUE}) {
            assertFalse(f.operation.allowsReceiverMetadataUpdate(index,wine(index==9?63:2)),"index="+index);
            waitUnchanged(f.operation,f.merge(0,2),Set.of(),Set.of(),Set.of(index));
        }
        Fixture empty=new Fixture(1,EMPTY,0,64);empty.swapOut();var nativeEmptyReceiver=empty.merge(0,1);
        assertFalse(empty.operation.allowsReceiverMetadataUpdate(10,wine(1)));
        waitUnchanged(empty.operation,nativeEmptyReceiver,Set.of(),Set.of(),Set.of(10));
        assertEquals(NEXT,empty.operation.acknowledge(nativeEmptyReceiver),"an ordinary empty receiver needs no metadata exception");
        Fixture compatible=new Fixture(1,wine(1),0,64);compatible.swapOut();var unchangedKind=compatible.merge(0,2);
        assertFalse(compatible.operation.allowsReceiverMetadataUpdate(10,wine(2)));
        waitUnchanged(compatible.operation,unchangedKind,Set.of(),Set.of(),Set.of(10));
        assertEquals(NEXT,compatible.operation.acknowledge(unchangedKind),"an unchanged receiver identity stays on the ordinary exact proof path");
    }

    @Test void unprovenSourceBorrowedItemProtectedSlotOrOtherChangesCannotHideInsideTheAck() {
        Fixture f=new Fixture();f.swapOut();var merged=f.merge(0,2);
        for(var wrong:List.of(change(merged,9,stack("torch",61)),change(merged,4,EMPTY),
                change(merged,15,stack("food_cached",2)),change(merged,16,stack("tomato",1))))
            waitUnchanged(f.operation,wrong,Set.of(),Set.of(),Set.of(10));
        Fixture partial=new Fixture(4,UNINITIALIZED,0,64);partial.swapOut();
        waitUnchanged(partial.operation,change(partial.merge(1,4),6,stack("wine_B",1)),Set.of(),Set.of(),Set.of(10));
        assertEquals(NEXT,f.operation.acknowledge(merged,Set.of(),Set.of(),Set.of(10)));
    }

    @Test void disjointVerifiedUnrelatedChangesCanCoexistButOverlappingProofsCannot() {
        Fixture f=new Fixture();f.swapOut();var merged=f.merge(0,2);
        waitUnchanged(f.operation,merged,Set.of(10),Set.of(),Set.of(10));
        waitUnchanged(f.operation,merged,Set.of(),Set.of(10),Set.of(10));
        waitUnchanged(f.operation,merged,Set.of(10),Set.of(10),Set.of(10));
        var concurrent=change(change(merged,15,stack("food_cached",2)),16,stack("tomato",1));
        assertEquals(NEXT,f.operation.acknowledge(concurrent,Set.of(15),Set.of(16),Set.of(10)));
        assertEquals(stack("food_cached",2),f.operation.expectedLive().items().get(15));
        assertEquals(stack("tomato",1),f.operation.expectedLive().items().get(16));
        assertEquals(COMPLETE,f.operation.acknowledge(swap(concurrent,9,6)));
    }

    @Test void nullOrMalformedProofArgumentsAreRejectedWithoutChangingProgress() {
        Fixture f=new Fixture();f.swapOut();var merged=f.merge(0,2);
        waitUnchanged(f.operation,null,Set.of(),Set.of(),Set.of(10));
        waitUnchanged(f.operation,merged,null,Set.of(),Set.of(10));
        waitUnchanged(f.operation,merged,Set.of(),null,Set.of(10));
        waitUnchanged(f.operation,merged,Set.of(),Set.of(),null);
        for(Integer invalid:Arrays.asList(-1,36,Integer.MAX_VALUE,null))for(int argument=0;argument<3;argument++) {
            Set<Integer> malformed=new HashSet<>();malformed.add(invalid);
            waitUnchanged(f.operation,merged,argument==0?malformed:Set.of(),argument==1?malformed:Set.of(),
                argument==2?malformed:Set.of(10));
        }
    }

    @Test void receiverMetadataAuthorityCannotBeReusedForOutwardSwapRestoreOrDone() {
        Fixture f=new Fixture();var swapped=swap(f.operation.expectedLive(),9,6);
        assertFalse(f.operation.allowsReceiverMetadataUpdate(10,wine(2)));
        waitUnchanged(f.operation,change(swapped,10,wine(2)),Set.of(),Set.of(),Set.of(10));
        f.swapOut();var merged=f.merge(0,2);assertEquals(NEXT,f.operation.acknowledge(merged,Set.of(),Set.of(),Set.of(10)));
        var restored=swap(merged,9,6);assertFalse(f.operation.allowsReceiverMetadataUpdate(12,wine(34)));
        waitUnchanged(f.operation,change(restored,12,wine(34)),Set.of(),Set.of(),Set.of(12));
        assertEquals(COMPLETE,f.operation.acknowledge(restored));
        assertFalse(f.operation.allowsReceiverMetadataUpdate(10,wine(3)));
        waitUnchanged(f.operation,change(restored,10,wine(3)),Set.of(),Set.of(),Set.of(10));
    }

    @Test void multipleMetadataReceiversMustAllBeProvenAndTheirTotalMustEqualTheSourceLoss() {
        Fixture f=new Fixture(3,UNINITIALIZED,2,64);f.swapOut();var merged=change(f.merge(0,2),11,wine(4));
        waitUnchanged(f.operation,merged,Set.of(),Set.of(),Set.of(10));
        waitUnchanged(f.operation,change(merged,11,stack("wine_B",4)),Set.of(),Set.of(),Set.of(10,11));
        waitUnchanged(f.operation,change(merged,11,wine(5)),Set.of(),Set.of(),Set.of(10,11));
        assertEquals(NEXT,f.operation.acknowledge(merged,Set.of(),Set.of(),Set.of(10,11)));
        assertEquals(wine(2),f.operation.expectedLive().items().get(10));assertEquals(wine(4),f.operation.expectedLive().items().get(11));
        assertEquals(COMPLETE,f.operation.acknowledge(swap(merged,9,6)));
    }

    @Test void partialMovesRestoreOnlyTheirExactlyConfirmedRemainder() {
        Fixture f=new Fixture(4,UNINITIALIZED,0,64);f.swapOut();var partial=f.merge(2,3);
        assertEquals(NEXT,f.operation.acknowledge(partial,Set.of(),Set.of(),Set.of(10)));
        var restored=swap(partial,9,6);
        waitUnchanged(f.operation,change(restored,9,wine(1)),Set.of(),Set.of(),Set.of());
        assertEquals(COMPLETE,f.operation.acknowledge(restored));
        assertEquals(wine(2),f.operation.expectedLive().items().get(9));assertEquals(TORCH,f.operation.expectedLive().items().get(6));
        assertEquals(wine(3),f.operation.expectedLive().items().get(10));assertEquals(0,f.operation.freedSlots());
    }

    private static InventoryConsolidation.Stack stack(String identity,int count) {
        return count==0?EMPTY:new InventoryConsolidation.Stack(identity,count,64);
    }
    private static InventoryConsolidation.Stack wine(int count){return stack("wine_A",count);}
    private static InventoryConsolidation.Snapshot change(InventoryConsolidation.Snapshot snapshot,int index,InventoryConsolidation.Stack item) {
        var items=new ArrayList<>(snapshot.items());items.set(index,item);return new InventoryConsolidation.Snapshot(items);
    }
    private static InventoryConsolidation.Snapshot swap(InventoryConsolidation.Snapshot snapshot,int a,int b) {
        var items=new ArrayList<>(snapshot.items());Collections.swap(items,a,b);return new InventoryConsolidation.Snapshot(items);
    }
    private static void waitUnchanged(InventoryConsolidation operation,InventoryConsolidation.Snapshot after,
                                      Set<Integer> passive,Set<Integer> additions,Set<Integer> receivers) {
        var before=operation.expectedLive();var click=operation.complete()?null:operation.click();
        boolean done=operation.complete(),restore=operation.requiresRestoration();
        assertEquals(WAIT,operation.acknowledge(after,passive,additions,receivers));
        assertSame(before,operation.expectedLive());assertEquals(done,operation.complete());assertEquals(restore,operation.requiresRestoration());
        if(click!=null)assertEquals(click,operation.click());
    }
    private static final class Fixture {
        final InventoryConsolidation operation;
        Fixture(){this(1,UNINITIALIZED,0,64);}
        Fixture(int sourceCount,InventoryConsolidation.Stack receiver,int secondCount,int sourceLimit) {
            var items=new ArrayList<>(Collections.nCopies(36,EMPTY));
            items.set(4,HOE);items.set(5,UNINITIALIZED);items.set(6,TORCH);
            items.set(9,new InventoryConsolidation.Stack("wine_A",sourceCount,sourceLimit));items.set(10,receiver);
            if(secondCount>0)items.set(11,stack("wine_other_uninitialized",secondCount));
            items.set(12,wine(33));items.set(15,stack("food",2));
            var visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
            visible.set(6,new ItemData("minecraft:torch",62,0,null,false,0));
            operation=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,6,List.of(12),visible),
                new InventoryConsolidation.Snapshot(items));
        }
        void swapOut(){assertEquals(NEXT,operation.acknowledge(swap(operation.expectedLive(),9,6)));}
        InventoryConsolidation.Snapshot merge(int remaining,int receiverCount){return change(change(operation.expectedLive(),6,wine(remaining)),10,wine(receiverCount));}
    }
}
