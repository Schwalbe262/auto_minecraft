package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Bounded read-only clearance proof, not permission to skip a physical landing. */
final class NativeDescentChain {
    private static final double EPS=1.0e-5,LANE_MARGIN=.10;
    private NativeDescentChain() { }

    static boolean mayChain(Minecraft mc,MinecraftWorld world,List<Pos> feet,Profile profile) {
        try {
            if (mc==null || mc.level==null || profile==null || !NativeLoggingJump.normalPhysics(mc)
                    || !shape(feet)) return false;
            List<Double> heights=new ArrayList<>();
            for (int i=0;i<feet.size();i++) {
                Pos p=feet.get(i);
                if (!world.canStand(p) || (i>0 && !world.canTraverse(feet.get(i-1),p))) return false;
                heights.add(world.standingY(p));
            }
            NativeLoggingJump.Cells cells=p -> {
                if (!world.loaded(p)) return new NativeLoggingJump.Cell(false,false,true,false,List.of());
                BlockPos bp=MinecraftWorld.nativePos(p);
                var state=mc.level.getBlockState(bp);
                var block=state.getBlock();
                return new NativeLoggingJump.Cell(true,true,
                    NativeLoggingJump.forbiddenBlock(state) || state.is(BlockTags.CLIMBABLE)
                        || NativeLoggingJump.protectedPlanting(profile,p),
                    NativeLoggingJump.normalSurface(block.getJumpFactor(),block.getSpeedFactor(),block.getFriction()),
                    state.getCollisionShape(mc.level,bp,CollisionContext.of(mc.player)).toAabbs());
            };
            double width=mc.player.getBbWidth(),height=mc.player.getBbHeight();
            int dx=feet.get(1).x()-feet.get(0).x(),dz=feet.get(1).z()-feet.get(0).z();
            Predicate<Pos> ordinaryStair=p -> {
                var state=mc.level.getBlockState(MinecraftWorld.nativePos(p));
                if (!(state.getBlock() instanceof StairBlock) || state.getValue(StairBlock.HALF)!=Half.BOTTOM
                        || state.getValue(StairBlock.SHAPE)!=StairsShape.STRAIGHT) return false;
                var facing=state.getValue(StairBlock.FACING);
                return facing.getStepX()==-dx && facing.getStepZ()==-dz;
            };
            if (!geometry(feet,heights,width,height,cells,ordinaryStair)) return false;
            for (int i=1;i<feet.size();i++) {
                AABB sweep=sweep(feet.get(i-1),feet.get(i),heights.get(i-1),heights.get(i),width,height);
                if (!world.insideBorder(sweep) || !mc.level.getEntityCollisions(mc.player,sweep).isEmpty()) return false;
            }
            return true;
        } catch (RuntimeException unavailable) { return false; }
    }

    static boolean shape(List<Pos> feet) {
        if (feet==null || feet.size()<3 || feet.size()>4 || feet.stream().anyMatch(Objects::isNull)) return false;
        int dx=feet.get(1).x()-feet.get(0).x(),dz=feet.get(1).z()-feet.get(0).z();
        if (Math.abs(dx)+Math.abs(dz)!=1) return false;
        for (int i=1;i<feet.size();i++) {
            Pos from=feet.get(i-1),to=feet.get(i);
            if (to.x()-from.x()!=dx || to.z()-from.z()!=dz || to.y()>from.y() || from.y()-to.y()>1) return false;
        }
        return true;
    }

    static boolean geometry(List<Pos> feet,List<Double> heights,double width,double height,NativeLoggingJump.Cells cells) {
        return geometry(feet,heights,width,height,cells,p -> false);
    }
    static boolean geometry(List<Pos> feet,List<Double> heights,double width,double height,NativeLoggingJump.Cells cells,
                            Predicate<Pos> ordinaryStair) {
        if (!shape(feet) || heights==null || heights.size()!=feet.size() || cells==null
                || ordinaryStair==null || !NativeLoggingJump.dimensions(width,height)) return false;
        int dx=feet.get(1).x()-feet.get(0).x(),dz=feet.get(1).z()-feet.get(0).z();
        for (int i=0;i<feet.size();i++) {
            Double y=heights.get(i);
            if (y==null || !Double.isFinite(y)) return false;
            if (i>0 && (heights.get(i-1)-y<=.10001 || heights.get(i-1)-y>1+EPS)) return false;
            Pos floor=feet.get(i).offset(0,-1,0);
            if (!support(cells.at(floor),floor,y,ordinaryStair.test(floor),dx,dz)) return false;
        }
        for (int i=1;i<feet.size();i++) {
            Pos from=feet.get(i-1),to=feet.get(i);
            AABB body=sweep(from,to,heights.get(i-1),heights.get(i),width,height);
            for (int x=(int)Math.floor(body.minX);x<=(int)Math.floor(body.maxX);x++)
                for (int z=(int)Math.floor(body.minZ);z<=(int)Math.floor(body.maxZ);z++)
                    for (int y=(int)Math.floor(body.minY);y<=(int)Math.floor(body.maxY);y++) {
                        Pos p=new Pos(x,y,z); var cell=cells.at(p);
                        if (!safe(cell)) return false;
                        for (AABB box:cell.boxes()) {
                            if (!validBox(box)) return false;
                            if (!body.intersects(box.move(x,y,z))) continue;
                            // The verified upper tread intersects this conservative vertical envelope.
                            // No other obstacle, including a wall above that tread, is excused.
                            if (!p.equals(from.offset(0,-1,0)) && !p.equals(to.offset(0,-1,0))) return false;
                        }
                    }
        }
        return true;
    }

    static AABB sweep(Pos from,Pos to,double fromY,double toY,double width,double height) {
        double radius=width/2+LANE_MARGIN;
        return new AABB(Math.min(from.x(),to.x())+.5-radius,toY+EPS,
            Math.min(from.z(),to.z())+.5-radius,Math.max(from.x(),to.x())+.5+radius,
            fromY+height-EPS,Math.max(from.z(),to.z())+.5+radius);
    }
    private static boolean support(NativeLoggingJump.Cell cell,Pos floor,double y,boolean ordinaryStair,int dx,int dz) {
        if (!safe(cell) || !cell.normalSurface() || cell.boxes().isEmpty()) return false;
        if (cell.boxes().size()==1) {
            AABB box=cell.boxes().get(0);
            if (validBox(box) && Math.abs(box.minX)<EPS && Math.abs(box.minZ)<EPS
                    && Math.abs(box.maxX-1)<EPS && Math.abs(box.maxZ-1)<EPS && Math.abs(floor.y()+box.maxY-y)<EPS) return true;
        }
        return ordinaryStair && Math.abs(floor.y()+1-y)<EPS && straightStairShape(cell.boxes(),dx,dz);
    }
    /** Exact six-octant bottom/straight staircase, independent of native box merging/order. */
    static boolean straightStairShape(List<AABB> boxes,int dx,int dz) {
        if (boxes==null || boxes.isEmpty() || boxes.size()>8 || Math.abs(dx)+Math.abs(dz)!=1) return false;
        for (AABB box:boxes) {
            if (!validBox(box)) return false;
            for (double edge:new double[]{box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ})
                if (edge!=0 && edge!=.5 && edge!=1) return false;
        }
        for (double x:new double[]{.25,.75}) for (double y:new double[]{.25,.75}) for (double z:new double[]{.25,.75}) {
            boolean expected=y<.5 || (x-.5)*dx+(z-.5)*dz<0;
            boolean filled=false;
            for (AABB box:boxes) if (box.contains(x,y,z)) { filled=true;break; }
            if (filled!=expected) return false;
        }
        return true;
    }
    private static boolean safe(NativeLoggingJump.Cell cell) {
        return cell!=null && cell.loaded() && cell.bounded() && !cell.forbidden();
    }
    private static boolean validBox(AABB b) {
        return b!=null && Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
            && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ)
            && b.minX>=0 && b.minY>=0 && b.minZ>=0 && b.maxX<=1 && b.maxY<=1 && b.maxZ<=1
            && b.maxX>b.minX && b.maxY>b.minY && b.maxZ>b.minZ;
    }
}
