package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Current count-only custody reconciliation, never a historical action acknowledgement. */
final class LoggingLeaseGrowthReceipt {
    /**
     * Both hashes come from the entire native stack serialization. The second
     * changes only its count back to the durable original's count before hashing.
     * Public ItemData fields alone cannot establish an unchanged native identity.
     */
    record Endpoint(ItemData item,String fingerprint,String originalCountFingerprint,int limit) {
        Endpoint {
            if(item!=null && item.id()!=null && item.count()>=0 && item.empty()) {
                item=ItemData.EMPTY;fingerprint="empty";originalCountFingerprint="empty";limit=64;
            }
        }
    }
    record SlotUpdate(long seq,long generation,int menuId,int slot,Endpoint packetItem) { }

    /** The caller supplies the newest actual FULL and complete subsequent raw slot history. */
    static LoggingHotbarLease reconcile(LoggingHotbarLease lease,long generation,long fullGeneration,
            long lastSwapGeneration,long lastSwapSequence,long fullSequence,int fullSize,
            boolean rawFullEmptyCursor,boolean completeSlotHistory,Endpoint liveSource,Endpoint liveHotbar,
            Endpoint fullSource,Endpoint fullHotbar,List<SlotUpdate> updates) {
        if(!validLease(lease) || generation!=fullGeneration || fullSize!=46 || !rawFullEmptyCursor
            || !completeSlotHistory || fullSequence<1 || generation==lastSwapGeneration && fullSequence<=lastSwapSequence
            || !valid(liveSource) || !valid(liveHotbar) || !valid(fullSource) || !valid(fullHotbar)
            || updates==null || updates.stream().anyMatch(Objects::isNull))return null;
        boolean parked=original(lease,fullSource) && working(lease,fullHotbar);
        boolean restored=original(lease,fullHotbar) && working(lease,fullSource);
        if(parked==restored)return null;
        int originalSlot=parked ? lease.sourceIndex() : 36+lease.hotbarSlot();
        int workingSlot=parked ? 36+lease.hotbarSlot() : lease.sourceIndex();
        Endpoint packetOriginal=parked ? fullSource : fullHotbar;
        Endpoint packetWorking=parked ? fullHotbar : fullSource;
        long previousSequence=fullSequence;
        for(SlotUpdate update:updates.stream().sorted(Comparator.comparingLong(SlotUpdate::seq)).toList()) {
            if(update.seq()<=fullSequence)continue;
            // A player-inventory-index packet or cursor packet cannot be silently
            // relabelled as a menu-0 endpoint. Missing raw history requires a FULL.
            if(update.generation()!=generation || update.menuId()!=0 || update.seq()<=previousSequence
                || update.slot()<0 || update.slot()>=46 || !valid(update.packetItem()))return null;
            previousSequence=update.seq();
            if(update.slot()==originalSlot) {
                Endpoint next=update.packetItem();
                if(!original(lease,next) || next.limit()!=packetOriginal.limit()
                    || next.item().count()<packetOriginal.item().count())return null;
                packetOriginal=next;
            } else if(update.slot()==workingSlot) {
                if(!working(lease,update.packetItem()))return null;
                packetWorking=update.packetItem();
            }
        }
        Endpoint liveOriginal=parked ? liveSource : liveHotbar;
        Endpoint liveWorking=parked ? liveHotbar : liveSource;
        if(!packetOriginal.equals(liveOriginal) || !packetWorking.equals(liveWorking)
            || liveOriginal.item().count()<=lease.original().count())return null;
        return new LoggingHotbarLease(lease.sourceIndex(),lease.hotbarSlot(),liveOriginal.item(),
            liveOriginal.fingerprint(),lease.stage());
    }

    private static boolean validLease(LoggingHotbarLease lease) {
        return lease!=null && lease.stage()!=null && lease.sourceIndex()>=9 && lease.sourceIndex()<=35
            && lease.hotbarSlot()>=0 && lease.hotbarSlot()<=8 && lease.original()!=null && lease.original().id()!=null
            && !lease.original().id().isBlank()
            && !lease.original().empty() && lease.original().count()<=64 && !lease.original().hoe()
            && !lease.original().is(LoggingRules.AXE) && !lease.original().is(LoggingRules.SAPLING)
            && hash(lease.fingerprint());
    }
    private static boolean valid(Endpoint endpoint) {
        if(endpoint==null || endpoint.item()==null || endpoint.item().id()==null || endpoint.item().id().isBlank()
            || endpoint.item().count()<0)return false;
        return endpoint.item().empty() || endpoint.limit()>=1 && endpoint.limit()<=64
            && endpoint.item().count()<=endpoint.limit() && hash(endpoint.fingerprint()) && hash(endpoint.originalCountFingerprint());
    }
    private static boolean original(LoggingHotbarLease lease,Endpoint endpoint) {
        ItemData current=endpoint.item(),old=lease.original();
        return !current.empty() && current.count()>=old.count() && current.count()<=endpoint.limit()
            && lease.fingerprint().equals(endpoint.originalCountFingerprint())
            && (current.count()==old.count())==endpoint.fingerprint().equals(endpoint.originalCountFingerprint())
            && old.equals(new ItemData(current.id(),old.count(),current.quality(),current.year(),current.hoe(),current.durability()));
    }
    private static boolean working(LoggingHotbarLease lease,Endpoint endpoint) {
        return LoggingRules.temporaryHotbarItem(endpoint.item())
            && (endpoint.item().empty() || !endpoint.item().is(lease.original().id()));
    }
    private static boolean hash(String hash) { return hash!=null && hash.matches("[0-9a-fA-F]{64}"); }
}
