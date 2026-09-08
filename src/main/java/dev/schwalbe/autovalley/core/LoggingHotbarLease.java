package dev.schwalbe.autovalley.core;

/** Private checkpoint for one borrowed hotbar slot; the fingerprint is not raw item NBT. */
public record LoggingHotbarLease(int sourceIndex,int hotbarSlot,ItemData original,String fingerprint,Stage stage) {
    public enum Stage { PREPARED, PARKED, RESTORING }
    public LoggingHotbarLease(int sourceIndex,int hotbarSlot,ItemData original,String fingerprint) {
        this(sourceIndex,hotbarSlot,original,fingerprint,Stage.PREPARED);
    }
    public LoggingHotbarLease withStage(Stage next) {
        return new LoggingHotbarLease(sourceIndex,hotbarSlot,original,fingerprint,next);
    }
}
