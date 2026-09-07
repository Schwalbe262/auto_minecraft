package dev.schwalbe.autovalley.core;

import java.util.*;

/** No world reads, registration guesses, inventory permissions, or production schedule changes. */
public final class MachineGroupRules {
    private MachineGroupRules() { }
    public static boolean supported(PoiKind kind) { return kind==PoiKind.PRESERVES_JAR || kind==PoiKind.WINE_KEG; }
    public static boolean validName(String name) {
        return name!=null && !name.isBlank() && name.length()<=64 && name.chars().noneMatch(Character::isISOControl);
    }
    public static void validate(Profile profile) {
        if (profile.machineGroups==null || profile.machineGroups.size()>4096)
            throw new IllegalArgumentException("Invalid machine groups");
        Map<Pos,Poi> registered=new HashMap<>(); profile.pois.forEach(p -> registered.put(p.pos(),p));
        Set<Pos> assigned=new HashSet<>();
        for (var entry:profile.machineGroups.entrySet()) {
            MachineGroup group=entry.getValue();
            if (entry.getKey()==null || !entry.getKey().matches("[A-Za-z0-9_-]{1,80}") || group==null
                    || !validName(group.name()) || !supported(group.kind()) || group.members()==null
                    || group.members().isEmpty() || group.members().size()>4096)
                throw new IllegalArgumentException("Invalid machine group metadata");
            for (Pos pos:group.members()) {
                Poi poi=registered.get(pos);
                if (poi==null || poi.kind()!=group.kind() || !assigned.add(pos))
                    throw new IllegalArgumentException("Machine group references a missing, different, or duplicate machine");
            }
        }
    }
    public static String owner(Profile profile,Pos pos) {
        for(var entry:profile.machineGroups.entrySet()) if(entry.getValue().members().contains(pos)) return entry.getKey();
        return null;
    }
    /** Collapse legacy registrations visually without saving or claiming any unregistered block. */
    public static List<List<Poi>> legacyGroups(Profile profile) {
        Set<Pos> assigned=new HashSet<>(); profile.machineGroups.values().forEach(g -> assigned.addAll(g.members()));
        Map<Pos,Poi> remaining=new LinkedHashMap<>();
        profile.pois.stream().filter(p -> supported(p.kind()) && !assigned.contains(p.pos()))
            .forEach(p -> remaining.put(p.pos(),p));
        List<List<Poi>> result=new ArrayList<>();
        while(!remaining.isEmpty()) {
            Poi seed=remaining.values().iterator().next(); remaining.remove(seed.pos());
            List<Poi> group=new ArrayList<>(); ArrayDeque<Poi> queue=new ArrayDeque<>(); queue.add(seed);
            while(!queue.isEmpty()) {
                Poi current=queue.removeFirst(); group.add(current);
                for(int dx=-3;dx<=3;dx++) for(int dy=-3;dy<=3;dy++) for(int dz=-3;dz<=3;dz++) {
                    if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)>3) continue;
                    long x=(long)current.pos().x()+dx,y=(long)current.pos().y()+dy,z=(long)current.pos().z()+dz;
                    if(x<Integer.MIN_VALUE || x>Integer.MAX_VALUE || y<Integer.MIN_VALUE || y>Integer.MAX_VALUE
                            || z<Integer.MIN_VALUE || z>Integer.MAX_VALUE) continue;
                    Pos pos=new Pos((int)x,(int)y,(int)z); Poi neighbor=remaining.get(pos);
                    if(neighbor!=null && neighbor.kind()==seed.kind()) { remaining.remove(pos); queue.add(neighbor); }
                }
            }
            result.add(List.copyOf(group));
        }
        return List.copyOf(result);
    }
    /** Removing one registered machine must not leave a dangling presentation reference. */
    public static void detach(Profile profile,Pos pos) {
        String id=owner(profile,pos); if(id==null) return;
        MachineGroup group=profile.machineGroups.get(id);
        List<Pos> remaining=group.members().stream().filter(p -> !p.equals(pos)).toList();
        if(remaining.isEmpty()) profile.machineGroups.remove(id);
        else profile.machineGroups.put(id,new MachineGroup(group.name(),group.kind(),remaining));
    }
}
