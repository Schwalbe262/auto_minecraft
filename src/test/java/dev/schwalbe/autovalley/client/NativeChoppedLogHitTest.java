package dev.schwalbe.autovalley.client;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeChoppedLogHitTest {
    private static final BlockPos TARGET=new BlockPos(0,64,0);
    private static final AABB CORNER=new AABB(.875,0,.875,1,1,1);
    private static Function<Vec3,BlockHitResult> unobstructed(Vec3 eye,List<AABB> boxes) {
        return end -> AABB.clip(boxes,eye,end,TARGET);
    }
    private static BlockHitResult hit(Vec3 eye,List<AABB> boxes,double reach) {
        return NativeChoppedLogHit.nearest(TARGET,eye,boxes,reach,unobstructed(eye,boxes));
    }
    @Test void offCentreReducedLogMissedByOldQuarterSamplesHasARealOutlineHit() {
        Vec3 eye=new Vec3(-1,64.5,-1); var boxes=List.of(CORNER);
        for (double y:new double[]{.5,.8,.25}) for (double x:new double[]{.5,.25,.75}) for (double z:new double[]{.5,.25,.75})
            assertNull(AABB.clip(boxes,eye,new Vec3(x,64+y,z),TARGET));
        BlockHitResult result=hit(eye,boxes,4);
        assertNotNull(result); assertEquals(TARGET,result.getBlockPos());
        assertTrue(result.getLocation().x>=.875-1.0e-7 && result.getLocation().z>=.875-1.0e-7);
    }
    @Test void narrowPillarAndLowHorizontalRemainderUseTheirOwnBounds() {
        assertNotNull(hit(new Vec3(-1,64.5,.5),List.of(new AABB(.4375,0,.4375,.5625,1,.5625)),4));
        assertNotNull(hit(new Vec3(.5,66,.5),List.of(new AABB(0,0,0,1,.125,1)),4));
    }
    @Test void allGeneratedPointsAreStrictlyInsideOneOfTheNativeBoxes() {
        var boxes=List.of(CORNER,new AABB(.1,.2,.3,.2,.3,.4));
        var points=NativeChoppedLogHit.candidates(TARGET,new Vec3(-2,66,-2),boxes);
        assertFalse(points.isEmpty()); assertTrue(points.size()<=28*boxes.size());
        for (Vec3 point:points) assertTrue(boxes.stream().anyMatch(b ->
            point.x>b.minX && point.x<b.maxX && point.y-64>b.minY && point.y-64<b.maxY && point.z>b.minZ && point.z<b.maxZ));
        assertThrows(UnsupportedOperationException.class,() -> points.add(Vec3.ZERO));
    }
    @Test void anOccludingBlockRemainsTheFirstWorldHitAndIsNeverBypassed() {
        Vec3 eye=new Vec3(-2,64.5,.9375); var boxes=List.of(CORNER);
        BlockPos wall=new BlockPos(-1,64,0); var wallBoxes=List.of(new AABB(0,0,0,1,1,1));
        Function<Vec3,BlockHitResult> clip=end -> {
            BlockHitResult target=AABB.clip(boxes,eye,end,TARGET),blocked=AABB.clip(wallBoxes,eye,end,wall);
            return blocked!=null && (target==null || eye.distanceTo(blocked.getLocation())<eye.distanceTo(target.getLocation())) ? blocked : target;
        };
        assertNull(NativeChoppedLogHit.nearest(TARGET,eye,boxes,4,clip));
    }
    @Test void partiallyOccludedCentreCanUseAnotherExposedNativeFace() {
        Vec3 eye=new Vec3(-1,64.5,.9375); var boxes=List.of(CORNER);
        Function<Vec3,BlockHitResult> clip=end -> {
            BlockHitResult nativeHit=AABB.clip(boxes,eye,end,TARGET);
            if (end.y>64.25 && end.y<64.75) return new BlockHitResult(new Vec3(-.5,64.5,.9375),Direction.WEST,new BlockPos(-1,64,0),false);
            return nativeHit;
        };
        assertNotNull(NativeChoppedLogHit.nearest(TARGET,eye,boxes,4,clip));
    }
    @Test void usesNearestValidatedIntersectionRatherThanFirstOrDistantCentre() {
        Vec3 eye=new Vec3(-3.1,64.5,.9375); var boxes=List.of(CORNER);
        BlockHitResult result=hit(eye,boxes,4);
        assertNotNull(result); assertEquals(3.975,eye.distanceTo(result.getLocation()),1.0e-6);
        assertNull(hit(eye,boxes,3.97));
    }
    @Test void actualReachAndAbsoluteFourBlockCeilingAreNotExpanded() {
        Vec3 eye=new Vec3(-3.2,64.5,.9375); var boxes=List.of(CORNER);
        assertNull(hit(eye,boxes,4)); assertNull(hit(eye,boxes,100));
        assertNull(hit(new Vec3(-1,64.5,.9375),boxes,1.5));
    }
    @Test void missesOtherTargetsInsideHitsAndInvalidNativeLocationsNeverAuthorizeUse() {
        Vec3 eye=new Vec3(-1,64.5,.9375); var boxes=List.of(CORNER); Vec3 face=new Vec3(.875,64.5,.9375);
        for (BlockHitResult result:List.of(BlockHitResult.miss(face,Direction.WEST,TARGET),
                new BlockHitResult(face,Direction.WEST,TARGET.offset(1,0,0),false),
                new BlockHitResult(face,Direction.WEST,TARGET,true),
                new BlockHitResult(new Vec3(.5,64.5,.5),Direction.WEST,TARGET,false),
                new BlockHitResult(new Vec3(Double.NaN,64.5,.9375),Direction.WEST,TARGET,false)))
            assertNull(NativeChoppedLogHit.nearest(TARGET,eye,boxes,4,end -> result));
        assertNull(NativeChoppedLogHit.nearest(TARGET,eye,boxes,4,end -> null));
    }
    @Test void unknownEmptyOutOfBlockAndExcessiveShapesFailClosed() {
        Vec3 eye=new Vec3(-1,64.5,.9375);
        assertNull(hit(eye,List.of(),4));
        assertNull(hit(eye,List.of(new AABB(-.1,0,0,1,1,1)),4));
        assertNull(hit(eye,List.of(new AABB(0,0,0,1,1,1.1)),4));
        assertNull(hit(eye,Collections.nCopies(NativeChoppedLogHit.MAX_BOXES+1,CORNER),4));
        assertNull(NativeChoppedLogHit.nearest(TARGET,eye,null,4,end -> null));
        assertNull(NativeChoppedLogHit.nearest(TARGET,new Vec3(Double.NaN,0,0),List.of(CORNER),4,end -> null));
        assertNull(hit(eye,List.of(CORNER),Double.NaN));
    }
    @Test void changingNativeOutlineRebuildsCandidatesWithoutOldShapeCaching() {
        Vec3 eye=new Vec3(-1,64.5,-1);
        var before=NativeChoppedLogHit.candidates(TARGET,eye,List.of(new AABB(.75,0,.75,1,1,1)));
        var after=NativeChoppedLogHit.candidates(TARGET,eye,List.of(CORNER));
        assertTrue(before.stream().anyMatch(p -> p.x<.875));
        assertTrue(after.stream().allMatch(p -> p.x>.875 && p.z>.875));
    }
    @Test void farTargetsSpendNoNativeRaysForEitherQueryAndRespectActualReach() {
        var boxes=List.of(new AABB(0,0,0,1,1,1));int[] calls={0};
        Function<Vec3,BlockHitResult> never=end->{calls[0]++;throw new AssertionError("far broadphase must reject before clipping");};
        for(Vec3 eye:List.of(new Vec3(-4.01,64.5,.5),new Vec3(.5,69.01,.5),new Vec3(.5,64.5,5.01),
                new Vec3(-3,64.5,-3),new Vec3(1.0e100,64.5,.5))) {
            assertNull(NativeChoppedLogHit.nearest(TARGET,eye,boxes,100,never));
            assertFalse(NativeChoppedLogHit.visible(TARGET,eye,boxes,100,never));
        }
        assertNull(NativeChoppedLogHit.nearest(TARGET,new Vec3(-1.51,64.5,.5),boxes,1.5,never));
        assertFalse(NativeChoppedLogHit.visible(TARGET,new Vec3(-1.51,64.5,.5),boxes,1.5,never));
        assertEquals(0,calls[0]);
    }
    @Test void visibleStopsAtFirstVerifiedHitWhileNearestStillFindsTheClosest() {
        var boxes=List.of(new AABB(0,0,0,1,1,1));Vec3 eye=new Vec3(-1,64.5,.5);int[] visibleCalls={0},nearestCalls={0};
        Function<Vec3,BlockHitResult> visibleClip=end->{visibleCalls[0]++;return new BlockHitResult(new Vec3(.75,64.5,.5),Direction.WEST,TARGET,false);};
        assertTrue(NativeChoppedLogHit.visible(TARGET,eye,boxes,4,visibleClip));assertEquals(1,visibleCalls[0]);
        Function<Vec3,BlockHitResult> nearestClip=end->{nearestCalls[0]++;return new BlockHitResult(new Vec3(nearestCalls[0]==1?.75:0,64.5,.5),Direction.WEST,TARGET,false);};
        BlockHitResult nearest=NativeChoppedLogHit.nearest(TARGET,eye,boxes,4,nearestClip);
        assertNotNull(nearest);assertEquals(1,eye.distanceTo(nearest.getLocation()),1.0e-9);
        assertEquals(NativeChoppedLogHit.candidates(TARGET,eye,boxes).size(),nearestCalls[0]);
    }
    @Test void visibleContinuesPastOccludedFacesAndRetainsAllNativeHitChecks() {
        Vec3 eye=new Vec3(-1,64.5,.9375);var boxes=List.of(CORNER);int[] calls={0};
        Function<Vec3,BlockHitResult> clip=end->{
            if(++calls[0]==1)return new BlockHitResult(new Vec3(-.5,64.5,.9375),Direction.WEST,TARGET.offset(-1,0,0),false);
            return AABB.clip(boxes,eye,end,TARGET);
        };
        assertTrue(NativeChoppedLogHit.visible(TARGET,eye,boxes,4,clip));assertEquals(2,calls[0]);
        for(BlockHitResult invalid:List.of(BlockHitResult.miss(new Vec3(.875,64.5,.9375),Direction.WEST,TARGET),
                new BlockHitResult(new Vec3(.875,64.5,.9375),Direction.WEST,TARGET,true),
                new BlockHitResult(new Vec3(.5,64.5,.5),Direction.WEST,TARGET,false)))
            assertFalse(NativeChoppedLogHit.visible(TARGET,eye,boxes,4,end->invalid));
    }
    @Test void unitCubeInteriorAndBoundaryFacesAreNotRejectedByEndpointDistance() {
        var boxes=List.of(CORNER);Vec3 interior=new Vec3(.5,64.5,.9375);
        assertTrue(NativeChoppedLogHit.visible(TARGET,interior,boxes,4,unobstructed(interior,boxes)));
        // The far box's endpoints exceed reach, but the actual first face is exactly four blocks away.
        Vec3 edge=new Vec3(-3.125,64.5,.9375);
        assertTrue(NativeChoppedLogHit.candidates(TARGET,edge,boxes).stream().allMatch(p->edge.distanceTo(p)>4));
        assertTrue(NativeChoppedLogHit.visible(TARGET,edge,boxes,4,unobstructed(edge,boxes)));
        assertEquals(4,edge.distanceTo(hit(edge,boxes,4).getLocation()),1.0e-9);
    }
    @Test void invalidVisibilityInputsNeverReachNativeClip() {
        Vec3 eye=new Vec3(-1,64.5,.9375);var boxes=List.of(CORNER);int[] calls={0};
        Function<Vec3,BlockHitResult> never=end->{calls[0]++;throw new AssertionError("invalid input must not clip");};
        assertFalse(NativeChoppedLogHit.visible(null,eye,boxes,4,never));
        assertFalse(NativeChoppedLogHit.visible(TARGET,new Vec3(0,Double.NaN,0),boxes,4,never));
        assertFalse(NativeChoppedLogHit.visible(TARGET,new Vec3(Double.POSITIVE_INFINITY,64,0),boxes,4,never));
        for(double reach:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertFalse(NativeChoppedLogHit.visible(TARGET,eye,boxes,reach,never));
        for(List<AABB> invalid:Arrays.<List<AABB>>asList(null,List.of(),Arrays.asList((AABB)null),
                List.of(new AABB(-.1,0,0,1,1,1)),Collections.nCopies(NativeChoppedLogHit.MAX_BOXES+1,CORNER))) {
            assertFalse(NativeChoppedLogHit.visible(TARGET,eye,invalid,4,never));
            assertNull(NativeChoppedLogHit.nearest(TARGET,eye,invalid,4,never));
        }
        assertFalse(NativeChoppedLogHit.visible(TARGET,eye,boxes,4,null));assertEquals(0,calls[0]);
    }
}
