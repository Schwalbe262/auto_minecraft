package dev.schwalbe.autovalley.client;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoggingLeafHitTest {
    private static final BlockPos BASE=new BlockPos(0,64,0),LEAF=new BlockPos(-1,64,0);
    private static final List<AABB> FULL=List.of(new AABB(0,0,0,1,1,1));
    private static final Vec3 EYE=new Vec3(-1.5,64.5,.5);
    private static BlockHitResult leafHit(boolean inside) {
        return new BlockHitResult(new Vec3(-1,64.5,.5),Direction.WEST,LEAF,inside);
    }
    private static BlockPos find(Vec3 eye,List<AABB> boxes,double reach,Function<Vec3,BlockHitResult> clip) {
        return NativeLoggingLeafHit.obstruction(BASE,eye,boxes,reach,clip,LEAF::equals);
    }
    @Test void aRealFirstLeafHitIsReturnedOnlyAfterAllBaseOutlineSamplesAreChecked() {
        int[] rays={0};
        assertEquals(LEAF,find(EYE,FULL,4,end->{rays[0]++;return leafHit(false);}));
        assertEquals(NativeChoppedLogHit.candidates(BASE,EYE,FULL).size(),rays[0]);
        assertTrue(rays[0]<=28);
    }
    @Test void startingInsideTheExactLeafIsNotConfusedWithStartingInsideTheBase() {
        Vec3 insideLeaf=new Vec3(-.5,64.5,.5);
        assertEquals(LEAF,find(insideLeaf,FULL,4,end -> new BlockHitResult(insideLeaf,Direction.WEST,LEAF,true)));
        assertNull(find(EYE,FULL,4,end -> new BlockHitResult(new Vec3(.5,64.5,.5),Direction.WEST,BASE,true)));
        assertNull(find(EYE,FULL,4,end -> new BlockHitResult(new Vec3(.5,64.5,.5),Direction.WEST,LEAF,true)),
            "An inside marker does not authorise a different cell from the native hit location");
    }
    @Test void anyLaterVisibleBaseFaceCancelsAnEarlierLeafCandidate() {
        int[] rays={0};
        assertNull(find(EYE,FULL,4,end -> ++rays[0]==1 ? leafHit(false) : AABB.clip(FULL,EYE,end,BASE)));
        assertEquals(2,rays[0]);
    }
    @Test void aDistantBaseCannotAuthorizeClearingANearbyLeafAndReachIsNeverExpanded() {
        int[] rays={0};Function<Vec3,BlockHitResult> unused=end->{rays[0]++;return leafHit(false);};
        assertNull(find(new Vec3(-4.01,64.5,.5),FULL,100,unused));
        assertNull(find(EYE,FULL,1,unused));assertEquals(0,rays[0]);
        // Its near face is four blocks away, but every interior sample is farther:
        // do not turn the nearby leaf alone into proof of a reachable base sample.
        assertNull(find(new Vec3(-4,64.5,.5),FULL,4,end -> leafHit(false)));
    }
    @Test void aReachableBaseFaceStillWinsWhenItsInteriorEndpointIsFartherThanReach() {
        Vec3 edge=new Vec3(-4,64.5,.5);int[] rays={0};
        assertNull(find(edge,FULL,4,end->{rays[0]++;return AABB.clip(FULL,edge,end,BASE);}));
        assertEquals(1,rays[0]);
    }
    @Test void anotherFirstBlockIsNeverSkippedOrTreatedAsSpruceLeaves() {
        BlockPos wall=LEAF.offset(-1,0,0);
        assertNull(find(EYE,FULL,4,end -> new BlockHitResult(new Vec3(-1.6,64.5,.5),Direction.WEST,wall,false)));
        assertNull(NativeLoggingLeafHit.obstruction(BASE,EYE,FULL,4,end -> leafHit(false),p -> false));
        assertNull(find(EYE,FULL,4,end -> BlockHitResult.miss(new Vec3(-1,64.5,.5),Direction.WEST,LEAF)));
    }
    @Test void unavailableOrMalformedRepliesDoNotBecomeNegativeVisibility() {
        assertNull(find(EYE,FULL,4,end -> null));
        assertNull(find(EYE,FULL,4,end -> new BlockHitResult(new Vec3(Double.NaN,64.5,.5),Direction.WEST,LEAF,false)));
        int[] rays={0};assertNull(find(EYE,FULL,4,end -> ++rays[0]==1 ? leafHit(false) : null));
    }
    @Test void partialLogsUseOnlyTheirCurrentNativeOutlineNotTheOldBlockCentre() {
        List<AABB> narrow=List.of(new AABB(.875,0,.875,1,1,1));
        Vec3 eye=new Vec3(-1,64.5,.9375);int[] rays={0};
        assertEquals(LEAF,find(eye,narrow,4,end -> {
            rays[0]++;assertTrue(end.x>.875 && end.z>.875);
            return new BlockHitResult(new Vec3(-.5,64.5,.9375),Direction.WEST,LEAF,false);
        }));assertTrue(rays[0]>0);
    }
    @Test void invalidInputsAndUnboundedShapesSpendNoNativeRay() {
        int[] rays={0};Function<Vec3,BlockHitResult> never=end->{rays[0]++;throw new AssertionError("invalid native input");};
        for(List<AABB> boxes:Arrays.<List<AABB>>asList(null,List.of(),Arrays.asList((AABB)null),
                List.of(new AABB(-.1,0,0,1,1,1)),Collections.nCopies(17,FULL.get(0))))
            assertNull(find(EYE,boxes,4,never));
        for(double reach:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})assertNull(find(EYE,FULL,reach,never));
        assertNull(find(new Vec3(Double.NaN,64,0),FULL,4,never));
        assertNull(NativeLoggingLeafHit.obstruction(null,EYE,FULL,4,never,LEAF::equals));
        assertEquals(0,rays[0]);
    }
}
