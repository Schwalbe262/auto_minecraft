package dev.schwalbe.autovalley.core;
public interface ActionPort {
    boolean busy();
    long submit(Action action);
    ActionOutcome outcome(long ticket);
    void move(Movement movement);
    void stopMovement();
    void cancel();
    /** Unresolved in-flight inventory changes must block every scheduler consumer. */
    default String pauseReason() { return null; }
    /** Explicit manual resume may clear an already-resolved failure, never a late-ACK fence. */
    default String startRejection() { return pauseReason(); }
    /** Moving/aiming separation must be supported before a module overlaps harvest and walking. */
    default boolean supportsMovingHarvest() { return false; }
    /** Requires a supported server-side TrashSlot single-slot deletion channel. */
    default boolean supportsInventoryTrash() { return false; }
}
