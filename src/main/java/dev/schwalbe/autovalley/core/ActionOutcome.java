package dev.schwalbe.autovalley.core;
public record ActionOutcome(State state, String message, int confirmedCount) {
    public ActionOutcome(State state,String message) { this(state,message,0); }
    public enum State { PENDING, SUCCEEDED, FAILED, CANCELLED }
    public boolean done() { return state != State.PENDING; }
    public boolean success() { return state == State.SUCCEEDED; }
}
