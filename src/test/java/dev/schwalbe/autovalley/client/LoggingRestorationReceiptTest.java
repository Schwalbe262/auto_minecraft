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
    @Test void saplingsMayRemainAtTheSourceOnlyWithExactLiveAndPacketAgreement(){
        var saplings=new LoggingRestorationReceipt.Endpoint(item(LoggingRules.SAPLING,9),"saplings-9");
        assertTrue(proof(LEASE,5,101,true,saplings,ORIGINAL,saplings,ORIGINAL));
        assertFalse(proof(LEASE,5,101,true,saplings,ORIGINAL,EMPTY,ORIGINAL));
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
