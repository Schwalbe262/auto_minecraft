package dev.schwalbe.autovalley.core;
public record ActionOutcome(State state, String message, int confirmedCount, Proof proof) {
    public ActionOutcome(State state,String message,int confirmedCount) { this(state,message,confirmedCount,Proof.NONE); }
    public ActionOutcome(State state,String message) { this(state,message,0); }
    public ActionOutcome {
        proof=proof==null ? Proof.NONE : proof;
        if (state==State.SKIPPED || proof==Proof.CONSOLIDATION_SKIPPED_UNSENT) {
            if (state!=State.SKIPPED || proof!=Proof.CONSOLIDATION_SKIPPED_UNSENT || confirmedCount!=0)
                throw new IllegalArgumentException("Safe consolidation skip requires its exact proof and zero confirmed progress");
        } else if (proof!=Proof.NONE && state!=State.SUCCEEDED)
            throw new IllegalArgumentException("Native completion proof requires a successful action outcome");
    }
    public enum State { PENDING, SUCCEEDED, FAILED, CANCELLED, SKIPPED }
    /** Per-ticket native evidence only; not an action request or a persistent work obligation. */
    public enum Proof { NONE, ARTISAN_CYCLE_ADVANCED, WINE_PARTIAL_FEED, CONSOLIDATION_SKIPPED_UNSENT }
    public boolean done() { return state != State.PENDING; }
    public boolean success() { return state == State.SUCCEEDED; }
}
