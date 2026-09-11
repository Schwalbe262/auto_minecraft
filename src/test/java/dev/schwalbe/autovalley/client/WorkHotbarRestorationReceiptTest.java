package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkHotbarRestorationReceiptTest {
    private static final String HASH="a".repeat(64),OTHER="b".repeat(64);
    private static final ItemData ORIGINAL=new ItemData("minecraft:torch",17,0,null,false,999);
    private static final HotbarLease LEASE=new HotbarLease(Feature.CRYSTAL_COPY,9,1,ORIGINAL,HASH);
    private static LoggingRestorationReceipt.Endpoint endpoint(ItemData item,String hash){return new LoggingRestorationReceipt.Endpoint(item,hash);}
    private static final LoggingRestorationReceipt.Endpoint ORIGINAL_ENDPOINT=endpoint(ORIGINAL,HASH),EMPTY=endpoint(ItemData.EMPTY,"ignored");
    private static LoggingRestorationReceipt.Endpoint working(String id){return endpoint(new ItemData(id,3,0,null,false,999),OTHER);}
    private static boolean restored(LoggingRestorationReceipt.Endpoint source,LoggingRestorationReceipt.Endpoint hotbar) {
        return WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,101,true,source,hotbar,source,hotbar);
    }
    private static boolean parked(LoggingRestorationReceipt.Endpoint source,LoggingRestorationReceipt.Endpoint hotbar) {
        return WorkHotbarRestorationReceipt.provesParked(LEASE,2,2,100,101,true,source,hotbar,source,hotbar);
    }
    private static WorkHotbarRestorationReceipt.SlotUpdate slot(long seq,int menu,int index,LoggingRestorationReceipt.Endpoint item) {
        return new WorkHotbarRestorationReceipt.SlotUpdate(seq,menu,index,item);
    }
    private static boolean current(long fullSequence,LoggingRestorationReceipt.Endpoint fullSource,
            LoggingRestorationReceipt.Endpoint fullHotbar,LoggingRestorationReceipt.Endpoint liveSource,
            LoggingRestorationReceipt.Endpoint liveHotbar,List<WorkHotbarRestorationReceipt.SlotUpdate> updates,boolean restored) {
        return WorkHotbarRestorationReceipt.provesCurrent(LEASE,2,2,100,fullSequence,46,true,
            liveSource,liveHotbar,fullSource,fullHotbar,updates,restored);
    }
    @Test void restoredSourceMayContainTheActualWorkingInputOutputOrBeEmpty() {
        for(var source:List.of(EMPTY,working("society:jade"),working("society:ancient_fruit_seed"),working(FruitRules.ITEM)))
            assertTrue(restored(source,ORIGINAL_ENDPOINT));
    }
    @Test void parkedOriginalMustBeAtSourceAndTemporaryHotbarMayBeEmptyOrWorking() {
        for(var held:List.of(EMPTY,working("society:jade")))assertTrue(parked(ORIGINAL_ENDPOINT,held));
        assertFalse(restored(ORIGINAL_ENDPOINT,EMPTY));assertFalse(parked(EMPTY,ORIGINAL_ENDPOINT));
    }
    @Test void bothProofDirectionsRequireBothLiveEndpointsToMatchTheSameRawFull() {
        var jade=working("society:jade");
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,101,true,jade,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,101,true,jade,ORIGINAL_ENDPOINT,jade,EMPTY));
        assertFalse(WorkHotbarRestorationReceipt.provesParked(LEASE,2,2,100,101,true,ORIGINAL_ENDPOINT,jade,ORIGINAL_ENDPOINT,EMPTY));
        assertFalse(WorkHotbarRestorationReceipt.provesParked(LEASE,2,2,100,101,true,ORIGINAL_ENDPOINT,jade,EMPTY,jade));
    }
    @Test void originalItemCountQualityAndFingerprintCannotBeReplacedByAnotherSimilarStack() {
        for(var wrong:List.of(endpoint(new ItemData(ORIGINAL.id(),16,0,null,false,999),HASH),
                endpoint(new ItemData(ORIGINAL.id(),17,1,null,false,999),HASH),endpoint(ORIGINAL,OTHER),working("minecraft:diamond"))) {
            assertFalse(restored(EMPTY,wrong));assertFalse(parked(wrong,EMPTY));
        }
    }
    @Test void sameOriginalItemAtBothEndpointsIsAmbiguousEvenWithDifferentCountsOrTags() {
        for(var duplicate:List.of(ORIGINAL_ENDPOINT,endpoint(new ItemData(ORIGINAL.id(),1,0,null,false,999),OTHER))) {
            assertFalse(restored(duplicate,ORIGINAL_ENDPOINT));assertFalse(parked(ORIGINAL_ENDPOINT,duplicate));
        }
    }
    @Test void stalePreDispatchOrManualInputSequenceAndMissingEmptyCursorCannotProveCustody() {
        for(long sequence:List.of(-1L,0L,99L,100L))
            assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,sequence,true,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,101,false,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,101,101,true,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
    }
    @Test void newConnectionStillRequiresActualNewFullButDoesNotBorrowTheOldSequence() {
        assertTrue(WorkHotbarRestorationReceipt.proves(LEASE,3,2,100,1,true,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,3,2,100,0,true,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
    }
    @Test void missingNativeEndpointAndInvalidLeaseNeverCreateProof() {
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,101,true,null,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
        assertFalse(WorkHotbarRestorationReceipt.proves(LEASE,2,2,100,101,true,EMPTY,null,EMPTY,ORIGINAL_ENDPOINT));
        assertFalse(WorkHotbarRestorationReceipt.proves(new HotbarLease(Feature.WINE,9,1,ORIGINAL,HASH),2,2,100,101,true,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
    }
    @Test void everyDurableStageCanBeObservedWithoutInventingItsHistoricalActionOutcome() {
        for(HotbarLease.Stage stage:HotbarLease.Stage.values()) {
            HotbarLease lease=LEASE.withStage(stage);
            assertTrue(WorkHotbarRestorationReceipt.proves(lease,2,2,100,101,true,EMPTY,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT));
            assertTrue(WorkHotbarRestorationReceipt.provesParked(lease,2,2,100,101,true,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,EMPTY));
            assertEquals(stage,lease.stage());
        }
    }

    @Test void laterRawPickupCanProveCurrentParkedCustodyWithoutBecomingAFullOrHistoricalSwapAck() {
        var ruby=working("society:ruby");
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,List.of(),false));
        assertTrue(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,List.of(slot(102,0,37,ruby)),false));
        // The strict original FULL-only predicate still rejects this stale view.
        assertFalse(WorkHotbarRestorationReceipt.provesParked(LEASE,2,2,100,101,true,
            ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,EMPTY));
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,List.of(slot(102,0,37,ruby)),true));
    }

    @Test void rawUpdatesCanProveRestoredCustodyButEveryChangedEndpointNeedsItsOwnLatestPacket() {
        var ruby=working("society:ruby");
        assertTrue(current(101,EMPTY,ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,List.of(slot(102,0,9,ruby)),true));
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ruby,ORIGINAL_ENDPOINT,List.of(slot(102,0,9,ruby)),true));
        assertTrue(current(101,ORIGINAL_ENDPOINT,EMPTY,ruby,ORIGINAL_ENDPOINT,
            List.of(slot(102,0,9,ruby),slot(103,0,37,ORIGINAL_ENDPOINT)),true));
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ruby,ORIGINAL_ENDPOINT,
            List.of(slot(102,0,9,ruby),slot(103,0,37,EMPTY)),true));
    }

    @Test void newestEndpointUpdatesWinAndCannotBeReplacedByOlderMatchingEvidence() {
        var ruby=working("society:ruby");var jade=working("society:jade");
        var updates=List.of(slot(104,0,37,jade),slot(102,0,37,ruby),slot(103,0,12,ruby));
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,updates,false));
        assertTrue(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,jade,updates,false));
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,jade,
            List.of(slot(104,0,37,jade),slot(105,0,9,EMPTY)),false));
    }

    @Test void aNewerFullSupersedesAllOlderSlotEvidenceEvenWhenTheOldViewWouldMatchLive() {
        var ruby=working("society:ruby");var jade=working("society:jade");
        assertFalse(current(105,ORIGINAL_ENDPOINT,jade,ORIGINAL_ENDPOINT,ruby,List.of(slot(104,0,37,ruby)),false));
        assertFalse(current(105,EMPTY,ruby,ORIGINAL_ENDPOINT,ruby,List.of(slot(104,0,9,ORIGINAL_ENDPOINT)),false));
        assertTrue(current(105,ORIGINAL_ENDPOINT,jade,ORIGINAL_ENDPOINT,ruby,List.of(slot(106,0,37,ruby)),false));
        assertFalse(current(105,ORIGINAL_ENDPOINT,jade,ORIGINAL_ENDPOINT,ruby,List.of(slot(105,0,37,ruby)),false));
    }

    @Test void inventoryIndexPacketsAndOtherMenusCannotBeRelabelledAsInventoryMenuEndpoints() {
        var ruby=working("society:ruby");
        for(var wrong:List.of(slot(102,-2,1,ruby),slot(102,-2,37,ruby),slot(102,73,37,ruby),
                slot(102,-1,-1,ruby),slot(102,0,1,ruby),slot(102,0,36,ruby),slot(100,0,37,ruby)))
            assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,List.of(wrong),false));
        assertFalse(current(101,EMPTY,ruby,ORIGINAL_ENDPOINT,ruby,List.of(slot(102,-2,9,ORIGINAL_ENDPOINT)),false));
    }

    @Test void slotUpdatesNeverExcuseOriginalCountGrowthTagChangesOrDuplicateIdentity() {
        var ruby=working("society:ruby");
        for(var changed:List.of(endpoint(new ItemData(ORIGINAL.id(),18,0,null,false,999),OTHER),
                endpoint(new ItemData(ORIGINAL.id(),16,0,null,false,999),OTHER),endpoint(ORIGINAL,OTHER))) {
            assertFalse(current(101,ORIGINAL_ENDPOINT,ruby,changed,ruby,List.of(slot(102,0,9,changed)),false));
            assertFalse(current(101,ruby,ORIGINAL_ENDPOINT,ruby,changed,List.of(slot(102,0,37,changed)),true));
        }
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ORIGINAL_ENDPOINT,
            List.of(slot(102,0,37,ORIGINAL_ENDPOINT)),false));
        assertFalse(current(101,EMPTY,ORIGINAL_ENDPOINT,ORIGINAL_ENDPOINT,ORIGINAL_ENDPOINT,
            List.of(slot(102,0,9,ORIGINAL_ENDPOINT)),true));
    }

    @Test void rawSlotsCannotUpgradeStaleFullAcrossManualOrSwapBarrierOrSupplyMissingCursorAndShapeProof() {
        var ruby=working("society:ruby");var updates=List.of(slot(102,0,37,ruby));
        for(long full:List.of(-1L,0L,99L,100L))
            assertFalse(current(full,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,updates,false));
        assertFalse(WorkHotbarRestorationReceipt.provesCurrent(LEASE,2,2,101,101,46,true,
            ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,EMPTY,updates,false));
        assertFalse(WorkHotbarRestorationReceipt.provesCurrent(LEASE,2,2,100,101,46,false,
            ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,EMPTY,updates,false));
        for(int size:List.of(0,36,45,47))
            assertFalse(WorkHotbarRestorationReceipt.provesCurrent(LEASE,2,2,100,101,size,true,
                ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,EMPTY,updates,false));
        assertFalse(current(101,ORIGINAL_ENDPOINT,null,ORIGINAL_ENDPOINT,ruby,updates,false));
        assertFalse(current(101,ORIGINAL_ENDPOINT,EMPTY,ORIGINAL_ENDPOINT,ruby,List.of(slot(102,0,37,null)),false));
    }

    @Test void newConnectionNeedsItsOwnFullBeforeItsOwnRawSlotEvidenceCanProveCustody() {
        var ruby=working("society:ruby");
        assertTrue(WorkHotbarRestorationReceipt.provesCurrent(LEASE,3,2,100,1,46,true,
            ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,EMPTY,List.of(slot(2,0,37,ruby)),false));
        assertFalse(WorkHotbarRestorationReceipt.provesCurrent(LEASE,3,2,100,0,46,true,
            ORIGINAL_ENDPOINT,ruby,ORIGINAL_ENDPOINT,EMPTY,List.of(slot(2,0,37,ruby)),false));
    }
}
