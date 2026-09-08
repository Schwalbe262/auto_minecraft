package dev.schwalbe.autovalley.core;
public interface AutomationModule {
    enum ResourceReadiness { WAITING, READY, UNSAFE }
    Feature feature();
    int priority();
    WorkResult tick(Context context);
    void reset();
    /** Read-only revalidation of a previously granted, inventory-only resource wait. */
    default ResourceReadiness resourceReadiness(Context context) { return ResourceReadiness.UNSAFE; }
}
