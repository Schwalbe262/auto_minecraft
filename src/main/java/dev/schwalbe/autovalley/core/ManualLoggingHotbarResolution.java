package dev.schwalbe.autovalley.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Explicit operator release of one logging slot obligation; never proof that any game action completed. */
public final class ManualLoggingHotbarResolution {
    public static final int MAX_HISTORY=32;
    public enum Resolution { CONFIRMED_MANUALLY_HANDLED }
    public record Entry(LoggingHotbarLease lease,String confirmationKey,Resolution resolution,long resolvedDay) { }
    private ManualLoggingHotbarResolution() { }

    /** Historical lease validity does not depend on current configured tool slots or plot registrations. */
    public static boolean validLease(LoggingHotbarLease lease) {
        if(lease==null || lease.sourceIndex()<9 || lease.sourceIndex()>35 || lease.hotbarSlot()<0 || lease.hotbarSlot()>8
            || lease.stage()==null || lease.original()==null)return false;
        ItemData item=lease.original();
        return item.id()!=null && item.id().length()<=256 && item.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            && !item.empty() && item.count()<=64 && !item.hoe() && !item.is(LoggingRules.AXE)
            && lease.fingerprint()!=null && lease.fingerprint().matches("[0-9a-fA-F]{64}");
    }

    /** Binds the operator's confirmation to every field of the displayed obligation. */
    public static String confirmationKey(LoggingHotbarLease lease) {
        if(!validLease(lease))throw new IllegalArgumentException("A valid logging hotbar lease is required");
        ItemData item=lease.original();
        String record="manual-logging-hotbar-v1|"+lease.sourceIndex()+"|"+lease.hotbarSlot()
            +"|"+item.id()+"|"+item.count()+"|"+item.quality()+"|"+item.year()+"|"+item.hoe()+"|"+item.durability()
            +"|"+lease.fingerprint()+"|"+lease.stage().name();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record.getBytes(StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException unavailable) {throw new IllegalStateException("Confirmation digest is unavailable",unavailable);}
    }

    /** Read-only eligibility. Item absence, old audit entries and F8 are never operator consent. */
    public static String rejection(Context c,String expectedKey) {
        if(c==null || c.profile()==null || c.session()==null || c.world()==null || c.actions()==null || c.navigation()==null)
            return "현재 연결된 자동화 상태를 확인할 수 없습니다";
        Profile profile=c.profile();LoggingHotbarLease lease=profile.loggingHotbarLease;
        if(!validLease(lease) || expectedKey==null || !expectedKey.matches("[a-f0-9]{64}")
            || !confirmationKey(lease).equals(expectedKey))return "확인하려는 벌목 단축바 기록이 변경됐습니다. 다시 확인해 주세요";
        try { validate(profile);LoggingRules.validate(profile); }
        catch(IllegalArgumentException invalid) {return "벌목 단축바 또는 미완료 작업 기록을 확인할 수 없습니다";}
        PlayerState player=c.world().player();MenuData menu=c.world().menu();
        if(player==null || !player.connected() || !player.onGround() || player.sleeping()
            || menu==null || menu.id()!=0 || menu.container() || menu.carried()==null || !menu.carried().empty())
            return "게임에 접속해 바닥에 선 상태에서 보관함과 인벤토리를 닫고 커서를 비워 주세요";
        if(c.actions().busy())return "진행 중인 조작의 서버 확인이 필요합니다";
        // This adapter check is read-only: it must not reconcile a late reply or
        // clear a failure. It proves quietness, not native restoration or intent.
        String nativeRejection=c.actions().manualWorkHotbarResolutionRejection();
        if(nativeRejection!=null)return nativeRejection;
        if(c.navigation().pendingInteractionOutcome(c)!=null)return "이동 중 실행한 조작의 확인이 아직 남아 있습니다";
        if(profile.workHotbarLease!=null || profile.pendingMachineOutputs==null || !profile.pendingMachineOutputs.isEmpty())
            return "다른 미확인 아이템 작업을 먼저 해결해 주세요";
        if(c.world().dayTime()<0)return "수동 정리를 기록할 게임 날짜를 확인할 수 없습니다";
        return null;
    }

    /**
     * Call only after explicit confirmation for this key. Archive the obsolete
     * slot-return obligation; retain the active batch, remaining plots, replanting
     * debts and all schedules. No item, ticket, native fence or switch is changed.
     */
    public static boolean confirm(Context c,String expectedKey) {
        if(rejection(c,expectedKey)!=null)return false;
        Profile profile=c.profile();LoggingHotbarLease lease=profile.loggingHotbarLease;
        List<Entry> previousHistory=profile.manualLoggingHotbarResolutions;int previousSchema=profile.schemaVersion;
        List<Entry> nextHistory=new ArrayList<>(previousHistory);
        nextHistory.add(new Entry(lease,expectedKey,Resolution.CONFIRMED_MANUALLY_HANDLED,Math.floorDiv(c.world().dayTime(),24000L)));
        if(nextHistory.size()>MAX_HISTORY)nextHistory=new ArrayList<>(nextHistory.subList(nextHistory.size()-MAX_HISTORY,nextHistory.size()));
        try {
            profile.manualLoggingHotbarResolutions=nextHistory;profile.loggingHotbarLease=null;
            profile.schemaVersion=Math.max(profile.schemaVersion,9);
            validate(profile);c.checkpoint().run();return true;
        } catch(RuntimeException failure) {
            profile.manualLoggingHotbarResolutions=previousHistory;profile.loggingHotbarLease=lease;profile.schemaVersion=previousSchema;
            throw new IllegalStateException("벌목 단축바 수동 정리를 저장하지 못해 기존 기록을 보존했습니다",failure);
        }
    }

    /** History is an audit only and can never release a later lease, even when all fields coincide. */
    public static void validate(Profile profile) {
        if(profile==null || profile.manualLoggingHotbarResolutions==null || profile.manualLoggingHotbarResolutions.size()>MAX_HISTORY)
            throw new IllegalArgumentException("Invalid manual logging hotbar resolution history");
        for(Entry entry:profile.manualLoggingHotbarResolutions) {
            if(entry==null || !validLease(entry.lease()) || entry.resolution()!=Resolution.CONFIRMED_MANUALLY_HANDLED
                || entry.resolvedDay()<0 || !confirmationKey(entry.lease()).equals(entry.confirmationKey()))
                throw new IllegalArgumentException("Invalid manual logging hotbar resolution entry");
        }
    }
}
