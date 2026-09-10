package dev.schwalbe.autovalley.client;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Bounded native soil/single-layer-snow UP-face selection; an occluder is never ignored. */
final class NativeLoggingPlantHit {
    static final int MAX_BOXES=16,MAX_CANDIDATES=MAX_BOXES*9;
    private static final double EPS=1.0e-7;
    private NativeLoggingPlantHit() { }

    /** The native context must place inside the registered cell, never above snow or into its soil. */
    static boolean placementTargetsCell(BlockPos target,BlockHitResult hit,BlockPos nativePlacement,
            boolean singleSnowLayer,boolean replacingClicked,boolean canReplaceCell,boolean canPlace) {
        if (target==null || hit==null || nativePlacement==null || !target.equals(nativePlacement)
            || hit.getType()!=HitResult.Type.BLOCK || hit.isInside() || hit.getDirection()!=Direction.UP
            || !canReplaceCell || !canPlace || replacingClicked!=singleSnowLayer) return false;
        return (singleSnowLayer ? target : target.below()).equals(hit.getBlockPos());
    }

    static BlockHitResult nearest(BlockPos soil,Vec3 eye,List<AABB> boxes,double reach,Function<Vec3,BlockHitResult> clip) {
        if (soil==null || !finite(eye) || !Double.isFinite(reach) || reach<=0 || clip==null) return null;
        List<Vec3> points=candidates(soil,boxes);
        double limit=Math.min(4,reach),bestDistance=Double.POSITIVE_INFINITY;
        BlockHitResult best=null;
        for(Vec3 point:points) {
            // End just inside this real outline box so native clipping intersects
            // its top plane. The returned native hit, not the synthetic endpoint,
            // is the only candidate that can later be sent to useItemOn.
            BlockHitResult hit=clip.apply(point);
            if (hit==null || hit.getType()!=HitResult.Type.BLOCK || hit.isInside()
                || !soil.equals(hit.getBlockPos()) || hit.getDirection()!=Direction.UP
                || !finite(hit.getLocation()) || !onTopInterior(soil,boxes,hit.getLocation())) continue;
            double distance=eye.distanceTo(hit.getLocation());
            if (distance<=limit && distance<bestDistance) { best=hit; bestDistance=distance; }
        }
        return best;
    }

    static List<Vec3> candidates(BlockPos soil,List<AABB> boxes) {
        if (soil==null || boxes==null || boxes.isEmpty() || boxes.size()>MAX_BOXES
            || boxes.stream().anyMatch(b -> !valid(b))) return List.of();
        LinkedHashSet<Vec3> points=new LinkedHashSet<>();
        for(AABB b:boxes) {
            double y=b.maxY-Math.min(.001,(b.maxY-b.minY)/4);
            for(double x:new double[]{.5,.25,.75}) for(double z:new double[]{.5,.25,.75})
                points.add(new Vec3(soil.getX()+b.minX+x*(b.maxX-b.minX),soil.getY()+y,
                    soil.getZ()+b.minZ+z*(b.maxZ-b.minZ)));
        }
        return List.copyOf(points);
    }
    private static boolean onTopInterior(BlockPos soil,List<AABB> boxes,Vec3 point) {
        double x=point.x-soil.getX(),y=point.y-soil.getY(),z=point.z-soil.getZ();
        return boxes.stream().anyMatch(b -> x>b.minX+EPS && x<b.maxX-EPS && z>b.minZ+EPS && z<b.maxZ-EPS
            && Math.abs(y-b.maxY)<=EPS);
    }
    private static boolean valid(AABB b) {
        return b!=null && finite(new Vec3(b.minX,b.minY,b.minZ)) && finite(new Vec3(b.maxX,b.maxY,b.maxZ))
            && b.minX>=0 && b.minY>=0 && b.minZ>=0 && b.maxX<=1 && b.maxY<=1 && b.maxZ<=1
            && b.maxX-b.minX>EPS && b.maxY-b.minY>EPS && b.maxZ-b.minZ>EPS;
    }
    private static boolean finite(Vec3 p) { return p!=null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
}
