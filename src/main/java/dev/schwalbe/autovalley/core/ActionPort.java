package dev.schwalbe.autovalley.core;
public interface ActionPort {
    boolean busy();
    /** A wrapper may close only a menu that this adapter owns, never a player's unrelated screen. */
    default boolean ownsContainer() { return false; }
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
    /** Explicit operator cleanup must not reconcile a late reply or clear any native failure. */
    default String manualWorkHotbarResolutionRejection() { return "Manual work hotbar resolution requires a read-only native safety check"; }
    /**
     * Read-only server proof of current restored custody for this exact lease, with no unresolved action.
     * The native FULL must follow same-connection swap dispatch/manual slot input and match the live endpoints.
     * This settles only the borrowed slot, never an old action outcome or permission to restart.
     */
    default boolean loggingHotbarRestored(LoggingHotbarLease lease) { return false; }
    /**
     * Read-only native custody proof of count growth in the exact borrowed original.
     * Returns the same position/stage with its current count and fingerprint, or null.
     * Never acknowledges an action, changes a profile, or authorizes a pending swap.
     */
    default LoggingHotbarLease loggingHotbarGrowth(LoggingHotbarLease lease) { return null; }
    /** Current exact live/raw-FULL custody for one work lease; never settles an outstanding click. */
    default boolean workHotbarRestored(HotbarLease lease) { return false; }
    /** Current exact original-at-source custody; never acknowledges the historical parking click. */
    default boolean workHotbarParked(HotbarLease lease) { return false; }
    /** Requires a verified non-mutating normal-inventory request and a real server FULL reply. */
    default boolean supportsInventoryRefresh() { return false; }
    /** Informational bounded same-ticket recovery; null does not clear any action fence. */
    default String recoveryStatus() { return null; }
    /** Target-scoped unconfirmed artisan use; does not stop unrelated automation. */
    default String artisanRejection(Pos target) { return null; }
    /** Short-lived, target-scoped server observation; null is unknown, never a guessed original. */
    default CrystalInspection crystalInspection(Pos target) { return null; }
    /** Moving/aiming separation must be supported before a module overlaps harvest and walking. */
    default boolean supportsMovingHarvest() { return false; }
    /** Requires a supported server-side TrashSlot single-slot deletion channel. */
    default boolean supportsInventoryTrash() { return false; }
    /** Read-only server-endpoint preflight; existing trash-buffer contents do not gate disposal. */
    default String inventoryTrashRejection() { return null; }
}
