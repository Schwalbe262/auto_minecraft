package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Finish all fields and the owned deposit for one crop before admitting the next crop. */
public final class HarvestAndStorageModule implements AutomationModule {
    private final HarvestModule harvest;
    private record CropJob(String key,CropDefinition crop,String storeId,CommodityStore store) { }
    private List<CropJob> jobs;
    private List<Farm> farms;
    private List<Poi> tomatoStores;
    private Map<String,String> links;
    private Profile profile;
    private SessionState session;
    private WorldAccess world;
    private AutomationModule storage;
    private int index;
    private boolean storing;
    private long passDay;
    public HarvestAndStorageModule(HarvestModule harvest){this.harvest=Objects.requireNonNull(harvest);}
    @Override public Feature feature(){return Feature.HARVEST;}
    @Override public int priority(){return harvest.priority();}
    @Override public WorkResult tick(Context c) {
        if(c.actions().pauseReason()!=null)return WorkResult.blocked(c.actions().pauseReason());
        if(jobs==null) {
            if(!c.session().allows(c.profile(),feature()))return WorkResult.idle();
            if(c.actions().busy())return WorkResult.busy("기존 조작 확인 뒤 작물별 수확·보관 시작");
            if(c.profile().farms.isEmpty())return harvest.tick(c);
            profile=c.profile();session=c.session();world=c.world();passDay=day(c);
            farms=List.copyOf(profile.farms);tomatoStores=List.copyOf(profile.pois(PoiKind.TOMATO_CHEST));
            links=profile.cropStores==null ? Map.of() : Map.copyOf(profile.cropStores);
            LinkedHashSet<String> keys=new LinkedHashSet<>();
            // Existing tomatoes also belong to the first deposit after a restart,
            // even when every tomato field already has its confirmed daily date.
            if(farms.stream().anyMatch(f->CropRules.TOMATO.equals(f.cropId())) || links.containsKey(CropRules.TOMATO)
                    || held(c,ItemData.TOMATO))keys.add(CropRules.TOMATO);
            farms.forEach(f->keys.add(f.cropId()));keys.addAll(links.keySet());
            jobs=keys.stream().map(key->new CropJob(key,CropRules.definition(profile,key),links.get(key),
                linkedStore(profile,links.get(key)))).toList();
        }
        if(!unchanged(c) || !c.session().allows(c.profile(),feature())) {
            // Never discard or replace an in-flight native action to adopt edits.
            if(c.actions().busy())return WorkResult.busy("기존 수확·저장 조작 확인 대기");
            return WorkResult.blocked("작물별 수확·보관 중 등록 또는 실행 대상이 바뀌었습니다.");
        }
        while(index<jobs.size()) {
            CropJob job=jobs.get(index);
            if(job.crop()==null)return WorkResult.blocked("작물 정의를 확인하세요: "+job.key());
            if(storage==null) {
                // Prefer the existing tomato warehouse. A profile with only an
                // explicit tomato commodity link retains its previous routing.
                if(CropRules.TOMATO.equals(job.key()) && (!tomatoStores.isEmpty() || job.storeId()==null))
                    storage=new TomatoStorageModule();
                else if(job.store()!=null && job.store().items().contains(job.crop().itemId()))
                    storage=new CommodityStorageModule(feature(),job.storeId(),Set.of(job.crop().itemId()));
                else if(explicitlyNotDueAndEmpty(c,job)) { index++;continue; }
                else return WorkResult.blocked("작물의 연결 저장고를 먼저 확인하세요: "+job.key());
            }
            if(!storing) {
                WorkResult result=harvest.tickCrop(c,job.key());
                if(result.state()!=WorkResult.State.IDLE)return result;
                storing=true;
            }
            WorkResult result=storage.tick(c);
            if(result.state()!=WorkResult.State.IDLE)return result;
            // DepositModule retains all native ACK ownership, including close.
            // Only observed empty inventory and a clean boundary allow another crop.
            if(c.actions().busy() || c.world().menu()==null || c.world().menu().container()
                    || !c.world().menu().carried().empty() || held(c,job.crop().itemId()))
                return WorkResult.blocked("현재 작물의 보관과 상자 닫기 확인을 먼저 마쳐야 합니다: "+job.key());
            storage=null;storing=false;index++;
            if(day(c)!=passDay) {
                clearPlan();
                return WorkResult.busy("날짜 변경 뒤 토마토부터 작물별 작업 순서를 다시 확인합니다.");
            }
        }
        clearPlan();return WorkResult.idle();
    }
    private boolean unchanged(Context c) {
        return profile==c.profile() && session==c.session() && world==c.world() && farms.equals(profile.farms)
            && tomatoStores.equals(profile.pois(PoiKind.TOMATO_CHEST))
            && links.equals(profile.cropStores==null ? Map.of() : profile.cropStores)
            && jobs.stream().allMatch(job->Objects.equals(job.crop(),CropRules.definition(profile,job.key()))
                && Objects.equals(job.store(),linkedStore(profile,job.storeId())));
    }
    /** A missing destination is irrelevant only with positive dates for every existing field and no owned produce. */
    private boolean explicitlyNotDueAndEmpty(Context c,CropJob job) {
        if(held(c,job.crop().itemId()))return false;
        List<Farm> matching=farms.stream().filter(f->job.key().equals(f.cropId())).toList();
        long today=day(c);
        return !matching.isEmpty() && matching.stream().allMatch(f->{
            Long due=c.profile().nextEligibleDay.get(CropRules.farmKey(f));
            return due!=null && due>today;
        });
    }
    private static CommodityStore linkedStore(Profile profile,String id){return id==null ? null : CommodityStorageRules.store(profile,id);}
    private static long day(Context c){return Math.floorDiv(c.world().dayTime(),24000L);}
    private static boolean held(Context c,String itemId){return ModuleSupport.inventoryItem(c,item->item.is(itemId))!=null;}
    private void clearPlan(){jobs=null;farms=null;tomatoStores=null;links=null;profile=null;session=null;world=null;storage=null;index=0;storing=false;}
    @Override public void reset(){harvest.reset();if(storage!=null)storage.reset();clearPlan();}
}
