package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import java.util.function.BiPredicate;

/** Detached proof for one real FULL inverse-SWAP reply, never an applied client menu. */
final class NativeRestorePickupReceipt {
    record SlotProof(long seq,int menuId,int slot,Stack item) { }

    private NativeRestorePickupReceipt() { }

    /**
     * The caller supplies raw server packets from the current connection and an
     * independently authenticated FULL reply. Only the latest preceding scratch
     * packet can qualify. Native passive metadata reproduction must preserve
     * count/limit; every other slot, including armor/crafting/offhand, stays exact.
     */
    static Stack verifiedPickup(long generation,long observedGeneration,int menuId,
            long beforeSequence,long fullSequence,int sourceSlot,int scratchSlot,
            List<Stack> before,List<Stack> after,boolean cursorEmpty,List<SlotProof> slots,
            BiPredicate<Stack,Stack> passiveChange) {
        if (generation!=observedGeneration || generation<0 || menuId!=0 || beforeSequence<0
                || fullSequence<=beforeSequence || sourceSlot<9 || sourceSlot>35
                || scratchSlot<36 || scratchSlot>44 || !cursorEmpty
                || before==null || after==null || before.size()!=46 || after.size()!=46
                || slots==null || passiveChange==null || before.stream().anyMatch(Objects::isNull)
                || after.stream().anyMatch(Objects::isNull) || !before.get(scratchSlot).empty()
                || before.get(sourceSlot).empty()) return null;
        SlotProof latest=null;
        Set<Long> sequences=new HashSet<>();
        for (SlotProof slot:slots) {
            if (slot==null) return null;
            if (slot.menuId()!=menuId || slot.seq()<=beforeSequence || slot.seq()>=fullSequence) continue;
            if (!sequences.add(slot.seq()) || slot.slot()<0 || slot.slot()>=46 || slot.item()==null) return null;
            if (slot.slot()==scratchSlot && (latest==null || slot.seq()>latest.seq())) latest=slot;
        }
        if (latest==null) return null;
        Stack raw=latest.item(),pickup=after.get(sourceSlot);
        if (raw.empty() || raw.limit()>64 || raw.count()!=pickup.count() || raw.limit()!=pickup.limit()
                || !NativeInventoryConsolidation.productionAddition(Stack.EMPTY,raw)
                || !NativeInventoryConsolidation.productionAddition(Stack.EMPTY,pickup)) return null;
        if (!raw.equals(pickup) && !passiveChange.test(raw,pickup)) return null;
        List<Stack> expected=new ArrayList<>(before);
        expected.set(scratchSlot,pickup);
        Collections.swap(expected,sourceSlot,scratchSlot);
        return expected.equals(after) ? pickup : null;
    }
}
