package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;
import java.util.function.BiPredicate;
import net.minecraft.nbt.TagParser;

/** One actual FULL inverse-SWAP reply with an independently verified moved-wine refresh. */
final class NativeRestoreMetadataReceipt {
    private NativeRestoreMetadataReceipt() { }

    /** Equality of copied contents is not retained packet provenance. */
    static <T> boolean retainedFull(T candidate,List<T> actualFullReplies) {
        return candidate!=null && actualFullReplies!=null && actualFullReplies.stream().anyMatch(reply -> reply==candidate);
    }

    static Stack verifiedMovedWine(long generation,long observedGeneration,int menuId,
            long beforeSequence,long fullSequence,int sourceSlot,int scratchSlot,
            List<Stack> before,List<Stack> after,boolean cursorEmpty,BiPredicate<Stack,Stack> passiveChange) {
        if (generation<0 || generation!=observedGeneration || menuId!=0 || beforeSequence<0 || fullSequence<=beforeSequence
                || sourceSlot<9 || sourceSlot>35 || scratchSlot<36 || scratchSlot>44 || !cursorEmpty
                || before==null || after==null || before.size()!=46 || after.size()!=46 || passiveChange==null
                || before.stream().anyMatch(Objects::isNull) || after.stream().anyMatch(Objects::isNull)) return null;
        Stack borrowed=before.get(sourceSlot),oldWine=before.get(scratchSlot),movedWine=after.get(sourceSlot);
        if (borrowed.empty() || oldWine.empty() || movedWine.empty() || !borrowed.equals(after.get(scratchSlot))
                || oldWine.count()!=movedWine.count() || oldWine.limit()!=movedWine.limit() || oldWine.limit()>64
                || oldWine.identity().equals(movedWine.identity()) || !wine(oldWine) || !wine(movedWine)) return null;
        List<Stack> comparison=new ArrayList<>(before);
        comparison.set(scratchSlot,movedWine);
        if (comparison.equals(after)) return null; // A passive refresh alone cannot impersonate an inverse.
        Collections.swap(comparison,sourceSlot,scratchSlot);
        if (!comparison.equals(after) || !passiveChange.test(oldWine,movedWine)) return null;
        return movedWine;
    }

    private static boolean wine(Stack item) {
        try { return ItemData.WINE.equals(TagParser.parseTag(item.identity()).getString("id")); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException malformed) { return false; }
    }
}
