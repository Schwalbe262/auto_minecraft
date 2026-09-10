package dev.schwalbe.autovalley.core;
public interface ActionPort {
    boolean busy();
    long submit(Action action);
    ActionOutcome outcome(long ticket);
    void move(Movement movement);
    void stopMovement();
    void cancel();
    /** Logging-only one-pulse movement; unknown/native-unverified adapters cannot jump. */
    default boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch) { return false; }
    /** An active navigator may request one natively verified ascent, not an unrestricted jump key. */
    default boolean moveStepUp(LoggingJumpEdge edge,boolean launch) { return false; }
    /** Unresolved in-flight inventory changes must block every scheduler consumer. */
    default String pauseReason() { return null; }
    /** Explicit manual resume may clear an already-resolved failure, never a late-ACK fence. */
    default String startRejection() { return pauseReason(); }
    /**
     * Read-only server proof of current restored custody for this exact lease, with no unresolved action.
     * The native FULL must follow same-connection swap dispatch/manual slot input and match the live endpoints.
     * This settles only the borrowed slot, never an old action outcome or permission to restart.
     */
    default boolean loggingHotbarRestored(LoggingHotbarLease lease) { return false; }
    /** Informational bounded same-ticket recovery; null does not clear any action fence. */
    default String recoveryStatus() { return null; }
    /** Target-scoped unconfirmed artisan use; does not stop unrelated automation. */
    default String artisanRejection(Pos target) { return null; }
    /** Moving/aiming separation must be supported before a module overlaps harvest and walking. */
    default boolean supportsMovingHarvest() { return false; }
    /** Requires a supported server-side TrashSlot single-slot deletion channel. */
    default boolean supportsInventoryTrash() { return false; }
    /** Read-only server-endpoint preflight; existing trash-buffer contents do not gate disposal. */
    default String inventoryTrashRejection() { return null; }
}
