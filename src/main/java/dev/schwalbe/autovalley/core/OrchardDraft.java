package dev.schwalbe.autovalley.core;

import java.util.*;

/** Recording observations only. This draft grants no work area, storage or item-use authority. */
public record OrchardDraft(String id,String name,String toolItemId,String fruitBlockId,String outputItemId,
                           List<Pos> observedFruits,String sourceRecording) {
    public static final String TOOL_ITEM_ID="society:cornucopia";
    public static final String FRUIT_BLOCK_ID="pamhc2trees:pamorange";
    public static final String OUTPUT_ITEM_ID="atmospheric:orange";
    public static final int MAX_DRAFTS=128,MAX_OBSERVED_FRUITS=4096;

    public OrchardDraft {
        observedFruits=observedFruits==null ? List.of() : Collections.unmodifiableList(new ArrayList<>(observedFruits));
    }
    public boolean valid() {
        return id!=null && id.matches("[a-z0-9_][a-z0-9_.-]{0,63}") && label(name,64) && label(sourceRecording,255)
            && TOOL_ITEM_ID.equals(toolItemId) && FRUIT_BLOCK_ID.equals(fruitBlockId) && OUTPUT_ITEM_ID.equals(outputItemId)
            && !observedFruits.isEmpty() && observedFruits.size()<=MAX_OBSERVED_FRUITS
            && observedFruits.stream().allMatch(CoordinateDestinationRules::validPosition)
            && new HashSet<>(observedFruits).size()==observedFruits.size();
    }
    private static boolean label(String value,int maxLength) {
        return value!=null && !value.isBlank() && value.length()<=maxLength && value.chars().noneMatch(Character::isISOControl);
    }
}
