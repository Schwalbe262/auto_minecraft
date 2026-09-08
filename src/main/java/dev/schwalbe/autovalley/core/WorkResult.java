package dev.schwalbe.autovalley.core;
public record WorkResult(State state, String message) {
    public enum State { BUSY, IDLE, BLOCKED, DEFERRED, RESOURCE_WAIT }
    public static WorkResult busy(String message) { return new WorkResult(State.BUSY,message); }
    public static WorkResult idle() { return new WorkResult(State.IDLE,""); }
    public static WorkResult blocked(String message) { return new WorkResult(State.BLOCKED,message); }
    /** Retryable navigation or a clean scoped wait; never completion or permission to resend a sent action. */
    public static WorkResult deferred(String message) { return new WorkResult(State.DEFERRED,message); }
    /** Validated logging material/visibility wait, never completion or permission to retry an action. */
    public static WorkResult resourceWait(String message) { return new WorkResult(State.RESOURCE_WAIT,message); }
}
