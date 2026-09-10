package dev.schwalbe.autovalley.core;

import java.util.*;

/** One reversible, checkpointed working slot. Native swaps retain their own exact server receipts. */
public final class HotbarWorkspace {
    private enum Step { PARK, RESTORE, REFRESH }
    private final Feature owner;
    private long ticket=-1;
    private Step step;
    private Profile requestProfile;
    private HotbarLease expected;
    private boolean refreshed;
    private String failure;

    public HotbarWorkspace(Feature owner) {
        if(owner!=Feature.SEED_MAKER && owner!=Feature.CRYSTAL_COPY && owner!=Feature.STARFRUIT)
            throw new IllegalArgumentException("Unsupported working-slot owner");
        this.owner=owner;
    }
    public boolean owns(Context c) { return c.profile().workHotbarLease!=null && c.profile().workHotbarLease.owner()==owner; }
    /** No slot is moved by a readiness query. */
    public boolean canPrepare(Context c) {
        return c.profile().workHotbarLease==null && c.session().allows(c.profile(),owner)
            && boundary(c) && candidate(c)!=null;
    }
    /** Poll only this controller's already-submitted ticket. Never resends a timed-out click. */
    public WorkResult pending(Context c) {
        if(failure!=null) return WorkResult.blocked(failure);
        if(ticket<0) return null;
        c.actions().stopMovement();
        if(c.profile()!=requestProfile || !Objects.equals(expected,c.profile().workHotbarLease))
            return fail("임시 단축바 기록이 조작 도중 바뀌었습니다. 원래 아이템 기록은 보존합니다");
        ActionOutcome outcome=c.actions().outcome(ticket);
        if(!outcome.done()) return WorkResult.busy("임시 단축바 교환·복원 서버 응답 대기");
        ticket=-1;
        if(!outcome.success()) return fail("임시 단축바 조작 확인 실패 — 재클릭하지 않고 원본 기록 보존: "+outcome.message());
        Step completed=step;step=null;
        if(completed==Step.PARK) {
            if(!originalAt(c,expected.sourceIndex(),expected)) return fail("이동한 원래 단축바 아이템을 확인할 수 없습니다");
            save(c,expected.withStage(HotbarLease.Stage.PARKED));
        } else if(completed==Step.RESTORE) {
            if(!c.actions().workHotbarRestored(expected)) return fail("복원 응답 뒤 원래 단축바 위치의 서버 확인이 필요합니다");
            save(c,null);c.session().workHotbarOwner=null;
        }
        return null;
    }
    /** Called only when the job has no usable working hotbar slot. */
    public WorkResult prepare(Context c) {
        WorkResult pending=pending(c);if(pending!=null)return pending;
        if(c.profile().workHotbarLease!=null)
            return owns(c) ? WorkResult.deferred("임시 작업 칸에 다른 아이템이 있어 원래 단축바 복원 후 다시 확인합니다")
                : WorkResult.blocked("다른 작업의 단축바 복원이 먼저 필요합니다");
        if(!c.session().allows(c.profile(),owner) || !boundary(c))
            return WorkResult.blocked("임시 단축바 준비 전 현재 조작·메뉴·식재 복원을 확인해야 합니다");
        HotbarLease lease=candidate(c);
        if(lease==null) return WorkResult.deferred("작업용 단축바를 준비할 일반 인벤토리 빈칸과 이동 가능한 아이템이 필요합니다");
        c.actions().stopMovement();save(c,lease);
        c.session().workHotbarOwner=owner;refreshed=false;
        submit(c,new Action.SwapHotbar(lease.sourceIndex(),lease.hotbarSlot()),Step.PARK);
        return WorkResult.busy("기존 단축바 아이템을 일반 인벤토리에 보관하고 작업 칸 준비");
    }
    /** Restoration is allowed even after feature OFF, but never while automation itself is OFF. */
    public WorkResult restore(Context c) {
        WorkResult pending=pending(c);if(pending!=null)return pending;
        HotbarLease lease=c.profile().workHotbarLease;
        if(lease==null) { c.session().workHotbarOwner=null;return null; }
        if(lease.owner()!=owner || !lease.valid()) return fail("임시 단축바 복원 기록의 소유자를 확인할 수 없습니다");
        if(!boundary(c)) return fail("임시 단축바 복원 전에 조작 응답·메뉴·커서를 확인해야 합니다");
        c.actions().stopMovement();c.session().workHotbarOwner=owner;
        if(c.actions().workHotbarRestored(lease)) {
            save(c,null);c.session().workHotbarOwner=null;return null;
        }
        // RESTORING may describe an interrupted sent operation. Current client
        // appearance cannot authorize replay; require proof of its final custody.
        if(lease.stage()!=HotbarLease.Stage.RESTORING && c.actions().workHotbarParked(lease)) {
            save(c,lease.withStage(HotbarLease.Stage.RESTORING));
            submit(c,new Action.SwapHotbar(lease.sourceIndex(),lease.hotbarSlot()),Step.RESTORE);
            return WorkResult.busy("작업을 마치고 원래 단축바 아이템 복원");
        }
        if(!refreshed && c.actions().supportsInventoryRefresh()) {
            refreshed=true;submit(c,new Action.RefreshInventory(),Step.REFRESH);
            return WorkResult.busy("아이템 이동 없이 임시 단축바의 현재 서버 상태 확인");
        }
        return fail("원래 단축바 아이템의 정확한 위치를 확인할 수 없습니다. 임시 보관 기록은 보존했습니다");
    }
    private boolean boundary(Context c) {
        PlayerState p=c.world().player();MenuData menu=c.world().menu();
        return p!=null && p.connected() && p.onGround() && !p.sleeping()
            && Float.isFinite(p.health()) && p.health()>4 && p.food()>4
            && (p.focused() || c.profile().allowBackground)
            && menu!=null && menu.id()==0 && !menu.container() && menu.carried()!=null && menu.carried().empty()
            && !c.actions().busy() && c.actions().pauseReason()==null
            && c.navigation().pendingInteractionOutcome(c)==null
            && c.profile().loggingHotbarLease==null && !MachineOutputLedger.hasPending(c);
    }
    private HotbarLease candidate(Context c) {
        Map<Integer,ItemData> inventory=inventory(c);
        if(inventory.size()!=36)return null;
        int source=-1;
        for(int i=9;i<36;i++)if(inventory.get(i).empty()){source=i;break;}
        if(source<0)return null;
        Set<String> working=workingItems(c);
        for(int offset=1;offset<=9;offset++) {
            int slot=Math.floorMod(c.profile().hoeHotbarSlot+offset,9);
            ItemData original=inventory.get(slot);
            if(slot==c.profile().hoeHotbarSlot || slot==c.profile().loggingAxeHotbarSlot
                || original.empty() || original.hoe() || original.count()>64 || original.year()!=null
                || working.contains(original.id()))continue;
            String fingerprint=c.world().loggingItemFingerprint(slot);
            if(fingerprint==null || !fingerprint.matches("[0-9a-f]{64}"))continue;
            HotbarLease lease=new HotbarLease(owner,source,slot,original,fingerprint);
            if(lease.valid())return lease;
        }
        return null;
    }
    private Set<String> workingItems(Context c) {
        Set<String> items=new HashSet<>();
        if(owner==Feature.STARFRUIT)items.add(FruitRules.ITEM);
        // Any manually seeded crystal can arrive while this slot is borrowed.
        // Parking one would let a same-ID pickup change the original's custody.
        if(owner==Feature.CRYSTAL_COPY)items.addAll(CrystalCollection.OUTPUT_IDS);
        if(c.profile().artisanJobs!=null) for(ArtisanJob job:c.profile().artisanJobs.values()) {
            if(job==null || job.recipe().feature()!=owner)continue;
            ArtisanRecipe recipe=job.recipe();items.add(recipe.inputId());items.add(recipe.outputId());
            if(recipe.separateBonusOutputId()!=null)items.add(recipe.separateBonusOutputId());
        }
        return items;
    }
    private static Map<Integer,ItemData> inventory(Context c) {
        Map<Integer,ItemData> items=new HashMap<>();
        for(ItemSlot slot:c.world().inventory()) {
            int index=slot.inventoryIndex();
            if(!slot.player() || index<0 || index>=36 || slot.item()==null || items.put(index,slot.item())!=null)return Map.of();
        }
        return items;
    }
    private static boolean originalAt(Context c,int index,HotbarLease lease) {
        ItemData current=inventory(c).get(index);
        return lease.original().equals(current) && lease.fingerprint().equals(c.world().loggingItemFingerprint(index));
    }
    private void save(Context c,HotbarLease lease) {
        HotbarLease before=c.profile().workHotbarLease;c.profile().workHotbarLease=lease;
        try { c.checkpoint().run(); }
        catch(RuntimeException error) { c.profile().workHotbarLease=before;throw error; }
        expected=lease;
    }
    private void submit(Context c,Action action,Step next) {
        requestProfile=c.profile();expected=c.profile().workHotbarLease;step=next;
        ticket=c.actions().submit(action);
    }
    private WorkResult fail(String message) { failure=message;return WorkResult.blocked(message); }
    /** Only ephemeral state is cleared; a manual OFF never discards the persistent original item. */
    public void reset() { ticket=-1;step=null;requestProfile=null;expected=null;refreshed=false;failure=null; }
}
