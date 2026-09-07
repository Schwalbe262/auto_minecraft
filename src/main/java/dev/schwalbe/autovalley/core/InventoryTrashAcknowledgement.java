package dev.schwalbe.autovalley.core;

import java.util.*;

/** One whole source stack disappears; unrelated changes require independent server proof. */
public final class InventoryTrashAcknowledgement {
    /** Native packet item only, or the source slot of an authoritative full-menu packet. */
    public record SlotProof(long sequence,int menuId,int slot,InventoryConsolidation.Stack item) { }
    private InventoryTrashAcknowledgement() { }
    public static int confirmed(List<InventoryConsolidation.Stack> before,List<InventoryConsolidation.Stack> after,
                                int sourceSlot,boolean sourceServerConfirmedEmpty,boolean cursorEmpty) {
        return confirmed(before,after,sourceSlot,sourceServerConfirmedEmpty,cursorEmpty,Set.of(),Set.of());
    }
    /** The native adapter verifies item whitelist, normal-inventory scope and raw packet provenance. */
    public static int confirmed(List<InventoryConsolidation.Stack> before,List<InventoryConsolidation.Stack> after,
                                int sourceSlot,boolean sourceServerConfirmedEmpty,boolean cursorEmpty,
                                Set<Integer> verifiedProductionAdditions,Set<Integer> normalInventorySlots) {
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        if (!sourceServerConfirmedEmpty || !cursorEmpty || sourceSlot<0 || sourceSlot>=before.size()
            || before.size()!=after.size() || before.size()>128 || before.get(sourceSlot).empty() || !after.get(sourceSlot).empty()
            || verifiedProductionAdditions==null || verifiedProductionAdditions.size()>36 || verifiedProductionAdditions.contains(sourceSlot)
            || normalInventorySlots==null || normalInventorySlots.size()>36 || !normalInventorySlots.containsAll(verifiedProductionAdditions)) return 0;
        for (Integer index:verifiedProductionAdditions) if (index==null || index<0 || index>=before.size()) return 0;
        for (int index=0;index<before.size();index++) {
            if (index==sourceSlot || before.get(index).equals(after.get(index))) continue;
            var old=before.get(index); var now=after.get(index);
            if (!verifiedProductionAdditions.contains(index) || now.empty() || now.count()<=old.count() || now.count()>now.limit()
                || !old.empty() && (old.limit()!=now.limit() || !old.identity().equals(now.identity()))
                || now.identity().equals(before.get(sourceSlot).identity())) return 0;
        }
        return before.get(sourceSlot).count();
    }

    /**
     * At most 64 raw slot replies plus 8 full-menu source observations. Later replies
     * cannot justify an older applied-client snapshot. Null means missing/unsafe proof.
     */
    public static Map<Integer,InventoryConsolidation.Stack> precedingSlotProofs(long requestGeneration,long observationGeneration,
            int menuId,long beforeSequence,long deletionSequence,int sourceSlot,InventoryConsolidation.Stack originalSource,
            List<SlotProof> proofs) {
        if (requestGeneration!=observationGeneration || deletionSequence<=beforeSequence || sourceSlot<0 || sourceSlot>=128
            || originalSource==null || originalSource.empty() || proofs==null || proofs.size()>72) return null;
        if (proofs.stream().anyMatch(p -> p==null || p.item()==null)) return null;
        List<SlotProof> ordered=proofs.stream().filter(p -> p.menuId()==menuId && p.sequence()>beforeSequence && p.sequence()<=deletionSequence)
            .sorted(Comparator.comparingLong(SlotProof::sequence)).toList();
        Map<Integer,InventoryConsolidation.Stack> latest=new HashMap<>();boolean sawEmpty=false,sourceAtDeletion=false;
        for (SlotProof proof:ordered) {
            if (proof.slot()<0 || proof.slot()>=128) return null;
            if (proof.slot()==sourceSlot) {
                if (proof.item().empty()) sawEmpty=true;
                else if (sawEmpty || !proof.item().equals(originalSource)) return null;
                if (proof.sequence()==deletionSequence && proof.item().empty()) sourceAtDeletion=true;
            }
            latest.put(proof.slot(),proof.item());
        }
        return sourceAtDeletion ? Map.copyOf(latest) : null;
    }
}
