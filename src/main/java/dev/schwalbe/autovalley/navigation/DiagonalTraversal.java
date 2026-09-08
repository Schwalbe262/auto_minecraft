package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;

/** A one-cell flat diagonal, never a long shortcut or a cut across a blocked corner. */
public final class DiagonalTraversal {
    private DiagonalTraversal() { }

    public static boolean canTraverse(Pos from,Pos to,WorldAccess world,ProfileBounds bounds) {
        int dx=to.x()-from.x(),dz=to.z()-from.z();
        if (from.y()!=to.y() || Math.abs(dx)!=1 || Math.abs(dz)!=1) return false;
        Pos sideX=from.offset(dx,0,0),sideZ=from.offset(0,0,dz);
        double height=world.standingY(from);
        if (!Double.isFinite(height)) return false;
        for (Pos cell:new Pos[]{from,sideX,sideZ,to}) {
            if (!bounds.contains(cell) || !world.loaded(cell) || !world.loaded(cell.offset(0,-1,0))
                    || !world.loaded(cell.offset(0,1,0)) || !world.canStand(cell)
                    || !Double.isFinite(world.standingY(cell)) || Math.abs(world.standingY(cell)-height)>1.0e-4)
                return false;
            for (int dy=0;dy<=1;dy++) {
                BlockData block=world.block(cell.offset(0,dy,0));
                // Even an open door keeps the established cardinal approach. Native
                // adapters additionally identify DoorBlock subclasses by their type.
                if (block==null || block.id()==null || block.id().endsWith("_door")) return false;
            }
        }
        return world.canTraverse(from,sideX) && world.canTraverse(sideX,to)
                && world.canTraverse(from,sideZ) && world.canTraverse(sideZ,to)
                && world.canTraverseDiagonal(from,to);
    }
}
