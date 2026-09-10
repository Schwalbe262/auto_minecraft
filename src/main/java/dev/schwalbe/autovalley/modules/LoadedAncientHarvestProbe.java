package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Bounded local observation only: never travel or load chunks to challenge a future crop date. */
final class LoadedAncientHarvestProbe {
    static final int CELL_BUDGET=512;
    private final Map<Farm,Observation> observations=new HashMap<>();
    private Profile owner;
    private WorldAccess world;
    private int nextFarm;
    private static final class Observation {
        final long day,due;
        final CropDefinition crop;
        int cursor;
        boolean missing,complete;
        long retryAt;
        Observation(long day,long due,CropDefinition crop){this.day=day;this.due=due;this.crop=crop;}
    }

    void refresh(Context c,String cropScope) {
        Profile profile=c.profile();
        if(owner!=profile || world!=c.world()) {observations.clear();nextFarm=0;owner=profile;world=c.world();}
        observations.keySet().retainAll(profile.farms);
        if(profile.farms.isEmpty())return;
        long today=Math.floorDiv(world.dayTime(),24000L);
        int budget=CELL_BUDGET;
        int start=Math.floorMod(nextFarm,profile.farms.size());
        for(int visited=0;visited<profile.farms.size();visited++) {
            int farmIndex=(start+visited)%profile.farms.size();
            Farm farm=profile.farms.get(farmIndex);
            if(cropScope!=null && !cropScope.equals(farm.cropId()) || !AncientHarvestTiming.supports(profile,farm)
                    || farm.volume()>32768)continue;
            Long due=profile.nextEligibleDay.get(CropRules.farmKey(farm));
            if(due==null || due<=today) {observations.remove(farm);continue;}
            CropDefinition crop=CropRules.definition(profile,farm);
            Observation observation=observations.get(farm);
            if(observation==null || observation.day!=today || observation.due!=due || !observation.crop.equals(crop)) {
                observation=new Observation(today,due,crop);observations.put(farm,observation);
            }
            if(observation.complete || world.tick()<observation.retryAt)continue;
            int width=1+Math.abs(farm.first().x()-farm.second().x());
            int height=1+Math.abs(farm.first().y()-farm.second().y());
            while(budget>0 && observation.cursor<farm.volume()) {
                int index=observation.cursor++;
                Pos pos=new Pos(Math.min(farm.first().x(),farm.second().x())+index%width,
                    Math.min(farm.first().y(),farm.second().y())+index/width%height,
                    Math.min(farm.first().z(),farm.second().z())+index/width/height);
                budget--;
                if(!world.loaded(pos)) {observation.missing=true;continue;}
                BlockData block=world.block(pos);
                if(block==null) {observation.missing=true;continue;}
                if(CropRules.mature(crop,block) && crop.equals(CropRules.registeredCrop(profile,pos))) {
                    // Readiness only. The ordinary full-field scan, native mask, hoe and ACK still gate every use.
                    profile.nextEligibleDay.put(CropRules.farmKey(farm),today);
                    observations.remove(farm);break;
                }
            }
            if(observation.cursor>=farm.volume()) {
                if(observation.missing) {
                    observation.cursor=0;observation.missing=false;
                    observation.retryAt=world.tick()+Math.max(100,profile.harvestCheckTicks);
                } else observation.complete=true; // Daily crops do not naturally ripen again during this day.
            }
            if(budget==0) {nextFarm=(farmIndex+1)%profile.farms.size();break;}
        }
    }
}
