package dev.schwalbe.autovalley.core;

import java.util.Objects;

/** One navigation request only: it never runs a work module or registers/opens a facility. */
public final class CoordinateTravel {
    public enum Result { MOVING, COMPLETE, BLOCKED }
    private final Pos destination;
    private final CoordinateDestination facility;
    private String status="좌표로 이동 준비 중";

    private CoordinateTravel(Pos destination,CoordinateDestination facility) {
        this.destination=Objects.requireNonNull(destination); this.facility=facility;
    }
    public static CoordinateTravel position(Pos feet) { return new CoordinateTravel(feet,null); }
    public static CoordinateTravel observe(CoordinateDestination facility) {
        Objects.requireNonNull(facility);
        if (facility.facilityKind()==null) throw new IllegalArgumentException("Facility kind is required");
        return new CoordinateTravel(facility.pos(),facility);
    }
    public Pos destination() { return destination; }
    public String status() { return status; }

    public static String rejection(Context c) {
        PlayerState player=c.world().player(); MenuData menu=c.world().menu();
        if (player==null || !player.connected()) return "서버에 접속한 뒤 이동하세요.";
        if (!player.focused() && !c.profile().allowBackground) return "게임 창을 활성화한 뒤 이동하세요.";
        if (player.sleeping() || player.health()<=4 || player.food()<=4) return "수면·체력·허기를 확인한 뒤 이동하세요.";
        if (menu==null || menu.container() || !menu.carried().empty()) return "열린 상자와 커서의 아이템을 먼저 정리하세요.";
        if (c.profile().loggingRunActive || c.profile().loggingHotbarLease!=null) return "미완료 벌목과 단축바 복원을 먼저 마무리하세요.";
        if (c.actions().busy() || c.actions().pauseReason()!=null || MachineOutputLedger.hasPending(c))
            return "확인 중인 조작을 먼저 마무리하세요.";
        return null;
    }

    public Result tick(Context c) {
        String rejection=rejection(c);
        // A door opened by the navigator has an acknowledged action in flight.
        // No other action is submitted by this controller.
        if (rejection!=null && !(c.actions().busy() && c.actions().pauseReason()==null
                && c.world().menu()!=null && !c.world().menu().container() && c.world().menu().carried().empty()
                && healthy(c) && !c.profile().loggingRunActive && c.profile().loggingHotbarLease==null
                && !MachineOutputLedger.hasPending(c))) return blocked(c,rejection);
        Navigation.Result result=facility==null ? c.navigation().moveToPosition(destination,.25,c)
            : c.navigation().moveToObserve(destination,8,c);
        if (result==Navigation.Result.BLOCKED) return blocked(c,c.navigation().failureReason());
        if (result==Navigation.Result.MOVING) {
            String detail=c.navigation().diagnosticStatus();
            status="좌표 이동 " + destination + (detail.isBlank() ? "" : " — " + detail);
            return Result.MOVING;
        }
        if (facility!=null && (!c.world().loaded(destination)
                || !CoordinateDestinationRules.matches(facility.facilityKind(),c.world().block(destination))))
            return blocked(c,"지정한 좌표의 실제 블록 종류가 시설 설정과 다릅니다. 등록하거나 조작하지 않았습니다.");
        c.actions().stopMovement();
        status=facility==null ? "좌표 이동 완료 — 자동화 OFF" : "시설 위치 확인 완료 — 좌표 목록에서 확인 후 등록하세요. 자동화 OFF";
        return Result.COMPLETE;
    }
    private static boolean healthy(Context c) {
        PlayerState p=c.world().player();
        return p!=null && p.connected() && !p.sleeping() && p.health()>4 && p.food()>4
            && (p.focused() || c.profile().allowBackground);
    }
    private Result blocked(Context c,String reason) {
        c.actions().stopMovement(); status="좌표 이동 중지 — " + (reason==null || reason.isBlank() ? "안전한 경로를 찾지 못했습니다." : reason);
        return Result.BLOCKED;
    }
}
