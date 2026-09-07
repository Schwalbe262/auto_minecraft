package dev.schwalbe.autovalley.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmergencyStartGateTest {
    @Test void nextRuntimeAdvanceCannotEraseEmergencyPriority() {
        EmergencyStartGate gate=new EmergencyStartGate(); assertFalse(gate.blockedAt(10));
        gate.stopAt(10); assertTrue(gate.blockedAt(10)); assertTrue(gate.blockedAt(11)); assertFalse(gate.blockedAt(12));
    }
    @Test void repeatedStopDoesNotShortenTheGateAndOverflowCannotReopenIt() {
        EmergencyStartGate gate=new EmergencyStartGate(); gate.stopAt(10); gate.stopAt(9); assertTrue(gate.blockedAt(11));
        gate.stopAt(Long.MAX_VALUE); assertTrue(gate.blockedAt(Long.MAX_VALUE));
    }
    @Test void emergencyBypassesPollingDelaySoOldStartRequestCannotRunLater() {
        assertFalse(EmergencyStartGate.shouldPoll(false,100,1000));
        assertTrue(EmergencyStartGate.shouldPoll(true,100,1000));
        assertTrue(EmergencyStartGate.shouldPoll(false,1000,1000));
        assertTrue(EmergencyStartGate.rejectsCommand(true,"start"));
        assertTrue(EmergencyStartGate.rejectsCommand(true,"once"));
        assertFalse(EmergencyStartGate.rejectsCommand(false,"start"));
    }
    @Test void manualRecordingAndPauseRemainAvailable() {
        for(String command:new String[]{"record_start","record_stop","pause"})
            assertFalse(EmergencyStartGate.rejectsCommand(true,command));
    }
    @Test void unreadableOldRequestKeepsStartsBlockedBeyondTheTickWindow() {
        EmergencyStartGate gate=new EmergencyStartGate(); gate.stopAt(10);
        gate.controlChecked(11,false);
        assertTrue(gate.blockedAt(1000));
        assertTrue(EmergencyStartGate.rejectsCommand(gate.blockedAt(1000),"start"));
        assertTrue(EmergencyStartGate.rejectsCommand(gate.blockedAt(1000),"once"));
        gate.controlChecked(1000,false);
        assertTrue(gate.blockedAt(2000));
        // Consume/reject while the fence is still active, then release it.
        assertTrue(EmergencyStartGate.rejectsCommand(gate.blockedAt(2000),"start"));
        gate.controlChecked(2000,true);
        assertFalse(gate.blockedAt(2001));
    }
    @Test void confirmedAbsenceClearsIoFenceButNotARecentStop() {
        EmergencyStartGate gate=new EmergencyStartGate(); gate.stopAt(10);
        gate.controlChecked(10,false); gate.controlChecked(11,true);
        assertTrue(gate.blockedAt(11)); assertFalse(gate.blockedAt(12));
    }
    @Test void ordinaryIoFailureDoesNotInventAnEmergencyStop() {
        EmergencyStartGate gate=new EmergencyStartGate(); gate.controlChecked(10,false);
        assertFalse(gate.blockedAt(11));
    }
}
