package dev.schwalbe.autovalley.core;

import java.util.*;

/**
 * RAM-only last-confirmed warehouse contents, never a transfer or sale permission.
 * Callers supply complete snapshots from a verified open menu or its native ACK;
 * this class never reads closed inventories, schedules work or moves the player.
 */
public final class TomatoStockCache {
    public static final int STORAGE_SLOTS=27;
    private Profile profile;
    private WorldAccess world;
    private SessionState session;
    private Set<Pos> registered=Set.of();
    private final Map<Pos,List<ItemData>> contents=new LinkedHashMap<>();
    private final Map<Pos,String> observedShapes=new HashMap<>();
    private final Set<Pos> dirty=new HashSet<>();
    private long epoch,lastTick=Long.MIN_VALUE,lastDay=Long.MIN_VALUE;
    private long fullSurveyTick=-1,fullSurveyDay=-1;

    /** Opaque token: an interrupted/invalidated survey cannot publish fresh evidence. */
    public static final class SurveyToken {
        private final TomatoStockCache owner;
        private final long epoch;
        private final Set<Pos> registered;
        private boolean consumed;
        private SurveyToken(TomatoStockCache owner,long epoch,Set<Pos> registered) {
            this.owner=owner;this.epoch=epoch;this.registered=registered;
        }
    }
    public record View(Map<Pos,List<ItemData>> contents,long fullSurveyDay,long fullSurveyTick,long epoch) {
        public View {
            Map<Pos,List<ItemData>> copy=new LinkedHashMap<>();
            contents.forEach((pos,items)->copy.put(pos,List.copyOf(items)));
            contents=Collections.unmodifiableMap(copy);
        }
        public long total() { return contents.values().stream().flatMap(Collection::stream).filter(i->i.is(ItemData.TOMATO)).mapToLong(ItemData::count).sum(); }
        public long capacity() { return contents.size()*STORAGE_SLOTS*64L; }
        public int[] qualityCounts() {
            int[] counts=new int[4];
            contents.values().forEach(items->items.stream().filter(i->i.is(ItemData.TOMATO)).forEach(i->counts[i.quality()]+=i.count()));
            return counts;
        }
    }

    public long invalidationEpoch() { return epoch; }
    public SurveyToken beginSurvey(Context c) {
        synchronize(c);
        return new SurveyToken(this,epoch,registered);
    }
    public boolean completeSurvey(Context c,SurveyToken token,Map<Pos,List<ItemData>> snapshots) {
        boolean valid=synchronize(c);
        if(token==null || token.owner!=this || token.consumed)return false;
        token.consumed=true;
        if(!valid || token.epoch!=epoch || !token.registered.equals(registered) || snapshots==null
            || !snapshots.keySet().equals(registered))return false;
        Map<Pos,List<ItemData>> copy=new LinkedHashMap<>();
        Map<Pos,String> shapes=new HashMap<>();
        for(Pos pos:registered) {
            List<ItemData> snapshot=snapshots.get(pos);
            String shape=storageShape(c,pos);
            if(shape==null || !validSnapshot(snapshot)){invalidate(pos);return false;}
            copy.put(pos,List.copyOf(snapshot));
            shapes.put(pos,shape);
        }
        contents.clear();contents.putAll(copy);dirty.clear();
        observedShapes.clear();observedShapes.putAll(shapes);
        fullSurveyTick=lastTick;fullSurveyDay=lastDay;
        return true;
    }
    /** Replacement is idempotent; a deposit/withdrawal is never guessed or counted twice. */
    public boolean observeVerified(Context c,Pos pos,List<ItemData> snapshot) {
        if(!synchronize(c) || pos==null || !registered.contains(pos))return false;
        String shape=storageShape(c,pos);
        if(shape==null || !validSnapshot(snapshot)){invalidate(pos);return false;}
        contents.put(pos,List.copyOf(snapshot));dirty.remove(pos);
        observedShapes.put(pos,shape);
        // A single touched barrel does not postpone the periodic full warehouse refresh.
        return true;
    }
    public Optional<View> reusable(Context c) {
        if(!synchronize(c) || c.profile().tomatoStockRefreshDays<1 || c.profile().tomatoStockRefreshDays>28
            || fullSurveyDay<0 || lastDay-fullSurveyDay>=c.profile().tomatoStockRefreshDays
            || !dirty.isEmpty() || !contents.keySet().equals(registered))return Optional.empty();
        // Unloading a distant chunk is not a warehouse mutation. Consumers must
        // still obtain current native permission at the actual container/action.
        for(Pos pos:registered) if(c.world().loaded(pos) && !Objects.equals(observedShapes.get(pos),storageShape(c,pos))) {
            invalidate(pos);return Optional.empty();
        }
        return Optional.of(new View(contents,fullSurveyDay,fullSurveyTick,epoch));
    }
    public void invalidate(Pos pos) {
        if(pos!=null && registered.contains(pos) && dirty.add(pos))epoch++;
    }
    public void invalidateAll() {
        if(dirty.addAll(registered) || registered.isEmpty())epoch++;
    }
    private boolean synchronize(Context c) {
        Objects.requireNonNull(c,"context");
        List<Poi> stores=c.profile().pois(PoiKind.TOMATO_CHEST);
        Set<Pos> positions=new LinkedHashSet<>();
        boolean valid=!stores.isEmpty() && stores.size()<=4096;
        for(Poi poi:stores)if(poi.pos()==null || !positions.add(poi.pos()))valid=false;
        long tick=c.world().tick(),day=Math.floorDiv(c.world().dayTime(),24000L);
        if(profile!=c.profile() || world!=c.world() || session!=c.session() || !positions.equals(registered)
            || tick<lastTick || day<lastDay || !valid) {
            epoch++;contents.clear();observedShapes.clear();dirty.clear();fullSurveyTick=-1;fullSurveyDay=-1;
            profile=c.profile();world=c.world();session=c.session();registered=Set.copyOf(positions);
        }
        lastTick=tick;lastDay=day;
        return valid && tick>=0 && day>=0;
    }
    private static boolean validSnapshot(List<ItemData> items) {
        return items!=null && items.size()==STORAGE_SLOTS && items.stream().allMatch(item->item!=null
            && item.id()!=null && item.count()>=0 && item.count()<=64
            && (item.empty() ? item.count()==0 : item.is(ItemData.TOMATO) && item.quality()>=0 && item.quality()<4));
    }
    private static String storageShape(Context c,Pos pos) {
        if(!c.world().loaded(pos))return null;
        BlockData block=c.world().block(pos);
        if(block==null)return null;
        if("minecraft:barrel".equals(block.id()))return block.id();
        if("minecraft:chest".equals(block.id()) && "single".equals(block.properties().get("type")))return block.id()+":single";
        return null;
    }
}
