package dev.schwalbe.autovalley.core;

/** Private durable custody for one work slot; its fingerprint is a digest, never raw item NBT. */
public record HotbarLease(Feature owner,int sourceIndex,int hotbarSlot,ItemData original,String fingerprint,Stage stage) {
    public enum Stage { PREPARED, PARKED, RESTORING }
    public HotbarLease(Feature owner,int sourceIndex,int hotbarSlot,ItemData original,String fingerprint) {
        this(owner,sourceIndex,hotbarSlot,original,fingerprint,Stage.PREPARED);
    }
    public HotbarLease withStage(Stage next) {
        return new HotbarLease(owner,sourceIndex,hotbarSlot,original,fingerprint,next);
    }
    /** Structural validity only; current native custody and protected configured slots need separate proof. */
    public boolean valid() {
        return (owner==Feature.SEED_MAKER || owner==Feature.CRYSTAL_COPY || owner==Feature.STARFRUIT)
            && sourceIndex>=9 && sourceIndex<=35 && hotbarSlot>=0 && hotbarSlot<=8
            && original!=null && original.id()!=null && original.id().length()<=256
            && original.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && !original.empty() && original.count()<=64
            && fingerprint!=null && fingerprint.matches("[0-9a-fA-F]{64}") && stage!=null;
    }
}
