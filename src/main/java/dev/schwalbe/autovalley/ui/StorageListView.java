package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Immutable display grouping only. Never creates registrations, changes ownership, or saves a profile. */
public final class StorageListView {
    private StorageListView() { }
    public record Group(String id,String name,boolean tomato,List<String> items,List<Pos> members) {
        public Group { items=List.copyOf(items);members=List.copyOf(members); }
    }
    public record Snapshot(List<Group> groups,List<Poi> standalonePois) {
        public Snapshot { groups=List.copyOf(groups);standalonePois=List.copyOf(standalonePois); }
    }
    public static Snapshot snapshot(Profile profile) {
        if(profile==null)return new Snapshot(List.of(),List.of());
        List<Poi> pois=profile.pois==null ? List.of() : List.copyOf(profile.pois);
        List<Group> groups=new ArrayList<>();
        List<Pos> tomatoes=pois.stream().filter(poi->poi.kind()==PoiKind.TOMATO_CHEST).map(Poi::pos).toList();
        if(!tomatoes.isEmpty())groups.add(new Group("legacy:tomato","",true,List.of(ItemData.TOMATO),tomatoes));
        Set<Pos> commodityMembers=new HashSet<>();
        if(profile.commodityStores!=null)for(var entry:profile.commodityStores.entrySet()) {
            CommodityStore store=entry.getValue();
            if(store==null || !store.valid() || !Objects.equals(entry.getKey(),store.id()))continue;
            groups.add(new Group(store.id(),store.name(),false,store.items().stream().sorted().toList(),store.containers()));
            commodityMembers.addAll(store.containers());
        }
        List<Poi> standalone=pois.stream().filter(poi->poi.kind()!=PoiKind.TOMATO_CHEST
            && !(poi.kind()==PoiKind.STORAGE_CANDIDATE && commodityMembers.contains(poi.pos()))).toList();
        return new Snapshot(groups,standalone);
    }
    /** Retain exact current membership/labels/items; never silently turn an old detail into another group. */
    public static boolean current(Profile profile,Group group) {
        return group!=null && snapshot(profile).groups().contains(group);
    }
    /** Preserve the old explicit individual unregister control, including unloaded or removed chests. */
    public static boolean removableTomato(Profile owner,Profile current,Group group,Poi poi) {
        return owner!=null && owner==current && group!=null && group.tomato() && current(current,group)
            && poi!=null && poi.kind()==PoiKind.TOMATO_CHEST && group.members().contains(poi.pos()) && current.pois.contains(poi);
    }
    public static List<Pos> page(Group group,int page,int rows) {
        if(group==null || page<0 || rows<1)return List.of();
        long start=(long)page*rows;
        if(start>=group.members().size())return List.of();
        return List.copyOf(group.members().subList((int)start,(int)Math.min((long)group.members().size(),start+rows)));
    }
}
