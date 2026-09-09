package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.List;

/** Pure proof for moving toward the centre of a full floor already under the actual body. */
final class SupportRecenterGeometry {
    static final double EPS=1.0e-5,MAX_DISTANCE=1.25;
    static final int MAX_CELLS=64;
    private SupportRecenterGeometry() { }
    record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
        boolean valid() {
            return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                && maxX>minX && maxY>minY && maxZ>minZ;
        }
        boolean intersects(Box other,int x,int y,int z) {
            return maxX>other.minX+x && minX<other.maxX+x && maxY>other.minY+y && minY<other.maxY+y
                && maxZ>other.minZ+z && minZ<other.maxZ+z;
        }
    }
    record Cell(boolean loaded,boolean forbidden,List<Box> boxes) { }
    @FunctionalInterface interface Cells { Cell at(Pos p); }

    static Box sweep(double x,double y,double z,double width,double height,Box actual,Pos feet,
                     List<Box> defaultFloor,List<Box> playerFloor) {
        if (feet==null || Math.abs((long)feet.x())>29_999_984 || Math.abs((long)feet.z())>29_999_984
                || feet.y() < -2032 || feet.y()>1967 || actual==null || !actual.valid()
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(width) || !Double.isFinite(height)
                || Math.abs(width-.6)>=EPS || Math.abs(height-1.8)>=EPS
                || Math.abs(actual.minX-(x-width/2))>=EPS || Math.abs(actual.maxX-(x+width/2))>=EPS
                || Math.abs(actual.minY-y)>=EPS || Math.abs(actual.maxY-(y+height))>=EPS
                || Math.abs(actual.minZ-(z-width/2))>=EPS || Math.abs(actual.maxZ-(z+width/2))>=EPS
                || defaultFloor==null || playerFloor==null || defaultFloor.size()!=1 || !defaultFloor.equals(playerFloor)) return null;
        Box floor=defaultFloor.get(0);
        if (!localBox(floor) || floor.minX!=0 || floor.minZ!=0 || floor.maxX!=1 || floor.maxZ!=1
                || Math.abs(feet.y()-1+floor.maxY-y)>=EPS
                || Math.hypot(feet.x()+.5-x,feet.z()+.5-z)>MAX_DISTANCE) return null;
        // Strict overlap, without an epsilon that could turn touching/air into support.
        if (actual.maxX<=feet.x() || actual.minX>=feet.x()+1
                || actual.maxZ<=feet.z() || actual.minZ>=feet.z()+1) return null;
        // Toward this rectangle's centre, each axis' overlap can only increase.
        // The enclosing sweep is stronger than point samples, including the diagonal corners.
        Box sweep=new Box(Math.min(actual.minX,feet.x()+.5-width/2)-EPS,y+EPS,
            Math.min(actual.minZ,feet.z()+.5-width/2)-EPS,
            Math.max(actual.maxX,feet.x()+.5+width/2)+EPS,y+height-EPS,
            Math.max(actual.maxZ,feet.z()+.5+width/2)+EPS);
        return bounded(sweep) ? sweep : null;
    }
    static boolean clear(Box sweep,Cells cells) {
        if (!bounded(sweep) || cells==null) return false;
        int minY=(int)Math.floor(sweep.minY-2*EPS);
        for(int x=(int)Math.floor(sweep.minX);x<=(int)Math.floor(sweep.maxX);x++)
            for(int z=(int)Math.floor(sweep.minZ);z<=(int)Math.floor(sweep.maxZ);z++)
                for(int y=minY;y<=(int)Math.floor(sweep.maxY);y++) {
                    Cell cell=cells.at(new Pos(x,y,z));
                    if(cell==null || !cell.loaded || cell.forbidden || cell.boxes==null || cell.boxes.size()>64) return false;
                    for(Box box:cell.boxes) if(!localBox(box) || sweep.intersects(box,x,y,z)) return false;
                }
        return true;
    }
    private static boolean bounded(Box body) {
        if(body==null || !body.valid() || Math.abs(body.minX)>29_999_990 || Math.abs(body.maxX)>29_999_990
                || Math.abs(body.minZ)>29_999_990 || Math.abs(body.maxZ)>29_999_990 || body.minY < -2040 || body.maxY>1980) return false;
        long x=(long)Math.floor(body.maxX)-(long)Math.floor(body.minX)+1;
        long y=(long)Math.floor(body.maxY)-(long)Math.floor(body.minY-2*EPS)+1;
        long z=(long)Math.floor(body.maxZ)-(long)Math.floor(body.minZ)+1;
        return x>0 && y>0 && z>0 && x<=MAX_CELLS && y<=MAX_CELLS && z<=MAX_CELLS && x*y*z<=MAX_CELLS;
    }
    private static boolean localBox(Box box) {
        return box!=null && box.valid() && box.minX>=0 && box.minY>=0 && box.minZ>=0
            && box.maxX<=1 && box.maxY<=1 && box.maxZ<=1;
    }
}
