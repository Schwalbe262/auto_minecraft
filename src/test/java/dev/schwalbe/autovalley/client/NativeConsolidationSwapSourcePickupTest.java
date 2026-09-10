package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** Full-server-reply projection of an exact outward SWAP followed by a distinct source-slot pickup. */
class NativeConsolidationSwapSourcePickupTest {
    private static Stack item(String id,int count) {return new Stack("{Count:1b,id:\""+id+"\"}",count,64);}
    private static Stack tomato(int count){return item(ItemData.TOMATO,count);}
    private static Stack wine(int count){return item(ItemData.WINE,count);}
    private static final class Fixture {
        final List<Stack> items=new ArrayList<>(Collections.nCopies(36,Stack.EMPTY));
        final InventoryConsolidation tx;
        Fixture(boolean borrowed) {
            items.set(19,tomato(3));items.set(20,tomato(50));
            List<ItemData> visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
            if(borrowed){items.set(0,item("minecraft:apple",1));visible.set(0,new ItemData("minecraft:apple",1,0,null,false,99));}
            tx=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.TOMATO,19,0,List.of(20),visible),new Snapshot(items));
        }
        void reply(){Collections.swap(items,19,0);items.set(19,wine(1));}
        Set<Integer> classify(){return NativeInventoryConsolidation.concurrentProductionAdditions(tx,new Snapshot(items),4);}
        Confirmation acknowledge(){return tx.acknowledge(new Snapshot(items),Set.of(),classify());}
    }
    @Test void exactThreeTomatoSwapAndWineSourcePickupCanAdvanceOnlyTheUnsentMerge() {
        Fixture f=new Fixture(false);f.reply();
        assertEquals(Set.of(19),f.classify());assertEquals(NEXT,f.acknowledge());
        assertEquals(new Click(Type.QUICK_MOVE,0,-1),f.tx.click());assertFalse(f.tx.requiresRestoration());
        assertEquals(wine(1),f.tx.expectedLive().items().get(19));assertEquals(tomato(3),f.tx.expectedLive().items().get(0));
        f.items.set(0,Stack.EMPTY);f.items.set(20,tomato(53));
        assertEquals(COMPLETE,f.acknowledge());assertEquals(wine(1),f.tx.expectedLive().items().get(19));
    }
    @Test void unchangedLegacyOverloadCannotInferAProductionPickup() {
        Fixture f=new Fixture(false);f.reply();Snapshot before=f.tx.expectedLive();
        assertEquals(WAIT,f.tx.acknowledge(new Snapshot(f.items)));
        assertEquals(before,f.tx.expectedLive());assertEquals(new Click(Type.SWAP,19,0),f.tx.click());
    }
    @Test void missingPartialOrExtraMovedStackNeverPassesEvenWithAWhitelistedPickup() {
        for(int count:new int[]{0,1,2,4,64}) {
            Fixture f=new Fixture(false);f.reply();f.items.set(0,count==0?Stack.EMPTY:tomato(count));Snapshot before=f.tx.expectedLive();
            assertEquals(WAIT,f.acknowledge(),"Wrong transferred count "+count);assertEquals(before,f.tx.expectedLive());
        }
    }
    @Test void wrongMovingIdentityInScratchCannotBeHiddenByTheSourceException() {
        Fixture f=new Fixture(false);f.reply();f.items.set(0,wine(3));assertEquals(WAIT,f.acknowledge());
    }
    @Test void sourcePickupCannotBeAnExtraCopyOfTheMovingIdentity() {
        Fixture f=new Fixture(false);f.reply();f.items.set(19,tomato(1));
        assertTrue(f.classify().isEmpty());assertEquals(WAIT,f.tx.acknowledge(new Snapshot(f.items),Set.of(),Set.of(19)));
    }
    @Test void borrowedScratchItemCannotBeReplacedByAWhitelistedProduct() {
        Fixture f=new Fixture(true);f.reply();
        assertTrue(f.classify().isEmpty());assertFalse(f.tx.allowsPostSwapSourceAddition(19,wine(1)));
        assertEquals(WAIT,f.tx.acknowledge(new Snapshot(f.items),Set.of(),Set.of(19)));
    }
    @Test void arbitraryItemsAndProtectedSlotsCannotGetNativePickupAuthority() {
        Fixture f=new Fixture(false);f.reply();f.items.set(19,item("minecraft:torch",1));
        assertTrue(f.classify().isEmpty());assertEquals(WAIT,f.acknowledge());
        f.items.set(19,wine(1));
        assertTrue(NativeInventoryConsolidation.concurrentProductionAdditions(f.tx,new Snapshot(f.items),19).isEmpty());
    }
    @Test void actualCrystalOutputsCannotHideAMissingSwapOrReplaceTheBorrowedScratchOriginal() {
        for(String id:CrystalCollection.OUTPUT_IDS) {
            Stack output=item(id,2);
            Fixture exact=new Fixture(false);exact.reply();exact.items.set(19,output);
            assertEquals(Set.of(19),exact.classify(),id);assertEquals(NEXT,exact.acknowledge(),id);
            assertEquals(output,exact.tx.expectedLive().items().get(19),id);
            Fixture missing=new Fixture(false);missing.items.set(19,output);
            assertEquals(WAIT,missing.acknowledge(),"Output recognition cannot supply a missing outward SWAP: "+id);
            Fixture borrowed=new Fixture(true);borrowed.reply();borrowed.items.set(19,output);
            assertTrue(borrowed.classify().isEmpty(),id);assertEquals(WAIT,borrowed.acknowledge(),id);
            Fixture protectedSlot=new Fixture(false);protectedSlot.reply();protectedSlot.items.set(19,output);
            assertTrue(NativeInventoryConsolidation.concurrentProductionAdditions(protectedSlot.tx,new Snapshot(protectedSlot.items),19).isEmpty(),id);
        }
    }
    @Test void otherUnexpectedChangesMustStillBeRejected() {
        Fixture f=new Fixture(false);f.reply();f.items.set(20,tomato(49));
        assertEquals(Set.of(19),f.classify());assertEquals(WAIT,f.acknowledge());
    }
    @Test void onlyTheEmptiedSourceOfTheFirstSwapIsEligible() {
        Fixture f=new Fixture(false);
        for(int index:new int[]{-1,0,4,18,20,36})assertFalse(f.tx.allowsPostSwapSourceAddition(index,wine(1)));
        assertFalse(f.tx.allowsPostSwapSourceAddition(19,null));assertFalse(f.tx.allowsPostSwapSourceAddition(19,Stack.EMPTY));
        f.reply();assertEquals(NEXT,f.acknowledge());
        assertFalse(f.tx.allowsPostSwapSourceAddition(19,wine(2)));assertEquals(WAIT,f.acknowledge(),"The same reply cannot prove the next primitive");
    }
    @Test void passiveMetadataGrantCannotBeUsedForThisSourceReplacement() {
        Fixture f=new Fixture(false);f.reply();
        assertEquals(WAIT,f.tx.acknowledge(new Snapshot(f.items),Set.of(19),Set.of()));
        assertEquals(WAIT,f.tx.acknowledge(new Snapshot(f.items),Set.of(19),Set.of(19)));
    }
    @Test void subsequentMergeCannotDeleteOrMoveTheObservedWine() {
        for(boolean moved:List.of(false,true)) {
            Fixture f=new Fixture(false);f.reply();assertEquals(NEXT,f.acknowledge());
            Snapshot before=f.tx.expectedLive();f.items.set(0,Stack.EMPTY);f.items.set(20,tomato(53));
            f.items.set(19,Stack.EMPTY);if(moved)f.items.set(21,wine(1));
            assertEquals(WAIT,f.acknowledge());assertEquals(before,f.tx.expectedLive());
        }
    }
    @Test void pickupWithoutTheSwapCannotAuthorizeTheNextPrimitive() {
        Fixture f=new Fixture(false);Snapshot before=f.tx.expectedLive();
        f.items.set(19,wine(1));assertEquals(Set.of(19),f.classify());
        assertEquals(WAIT,f.acknowledge());assertEquals(before,f.tx.expectedLive());
    }
}
