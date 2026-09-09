package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Pure, explicit registration plans. Scanning and checkbox selection grant no action permission. */
public final class GroupExpansionRules {
    private GroupExpansionRules() { }
    public enum Kind { MACHINE, TOMATO, COMMODITY }
    public record Snapshot(Profile owner,List<Poi> pois,Map<String,MachineGroup> machines,Map<String,CommodityStore> stores) {
        public Snapshot { pois=List.copyOf(pois);machines=Collections.unmodifiableMap(new LinkedHashMap<>(machines));stores=Collections.unmodifiableMap(new LinkedHashMap<>(stores)); }
        public boolean current(Profile profile) {return profile==owner && pois.equals(profile.pois) && machines.equals(profile.machineGroups) && stores.equals(profile.commodityStores);}
    }
    public record Target(Kind kind,String id,String name,PoiKind machineKind,List<Pos> members,Set<String> items) {
        public Target {members=List.copyOf(members);items=Collections.unmodifiableSet(new LinkedHashSet<>(items));}
    }
    /** Both physical halves are supplied by the existing native chest-pair check. */
    public record Candidate(BlockData block,List<Pos> physicalCells) {
        public Candidate {block=new BlockData(block.pos(),block.id(),Map.copyOf(block.properties()));physicalCells=List.copyOf(physicalCells);}
    }
    public record Changes(List<Poi> pois,Map<String,MachineGroup> machines,Map<String,CommodityStore> stores) {
        public Changes {pois=List.copyOf(pois);machines=Collections.unmodifiableMap(new LinkedHashMap<>(machines));stores=Collections.unmodifiableMap(new LinkedHashMap<>(stores));}
    }
    public static Snapshot capture(Profile profile) {return new Snapshot(profile,profile.pois,profile.machineGroups,profile.commodityStores);}
    public static int rows(int height) {return Math.max(1,(height-201)/23);}
    public static Target machine(Snapshot snapshot,String id) {
        MachineGroup group=snapshot.machines().get(id);
        require(group!=null && MachineGroupRules.supported(group.kind()));
        return new Target(Kind.MACHINE,id,group.name(),group.kind(),group.members(),Set.of());
    }
    public static Target tomato(Snapshot snapshot) {
        List<Pos> positions=snapshot.pois().stream().filter(p->p.kind()==PoiKind.TOMATO_CHEST).map(Poi::pos).toList();require(!positions.isEmpty());
        return new Target(Kind.TOMATO,"legacy:tomato","",null,positions,Set.of(ItemData.TOMATO));
    }
    public static Target commodity(Snapshot snapshot,String id) {
        CommodityStore store=snapshot.stores().get(id);require(store!=null && store.valid() && id.equals(store.id()) && !store.items().contains(ItemData.WINE));
        return new Target(Kind.COMMODITY,id,store.name(),null,store.containers(),store.items());
    }
    private static void targetCurrent(Snapshot snapshot,Target target) {
        require(snapshot.current(snapshot.owner()));
        Target actual=switch(target.kind()) {case MACHINE->machine(snapshot,target.id());case TOMATO->tomato(snapshot);case COMMODITY->commodity(snapshot,target.id());};
        require(actual.equals(target));
    }
    public static boolean accepts(Target target,Candidate candidate) {
        if(target==null || candidate==null || candidate.block()==null || candidate.physicalCells().isEmpty() || candidate.physicalCells().size()>2
            || !candidate.physicalCells().contains(candidate.block().pos()) || new HashSet<>(candidate.physicalCells()).size()!=candidate.physicalCells().size()
            || candidate.physicalCells().stream().anyMatch(p->!CoordinateDestinationRules.validPosition(p)))return false;
        if(target.kind()!=Kind.MACHINE)return StorageSurveyRules.ordinaryStorage(candidate.block());
        return candidate.physicalCells().size()==1 && (target.machineKind()==PoiKind.WINE_KEG ? "society:wine_keg" : "society:preserves_jar").equals(candidate.block().id());
    }
    public static List<Candidate> available(Snapshot snapshot,Target target,List<Candidate> scanned) {
        targetCurrent(snapshot,target);require(scanned!=null && scanned.size()<=4096);
        Set<Pos> reserved=new HashSet<>();snapshot.pois().forEach(p->reserved.add(p.pos()));snapshot.stores().values().forEach(s->reserved.addAll(s.containers()));
        Map<Pos,Candidate> result=new TreeMap<>(Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z));
        for(Candidate candidate:scanned)if(accepts(target,candidate) && Collections.disjoint(reserved,candidate.physicalCells())) {
            Candidate previous=result.putIfAbsent(candidate.block().pos(),candidate);require(previous==null || previous.equals(candidate));
        }
        return List.copyOf(result.values());
    }
    public static boolean sameGeometry(Target target,Candidate before,Candidate now) {
        if(!accepts(target,before) || !accepts(target,now) || !before.block().pos().equals(now.block().pos())
            || !before.block().id().equals(now.block().id()) || !before.physicalCells().equals(now.physicalCells()))return false;
        if(target.kind()==Kind.MACHINE)return true; // Ripeness/working changes are not a different registration.
        for(String property:List.of("facing","type","container"))if(!Objects.equals(before.block().properties().get(property),now.block().properties().get(property)))return false;
        return true;
    }
    public static Changes expand(Snapshot snapshot,Target target,List<Candidate> preview,Set<Pos> selected,List<Candidate> fresh,boolean contentsChecked) {
        targetCurrent(snapshot,target);require(selected!=null && !selected.isEmpty() && selected.size()<=4096);
        require(target.kind()==Kind.MACHINE || contentsChecked);
        Map<Pos,Candidate> before=new HashMap<>(),now=new HashMap<>();
        available(snapshot,target,preview).forEach(c->before.put(c.block().pos(),c));available(snapshot,target,fresh).forEach(c->now.put(c.block().pos(),c));
        List<Pos> additions=new ArrayList<>();Set<Pos> physical=new HashSet<>();
        for(Candidate candidate:preview)if(selected.contains(candidate.block().pos())) {
            Pos pos=candidate.block().pos();require(before.containsKey(pos) && now.containsKey(pos) && sameGeometry(target,candidate,now.get(pos)));
            require(!additions.contains(pos));for(Pos cell:candidate.physicalCells())require(physical.add(cell));additions.add(pos);
        }
        require(additions.size()==selected.size() && target.members().size()+additions.size()<=4096);
        List<Poi> pois=new ArrayList<>(snapshot.pois());Map<String,MachineGroup> machines=new LinkedHashMap<>(snapshot.machines());Map<String,CommodityStore> stores=new LinkedHashMap<>(snapshot.stores());
        List<Pos> members=new ArrayList<>(target.members());members.addAll(additions);
        if(target.kind()==Kind.COMMODITY)stores.put(target.id(),new CommodityStore(target.id(),target.name(),target.items(),members));
        else {
            require(pois.size()+additions.size()<=4096);PoiKind kind=target.kind()==Kind.TOMATO ? PoiKind.TOMATO_CHEST : target.machineKind();
            int ordinal=target.members().size()+1;
            for(Pos pos:additions)pois.add(new Poi(pos,kind,(target.kind()==Kind.TOMATO ? "Tomato storage" : target.name())+" "+ordinal++,null));
            if(target.kind()==Kind.MACHINE)machines.put(target.id(),new MachineGroup(target.name(),target.machineKind(),members));
        }
        return new Changes(pois,machines,stores);
    }
    /** Optional bulk checkbox rows; every batch starts unselected and is previewed before saving. */
    public static List<List<Candidate>> batches(List<Candidate> candidates) {
        require(candidates!=null && candidates.size()<=4096);Map<Pos,Candidate> remaining=new LinkedHashMap<>();candidates.forEach(c->remaining.put(c.block().pos(),c));
        List<List<Candidate>> groups=new ArrayList<>();
        while(!remaining.isEmpty()) {
            Candidate seed=remaining.values().iterator().next();remaining.remove(seed.block().pos());List<Candidate> group=new ArrayList<>();ArrayDeque<Candidate> queue=new ArrayDeque<>();queue.add(seed);
            while(!queue.isEmpty()) {
                Candidate current=queue.removeFirst();group.add(current);
                for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++)for(int dz=-3;dz<=3;dz++)if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)<=3) {
                    Pos pos=current.block().pos().offset(dx,dy,dz);Candidate neighbor=remaining.get(pos);
                    if(neighbor!=null && seed.block().id().equals(neighbor.block().id())) {remaining.remove(pos);queue.add(neighbor);}
                }
            }
            groups.add(List.copyOf(group));
        }
        return List.copyOf(groups);
    }
    private static void require(boolean condition) {if(!condition)throw new IllegalArgumentException("Registration preview changed or is not permitted");}
}
