package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConsolidationSkipOutcomeTest {
    @Test void optionalOutputMustBeExplicitAndDoesNotChangeThePlan() {
        var items=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        items.set(2,new ItemData(ItemData.WINE,1,0,20,false,999));
        items.set(9,new ItemData(ItemData.WINE,20,0,20,false,999));
        var plan=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,2,-1,List.of(9),items);
        assertFalse(new Action.ConsolidateInventory(plan).optionalOutput());
        assertFalse(new Action.ConsolidateInventory(plan,false).optionalOutput());
        var optional=new Action.ConsolidateInventory(plan,true);
        assertTrue(optional.optionalOutput()); assertSame(plan,optional.plan());
    }

    @Test void safeSkipIsTerminalButIsNotASuccessfulMergeAcknowledgement() {
        var outcome=new ActionOutcome(ActionOutcome.State.SKIPPED,"No further click was sent",0,
            ActionOutcome.Proof.CONSOLIDATION_SKIPPED_UNSENT);
        assertTrue(outcome.done()); assertFalse(outcome.success()); assertEquals(0,outcome.confirmedCount());
    }

    @Test void skippedCannotCarryAnAbsentOrDifferentProof() {
        for(ActionOutcome.Proof proof:ActionOutcome.Proof.values()) {
            if(proof==ActionOutcome.Proof.CONSOLIDATION_SKIPPED_UNSENT)continue;
            assertThrows(IllegalArgumentException.class,() -> new ActionOutcome(ActionOutcome.State.SKIPPED,"wrong proof",0,proof));
        }
        assertThrows(IllegalArgumentException.class,() -> new ActionOutcome(ActionOutcome.State.SKIPPED,"absent",0,null));
    }

    @Test void safeSkipCannotClaimAnyNonzeroProgressOrAnotherState() {
        for(int count:List.of(-1,1,64))
            assertThrows(IllegalArgumentException.class,() -> new ActionOutcome(ActionOutcome.State.SKIPPED,"wrong count",count,
                ActionOutcome.Proof.CONSOLIDATION_SKIPPED_UNSENT));
        for(ActionOutcome.State state:ActionOutcome.State.values()) {
            if(state==ActionOutcome.State.SKIPPED)continue;
            assertThrows(IllegalArgumentException.class,() -> new ActionOutcome(state,"wrong state",0,
                ActionOutcome.Proof.CONSOLIDATION_SKIPPED_UNSENT));
        }
    }
}
