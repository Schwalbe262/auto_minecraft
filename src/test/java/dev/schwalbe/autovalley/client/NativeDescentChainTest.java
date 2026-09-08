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
}
