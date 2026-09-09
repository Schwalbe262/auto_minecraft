package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Feature;
import dev.schwalbe.autovalley.core.InventoryConsolidation;
import dev.schwalbe.autovalley.core.ItemData;
import dev.schwalbe.autovalley.core.ProductionMergePlanner;

/** Pure permission to abandon optional, UNSENT work. Never acknowledges, sends or edits a transaction. */
final class OptionalConsolidationSkip {
    enum Reason { UNSENT_BASELINE_MISMATCH, TIMEOUT, FAULT }

    /**
     * Captured/rechecked by the action owner on the client thread. Positive flags
     * require actual current evidence, not a missing/unknown guard. In particular
     * recoveryMayContinue is this transaction's existing bounded budget result;
     * it is not a new deadline and must not ignore cancellation or expiry.
     */
    record Boundary(Reason reason,boolean optionalWineOutput,boolean inFlight,
                    boolean sameContext,boolean sameProfile,boolean sameSession,boolean sameWorld,
                    long generation,long observedGeneration,
                    boolean normalInventoryMenu,int menuId,int menuSlots,boolean cursorEmpty,
                    boolean enabled,boolean featureAllowed,boolean protectedHoeUnchanged,int protectedHotbar,
                    boolean borrowedLeaseAbsent,boolean pendingOutputAbsent,boolean otherFencesAbsent,
                    boolean recoveryMayContinue,long lastTick,long tick) { }

    private OptionalConsolidationSkip() { }

    /** Only call after matchesLive returned false for the next primitive that has not been sent. */
    static boolean allowed(InventoryConsolidation transaction,ProductionMergePlanner.Plan plan,Boundary b) {
        if (transaction==null || plan==null || b==null || transaction.complete() || transaction.requiresRestoration()
                || b.reason()!=Reason.UNSENT_BASELINE_MISMATCH || !b.optionalWineOutput() || b.inFlight()
                || plan.feature()!=Feature.WINE || !ItemData.WINE.equals(plan.itemId())
                || !b.sameContext() || !b.sameProfile() || !b.sameSession() || !b.sameWorld()
                || b.generation()<0 || b.observedGeneration()!=b.generation()
                || !b.normalInventoryMenu() || b.menuId()!=0 || b.menuSlots()!=46 || !b.cursorEmpty()
                || !b.enabled() || !b.featureAllowed() || !b.protectedHoeUnchanged()
                || !b.borrowedLeaseAbsent() || !b.pendingOutputAbsent() || !b.otherFencesAbsent()
                || !b.recoveryMayContinue() || b.lastTick()<0 || b.tick()<b.lastTick()) return false;
        try { return ProductionMergePlanner.protectsProductionSlots(plan,b.protectedHotbar()); }
        catch (RuntimeException malformedPlan) { return false; }
    }
}
