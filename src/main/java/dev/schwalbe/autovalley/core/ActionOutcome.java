package dev.schwalbe.autovalley.core;
public record ActionOutcome(State state, String message) {
    public enum State { PENDING, SUCCEEDED, FAILED, CANCELLED }
    public boolean done() { return state != State.PENDING; }
    public boolean success() { return state == State.SUCCEEDED; }
}
