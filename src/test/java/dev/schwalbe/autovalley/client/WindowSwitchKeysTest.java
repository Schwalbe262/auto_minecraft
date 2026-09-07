package dev.schwalbe.autovalley.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WindowSwitchKeysTest {
    @Test void backgroundAllowsAltTabAndWindowsSwitching() {
        for (int key : new int[]{342,346,258,343,347}) assertTrue(WindowSwitchKeys.allowed(true,key,false));
        assertTrue(WindowSwitchKeys.allowed(true,340,true));
    }
    @Test void normalGameMovementStillInterrupts() {
        for (int key : new int[]{87,65,83,68,69,32,340,344}) assertFalse(WindowSwitchKeys.allowed(true,key,false));
        assertFalse(WindowSwitchKeys.allowed(true,87,true));
    }
    @Test void foregroundOnlyModeDoesNotExemptAltTab() {
        assertFalse(WindowSwitchKeys.allowed(false,342,false));
        assertFalse(WindowSwitchKeys.allowed(false,258,true));
    }
}
