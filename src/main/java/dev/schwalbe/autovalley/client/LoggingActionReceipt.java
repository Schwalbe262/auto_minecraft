package dev.schwalbe.autovalley.client;

import java.util.function.BooleanSupplier;

/** Historical completion and processed cancellation are separate facts; neither changes a cancelled ticket. */
final class LoggingActionReceipt {
    record Processed(long observationSequence,int throughSequence) { }
    private final long generation;
    private boolean completed;

    LoggingActionReceipt(long generation) { this.generation=generation; }

    /** A real success survives bounded packet-history eviction, only within its original connection. */
    boolean confirmed(long currentGeneration,BooleanSupplier actualSuccess) {
        if(currentGeneration!=generation)return false;
        if(!completed) {
            // Passive observation must not interrupt packet application or skip
            // the ordinary ABORT path during cancellation. Unknown is no proof.
            try { completed=actualSuccess.getAsBoolean(); }
            catch(RuntimeException unavailable) { return false; }
        }
        return completed;
    }

    /**
     * Vanilla may defer destruction after STOP; an ABORT processing receipt
     * cannot settle that case. Only a non-instant stroke cancelled before any
     * STOP can use the real cumulative server sequence ACK as cancellation proof.
     * This proves processing of the admitted inputs, not that Forge allowed ABORT
     * or that any block was removed: ordinary START-only progress cannot destroy later.
     */
    static boolean cancelledBeforeStop(boolean planting,boolean started,boolean stopped,boolean aborted,
            boolean abortDispatched,boolean abortForwarded,long generation,long currentGeneration,long beforeSequence,
            long abortBeforeSequence,int abortPacketSequence,Processed processed) {
        return !planting && started && !stopped && aborted && abortDispatched && abortForwarded
            && generation>=0 && generation==currentGeneration && beforeSequence>=0 && abortBeforeSequence>=beforeSequence
            && abortPacketSequence>0 && processed!=null && processed.observationSequence()>abortBeforeSequence
            && processed.throughSequence()>=abortPacketSequence;
    }
}
