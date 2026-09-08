package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** A harvest one-shot includes the explicitly linked commodity stores, not unrelated jobs. */
public final class HarvestAndStorageModule implements AutomationModule {
    private final HarvestModule harvest;
    private CommodityStorageModule storage;
    private Iterator<Map.Entry<String,String>> stores;
    public HarvestAndStorageModule(HarvestModule harvest){this.harvest=Objects.requireNonNull(harvest);}
    @Override public Feature feature(){return Feature.HARVEST;}
    @Override public int priority(){return harvest.priority();}
    @Override public WorkResult tick(Context c) {
        if(stores==null) {
            WorkResult result=harvest.tick(c);
            if(result.state()!=WorkResult.State.IDLE)return result;
            stores=List.copyOf(c.profile().cropStores.entrySet()).iterator();
        }
        while(true) {
            if(storage!=null) {
                WorkResult result=storage.tick(c);
                if(result.state()!=WorkResult.State.IDLE)return result;
                storage=null;
            }
            if(!stores.hasNext()){stores=null;return WorkResult.idle();}
            var next=stores.next();CropDefinition crop=CropRules.definition(c.profile(),next.getKey());
            if(crop!=null)storage=new CommodityStorageModule(Feature.HARVEST,next.getValue(),Set.of(crop.itemId()));
        }
    }
    @Override public void reset(){harvest.reset();if(storage!=null)storage.reset();storage=null;stores=null;}
}
