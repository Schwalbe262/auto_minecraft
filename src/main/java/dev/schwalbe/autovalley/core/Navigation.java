package dev.schwalbe.autovalley.core;
public interface Navigation {
    enum Result { MOVING, ARRIVED, BLOCKED }
    Result moveTo(Pos target, double reach, Context context);
    /** Capture before reset so an operator can distinguish bounds, collision and reach failures. */
    default String failureReason() { return ""; }
    /** A continuation may steer only; it must never open a door or send another action. */
    default Result moveToWithoutInteraction(Pos target,double reach,Context context) {
        context.actions().stopMovement();
        return Result.BLOCKED;
    }
    void reset();
}
