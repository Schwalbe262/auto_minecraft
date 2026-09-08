package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;

/** A pre-braked, walking-only descent on one already validated cardinal edge. */
final class DescentController {
    enum Phase { PREPARE, DESCEND, LAND, COMPLETE, FAILED }
    private static final double HEIGHT_TOLERANCE=.10001, CENTER=.10;
    private final Pos from,to;
    private final double fromHeight,toHeight;
    private Phase phase=Phase.PREPARE;
    private long firstTick=Long.MIN_VALUE,lastTick=Long.MIN_VALUE,progressTick;
    private PlayerState sample,progressSample;
    private int quietSamples;
    private boolean airborne;
    private String failure="";
    private ActionPort actions;
    private float lastInput;
    private double lastSpeed,observedDrag=Double.NaN;
    private double observedAcceleration=Double.NaN;
    private boolean usedStandardFallBound;

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
        double speed=Math.hypot(vx,vz);
        if(measured && lastInput>0 && lastSpeed<=.002 && sample.onGround() && p.onGround() && Math.abs(p.y()-sample.y())<.00001) {
            // A small pulse from an observed rest estimates this body's input
            // response with margin. This is steering feedback, not a collision,
            // gravity or permission proof; changed motion still meets all guards.
            double response=(speed+.002)/lastInput*1.5;
            if(Double.isFinite(response) && response>.01)
                observedAcceleration=Math.max(Double.isFinite(observedAcceleration)?observedAcceleration:0,Math.max(.06,response));
        }
        if(measured && lastInput==0 && sample.onGround() && p.onGround() && Math.abs(p.y()-sample.y())<.00001 && lastSpeed>.004) {
            double drag=speed/lastSpeed;
            if(Double.isFinite(drag) && drag>=0 && drag<1)
                observedDrag=Math.max(Double.isFinite(observedDrag)?observedDrag:.65,Math.min(.95,drag+.05));
        }
        lastSpeed=speed;
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
                phase=Phase.DESCEND;quietSamples=0;progressTick=now;stop();
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
            if (settled(p,to,measured,vx,vz)) { phase=Phase.COMPLETE;stop();return Navigation.Result.ARRIVED; }
            align(p,to,measured,vx,vz);return Navigation.Result.MOVING;
        }
        if (p.onGround() && Math.abs(p.y()-toHeight)<=HEIGHT_TOLERANCE) {
            phase=Phase.LAND;quietSamples=0;progressTick=now;stop();return Navigation.Result.MOVING;
        }
        boolean standardPhysics=world.standardDescentPhysics();
        if (!p.onGround() && usedStandardFallBound && !standardPhysics)
            return fail("내려가는 도중 표준 낙하 조건이 바뀌어 이동을 중단합니다.");
        if (!p.onGround() || !measured) {
            // No airborne acceleration: braking happens before crossing the edge.
            stop();return Navigation.Result.MOVING;
        }
        if(!Double.isFinite(observedDrag)) {
            // Establish actual ground damping with one small pulse and a coast
            // sample before allowing stronger input. Unknown friction is not
            // assumed to be ordinary stone/farmland merely from the block ID.
            if(lastInput>0 || speed>.004) stop();
            else steer(p,to,.08f);
            return Navigation.Result.MOVING;
        }
        if(observedDrag>.80) return fail("실제 감속이 충분하지 않은 미끄러운 지면에서는 하강을 시작하지 않습니다.");
        // Assume the next input may already leave support. The remaining height
        // determines a conservative no-input fall horizon; reserve the final
        // center margin for grounded braking. This only chooses input strength:
        // observed support, corridor and landing checks remain authoritative.
        double speedLimit=Math.min(.18,Math.max(0,horizontal(p,to)-CENTER)/fallCoastFactor(p.y()-toHeight,standardPhysics));
        double accelerationRoom=speedLimit-.91*speed;
        if(accelerationRoom<=0) stop();
        else {
            usedStandardFallBound=standardPhysics;
            steer(p,to,(float)Math.min(.45,accelerationRoom/accelerationAllowance()));
        }
        return Navigation.Result.MOVING;
    }
    private boolean settled(PlayerState p,Pos goal,boolean measured,double vx,double vz) {
        if (measured && horizontal(p,goal)<=CENTER && Math.hypot(vx,vz)<=.002) return ++quietSamples>=2;
        quietSamples=0;return false;
    }
    private void align(PlayerState p,Pos goal,boolean measured,double vx,double vz) {
        if (!measured) { stop();return; }
        double dx=goal.x()+.5-p.x(),dz=goal.z()+.5-p.z(),distance=Math.hypot(dx,dz),speed=Math.hypot(vx,vz);
        double drag=Double.isFinite(observedDrag)?observedDrag:.91;
        double coast=drag/(1-drag)*speed,toward=dx*vx+dz*vz;
        if (distance<=CENTER && speed<=.002) stop();
        else if(speed>.004 && (toward<=0 || coast>distance+CENTER*.5))
            steerVector(-vx,-vz,(float)Math.max(.02,Math.min(.4,.8*speed/accelerationAllowance())));
        else if(speed>.002 && toward>=0 && distance<=coast+CENTER) stop();
        else {
            double room=Math.max(0,distance-CENTER*.7-coast);
            steer(p,goal,(float)Math.max(.02,Math.min(.35,room/accelerationAllowance())));
        }
    }
    void cancel() { if (phase!=Phase.COMPLETE) fail("계단 이동이 중단되었습니다."); }
    private Navigation.Result fail(String reason) {
        phase=Phase.FAILED;failure=reason;if(actions!=null) stop();return Navigation.Result.BLOCKED;
    }
    private static double fallCoastFactor(double height,boolean standardPhysics) {
        // With unknown/slow gravity the complete .91 geometric series bounds
        // passive horizontal travel for any flight duration, not just vanilla's.
        if(!standardPhysics) return 1/(1-.91);
        double fallen=0,down=0;int ticks=0;
        while(fallen<Math.max(0,height)+HEIGHT_TOLERANCE && ticks<10) { down=(down+.08)*.98;fallen+=down;ticks++; }
        double factor=0,drag=1;
        for(int n=0;n<ticks+2;n++) { factor+=drag;drag*=.91; }
        return factor;
    }
    private void stop() { lastInput=0;actions.stopMovement(); }
    private double accelerationAllowance() { return Double.isFinite(observedAcceleration)?observedAcceleration:.30; }
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
        lastInput=scale;
        actions.move(new Movement((float)Math.toDegrees(Math.atan2(-dx,dz)),0,true,false,false,false,scale));
    }
}
