package dev.schwalbe.autovalley.client;

/**
 * Main-thread emergency priority latch; never reads keys or invokes game actions.
 * Screen key events do not necessarily create a KeyMapping click. Retain an emergency
 * handled immediately by such an event until the next queued-control drain, so a stale
 * F8/settings/waypoint click cannot undo it. Repeated requests are intentionally idempotent.
 */
public final class EmergencyKeyRules {
    private boolean stopRequested;

    /** Call before handling an emergency directly in a screen/raw-input event. */
    public void requestStop() { stopRequested=true; }

    /**
     * Call once before dispatching any queued controls. True means discard every queued
     * automation control for this drain and remain stopped; the caller must not toggle.
     * queuedStop must summarize/drain all queued emergency clicks, not just the first one.
     */
    public boolean consumeStopPriority(boolean queuedStop) {
        boolean stop=stopRequested || queuedStop;
        stopRequested=false;
        return stop;
    }
}
