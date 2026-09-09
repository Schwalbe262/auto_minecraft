package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.stream.Collectors;

/** Reuses fresh confirmed warehouse contents; surveys only when a real storage haul needs it. */
final class TomatoOverflowStorageModule implements AutomationModule {
    static final int MAX_PERMIT_RENEWALS=36;
    private enum Stage { START, SURVEY, INSPECT, DEPOSIT, STORE, SHIPPING, SELL, FINISH }
    private enum Pending { OPEN, CLOSE, DEPOSIT, SELL }
    private Stage stage=Stage.START,afterClose;
    private Pending pending;
    private long ticket=-1,started,day;
    private int index,menuId=-1,percent,sent;
    private Profile profile;
    private SessionState session;
    private WorldAccess world;
    private List<Poi> stores=List.of(),shipping=List.of();
    private final Map<Pos,List<ItemData>> observed=new LinkedHashMap<>();
    private TomatoStockCache.SurveyToken survey;
    private TomatoSalePermit batchProof;
    private int permitRenewals;
    private WorkResult completion;
    @Override public Feature feature(){return Feature.TOMATO_STORAGE;}
    @Override public int priority(){return 20;}
    @Override public WorkResult tick(Context c) {
        if(ticket>=0) {
            ActionOutcome outcome=c.actions().outcome(ticket);
            if(!outcome.done())return WorkResult.busy("토마토 보관·출하 서버 확인 대기");
            ticket=-1;
            if(!outcome.success()) {
                invalidatePendingDeposit();
                if(pending==Pending.CLOSE){reset();return WorkResult.blocked("토마토 상자 닫기 확인 실패: "+outcome.message());}
                return finish(c,WorkResult.blocked("토마토 보관·출하 확인 실패: "+outcome.message()));
            }
            switch(pending) {
                case CLOSE -> {menuId=-1;stage=afterClose;}
                case OPEN -> {
                    if(!c.world().menu().container() || !c.world().menu().carried().empty())
                        return finish(c,WorkResult.blocked("토마토 보관·출하 메뉴가 변경되었습니다."));
                    menuId=c.world().menu().id();
                    stage=stage==Stage.SURVEY ? Stage.INSPECT : stage==Stage.DEPOSIT ? Stage.STORE : Stage.SELL;
                }
                case DEPOSIT,SELL -> {
                    if(outcome.confirmedCount()<=0 || outcome.confirmedCount()>sent) {
                        invalidatePendingDeposit();
                        return finish(c,WorkResult.blocked("토마토 이동 개수의 서버 확인이 없습니다."));
                    }
                }
            }
            pending=null;
        }
        if(stage==Stage.FINISH) {WorkResult result=completion;reset();return result;}
        if(stage!=Stage.START && !unchanged(c))
            return finish(c,WorkResult.blocked("토마토 저장량 확인 중 설정·날짜 또는 연결이 바뀌었습니다. 다시 확인합니다."));
        switch(stage) {
            case START -> {
                if(held(c)==0)return WorkResult.idle();
                profile=c.profile();session=c.session();world=c.world();percent=profile.tomatoStorageLimitPercent;
                if(!TomatoSaleRules.enabled(c))return WorkResult.blocked("토마토 잉여 출하 설정을 확인하세요.");
                session.tomatoSalePermit=null;stores=StorageVisitOrder.order(profile.pois(PoiKind.TOMATO_CHEST),world.player());
                shipping=ModuleSupport.nearest(c,profile.pois(PoiKind.SHIPPING_BIN));
                if(stores.isEmpty() || stores.size()>4096 || positions().size()!=stores.size())
                    return finish(c,WorkResult.blocked("토마토 저장고 등록을 확인하세요."));
                if(shipping.isEmpty())return finish(c,WorkResult.blocked("잉여 토마토를 보낼 배송 상자를 등록하세요."));
                started=world.tick();day=Math.floorDiv(world.dayTime(),24000L);index=0;
                Optional<TomatoStockCache.View> cached=session.tomatoStockCache.reusable(c);
                if(cached.isPresent()) {
                    observed.putAll(cached.get().contents());stage=Stage.DEPOSIT;
                } else {
                    survey=session.tomatoStockCache.beginSurvey(c);stage=Stage.SURVEY;
                }
                if(world.menu().container())close(c,stage);
            }
            case SURVEY,DEPOSIT,SHIPPING -> {
                List<Poi> targets=stage==Stage.SHIPPING ? shipping : stores;
                if(index>=targets.size()) {
                    if(stage==Stage.SURVEY){
                        if(!session.tomatoStockCache.completeSurvey(c,survey,observed))
                            return finish(c,WorkResult.blocked("토마토 창고 전체 관측 중 등록·내용 증명이 바뀌었습니다."));
                        survey=null;index=0;stage=Stage.DEPOSIT;break;
                    }
                    return finish(c,WorkResult.blocked(stage==Stage.SHIPPING ? "배송 상자가 가득 찼습니다. 잉여 토마토를 보존합니다."
                        : "보관 한도 미만이지만 맞는 토마토 스택의 공간이 없습니다. 잉여 판매는 하지 않았습니다."));
                }
                if(stage==Stage.DEPOSIT && readyToShip()) {
                    if(held(c)==0)return finish(c,WorkResult.idle());
                    Optional<TomatoStockCache.View> proof=session.tomatoStockCache.reusable(c);
                    if(proof.isEmpty())return finish(c,WorkResult.blocked("토마토 창고 관측이 변경되어 출하하지 않습니다."));
                    batchProof=new TomatoSalePermit(held(c),started,day,percent,total(),capacity(),positions(),proof.get().epoch());
                    session.tomatoSalePermit=batchProof;
                    index=0;stage=Stage.SHIPPING;break;
                }
                if(stage==Stage.DEPOSIT && held(c)==0)return finish(c,WorkResult.idle());
                if(stage==Stage.SHIPPING) {
                    if(held(c)==0)return finish(c,WorkResult.idle());
                    if(!TomatoSaleRules.routeHintValid(batchProof,c))
                        return finish(c,WorkResult.blocked("토마토 잉여 출하 확인이 만료되어 저장량을 다시 확인합니다."));
                }
                Navigation.Result nav=c.navigation().moveTo(targets.get(index).pos(),2.5,c);
                if(nav==Navigation.Result.BLOCKED)return finish(c,ModuleSupport.navigationResult(c,"토마토 보관·출하 위치에 도달하지 못했습니다"));
                if(nav==Navigation.Result.ARRIVED) {
                    if(stage==Stage.SHIPPING && !saleValid(c))
                        return finish(c,WorkResult.blocked("배송 전 토마토 창고의 현재 로드·재고 증명을 확인할 수 없습니다."));
                    submit(c,new Action.UseBlock(targets.get(index).pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN);
                }
            }
            case INSPECT,STORE -> {
                if(!validMenu(c))return finish(c,WorkResult.blocked("토마토 저장 메뉴가 변경되었습니다."));
                List<ItemData> contents=readStorage(c);
                if(contents==null) {
                    session.tomatoStockCache.invalidate(stores.get(index).pos());
                    return finish(c,WorkResult.blocked("잉여 판매 전 토마토 전용 일반 통 27칸을 확인해야 합니다. 다른 내용물은 건드리지 않았습니다."));
                }
                observed.put(stores.get(index).pos(),contents);
                if(!session.tomatoStockCache.observeVerified(c,stores.get(index).pos(),contents))
                    return finish(c,WorkResult.blocked("토마토 창고의 확정 관측을 보존할 수 없습니다."));
                if(stage==Stage.INSPECT){index++;close(c,Stage.SURVEY);break;}
                if(readyToShip()){close(c,Stage.DEPOSIT);break;}
                ItemSlot source=ModuleSupport.menuPlayerItem(c,item->item.is(ItemData.TOMATO) && ModuleSupport.canReceive(c,item));
                if(source==null) {
                    if(held(c)==0)return finish(c,WorkResult.idle());
                    index++;close(c,Stage.DEPOSIT);break;
                }
                // Vanilla quick-move fills compatible partial stacks first. Keep stacks whole;
                // the last reserve transfer may cross the chosen threshold by at most 63 items.
                sent=source.item().count();submit(c,new Action.QuickMove(menuId,source.index()),Pending.DEPOSIT);
            }
            case SELL -> {
                if(!validMenu(c))return finish(c,WorkResult.blocked("토마토 배송 메뉴가 변경되었습니다."));
                ItemSlot source=ModuleSupport.menuPlayerItem(c,item->item.is(ItemData.TOMATO));
                if(source==null)return finish(c,WorkResult.idle());
                if(!saleValid(c,source.item()))return finish(c,WorkResult.blocked("확인한 잉여 토마토 출하 한도 또는 유효 기간을 마쳤습니다."));
                if(!ModuleSupport.canReceive(c,source.item())){index++;close(c,Stage.SHIPPING);break;}
                sent=source.item().count();submit(c,new Action.QuickMove(menuId,source.index()),Pending.SELL);
            }
            default -> { }
        }
        return WorkResult.busy(stage==Stage.SHIPPING || stage==Stage.SELL ? "보관 한도 "+percent+"% 도달 — 잉여 토마토 배송"
            : "토마토 저장량 확인·보관 "+Math.min(index+1,stores.size())+"/"+stores.size()+" (한도 "+percent+"%)");
    }
    private List<ItemData> readStorage(Context c) {
        // 27-slot barrels/single chests only: never double-count a two-block inventory.
        BlockData block=c.world().block(stores.get(index).pos());
        if(!block.id().equals("minecraft:barrel") && !(block.id().equals("minecraft:chest") && "single".equals(block.properties().get("type"))))return null;
        List<ItemSlot> slots=c.world().menu().slots().stream().filter(s->!s.player()).sorted(Comparator.comparingInt(ItemSlot::index)).toList();
        if(slots.size()!=27 || slots.stream().anyMatch(s->s.item()==null || s.item().count()<0 || s.item().count()>64
            || !s.item().empty() && !s.item().is(ItemData.TOMATO)))return null;
        for(int slot=0;slot<27;slot++)if(slots.get(slot).index()!=slot)return null;
        ItemData sample=new ItemData(ItemData.TOMATO,1,0,null,false,Integer.MAX_VALUE);
        if(slots.stream().anyMatch(s->!c.world().mayPlace(s.index(),sample)))return null;
        return slots.stream().map(ItemSlot::item).toList();
    }
    private boolean unchanged(Context c) {
        return profile==c.profile() && session==c.session() && world==c.world() && TomatoSaleRules.enabled(c)
            && percent==profile.tomatoStorageLimitPercent && new HashSet<>(stores).equals(new HashSet<>(profile.pois(PoiKind.TOMATO_CHEST)))
            && stores.size()==profile.pois(PoiKind.TOMATO_CHEST).size() && new HashSet<>(shipping).equals(new HashSet<>(profile.pois(PoiKind.SHIPPING_BIN)))
            && Math.floorDiv(world.dayTime(),24000L)==day && world.tick()>=started && world.tick()-started<1200;
    }
    private long capacity(){return observed.values().stream().mapToLong(items->items.size()*64L).sum();}
    private long total(){return observed.values().stream().flatMap(Collection::stream).filter(i->i.is(ItemData.TOMATO)).mapToLong(ItemData::count).sum();}
    private boolean readyToShip(){return observed.size()==stores.size() && total()>=TomatoSaleRules.reserve(capacity(),percent);}
    private Set<Pos> positions(){return stores.stream().map(Poi::pos).collect(Collectors.toSet());}
    private int held(Context c){return ModuleSupport.count(c,i->i.is(ItemData.TOMATO));}
    private boolean saleValid(Context c) {
        ItemSlot item=ModuleSupport.inventoryItem(c,i->i.is(ItemData.TOMATO));
        return item!=null && saleValid(c,item.item());
    }
    private boolean saleValid(Context c,ItemData item) {
        TomatoSalePermit current=session.tomatoSalePermit;
        if(batchProof==null || !TomatoSaleRules.validProof(batchProof,c)
            || current!=null && !current.equals(batchProof.withInventoryLimit(current.inventoryLimit())))return false;
        if(TomatoSaleRules.permitted(item,c))return true;
        if(permitRenewals>=MAX_PERMIT_RENEWALS || current!=null && current.inventoryLimit()>=item.count())return false;
        // A pickup can enlarge a later stack beyond the old remainder. Only the actual
        // carried inventory is re-authorized, within this SAME finite shipment batch.
        session.tomatoSalePermit=batchProof.withInventoryLimit(held(c));permitRenewals++;
        return TomatoSaleRules.permitted(item,c);
    }
    private void invalidatePendingDeposit() {
        if(pending==Pending.DEPOSIT && session!=null && index<stores.size())
            session.tomatoStockCache.invalidate(stores.get(index).pos());
    }
    private boolean validMenu(Context c){MenuData menu=c.world().menu();return menu.container() && menu.id()==menuId && menu.carried().empty();}
    private void submit(Context c,Action action,Pending kind){pending=kind;ticket=c.actions().submit(action);}
    private void close(Context c,Stage next){afterClose=next;submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE);}
    private WorkResult finish(Context c,WorkResult result) {
        if(session!=null)session.tomatoSalePermit=null;
        completion=result;stage=Stage.FINISH;
        if(!c.actions().busy() && c.world().menu().container() && c.world().menu().carried().empty()) {
            close(c,Stage.FINISH);return WorkResult.busy("토마토 보관·출하 상자 닫기");
        }
        reset();return result;
    }
    @Override public void reset(){invalidatePendingDeposit();if(session!=null)session.tomatoSalePermit=null;stage=Stage.START;pending=null;ticket=-1;menuId=-1;
        index=0;profile=null;session=null;world=null;stores=List.of();shipping=List.of();observed.clear();completion=null;
        survey=null;batchProof=null;permitRenewals=0;}
}
