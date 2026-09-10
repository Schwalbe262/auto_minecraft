package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached native identities; only confirmed(ServerObservations) supplies real FULL provenance. */
class NativeLoggingSwapPickupTest {
    private static final int SOURCE=12,DESTINATION=36;
    private static Stack item(String id,int count,int limit,String tag) {
        return new Stack("{id:\""+id+"\",Count:1b,tag:"+tag+"}",count,limit);
    }
    private static Stack saplings(int count) { return item("minecraft:spruce_sapling",count,64,"{}"); }
    private static Stack sword() { return item("society:galaxy_sword",1,1,"{Damage:7,custom:1}"); }
    private static final class Fixture {
        final List<Stack> before=new ArrayList<>(Collections.nCopies(46,Stack.EMPTY));
        final List<Stack> after;
        Fixture() {
            before.set(SOURCE,saplings(11));before.set(DESTINATION,sword());
            before.set(20,item("twigs:twig",11,64,"{}"));
            before.set(21,item("minecraft:spruce_log",18,64,"{}"));
            after=new ArrayList<>(before);Collections.swap(after,SOURCE,DESTINATION);
            after.set(DESTINATION,saplings(13));
            after.set(20,item("twigs:twig",12,64,"{}"));
            after.set(21,item("minecraft:spruce_log",47,64,"{}"));
        }
        boolean proof() { return NativeLoggingSwap.swappedWithPickup(before,after,SOURCE,DESTINATION); }
    }

    @Test void exactBorrowedSwordRelocationAndSaplingGrowthAcceptConcurrentLogAndTwigPickups() {
        Fixture f=new Fixture();var baseline=List.copyOf(f.before);var received=List.copyOf(f.after);
        assertFalse(NativeLoggingSwap.swapped(f.before,f.after,SOURCE,DESTINATION));
        assertTrue(f.proof());
        assertEquals(f.before.get(DESTINATION),f.after.get(SOURCE));
        assertEquals(baseline,f.before);assertEquals(received,f.after);
    }
    @Test void restorationMayGrowSaplingsAtTheOppositeEndpointWhileSwordReturnsExactly() {
        Fixture f=new Fixture();Collections.swap(f.before,SOURCE,DESTINATION);
        f.after.set(SOURCE,saplings(13));f.after.set(DESTINATION,sword());
        assertTrue(f.proof());
    }
    @Test void emptyPartnerMustBeExactlyVacatedAndMovedStackCanGrowToItsNativeLimit() {
        Fixture f=new Fixture();f.before.set(DESTINATION,Stack.EMPTY);f.after.set(SOURCE,Stack.EMPTY);
        f.after.set(DESTINATION,saplings(64));assertTrue(f.proof());
        f.after.set(SOURCE,item("minecraft:stone",1,64,"{}"));assertFalse(f.proof());
    }
    @Test void ordinaryIngredientUsesTheSameIdentityProofWithoutGrantingQualityChanges() {
        Fixture f=new Fixture();
        f.before.set(SOURCE,item("farm_and_charm:tomato",3,64,"{quality_food:{quality:2}}"));
        f.after.set(DESTINATION,item("farm_and_charm:tomato",5,64,"{quality_food:{quality:2}}"));
        assertTrue(f.proof());
        f.after.set(DESTINATION,item("farm_and_charm:tomato",5,64,"{quality_food:{quality:1}}"));
        assertFalse(f.proof());
    }
    @Test void exactSwapsContinueThroughLegacyPredicateWithoutNeedingPickupException() {
        Fixture f=new Fixture();f.after.set(DESTINATION,saplings(11));
        assertTrue(NativeLoggingSwap.swapped(f.before,f.after,SOURCE,DESTINATION));
        assertFalse(f.proof());
    }
    @Test void noOpAndUnmovedSwordCannotBecomeASwapReceiptFromAmbientPickups() {
        Fixture f=new Fixture();f.after.set(SOURCE,saplings(13));f.after.set(DESTINATION,sword());
        assertFalse(f.proof());
        assertFalse(NativeLoggingSwap.swappedWithPickup(f.before,f.before,SOURCE,DESTINATION));
    }
    @Test void sameIdentityEndpointsWithDifferentCountsRemainAmbiguousEvenIfCountsFitASwap() {
        Fixture f=new Fixture();f.before.set(DESTINATION,saplings(2));
        f.after.set(SOURCE,saplings(2));f.after.set(DESTINATION,saplings(13));
        assertFalse(f.proof());
        f.before.set(DESTINATION,saplings(11));f.after.set(SOURCE,saplings(11));assertFalse(f.proof());
    }
    @Test void changedGrowingPartnerItemTagsLimitDecreasedCountOrDeletionCannotQualify() {
        for(Stack changed:List.of(item("minecraft:oak_sapling",13,64,"{}"),
                item("minecraft:spruce_sapling",13,64,"{custom:1}"),
                item("minecraft:spruce_sapling",13,16,"{}"),saplings(10),Stack.EMPTY)) {
            Fixture f=new Fixture();f.after.set(DESTINATION,changed);assertFalse(f.proof());
        }
    }
    @Test void displacedItemRetainsItsEntireFingerprintLimitAndCount() {
        for(Stack changed:List.of(item("society:galaxy_sword",1,1,"{Damage:8,custom:1}"),
                item("society:galaxy_sword",1,1,"{Damage:7,custom:2}"),
                item("society:galaxy_sword",1,64,"{Damage:7,custom:1}"),Stack.EMPTY)) {
            Fixture f=new Fixture();f.after.set(SOURCE,changed);assertFalse(f.proof());
        }
        Fixture f=new Fixture();
        Stack original=item("minecraft:torch",4,64,"{}");f.before.set(DESTINATION,original);
        f.after.set(SOURCE,item("minecraft:torch",3,64,"{}"));assertFalse(f.proof());
    }
    @Test void simultaneousGrowthOfBothDistinctPartnersCannotReplaceExactRelocationProof() {
        Fixture f=new Fixture();f.before.set(DESTINATION,item("minecraft:torch",4,64,"{}"));
        f.after.set(SOURCE,item("minecraft:torch",5,64,"{}"));
        assertFalse(f.proof(),"Both partners growing is not exact relocation");
    }
    @Test void unrelatedSlotChangesCannotInvalidateOrBecomePartOfTheProvenEndpointExchange() {
        for(Stack changed:List.of(item("twigs:twig",10,64,"{}"),Stack.EMPTY,
                item("minecraft:stick",12,64,"{}"),item("twigs:twig",12,64,"{custom:1}"),
                item("twigs:twig",12,16,"{}"))) {
            Fixture f=new Fixture();f.after.set(20,changed);assertTrue(f.proof());
        }
        Fixture f=new Fixture();f.after.set(22,saplings(1));assertTrue(f.proof());
        f.after.set(SOURCE,Stack.EMPTY);assertFalse(f.proof(),"Unrelated changes never substitute for either endpoint");
    }
    @Test void unrelatedCraftingArmorAndOffhandChangesDoNotChangeTheEndpointReceipt() {
        for(int slot:new int[]{0,1,4,5,8,45}) {
            Fixture f=new Fixture();f.before.set(slot,saplings(1));f.after.set(slot,saplings(2));
            assertTrue(f.proof(),"slot="+slot);
        }
    }
    @Test void higherThanVanillaStackLimitsCannotQualifyForEitherParticipant() {
        Fixture f=new Fixture();
        f.before.set(SOURCE,item("minecraft:spruce_sapling",11,128,"{}"));
        f.after.set(DESTINATION,item("minecraft:spruce_sapling",13,128,"{}"));assertFalse(f.proof());
        f=new Fixture();Stack oversized=item("society:galaxy_sword",1,128,"{Damage:7,custom:1}");
        f.before.set(DESTINATION,oversized);f.after.set(SOURCE,oversized);assertFalse(f.proof());
    }
    @Test void malformedMenuShapeNullSlotsAndNonInventoryOrDuplicateEndpointsAreRejected() {
        Fixture f=new Fixture();
        assertFalse(NativeLoggingSwap.swappedWithPickup(null,f.after,SOURCE,DESTINATION));
        assertFalse(NativeLoggingSwap.swappedWithPickup(f.before,null,SOURCE,DESTINATION));
        assertFalse(NativeLoggingSwap.swappedWithPickup(f.before.subList(0,45),f.after,SOURCE,DESTINATION));
        assertFalse(NativeLoggingSwap.swappedWithPickup(f.before,f.after.subList(0,45),SOURCE,DESTINATION));
        for(int[] indices:new int[][]{{-1,36},{0,36},{8,36},{45,36},{12,35},{12,45},{36,36}})
            assertFalse(NativeLoggingSwap.swappedWithPickup(f.before,f.after,indices[0],indices[1]));
        f.after.set(22,null);assertFalse(f.proof());f.after.set(22,Stack.EMPTY);
        f.before.set(22,null);assertFalse(f.proof());
    }
    @Test void confirmationSurvivesBoundedHistoryEvictionAndDoesNotReevaluateLaterChangedInventory() {
        Fixture f=new Fixture();var latch=new NativeLoggingSwap.ReceiptLatch(5,100);
        assertTrue(latch.confirmed(5,() -> f.proof() ? 101 : -1));
        f.after.set(DESTINATION,saplings(9));
        assertTrue(latch.confirmed(5,() -> { throw new AssertionError("Completed receipt must outlive packet eviction"); }));
    }
    @Test void absentInvalidAndPreDispatchEvidenceNeverLatchAndLegitimateLateReplyCanStillComplete() {
        Fixture f=new Fixture();var latch=new NativeLoggingSwap.ReceiptLatch(5,100);
        assertFalse(latch.confirmed(5,() -> -1));
        assertFalse(latch.confirmed(5,() -> f.proof() ? 100 : -1));
        f.after.set(SOURCE,Stack.EMPTY);assertFalse(latch.confirmed(5,() -> f.proof() ? 101 : -1));
        f.after.set(SOURCE,sword());assertTrue(latch.confirmed(5,() -> f.proof() ? 102 : -1));
    }
    @Test void connectionChangeRejectsBothUnconfirmedAndPreviouslyLatchedReceiptsWithoutReadingHistory() {
        var fresh=new NativeLoggingSwap.ReceiptLatch(5,100);
        assertFalse(fresh.confirmed(6,() -> { throw new AssertionError("Wrong connection"); }));
        assertTrue(fresh.confirmed(5,() -> 101));
        assertFalse(fresh.confirmed(6,() -> { throw new AssertionError("Old completion cannot cross connection"); }));
    }
}
