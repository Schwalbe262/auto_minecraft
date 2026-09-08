package dev.schwalbe.autovalley.core;
public interface Navigation {
    enum Result { MOVING, ARRIVED, BLOCKED }
    Result moveTo(Pos target, double reach, Context context);
    /** Only the active logging routine may opt in to its separately verified ascent edges. */
    default Result moveToLogging(Pos target,double reach,Context context) { return moveTo(target,reach,context); }
    /** Reach a stance that can plant every missing cell of one registered 2x2 plot. */
    default Result moveToLoggingPlanting(java.util.List<Pos> targets,Context context) {
        context.actions().stopMovement();
        return Result.BLOCKED;
    }
    /** Capture before reset so an operator can distinguish bounds, collision and reach failures. */
    default String failureReason() { return ""; }
    /** A continuation may steer only; it must never open a door or send another action. */
    default Result moveToWithoutInteraction(Pos target,double reach,Context context) {
        context.actions().stopMovement();
        return Result.BLOCKED;
    }
    void reset();
}
