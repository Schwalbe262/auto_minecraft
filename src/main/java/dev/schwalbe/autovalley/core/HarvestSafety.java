package dev.schwalbe.autovalley.core;

import java.util.HashSet;

/** Every possible native crop interaction must stay inside the selected registered crop mask. */
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
        CropDefinition selected=CropRules.registeredCrop(context.profile(),target);
        if(selected==null)return "Harvest target has no unambiguous registered crop definition";
        long today=Math.floorDiv(context.world().dayTime(),24000L);
        for (Pos pos:footprint.potentialTargets()) {
            if (pos==null || Math.abs((long)pos.x()-target.x())>footprint.safetyRadius()
                || Math.abs((long)pos.z()-target.z())>footprint.safetyRadius() || pos.y()<target.y() || (long)pos.y()>((long)target.y()+1)
                || !context.world().loaded(pos)) return "Native harvest area is not fully observable";
            BlockData block=context.world().block(pos);
            if (!CropRules.matches(selected,block) || !selected.equals(CropRules.registeredCrop(context.profile(),pos)))
                return "Area harvest could interact with an unregistered plant or another crop";
            // The admitted field may already have reserved its next cycle after
            // its first ACK. It may finish this pass, but its area effect must
            // not borrow that admission for a different, not-yet-due field.
            for(Farm farm:context.profile().farms) {
                if(farm==null || farm.volume()>32768 || farm.contains(target) || !farm.contains(pos))continue;
                Long due=context.profile().nextEligibleDay.get(CropRules.farmKey(farm));
                if(due!=null && due>today)
                    return "괭이 범위가 아직 수확 주기가 되지 않은 다른 밭에 닿습니다: "+farm.name()+" (다음 작업일: "+due+")";
            }
        }
        return null;
    }
}
