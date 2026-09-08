package dev.schwalbe.autovalley.core;

/** General transit shares ascent geometry, never logging's work or planting authority. */
public final class StepUpRules {
    private StepUpRules() { }

    public static boolean verifiedSupports(LoggingJumpEdge edge,WorldAccess world,Profile profile) {
        if (!LoggingJumpRules.validShape(edge) || world==null || profile==null
                || profile.navigationMode!=NavigationMode.TERRAIN) return false;
        for (Pos feet:new Pos[]{edge.from(),edge.to()}) {
            if (!world.loaded(feet) || !world.loaded(feet.offset(0,1,0)) || !world.loaded(feet.offset(0,-1,0))
                    || !world.canStand(feet)) return false;
            for (Farm farm:profile.farms)
                if (farm.contains(feet) || farm.contains(feet.offset(0,-1,0))) return false;
            for (LoggingPlot plot:profile.loggingPlots)
                if (plot.containsTrunk(feet) || plot.containsTrunk(feet.offset(0,-1,0))) return false;
        }
        double from=world.standingY(edge.from()),to=world.standingY(edge.to());
        return Double.isFinite(from) && Double.isFinite(to) && Math.abs(to-from-1)<=.00001;
    }

    public static boolean authorised(Context c) {
        if (c==null || c.profile().navigationMode!=NavigationMode.TERRAIN) return false;
        PlayerState p=c.world().player(); MenuData menu=c.world().menu();
        return p!=null && p.connected() && !p.sleeping() && p.health()>0
            && (p.focused() || c.profile().allowBackground) && menu!=null
            && !menu.container() && menu.carried().empty() && !c.actions().busy();
    }

    public static boolean permitted(LoggingJumpEdge edge,Context c) {
        return authorised(c) && verifiedSupports(edge,c.world(),c.profile())
            && c.world().canStepUp(edge,c.profile());
    }
}
