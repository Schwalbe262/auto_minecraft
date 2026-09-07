package dev.schwalbe.autovalley.core;
public interface AutomationModule {
    Feature feature();
    int priority();
    WorkResult tick(Context context);
    void reset();
}
