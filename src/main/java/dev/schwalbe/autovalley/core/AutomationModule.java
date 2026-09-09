package dev.schwalbe.autovalley.core;
public interface AutomationModule {
    enum ResourceReadiness { WAITING, READY, UNSAFE }
    Feature feature();
    int priority();
    WorkResult tick(Context context);
    void reset();
    /** Read-only revalidation of a previously granted safe logging material or visibility wait. */
    default ResourceReadiness resourceReadiness(Context context) { return ResourceReadiness.UNSAFE; }
    /** Explicit opt-in only after a clean material/receipt wait; navigation deferrals stay sleep-blocking. */
    default boolean sleepSafeDeferred(Context context) { return false; }
    /** No pending or unconsumed action; the current stage may resume after a small nearby job. */
    default boolean canYieldForNearbyWork(Context context) { return false; }
    /** Read-only availability, not permission to preempt; the scheduler applies its own whitelist. */
    default boolean hasNearbyWork(Context context) { return false; }
}
