package dev.schwalbe.autovalley.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmergencyKeyRulesTest {
    @Test void ordinaryControlDrainDoesNotRequestAnEmergency() {
        assertFalse(new EmergencyKeyRules().consumeStopPriority(false));
    }

    @Test void screenEmergencyWinsEvenWhenTheScreenDidNotQueueAKeyMappingClick() {
        EmergencyKeyRules rules=new EmergencyKeyRules();
        rules.requestStop();
        assertTrue(rules.consumeStopPriority(false)); // Pending F8/settings must be discarded.
    }

    @Test void rawInputEmergencySurvivesItsImmediateHandlerUntilQueuedControlsAreDrained() {
        EmergencyKeyRules rules=new EmergencyKeyRules();
        rules.requestStop();
        // The runtime may already be OFF here; priority must not depend on running().
        assertTrue(rules.consumeStopPriority(false));
        assertFalse(rules.consumeStopPriority(false)); // A later deliberate resume is allowed.
    }

    @Test void aQueuedEmergencyTakesPriorityWithoutAnEarlierScreenEvent() {
        EmergencyKeyRules rules=new EmergencyKeyRules();
        assertTrue(rules.consumeStopPriority(true));
        assertFalse(rules.consumeStopPriority(false));
    }

    @Test void screenRawAndRepeatedEmergencyEventsCoalesceIntoOnePriorityDrain() {
        EmergencyKeyRules rules=new EmergencyKeyRules();
        rules.requestStop(); rules.requestStop(); rules.requestStop();
        assertTrue(rules.consumeStopPriority(true));
        assertFalse(rules.consumeStopPriority(false));
    }

    @Test void aNewEmergencyAfterTheDrainIsNeverDiscardedAsAnOldRepeat() {
        EmergencyKeyRules rules=new EmergencyKeyRules();
        rules.requestStop();
        assertTrue(rules.consumeStopPriority(false));
        rules.requestStop();
        assertTrue(rules.consumeStopPriority(false));
    }
}
