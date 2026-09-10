package dev.schwalbe.autovalley.ui;

/** Local wine configuration only; retained logging checkpoints are not gameplay activity. */
public final class WineLineEditPolicy {
    private WineLineEditPolicy() { }

    public record Boundary(boolean running,boolean recording,boolean profileReady,boolean persistenceReady,
                           boolean startGateReady,boolean connected,boolean normalMenu,boolean emptyCursor,
                           boolean nativeQuiet,boolean pendingOutputs) { }

    /** Read-only: no action reconciliation, checkpoint changes, or automation start. */
    public static String rejection(Boundary boundary) {
        if (boundary==null) return "와인 구역을 변경할 수 있는지 확인하지 못했습니다.";
        if (boundary.running()) return "자동화를 일시정지한 뒤 와인 구역을 변경하세요.";
        if (boundary.recording()) return "직접 플레이 기록을 저장한 뒤 와인 구역을 변경하세요.";
        if (!boundary.profileReady()) return "현재 접속의 설정을 확인한 뒤 와인 구역을 변경하세요.";
        if (!boundary.persistenceReady()) return "설정 저장 오류가 있어 와인 구역을 변경할 수 없습니다.";
        if (!boundary.startGateReady()) return "긴급 정지 처리가 끝난 뒤 와인 구역을 변경하세요.";
        if (!boundary.connected()) return "게임에 접속한 뒤 와인 구역을 변경하세요.";
        if (!boundary.normalMenu()) return "열린 상자나 작업대 화면을 닫은 뒤 와인 구역을 변경하세요.";
        if (!boundary.emptyCursor()) return "커서에 든 아이템을 정리한 뒤 와인 구역을 변경하세요.";
        if (!boundary.nativeQuiet()) return "진행 중이거나 서버 응답 확인이 끝나지 않은 조작이 있습니다.";
        if (boundary.pendingOutputs()) return "미확인 기계 산출물이 남아 있어 와인 구역을 변경할 수 없습니다.";
        return null;
    }
}
