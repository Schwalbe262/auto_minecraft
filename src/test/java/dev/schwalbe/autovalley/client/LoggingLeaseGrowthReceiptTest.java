package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingLeaseGrowthReceiptTest {
    private static final String TAG="{custom:{owner:\"old\"},quality_food:{quality:2}}";
    private static final ItemData ORIGINAL=item(LoggingRules.FIRE_LOG,6);
    private static final String HASH=hash(raw(LoggingRules.FIRE_LOG,6,TAG));
    private static final LoggingHotbarLease LEASE=new LoggingHotbarLease(11,0,ORIGINAL,HASH,LoggingHotbarLease.Stage.PARKED);
    private static final LoggingLeaseGrowthReceipt.Endpoint EMPTY=endpoint(ItemData.EMPTY,"{}",64);
    private static final LoggingLeaseGrowthReceipt.Endpoint SAPLINGS=endpoint(item(LoggingRules.SAPLING,14),"{}",64);
    private static ItemData item(String id,int count) { return new ItemData(id,count,0,null,false,Integer.MAX_VALUE); }
    private static String raw(String id,int count,String tag) { return "{id:\""+id+"\",Count:"+count+"b,tag:"+tag+"}"; }
    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static LoggingLeaseGrowthReceipt.Endpoint endpoint(ItemData item,String tag,int limit) {
        return new LoggingLeaseGrowthReceipt.Endpoint(item,hash(raw(item.id(),item.count(),tag)),hash(raw(item.id(),6,tag)),limit);
    }
    private static LoggingLeaseGrowthReceipt.Endpoint original(int count) { return endpoint(item(LoggingRules.FIRE_LOG,count),TAG,64); }
    private static LoggingLeaseGrowthReceipt.SlotUpdate slot(long seq,int index,LoggingLeaseGrowthReceipt.Endpoint item) {
        return new LoggingLeaseGrowthReceipt.SlotUpdate(seq,5,0,index,item);
    }
    private static final class Fixture {
        LoggingHotbarLease lease=LEASE;
        long generation=5,fullGeneration=5,lastGeneration=5,lastSequence=100,fullSequence=101;
        int size=46;
        boolean emptyCursor=true,completeHistory=true;
        LoggingLeaseGrowthReceipt.Endpoint liveSource=original(9),liveHotbar=SAPLINGS;
        LoggingLeaseGrowthReceipt.Endpoint fullSource=liveSource,fullHotbar=liveHotbar;
        List<LoggingLeaseGrowthReceipt.SlotUpdate> updates=List.of();
        LoggingHotbarLease proof() {
            return LoggingLeaseGrowthReceipt.reconcile(lease,generation,fullGeneration,lastGeneration,lastSequence,
                fullSequence,size,emptyCursor,completeHistory,liveSource,liveHotbar,fullSource,fullHotbar,updates);
        }
        Fixture restored() {
            liveSource=fullSource=SAPLINGS;liveHotbar=fullHotbar=original(9);return this;
        }
    }

    @Test void rawFullGrowthRebasesOnlyCountAndFullFingerprintForEitherLayoutAndEveryDurableStage() {
        for(var stage:LoggingHotbarLease.Stage.values())for(boolean restored:List.of(false,true)) {
            Fixture f=new Fixture();if(restored)f.restored();f.lease=LEASE.withStage(stage);
            LoggingHotbarLease rebased=f.proof();assertNotNull(rebased);
            assertEquals(original(9).item(),rebased.original());assertEquals(original(9).fingerprint(),rebased.fingerprint());
            assertEquals(11,rebased.sourceIndex());assertEquals(0,rebased.hotbarSlot());assertEquals(stage,rebased.stage());
            assertEquals(6,f.lease.original().count());assertEquals(HASH,f.lease.fingerprint());
        }
    }
    @Test void fullAndAllLaterRawSlotsCanEstablishNondecreasingGrowthAtEitherEndpoint() {
        for(boolean restored:List.of(false,true)) {
            Fixture f=new Fixture();if(restored)f.restored();
            if(restored)f.fullHotbar=original(6);else f.fullSource=original(6);
            int index=restored ? 36 : 11;
            f.updates=List.of(slot(104,index,original(9)),slot(102,index,original(7)),slot(103,index,original(7)));
            assertNotNull(f.proof());
            f.updates=List.of(slot(102,index,original(10)),slot(103,index,original(9)));
            assertNull(f.proof(),"Even a later net gain cannot excuse a witnessed decrease");
        }
    }
    @Test void originalGrowthMayReachNativeLimitButCannotShrinkRemainEqualDisappearOrExceedBounds() {
        for(int count:List.of(-1,0,1,5,6,65,127)) {
            Fixture f=new Fixture();f.liveSource=f.fullSource=original(count);assertNull(f.proof(),"count "+count);
        }
        Fixture f=new Fixture();f.liveSource=f.fullSource=original(64);assertNotNull(f.proof());
        f.liveSource=f.fullSource=endpoint(item(LoggingRules.FIRE_LOG,16),TAG,16);assertNotNull(f.proof());
        for(int limit:List.of(0,1,8,65,128)) {
            f.liveSource=f.fullSource=endpoint(item(LoggingRules.FIRE_LOG,9),TAG,limit);assertNull(f.proof(),"limit "+limit);
        }
        f.fullSource=endpoint(item(LoggingRules.FIRE_LOG,7),TAG,16);f.liveSource=original(9);
        f.updates=List.of(slot(102,11,f.liveSource));assertNull(f.proof(),"Native limit cannot change between observations");
    }
    @Test void identicalPublicProjectionNeverExcusesAnyNativeTagChange() {
        for(String tag:List.of("{}","{custom:{owner:\"new\"},quality_food:{quality:2}}",
                "{custom:{owner:\"old\"},quality_food:{quality:1}}","{Damage:1}","{ForgeCaps:{custom:1}}")) {
            Fixture f=new Fixture();f.liveSource=f.fullSource=endpoint(original(9).item(),tag,64);assertNull(f.proof());
            f.fullSource=original(6);f.updates=List.of(slot(102,11,f.liveSource));assertNull(f.proof());
        }
    }
    @Test void publicProjectionAndHashNormalizationMustBothMatchTheDurableOriginal() {
        for(ItemData wrong:List.of(item("minecraft:oak_log",9),new ItemData(ORIGINAL.id(),9,1,null,false,Integer.MAX_VALUE),
                new ItemData(ORIGINAL.id(),9,0,7,false,Integer.MAX_VALUE),new ItemData(ORIGINAL.id(),9,0,null,true,Integer.MAX_VALUE),
                new ItemData(ORIGINAL.id(),9,0,null,false,9))) {
            Fixture f=new Fixture();f.liveSource=f.fullSource=new LoggingLeaseGrowthReceipt.Endpoint(wrong,original(9).fingerprint(),HASH,64);
            assertNull(f.proof());
        }
        Fixture f=new Fixture();f.liveSource=f.fullSource=new LoggingLeaseGrowthReceipt.Endpoint(original(9).item(),HASH,HASH,64);
        assertNull(f.proof(),"An unchanged saved count hash cannot be the increased stack's full hash");
    }
    @Test void temporaryEndpointMustRemainEmptyOrDistinctLoggingItemWithItsExactLatestNativePacket() {
        for(String id:List.of(LoggingRules.SAPLING,LoggingRules.LOG,LoggingRules.TWIG,LoggingRules.BERRY)) {
            Fixture f=new Fixture();f.liveHotbar=f.fullHotbar=endpoint(item(id,3),"{}",64);assertNotNull(f.proof());
        }
        Fixture f=new Fixture();f.liveHotbar=f.fullHotbar=EMPTY;assertNotNull(f.proof());
        for(var wrong:List.of(original(1),original(9),endpoint(item(LoggingRules.FIRE_LOG,2),"{other:1}",64),
                endpoint(item("minecraft:diamond",1),"{}",64),endpoint(item(LoggingRules.SAPLING,65),"{}",64))) {
            f.liveHotbar=f.fullHotbar=wrong;assertNull(f.proof());
        }
        f=new Fixture();f.fullHotbar=EMPTY;assertNull(f.proof());
        f.updates=List.of(slot(102,36,SAPLINGS));assertNotNull(f.proof());
        f.updates=List.of(slot(102,36,SAPLINGS),slot(103,36,EMPTY));assertNull(f.proof());
    }
    @Test void newerNegativeEndpointsCannotBeIgnoredEvenIfAnOlderSnapshotOrClientPredictionMatches() {
        Fixture f=new Fixture();f.fullSource=original(6);assertNull(f.proof());
        f.updates=List.of(slot(102,11,original(9)));assertNotNull(f.proof());
        for(var latest:List.of(original(7),EMPTY,endpoint(original(9).item(),"{changed:1}",64))) {
            f.updates=List.of(slot(102,11,original(9)),slot(103,11,latest));assertNull(f.proof());
        }
        f.fullSequence=105;f.fullSource=EMPTY;f.updates=List.of(slot(104,11,original(9)));assertNull(f.proof());
        f.fullSource=original(6);assertNull(f.proof(),"Slots older than latest FULL cannot override it");
    }
    @Test void latestFullMustFollowSwapOrManualBoundaryAndOwnAnEmptyCursorAndNormalShape() {
        for(long seq:List.of(-1L,0L,99L,100L)) {
            Fixture f=new Fixture();f.fullSequence=seq;f.updates=List.of(slot(102,11,original(9)));assertNull(f.proof());
        }
        Fixture f=new Fixture();f.lastSequence=101;assertNull(f.proof());
        f=new Fixture();f.emptyCursor=false;assertNull(f.proof());
        for(int size:List.of(0,36,45,47)) { f=new Fixture();f.size=size;assertNull(f.proof()); }
        f=new Fixture();f.completeHistory=false;assertNull(f.proof(),"Dropped packets or missing raw cursor/index packets cannot be skipped");
    }
    @Test void connectionChangesCannotBorrowOldFullOrSlotEvidenceButANewFullCanReconcileDurableCustody() {
        Fixture f=new Fixture();f.generation=6;assertNull(f.proof());
        f.fullGeneration=6;f.fullSequence=1;assertNotNull(f.proof());
        f.fullSource=original(6);f.updates=List.of(slot(2,11,original(9)));assertNull(f.proof());
        f.updates=List.of(new LoggingLeaseGrowthReceipt.SlotUpdate(2,6,0,11,original(9)));assertNotNull(f.proof());
        f.fullSequence=0;assertNull(f.proof());
    }
    @Test void malformedOrAmbiguousRawUpdatesFailClosed() {
        for(var update:List.of(new LoggingLeaseGrowthReceipt.SlotUpdate(102,5,-2,11,original(9)),
                new LoggingLeaseGrowthReceipt.SlotUpdate(102,5,-1,-1,EMPTY),slot(102,-1,EMPTY),slot(102,46,EMPTY),
                slot(102,11,null))) {
            Fixture f=new Fixture();f.updates=List.of(update);assertNull(f.proof());
        }
        Fixture f=new Fixture();f.updates=Arrays.asList((LoggingLeaseGrowthReceipt.SlotUpdate)null);assertNull(f.proof());
        f.updates=null;assertNull(f.proof());
        f.updates=List.of(slot(102,11,original(9)),slot(102,11,original(9)));assertNull(f.proof());
        f.updates=List.of(slot(102,20,endpoint(item(LoggingRules.TWIG,3),"{}",64)));assertNotNull(f.proof());
    }
    @Test void malformedLeasesAndEndpointsCannotCreateARebaseline() {
        List<LoggingHotbarLease> invalid=new ArrayList<>();invalid.add(null);
        for(int index:List.of(-1,0,8,36))invalid.add(new LoggingHotbarLease(index,0,ORIGINAL,HASH));
        for(int hotbar:List.of(-1,9))invalid.add(new LoggingHotbarLease(11,hotbar,ORIGINAL,HASH));
        for(String hash:Arrays.asList(null,"","wrong","g".repeat(64)))invalid.add(new LoggingHotbarLease(11,0,ORIGINAL,hash));
        invalid.add(new LoggingHotbarLease(11,0,ORIGINAL,HASH,null));
        for(ItemData original:Arrays.asList(null,ItemData.EMPTY,item(LoggingRules.SAPLING,6),item(LoggingRules.AXE,1),
                item(LoggingRules.FIRE_LOG,65),new ItemData(null,6,0,null,false,1)))
            invalid.add(new LoggingHotbarLease(11,0,original,HASH));
        for(var lease:invalid) { Fixture f=new Fixture();f.lease=lease;assertNull(f.proof()); }
        for(var endpoint:Arrays.asList(null,new LoggingLeaseGrowthReceipt.Endpoint(null,HASH,HASH,64),
                new LoggingLeaseGrowthReceipt.Endpoint(new ItemData(null,9,0,null,false,1),HASH,HASH,64),
                new LoggingLeaseGrowthReceipt.Endpoint(original(9).item(),"invalid",HASH,64))) {
            Fixture f=new Fixture();f.liveSource=f.fullSource=endpoint;assertNull(f.proof());
        }
    }
}
