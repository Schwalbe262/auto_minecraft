package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import dev.schwalbe.autovalley.core.ItemData;
import dev.schwalbe.autovalley.core.ActionOutcome;
import dev.schwalbe.autovalley.core.InventoryAcknowledgements;
import dev.schwalbe.autovalley.core.ItemSlot;
import dev.schwalbe.autovalley.core.MenuData;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerObservationsTest {
    private static ItemData tomato(int count) { return new ItemData(ItemData.TOMATO,count,0,null,false,999); }

    @Test void nativeLoggingObserverIsExclusiveAndOnlyRunsForActualLoggingEvidence() {
        ServerObservations observations=new ServerObservations();int[] calls={0};
        assertThrows(NullPointerException.class,()->observations.observeNativeLogging(null));
        observations.observeNativeLogging(()->calls[0]++);
        assertThrows(IllegalStateException.class,()->observations.observeNativeLogging(()->fail("replaced observer")));
        observations.menu(0);observations.fullMenu(0);observations.fullMenu(0,List.of(ItemData.EMPTY));
        observations.block(new Pos(1,64,1));observations.blockActionsProcessed(0);observations.blockActionsProcessed(-1);
        assertEquals(0,calls[0],"Markers, reduced menus and invalid packet numbers are not proof");
        observations.chop(new Pos(1,64,1),1,1);observations.snowPlant(new Pos(1,64,1),true);observations.blockActionsProcessed(7);
        assertEquals(3,calls[0]);observations.clear();assertEquals(3,calls[0]);
        assertThrows(IllegalStateException.class,()->observations.observeNativeLogging(()->fail("replaced after reconnect")));
    }

    @Test void processedSequenceKeepsItsActualPacketBoundaryAndResetsWithTheConnection() {
        ServerObservations observations=new ServerObservations();assertNull(observations.nativeBlockActionsProcessed());
        observations.blockActionsProcessed(-1);observations.blockActionsProcessed(0);assertEquals(0,observations.sequence());
        observations.blockActionsProcessed(17);var first=observations.nativeBlockActionsProcessed();
        assertEquals(new LoggingActionReceipt.Processed(1,17),first);
        observations.blockActionsProcessed(12);assertSame(first,observations.nativeBlockActionsProcessed());
        assertEquals(2,observations.sequence());
        assertFalse(LoggingActionReceipt.cancelledBeforeStop(false,true,false,true,true,true,
            0,0,0,1,17,observations.nativeBlockActionsProcessed()),"A later lower ACK cannot give an old high watermark a new timestamp");
        observations.blockActionsProcessed(18);assertEquals(new LoggingActionReceipt.Processed(3,18),observations.nativeBlockActionsProcessed());
        assertTrue(LoggingActionReceipt.cancelledBeforeStop(false,true,false,true,true,true,
            0,0,0,1,18,observations.nativeBlockActionsProcessed()));
        observations.clear();assertNull(observations.nativeBlockActionsProcessed());assertEquals(0,observations.sequence());
    }

    @Test void successfulForwardNotificationRetainsExactIdentityWithoutInventingServerProof() {
        ServerObservations observations=new ServerObservations();Object packet=new Object();Object[] seen={null};
        assertThrows(NullPointerException.class,()->observations.observeLoggingPacketForwarded(null));
        observations.observeLoggingPacketForwarded(value->seen[0]=value);
        assertThrows(IllegalStateException.class,()->observations.observeLoggingPacketForwarded(value->fail("replaced")));
        observations.loggingPacketForwarded(packet);assertSame(packet,seen[0]);
        assertNull(observations.nativeBlockActionsProcessed());assertEquals(0,observations.sequence());
        assertThrows(NullPointerException.class,()->observations.loggingPacketForwarded(null));
        observations.clear();assertThrows(IllegalStateException.class,()->observations.observeLoggingPacketForwarded(value->fail("replaced")));
    }

    @Test void passiveLoggingReceiptLatchesBeforeBoundedHistoryEvictionAndWhileOrdinaryPollingIsPaused() {
        ServerObservations observations=new ServerObservations();var receipt=new LoggingActionReceipt(observations.generation());
        Pos target=new Pos(1,64,1);int[] reads={0};
        observations.observeNativeLogging(()->receipt.confirmed(observations.generation(),()->{
            reads[0]++;return observations.nativeChopsSince(0).stream().anyMatch(ack->ack.seq()==1 && ack.chops()==1);
        }));
        observations.chop(target,1,1);
        for(int count=0;count<1100;count++)observations.chop(target,0,1);
        assertTrue(observations.nativeChopsSince(0).stream().noneMatch(ack->ack.seq()==1));
        assertEquals(1,reads[0]);assertTrue(receipt.confirmed(observations.generation(),()->false));
        observations.clear();assertFalse(receipt.confirmed(observations.generation(),()->{throw new AssertionError("old connection");}));
    }

    @Test void nativeReceiptObserverCannotBeReplacedOrTriggeredByReducedEvidence() {
        ServerObservations observations=new ServerObservations();int[] calls={0};
        assertThrows(NullPointerException.class,() -> observations.observeNativeFullMenus(null));
        observations.observeNativeFullMenus(() -> calls[0]++);
        assertThrows(IllegalStateException.class,() -> observations.observeNativeFullMenus(() -> fail("replaced")));
        observations.menu(0);observations.fullMenu(0);observations.fullMenu(0,List.of(tomato(11)));
        observations.clear();observations.fullMenu(0,List.of(tomato(13)));
        assertEquals(0,calls[0],"No native FULL was received; markers and reconnect are not proof");
        assertThrows(IllegalStateException.class,() -> observations.observeNativeFullMenus(() -> fail("replaced after reconnect")));
    }

    @Test void incompleteSingleSlotEvidenceCannotAdvanceOrOverwriteAnyAcknowledgement() {
        ServerObservations observations=new ServerObservations();
        observations.fullMenu(0,List.of(tomato(10))); long before=observations.sequence();
        assertThrows(NullPointerException.class,() -> observations.nativeSlot(0,9,null,List.of(),null));
        assertEquals(before,observations.sequence());
        assertTrue(observations.nativeSlotSnapshotsSince(0,0).isEmpty());
        assertEquals(10,observations.fullMenuSnapshotSince(0,0).items().get(0).count());
    }

    @Test void ordinaryMenuMarkersDoNotInventAuthoritativeSingleSlotEvidence() {
        ServerObservations observations=new ServerObservations();
        observations.menu(0); observations.menu(-2); observations.fullMenu(0,List.of(ItemData.EMPTY));
        assertTrue(observations.nativeSlotSnapshotsSince(0,0).isEmpty());
        assertTrue(observations.nativeSlotSnapshotsSince(-2,0).isEmpty());
        observations.clear();
        assertTrue(observations.nativeSlotSnapshotsSince(0,0).isEmpty());
    }

    @Test void uncapturedInventoryAndCursorPacketsRequireANewerFullForGrowthCustody() {
        for(int id:List.of(0,-1,-2)) {
            ServerObservations observations=new ServerObservations();observations.fullMenu(0,List.of(ItemData.EMPTY));
            long full=observations.sequence();assertTrue(observations.nativeInventorySlotsCompleteSince(full));
            observations.menu(id);assertFalse(observations.nativeInventorySlotsCompleteSince(full));
            observations.fullMenu(0,List.of(ItemData.EMPTY));assertTrue(observations.nativeInventorySlotsCompleteSince(observations.sequence()));
            observations.menu(id);observations.clear();observations.fullMenu(0,List.of(ItemData.EMPTY));
            assertTrue(observations.nativeInventorySlotsCompleteSince(observations.sequence()),"Reconnect must clear old sequence barriers");
        }
        ServerObservations observations=new ServerObservations();observations.fullMenu(0,List.of(ItemData.EMPTY));
        long full=observations.sequence();observations.menu(7);assertTrue(observations.nativeInventorySlotsCompleteSince(full));
        assertFalse(observations.nativeInventorySlotsCompleteSince(0));
    }

    @Test void snapshotCopyDetachesMutableValuesAndNestedMetadataOnEveryRead() {
        // Native ItemStacks need a launched Forge registry. Exercise the exact generic copy
        // path they use with mutable count/tag stand-ins in this ordinary JVM test suite.
        final class MutableValue {
            int count;
            final Map<String,Integer> tag;
            MutableValue(int count,Map<String,Integer> tag) { this.count=count; this.tag=new HashMap<>(tag); }
            MutableValue copy() { return new MutableValue(count,tag); }
        }
        MutableValue packetItem=new MutableValue(64,Map.of("Year",7));
        List<MutableValue> packetItems=new ArrayList<>(List.of(packetItem));
        var retained=ServerObservations.copySnapshotItems(packetItems,MutableValue::copy);
        packetItem.count=1; packetItem.tag.put("Year",8); packetItems.clear();
        assertEquals(64,retained.get(0).count); assertEquals(7,retained.get(0).tag.get("Year"));
        var firstRead=ServerObservations.copySnapshotItems(retained,MutableValue::copy);
        firstRead.get(0).count=2; firstRead.get(0).tag.clear();
        var secondRead=ServerObservations.copySnapshotItems(retained,MutableValue::copy);
        assertNotSame(retained.get(0),secondRead.get(0)); assertNotSame(firstRead.get(0),secondRead.get(0));
        assertEquals(64,secondRead.get(0).count); assertEquals(7,secondRead.get(0).tag.get("Year"));
        assertThrows(UnsupportedOperationException.class,() -> firstRead.clear());
        assertThrows(UnsupportedOperationException.class,() -> retained.clear());
    }

    @Test void snapshotCopyRejectsMissingValuesRatherThanRetainingMutableAliases() {
        assertThrows(NullPointerException.class,() -> ServerObservations.<String>copySnapshotItems(null,String::new));
        assertThrows(NullPointerException.class,() -> ServerObservations.copySnapshotItems(List.of("slot"),null));
        assertThrows(NullPointerException.class,() -> ServerObservations.copySnapshotItems(Arrays.asList((String)null),String::new));
        assertThrows(NullPointerException.class,() -> ServerObservations.copySnapshotItems(List.of("slot"),item -> null));
    }

    @Test void mismatchedNativeAndReducedPacketSizesCannotAdvanceOrOverwriteAnAck() {
        ServerObservations observations=new ServerObservations();
        observations.fullMenu(4,List.of(tomato(64))); long before=observations.sequence();
        assertThrows(IllegalArgumentException.class,() -> observations.fullMenu(4,List.of(tomato(1)),List.of(),null));
        assertEquals(before,observations.sequence());
        assertEquals(64,observations.fullMenuSnapshotSince(4,0).items().get(0).count());
        assertTrue(observations.fullNativeMenuSnapshotsSince(4,0).isEmpty());
    }

    @Test void missingNativeCursorCannotAdvanceOrOverwriteAnAck() {
        ServerObservations observations=new ServerObservations();
        observations.fullMenu(4,List.of(tomato(64))); long before=observations.sequence();
        assertThrows(NullPointerException.class,() -> observations.fullMenu(4,List.of(),List.of(),null));
        assertEquals(before,observations.sequence());
        assertEquals(64,observations.fullMenuSnapshotSince(4,0).items().get(0).count());
        assertTrue(observations.fullNativeMenuSnapshotsSince(4,0).isEmpty());
    }

    @Test void acknowledgedEmptySlotSurvivesInstantLiveInventoryRefill() {
        ServerObservations observations=new ServerObservations(); long sent=observations.sequence();
        List<ItemData> packetSlots=new ArrayList<>(List.of(tomato(64),ItemData.EMPTY));
        observations.fullMenu(4,packetSlots);
        packetSlots.set(1,tomato(64)); // A following pickup refills the same live player slot.
        ServerObservations.FullMenuSnapshot ack=observations.fullMenuSnapshotSince(4,sent);
        assertNotNull(ack); assertTrue(ack.items().get(1).empty()); assertEquals(64,ack.items().get(0).count());
        assertThrows(UnsupportedOperationException.class,() -> ack.items().set(1,tomato(64)));
    }

    @Test void snapshotsRequireExactMenuAndNewerSequenceAndReplaceOnlyTheirOwnMenu() {
        ServerObservations observations=new ServerObservations();
        observations.fullMenu(4,List.of(tomato(64))); long sent=observations.sequence();
        observations.menu(-2); observations.fullMenu(5,List.of(tomato(2)));
        assertNull(observations.fullMenuSnapshotSince(4,sent));
        observations.fullMenu(4,List.of(tomato(3)));
        ServerObservations.FullMenuSnapshot ack=observations.fullMenuSnapshotSince(4,sent);
        assertNotNull(ack); assertEquals(3,ack.items().get(0).count()); assertEquals(observations.sequence(),ack.seq());
        assertEquals(2,observations.fullMenuSnapshotSince(5,sent).items().get(0).count());
        assertNull(observations.fullMenuSnapshotSince(4,ack.seq()));
    }

    @Test void firstAuthoritativeTransferSurvivesLaterFullRefillBeforeClientTick() {
        ServerObservations observations=new ServerObservations();
        MenuData before=new MenuData(4,0,List.of(new ItemSlot(0,-1,false,ItemData.EMPTY),
            new ItemSlot(1,0,true,tomato(64))),ItemData.EMPTY,true);
        observations.fullMenu(4,List.of(ItemData.EMPTY,tomato(64)));
        long sent=observations.sequence();
        observations.fullMenu(4,List.of(tomato(64),ItemData.EMPTY)); // The one click's server reply.
        observations.fullMenu(4,List.of(tomato(64),tomato(64))); // A later pickup, then full refresh.
        assertEquals(0,InventoryAcknowledgements.removed(before,observations.fullMenuSnapshotSince(4,sent).items(),1));
        var acknowledgements=observations.fullMenuSnapshotsSince(4,sent);
        assertEquals(2,acknowledgements.size());
        assertTrue(acknowledgements.get(0).seq()<acknowledgements.get(1).seq());
        assertEquals(64,InventoryAcknowledgements.removed(before,acknowledgements.get(0).items(),1));
        assertEquals(64,InventoryAcknowledgements.destinationIncrease(before,acknowledgements.get(0).items(),tomato(64)));
        assertThrows(UnsupportedOperationException.class,() -> acknowledgements.clear());
        observations.fullMenu(4,List.of(tomato(64),tomato(32)));
        assertEquals(2,acknowledgements.size()); // Returned history is detached from future packets.
    }

    @Test void historiesBoundEightPerMenuAndNeverIncludeOtherOrPreBaselinePackets() {
        ServerObservations observations=new ServerObservations();
        for (int count=1;count<=10;count++) observations.fullMenu(4,List.of(tomato(count)));
        var history=observations.fullMenuSnapshotsSince(4,0);
        assertEquals(8,history.size());
        assertEquals(3,history.get(0).items().get(0).count());
        assertEquals(10,history.get(7).items().get(0).count());
        long sent=observations.sequence(); observations.fullMenu(5,List.of(tomato(64))); observations.menu(-2);
        assertTrue(observations.fullMenuSnapshotsSince(4,sent).isEmpty());
        observations.fullMenu(4,List.of(tomato(11)));
        assertEquals(List.of(11),observations.fullMenuSnapshotsSince(4,sent).stream().map(s -> s.items().get(0).count()).toList());
        for (int id=6;id<=260;id++) observations.fullMenu(id,List.of(tomato(1)));
        assertTrue(observations.fullMenuSnapshotsSince(5,0).isEmpty());
        assertEquals(8,observations.fullMenuSnapshotsSince(4,0).size());
        observations.fullMenu(261,List.of(tomato(1)));
        assertTrue(observations.fullMenuSnapshotsSince(4,0).isEmpty());
    }

    @Test void dataLessMarkerAndReconnectInvalidateEntireHistory() {
        ServerObservations observations=new ServerObservations();
        observations.fullMenu(4,List.of(tomato(64))); observations.fullMenu(4,List.of(ItemData.EMPTY));
        observations.fullMenu(4);
        assertTrue(observations.fullMenuSnapshotsSince(4,0).isEmpty());
        assertNull(observations.fullMenuSnapshotSince(4,0));
        observations.fullMenu(4,List.of(tomato(1)));
        assertEquals(List.of(1),observations.fullMenuSnapshotsSince(4,0).stream().map(s -> s.items().get(0).count()).toList());
        observations.clear();
        assertTrue(observations.fullMenuSnapshotsSince(4,0).isEmpty());
    }

    @Test void legacyMarkerCannotReuseOldSlotDataAndReconnectClearsSnapshots() {
        ServerObservations observations=new ServerObservations(); observations.fullMenu(4,List.of(tomato(64)));
        observations.fullMenu(4);
        assertTrue(observations.fullMenuSince(4,0)); assertNull(observations.fullMenuSnapshotSince(4,0));
        observations.fullMenu(4,List.of(tomato(3))); observations.clear();
        assertNull(observations.fullMenuSnapshotSince(4,0)); assertFalse(observations.fullMenuSince(4,0));
    }

    @Test void snapshotRetentionIsBoundedAndUsesLatestSnapshotWhenMenuIdsAreReused() {
        ServerObservations observations=new ServerObservations();
        for (int id=0;id<257;id++) observations.fullMenu(id,List.of(tomato(1)));
        assertNull(observations.fullMenuSnapshotSince(0,0)); assertNotNull(observations.fullMenuSnapshotSince(1,0));
        observations.fullMenu(1,List.of(tomato(7))); observations.fullMenu(257,List.of(tomato(1)));
        assertEquals(7,observations.fullMenuSnapshotSince(1,0).items().get(0).count());
        assertNull(observations.fullMenuSnapshotSince(2,0));
    }

    @Test void actionOutcomeRetainsCompatibilityAndCarriesTheAuthoritativeTransferCount() {
        assertEquals(0,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"legacy").confirmedCount());
        ActionOutcome result=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"confirmed",64);
        assertTrue(result.done()); assertTrue(result.success()); assertEquals(64,result.confirmedCount());
    }
    @Test void unrelatedOrPredictedSlotChangesCannotConfirmInventoryTransaction() {
        ServerObservations observations=new ServerObservations();
        long sent=observations.sequence();
        observations.menu(-2);
        observations.menu(4);
        observations.fullMenu(5);
        assertFalse(observations.fullMenuSince(4,sent));
        observations.fullMenu(4);
        assertTrue(observations.fullMenuSince(4,sent));
        assertFalse(observations.fullMenuSince(4,observations.sequence()));
    }
    @Test void reconnectInvalidatesOldServerEvidence() {
        ServerObservations observations=new ServerObservations();
        long generation=observations.generation();
        observations.fullMenu(3);
        observations.block(new Pos(1,64,2));
        observations.clear();
        assertNotEquals(generation,observations.generation());
        assertFalse(observations.fullMenuSince(3,0));
        assertFalse(observations.blockSince(new Pos(1,64,2),0));
    }
    @Test void onlyTargetBlockUpdateConfirmsAWorldInteraction() {
        ServerObservations observations=new ServerObservations();
        Pos tomato=new Pos(1,64,2), neighbor=new Pos(2,64,2);
        long sent=observations.sequence();
        observations.block(neighbor);
        assertFalse(observations.blockSince(tomato,sent));
        observations.block(tomato);
        assertTrue(observations.blockSince(tomato,sent));
    }
}
