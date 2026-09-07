package dev.schwalbe.autovalley.core;

/** A durable obligation; legacy wine records remain readable for explicit tracking-disabled migration. */
public record PendingMachineOutput(String id, Feature feature, Pos machine, long createdDay,
                                   Integer expectedWineYear, int minimumInventoryCount, Phase phase) {
    public enum Phase { AWAITING_MACHINE_CONFIRMATION, AWAITING_PICKUP }

    public String outputId() {
        if (feature == Feature.WINE) return ItemData.WINE;
        if (feature == Feature.PRESERVES) return ItemData.PRESERVES;
        throw new IllegalStateException("Unsupported machine-output feature");
    }
}
