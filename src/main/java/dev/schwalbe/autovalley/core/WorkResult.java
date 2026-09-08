package dev.schwalbe.autovalley.core;
public record WorkResult(State state, String message) {
    public enum State { BUSY, IDLE, BLOCKED, DEFERRED }
    public static WorkResult busy(String message) { return new WorkResult(State.BUSY,message); }
    public static WorkResult idle() { return new WorkResult(State.IDLE,""); }
    public static WorkResult blocked(String message) { return new WorkResult(State.BLOCKED,message); }
    /** Retryable ordinary navigation only, never an unconfirmed action or completed work. */
    public static WorkResult deferred(String message) { return new WorkResult(State.DEFERRED,message); }
}
