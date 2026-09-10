package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Tries every carried native-visible wine group against all containers in its line's shared reserve. */
public final class WineLineStorageModule implements AutomationModule {
    private final Feature scope;
    private final String lineId;
    private enum Stage { FIND, APPROACH, TRANSFER }
    private enum Pending { OPEN, MOVE, CLOSE }
    private record Group(String lineId,ItemData item) { }
    private Stage stage=Stage.FIND,afterClose;
    private Pending pending;
    private final Deque<Group> groups=new ArrayDeque<>();
    private boolean initialized;
    private Group group;
    private WineProductionLine line;
    private CommodityStore store;
    private List<Poi> destinations=List.of();
    private int destination,containerId=-1,beforeDestination,sentCount;
    private long ticket=-1;
    public WineLineStorageModule() { this(Feature.WINE_STORAGE,null); }
    public WineLineStorageModule(Feature scope,String lineId) {
        if (scope!=Feature.WINE_STORAGE && scope!=Feature.WINE) throw new IllegalArgumentException("Invalid wine storage scope");
        if (scope==Feature.WINE && (lineId==null || WineProductionRules.LEGACY_ID.equals(lineId)))
            throw new IllegalArgumentException("Production storage requires an exact custom line");
        this.scope=scope;this.lineId=lineId;
    }
    @Override public Feature feature() { return scope; }
    @Override public int priority() { return 31; }
    @Override public WorkResult tick(Context c) {
        if (ticket>=0) {
            ActionOutcome result=c.actions().outcome(ticket);
            if (!result.done()) return busy("저장 서버 확인 대기");
            ticket=-1;
            if (!result.success()) return fail("와인 라인 저장 조작이 확인되지 않았습니다: "+result.message());
            if (pending==Pending.OPEN) {
                if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("와인 저장 메뉴가 바뀌었습니다.");
                containerId=c.world().menu().id();stage=Stage.TRANSFER;
            } else if (pending==Pending.MOVE) {
                int increase=destinationCount(c)-beforeDestination;
                if (!validMenu(c) || result.confirmedCount()<=0 || result.confirmedCount()>sentCount
                    || increase<result.confirmedCount()) return fail("와인 저장 수량의 서버 확인이 일치하지 않습니다.");
            } else { containerId=-1;stage=afterClose; }
            pending=null;
        }
        if (!c.session().allows(c.profile(),feature()) || MachineOutputLedger.hasPending(c))
            return fail("와인 저장 권한 또는 미확인 생산물 수거를 확인하세요.");
        if (group!=null && (!line.equals(WineLineSaleRules.outputLine(c.profile(),group.item().id())) || !line.enabled()
            || !store.equals(WineProductionRules.outputStore(c.profile(),line)))) return fail("와인 라인 저장 등록이 바뀌었습니다.");
        switch (stage) {
            case FIND -> {
                if (!initialized) {
                    LinkedHashSet<Group> initial=new LinkedHashSet<>();
                    for (ItemSlot slot:c.world().inventory()) {
                        ItemData item=slot.item();WineProductionLine owner=WineLineSaleRules.outputLine(c.profile(),item.id());
                        if (owner!=null && owner.enabled() && !item.empty() && (lineId==null || lineId.equals(owner.id())))
                            initial.add(new Group(owner.id(),new ItemData(item.id(),1,item.quality(),item.year(),item.hoe(),item.durability())));
                    }
                    if (initial.size()>36) return fail("와인 인벤토리 그룹이 너무 많습니다.");
                    groups.addAll(initial);initialized=true;
                }
                group=null;
                while (!groups.isEmpty() && group==null) {
                    Group next=groups.removeFirst();
                    if (ModuleSupport.inventoryItem(c,item->ModuleSupport.same(item,next.item()))!=null) group=next;
                }
                if (group==null) { reset();return WorkResult.idle(); }
                line=WineLineSaleRules.outputLine(c.profile(),group.item().id());
                if (line==null || !line.enabled() || !line.id().equals(group.lineId())) return fail("와인 출력 라인을 확인할 수 없습니다.");
                store=WineProductionRules.outputStore(c.profile(),line);
                if (store==null) return fail("와인 출력 보관함을 등록하세요.");
                destinations=ModuleSupport.nearest(c,store.containers().stream()
                    .map(pos->new Poi(pos,PoiKind.STORAGE_CANDIDATE,line.name(),null)).toList());
                destination=0;stage=Stage.APPROACH;
                if (c.world().menu().container()) close(c,Stage.APPROACH);
            }
            case APPROACH -> {
                if (destination>=destinations.size()) { group=null;stage=Stage.FIND;break; }
                Navigation.Result nav=c.navigation().moveTo(destinations.get(destination).pos(),2.5,c);
                if (nav==Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(c,"와인 출력 보관함에 도달하지 못했습니다.");
                if (nav==Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(destinations.get(destination).pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN);
            }
            case TRANSFER -> {
                if (!validMenu(c)) return fail("와인 저장 메뉴가 바뀌었습니다.");
                ItemSlot held=ModuleSupport.menuPlayerItem(c,item->ModuleSupport.same(item,group.item()));
                if (held==null) { group=null;close(c,Stage.FIND);break; }
                // Any vintage may occupy another slot; the existing native transfer still controls stacking.
                List<ItemSlot> slots=c.world().menu().slots().stream().filter(slot->!slot.player()).toList();
                boolean compatible=(slots.size()==27 || slots.size()==54)
                    && slots.stream().allMatch(slot->slot.item().empty() || slot.item().is(line.outputItemId()));
                if (!compatible || !ModuleSupport.canReceive(c,held.item())) {
                    destination++;close(c,Stage.APPROACH);break;
                }
                beforeDestination=destinationCount(c);sentCount=held.item().count();
                submit(c,new Action.QuickMove(containerId,held.index()),Pending.MOVE);
            }
        }
        return busy("보관함 전체 공간부터 채우는 중");
    }
    private int destinationCount(Context c) {
        return c.world().menu().slots().stream().filter(slot->!slot.player()).map(ItemSlot::item)
            .filter(item->group!=null && ModuleSupport.same(item,group.item())).mapToInt(ItemData::count).sum();
    }
    private boolean validMenu(Context c) { return c.world().menu().container() && c.world().menu().id()==containerId && c.world().menu().carried().empty(); }
    private void submit(Context c,Action action,Pending why) { pending=why;ticket=c.actions().submit(action); }
    private void close(Context c,Stage next) { afterClose=next;submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE); }
    private static WorkResult busy(String message) { return WorkResult.busy("와인 라인: "+message); }
    private WorkResult fail(String message) { reset();return WorkResult.blocked(message); }
    @Override public void reset() {
        stage=Stage.FIND;afterClose=null;pending=null;groups.clear();initialized=false;group=null;line=null;store=null;
        destinations=List.of();destination=0;containerId=-1;ticket=-1;
    }
}
