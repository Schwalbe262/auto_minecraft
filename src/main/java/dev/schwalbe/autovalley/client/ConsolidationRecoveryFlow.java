package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation;

/** The same-ticket native ACK dispatcher. A WAIT is never an instruction to resend. */
final class ConsolidationRecoveryFlow {
    interface Step<A> {
        boolean inFlight();
        boolean normalTimeout();
        Iterable<A> acknowledgements();
        Confirmation acknowledge(A acknowledgement);
        void markAcknowledged();
        void complete();
        void sendUnsent();
        void hold();
        void expired();
    }

    private ConsolidationRecoveryFlow() { }
    /** Only post-cancel FULL packets qualify. A terminated old channel cannot send another old primitive. */
    static boolean manualRestoreCandidate(boolean inFlight,long originalGeneration,long currentGeneration,
                                          long cancellationGeneration,long afterSequence,long sequence) {
        if (sequence<0 || currentGeneration<originalGeneration || currentGeneration<cancellationGeneration
            || inFlight && currentGeneration==originalGeneration) return false;
        return currentGeneration>cancellationGeneration || sequence>afterSequence;
    }
    static <A> void poll(ConsolidationRecoveryBudget budget,long now,Step<A> step) {
        if (budget==null || !budget.mayContinue(now)) { step.expired();return; }
        if (!step.inFlight()) { step.sendUnsent();return; }
        for (A acknowledgement:step.acknowledgements()) {
            Confirmation confirmation=step.acknowledge(acknowledgement);
            if (confirmation==Confirmation.WAIT) continue;
            step.markAcknowledged();
            // Cancellation wins even at a callback boundary. No new primitive
            // and no overwritten CANCELLED outcome may follow it.
            if (!budget.mayContinue(now)) return;
            if (confirmation==Confirmation.COMPLETE) step.complete();
            else step.sendUnsent();
            return;
        }
        if (step.normalTimeout()) step.hold();
    }
}
