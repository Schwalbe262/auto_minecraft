package dev.schwalbe.autovalley.client;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A real first-world leaf hit, never a ray which skips an obstruction. */
final class NativeLoggingLeafHit {
    private static final double EPS=1.0e-7;
    private NativeLoggingLeafHit() { }

    static BlockPos obstruction(BlockPos base,Vec3 eye,List<AABB> boxes,double reach,
            Function<Vec3,BlockHitResult> clip,Predicate<BlockPos> eligibleLeaf) {
        if (base==null || !finite(eye) || !Double.isFinite(reach) || reach<=0 || clip==null || eligibleLeaf==null) return null;
        double limit=Math.min(4,reach);
        double dx=Math.max(0,Math.max(base.getX()-eye.x,eye.x-base.getX()-1));
        double dy=Math.max(0,Math.max(base.getY()-eye.y,eye.y-base.getY()-1));
        double dz=Math.max(0,Math.max(base.getZ()-eye.z,eye.z-base.getZ()-1));
        if (dx*dx+dy*dy+dz*dz>limit*limit) return null;
        // This validates the current outline and supplies only its interior points.
        List<Vec3> samples=NativeChoppedLogHit.candidates(base,eye,boxes);
        BlockPos chosen=null;
        for (Vec3 end:samples) {
            BlockHitResult hit=clip.apply(end);
            if (hit==null) return null; // Unavailable native geometry is not negative visibility.
            if (hit.getType()!=HitResult.Type.BLOCK) continue;
            Vec3 point=hit.getLocation();
            if (!finite(point)) return null;
            double distance=eye.distanceTo(point);
            if (base.equals(hit.getBlockPos())) {
                // A far interior sample can still reveal a reachable near face.
                if (!hit.isInside() && distance<=limit && onShape(base,boxes,point)) return null;
                continue;
            }
            // Only a reachable base sample proves a useful obstruction. A nearby
            // leaf in front of an out-of-reach base is not a clearing permission.
            BlockPos leaf=hit.getBlockPos();
            if (chosen==null && eye.distanceTo(end)<=limit && distance<=limit
                    && inCell(leaf,point) && eligibleLeaf.test(leaf))
                chosen=leaf.immutable(); // Inside hits are permitted only for this exact leaf.
        }
        return chosen;
    }
    private static boolean finite(Vec3 p) { return p!=null && Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
    private static boolean inCell(BlockPos p,Vec3 point) {
        return point.x>=p.getX()-EPS && point.x<=p.getX()+1+EPS
            && point.y>=p.getY()-EPS && point.y<=p.getY()+1+EPS
            && point.z>=p.getZ()-EPS && point.z<=p.getZ()+1+EPS;
    }
    private static boolean onShape(BlockPos p,List<AABB> boxes,Vec3 point) {
        double x=point.x-p.getX(),y=point.y-p.getY(),z=point.z-p.getZ();
        return boxes.stream().anyMatch(b -> x>=b.minX-EPS && x<=b.maxX+EPS
            && y>=b.minY-EPS && y<=b.maxY+EPS && z>=b.minZ-EPS && z<=b.maxZ+EPS);
    }
}
