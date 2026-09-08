package dev.schwalbe.autovalley.core;

import java.util.*;

/** Named commodity group. Mixed legacy contents never authorize moving other items. */
public record CommodityStore(String id,String name,Set<String> items,List<Pos> containers) {
    public CommodityStore {
        items=items==null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(items));
        containers=containers==null ? List.of() : Collections.unmodifiableList(new ArrayList<>(containers));
    }
    public boolean valid() {
        return id!=null && id.matches("[a-z0-9_][a-z0-9_.-]{0,63}") && name!=null && !name.isBlank() && name.length()<=128
            && !items.isEmpty() && items.size()<=64 && items.stream().allMatch(item->item!=null && item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            && !containers.isEmpty() && containers.size()<=4096 && containers.stream().noneMatch(Objects::isNull)
            && new HashSet<>(containers).size()==containers.size();
    }
    public boolean accepts(ItemData item) { return valid() && item!=null && !item.empty() && items.contains(item.id()); }
}
