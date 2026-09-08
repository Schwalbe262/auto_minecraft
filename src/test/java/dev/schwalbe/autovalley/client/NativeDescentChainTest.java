package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDescentChainTest {
    private static final List<Pos> FEET=List.of(new Pos(0,72,0),new Pos(1,71,0),new Pos(2,70,0),new Pos(3,69,0));
    private static final List<Double> HEIGHTS=List.of(72d,71d,70d,69d);
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    private static final NativeLoggingJump.Cell AIR=cell(true,false,true,List.of());
    private static NativeLoggingJump.Cell cell(boolean loaded,boolean forbidden,boolean normal,List<AABB> boxes) {
        return new NativeLoggingJump.Cell(loaded,true,forbidden,normal,boxes);
    }
    private static Map<Pos,NativeLoggingJump.Cell> stairs() {
        Map<Pos,NativeLoggingJump.Cell> cells=new HashMap<>();
        for (Pos p:FEET) cells.put(p.offset(0,-1,0),cell(true,false,true,List.of(CUBE)));
        return cells;
    }
    private static boolean inspect(Map<Pos,NativeLoggingJump.Cell> cells) {
        return NativeDescentChain.geometry(FEET,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR));
    }
    @Test void threeStraightFullBlockDropsHaveContinuousClearance() { assertTrue(inspect(stairs())); }
    @Test void allFourCardinalHeadingsUseTheSameClearanceProof() {
        for (int[] direction:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
            List<Pos> feet=new ArrayList<>(); var cells=new HashMap<Pos,NativeLoggingJump.Cell>();
            for (int i=0;i<4;i++) {
                Pos p=new Pos(i*direction[0],72-i,i*direction[1]); feet.add(p);
                cells.put(p.offset(0,-1,0),cell(true,false,true,List.of(CUBE)));
            }
            assertTrue(NativeDescentChain.geometry(feet,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR)));
        }
    }
    @Test void twoEdgesAreSufficientButOneOrMoreThanThreeAreNotAPreview() {
        var cells=stairs();
        assertTrue(NativeDescentChain.geometry(FEET.subList(0,3),HEIGHTS.subList(0,3),.6,1.8,p -> cells.getOrDefault(p,AIR)));
        assertFalse(NativeDescentChain.shape(FEET.subList(0,2)));
        var longPath=new ArrayList<>(FEET); longPath.add(new Pos(4,68,0));
        assertFalse(NativeDescentChain.shape(longPath));
    }
    @Test void turnsGapsDiagonalsAscentsAndUnknownPositionsFailClosed() {
        for (Pos p:List.of(new Pos(1,70,1),new Pos(3,70,0),new Pos(2,70,1),new Pos(2,72,0),new Pos(2,69,0)))
            assertFalse(NativeDescentChain.shape(List.of(FEET.get(0),FEET.get(1),p)));
        assertFalse(NativeDescentChain.shape(null));
        assertFalse(NativeDescentChain.shape(Arrays.asList(FEET.get(0),FEET.get(1),null)));
    }
    @Test void upperFlightHeadroomIsCheckedNotOnlyStandingHeadroomAtBottom() {
        var cells=stairs(); cells.put(new Pos(1,73,0),cell(true,false,true,List.of(CUBE)));
        assertFalse(inspect(cells));
        cells=stairs(); cells.put(new Pos(3,71,0),cell(true,false,true,List.of(CUBE)));
        assertFalse(inspect(cells));
    }
    @Test void wallAboveExpectedTreadIsNotIgnored() {
        var cells=stairs(); cells.put(FEET.get(1),cell(true,false,true,List.of(CUBE)));
        assertFalse(inspect(cells));
    }
    @Test void unknownFluidsDoorsCropsProtectedSoilAndSlipperySupportsRejectTheWholePreview() {
        for (Pos p:List.of(FEET.get(0).offset(0,-1,0),FEET.get(2).offset(0,-1,0),FEET.get(3),new Pos(1,73,0))) {
            var cells=stairs(); var boxes=cells.getOrDefault(p,AIR).boxes();
            cells.put(p,cell(false,false,true,boxes)); assertFalse(inspect(cells));
            cells.put(p,cell(true,true,true,boxes)); assertFalse(inspect(cells));
        }
        var cells=stairs(); cells.put(FEET.get(2).offset(0,-1,0),cell(true,false,false,List.of(CUBE)));
        assertFalse(inspect(cells));
    }
    @Test void narrowOrUnevenSupportsCannotInheritTheFullWidthLandingProof() {
        var cells=stairs(); cells.put(FEET.get(2).offset(0,-1,0),cell(true,false,true,List.of(new AABB(0,0,0,.5,1,1))));
        assertFalse(inspect(cells));
        cells.put(FEET.get(2).offset(0,-1,0),cell(true,false,true,List.of(new AABB(0,0,0,1,.5,1),new AABB(.5,.5,0,1,1,1))));
        assertFalse(inspect(cells));
    }
    @Test void measuredSupportHeightsAndKnownPlayerDimensionsAreRequired() {
        var cells=stairs();
        for (List<Double> heights:List.of(List.of(72d,71d,Double.NaN,69d),List.of(72d,71d,71d,69d),List.of(72d,71d,69.5d,69d)))
            assertFalse(NativeDescentChain.geometry(FEET,heights,.6,1.8,p -> cells.getOrDefault(p,AIR)));
        assertFalse(NativeDescentChain.geometry(FEET,HEIGHTS,.6,1.5,p -> cells.getOrDefault(p,AIR)));
        assertFalse(NativeDescentChain.geometry(FEET,HEIGHTS,Double.NaN,1.8,p -> cells.getOrDefault(p,AIR)));
    }
    @Test void ordinaryFullFootprintSlabsCanUseTheirActualFractionalHeights() {
        var cells=stairs();
        for (Pos p:FEET) cells.put(p.offset(0,-1,0),cell(true,false,true,List.of(new AABB(0,0,0,1,.5,1))));
        assertTrue(NativeDescentChain.geometry(FEET,List.of(71.5d,70.5d,69.5d,68.5d),.6,1.8,p -> cells.getOrDefault(p,AIR)));
    }
    private static List<AABB> stairBoxes(int dx,int dz) {
        return List.of(new AABB(0,0,0,1,.5,1),new AABB(dx<0?.5:0,.5,dz<0?.5:0,dx>0?.5:1,1,dz>0?.5:1));
    }
    @Test void knownStraightBottomStairsFacingUpstreamPassInAllFourDirections() {
        for (int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
            List<Pos> feet=new ArrayList<>(); var cells=new HashMap<Pos,NativeLoggingJump.Cell>();
            for (int i=0;i<4;i++) {
                Pos p=new Pos(i*d[0],72-i,i*d[1]); feet.add(p);
                cells.put(p.offset(0,-1,0),cell(true,false,true,stairBoxes(d[0],d[1])));
            }
            assertTrue(NativeDescentChain.geometry(feet,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
            assertFalse(NativeDescentChain.geometry(feet,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> false),
                "shape alone cannot opt in an unknown modded support");
        }
    }
    @Test void reverseSidewaysCornerAndUpsideDownStairsDoNotBorrowTheStraightStairProof() {
        assertFalse(NativeDescentChain.straightStairShape(stairBoxes(-1,0),1,0));
        assertFalse(NativeDescentChain.straightStairShape(stairBoxes(0,1),1,0));
        assertFalse(NativeDescentChain.straightStairShape(List.of(new AABB(0,.5,0,1,1,1),new AABB(0,0,0,.5,.5,1)),1,0));
        assertFalse(NativeDescentChain.straightStairShape(List.of(new AABB(0,0,0,1,.5,1),new AABB(0,.5,0,.5,1,.5)),1,0));
    }
    @Test void EquivalentNativeBoxMergingStillHasExactlyTheSixRequiredOctants() {
        assertTrue(NativeDescentChain.straightStairShape(List.of(new AABB(0,0,0,.5,1,1),new AABB(.5,0,0,1,.5,1)),1,0));
        assertFalse(NativeDescentChain.straightStairShape(List.of(CUBE),1,0));
        assertFalse(NativeDescentChain.straightStairShape(List.of(new AABB(0,0,0,1,.5,1),new AABB(0,.5,0,.49,1,1)),1,0));
    }
    private static Map<Pos,NativeLoggingJump.Cell> halfStairs() {
        var cells=stairs();
        for (Pos p:FEET.subList(0,2)) cells.put(p.offset(0,-1,0),cell(true,false,true,stairBoxes(1,0)));
        return cells;
    }
    private static boolean inspectHalf(Map<Pos,NativeLoggingJump.Cell> cells) {
        return NativeDescentChain.halfStepGeometry(FEET.subList(0,2),HEIGHTS.subList(0,2),.6,1.8,
            p -> cells.getOrDefault(p,AIR),p -> true);
    }
    @Test void exactNativeHalfTreadsHaveTheirOwnSingleEdgeProofWithoutRequiringAFollowingStair() {
        assertTrue(inspectHalf(halfStairs()));
        assertFalse(NativeDescentChain.shape(FEET.subList(0,2)),"A single half-tread edge cannot authorize a chained handoff");
        var cells=halfStairs();
        assertFalse(NativeDescentChain.halfStepGeometry(FEET,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
    }
    @Test void fullBlocksSlabsAndUnknownNativeStairIdentityNeverShortenTheFallHorizon() {
        for (Pos floor:List.of(FEET.get(0).offset(0,-1,0),FEET.get(1).offset(0,-1,0))) {
            var cells=halfStairs();cells.put(floor,cell(true,false,true,List.of(CUBE)));
            assertFalse(inspectHalf(cells));
            cells.put(floor,cell(true,false,true,List.of(new AABB(0,0,0,1,.5,1))));
            assertFalse(inspectHalf(cells));
            var exact=halfStairs();
            assertFalse(NativeDescentChain.halfStepGeometry(FEET.subList(0,2),HEIGHTS.subList(0,2),.6,1.8,
                p -> exact.getOrDefault(p,AIR),p -> !p.equals(floor)));
        }
    }
    @Test void nativeHalfTreadProofKeepsLoadingNormalSurfaceAndFullFlightHeadroomChecks() {
        var cells=halfStairs();Pos floor=FEET.get(0).offset(0,-1,0);
        cells.put(floor,cell(false,false,true,stairBoxes(1,0)));assertFalse(inspectHalf(cells));
        cells.put(floor,cell(true,false,false,stairBoxes(1,0)));assertFalse(inspectHalf(cells));
        cells.put(floor,cell(true,true,true,stairBoxes(1,0)));assertFalse(inspectHalf(cells));
        cells=halfStairs();cells.put(new Pos(1,73,0),cell(true,false,true,List.of(CUBE)));assertFalse(inspectHalf(cells));
        cells=halfStairs();cells.put(FEET.get(1),cell(true,true,true,List.of()));assertFalse(inspectHalf(cells));
    }
    @Test void halfTreadNativeProofHasTheSameDirectionAndHeightChecksOnEveryAxis() {
        for (int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
            List<Pos> feet=List.of(new Pos(0,72,0),new Pos(d[0],71,d[1]));
            var cells=new HashMap<Pos,NativeLoggingJump.Cell>();
            for (Pos p:feet) cells.put(p.offset(0,-1,0),cell(true,false,true,stairBoxes(d[0],d[1])));
            assertTrue(NativeDescentChain.halfStepGeometry(feet,List.of(72d,71d),.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
            assertFalse(NativeDescentChain.halfStepGeometry(feet,List.of(72d,71.5d),.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
            cells.put(feet.get(1).offset(0,-1,0),cell(true,false,true,stairBoxes(-d[0],-d[1])));
            assertFalse(NativeDescentChain.halfStepGeometry(feet,List.of(72d,71d),.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
        }
    }
    private static Map<Pos,NativeLoggingJump.Cell> flowStairs() {
        var cells=stairs();
        for (Pos p:FEET) cells.put(p.offset(0,-1,0),cell(true,false,true,stairBoxes(1,0)));
        return cells;
    }
    private static boolean inspectFlow(Map<Pos,NativeLoggingJump.Cell> cells) {
        return NativeDescentChain.flowGeometry(FEET,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true);
    }
    @Test void continuousFlowAcceptsOnlyTwoOrThreeFullyProvenStraightStairEdges() {
        var cells=flowStairs();
        assertTrue(inspectFlow(cells));
        assertTrue(NativeDescentChain.flowGeometry(FEET.subList(0,3),HEIGHTS.subList(0,3),.6,1.8,
            p -> cells.getOrDefault(p,AIR),p -> true));
        assertFalse(NativeDescentChain.flowShape(FEET.subList(0,2)));
        var tooLong=new ArrayList<>(FEET);tooLong.add(new Pos(4,68,0));
        assertFalse(NativeDescentChain.flowShape(tooLong));
    }
    @Test void continuousFlowRetainsExactStairShapeAndUpstreamDirectionInEveryHeading() {
        for (int[] d:List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1})) {
            var feet=new ArrayList<Pos>();var cells=new HashMap<Pos,NativeLoggingJump.Cell>();
            for (int i=0;i<4;i++) {
                Pos p=new Pos(i*d[0],72-i,i*d[1]);feet.add(p);
                cells.put(p.offset(0,-1,0),cell(true,false,true,stairBoxes(d[0],d[1])));
            }
            assertTrue(NativeDescentChain.flowGeometry(feet,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
            cells.put(feet.get(2).offset(0,-1,0),cell(true,false,true,stairBoxes(-d[0],-d[1])));
            assertFalse(NativeDescentChain.flowGeometry(feet,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
        }
    }
    @Test void flowDoesNotInheritFullBlockSlabOrMerelyStairShapedModdedSupportPermission() {
        for (Pos p:FEET) {
            Pos floor=p.offset(0,-1,0);var cells=flowStairs();
            cells.put(floor,cell(true,false,true,List.of(CUBE)));assertFalse(inspectFlow(cells));
            cells.put(floor,cell(true,false,true,List.of(new AABB(0,0,0,1,.5,1))));assertFalse(inspectFlow(cells));
            var actual=flowStairs();
            assertFalse(NativeDescentChain.flowGeometry(FEET,HEIGHTS,.6,1.8,p2 -> actual.getOrDefault(p2,AIR),p2 -> !p2.equals(floor)));
        }
    }
    @Test void flowChecksHighBodyClearanceOverLaterStepsEvenWhenEachEdgeAloneIsClear() {
        for (Pos high:List.of(new Pos(2,73,0),new Pos(3,72,0))) {
            var cells=flowStairs();cells.put(high,cell(true,false,true,List.of(CUBE)));
            assertTrue(NativeDescentChain.geometry(FEET,HEIGHTS,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true),
                "the old settled-edge proof deliberately does not authorize the larger flight envelope");
            assertFalse(inspectFlow(cells));
        }
    }
    @Test void flowAllowsSolidStructureBelowEachVerifiedStairWithoutExcusingAnyBodyObstacle() {
        var cells=flowStairs();
        for (Pos p:FEET) for (int y=68;y<p.y()-1;y++)
            cells.put(new Pos(p.x(),y,p.z()),cell(true,false,true,List.of(CUBE)));
        assertTrue(inspectFlow(cells),"only the edge-local lower floor bounds the occupied envelope");
        cells.put(FEET.get(2),cell(true,false,true,List.of(CUBE)));
        assertFalse(inspectFlow(cells),"a wall above that same structure remains a real obstacle");
    }
    @Test void flowChecksLoadingAndPermissionThroughoutTheLargerFlightEnvelope() {
        for (Pos p:List.of(new Pos(3,72,0),FEET.get(1),FEET.get(2).offset(0,-1,0))) {
            var cells=flowStairs();var boxes=cells.getOrDefault(p,AIR).boxes();
            cells.put(p,cell(false,false,true,boxes));assertFalse(inspectFlow(cells));
            cells.put(p,new NativeLoggingJump.Cell(true,false,false,true,boxes));assertFalse(inspectFlow(cells));
            cells.put(p,cell(true,true,true,boxes));assertFalse(inspectFlow(cells));
        }
    }
    @Test void everyFlowSupportRequiresNormalSurfaceAndCannotHideAChangedShape() {
        for (Pos p:FEET) {
            var cells=flowStairs();Pos floor=p.offset(0,-1,0);
            cells.put(floor,cell(true,false,false,stairBoxes(1,0)));assertFalse(inspectFlow(cells));
            cells.put(floor,cell(true,false,true,List.of(new AABB(0,0,0,1,.5,1),new AABB(0,.5,0,.5,1,.5))));
            assertFalse(inspectFlow(cells));
        }
    }
    @Test void flowRejectsLevelTransitionsGapsTurnsAndCoordinateWraparound() {
        for (Pos to:List.of(new Pos(2,71,0),new Pos(2,69,0),new Pos(1,70,1),new Pos(3,70,0)))
            assertFalse(NativeDescentChain.flowShape(List.of(FEET.get(0),FEET.get(1),to)));
        assertFalse(NativeDescentChain.flowShape(List.of(new Pos(Integer.MAX_VALUE,72,0),new Pos(Integer.MIN_VALUE,71,0),new Pos(Integer.MIN_VALUE+1,70,0))));
        assertFalse(NativeDescentChain.flowShape(List.of(new Pos(0,Integer.MIN_VALUE,0),new Pos(1,Integer.MAX_VALUE,0),new Pos(2,Integer.MAX_VALUE-1,0))));
        assertFalse(NativeDescentChain.flowShape(null));
        assertFalse(NativeDescentChain.flowShape(Arrays.asList(FEET.get(0),FEET.get(1),null)));
    }
    @Test void flowRejectsUnknownDimensionsHeightsAndMalformedCollisionBoxes() {
        var cells=flowStairs();
        for (List<Double> heights:List.of(List.of(72d,71d,Double.NaN,69d),List.of(72d,71d,70d,68.5d)))
            assertFalse(NativeDescentChain.flowGeometry(FEET,heights,.6,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
        assertFalse(NativeDescentChain.flowGeometry(FEET,HEIGHTS,.6,1.5,p -> cells.getOrDefault(p,AIR),p -> true));
        assertFalse(NativeDescentChain.flowGeometry(FEET,HEIGHTS,Double.NaN,1.8,p -> cells.getOrDefault(p,AIR),p -> true));
        cells.put(new Pos(3,72,0),cell(true,false,true,List.of(new AABB(0,0,0,1,2,1))));
        assertFalse(inspectFlow(cells));
    }
    @Test void flowKeepsTheCompleteMarginAndCannotTreatAWallAsVerifiedSupport() {
        var cells=flowStairs();cells.put(FEET.get(2),cell(true,false,true,List.of(new AABB(.85,0,0,1,1,1))));
        assertFalse(inspectFlow(cells),"the .10 lane margin is clearance, not extra collision permission");
    }
}
