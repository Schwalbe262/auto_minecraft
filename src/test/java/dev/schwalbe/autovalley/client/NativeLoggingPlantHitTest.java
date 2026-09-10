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

class NativeLoggingPlantHitTest {
    private static final BlockPos SOIL=new BlockPos(0,0,0);
    private static final List<AABB> FULL=List.of(new AABB(0,0,0,1,1,1));
    private static Function<Vec3,BlockHitResult> clear(Vec3 eye,List<AABB> boxes) {
        return end -> AABB.clip(boxes,eye,end,SOIL);
    }
    private static BlockHitResult hit(Vec3 eye,List<AABB> boxes,double reach) {
        return NativeLoggingPlantHit.nearest(SOIL,eye,boxes,reach,clear(eye,boxes));
    }

    @Test void fullSoilProvidesNineInteriorCandidatesAndAnActualUpIntersection() {
        var points=NativeLoggingPlantHit.candidates(SOIL,FULL);
        assertEquals(9,points.size());
        for(Vec3 point:points) { assertTrue(point.x>0&&point.x<1);assertTrue(point.z>0&&point.z<1);assertEquals(.999,point.y,1.0e-9); }
        Vec3 eye=new Vec3(.5,3,.5); BlockHitResult result=hit(eye,FULL,3.25);
        assertNotNull(result); assertEquals(Direction.UP,result.getDirection()); assertEquals(SOIL,result.getBlockPos());
        assertEquals(new Vec3(.5,1,.5),result.getLocation());
        assertThrows(UnsupportedOperationException.class,()->points.add(Vec3.ZERO));
    }

    @Test void aBlockedCenterCanChooseAnotherGenuinelyVisibleUpFaceButNeverTheOccluder() {
        Vec3 eye=new Vec3(.5,3,.5); BlockPos sapling=SOIL.above().north();
        List<BlockHitResult> observed=new ArrayList<>(); int[] rays={0};
        Function<Vec3,BlockHitResult> clip=end -> {
            rays[0]++;
            BlockHitResult result=end.x==.5&&end.z==.5
                ? new BlockHitResult(new Vec3(.5,1.8,-.2),Direction.NORTH,sapling,false)
                : AABB.clip(FULL,eye,end,SOIL);
            observed.add(result); return result;
        };
        BlockHitResult result=NativeLoggingPlantHit.nearest(SOIL,eye,FULL,3.25,clip);
        assertNotNull(result); assertEquals(SOIL,result.getBlockPos()); assertEquals(Direction.UP,result.getDirection());
        assertFalse(result.getLocation().x==.5&&result.getLocation().z==.5);
        assertTrue(observed.stream().anyMatch(h -> h==result),"Return the actual native first hit object, not a manufactured center hit");
        assertEquals(9,rays[0]);
    }

    @Test void anActualWallBlockingEveryRayIsNeverBypassed() {
        Vec3 eye=new Vec3(.5,3,.5); BlockPos obstruction=SOIL.above();
        assertNull(NativeLoggingPlantHit.nearest(SOIL,eye,FULL,3.25,end -> {
            var wall=AABB.clip(FULL,eye,end,obstruction);
            return wall!=null ? wall:AABB.clip(FULL,eye,end,SOIL);
        }));
    }

    @Test void sideOnlyVisibilityDoesNotAuthorizePlanting() {
        Vec3 eye=new Vec3(-1,.5,.5);
        assertNotNull(AABB.clip(FULL,eye,new Vec3(.5,.5,.5),SOIL));
        assertNull(hit(eye,FULL,3.25));
    }

    @Test void partialHeightSupportUsesItsRealTopPlaneNotTheAirCellBoundary() {
        var lowered=List.of(new AABB(0,0,0,1,.9375,1));
        BlockHitResult result=hit(new Vec3(.5,3,.5),lowered,3.25);
        assertNotNull(result); assertEquals(.9375,result.getLocation().y,1.0e-9);
    }

    @Test void actualRequestedReachAndFourBlockCeilingAreNeverExpanded() {
        assertNotNull(hit(new Vec3(.5,4.25,.5),FULL,3.25));
        assertNull(hit(new Vec3(.5,4.251,.5),FULL,3.25));
        assertNull(hit(new Vec3(.5,5.001,.5),FULL,100));
        assertNull(hit(new Vec3(.5,3,.5),FULL,1));
        assertNull(hit(new Vec3(.5,3,.5),FULL,Double.NaN));
    }

    @Test void otherTargetsWrongFacesInsideHitsAndInventedLocationsAreRejected() {
        Vec3 eye=new Vec3(.5,3,.5),top=new Vec3(.5,1,.5);
        for(BlockHitResult result:List.of(
            BlockHitResult.miss(top,Direction.UP,SOIL),
            new BlockHitResult(top,Direction.UP,SOIL.east(),false),
            new BlockHitResult(top,Direction.NORTH,SOIL,false),
            new BlockHitResult(top,Direction.DOWN,SOIL,false),
            new BlockHitResult(top,Direction.UP,SOIL,true),
            new BlockHitResult(new Vec3(.5,.5,.5),Direction.UP,SOIL,false),
            new BlockHitResult(new Vec3(0,1,.5),Direction.UP,SOIL,false),
            new BlockHitResult(new Vec3(1.1,1,.5),Direction.UP,SOIL,false),
            new BlockHitResult(new Vec3(Double.NaN,1,.5),Direction.UP,SOIL,false)))
            assertNull(NativeLoggingPlantHit.nearest(SOIL,eye,FULL,3.25,end -> result));
        assertNull(NativeLoggingPlantHit.nearest(SOIL,eye,FULL,3.25,end -> null));
    }

    @Test void disconnectedOutlinePartsRemainBoundedAndCandidatesStayInsideTheirOwnBox() {
        var boxes=List.of(new AABB(.1,0,.1,.4,.75,.4),new AABB(.6,0,.6,.9,1,.9));
        var points=NativeLoggingPlantHit.candidates(SOIL,boxes); assertEquals(18,points.size());
        for(Vec3 p:points) assertTrue(boxes.stream().anyMatch(b -> p.x>b.minX&&p.x<b.maxX&&p.z>b.minZ&&p.z<b.maxZ&&p.y<b.maxY&&p.y>b.minY));
        assertTrue(points.size()<=NativeLoggingPlantHit.MAX_CANDIDATES);
    }

    @Test void missingInvalidAndExcessiveShapeDataFailClosedWithoutCastingRays() {
        Vec3 eye=new Vec3(.5,3,.5); int[] rays={0};
        Function<Vec3,BlockHitResult> clip=end -> { rays[0]++;return AABB.clip(FULL,eye,end,SOIL); };
        for(List<AABB> boxes:List.of(List.<AABB>of(),List.of(new AABB(-.1,0,0,1,1,1)),
            List.of(new AABB(0,0,0,1,1.1,1)),Collections.nCopies(17,FULL.get(0))))
            assertNull(NativeLoggingPlantHit.nearest(SOIL,eye,boxes,3.25,clip));
        assertNull(NativeLoggingPlantHit.nearest(SOIL,eye,null,3.25,clip));
        assertEquals(0,rays[0]);
        assertNull(NativeLoggingPlantHit.nearest(SOIL,new Vec3(Double.NaN,3,.5),FULL,3.25,clip));
    }

    @Test void aChangedSupportRebuildsPointsAndDoesNotReuseAnOldTopHit() {
        Vec3 eye=new Vec3(.5,3,.5);
        BlockHitResult before=hit(eye,FULL,3.25);
        var changed=List.of(new AABB(.125,0,.125,.875,.5,.875));
        BlockHitResult after=hit(eye,changed,3.25);
        assertNotNull(before);assertNotNull(after);assertEquals(1,before.getLocation().y);assertEquals(.5,after.getLocation().y);
        assertNull(NativeLoggingPlantHit.nearest(SOIL,eye,changed,3.25,end -> before));
    }

    @Test void aSingleSnowLayerIsClickedOnItsActualOutlineAndNotThroughToSoil() {
        BlockPos snow=SOIL.above(); Vec3 eye=new Vec3(.5,3,.5);
        var layer=List.of(new AABB(0,0,0,1,.125,1));
        List<BlockHitResult> observed=new ArrayList<>();
        BlockHitResult result=NativeLoggingPlantHit.nearest(snow,eye,layer,3.25,end -> {
            BlockHitResult nativeHit=AABB.clip(layer,eye,end,snow); observed.add(nativeHit); return nativeHit;
        });
        assertNotNull(result); assertEquals(snow,result.getBlockPos());
        assertEquals(1.125,result.getLocation().y,1.0e-9); assertEquals(Direction.UP,result.getDirection());
        assertTrue(observed.stream().anyMatch(h -> h==result));
        assertNull(NativeLoggingPlantHit.nearest(snow,eye,layer,3.25,end -> AABB.clip(FULL,eye,end,SOIL)));
    }

    @Test void aSnowSurfaceStillRequiresUnobstructedUpFaceAndActualReach() {
        BlockPos snow=SOIL.above(); var layer=List.of(new AABB(0,0,0,1,.125,1));
        Vec3 distant=new Vec3(.5,4.376,.5);
        assertNull(NativeLoggingPlantHit.nearest(snow,distant,layer,3.25,end -> AABB.clip(layer,distant,end,snow)));
        Vec3 side=new Vec3(-1,1.05,.5);
        assertNull(NativeLoggingPlantHit.nearest(snow,side,layer,3.25,end -> AABB.clip(layer,side,end,snow)));
        Vec3 eye=new Vec3(.5,4,.5); BlockPos obstruction=snow.above();
        assertNull(NativeLoggingPlantHit.nearest(snow,eye,layer,3.25,end -> {
            BlockHitResult wall=AABB.clip(FULL,eye,end,obstruction);
            return wall!=null ? wall : AABB.clip(layer,eye,end,snow);
        }));
    }

    @Test void nativePlacementMustReplaceTheClickedSnowCellAndNeverPlaceAboveIt() {
        BlockPos target=SOIL.above();
        BlockHitResult snow=new BlockHitResult(new Vec3(.5,1.125,.5),Direction.UP,target,false);
        assertTrue(NativeLoggingPlantHit.placementTargetsCell(target,snow,target,true,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,snow,target.above(),true,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,snow,SOIL,true,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,snow,target,true,false,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,snow,target,true,true,false,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,snow,target,true,true,true,false));
    }

    @Test void normalAirPlacementMustStillClickSoilWithoutReplacingIt() {
        BlockPos target=SOIL.above();
        BlockHitResult soil=new BlockHitResult(new Vec3(.5,1,.5),Direction.UP,SOIL,false);
        assertTrue(NativeLoggingPlantHit.placementTargetsCell(target,soil,target,false,false,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,soil,target,false,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,soil,target,true,true,true,true));
        BlockHitResult wrongSurface=new BlockHitResult(new Vec3(.5,1,.5),Direction.UP,target,false);
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,wrongSurface,target,false,false,true,true));
    }

    @Test void placementContextCannotAuthorizeUnknownTargetMissInsideOrNonUpFace() {
        BlockPos target=SOIL.above(); Vec3 top=new Vec3(.5,1.125,.5);
        for(BlockHitResult hit:List.of(BlockHitResult.miss(top,Direction.UP,target),
            new BlockHitResult(top,Direction.UP,target,true),new BlockHitResult(top,Direction.NORTH,target,false),
            new BlockHitResult(top,Direction.UP,target.east(),false)))
            assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,hit,target,true,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,null,target,true,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(null,null,target,true,true,true,true));
        assertFalse(NativeLoggingPlantHit.placementTargetsCell(target,new BlockHitResult(top,Direction.UP,target,false),null,true,true,true,true));
    }

    @Test void liveAdapterRequiresNativeSingleSnowLayerContextSurvivalAndFreshHitBeforeUse() throws Exception {
        String source=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/schwalbe/autovalley/client/NativeLoggingActions.java"));
        assertTrue(source.contains("state.is(Blocks.SNOW) && state.hasProperty(SnowLayerBlock.LAYERS)"));
        assertTrue(source.contains("state.getValue(SnowLayerBlock.LAYERS)==1"));
        assertTrue(source.contains("if (!cell.isAir() && !snow) return null;"));
        assertTrue(source.contains("Blocks.SPRUCE_SAPLING.defaultBlockState().canSurvive(mc.level,base)"));
        assertTrue(source.contains("var surface=snow ? base : soil;"));
        assertTrue(source.contains("ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE"));
        assertTrue(source.contains("placement.getClickedPos()"));
        assertTrue(source.contains("placement.replacingClickedOnBlock(),cell.canBeReplaced(placement),placement.canPlace()"));
        assertTrue(source.contains("original.isAir(),singleSnowLayer(original),!held.isEmpty() && held.is(Items.SPRUCE_SAPLING)"));
        String begin=source.substring(source.indexOf("void begin("),source.indexOf("String advance("));
        assertTrue(begin.indexOf("BlockHitResult hit=plantHit(mc,target)")<begin.indexOf("mc.gameMode.useItemOn"));
        String planting=begin.substring(0,begin.indexOf("} else {"));
        assertFalse(planting.contains("DESTROY_BLOCK")); assertFalse(planting.contains("setBlock"));
        assertFalse(planting.contains("removeBlock"));
    }
}
