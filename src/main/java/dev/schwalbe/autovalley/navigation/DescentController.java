package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;

/** A pre-braked, walking-only descent on one already validated cardinal edge. */
final class DescentController {
    enum Phase { PREPARE, DESCEND, LAND, COMPLETE, FAILED }
    private static final double HEIGHT_TOLERANCE=.10001, CENTER=.10;
    private static final float APPROACH_INPUT=.08f;
    private final Pos from,to;
    private final double fromHeight,toHeight;
    private Phase phase=Phase.PREPARE;
    private long firstTick=Long.MIN_VALUE,lastTick=Long.MIN_VALUE,progressTick;
    private PlayerState sample,progressSample;
    private int quietSamples;
    private boolean airborne;
    private String failure="";
    private ActionPort actions;

    DescentController(Pos from,Pos to,WorldAccess world) {
        this.from=from;this.to=to;fromHeight=world.standingY(from);toHeight=world.standingY(to);
    }
    Phase phase() { return phase; }
    String failureReason() { return failure; }
    boolean airborne() { return airborne; }
    static boolean descending(Pos from,Pos to,WorldAccess world) {
        double a=world.standingY(from),b=world.standingY(to);
        return Math.abs(to.x()-from.x())+Math.abs(to.z()-from.z())==1 && to.y()<=from.y()
            && Double.isFinite(a) && Double.isFinite(b) && a-b>HEIGHT_TOLERANCE && a-b<=1.00001;
    }
    Navigation.Result tick(Context c) {
        actions=c.actions();WorldAccess world=c.world();PlayerState p=world.player();long now=world.tick();
        airborne=p!=null && !p.onGround();
        if (phase==Phase.FAILED) { actions.stopMovement();return Navigation.Result.BLOCKED; }
        if (phase==Phase.COMPLETE) { actions.stopMovement();return Navigation.Result.ARRIVED; }
        MenuData menu=world.menu();
        if (p==null || actions.busy() || menu==null || menu.container() || menu.carried()==null || !menu.carried().empty())
            return fail("다른 동작 또는 열린 보관함이 있어 계단 이동을 멈춥니다.");
        if (now==lastTick) return Navigation.Result.MOVING;
        if (lastTick!=Long.MIN_VALUE && now<lastTick) return fail("내려가는 동안 시간이 되돌아갔습니다.");
        boolean measured=sample!=null && now-lastTick==1;
        double vx=measured ? p.x()-sample.x() : 0,vz=measured ? p.z()-sample.z() : 0;
        sample=p;lastTick=now;airborne=!p.onGround();
        if (firstTick==Long.MIN_VALUE) { firstTick=now;progressTick=now;progressSample=p; }
        if (!Double.isFinite(fromHeight) || !Double.isFinite(toHeight)
            || !TerrainPathSearch.loadedStance(world,from) || !TerrainPathSearch.loadedStance(world,to) || !world.canStand(from) || !world.canStand(to)
            || !world.canTraverse(from,to) || !descending(from,to,world)
            || Math.abs(world.standingY(from)-fromHeight)>.00001 || Math.abs(world.standingY(to)-toHeight)>.00001)
            return fail("내려갈 경로의 실제 발판 또는 통행 조건이 바뀌었습니다.");
        if (closedDoor(world,from) || closedDoor(world,to)) return fail("계단 통로의 문이 닫혀 내려가는 이동을 멈춥니다.");
        if (Math.sqrt(Math.pow(p.x()-progressSample.x(),2)+Math.pow(p.y()-progressSample.y(),2)+Math.pow(p.z()-progressSample.z(),2))>.08) {
            progressSample=p;progressTick=now;
        }
        if (now-progressTick>60 || now-firstTick>200) return fail("계단의 안전한 정지 또는 착지가 시간 안에 확인되지 않았습니다.");
        if (phase==Phase.PREPARE) {
            if (!p.onGround() || Math.abs(p.y()-fromHeight)>HEIGHT_TOLERANCE || horizontal(p,from)>.45)
                return fail("계단을 내려가기 전에 상단의 안전한 지면에서 정지하지 못했습니다.");
            if (settled(p,from,measured,vx,vz)) {
                phase=Phase.DESCEND;quietSamples=0;progressTick=now;actions.stopMovement();
            } else align(p,from,measured,vx,vz);
            return Navigation.Result.MOVING;
        }
        // This is the existing one-edge descending corridor, not a permission
        // to recover side falls or extend a landing beyond its planned center.
        int dx=to.x()-from.x(),dz=to.z()-from.z();
        double rx=p.x()-from.x()-.5,rz=p.z()-from.z()-.5;
        double along=rx*dx+rz*dz,lateral=Math.abs(rx*dz-rz*dx);
        boolean onUpper=p.onGround() && Math.abs(p.y()-fromHeight)<=HEIGHT_TOLERANCE
            && from.equals(NavigationFeet.resolve(world,p)) && world.canStand(from) && horizontal(p,from)<=.45;
        if (along<0 && !onUpper || along>1.00001 || lateral>.38 || p.y()<toHeight-HEIGHT_TOLERANCE || p.y()>fromHeight+HEIGHT_TOLERANCE)
            return fail("내려가는 동안 검증된 한 구간을 벗어났습니다.");
        if (phase==Phase.LAND) {
            if (!p.onGround() || Math.abs(p.y()-toHeight)>HEIGHT_TOLERANCE)
                return fail("계단 착지 확인 중 다시 지면에서 벗어났습니다.");
            if (settled(p,to,measured,vx,vz)) { phase=Phase.COMPLETE;actions.stopMovement();return Navigation.Result.ARRIVED; }
            align(p,to,measured,vx,vz);return Navigation.Result.MOVING;
        }
        if (p.onGround() && Math.abs(p.y()-toHeight)<=HEIGHT_TOLERANCE) {
            phase=Phase.LAND;quietSamples=0;progressTick=now;actions.stopMovement();return Navigation.Result.MOVING;
        }
        if (!p.onGround() || !measured || Math.hypot(vx,vz)>.045) {
            // No airborne acceleration: braking happens before crossing the edge.
            actions.stopMovement();return Navigation.Result.MOVING;
        }
        steer(p,to,APPROACH_INPUT);return Navigation.Result.MOVING;
    }
    private boolean settled(PlayerState p,Pos goal,boolean measured,double vx,double vz) {
        if (measured && horizontal(p,goal)<=CENTER && Math.hypot(vx,vz)<=.002) return ++quietSamples>=2;
        quietSamples=0;return false;
    }
    private void align(PlayerState p,Pos goal,boolean measured,double vx,double vz) {
        if (!measured) { actions.stopMovement();return; }
        double dx=goal.x()+.5-p.x(),dz=goal.z()+.5-p.z(),distance=Math.hypot(dx,dz),speed=Math.hypot(vx,vz);
        if (distance<=CENTER && speed<=.002 || speed>.002 && distance<=1.25*speed+CENTER && dx*vx+dz*vz>=0) {
            actions.stopMovement();
        } else if (speed>.03 && (dx*vx+dz*vz<=0 || 1.25*speed>distance+CENTER)) {
            steerVector(-vx,-vz,.2f);
        } else steer(p,goal,APPROACH_INPUT);
    }
    void cancel() { if (phase!=Phase.COMPLETE) fail("계단 이동이 중단되었습니다."); }
    private Navigation.Result fail(String reason) {
        phase=Phase.FAILED;failure=reason;if(actions!=null) actions.stopMovement();return Navigation.Result.BLOCKED;
    }
    private static double horizontal(PlayerState p,Pos goal) { return Math.hypot(p.x()-goal.x()-.5,p.z()-goal.z()-.5); }
    private static boolean closedDoor(WorldAccess world,Pos feet) {
        for(int dy=0;dy<=1;dy++) {
            BlockData block=world.block(feet.offset(0,dy,0));
            if(block!=null && block.id()!=null && block.id().endsWith("_door") && !block.flag("open")) return true;
        }
        return false;
    }
    private void steer(PlayerState p,Pos goal,float scale) { steerVector(goal.x()+.5-p.x(),goal.z()+.5-p.z(),scale); }
    private void steerVector(double dx,double dz,float scale) {
        actions.move(new Movement((float)Math.toDegrees(Math.atan2(-dx,dz)),0,true,false,false,false,scale));
    }
}
