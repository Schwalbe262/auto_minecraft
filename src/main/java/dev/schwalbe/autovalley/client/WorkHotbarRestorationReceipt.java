package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.List;
import java.util.Objects;

/** Current custody only, independent of feature switches and cancelled action outcomes. */
final class WorkHotbarRestorationReceipt {
    /** Only a detached raw packet endpoint; an applied client menu is never evidence. */
    record SlotUpdate(long seq,int menuId,int slot,LoggingRestorationReceipt.Endpoint packetItem) { }

    /**
     * Project current endpoints from the newest retained FULL and later raw slot
     * packets. This neither creates a FULL nor completes a sent inventory action.
     * The caller must select the newest FULL of this connection, not an older
     * matching snapshot, and retain all ordinary pending-action/cursor fences.
     */
    static boolean provesCurrent(HotbarLease lease,long generation,long lastSwapGeneration,long lastSwapSequence,
            long fullSequence,int fullSize,boolean rawFullEmptyCursor,LoggingRestorationReceipt.Endpoint liveSource,
            LoggingRestorationReceipt.Endpoint liveHotbar,LoggingRestorationReceipt.Endpoint fullSource,
            LoggingRestorationReceipt.Endpoint fullHotbar,List<SlotUpdate> updates,boolean restored) {
        if(lease==null || !lease.valid() || fullSize!=46 || updates==null || fullSource==null || fullHotbar==null)return false;
        var packetSource=fullSource;var packetHotbar=fullHotbar;
        long sourceSequence=fullSequence,hotbarSequence=fullSequence;
        for(SlotUpdate update:updates) {
            if(update==null)return false;
            // -2 uses player-inventory indices, not menu slot numbers. It cannot
            // be silently relabelled as menu 0. Other menus are unrelated too.
            if(update.menuId()!=0 || update.seq()<=fullSequence)continue;
            if(update.slot()==lease.sourceIndex() && update.seq()>sourceSequence) {
                packetSource=update.packetItem();sourceSequence=update.seq();
            } else if(update.slot()==36+lease.hotbarSlot() && update.seq()>hotbarSequence) {
                packetHotbar=update.packetItem();hotbarSequence=update.seq();
            }
        }
        // Keep the FULL's actual sequence: post-dispatch slots cannot upgrade an
        // old FULL across a swap/manual-input barrier or prove an empty cursor.
        return proves(lease,generation,lastSwapGeneration,lastSwapSequence,fullSequence,rawFullEmptyCursor,
            liveSource,liveHotbar,packetSource,packetHotbar,restored);
    }

    static boolean proves(HotbarLease lease,long generation,long lastSwapGeneration,long lastSwapSequence,
            long fullSequence,boolean rawFullEmptyCursor,LoggingRestorationReceipt.Endpoint liveSource,
            LoggingRestorationReceipt.Endpoint liveHotbar,LoggingRestorationReceipt.Endpoint packetSource,
            LoggingRestorationReceipt.Endpoint packetHotbar) {
        return proves(lease,generation,lastSwapGeneration,lastSwapSequence,fullSequence,rawFullEmptyCursor,
            liveSource,liveHotbar,packetSource,packetHotbar,true);
    }
    static boolean provesParked(HotbarLease lease,long generation,long lastSwapGeneration,long lastSwapSequence,
            long fullSequence,boolean rawFullEmptyCursor,LoggingRestorationReceipt.Endpoint liveSource,
            LoggingRestorationReceipt.Endpoint liveHotbar,LoggingRestorationReceipt.Endpoint packetSource,
            LoggingRestorationReceipt.Endpoint packetHotbar) {
        return proves(lease,generation,lastSwapGeneration,lastSwapSequence,fullSequence,rawFullEmptyCursor,
            liveSource,liveHotbar,packetSource,packetHotbar,false);
    }
    private static boolean proves(HotbarLease lease,long generation,long lastSwapGeneration,long lastSwapSequence,
            long fullSequence,boolean rawFullEmptyCursor,LoggingRestorationReceipt.Endpoint liveSource,
            LoggingRestorationReceipt.Endpoint liveHotbar,LoggingRestorationReceipt.Endpoint packetSource,
            LoggingRestorationReceipt.Endpoint packetHotbar,boolean restored) {
        if(lease==null || !lease.valid() || !rawFullEmptyCursor || fullSequence<1
            || generation==lastSwapGeneration && fullSequence<=lastSwapSequence
            || liveSource==null || liveHotbar==null || packetSource==null || packetHotbar==null
            || liveSource.item()==null || liveHotbar.item()==null || liveSource.fingerprint()==null
            || !Objects.equals(liveSource,packetSource) || !Objects.equals(liveHotbar,packetHotbar))return false;
        // The restored work item may remain at the source; only the original's
        // exact identity may not appear at both participating endpoints.
        var original=restored ? liveHotbar : liveSource;
        var working=restored ? liveSource : liveHotbar;
        return lease.original().equals(original.item()) && lease.fingerprint().equals(original.fingerprint())
            && working.fingerprint()!=null && (working.item().empty() || !working.item().is(lease.original().id()));
    }
}
