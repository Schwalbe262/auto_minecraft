package dev.schwalbe.autovalley.core;

import java.util.Map;
import java.util.Set;

/** Read-only next observation date for the supported ten-stage ancient-fruit regrowth. */
public final class AncientHarvestTiming {
    private static final int MATURE_AGE=10;
    private static final long MAX_VOLUME=32768;
    private AncientHarvestTiming() { }

    /** A custom crop, growth rule or explicit interval must retain its existing scheduling policy. */
    public static boolean supports(Profile profile,Farm farm) {
        if(farm==null || !CropRules.ANCIENT_FRUIT.equals(farm.cropId()))return false;
        CropDefinition crop=CropRules.definition(profile,farm);
        return crop!=null && CropRules.ANCIENT_FRUIT.equals(crop.key())
            && CropRules.ANCIENT_FRUIT_ITEM.equals(crop.itemId()) && crop.cycleDays()==MATURE_AGE
            && crop.blockIds().equals(Set.of(CropRules.ANCIENT_FRUIT_ITEM))
            && crop.matureStates().equals(Map.of(CropRules.ANCIENT_FRUIT_ITEM,Map.of("age","10")));
    }

    /**
     * This is a date to observe again, never evidence of a harvest or native ACK.
     * Immature plants retain their individual progress instead of all receiving
     * ten more days when a differently-aged part of the field was harvested.
     */
    public static long nextCheckDay(Profile profile,Farm farm,WorldAccess world,long today,boolean harvestedThisPass) {
        if(!supports(profile,farm)) {
            CropDefinition crop=CropRules.definition(profile,farm);
            int days=harvestedThisPass && crop!=null ? CropRules.cycleDays(profile,farm) : 1;
            return after(today,days);
        }
        if(world==null || farm.first()==null || farm.second()==null || farm.volume()>MAX_VOLUME)return after(today,1);
        long minX=Math.min(farm.first().x(),farm.second().x()),maxX=Math.max(farm.first().x(),farm.second().x());
        long minY=Math.min(farm.first().y(),farm.second().y()),maxY=Math.max(farm.first().y(),farm.second().y());
        long minZ=Math.min(farm.first().z(),farm.second().z()),maxZ=Math.max(farm.first().z(),farm.second().z());
        int earliest=MATURE_AGE;
        boolean observedCrop=false,unknown=false;
        // Long loop counters also remain bounded at Integer.MAX_VALUE coordinates.
        for(long x=minX;x<=maxX;x++)for(long y=minY;y<=maxY;y++)for(long z=minZ;z<=maxZ;z++) {
            Pos position=new Pos((int)x,(int)y,(int)z);
            try {
                if(!world.loaded(position)) { unknown=true;continue; }
                BlockData block=world.block(position);
                if(block==null || !position.equals(block.pos()) || block.id()==null || block.id().isBlank()
                    || "autovalley:unloaded".equals(block.id())) { unknown=true;continue; }
                // Soil, aisles and sprinklers are not immature crops.
                if(!CropRules.ANCIENT_FRUIT_ITEM.equals(block.id()))continue;
                observedCrop=true;
                if(block.properties()==null) { unknown=true;continue; }
                int age;
                try { age=Integer.parseInt(block.properties().get("age")); }
                catch(RuntimeException invalidAge) { unknown=true;continue; }
                if(age<0 || age>MATURE_AGE) { unknown=true;continue; }
                earliest=Math.min(earliest,MATURE_AGE-age);
            } catch(RuntimeException unavailable) {
                // Observation failures do not invent success or defer an unknown plant for ten days.
                unknown=true;
            }
        }
        if(!observedCrop || unknown)earliest=Math.min(earliest,1);
        return after(today,earliest);
    }

    private static long after(long today,int days) {
        return today>Long.MAX_VALUE-days ? Long.MAX_VALUE : today+days;
    }
}
