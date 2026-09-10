package dev.schwalbe.autovalley.core;

import java.util.*;

/** One independently scheduled wine recipe and its dedicated facilities. */
public record WineProductionLine(String id,String name,String inputItemId,String outputItemId,
        String inputStoreId,String outputStoreId,List<Pos> machines,int cycleDays,boolean enabled) {
    public WineProductionLine {
        machines=machines==null ? List.of() : Collections.unmodifiableList(new ArrayList<>(machines));
    }
    public boolean valid() {
        boolean legacy=WineProductionRules.LEGACY_ID.equals(id);
        return id!=null && id.matches("[a-z0-9_][a-z0-9_.-]{0,63}") && name!=null && !name.isBlank() && name.length()<=128
            && inputItemId!=null && inputItemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            && outputItemId!=null && outputItemId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && !inputItemId.equals(outputItemId)
            && cycleDays>=1 && cycleDays<=365 && machines.size()<=4096 && (legacy || !machines.isEmpty())
            && machines.stream().allMatch(CoordinateDestinationRules::validPosition)
            && new HashSet<>(machines).size()==machines.size()
            && (legacy || inputStoreId!=null && !inputStoreId.isBlank() && outputStoreId!=null && !outputStoreId.isBlank()
                && !inputStoreId.equals(outputStoreId));
    }
}
