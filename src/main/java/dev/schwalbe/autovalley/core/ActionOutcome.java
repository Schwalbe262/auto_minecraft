package dev.schwalbe.autovalley.core;
public record ActionOutcome(State state, String message, int confirmedCount, Proof proof) {
    public ActionOutcome(State state,String message,int confirmedCount) { this(state,message,confirmedCount,Proof.NONE); }
    public ActionOutcome(State state,String message) { this(state,message,0); }
    public ActionOutcome {
        proof=proof==null ? Proof.NONE : proof;
        if (proof!=Proof.NONE && state!=State.SUCCEEDED)
            throw new IllegalArgumentException("Native completion proof requires a successful action outcome");
    }
    public enum State { PENDING, SUCCEEDED, FAILED, CANCELLED }
    /** Per-ticket native evidence only; not an action request or a persistent work obligation. */
    public enum Proof { NONE, ARTISAN_CYCLE_ADVANCED }
    public boolean done() { return state != State.PENDING; }
    public boolean success() { return state == State.SUCCEEDED; }
}
