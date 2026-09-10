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
}
