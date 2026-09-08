package dev.schwalbe.autovalley.core;

import java.util.*;

/** Crop identity and exact observed maturity; this data never proves native click support. */
public record CropDefinition(String key,String itemId,Set<String> blockIds,
                             Map<String,Map<String,String>> matureStates,int cycleDays) {
    public CropDefinition {
        blockIds=blockIds==null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(blockIds));
        Map<String,Map<String,String>> copied=new LinkedHashMap<>();
        if(matureStates!=null)for(var entry:matureStates.entrySet())
            copied.put(entry.getKey(),entry.getValue()==null ? Map.of() : Map.copyOf(entry.getValue()));
        matureStates=Collections.unmodifiableMap(copied);
    }
}
