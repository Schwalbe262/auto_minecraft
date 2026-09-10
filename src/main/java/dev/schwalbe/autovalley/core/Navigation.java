package dev.schwalbe.autovalley.core;
public interface Navigation {
    enum Result { MOVING, ARRIVED, BLOCKED }
    enum Failure { NONE, INVALID_START, NO_PATH, UNLOADED, SEARCH_LIMIT, OBSTACLE, STALLED, REACH, SAFETY, JUMP_UNCERTAIN }
    Result moveTo(Pos target, double reach, Context context);
    /** A literal standing location, not an interaction ray to the block at that location. */
    default Result moveToPosition(Pos target,double reach,Context context) { context.actions().stopMovement(); return Result.BLOCKED; }
    /** A loaded target observed from a safe nearby stance; no interaction or LOS is required. */
    default Result moveToObserve(Pos target,double reach,Context context) { context.actions().stopMovement(); return Result.BLOCKED; }
    /** Only the active logging routine may opt in to its separately verified ascent edges. */
    default Result moveToLogging(Pos target,double reach,Context context) { return moveTo(target,reach,context); }
    /** Reach an exact standing surface while retaining logging's existing movement authority. */
    default Result moveToLoggingPosition(Pos target,double reach,Context context) { context.actions().stopMovement(); return Result.BLOCKED; }
    /** Reach a stance that can plant every missing cell of one registered 2x2 plot. */
    default Result moveToLoggingPlanting(java.util.List<Pos> targets,Context context) {
        context.actions().stopMovement();
        return Result.BLOCKED;
    }
    /** Capture before reset so an operator can distinguish bounds, collision and reach failures. */
    default String failureReason() { return ""; }
    default Failure failureKind() { return Failure.NONE; }
    default boolean retryableFailure() { return false; }
    default Pos failureDestination() { return null; }
    default String diagnosticStatus() { return ""; }
    default java.util.Map<String,Object> diagnostics() { return java.util.Map.of(); }
    /** Read-only proof that the current flat travel may stop for a separately authorised nearby job. */
    default boolean canYieldTravel(Context context) { return false; }
    /** Read-only result of this navigator's owned interaction, not an unrelated action or a movement permit. */
    default ActionOutcome pendingInteractionOutcome(Context context) { return null; }
    /** Native movement rechecks the active request's domain, not a reusable global permission. */
    default boolean permitsTransit(Pos feet,Context context) { return false; }
    /** Only the exact active one-pulse controller may request a native general ascent. */
    default boolean permitsStepUp(LoggingJumpEdge edge,Context context) { return false; }
    /** A continuation may steer only; it must never open a door or send another action. */
    default Result moveToWithoutInteraction(Pos target,double reach,Context context) {
        context.actions().stopMovement();
        return Result.BLOCKED;
    }
    void reset();
}
