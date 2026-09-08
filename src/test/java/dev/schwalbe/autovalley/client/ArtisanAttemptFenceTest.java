package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArtisanAttemptFenceTest {
    private static final Pos TARGET=new Pos(1,64,0),OTHER=new Pos(2,64,0);
    @Test void cancelledOrTimedOutSentUsePreventsEverySameTargetResend() {
        var fence=new ArtisanAttemptFence<String>(128);int sends=0;
        if(!fence.blocked(TARGET,1,a->false) && fence.sent(TARGET,1,"attempt"))sends++;
        // Cancellation/timeout intentionally does not remove sent uncertainty.
        for(int i=0;i<600;i++)if(!fence.blocked(TARGET,1,a->false) && fence.sent(TARGET,1,"retry"))sends++;
        assertEquals(1,sends);assertEquals(1,fence.size());
    }
    @Test void anotherTargetIsNotStoppedByOneUnconfirmedMachine() {
        var fence=new ArtisanAttemptFence<String>(128);fence.sent(TARGET,1,"old");
        assertFalse(fence.blocked(OTHER,1,a->false));assertTrue(fence.sent(OTHER,1,"new"));
        assertTrue(fence.blocked(TARGET,1,a->false));
    }
    @Test void aFreshConfirmedReceiptAllowsNewWorkButDoesNotSendItItself() {
        var fence=new ArtisanAttemptFence<String>(128);fence.sent(TARGET,1,"old");
        assertFalse(fence.blocked(TARGET,1,"old"::equals));assertEquals(0,fence.size());
        assertTrue(fence.sent(TARGET,1,"new"));assertTrue(fence.blocked(TARGET,1,"old"::equals));
    }
    @Test void successfulCurrentAttemptCannotBeClearedByAnOldCompletion() {
        var fence=new ArtisanAttemptFence<Object>(128);Object old=new Object(),current=new Object();
        fence.sent(TARGET,1,old);fence.confirmed(TARGET,old);fence.sent(TARGET,1,current);
        fence.confirmed(TARGET,old);assertTrue(fence.blocked(TARGET,1,a->false));
        fence.confirmed(TARGET,current);assertEquals(0,fence.size());
    }
    @Test void newEpochReleasesTerminatedChannelWithoutCallingAnOldReceiptSuccessful() {
        var fence=new ArtisanAttemptFence<String>(128);fence.sent(TARGET,1,"old");int[] confirmations={0};
        assertFalse(fence.blocked(TARGET,2,a->{confirmations[0]++;return true;}));
        assertEquals(0,confirmations[0]);assertEquals(0,fence.size());assertTrue(fence.sent(TARGET,2,"new"));
    }
    @Test void boundedCapacityNeverEvictsAnUnresolvedTargetToPermitAnotherSend() {
        var fence=new ArtisanAttemptFence<String>(1);fence.sent(TARGET,1,"old");
        assertTrue(fence.blocked(OTHER,1,a->false));assertFalse(fence.sent(OTHER,1,"new"));
        assertTrue(fence.blocked(TARGET,1,a->false));assertEquals(1,fence.size());
    }
    @Test void fullCapacityCanReclaimOnlyActuallyAcknowledgedAttempts() {
        var fence=new ArtisanAttemptFence<String>(1);fence.sent(TARGET,1,"old");
        assertFalse(fence.blocked(OTHER,1,"old"::equals));assertTrue(fence.sent(OTHER,1,"new"));
    }
    @Test void malformedOrDuplicateAttemptsCannotReplaceTheOriginalEvidence() {
        var fence=new ArtisanAttemptFence<String>(1);
        assertFalse(fence.sent(null,1,"x"));assertFalse(fence.sent(TARGET,1,null));
        assertTrue(fence.sent(TARGET,1,"old"));assertFalse(fence.sent(TARGET,1,"replace"));
        assertTrue(fence.blocked(TARGET,1,"replace"::equals));
        assertThrows(IllegalArgumentException.class,()->new ArtisanAttemptFence<>(0));
    }
}
