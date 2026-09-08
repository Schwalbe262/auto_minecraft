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
}
