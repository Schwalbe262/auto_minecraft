package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.core.InventoryConsolidation.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

class InventoryConsolidationRestoreMetadataTest {
    private static final Stack EMPTY=Stack.EMPTY,TORCH=new Stack("torch{exact}",13,64);
    private static Stack wine(int count,boolean refreshed){return new Stack("wine{year:15,cache:"+(refreshed?"new":"old")+"}",count,64);}
    private static Snapshot snapshot(List<Stack> items){return new Snapshot(items);}

    @Test void acknowledgedPickupBeforeRestoreThenProvenMetadataAndExactInverseCompletesWithoutRebasing() {
        for(int count:new int[]{1,64}) {
            Fixture f=new Fixture();f.ready(count);Snapshot before=f.operation.expectedLive(),after=f.inverse(wine(count,true));
            assertEquals(WAIT,f.operation.acknowledge(after));assertSame(before,f.operation.expectedLive());
            assertEquals(COMPLETE,f.operation.acknowledgeRestoreMetadata(after,wine(count,true)));
            assertSame(after,f.operation.expectedLive());assertEquals(TORCH,after.items().get(6));
            assertEquals(wine(count,true),after.items().get(9));assertEquals(wine(34,false),after.items().get(12));
            assertFalse(f.operation.requiresRestoration());assertEquals(0,f.operation.freedSlots());
            assertEquals(WAIT,f.operation.acknowledgeRestoreMetadata(after,wine(count,true)));
        }
    }
    @Test void noOpMetadataOnlyAndUnprovedOrOrdinaryInverseCannotUseTheNewProof() {
        Fixture f=new Fixture();f.ready(1);
        f.reject(f.operation.expectedLive(),wine(1,true));f.reject(null,wine(1,true));
        f.reject(f.inverse(wine(1,true)),null);f.reject(f.inverse(wine(1,true)),EMPTY);
        List<Stack> metadataOnly=new ArrayList<>(f.items);metadataOnly.set(6,wine(1,true));f.reject(snapshot(metadataOnly),wine(1,true));
        f.reject(f.inverse(wine(1,false)),wine(1,false));
        assertFalse(f.operation.acknowledgeCancelledRestoration(f.inverse(wine(1,true))));
        assertEquals(COMPLETE,f.operation.acknowledge(f.inverse(wine(1,false))),"Ordinary exact inverse is unchanged");
    }
    @Test void countLimitAndFinalIdentityMustEqualTheIndependentlyVerifiedMovedScratch() {
        Fixture f=new Fixture();f.ready(1);
        for(Stack proof:List.of(EMPTY,wine(2,true),new Stack(wine(1,true).identity(),1,16),new Stack(wine(1,true).identity(),1,128),
                new Stack("wine{wrong}",1,64)))f.reject(f.inverse(wine(1,true)),proof);
        for(Stack after:List.of(EMPTY,wine(2,true),new Stack(wine(1,true).identity(),1,16),new Stack("wine{wrong}",1,64)))
            f.reject(f.inverse(after),wine(1,true));
    }
    @Test void borrowedStackAndEveryOtherInventorySlotStayExact() {
        Fixture f=new Fixture();f.ready(1);
        for(Stack wrong:List.of(EMPTY,new Stack(TORCH.identity(),12,64),new Stack(TORCH.identity(),14,64),
                new Stack("torch{changed}",13,64),new Stack(TORCH.identity(),13,128),wine(1,true))) {
            List<Stack> after=new ArrayList<>(f.inverse(wine(1,true)).items());after.set(6,wrong);f.reject(snapshot(after),wine(1,true));
        }
        for(int slot=0;slot<36;slot++)if(slot!=9 && slot!=6) {
            List<Stack> after=new ArrayList<>(f.inverse(wine(1,true)).items());after.set(slot,wine(2,true));f.reject(snapshot(after),wine(1,true));
        }
    }
    @Test void outwardSwapUnsentMergeAndEmptyRestoreCannotBorrowTheMetadataAuthority() {
        Fixture outward=new Fixture();outward.reject(outward.inverse(wine(1,true)),wine(1,true));
        Fixture merge=new Fixture();merge.swapOut();merge.reject(merge.inverse(wine(1,true)),wine(1,true));
        Fixture empty=new Fixture();empty.merged();empty.reject(empty.inverse(wine(1,true)),wine(1,true));
        assertEquals(COMPLETE,empty.operation.acknowledge(empty.inverse(EMPTY)));
    }
    @Test void aPartialMergeRemainderCanBeRestoredButNeitherEarlierPrimitiveIsInferred() {
        Fixture f=new Fixture();f.items.set(9,wine(2,false));f.items.set(12,wine(63,false));f.recreate();f.swapOut();
        f.items.set(6,wine(1,false));f.items.set(12,wine(64,false));assertEquals(NEXT,f.operation.acknowledge(snapshot(f.items)));
        assertEquals(COMPLETE,f.operation.acknowledgeRestoreMetadata(f.inverse(wine(1,true)),wine(1,true)));
        assertEquals(TORCH,f.operation.expectedLive().items().get(6));assertEquals(wine(64,false),f.operation.expectedLive().items().get(12));
    }
    @Test void participantsThatBecomeIdenticalCannotClaimASwapFromMetadataAlone() {
        Fixture f=new Fixture();Stack borrowed=wine(1,true);f.items.set(6,borrowed);f.recreate();f.swapOut();
        f.items.set(6,EMPTY);f.items.set(12,wine(34,false));assertEquals(NEXT,f.operation.acknowledge(snapshot(f.items)));
        f.items.set(6,wine(1,false));assertTrue(f.operation.rebaseVerifiedUpdates(snapshot(f.items),Set.of(6),Set.of(6)));
        f.reject(f.inverse(borrowed),borrowed);
    }
    private static final class Fixture {
        final List<Stack> items=new ArrayList<>(Collections.nCopies(36,EMPTY));InventoryConsolidation operation;
        Fixture(){items.set(6,TORCH);items.set(9,wine(1,false));items.set(12,wine(33,false));recreate();}
        void recreate(){List<ItemData> visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
            visible.set(6,new ItemData("minecraft:torch",13,0,null,false,0));
            operation=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,6,List.of(12),visible),snapshot(items));}
        void swapOut(){Collections.swap(items,9,6);assertEquals(NEXT,operation.acknowledge(snapshot(items)));}
        void merged(){swapOut();items.set(6,EMPTY);items.set(12,wine(34,false));assertEquals(NEXT,operation.acknowledge(snapshot(items)));}
        void ready(int count){merged();items.set(6,wine(count,false));assertTrue(operation.rebaseVerifiedUpdates(snapshot(items),Set.of(6),Set.of(6)));}
        Snapshot inverse(Stack moved){List<Stack> result=new ArrayList<>(items);Collections.swap(result,9,6);result.set(9,moved);return snapshot(result);}
        void reject(Snapshot after,Stack proof){Snapshot before=operation.expectedLive();Click click=operation.click();boolean owed=operation.requiresRestoration();
            assertEquals(WAIT,operation.acknowledgeRestoreMetadata(after,proof));assertSame(before,operation.expectedLive());
            assertEquals(click,operation.click());assertEquals(owed,operation.requiresRestoration());assertFalse(operation.complete());}
    }
}
