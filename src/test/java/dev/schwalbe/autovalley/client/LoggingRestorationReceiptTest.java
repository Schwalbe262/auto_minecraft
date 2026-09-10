package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LoggingRestorationReceiptTest {
    private static ItemData item(String id,int count){return new ItemData(id,count,0,null,false,1000);}
    private static final ItemData SWORD=item("society:galaxy_sword",1);
    private static final LoggingHotbarLease LEASE=new LoggingHotbarLease(12,0,SWORD,"original-fingerprint");
    private static final LoggingRestorationReceipt.Endpoint EMPTY=new LoggingRestorationReceipt.Endpoint(ItemData.EMPTY,"empty");
    private static final LoggingRestorationReceipt.Endpoint ORIGINAL=new LoggingRestorationReceipt.Endpoint(SWORD,"original-fingerprint");
    private static boolean proof(LoggingHotbarLease lease,long generation,long seq,boolean full,
            LoggingRestorationReceipt.Endpoint source,LoggingRestorationReceipt.Endpoint hotbar,
            LoggingRestorationReceipt.Endpoint packetSource,LoggingRestorationReceipt.Endpoint packetHotbar){
        return LoggingRestorationReceipt.proves(lease,generation,5,100,seq,full,source,hotbar,packetSource,packetHotbar);
    }
    @Test void exactCurrentCustodyAndNewRawFullCanResolvePreparedOrParkedLeases(){
        for(var stage:LoggingHotbarLease.Stage.values())assertTrue(proof(LEASE.withStage(stage),5,101,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
    }
    @Test void nativeEmptyAndDetachedZeroCountAirHaveTheSameVacantCustody() {
        var live=new LoggingRestorationReceipt.Endpoint(ItemData.EMPTY,"native-empty-serialization");
        var raw=new LoggingRestorationReceipt.Endpoint(ItemData.EMPTY,"detached-air-serialization");
        assertEquals(live,raw);
        assertTrue(proof(LEASE,5,101,true,live,ORIGINAL,raw,ORIGINAL));
        var occupied=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.SAPLING,1),"native-empty-serialization");
        assertNotEquals(live,occupied);
    }
    @Test void saplingsMayRemainAtTheSourceOnlyWithExactLiveAndPacketAgreement(){
        var saplings=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.SAPLING,9),"saplings-9");
        assertTrue(proof(LEASE,5,101,true,saplings,ORIGINAL,saplings,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,saplings,ORIGINAL,EMPTY,ORIGINAL));
    }
    @Test void confirmedNaturalLoggingPickupsMayRemainAtTheSourceAfterExactOriginalRestoration() {
        for(String id:List.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.TWIG,LoggingRules.BERRY)) {
            var pickup=new LoggingRestorationReceipt.Endpoint(item(id,34),"native-pickup-"+id);
            for(var stage:LoggingHotbarLease.Stage.values())
                assertTrue(proof(LEASE.withStage(stage),5,101,true,pickup,ORIGINAL,pickup,ORIGINAL),id);
        }
    }
    @Test void loggingPickupPermissionNeverReplacesOriginalNativeIdentityOrEitherEndpointAgreement() {
        var logs=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.LOG,34),"logs-34");
        var differentCount=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.LOG,35),"logs-35");
        var differentTags=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.LOG,34),"logs-other-tags");
        var changedSword=new LoggingRestorationReceipt.Endpoint(SWORD,"sword-other-tags");
        assertFalse(proof(LEASE,5,101,true,logs,ORIGINAL,differentCount,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,logs,ORIGINAL,differentTags,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,logs,changedSword,logs,changedSword));
        assertFalse(proof(LEASE,5,101,true,logs,ORIGINAL,logs,changedSword));
    }
    @Test void naturalPickupStillRequiresPostDispatchNativeFullAndAnEmptyCursor() {
        var logs=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.LOG,34),"logs-34");
        for(long stale:new long[]{-1,0,99,100})assertFalse(proof(LEASE,5,stale,true,logs,ORIGINAL,logs,ORIGINAL));
        assertFalse(proof(LEASE,5,101,false,logs,ORIGINAL,logs,ORIGINAL));
        assertFalse(LoggingRestorationReceipt.proves(LEASE,5,5,102,101,true,logs,ORIGINAL,logs,ORIGINAL));
        assertTrue(proof(LEASE,6,1,true,logs,ORIGINAL,logs,ORIGINAL));
    }
    @Test void foreignPickupsAndOversizedLoggingStacksCannotProveRestoredCustody() {
        for(String id:List.of("minecraft:oak_log","minecraft:stone",ItemData.TOMATO,ItemData.WINE,"minecraft:diamond",SWORD.id())) {
            var foreign=new LoggingRestorationReceipt.Endpoint(item(id,1),"foreign");
            assertFalse(proof(LEASE,5,101,true,foreign,ORIGINAL,foreign,ORIGINAL),id);
        }
        var oversized=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.LOG,65),"oversized");
        assertFalse(proof(LEASE,5,101,true,oversized,ORIGINAL,oversized,ORIGINAL));
    }
    @Test void anOriginalLoggingByproductStillNeedsItsExactFingerprintWithoutAmbiguousDuplication() {
        ItemData originalTwig=item(LoggingRules.TWIG,17);
        var lease=new LoggingHotbarLease(12,0,originalTwig,"original-twig");
        var original=new LoggingRestorationReceipt.Endpoint(originalTwig,"original-twig");
        var pickup=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.TWIG,3),"pickup-twig-3");
        assertTrue(proof(lease,5,101,true,pickup,original,pickup,original));
        assertFalse(proof(lease,5,101,true,original,original,original,original));
    }
    @Test void preDispatchMissingAndNonNativeOrCursorEvidenceNeverProvesRestoration(){
        for(long seq:new long[]{-1,0,99,100})assertFalse(proof(LEASE,5,seq,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
        assertFalse(proof(LEASE,5,101,false,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
    }
    @Test void newConnectionRequiresAnActualNewFullButNotAnOldConnectionSequence(){
        assertTrue(proof(LEASE,6,1,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
        assertFalse(proof(LEASE,6,0,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
        assertFalse(proof(LEASE,6,1,false,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
    }
    @Test void sameItemIdWithChangedFingerprintCountOrOtherSourceCannotClearTheLease(){
        var changed=new LoggingRestorationReceipt.Endpoint(SWORD,"different-tags");
        assertFalse(proof(LEASE,5,101,true,EMPTY,changed,EMPTY,changed));
        var extra=new LoggingRestorationReceipt.Endpoint(item(SWORD.id(),2),"original-fingerprint");
        assertFalse(proof(LEASE,5,101,true,EMPTY,extra,EMPTY,extra));
        var stone=new LoggingRestorationReceipt.Endpoint(item("minecraft:stone",1),"stone");
        assertFalse(proof(LEASE,5,101,true,stone,ORIGINAL,stone,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,EMPTY,ORIGINAL,EMPTY,changed));
        assertFalse(proof(LEASE,5,101,true,EMPTY,changed,EMPTY,ORIGINAL));
    }
    @Test void newerServerContentsOverrideAnOlderMatchingViewEvenWhenLivePredictionMatchesOld(){
        var changed=new LoggingRestorationReceipt.Endpoint(SWORD,"different-server-tags");
        var latest=LoggingRestorationReceipt.latest(List.of(ORIGINAL,changed));
        assertFalse(proof(LEASE,5,102,true,EMPTY,ORIGINAL,EMPTY,latest));
        assertNull(LoggingRestorationReceipt.latest(List.of()));
        assertNull(LoggingRestorationReceipt.latest(null));
    }
    @Test void manualSlotInputRevokesEarlierCustodyEvidenceUntilANewerFullArrives(){
        assertFalse(LoggingRestorationReceipt.proves(LEASE,5,5,102,101,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
        assertTrue(LoggingRestorationReceipt.proves(LEASE,5,5,102,103,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
    }
    @Test void missingEvidenceAndInvalidLeaseCoordinatesFailClosed(){
        assertFalse(proof(null,5,101,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,null,ORIGINAL,EMPTY,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,EMPTY,ORIGINAL,EMPTY,null));
        for(int source:new int[]{-1,0,8,36})assertFalse(proof(new LoggingHotbarLease(source,0,SWORD,"original-fingerprint"),5,101,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
        for(int hotbar:new int[]{-1,9})assertFalse(proof(new LoggingHotbarLease(12,hotbar,SWORD,"original-fingerprint"),5,101,true,EMPTY,ORIGINAL,EMPTY,ORIGINAL));
    }
}
