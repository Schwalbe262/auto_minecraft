package dev.schwalbe.autovalley.core;

/** Explicit opt-in for individual spruce leaves that actually obstruct a registered 2x2 base. */
public final class LoggingLeafRules {
    public static final String LEAVES="minecraft:spruce_leaves";
    public static final int MAX_CLEARS_PER_PLOT=16;
    private LoggingLeafRules() { }

    public static boolean inShell(LoggingPlot plot,Pos leaf) {
        if (plot==null || plot.corner()==null || leaf==null) return false;
        long dx=(long)leaf.x()-plot.corner().x(),dy=(long)leaf.y()-plot.corner().y(),dz=(long)leaf.z()-plot.corner().z();
        return dx>=-1 && dx<=2 && dz>=-1 && dz<=2 && dy>=0 && dy<=2
            && !plot.plantingPositions().contains(leaf);
    }
    public static LoggingPlot plot(Profile profile,Pos stump) {
        if (profile==null || stump==null || profile.loggingPlots==null) return null;
        var matches=profile.loggingPlots.stream().filter(p -> p!=null && p.plantingPositions().contains(stump)).toList();
        return matches.size()==1 ? matches.get(0) : null;
    }
    /** Retained authority, also usable after STOP when the exact target may already be air. */
    public static boolean authorised(Context c,Pos stump,Pos leaf) {
        if (c==null || !LoggingRules.allowed(c)) return false;
        Profile p=c.profile(); LoggingPlot plot=plot(p,stump);
        return p.loggingClearObstructingLeaves && p.loggingRunActive && p.loggingHotbarLease==null
            && plot!=null && inShell(plot,leaf) && p.loggingRemainingPlots!=null && p.loggingReplantingPlots!=null
            && p.loggingRemainingPlots.contains(plot.corner()) && !p.loggingReplantingPlots.contains(plot.corner())
            && p.farms!=null && p.farms.stream().noneMatch(f -> f==null || f.contains(leaf))
            && p.pois!=null && p.pois.stream().noneMatch(poi -> poi==null || poi.pos().equals(leaf));
    }
    public static String rejection(Action.ClearLoggingLeaf action,Context c) {
        if (action==null || !authorised(c,action.stump(),action.pos()))
            return "허용된 미완료 벌목 밑동 주변의 잎만 제거할 수 있습니다.";
        WorldAccess w=c.world(); MenuData menu=w.menu();
        if (!w.player().onGround() || menu==null || menu.container() || !menu.carried().empty()
            || MachineOutputLedger.hasPending(c)) return "잎 제거 전 자세·메뉴·미확인 작업을 확인하세요.";
        int axe=c.profile().loggingAxeHotbarSlot; ItemData held=SafetyPolicy.held(w);
        if (axe<0 || axe==c.profile().hoeHotbarSlot || w.player().selectedSlot()!=axe
            || !held.is(LoggingRules.AXE) || held.durability()<=1 || !w.loggingAxe(axe))
            return "등록한 사용 가능한 도끼를 들어야 잎을 제거할 수 있습니다.";
        LoggingPlot plot=plot(c.profile(),action.stump());
        if (plot.plantingPositions().stream().anyMatch(p -> !w.loaded(p)) || !w.loaded(action.pos()))
            return "잎 제거 대상과 등록 밑동의 청크를 먼저 확인하세요.";
        BlockData leaf=w.block(action.pos()),base=w.block(action.stump());
        if (leaf==null || !LEAVES.equals(leaf.id()) || !LoggingRules.stump(base))
            return "가문비나무 잎과 기존 벌목 밑동이 확인되지 않았습니다.";
        if (plot.plantingPositions().stream().anyMatch(p -> LoggingRules.stump(w.block(p)) && w.canInteract(p,4)))
            return "밑동이 직접 보이므로 잎 대신 원래 벌목을 진행해야 합니다.";
        if (!w.canInteract(action.pos(),4) || !action.pos().equals(w.loggingLeafObstruction(action.stump(),4)))
            return "이 잎이 실제 도달 가능한 밑동의 첫 조준 장애물인지 확인되지 않았습니다.";
        return w.loggingTreeRejection(action.stump(),c.profile().loggingPlots);
    }
}
