package dev.schwalbe.autovalley.client;

/** Pure GLFW key classification: no native input polling or OS key interception. */
public final class WindowSwitchKeys {
    private WindowSwitchKeys() { }
    public static boolean allowed(boolean background, int key, boolean altDown) {
        if (!background) return false;
        // Alt/Tab, Windows task switch, and Shift+Alt+Tab may leave the game focused briefly.
        return key==342 || key==346 || key==258 || key==343 || key==347
            || altDown && (key==340 || key==344);
    }
}
