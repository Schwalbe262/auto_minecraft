package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActionOutcomeProofTest {
    @Test void existingConstructorsCannotInventAdvancedArtisanEvidence() {
        for(ActionOutcome.State state:ActionOutcome.State.values()) {
            assertEquals(ActionOutcome.Proof.NONE,new ActionOutcome(state,"existing").proof());
            ActionOutcome outcome=new ActionOutcome(state,"quantity",7);
            assertEquals(7,outcome.confirmedCount());assertEquals(ActionOutcome.Proof.NONE,outcome.proof());
        }
    }
    @Test void onlyAnExplicitSuccessfulTicketCanCarryAdvancedEvidence() {
        for(ActionOutcome.State state:ActionOutcome.State.values()) {
            if(state==ActionOutcome.State.SUCCEEDED)continue;
            assertThrows(IllegalArgumentException.class,
                () -> new ActionOutcome(state,"not confirmed",0,ActionOutcome.Proof.ARTISAN_CYCLE_ADVANCED));
        }
        var outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native evidence",0,ActionOutcome.Proof.ARTISAN_CYCLE_ADVANCED);
        assertTrue(outcome.success());assertTrue(outcome.done());assertEquals(0,outcome.confirmedCount());
        assertEquals(ActionOutcome.Proof.ARTISAN_CYCLE_ADVANCED,outcome.proof());
    }
    @Test void anAbsentOptionalProofIsNotCompletionEvidence() {
        assertEquals(ActionOutcome.Proof.NONE,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"old",0,null).proof());
    }
}
