package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** The fourth, independently proven event is a pickup, never a replacement for either earlier ACK. */
class InventoryConsolidationRestorePickupAckTest {
    private static final InventoryConsolidation.Stack EMPTY=InventoryConsolidation.Stack.EMPTY;
    private static final InventoryConsolidation.Stack TORCH=new InventoryConsolidation.Stack("torch{exact}",62,64);
    private static final InventoryConsolidation.Stack HOE=new InventoryConsolidation.Stack("protected_hoe{exact}",1,1);
    private static InventoryConsolidation.Stack wine(int count){return new InventoryConsolidation.Stack("wine{initialized:A}",count,64);}
    private static InventoryConsolidation.Snapshot snapshot(List<InventoryConsolidation.Stack> items){return new InventoryConsolidation.Snapshot(items);}

    @Test void fullRestoreWithAnIndependentlyProvenSameKindPickupPreservesEveryUnit() {
        for(int pickedUp:new int[]{1,64}) {
            Fixture f=new Fixture(); f.merged(); var pickup=wine(pickedUp); var after=f.restored(pickup);
            assertEquals(COMPLETE,f.operation.acknowledgeRestorePickup(after,pickup));
            assertSame(after,f.operation.expectedLive()); assertTrue(f.operation.complete()); assertFalse(f.operation.requiresRestoration());
            assertEquals(TORCH,after.items().get(6)); assertEquals(pickup,after.items().get(10));
            assertEquals(wine(34),after.items().get(12)); assertEquals(HOE,after.items().get(4));
            assertEquals(34+pickedUp,after.items().stream().filter(s->s.identity().equals(wine(1).identity())).mapToInt(InventoryConsolidation.Stack::count).sum());
            assertEquals(62,after.items().stream().filter(s->s.identity().equals(TORCH.identity())).mapToInt(InventoryConsolidation.Stack::count).sum());
            assertEquals(0,f.operation.freedSlots(),"The real pickup occupies the slot freed by the earlier merge");
        }
    }

    @Test void legacyAcknowledgementsAndCancelledRestoreCannotBorrowTheNewPickupAuthority() {
        Fixture f=new Fixture(); f.merged(); var before=f.operation.expectedLive(); var after=f.restored(wine(1));
        assertEquals(WAIT,f.operation.acknowledge(after));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of(),Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of(),Set.of(),Set.of()));
        assertEquals(WAIT,f.operation.acknowledge(after,Set.of(),Set.of(10)));
        assertFalse(f.operation.acknowledgeCancelledRestoration(after)); assertSame(before,f.operation.expectedLive());
        assertEquals(COMPLETE,f.operation.acknowledgeRestorePickup(after,wine(1)));
    }

    @Test void aNativeVerifiedFinalIdentityIsOpaqueButMustMatchTheFinalSourceExactly() {
        Fixture f=new Fixture(); f.merged();
        var finalPickup=new InventoryConsolidation.Stack("wine{initialized:B,cache:verified}",1,64);
        var rawPickup=new InventoryConsolidation.Stack("wine{uninitialized}",1,64);
        f.waitUnchanged(f.restored(finalPickup),rawPickup);
        // Native raw identity -> final identity initialization is proved outside this core API.
        assertEquals(COMPLETE,f.operation.acknowledgeRestorePickup(f.restored(finalPickup),finalPickup));
        assertEquals(TORCH,f.operation.expectedLive().items().get(6));
        assertEquals(finalPickup,f.operation.expectedLive().items().get(10));
    }

    @Test void noOpPickupOnlyMissingProofAndOrdinaryEmptyRestoreCannotFakeThisAcknowledgement() {
        Fixture f=new Fixture(); f.merged();
        f.waitUnchanged(f.operation.expectedLive(),wine(1));
        var pickupOnly=new ArrayList<>(f.items); pickupOnly.set(6,wine(1));
        f.waitUnchanged(snapshot(pickupOnly),wine(1));
        f.waitUnchanged(f.restored(wine(1)),null); f.waitUnchanged(f.restored(wine(1)),EMPTY);
        f.waitUnchanged(null,wine(1)); f.waitUnchanged(f.restored(EMPTY),wine(1));
        assertEquals(COMPLETE,f.operation.acknowledge(f.restored(EMPTY)),"The exact ordinary inverse remains independently valid");
    }

    @Test void wrongPickupCountIdentityOrLimitRejectsAtomically() {
        Fixture f=new Fixture(); f.merged();
        for(var changed:List.of(EMPTY,wine(2),new InventoryConsolidation.Stack("wine{wrong_quality}",1,64),
                new InventoryConsolidation.Stack(wine(1).identity(),1,16)))
            f.waitUnchanged(f.restored(changed),wine(1));
        var tooLargeLimit=new InventoryConsolidation.Stack(wine(1).identity(),1,128);
        f.waitUnchanged(f.restored(tooLargeLimit),tooLargeLimit);
        f.waitUnchanged(f.restored(wine(1)),wine(2));
        assertEquals(COMPLETE,f.operation.acknowledgeRestorePickup(f.restored(wine(1)),wine(1)));
    }

    @Test void everyBorrowedStackPropertyAndBothSwapDestinationsRemainExact() {
        Fixture f=new Fixture(); f.merged();
        for(var changed:List.of(EMPTY,wine(1),new InventoryConsolidation.Stack(TORCH.identity(),61,64),
                new InventoryConsolidation.Stack(TORCH.identity(),63,64),new InventoryConsolidation.Stack("torch{changed}",62,64),
                new InventoryConsolidation.Stack(TORCH.identity(),62,128))) {
            var wrong=new ArrayList<>(f.restored(wine(1)).items()); wrong.set(6,changed);
            f.waitUnchanged(snapshot(wrong),wine(1));
        }
        var notSwapped=new ArrayList<>(f.items); notSwapped.set(6,wine(1));
        f.waitUnchanged(snapshot(notSwapped),wine(1));
        assertEquals(COMPLETE,f.operation.acknowledgeRestorePickup(f.restored(wine(1)),wine(1)));
    }

    @Test void anyUnprovenOtherSlotChangeIncludingAnotherProductionPickupRejectsTheWholeReply() {
        Fixture f=new Fixture(); f.merged();
        for(int slot:new int[]{0,4,5,9,11,12,15,35}) {
            var wrong=new ArrayList<>(f.restored(wine(1)).items()); wrong.set(slot,wine(1));
            f.waitUnchanged(snapshot(wrong),wine(1));
        }
        assertEquals(COMPLETE,f.operation.acknowledgeRestorePickup(f.restored(wine(1)),wine(1)));
    }

    @Test void bothEarlierAcknowledgementsAndABorrowedEmptyScratchAreMandatory() {
        Fixture beforeSwap=new Fixture(); beforeSwap.waitUnchanged(beforeSwap.restored(wine(1)),wine(1));
        Fixture beforeMerge=new Fixture(); beforeMerge.swapOut(); beforeMerge.waitUnchanged(beforeMerge.restored(wine(1)),wine(1));
        Fixture partial=new Fixture(2,63,true); partial.swapOut(); partial.items.set(6,wine(1));partial.items.set(12,wine(64));
        assertEquals(NEXT,partial.operation.acknowledge(snapshot(partial.items)));
        partial.waitUnchanged(partial.restored(wine(1)),wine(1));
        assertEquals(COMPLETE,partial.operation.acknowledge(partial.restored(wine(1))),"A nonempty merged remainder still uses the ordinary exact inverse");

        Fixture noBorrow=new Fixture(1,33,false); noBorrow.merged(); assertTrue(noBorrow.operation.complete());
        assertEquals(WAIT,noBorrow.operation.acknowledgeRestorePickup(noBorrow.restored(wine(1)),wine(1)));
        var direct=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,10,-1,List.of(6),beforeSwap.visible),snapshot(beforeSwap.items));
        assertEquals(WAIT,direct.acknowledgeRestorePickup(beforeSwap.restored(wine(1)),wine(1)));
    }

    @Test void aPickupAlreadyRebasedBeforeDispatchCannotBeCountedAgainDuringTheAck() {
        Fixture f=new Fixture(); f.merged(); f.items.set(6,wine(1));
        assertTrue(f.operation.rebaseVerifiedUpdates(snapshot(f.items),Set.of(6),Set.of(6)));
        var after=f.restored(wine(1)); f.waitUnchanged(after,wine(1));
        assertEquals(COMPLETE,f.operation.acknowledge(after));
        assertEquals(WAIT,f.operation.acknowledgeRestorePickup(after,wine(1)),"Completion does not permit a second acknowledgement");
    }

    private static final class Fixture {
        final List<InventoryConsolidation.Stack> items=new ArrayList<>(Collections.nCopies(36,EMPTY));
        final List<ItemData> visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        final InventoryConsolidation operation; final boolean borrowed;
        Fixture(){this(1,33,true);}
        Fixture(int source,int receiver,boolean borrowed) {
            this.borrowed=borrowed; items.set(4,HOE); items.set(10,wine(source));items.set(12,wine(receiver));
            items.set(15,new InventoryConsolidation.Stack("unrelated_food{exact}",3,64));
            if(borrowed){items.set(6,TORCH);visible.set(6,new ItemData("minecraft:torch",62,0,null,false,0));}
            visible.set(10,new ItemData(ItemData.WINE,source,0,null,false,0));
            visible.set(12,new ItemData(ItemData.WINE,receiver,0,null,false,0));
            operation=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,10,6,List.of(12),visible),snapshot(items));
        }
        void swapOut(){Collections.swap(items,10,6);assertEquals(NEXT,operation.acknowledge(snapshot(items)));}
        void merged(){swapOut();items.set(6,EMPTY);items.set(12,wine(34));assertEquals(borrowed?NEXT:COMPLETE,operation.acknowledge(snapshot(items)));}
        InventoryConsolidation.Snapshot restored(InventoryConsolidation.Stack pickup){var result=new ArrayList<>(items);result.set(10,pickup);result.set(6,TORCH);return snapshot(result);}
        void waitUnchanged(InventoryConsolidation.Snapshot after,InventoryConsolidation.Stack pickup) {
            var before=operation.expectedLive();var click=operation.click();boolean owed=operation.requiresRestoration();int freed=operation.freedSlots();
            assertEquals(WAIT,operation.acknowledgeRestorePickup(after,pickup));
            assertSame(before,operation.expectedLive());assertEquals(click,operation.click());assertEquals(owed,operation.requiresRestoration());
            assertEquals(freed,operation.freedSlots());assertFalse(operation.complete());
        }
    }
}
