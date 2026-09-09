package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;

/** One verified ascent, one launch pulse, and two distinct grounded landing observations. */
public class LoggingJumpController {
    public enum Phase { PREPARE, LIFT, FLIGHT, LAND, COMPLETE, FAILED }
    private final LoggingJumpEdge edge;
    private final boolean loggingAuthority;
    private Phase phase=Phase.PREPARE;
    private long startedTick=Long.MIN_VALUE,lastTick=Long.MIN_VALUE,launchTick;
    private double fromHeight,toHeight;
    private int quietSamples,landingSamples;
    private boolean attempted;
    private String failure="";
    private ActionPort lastActions;
    private PlayerState preparedSample;
    private PlayerState motionSample;
    private long motionSampleTick=Long.MIN_VALUE;
    private boolean outerSourceCentering;

    public LoggingJumpController(LoggingJumpEdge edge) { this(edge,true); }
    protected LoggingJumpController(LoggingJumpEdge edge,boolean loggingAuthority) { this.edge=edge;this.loggingAuthority=loggingAuthority; }
    public LoggingJumpEdge edge() { return edge; }
    public Phase phase() { return phase; }
    public boolean attempted() { return attempted; }
    public String failureReason() { return failure; }
    /** Presentation only; subclasses retain the same verified motion and failure guards. */
    protected String actionLabel() { return "벌목 오르기"; }

    public Navigation.Result tick(Context c) {
        if (phase==Phase.FAILED) return Navigation.Result.BLOCKED;
        if (phase==Phase.COMPLETE) return Navigation.Result.ARRIVED;
        lastActions=c.actions();
        if (!(loggingAuthority ? LoggingJumpRules.permitted(edge,c) : StepUpRules.permitted(edge,c)))
            return fail("한 칸 오르기의 권한이나 안전 지형이 바뀌었습니다.");
        long now=c.world().tick();
        if (lastTick!=Long.MIN_VALUE && now<lastTick) return fail(actionLabel()+" 중 시간이 되돌아갔습니다. 자동 재점프하지 않습니다.");
        // Multiple module calls in one client tick must not clear the launch pulse before physics runs.
        if (now==lastTick) return Navigation.Result.MOVING;
        lastTick=now;
        if (startedTick==Long.MIN_VALUE) {
            startedTick=now; fromHeight=c.world().standingY(edge.from()); toHeight=c.world().standingY(edge.to());
        }
        if (Math.abs(c.world().standingY(edge.from())-fromHeight)>.00001
            || Math.abs(c.world().standingY(edge.to())-toHeight)>.00001)
            return fail(actionLabel()+"의 출발 또는 착지 지면 높이가 바뀌었습니다.");
        PlayerState p=c.world().player();
        boolean measuredMotion=motionSample!=null && now-motionSampleTick==1;
        boolean observationGap=motionSample!=null && !measuredMotion;
        double vx=measuredMotion ? p.x()-motionSample.x() : 0;
        double vz=measuredMotion ? p.z()-motionSample.z() : 0;
        motionSample=p; motionSampleTick=now;
        if (phase==Phase.PREPARE) {
            if (now-startedTick>60) return fail(actionLabel()+" 출발점에 안전하게 정렬하지 못했습니다.");
            if (!p.onGround() || !Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z())
                || Math.abs(p.y()-fromHeight)>LoggingJumpRules.HEIGHT_TOLERANCE)
                return fail(actionLabel()+"는 확인된 출발 지면에서만 시작합니다.");
            // Arriving at an edge does not necessarily leave the player near
            // its centre. Only the SAME native-verified full support may admit
            // ordinary inward walking here; launch/flight bounds stay unchanged.
            if (!LoggingJumpRules.centered(p,edge.from(),.45)) outerSourceCentering=true;
            if (outerSourceCentering && (!edge.from().equals(NavigationFeet.resolve(c.world(),p))
                || !c.world().fullFlatSupport(edge.from())))
                return fail(actionLabel()+" 출발 칸 안의 평평한 지면을 확인하지 못했습니다.");
            if ((outerSourceCentering || observationGap) && !measuredMotion) {
                quietSamples=0; preparedSample=null; c.actions().stopMovement(); return Navigation.Result.MOVING;
            }
            if (!LoggingJumpRules.centered(p,edge.from(),LoggingJumpRules.SOURCE_CENTER)) {
                quietSamples=0; preparedSample=null;
                // Inner arrivals need the same measured small approach as
                // outer arrivals: a full pulse can oscillate across the much
                // smaller launch radius until the preparation deadline.
                if (measuredMotion) centerSource(c.actions(),p,vx,vz);
                else c.actions().stopMovement();
                return Navigation.Result.MOVING;
            }
            c.actions().stopMovement();
            if (measuredMotion && Math.hypot(vx,vz)>.002) {
                quietSamples=0; preparedSample=null; return Navigation.Result.MOVING;
            }
            if (preparedSample!=null && Math.hypot(p.x()-preparedSample.x(),p.z()-preparedSample.z())>.002) quietSamples=0;
            preparedSample=p;
            if (++quietSamples<2) return Navigation.Result.MOVING;
            attempted=true; launchTick=now; phase=Phase.LIFT;
            if (!(loggingAuthority ? c.actions().moveLoggingJump(edge,true) : c.actions().moveStepUp(edge,true)))
                return fail("네이티브 한 칸 오르기 시작이 거절되었습니다. 재전송하지 않습니다.");
            return Navigation.Result.MOVING;
        }
        if (now-launchTick>40) return fail(actionLabel()+" 착지가 시간 안에 확인되지 않았습니다. 자동 재점프하지 않습니다.");
        // Once native ground contact at the landing height was observed, inertia
        // is grounded motion, not a reason to enlarge the airborne permission.
        if (phase==Phase.LAND) return land(c,p,measuredMotion,vx,vz);
        if (!LoggingJumpRules.insideFlight(edge,p,fromHeight)) return fail(actionLabel()+"의 검증된 비행 범위를 벗어났습니다.");
        if (p.onGround() && Math.abs(p.y()-toHeight)<=LoggingJumpRules.HEIGHT_TOLERANCE) {
            phase=Phase.LAND; c.actions().stopMovement();
            return land(c,p,measuredMotion,vx,vz);
        }
        if (p.onGround()) {
            if (phase==Phase.FLIGHT || now-launchTick>4 || Math.abs(p.y()-fromHeight)>LoggingJumpRules.HEIGHT_TOLERANCE)
                return fail(actionLabel()+"의 이륙 또는 착지가 확인되지 않았습니다. 자동 재점프하지 않습니다.");
        } else phase=Phase.FLIGHT;
        if (!(loggingAuthority ? c.actions().moveLoggingJump(edge,false) : c.actions().moveStepUp(edge,false)))
            return fail("한 칸 오르기의 안전한 공중 이동이 거절되었습니다.");
        return Navigation.Result.MOVING;
    }

    private void centerSource(ActionPort actions,PlayerState player,double vx,double vz) {
        double dx=edge.from().x()+.5-player.x(),dz=edge.from().z()+.5-player.z();
        double distance=Math.hypot(dx,dz),speed=Math.hypot(vx,vz),toward=dx*vx+dz*vz;
        // As with landing, measured normal-surface coast only decides input;
        // success still requires two actually quiet samples inside SOURCE_CENTER.
        if (Math.hypot(dx-1.25*vx,dz-1.25*vz)<=.035
            || (speed>.002 && (toward<=0 || distance<=1.25*speed+LoggingJumpRules.SOURCE_CENTER))) {
            actions.stopMovement();
        } else {
            float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
            // The launch radius is narrower than the landing radius; even a
            // reduced pulse can overshoot it with strong inherited motion.
            float inputScale=distance<=.20 ? .1f : .2f;
            actions.move(new Movement(yaw,0,true,false,false,false,inputScale));
        }
    }

    private Navigation.Result land(Context c,PlayerState p,boolean measuredMotion,double vx,double vz) {
        if (!p.onGround() || Math.abs(p.y()-toHeight)>LoggingJumpRules.HEIGHT_TOLERANCE)
            return fail(actionLabel()+" 착지 확인 중 다시 지면에서 벗어났습니다.");
        // Early native landing can happen with only the body's leading edge on
        // the riser. Keep that original corridor, plus only the SAME verified
        // high landing cell. Airborne motion never receives this wider allowance.
        if (!LoggingJumpRules.insideFlight(edge,p,fromHeight) && !LoggingJumpRules.centered(p,edge.to(),.45))
            return fail(actionLabel()+"의 확인된 착지 지면을 벗어났습니다.");
        double speed=Math.hypot(vx,vz);
        if (measuredMotion && speed<=.002 && LoggingJumpRules.centered(p,edge.to(),LoggingJumpRules.LANDING_CENTER)) {
            c.actions().stopMovement();
            if (++landingSamples>=2) { phase=Phase.COMPLETE; return Navigation.Result.ARRIVED; }
            return Navigation.Result.MOVING;
        }
        landingSamples=0;
        if (!measuredMotion) {
            c.actions().stopMovement(); return Navigation.Result.MOVING;
        }
        double dx=edge.to().x()+.5-p.x(),dz=edge.to().z()+.5-p.z();
        double distance=Math.hypot(dx,dz),toward=dx*vx+dz*vz;
        // Normal-surface friction is part of the native proof. This short
        // observed-motion forecast only chooses ordinary stop/coast/brake input;
        // it never predicts a successful landing or substitutes for observations.
        double coastError=Math.hypot(dx-1.25*vx,dz-1.25*vz);
        if (coastError<=.11) {
            c.actions().stopMovement();
        } else if (speed>.08 && (toward<=0 || 1.25*speed>distance+LoggingJumpRules.LANDING_CENTER)) {
            // Opposite ordinary walking input brakes residual movement. No
            // sprint, jump, crouch, velocity write or positional correction.
            steerVector(c.actions(),-vx,-vz);
        } else if (speed>.002 && (toward<=0 || distance<=1.25*speed+LoggingJumpRules.LANDING_CENTER)) {
            c.actions().stopMovement();
        } else if (distance<=.20 && speed<=.03) {
            // Near a quiet landing, a full walking pulse can overshoot the
            // centre and repeatedly trigger the opposite braking pulse. Use
            // a small ordinary step only here; landing still needs two real
            // quiet observations within the unchanged radius and deadline.
            float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
            c.actions().move(new Movement(yaw,0,true,false,false,false,.2f));
        } else steer(c.actions(),p,edge.to());
        return Navigation.Result.MOVING;
    }

    /** Cancellation latches this controller; it can never issue another launch. */
    public void cancel() { if (phase!=Phase.COMPLETE) fail(actionLabel()+"가 취소되었습니다. 같은 요청을 재전송하지 않습니다."); }
    private Navigation.Result fail(String message) {
        phase=Phase.FAILED; failure=message;
        if (lastActions!=null) lastActions.stopMovement();
        return Navigation.Result.BLOCKED;
    }
    private static void steer(ActionPort actions,PlayerState player,Pos feet) {
        steerVector(actions,feet.x()+.5-player.x(),feet.z()+.5-player.z());
    }
    private static void steerVector(ActionPort actions,double dx,double dz) {
        float yaw=(float)Math.toDegrees(Math.atan2(-dx,dz));
        actions.move(new Movement(yaw,0,true,false,false,false));
    }
}
