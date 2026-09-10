package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.Objects;

/** Current custody only, independent of feature switches and cancelled action outcomes. */
final class WorkHotbarRestorationReceipt {
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
