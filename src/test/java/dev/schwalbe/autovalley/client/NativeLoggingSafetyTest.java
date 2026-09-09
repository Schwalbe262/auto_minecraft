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
    @Test void priorChopWearMayArriveAfterTheNextStartWithoutInvalidatingTheOtherwiseExactAxe() {
        Stack admitted=axe("{Damage:1645,Enchantments:[{id:\"minecraft:efficiency\",lvl:5s}],RepairCost:2}");
        Stack delayedWear=axe("{Damage:1646,Enchantments:[{id:\"minecraft:efficiency\",lvl:5s}],RepairCost:2}");
        assertTrue(NativeLoggingActions.axeWearCompatible(admitted,delayedWear));
        assertNull(NativeLoggingActions.miningContinuationRejection(true,true,false,true,true,true,
            NativeLoggingActions.axeWearCompatible(admitted,delayedWear)));
        // The comparator changes only the waiting boundary. A current ray failure
        // remains a failure before STOP even when the axe wear is legitimate.
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,false,false,true,true,true).contains("REACH"));
        assertTrue(NativeLoggingActions.axeWearCompatible(admitted,admitted));
    }
    @Test void preStopWearCompatibilityDoesNotAcceptRepairReplacementOrAnyOtherNativeTagChange() {
        Stack before=axe("{Damage:1645,RepairCost:2,custom:{marker:7}}");
        for(Stack changed:List.of(axe("{Damage:1644,RepairCost:2,custom:{marker:7}}"),
            axe("{Damage:1646,RepairCost:3,custom:{marker:7}}"),axe("{Damage:1646,RepairCost:2,custom:{marker:8}}"),
            axe("{Damage:1646,RepairCost:2}"),axe("{Damage:1646s,RepairCost:2,custom:{marker:7}}"),
            item("minecraft:diamond_axe",1),Stack.EMPTY,new Stack(before.identity(),2,64),new Stack(before.identity(),1,64))) {
            assertFalse(NativeLoggingActions.axeWearCompatible(before,changed),changed.toString());
            assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,false,true,true,true,
                NativeLoggingActions.axeWearCompatible(before,changed)).contains("AXE_METADATA"));
        }
    }
    @Test void nativeStrokeContinuationReportsEachGuardWithoutWeakeningAnotherGuard() {
        assertNull(NativeLoggingActions.miningContinuationRejection(true,true,false,true,true,true,true));
        assertTrue(NativeLoggingActions.miningContinuationRejection(false,true,false,true,true,true,true).contains("AUTHORITY"));
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,false,false,true,true,true,true).contains("GROUND"));
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,true,true,true,true,true).contains("CROUCH"));
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,false,false,true,true,true).contains("REACH"));
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,false,true,false,true,true).contains("SLOT"));
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,false,true,true,false,true).contains("(AXE)"));
        assertTrue(NativeLoggingActions.miningContinuationRejection(true,true,false,true,true,true,false).contains("AXE_METADATA"));
        // Multiple problems remain fail-closed; diagnostics use stable precedence.
        String failed=NativeLoggingActions.miningContinuationRejection(false,false,true,false,false,false,false);
        assertTrue(failed.contains("AUTHORITY"));assertTrue(failed.contains("재전송하지 않고"));
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
    @Test void discardingAnyExistingBufferDoesNotAuthorizeValuableNewInventorySources() {
        assertTrue(NativeTrashSlot.sourceAllowed(data(ItemData.ROTTEN),false));
        assertFalse(NativeTrashSlot.sourceAllowed(data(ItemData.ROTTEN),true));
        for(String id:List.of(LoggingRules.TWIG,LoggingRules.SAPLING)) {
            assertTrue(NativeTrashSlot.sourceAllowed(data(id),true));
            assertFalse(NativeTrashSlot.sourceAllowed(data(id),false));
        }
        for(String id:List.of(LoggingRules.LOG,LoggingRules.FIRE_LOG,LoggingRules.BERRY,LoggingRules.AXE,
                ItemData.TOMATO,ItemData.WINE,ItemData.PRESERVES,ItemData.PINE_TAR,"minecraft:diamond","minecraft:diamond_sword"))
            for(boolean logging:List.of(false,true))assertFalse(NativeTrashSlot.sourceAllowed(data(id),logging),id);
        for(boolean logging:List.of(false,true)) {
            assertFalse(NativeTrashSlot.sourceAllowed(null,logging));assertFalse(NativeTrashSlot.sourceAllowed(ItemData.EMPTY,logging));
        }
    }
    @Test void trashPreflightOnlyRequiresTheServerEndpointNotTheOldBufferContents() {
        assertNull(NativeTrashSlot.preflightRejection(true));
        assertNotNull(NativeTrashSlot.preflightRejection(false));
    }
    @Test void diamondToolOrUnobservableRecoveryBufferCannotReintroduceAGuiReadGate() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/schwalbe/autovalley/client/NativeTrashSlot.java"));
        assertFalse(source.contains("getTrashSlot"),"No existing-buffer read, including diamonds, tools or absent GUI");
        assertFalse(source.contains("TrashSlotGuiHandler"));assertFalse(source.contains("bufferRejection"));
        assertTrue(source.contains("!sourceAllowed(expected,logging)"),"The new selected inventory source stays allowlisted");
    }
}
