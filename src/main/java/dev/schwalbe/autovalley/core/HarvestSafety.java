package dev.schwalbe.autovalley.core;

import java.util.HashSet;

/** Every possible native crop interaction must stay in the registered tomato mask. */
public final class HarvestSafety {
    private HarvestSafety() { }
    public static String rejection(Context context,Pos target) {
        HarvestFootprint footprint=context.world().harvestFootprint(target);
        if (footprint==null || !footprint.known()) return "Native harvest area is unknown; no harvest click was sent";
        if (footprint.radius()<0 || footprint.safetyRadius()<footprint.radius() || footprint.safetyRadius()>16
            || footprint.potentialTargets().isEmpty() || !footprint.potentialTargets().contains(target)
            || footprint.potentialTargets().size()>2178
            || new HashSet<>(footprint.potentialTargets()).size()!=footprint.potentialTargets().size())
            return "Native harvest footprint is invalid";
        for (Pos pos:footprint.potentialTargets()) {
            if (pos==null || Math.abs((long)pos.x()-target.x())>footprint.safetyRadius()
                || Math.abs((long)pos.z()-target.z())>footprint.safetyRadius() || pos.y()<target.y() || (long)pos.y()>((long)target.y()+1)
                || !context.world().loaded(pos)) return "Native harvest area is not fully observable";
            BlockData block=context.world().block(pos);
            if (block==null || !block.tomato() || context.profile().farms.stream().noneMatch(farm -> farm.volume()<=32768 && farm.contains(pos)))
                return "Area harvest could interact with an unregistered tomato or another crop";
        }
        return null;
    }
}
