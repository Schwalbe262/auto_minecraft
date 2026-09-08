package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;

/** One verified ascent, one launch pulse, and two distinct grounded landing observations. */
public final class LoggingJumpController {
    public enum Phase { PREPARE, LIFT, FLIGHT, LAND, COMPLETE, FAILED }
    private final LoggingJumpEdge edge;
    private Phase phase=Phase.PREPARE;
    private long startedTick=Long.MIN_VALUE,lastTick=Long.MIN_VALUE,launchTick;
    private double fromHeight,toHeight;
    private int quietSamples,landingSamples;
    private boolean attempted;
    private String failure="";
    private ActionPort lastActions;
    private PlayerState preparedSample;

    public LoggingJumpController(LoggingJumpEdge edge) { this.edge=edge; }
    public LoggingJumpEdge edge() { return edge; }
    public Phase phase() { return phase; }
    public boolean attempted() { return attempted; }
    public String failureReason() { return failure; }

    public Navigation.Result tick(Context c) {
        if (phase==Phase.FAILED) return Navigation.Result.BLOCKED;
        if (phase==Phase.COMPLETE) return Navigation.Result.ARRIVED;
        lastActions=c.actions();
        if (!LoggingJumpRules.permitted(edge,c)) return fail("벌목 한 칸 오르기의 권한이나 안전 지형이 바뀌었습니다.");
        long now=c.world().tick();
        if (lastTick!=Long.MIN_VALUE && now<lastTick) return fail("벌목 오르기 중 시간이 되돌아갔습니다. 자동 재점프하지 않습니다.");
        // Multiple module calls in one client tick must not clear the launch pulse before physics runs.
        if (now==lastTick) return Navigation.Result.MOVING;
        lastTick=now;
        if (startedTick==Long.MIN_VALUE) {
            startedTick=now; fromHeight=c.world().standingY(edge.from()); toHeight=c.world().standingY(edge.to());
        }
        if (Math.abs(c.world().standingY(edge.from())-fromHeight)>.00001
            || Math.abs(c.world().standingY(edge.to())-toHeight)>.00001)
            return fail("벌목 오르기의 출발 또는 착지 지면 높이가 바뀌었습니다.");
        PlayerState p=c.world().player();
        if (phase==Phase.PREPARE) {
            if (now-startedTick>60) return fail("벌목 오르기 출발점에 안전하게 정렬하지 못했습니다.");
            if (!p.onGround() || Math.abs(p.y()-fromHeight)>LoggingJumpRules.HEIGHT_TOLERANCE
                || !LoggingJumpRules.centered(p,edge.from(),.45)) return fail("벌목 오르기는 확인된 출발 지면에서만 시작합니다.");
            if (!LoggingJumpRules.centered(p,edge.from(),LoggingJumpRules.SOURCE_CENTER)) {
                quietSamples=0; preparedSample=null; steer(c.actions(),p,edge.from()); return Navigation.Result.MOVING;
            }
            c.actions().stopMovement();
            if (preparedSample!=null && Math.hypot(p.x()-preparedSample.x(),p.z()-preparedSample.z())>.002) quietSamples=0;
            preparedSample=p;
            if (++quietSamples<2) return Navigation.Result.MOVING;
            attempted=true; launchTick=now; phase=Phase.LIFT;
            if (!c.actions().moveLoggingJump(edge,true)) return fail("네이티브 벌목 오르기 시작이 거절되었습니다. 재전송하지 않습니다.");
            return Navigation.Result.MOVING;
        }
        if (now-launchTick>40) return fail("벌목 오르기 착지가 시간 안에 확인되지 않았습니다. 자동 재점프하지 않습니다.");
        if (!LoggingJumpRules.insideFlight(edge,p,fromHeight)) return fail("벌목 오르기의 검증된 비행 범위를 벗어났습니다.");
        if (p.onGround() && Math.abs(p.y()-toHeight)<=LoggingJumpRules.HEIGHT_TOLERANCE) {
            if (phase!=Phase.LAND) { phase=Phase.LAND; c.actions().stopMovement(); }
            if (!LoggingJumpRules.centered(p,edge.to(),LoggingJumpRules.LANDING_CENTER)) {
                landingSamples=0; steer(c.actions(),p,edge.to()); return Navigation.Result.MOVING;
            }
            c.actions().stopMovement();
            if (++landingSamples>=2) { phase=Phase.COMPLETE; return Navigation.Result.ARRIVED; }
            return Navigation.Result.MOVING;
        }
        if (phase==Phase.LAND) return fail("벌목 오르기 착지 확인 중 다시 지면에서 벗어났습니다.");
        if (p.onGround()) {
            if (phase==Phase.FLIGHT || now-launchTick>4 || Math.abs(p.y()-fromHeight)>LoggingJumpRules.HEIGHT_TOLERANCE)
                return fail("벌목 오르기의 이륙 또는 착지가 확인되지 않았습니다. 자동 재점프하지 않습니다.");
        } else phase=Phase.FLIGHT;
        if (!c.actions().moveLoggingJump(edge,false)) return fail("벌목 오르기의 안전한 공중 이동이 거절되었습니다.");
        return Navigation.Result.MOVING;
    }

    /** Cancellation latches this controller; it can never issue another launch. */
    public void cancel() { if (phase!=Phase.COMPLETE) fail("벌목 오르기가 취소되었습니다. 같은 요청을 재전송하지 않습니다."); }
    private Navigation.Result fail(String message) {
        phase=Phase.FAILED; failure=message;
        if (lastActions!=null) lastActions.stopMovement();
        return Navigation.Result.BLOCKED;
    }
    private static void steer(ActionPort actions,PlayerState player,Pos feet) {
        float yaw=(float)Math.toDegrees(Math.atan2(-(feet.x()+.5-player.x()),feet.z()+.5-player.z()));
        actions.move(new Movement(yaw,0,true,false,false,false));
    }
}
