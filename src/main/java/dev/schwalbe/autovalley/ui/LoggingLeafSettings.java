package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.Profile;
import java.util.Objects;

/** One explicit opt-in bit; retained logging work and all gameplay authority remain unchanged. */
public final class LoggingLeafSettings {
    private LoggingLeafSettings() { }
    public record Boundary(boolean running,boolean recording,boolean connected,boolean grounded,
                           boolean normalMenu,boolean emptyCursor,boolean nativeQuiet,boolean persistenceReady) { }
    public static boolean editable(Profile profile,Boundary boundary) {
        return profile!=null && boundary!=null && !boundary.running() && !boundary.recording()
            && boundary.connected() && boundary.grounded() && boundary.normalMenu() && boundary.emptyCursor()
            && boundary.nativeQuiet() && boundary.persistenceReady() && profile.loggingHotbarLease==null
            && profile.pendingMachineOutputs!=null && profile.pendingMachineOutputs.isEmpty();
    }
    public static void apply(Profile profile,boolean desired,Boundary boundary,Runnable persist) {
        if (!editable(profile,boundary)) throw new IllegalStateException("Logging leaf setting requires a clean paused boundary");
        Objects.requireNonNull(persist);
        boolean before=profile.loggingClearObstructingLeaves;
        profile.loggingClearObstructingLeaves=desired;
        try { persist.run(); }
        catch (RuntimeException failure) { profile.loggingClearObstructingLeaves=before; throw failure; }
    }
}
