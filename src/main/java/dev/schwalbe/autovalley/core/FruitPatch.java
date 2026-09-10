package dev.schwalbe.autovalley.core;

import java.util.*;

/** Explicit orchard tree-fruit mask and its commodity destination; never expands to a whole forest. */
public record FruitPatch(String id,String storeId,List<Pos> fruits) {
    public FruitPatch { fruits=fruits==null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fruits)); }
    public boolean valid() { return id!=null && !id.isBlank() && storeId!=null && !storeId.isBlank()
        && !fruits.isEmpty() && fruits.size()<=4096 && fruits.stream().noneMatch(Objects::isNull)
        && new HashSet<>(fruits).size()==fruits.size(); }
}
