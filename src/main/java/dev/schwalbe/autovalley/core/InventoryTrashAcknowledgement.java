package dev.schwalbe.autovalley.core;

import java.util.List;
import java.util.Objects;

/** Exact native-fingerprint conservation: one whole source stack disappears, nothing else changes. */
public final class InventoryTrashAcknowledgement {
    private InventoryTrashAcknowledgement() { }
    public static int confirmed(List<InventoryConsolidation.Stack> before,List<InventoryConsolidation.Stack> after,
                                int sourceSlot,boolean sourceServerConfirmedEmpty,boolean cursorEmpty) {
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        if (!sourceServerConfirmedEmpty || !cursorEmpty || sourceSlot<0 || sourceSlot>=before.size()
            || before.size()!=after.size() || before.get(sourceSlot).empty() || !after.get(sourceSlot).empty()) return 0;
        for (int index=0;index<before.size();index++)
            if (index!=sourceSlot && !before.get(index).equals(after.get(index))) return 0;
        return before.get(sourceSlot).count();
    }
}
