package dev.schwalbe.autovalley.core;
public record WorkResult(State state, String message) {
    public enum State { BUSY, IDLE, BLOCKED, DEFERRED, RESOURCE_WAIT, COOLDOWN }
    public static WorkResult busy(String message) { return new WorkResult(State.BUSY,message); }
    public static WorkResult idle() { return new WorkResult(State.IDLE,""); }
    public static WorkResult blocked(String message) { return new WorkResult(State.BLOCKED,message); }
    /** Retryable navigation or a clean scoped wait; never completion or permission to resend a sent action. */
    public static WorkResult deferred(String message) { return new WorkResult(State.DEFERRED,message); }
    /** Validated logging material/visibility wait, never completion or permission to retry an action. */
    public static WorkResult resourceWait(String message) { return new WorkResult(State.RESOURCE_WAIT,message); }
    /** Observed production in progress at a clean boundary, not an error or a completed batch. */
    public static WorkResult cooldown(String message) { return new WorkResult(State.COOLDOWN,message); }
}
