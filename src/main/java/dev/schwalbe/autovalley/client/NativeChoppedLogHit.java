package dev.schwalbe.autovalley.client;

import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Aim candidates for TreeChop's shrinking, sometimes off-centre log outline.
 * Installed 0.19.0 ChoppedLogBlock#getShape uses its entity's current shape and
 * radius, not a full block cube. Candidates are strictly inside those native
 * outline boxes; only the real world's first OUTLINE hit grants visibility.
 */
final class NativeChoppedLogHit {
    static final int MAX_BOXES=16;
    private static final double INSET=1.0e-4,EPS=1.0e-7;
    private NativeChoppedLogHit() { }

    static BlockHitResult nearest(BlockPos target,Vec3 eye,List<AABB> boxes,double reach,
            Function<Vec3,BlockHitResult> clip) {
        if (target==null || !finite(eye) || clip==null || !Double.isFinite(reach) || reach<=0) return null;
        List<Vec3> candidates=candidates(target,eye,boxes);
        double limit=Math.min(4,reach),bestDistance=Double.POSITIVE_INFINITY;
        BlockHitResult best=null;
        for (Vec3 end:candidates) {
            BlockHitResult hit=clip.apply(end);
            if (hit==null || hit.getType()!=HitResult.Type.BLOCK || !target.equals(hit.getBlockPos())
                    || hit.isInside() || !finite(hit.getLocation()) || !onShape(target,boxes,hit.getLocation())) continue;
            double distance=eye.distanceTo(hit.getLocation());
            if (distance<=limit && distance<bestDistance) { best=hit; bestDistance=distance; }
        }
        return best;
    }

    static List<Vec3> candidates(BlockPos target,Vec3 eye,List<AABB> boxes) {
        if (target==null || !finite(eye) || boxes==null || boxes.isEmpty() || boxes.size()>MAX_BOXES
                || boxes.stream().anyMatch(box -> !valid(box))) return List.of();
        LinkedHashSet<Vec3> points=new LinkedHashSet<>();
        Vec3 localEye=eye.subtract(target.getX(),target.getY(),target.getZ());
        for (AABB box:boxes) {
            double[] xs=axis(box.minX,box.maxX),ys=axis(box.minY,box.maxY),zs=axis(box.minZ,box.maxZ);
            // Closest interior point finds the near exposed face even when the
            // box centre is too far away or hidden by another part of the tree.
            points.add(world(target,new Vec3(clamp(localEye.x,xs[0],xs[2]),clamp(localEye.y,ys[0],ys[2]),clamp(localEye.z,zs[0],zs[2]))));
            // Centre plus face/edge/corner interiors: no endpoint lies on a
            // zero-width boundary or outside the current authoritative outline.
            for (double x:xs) for (double y:ys) for (double z:zs) points.add(world(target,new Vec3(x,y,z)));
        }
        return List.copyOf(points);
    }
    private static double[] axis(double min,double max) {
        double inset=Math.min(INSET,(max-min)/4);
        return new double[]{min+inset,(min+max)/2,max-inset};
    }
    private static Vec3 world(BlockPos pos,Vec3 local) { return local.add(pos.getX(),pos.getY(),pos.getZ()); }
    private static double clamp(double value,double min,double max) { return Math.max(min,Math.min(max,value)); }
    private static boolean valid(AABB box) {
        return box!=null && finite(new Vec3(box.minX,box.minY,box.minZ)) && finite(new Vec3(box.maxX,box.maxY,box.maxZ))
            && box.minX>=0 && box.minY>=0 && box.minZ>=0 && box.maxX<=1 && box.maxY<=1 && box.maxZ<=1
            && box.maxX-box.minX>EPS && box.maxY-box.minY>EPS && box.maxZ-box.minZ>EPS;
    }
    private static boolean finite(Vec3 point) {
        return point!=null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
    private static boolean onShape(BlockPos pos,List<AABB> boxes,Vec3 point) {
        double x=point.x-pos.getX(),y=point.y-pos.getY(),z=point.z-pos.getZ();
        for (AABB b:boxes) if (x>=b.minX-EPS && x<=b.maxX+EPS && y>=b.minY-EPS && y<=b.maxY+EPS
            && z>=b.minZ-EPS && z<=b.maxZ+EPS) return true;
        return false;
    }
}
