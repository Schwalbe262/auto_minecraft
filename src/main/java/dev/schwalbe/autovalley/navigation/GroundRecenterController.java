package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;

/** Bounded ordinary inward walking on one already-overlapped, freshly proven support. */
final class GroundRecenterController {
    private final Pos anchor;
    private final WorldAccess world;
    private final ActionPort actions;
    private final Profile profile;
    private final SessionState session;
    private final double height;
    private final long started;
    private long lastTick=Long.MIN_VALUE,progressAt;
    private PlayerState previous;
    private double bestDistance=Double.POSITIVE_INFINITY;
    private int initialQuiet,finalQuiet;
    private boolean prepared,failed,complete;
    private String failure="";

    GroundRecenterController(Pos anchor,Context c) {
        this.anchor=anchor;world=c.world();actions=c.actions();profile=c.profile();session=c.session();
        height=world.standingY(anchor);started=progressAt=world.tick();
    }
    Pos anchor() { return anchor; }
    String failureReason() { return failure; }
    Navigation.Result tick(Context c) {
        if (failed) return Navigation.Result.BLOCKED;
        if (complete) return Navigation.Result.ARRIVED;
        PlayerState p=c.world().player();MenuData menu=c.world().menu();
        if (c.world()!=world || c.actions()!=actions || c.profile()!=profile || c.session()!=session
            || profile.navigationMode!=NavigationMode.TERRAIN || p==null || !p.connected() || !p.onGround() || p.sleeping()
            || p.health()<=0 || !p.focused() && !profile.allowBackground
            || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())
            || menu==null || menu.container() || menu.carried()==null || !menu.carried().empty()
            || actions.busy() || actions.pauseReason()!=null || !c.navigation().permitsTransit(anchor,c)
            || !c.navigation().permitsTransit(NavigationFeet.resolve(world,p),c)
            || !TerrainPathSearch.loadedStance(world,anchor) || !world.canStand(anchor) || !world.fullFlatSupport(anchor)
            || !Double.isFinite(height) || !Double.isFinite(world.standingY(anchor))
            || Math.abs(world.standingY(anchor)-height)>1.0e-5 || Math.abs(p.y()-height)>1.0e-5
            || Math.hypot(p.x()-anchor.x()-.5,p.z()-anchor.z()-.5)>1.25
            || !world.canRecenterOnSupport(anchor))
            return fail("출발 바닥 재정렬의 지지·충돌 또는 조작 조건이 바뀌었습니다.");
        long now=world.tick();
        if (now<lastTick || now<started || now-started>60) return fail("출발 바닥 중심에 제한 시간 안에 정렬하지 못했습니다.");
        if (now==lastTick) return Navigation.Result.MOVING;
        boolean measured=previous!=null && now-lastTick==1;
        double vx=measured ? p.x()-previous.x() : 0,vz=measured ? p.z()-previous.z() : 0;
        previous=p;lastTick=now;
        double dx=anchor.x()+.5-p.x(),dz=anchor.z()+.5-p.z(),distance=Math.hypot(dx,dz),speed=Math.hypot(vx,vz);
        if (measured && speed>.20) return fail("출발 바닥 재정렬 중 예상하지 못한 이동이 관측됐습니다.");
        if (distance<bestDistance-.001) { bestDistance=distance;progressAt=now; }
        if (now-progressAt>20) return fail("출발 바닥 재정렬이 진행되지 않아 중지했습니다.");
        if (!measured) {
            prepared=false;initialQuiet=finalQuiet=0;actions.stopMovement();return Navigation.Result.MOVING;
        }
        if (!prepared) {
            actions.stopMovement();initialQuiet=speed<=.002 ? initialQuiet+1 : 0;
            if (initialQuiet>=2) prepared=true;
            return Navigation.Result.MOVING;
        }
        if (distance<=.06 && speed<=.002 && NavigationFeet.resolve(world,p).equals(anchor)) {
            actions.stopMovement();
            if (++finalQuiet>=2) { complete=true;return Navigation.Result.ARRIVED; }
            return Navigation.Result.MOVING;
        }
        finalQuiet=0;
        // Same reduced ordinary input/coast selection as source centering. The
        // forecast never grants completion: actual quiet support samples do.
        double toward=dx*vx+dz*vz;
        if (Math.hypot(dx-1.25*vx,dz-1.25*vz)<=.035
            || speed>.002 && (toward<=0 || distance<=1.25*speed+.06)) actions.stopMovement();
        else actions.move(new Movement((float)Math.toDegrees(Math.atan2(-dx,dz)),0,true,false,false,false,distance<=.20 ? .1f : .2f));
        return Navigation.Result.MOVING;
    }
    void cancel() { if (!complete) fail("출발 바닥 재정렬이 취소되었습니다.");else actions.stopMovement(); }
    private Navigation.Result fail(String reason) { failed=true;failure=reason;actions.stopMovement();return Navigation.Result.BLOCKED; }
}
