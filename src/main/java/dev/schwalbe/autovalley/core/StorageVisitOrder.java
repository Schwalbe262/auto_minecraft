package dev.schwalbe.autovalley.core;

import java.util.*;

/**
 * Pure visit ordering for supplied registrations, not a movement path or a grant
 * of access. Finish each same-kind, face-adjacent component before selecting the
 * next component. Neither block contents nor walkability are inferred here.
 */
public final class StorageVisitOrder {
    private static final int MAX_REGISTRATIONS=4096;
    private static final int[][] FACES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private static final Comparator<Poi> TIE=Comparator.comparingInt((Poi p)->p.pos().x())
        .thenComparingInt(p->p.pos().y()).thenComparingInt(p->p.pos().z())
        .thenComparing(p->p.kind().name())
        .thenComparing(Poi::label,Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(Poi::classifier,Comparator.nullsFirst(Comparator.naturalOrder()));
    private record Cell(PoiKind kind,Pos pos) { }
    private record Cursor(double x,double y,double z) {
        double distanceSquared(Poi poi) {
            Pos p=poi.pos(); double dx=x-p.x()-.5,dy=y-p.y(),dz=z-p.z()-.5;
            return dx*dx+dy*dy+dz*dz;
        }
        static Cursor at(Poi poi) { return new Cursor(poi.pos().x()+.5,poi.pos().y(),poi.pos().z()+.5); }
    }
    private StorageVisitOrder() { }

    public static List<Poi> order(List<Poi> registrations,PlayerState origin) {
        if(registrations==null || registrations.size()>MAX_REGISTRATIONS || origin==null
            || !Double.isFinite(origin.x()) || !Double.isFinite(origin.y()) || !Double.isFinite(origin.z()))
            throw new IllegalArgumentException("Invalid or oversized storage visit request");
        List<Poi> sorted=new ArrayList<>(registrations);
        for(Poi poi:sorted) if(poi==null || poi.pos()==null || poi.kind()==null)
            throw new IllegalArgumentException("Incomplete storage registration");
        sorted.sort(TIE);
        // Retain every supplied registration, including coincident entries. The
        // profile validator owns duplicate-registration policy, not this ordering.
        Map<Cell,List<Poi>> cells=new LinkedHashMap<>();
        for(Poi poi:sorted) cells.computeIfAbsent(new Cell(poi.kind(),poi.pos()),key->new ArrayList<>()).add(poi);
        Set<Cell> unvisited=new HashSet<>(cells.keySet());
        List<List<Poi>> components=new ArrayList<>();
        for(Cell start:cells.keySet()) {
            if(!unvisited.remove(start)) continue;
            List<Poi> component=new ArrayList<>(); Deque<Cell> queue=new ArrayDeque<>(); queue.add(start);
            while(!queue.isEmpty()) {
                Cell cell=queue.removeFirst(); component.addAll(cells.get(cell));
                for(int[] face:FACES) {
                    long x=(long)cell.pos().x()+face[0],y=(long)cell.pos().y()+face[1],z=(long)cell.pos().z()+face[2];
                    if(x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || y<Integer.MIN_VALUE || y>Integer.MAX_VALUE
                        || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE) continue;
                    Cell next=new Cell(cell.kind(),new Pos((int)x,(int)y,(int)z));
                    if(unvisited.remove(next)) queue.addLast(next);
                }
            }
            components.add(component);
        }
        List<Poi> result=new ArrayList<>(sorted.size()); Cursor cursor=new Cursor(origin.x(),origin.y(),origin.z());
        while(!components.isEmpty()) {
            int chosen=0; Poi entrance=null;
            for(int index=0;index<components.size();index++) {
                List<Poi> candidate=components.get(index); Poi nearest=candidate.get(nearest(candidate,cursor));
                if(entrance==null || compare(nearest,entrance,cursor)<0) { chosen=index; entrance=nearest; }
            }
            List<Poi> component=components.remove(chosen);
            while(!component.isEmpty()) {
                Poi next=component.remove(nearest(component,cursor)); result.add(next); cursor=Cursor.at(next);
            }
        }
        return List.copyOf(result);
    }

    private static int nearest(List<Poi> candidates,Cursor cursor) {
        int best=0;
        for(int index=1;index<candidates.size();index++)
            if(compare(candidates.get(index),candidates.get(best),cursor)<0) best=index;
        return best;
    }
    private static int compare(Poi first,Poi second,Cursor cursor) {
        int distance=Double.compare(cursor.distanceSquared(first),cursor.distanceSquared(second));
        return distance!=0 ? distance : TIE.compare(first,second);
    }
}
