package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.navigation.ProfileBounds;

/** Pure bounds shared by logging's opt-in planner and one-pulse controller. */
public final class LoggingJumpRules {
    public static final double SOURCE_CENTER=.06,LANDING_CENTER=.12,HEIGHT_TOLERANCE=.10001;
    private LoggingJumpRules() { }

    public static boolean validShape(LoggingJumpEdge edge) {
        if (edge==null || edge.from()==null || edge.to()==null) return false;
        long dx=(long)edge.to().x()-edge.from().x(),dz=(long)edge.to().z()-edge.from().z();
        return Math.abs(dx)+Math.abs(dz)==1 && (long)edge.to().y()-edge.from().y()==1;
    }
    /** Geometry-only: usable for a read-only setup preflight while automation is OFF. */
    public static boolean verifiedSupports(LoggingJumpEdge edge,WorldAccess world,Profile profile) {
        if (!validShape(edge) || world==null || profile==null
            || !ProfileBounds.contains(profile,edge.from()) || !ProfileBounds.contains(profile,edge.to())) return false;
        for (Pos feet:new Pos[]{edge.from(),edge.to()})
            if (!world.loaded(feet) || !world.loaded(feet.offset(0,1,0)) || !world.loaded(feet.offset(0,-1,0)) || !world.canStand(feet)) return false;
        double from=world.standingY(edge.from()),to=world.standingY(edge.to());
        return Double.isFinite(from) && Double.isFinite(to) && Math.abs(to-from-1)<=.00001;
    }
    public static boolean authorised(Context c) {
        if (c==null || !c.profile().loggingRunActive || !c.session().allows(c.profile(),Feature.LOGGING)) return false;
        PlayerState p=c.world().player(); MenuData menu=c.world().menu();
        return p!=null && p.connected() && !p.sleeping() && p.health()>0 && (p.focused() || c.profile().allowBackground)
            && menu!=null && !menu.container() && menu.carried().empty() && !c.actions().busy();
    }
    public static boolean permitted(LoggingJumpEdge edge,Context c) {
        return authorised(c) && verifiedSupports(edge,c.world(),c.profile()) && c.world().canLoggingJump(edge,c.profile());
    }
    public static boolean centered(PlayerState p,Pos feet,double tolerance) {
        return p!=null && feet!=null && Double.isFinite(p.x()) && Double.isFinite(p.y()) && Double.isFinite(p.z())
            && Math.hypot(p.x()-feet.x()-.5,p.z()-feet.z()-.5)<=tolerance;
    }
    public static boolean standingAt(PlayerState p,Pos feet,double height,double centerTolerance) {
        return p!=null && p.onGround() && Double.isFinite(height) && Math.abs(p.y()-height)<=HEIGHT_TOLERANCE
            && centered(p,feet,centerTolerance);
    }
    public static boolean insideFlight(LoggingJumpEdge edge,PlayerState p,double fromHeight) {
        if (!validShape(edge) || p==null || !Double.isFinite(fromHeight)
            || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())) return false;
        int dx=edge.to().x()-edge.from().x(),dz=edge.to().z()-edge.from().z();
        double x=p.x()-edge.from().x()-.5,z=p.z()-edge.from().z()-.5;
        double along=x*dx+z*dz,lateral=Math.abs(x*dz-z*dx);
        return along>=-.12 && along<=1.12 && lateral<=.12
            && p.y()>=fromHeight-.10001 && p.y()<=fromHeight+1.35;
    }
}
