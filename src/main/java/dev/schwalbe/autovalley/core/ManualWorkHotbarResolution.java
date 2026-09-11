package dev.schwalbe.autovalley.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Explicit operator release of an obsolete work lease, never an inventory or action acknowledgement. */
public final class ManualWorkHotbarResolution {
    public static final int MAX_HISTORY=32;
    public enum Resolution { CONFIRMED_MANUALLY_HANDLED }
    public record Entry(HotbarLease lease,String confirmationKey,Resolution resolution,long resolvedDay) { }
    private ManualWorkHotbarResolution() { }

    /** Binds confirmation to every field of the displayed obligation, including its durable stage. */
    public static String confirmationKey(HotbarLease lease) {
        if(lease==null || !lease.valid())throw new IllegalArgumentException("A valid work hotbar lease is required");
        ItemData item=lease.original();
        // Every variable string is a validated resource ID, enum name or hex digest;
        // none can contain the separator. Null year remains distinct from any year.
        String record="manual-work-hotbar-v1|"+lease.owner().name()+"|"+lease.sourceIndex()+"|"+lease.hotbarSlot()
            +"|"+item.id()+"|"+item.count()+"|"+item.quality()+"|"+item.year()+"|"+item.hoe()+"|"+item.durability()
            +"|"+lease.fingerprint()+"|"+lease.stage().name();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException unavailable) {throw new IllegalStateException("Confirmation digest is unavailable",unavailable);}
    }

    /** Read-only eligibility; an item absence or matching historical audit is never a confirmation. */
    public static String rejection(Context c,String expectedKey) {
        if(c==null || c.profile()==null || c.session()==null || c.world()==null || c.actions()==null || c.navigation()==null)
            return "현재 연결된 자동화 상태를 확인할 수 없습니다";
        HotbarLease lease=c.profile().workHotbarLease;
        if(lease==null || !lease.valid() || expectedKey==null || !expectedKey.matches("[a-f0-9]{64}")
            || !confirmationKey(lease).equals(expectedKey))return "확인하려는 임시 단축바 기록이 변경됐습니다. 다시 확인해 주세요";
        try { validate(c.profile()); }
        catch(IllegalArgumentException invalid) {return "수동 단축바 정리 기록을 확인할 수 없습니다";}
        PlayerState player=c.world().player();MenuData menu=c.world().menu();
        if(player==null || !player.connected() || !player.onGround() || player.sleeping()
            || menu==null || menu.id()!=0 || menu.container() || menu.carried()==null || !menu.carried().empty())
            return "게임에 접속해 바닥에 선 상태에서 보관함과 인벤토리를 닫고 커서를 비워 주세요";
        if(c.actions().busy())return "진행 중인 조작의 서버 확인이 필요합니다";
        String nativeRejection=c.actions().manualWorkHotbarResolutionRejection();
        if(nativeRejection!=null)return nativeRejection;
        if(c.navigation().pendingInteractionOutcome(c)!=null)return "이동 중 실행한 조작의 확인이 아직 남아 있습니다";
        if(c.profile().loggingHotbarLease!=null || c.profile().pendingMachineOutputs==null
            || !c.profile().pendingMachineOutputs.isEmpty())return "다른 미확인 아이템 작업을 먼저 해결해 주세요";
        if(c.world().dayTime()<0)return "수동 정리를 기록할 게임 날짜를 확인할 수 없습니다";
        return null;
    }

    /**
     * Call only after the operator explicitly confirms manual cleanup of this
     * exact record. It waives return-to-slot bookkeeping, not a missing item,
     * historical ticket, native fence, inventory operation or automation start.
     */
    public static boolean confirm(Context c,String expectedKey) {
        if(rejection(c,expectedKey)!=null)return false;
        Profile profile=c.profile();HotbarLease lease=profile.workHotbarLease;
        List<Entry> previousHistory=profile.manualWorkHotbarResolutions;
        Feature previousOwner=c.session().workHotbarOwner;int previousSchema=profile.schemaVersion;
        List<Entry> nextHistory=new ArrayList<>(previousHistory);
        nextHistory.add(new Entry(lease,expectedKey,Resolution.CONFIRMED_MANUALLY_HANDLED,Math.floorDiv(c.world().dayTime(),24000L)));
        if(nextHistory.size()>MAX_HISTORY)nextHistory=new ArrayList<>(nextHistory.subList(nextHistory.size()-MAX_HISTORY,nextHistory.size()));
        try {
            profile.manualWorkHotbarResolutions=nextHistory;profile.workHotbarLease=null;
            profile.schemaVersion=Math.max(profile.schemaVersion,7);c.session().workHotbarOwner=null;
            validate(profile);c.checkpoint().run();return true;
        } catch(RuntimeException failure) {
            profile.manualWorkHotbarResolutions=previousHistory;profile.workHotbarLease=lease;
            profile.schemaVersion=previousSchema;c.session().workHotbarOwner=previousOwner;
            throw new IllegalStateException("수동 단축바 정리를 저장하지 못해 기존 기록을 보존했습니다",failure);
        }
    }

    /** Historical acknowledgement only; a stored entry is never authority to clear a later lease. */
    public static void validate(Profile profile) {
        if(profile==null || profile.manualWorkHotbarResolutions==null || profile.manualWorkHotbarResolutions.size()>MAX_HISTORY)
            throw new IllegalArgumentException("Invalid manual work hotbar resolution history");
        for(Entry entry:profile.manualWorkHotbarResolutions) {
            if(entry==null || entry.lease()==null || !entry.lease().valid()
                || entry.resolution()!=Resolution.CONFIRMED_MANUALLY_HANDLED || entry.resolvedDay()<0
                || !confirmationKey(entry.lease()).equals(entry.confirmationKey()))
                throw new IllegalArgumentException("Invalid manual work hotbar resolution entry");
        }
    }
}
