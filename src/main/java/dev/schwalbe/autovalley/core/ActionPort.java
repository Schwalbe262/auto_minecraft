package dev.schwalbe.autovalley.core;
public interface ActionPort {
    boolean busy();
    long submit(Action action);
    ActionOutcome outcome(long ticket);
    void move(Movement movement);
    void stopMovement();
    void cancel();
}
