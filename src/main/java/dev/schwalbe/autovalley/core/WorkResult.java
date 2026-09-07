package dev.schwalbe.autovalley.core;
public record WorkResult(State state, String message) {
    public enum State { BUSY, IDLE, BLOCKED }
    public static WorkResult busy(String message) { return new WorkResult(State.BUSY,message); }
    public static WorkResult idle() { return new WorkResult(State.IDLE,""); }
    public static WorkResult blocked(String message) { return new WorkResult(State.BLOCKED,message); }
}
