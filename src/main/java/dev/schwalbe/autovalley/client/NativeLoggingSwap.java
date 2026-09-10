package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Action;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.Level;

/** Hotbar swaps own a raw full server receipt and preserve each native stack identity. */
final class NativeLoggingSwap {
    final long generation,beforeSequence;
    final int menuId,source,destination;
    private final List<Stack> before;
    private final Level level;
    private final Integer wineYear;
    private final ReceiptLatch receipt;
    NativeLoggingSwap(LocalPlayer player,Action.SwapHotbar action,ServerObservations observations) {
        if (player.containerMenu!=player.inventoryMenu || !player.inventoryMenu.getCarried().isEmpty()) throw new IllegalArgumentException("Inventory changed");
        generation=observations.generation(); beforeSequence=observations.sequence(); menuId=player.inventoryMenu.containerId;
        receipt=new ReceiptLatch(generation,beforeSequence);
        level=player.level(); wineYear=VineryClock.year(level);
        source=slot(player,action.inventoryIndex()); destination=slot(player,action.hotbarSlot());
        before=NativeLoggingRecipe.stacks(player.inventoryMenu.slots.stream().map(s -> s.getItem()).toList());
    }
    private static int slot(LocalPlayer p,int index) {
        var slots=p.inventoryMenu.slots.stream().filter(s -> s.container==p.getInventory() && s.getContainerSlot()==index).toList();
        if (slots.size()!=1) throw new IllegalArgumentException("Ambiguous swap slot");
        return slots.get(0).index;
    }
    boolean confirmed(ServerObservations observations) {
        return receipt.confirmed(observations.generation(),() -> {
            for (var s:observations.fullNativeMenuSnapshotsSince(menuId,beforeSequence)) {
                if (!s.carried().isEmpty()) continue;
                List<Stack> after=NativeLoggingRecipe.stacks(s.items());
                if (swapped(before,after,source,destination) || menuId==0
                    && (swappedWithPickup(before,after,source,destination)
                        || swappedWithPassiveWine(before,after,source,destination,
                            (oldItem,newItem)->NativeWineMetadata.passiveChange(oldItem,newItem,level,wineYear))))
                    return s.seq();
            }
            return -1;
        });
    }
    /** Keeps proven completion after bounded packet history expires; never crosses a connection. */
    static final class ReceiptLatch {
        private final long generation,beforeSequence;
        private long confirmedSequence=-1;
        ReceiptLatch(long generation,long beforeSequence) { this.generation=generation; this.beforeSequence=beforeSequence; }
        boolean confirmed(long currentGeneration,LongSupplier verifiedFullSequence) {
            if (currentGeneration!=generation) return false;
            if (confirmedSequence>beforeSequence) return true;
            long verified=verifiedFullSequence.getAsLong();
            if (verified<=beforeSequence) return false;
            confirmedSequence=verified;
            return true;
        }
    }
    /**
     * A FULL reply may contain pickups into a moved stack. Exactly relocating the
     * distinct partner proves the exchange; the growing partner retains its entire
     * identity and original quantity. No participating loss, tag change or second
     * growing partner is excused. Like the exact-endpoint predicate, this proves
     * only the exchange; unrelated slots cannot invalidate it or gain any authority.
     */
    static boolean swappedWithPickup(List<Stack> before,List<Stack> after,int source,int destination) {
        if (before==null || after==null || before.size()!=46 || after.size()!=46
                || source<9 || source>44 || destination<36 || destination>44 || source==destination
                || before.stream().anyMatch(Objects::isNull) || after.stream().anyMatch(Objects::isNull)) return false;
        Stack oldSource=before.get(source),oldDestination=before.get(destination);
        if (oldSource.limit()>64 || oldDestination.limit()>64
                || oldSource.identity().equals(oldDestination.identity())) return false;
        boolean sourceGrew=boundedGrowth(oldSource,after.get(destination)) && oldDestination.equals(after.get(source));
        boolean destinationGrew=boundedGrowth(oldDestination,after.get(source)) && oldSource.equals(after.get(destination));
        return sourceGrew || destinationGrew;
    }
    private static boolean boundedGrowth(Stack before,Stack after) {
        return !before.empty() && !after.empty() && before.limit()==after.limit() && after.limit()<=64
            && before.identity().equals(after.identity()) && after.count()>before.count();
    }
    /** Opt-in actual FULL proof: only a wine endpoint may undergo exact native passive metadata refresh. */
    static boolean swappedWithPassiveWine(List<Stack> before,List<Stack> after,int source,int destination,
                                           BiPredicate<Stack,Stack> nativePassive) {
        if (before==null || after==null || before.size()!=46 || after.size()!=46 || nativePassive==null
                || source<9 || source>44 || destination<36 || destination>44 || source==destination
                || before.stream().anyMatch(Objects::isNull) || after.stream().anyMatch(Objects::isNull)
                || before.get(source).equals(before.get(destination))) return false;
        // This new exception has stronger whole-menu conservation than the legacy
        // exact-endpoint predicate. No unrelated pickup/rebase is inferred here.
        for (int slot=0;slot<46;slot++)
            if (slot!=source && slot!=destination && !before.get(slot).equals(after.get(slot))) return false;
        return exactOrPassiveWine(before.get(source),after.get(destination),nativePassive)
            && exactOrPassiveWine(before.get(destination),after.get(source),nativePassive);
    }
    private static boolean exactOrPassiveWine(Stack before,Stack after,BiPredicate<Stack,Stack> nativePassive) {
        if (before.equals(after)) return true;
        if (before.empty() || after.empty() || before.count()!=after.count() || before.limit()!=after.limit()
                || before.count()>before.limit() || before.limit()>64) return false;
        try {
            if (!ItemData.WINE.equals(TagParser.parseTag(before.identity()).getString("id"))
                    || !ItemData.WINE.equals(TagParser.parseTag(after.identity()).getString("id"))) return false;
            return nativePassive.test(before,after);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | RuntimeException unavailable) { return false; }
    }
    static boolean swapped(List<Stack> before,List<Stack> after,int source,int destination) {
        return before!=null && after!=null && before.size()==after.size() && source>=0 && destination>=0
            && source<before.size() && destination<before.size() && source!=destination
            && !before.get(source).equals(before.get(destination))
            && before.get(source).equals(after.get(destination)) && before.get(destination).equals(after.get(source));
    }
}
