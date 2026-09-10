package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.Objects;
import java.util.List;

/** Exact custody evidence only: never acknowledges a cancelled click or authorizes an inverse swap. */
final class LoggingRestorationReceipt {
    record Endpoint(ItemData item,String fingerprint) {
        Endpoint {
            // Native EMPTY and a detached zero-count AIR stack can serialize
            // differently. Neither owns an item; their opaque tag is not custody.
            if (item!=null && item.empty()) { item=ItemData.EMPTY;fingerprint="empty"; }
        }
    }
    static <T> T latest(List<T> snapshots) { return snapshots==null || snapshots.isEmpty() ? null : snapshots.get(snapshots.size()-1); }
    static boolean proves(LoggingHotbarLease lease,long generation,long lastSwapGeneration,long lastSwapSequence,
            long fullSequence,boolean rawFullEmptyCursor,Endpoint liveSource,Endpoint liveHotbar,
            Endpoint packetSource,Endpoint packetHotbar) {
        if (lease==null || lease.original()==null || lease.original().empty() || lease.original().is(LoggingRules.SAPLING)
            || lease.fingerprint()==null || lease.fingerprint().isBlank()
            || lease.sourceIndex()<9 || lease.sourceIndex()>35 || lease.hotbarSlot()<0 || lease.hotbarSlot()>8
            || !rawFullEmptyCursor || fullSequence<1
            || generation==lastSwapGeneration && fullSequence<=lastSwapSequence
            || liveSource==null || liveHotbar==null || packetSource==null || packetHotbar==null
            || liveSource.item()==null || liveHotbar.item()==null
            || !Objects.equals(liveSource,packetSource) || !Objects.equals(liveHotbar,packetHotbar)) return false;
        return lease.original().equals(liveHotbar.item()) && lease.fingerprint().equals(liveHotbar.fingerprint())
            && liveSource.fingerprint()!=null
            && LoggingRules.temporaryHotbarItem(liveSource.item())
            && (liveSource.item().empty() || !lease.fingerprint().equals(liveSource.fingerprint()));
    }
}
