package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoggingSafetyTest {
    private static final Pos BASE=new Pos(0,64,0);
    private static final LoggingPlot PLOT=new LoggingPlot("tree",BASE);
    private static final NativeLoggingTree.Cell AIR=new NativeLoggingTree.Cell(true,false,false,false,0);
    private static final NativeLoggingTree.Cell LOG=new NativeLoggingTree.Cell(true,true,true,false,0);
    private static final NativeLoggingTree.Cell LEAF=new NativeLoggingTree.Cell(true,false,false,true,0);
    private static Map<Pos,NativeLoggingTree.Cell> tree() {
        Map<Pos,NativeLoggingTree.Cell> cells=new HashMap<>();
        for (Pos base:PLOT.plantingPositions()) for(int y=0;y<20;y++) cells.put(base.offset(0,y,0),LOG);
        cells.put(BASE.offset(-1,20,0),LEAF); return cells;
    }
    private static NativeLoggingTree.Proof inspect(Map<Pos,NativeLoggingTree.Cell> cells) {
        return NativeLoggingTree.inspect(BASE,List.of(PLOT),p -> cells.getOrDefault(p,AIR));
    }
    @Test void recordedGiantSpruceColumnsWithNaturalLeavesAreAccepted() {
        var proof=inspect(tree()); assertTrue(proof.safe()); assertEquals(80,proof.chops().size());
    }
    @Test void onlyTheExactInspectedTreeChopVersionIsSupported() {
        assertTrue(NativeLoggingTree.supportedVersion("0.19.0"));
        for(String version:List.of("0.19.01","0.19.0-custom","0.19.0.1","0.19.1","0.18.0",""))
            assertFalse(NativeLoggingTree.supportedVersion(version));
        assertFalse(NativeLoggingTree.supportedVersion(null));
    }
    @Test void afterStopWaitingStillRequiresGroundedReachableSelectedAxe() {
        assertTrue(NativeLoggingActions.miningContinuationAllowed(true,true,false,true,true));
        assertFalse(NativeLoggingActions.miningContinuationAllowed(false,true,false,true,true));
        assertFalse(NativeLoggingActions.miningContinuationAllowed(true,false,false,true,true));
        assertFalse(NativeLoggingActions.miningContinuationAllowed(true,true,true,true,true));
        assertFalse(NativeLoggingActions.miningContinuationAllowed(true,true,false,false,true));
        assertFalse(NativeLoggingActions.miningContinuationAllowed(true,true,false,true,false));
    }
    private static Stack axe(String metadata) {
        return new Stack("{Count:1b,id:\"minecraft:netherite_axe\",tag:"+metadata+"}",1,1);
    }
    @Test void afterStopAllowsOnlyLegitimateWearWhileChopReplyIsPending() {
        assertTrue(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),axe("{Damage:13}")));
        assertTrue(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),axe("{Damage:12}")));
        assertTrue(NativeLoggingActions.axeAfterStop(axe("{}"),axe("{Damage:1}")));
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),axe("{Damage:11}")));
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),axe("{Damage:13,RepairCost:1}")));
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),axe("{Damage:\"13\"}")));
    }
    @Test void afterStopDoesNotAllowReplacementStackOrDifferentAxeMetadata() {
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),Stack.EMPTY));
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),item("minecraft:diamond_axe",1)));
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12}"),new Stack(axe("{Damage:13}").identity(),2,64)));
        assertFalse(NativeLoggingActions.axeAfterStop(axe("{Damage:12,Enchantments:[{id:\"minecraft:efficiency\",lvl:5s}]}"),axe("{Damage:13}")));
    }
    @Test void adjacentUnregisteredBuildingLogAndOtherSpeciesAreRejected() {
        var cells=tree(); cells.put(BASE.offset(2,0,0),LOG); assertFalse(inspect(cells).safe());
        cells=tree(); cells.put(BASE,new NativeLoggingTree.Cell(true,true,false,false,0)); assertFalse(inspect(cells).safe());
    }
    @Test void unloadedEnvelopeAndUnknownChoppedOriginalAreRejected() {
        var cells=tree(); cells.put(BASE.offset(7,32,7),new NativeLoggingTree.Cell(false,false,false,false,-1));
        assertFalse(inspect(cells).safe());
        cells=tree(); cells.put(BASE,new NativeLoggingTree.Cell(true,true,true,false,-1)); assertFalse(inspect(cells).safe());
    }
    @Test void barePostsOrConnectedBoundaryLogsAreNotAWholeTreeProof() {
        var cells=tree(); cells.remove(BASE.offset(-1,20,0)); assertFalse(inspect(cells).safe());
        var boundary=new LoggingPlot("neighbour",BASE.offset(8,0,0));
        cells=tree(); cells.put(boundary.corner(),LOG); var all=cells;
        assertFalse(NativeLoggingTree.inspect(BASE,List.of(PLOT,boundary),p -> all.getOrDefault(p,AIR)).safe());
    }
    @Test void permitRequiresExactObjectAndIsConsumedOnce() {
        NativeDestroyPermits p=new NativeDestroyPermits(); Object packet=new String("packet");
        p.grant(packet,1,10); assertFalse(p.consume(new String("packet"),1,11));
        assertTrue(p.consume(packet,1,11)); assertFalse(p.consume(packet,1,12));
    }
    @Test void expiredGenerationAndClearedPermitsCannotAuthorizeAttacks() {
        NativeDestroyPermits p=new NativeDestroyPermits(); Object packet=new Object();
        p.grant(packet,1,0); assertFalse(p.consume(packet,2,1));
        p.grant(packet,2,0); assertFalse(p.consume(packet,2,5_000_000_001L));
        p.grant(packet,2,10); p.clear(); assertFalse(p.consume(packet,2,11));
    }
    private static Stack item(String id,int count) { return new Stack("{Count:1b,id:\""+id+"\"}",count,64); }
    @Test void loggingSwapRequiresBothExactEndpointsNotAnUnrelatedPickup() {
        Stack sapling=item(LoggingRules.SAPLING,24),tool=item("minecraft:diamond_sword",1),log=item(LoggingRules.LOG,5);
        var before=List.of(sapling,tool,Stack.EMPTY);
        assertFalse(NativeLoggingSwap.swapped(before,List.of(sapling,tool,log),0,1));
        assertTrue(NativeLoggingSwap.swapped(before,List.of(tool,sapling,log),0,1));
        assertFalse(NativeLoggingSwap.swapped(before,List.of(tool,item(LoggingRules.SAPLING,23),log),0,1));
        assertFalse(NativeLoggingSwap.swapped(before,List.of(tool,sapling,log),0,0));
    }
    private static ItemData data(String id) { return new ItemData(id,1,0,null,false,100); }
    @Test void trashRecoveryBufferCanTransitionBetweenTheTwoAuthorizedWasteRoutines() {
        assertTrue(NativeTrashSlot.disposableBuffer(ItemData.EMPTY));
        assertFalse(NativeTrashSlot.disposableBuffer(null));
        for(String id:List.of(ItemData.ROTTEN,LoggingRules.TWIG,LoggingRules.SAPLING))
            assertTrue(NativeTrashSlot.disposableBuffer(data(id)));
        for(String id:List.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.BERRY,LoggingRules.AXE,
                ItemData.TOMATO,ItemData.WINE,ItemData.PRESERVES,ItemData.PINE_TAR,"minecraft:diamond_sword"))
            assertFalse(NativeTrashSlot.disposableBuffer(data(id)));
    }
    @Test void protectedTrashBufferNamesTheUnsentPrerequisiteWithoutDeletingOtherItems() {
        assertNull(NativeTrashSlot.bufferRejection(ItemData.EMPTY));
        for (String id:List.of(ItemData.ROTTEN,LoggingRules.TWIG,LoggingRules.SAPLING))
            assertNull(NativeTrashSlot.bufferRejection(data(id)));
        for (String id:List.of(ItemData.TOMATO,ItemData.WINE,"minecraft:diamond_sword")) {
            String refusal=NativeTrashSlot.bufferRejection(data(id));
            assertTrue(refusal.contains(id)); assertTrue(refusal.contains("삭제 요청은 보내지 않았습니다"));
        }
        assertNotNull(NativeTrashSlot.bufferRejection(null));
    }
}
