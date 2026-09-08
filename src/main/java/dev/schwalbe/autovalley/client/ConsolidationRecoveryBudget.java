package dev.schwalbe.autovalley.client;

/** One transaction-wide grace window; neither packets nor acknowledgements are inferred here. */
final class ConsolidationRecoveryBudget {
    static final long MAX_RECOVERY_TICKS=600;
    private long lastTick=Long.MIN_VALUE,firstWait=Long.MIN_VALUE;
    private boolean cancelled;

    /** Must be checked before every prospective new primitive, including after a late ACK. */
    boolean mayContinue(long tick) {
        if (cancelled || tick<0 || lastTick!=Long.MIN_VALUE && tick<lastTick) { cancelled=true;return false; }
        lastTick=tick;
        return firstWait==Long.MIN_VALUE || tick-firstWait>=0 && tick-firstWait<MAX_RECOVERY_TICKS;
    }

    /** Repeated waits and later primitives never extend this first deadline. */
    boolean awaitProof(long tick) {
        if (!mayContinue(tick)) return false;
        if (firstWait==Long.MIN_VALUE) firstWait=tick;
        return true;
    }

    boolean recovering() { return !cancelled && firstWait!=Long.MIN_VALUE; }
    long remainingTicks(long tick) {
        if (!recovering() || tick<firstWait) return 0;
        return Math.max(0,MAX_RECOVERY_TICKS-(tick-firstWait));
    }
    /** Manual OFF and action cancellation permanently revoke this transaction's automatic continuation. */
    void cancel() { cancelled=true; }
}
