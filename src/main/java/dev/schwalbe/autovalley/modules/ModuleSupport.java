package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.function.Predicate;

/** Shared read-only inventory rules. Every mutation still passes through ActionPort. */
final class ModuleSupport {
    private ModuleSupport() { }
    static String navigationFailure(Context c, String summary) {
        String detail=c.navigation().failureReason();
        return detail==null || detail.isBlank() ? summary : summary+": "+detail;
    }
    static WorkResult navigationResult(Context c,String summary) {
        Pos destination=c.navigation().failureDestination();
        String message=navigationFailure(c,summary)+(destination==null ? "" : " ("+destination+")");
        return c.navigation().retryableFailure() ? WorkResult.deferred(message) : WorkResult.blocked(message);
    }
    /** Read-only approach to an unloaded work site. No action or completion is implied. */
    static WorkResult observe(Context c,Pos target,double reach,String message) {
        Navigation.Result result=c.navigation().moveToObserve(target,reach,c);
        if (result==Navigation.Result.BLOCKED) return navigationResult(c,message);
        return WorkResult.busy(message+" — 작업장 관측 위치로 이동");
    }
    /** A whole-site preflight must not shuttle forever between mutually unloaded ends. */
    static final class ObservationWindow {
        private final Set<Pos> reached=new HashSet<>();
        private Pos previous;
        private long started=-1;
        WorkResult observe(Context c,Pos target,double reach,String message) {
            long now=c.world().tick();
            if (started>now) clear();
            if (started<0) started=now;
            if (previous!=null && !previous.equals(target) && c.world().loaded(previous)) reached.add(previous);
            if (reached.contains(target) || now-started>=2400)
                return WorkResult.deferred(message+" — 작업장 전체를 현재 로드 범위에서 확인하지 못했습니다. 재방문 반복/관측 시간 한도로 보류: "+target);
            previous=target;
            return ModuleSupport.observe(c,target,reach,message);
        }
        void clear() { reached.clear(); previous=null; started=-1; }
    }
    static int count(Context c, Predicate<ItemData> predicate) {
        return c.world().inventory().stream().map(ItemSlot::item).filter(predicate).mapToInt(ItemData::count).sum();
    }
    static boolean same(ItemData a, ItemData b) {
        return a.id().equals(b.id()) && a.quality() == b.quality() && Objects.equals(a.year(), b.year());
    }
    static int count(Context c, ItemData item) { return count(c, i -> same(i,item)); }
    static boolean tomatoGrade(ItemData item, int grade) { return item.is(ItemData.TOMATO) && item.quality() == grade; }
    static List<Poi> nearest(Context c, List<Poi> pois) {
        return pois.stream().sorted(Comparator.comparingDouble(p -> c.world().player().distance(p.pos()))).toList();
    }
    static boolean canReceive(Context c, ItemData item) {
        return c.world().menu().slots().stream().anyMatch(s -> !s.player() && c.world().mayPlace(s.index(),item)
            && (s.item().empty() || same(s.item(),item) && s.item().count() < 64));
    }
    static boolean hasEmptyInventorySlot(Context c) {
        return c.world().inventory().stream().filter(s -> !s.item().empty()).count() < 36;
    }
    static ItemSlot inventoryItem(Context c, Predicate<ItemData> predicate) {
        return c.world().inventory().stream().filter(s -> predicate.test(s.item())).findFirst().orElse(null);
    }
    static ItemSlot menuPlayerItem(Context c, Predicate<ItemData> predicate) {
        return c.world().menu().slots().stream().filter(ItemSlot::player)
            .filter(s -> s.inventoryIndex() >= 0 && s.inventoryIndex() < 36)
            .filter(s -> predicate.test(s.item())).findFirst().orElse(null);
    }
    static WorkResult busy(String message) { return WorkResult.busy(message); }
}
