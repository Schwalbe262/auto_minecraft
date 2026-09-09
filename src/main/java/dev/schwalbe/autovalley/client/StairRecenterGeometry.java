package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.List;
import net.minecraft.world.phys.AABB;

/** Read-only proof for the one ordinary half-step inside an already occupied stair cell. */
final class StairRecenterGeometry {
    static final double EPS=1.0e-5;
    static final int MAX_CELLS=128;
    private StairRecenterGeometry() { }
    record Proof(Pos support,List<AABB> shape,AABB envelope,AABB raisedSweep,AABB queryBounds) { }

    static Proof inspect(double x,double y,double z,double width,double height,AABB actual,Pos anchor,
                         double maxUpStep,boolean ordinaryStair,int facingX,int facingZ,
                         List<AABB> defaultShape,List<AABB> playerShape) {
        if (anchor==null || Math.abs((long)anchor.x())>29_999_984 || Math.abs((long)anchor.z())>29_999_984
                || anchor.y() < -2032 || anchor.y()>1967 || !ordinaryStair
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(maxUpStep) || maxUpStep<.5 || !NativeLoggingJump.dimensions(width,height)
                || !valid(actual) || Math.abs(actual.minX-(x-width/2))>=EPS || Math.abs(actual.maxX-(x+width/2))>=EPS
                || Math.abs(actual.minY-y)>=EPS || Math.abs(actual.maxY-(y+height))>=EPS
                || Math.abs(actual.minZ-(z-width/2))>=EPS || Math.abs(actual.maxZ-(z+width/2))>=EPS
                || defaultShape==null || !defaultShape.equals(playerShape)
                || !NativeDescentChain.straightStairShape(defaultShape,-facingX,-facingZ)) return null;
        Pos support=anchor.offset(0,-1,0);
        // Never select an inferred neighbouring start, even if one toe overlaps it.
        if (Math.floor(x)!=support.x() || Math.floor(z)!=support.z()
                || Math.hypot(anchor.x()+.5-x,anchor.z()+.5-z)>1.25) return null;
        boolean lower=Math.abs(y-(support.y()+.5))<EPS,upper=Math.abs(y-anchor.y())<EPS;
        if (!lower && !upper) return null;
        AABB high=new AABB(support.x()+(facingX>0?.5:0),support.y()+.5,support.z()+(facingZ>0?.5:0),
            support.x()+(facingX<0?.5:1),support.y()+1,support.z()+(facingZ<0?.5:1));
        boolean highOverlap=overlapXZ(actual,high);
        if (lower && highOverlap || upper && !highOverlap) return null;
        if (!overlapXZ(actual,new AABB(support.x(),support.y(),support.z(),support.x()+1,support.y()+.5,support.z()+1))) return null;
        // No sideways traversal across a narrow corner: the whole lateral width is on this stair.
        double lateralMin=facingX==0?actual.minX:actual.minZ,lateralMax=facingX==0?actual.maxX:actual.maxZ;
        int lateralCell=facingX==0?support.x():support.z();
        if (lateralMin<lateralCell-EPS || lateralMax>lateralCell+1+EPS) return null;
        // Moving to the centre increases low-tread support and then high-tread support;
        // once on the upper tread, overlap with its half-rectangle remains positive.
        double radius=width/2;
        // The exact convex XZ hull already contains the entire translating body.
        // Inflating it would falsely collide with a wall currently just touching
        // the body, even though the only permitted movement is inward/away.
        AABB envelope=new AABB(Math.min(actual.minX,anchor.x()+.5-radius),y+EPS,
            Math.min(actual.minZ,anchor.z()+.5-radius),Math.max(actual.maxX,anchor.x()+.5+radius),
            anchor.y()+height-EPS,Math.max(actual.maxZ,anchor.z()+.5+radius));
        AABB raised=new AABB(envelope.minX,anchor.y()+EPS,envelope.minZ,envelope.maxX,envelope.maxY,envelope.maxZ);
        // Native block-collision iteration includes neighbouring shapes. Preflight that
        // entire one-cell halo too, so a read cannot cause an unknown chunk to load.
        AABB query=envelope.inflate(1);
        if (!bounded(query)) return null;
        return new Proof(support,List.copyOf(defaultShape),envelope,raised,query);
    }

    static boolean clear(Proof proof,NativeLoggingJump.Cells cells) {
        if (proof==null || cells==null || !bounded(proof.queryBounds())) return false;
        AABB query=proof.queryBounds(),body=proof.envelope();
        for (int x=(int)Math.floor(query.minX);x<=(int)Math.floor(query.maxX);x++)
            for (int z=(int)Math.floor(query.minZ);z<=(int)Math.floor(query.maxZ);z++)
                for (int y=(int)Math.floor(query.minY);y<=(int)Math.floor(query.maxY);y++) {
                    Pos p=new Pos(x,y,z); var cell=cells.at(p);
                    if (cell==null || !cell.loaded() || !cell.bounded() || cell.boxes()==null || cell.boxes().size()>64) return false;
                    boolean touched=body.intersects(new AABB(x,y,z,x+1,y+1,z+1)) || p.equals(proof.support());
                    if (touched && (cell.forbidden() || !cell.normalSurface())) return false;
                    if (p.equals(proof.support()) && !proof.shape().equals(cell.boxes())) return false;
                    for (AABB box:cell.boxes()) {
                        if (!local(box)) return false;
                        if (body.intersects(box.move(x,y,z)) && !p.equals(proof.support())) return false;
                    }
                }
        return true;
    }
    private static boolean overlapXZ(AABB a,AABB b) {
        return a.maxX>b.minX && a.minX<b.maxX && a.maxZ>b.minZ && a.minZ<b.maxZ;
    }
    private static boolean valid(AABB b) {
        return b!=null && Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
            && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ)
            && b.maxX>b.minX && b.maxY>b.minY && b.maxZ>b.minZ;
    }
    private static boolean local(AABB b) {
        return valid(b) && b.minX>=0 && b.minY>=0 && b.minZ>=0 && b.maxX<=1 && b.maxY<=1 && b.maxZ<=1;
    }
    private static boolean bounded(AABB b) {
        if (!valid(b) || Math.abs(b.minX)>29_999_990 || Math.abs(b.maxX)>29_999_990
                || Math.abs(b.minZ)>29_999_990 || Math.abs(b.maxZ)>29_999_990 || b.minY < -2040 || b.maxY>1980) return false;
        long x=(long)Math.floor(b.maxX)-(long)Math.floor(b.minX)+1;
        long y=(long)Math.floor(b.maxY)-(long)Math.floor(b.minY)+1;
        long z=(long)Math.floor(b.maxZ)-(long)Math.floor(b.minZ)+1;
        return x>0 && y>0 && z>0 && x<=MAX_CELLS && y<=MAX_CELLS && z<=MAX_CELLS && x*y*z<=MAX_CELLS;
    }
}
