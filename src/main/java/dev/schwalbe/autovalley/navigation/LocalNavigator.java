package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Tick-driven movement; reset/cancel never produces an interaction. */
public final class LocalNavigator implements Navigation {
    private final LocalPathfinder pathfinder = new LocalPathfinder();
    private List<Pos> path = List.of();
    private Pos destination;
    private double destinationReach;
    private int nextIndex;
    private long progressTick;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private long doorTicket = -1;
    private boolean sprint;
    private PlayerState previousPlayer;
    private boolean previousMoving;
    private boolean previousSprint;
    private double walkingDistance;
    private double sprintingDistance;
    private String failure = "";
    private static final int MAX_REJECTED_ENDPOINTS = 4;
    private static final int ENDPOINT_SETTLE_TIMEOUT_TICKS = 10;
    private final Set<Pos> rejectedEndpoints = new HashSet<>();
    private long endpointSettleTick = -1, endpointSampleTick;
    private int endpointQuietTicks;
    private PlayerState endpointSample;

    public void setSprint(boolean sprint) { this.sprint = sprint; }
    public String failureReason() { return failure; }
    /** Measured displacement while movement was requested; teleports are excluded. */
    public double measuredDistance(boolean sprinting, WorldAccess world) {
        observeMotion(world.player());
        return sprinting ? sprintingDistance : walkingDistance;
    }

    @Override public Result moveTo(Pos target, double reach, Context context) {
        return moveTo(target,reach,context,true);
    }

    /** Harvest lookahead must never open a door while another click is being verified. */
    @Override public Result moveToWithoutInteraction(Pos target,double reach,Context context) {
        return moveTo(target,reach,context,false);
    }

    private Result moveTo(Pos target,double reach,Context context,boolean allowDoors) {
        WorldAccess world = context.world();
        ActionPort actions = context.actions();
        PlayerState player = world.player();
        observeMotion(player);
        if (player == null || !player.connected() || !player.focused() && !context.profile().allowBackground)
            return blocked(actions, "플레이어가 게임을 조작할 수 없습니다.");
        Pos walkingFeet = walkingFeet(world,player);
        if (!target.equals(destination) || Double.compare(reach, destinationReach) != 0) {
            reset();
            destination = target;
            destinationReach = reach;
            progressTick = world.tick();
        }
        if (!ProfileBounds.contains(context.profile(), walkingFeet)) return blocked(actions, "등록한 작업 구역 밖입니다. 연결 경유지를 등록하세요.");
        if (!world.loaded(target)) return blocked(actions, "목표 청크가 로드되지 않았습니다.");
        if (doorTicket >= 0) {
            actions.stopMovement();
            ActionOutcome outcome = actions.outcome(doorTicket);
            if (!outcome.done()) return Result.MOVING;
            doorTicket = -1;
            if (!outcome.success()) return blocked(actions, "문을 열지 못했습니다: " + outcome.message());
            progressTick = world.tick();
        }
        if (endpointSettleTick < 0 && player.distance(target) <= reach + 2.5 && world.canInteract(target, reach)) {
            actions.stopMovement();
            previousMoving = false;
            path = List.of();
            rejectedEndpoints.clear();
            failure = "";
            return Result.ARRIVED;
        }
        if (world.menu() != null && world.menu().container()) return blocked(actions, "상자가 열린 동안 이동하지 않습니다.");
        if (path.isEmpty()) {
            if (rejectedEndpoints.size() >= MAX_REJECTED_ENDPOINTS)
                return blocked(actions,"정지 후에도 확인한 접근 위치 4곳에서 목표가 보이지 않거나 손이 닿지 않습니다.");
            path = pathfinder.find(walkingFeet, target, reach, world, context.profile(), rejectedEndpoints);
            if (path.isEmpty()) return blocked(actions, "등록된 통로에 통행 가능한 경로가 없습니다.");
            nextIndex = path.size() > 1 ? 1 : 0;
            lastDistance = Double.POSITIVE_INFINITY;
            progressTick = world.tick();
        }
        while (nextIndex < path.size() - 1 && nearWaypoint(world, player, path.get(nextIndex))) {
            nextIndex++;
            lastDistance = Double.POSITIVE_INFINITY;
            progressTick = world.tick();
        }
        Pos next = path.get(nextIndex);
        if (!world.loaded(next) || !world.canStand(next)) return blocked(actions, "이동 경로가 바뀌었습니다.");
        boolean descendingEdge = verifiedDescendingEdge(world, player, next);
        // A body's center can cross into the lower cell while its rear still
        // rests on the previous step. The integer feet cell then has no floor.
        // Revalidate only that already planned edge, never a guessed new fall.
        if (!world.canStand(walkingFeet) && !descendingEdge)
            return blocked(actions, "현재 발밑의 안전한 경로를 확인할 수 없습니다.");
        if (!walkingFeet.equals(next) && walkingFeet.distanceSquared(next) <= 2
            && !world.canTraverse(walkingFeet, next) && !descendingEdge) return blocked(actions, "이동 경로가 막혔습니다.");
        if (descendingEdge && !player.onGround()) {
            actions.stopMovement();
            previousMoving = false;
            if (world.tick() - progressTick > 60) return blocked(actions, "계단 착지가 3초 동안 완료되지 않았습니다.");
            return Result.MOVING;
        }
        if (distanceToCenter(player, next) > 3) return blocked(actions, "경로에서 벗어났습니다.");
        if (endpointSettleTick >= 0) return settleEndpoint(next,target,reach,context,player);
        if (nextIndex == path.size() - 1
            && Math.hypot(player.x() - next.x() - .5,player.z() - next.z() - .5) < Math.min(.04,reach / 4)
            && player.onGround() && Math.abs(player.y() - waypointHeight(world,next)) <= .10001)
        {
            // A* evaluates an exact block center; the real eye ray can still be
            // occluded a few hundredths away. Stop residual motion before rejecting
            // this center, then try another validated goal instead of failing at once.
            endpointSettleTick=world.tick(); endpointSampleTick=world.tick();
            endpointQuietTicks=0; endpointSample=player;
            actions.stopMovement(); previousMoving=false;
            return Result.MOVING;
        }
        Pos door = closedDoor(world, next);
        if (door != null) {
            actions.stopMovement();
            previousMoving = false;
            if (!allowDoors) return blocked(actions,"수확 이동을 이어가려면 문을 열어야 하므로 잠시 멈춥니다.");
            if (!world.canInteract(door, 4.25)) return blocked(actions, "문에 손이 닿지 않습니다.");
            if (actions.busy()) return Result.MOVING;
            doorTicket = actions.submit(new Action.UseBlock(door, Action.Use.DOOR));
            return Result.MOVING;
        }
        double distance = distanceToCenter(player, next);
        if (distance < lastDistance - 0.08) {
            lastDistance = distance;
            progressTick = world.tick();
        }
        if (world.tick() - progressTick > 60) return blocked(actions, "이동이 3초 동안 진행되지 않았습니다.");
        double dx = next.x() + 0.5 - player.x(), dz = next.z() + 0.5 - player.z();
        float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
        boolean flat = next.y() == walkingFeet.y();
        previousMoving = true;
        previousSprint = sprint && flat && distance > 0.55;
        actions.move(new Movement(yaw, 0, true, previousSprint, false, false));
        return Result.MOVING;
    }

    private Result settleEndpoint(Pos endpoint,Pos target,double reach,Context context,PlayerState player) {
        WorldAccess world=context.world(); ActionPort actions=context.actions();
        actions.stopMovement(); previousMoving=false;
        if (world.tick()>endpointSampleTick) {
            double displacement=Math.sqrt(Math.pow(player.x()-endpointSample.x(),2)
                +Math.pow(player.y()-endpointSample.y(),2)+Math.pow(player.z()-endpointSample.z(),2));
            endpointQuietTicks=player.onGround() && displacement<=.02 ? endpointQuietTicks+1 : 0;
            endpointSample=player; endpointSampleTick=world.tick();
        }
        if (endpointQuietTicks>=2 && world.tick()-endpointSettleTick>=2) {
            if (world.canInteract(target,reach)) {
                clearEndpointSettle(); rejectedEndpoints.clear(); path=List.of(); failure="";
                return Result.ARRIVED;
            }
            rejectedEndpoints.add(endpoint); clearEndpointSettle(); path=List.of();
            lastDistance=Double.POSITIVE_INFINITY; progressTick=world.tick();
            if (rejectedEndpoints.size()>=MAX_REJECTED_ENDPOINTS)
                return blocked(actions,"정지 후에도 확인한 접근 위치 4곳에서 목표가 보이지 않거나 손이 닿지 않습니다.");
            return Result.MOVING; // Replan next tick; this tick remains stopped.
        }
        if (world.tick()-endpointSettleTick>=ENDPOINT_SETTLE_TIMEOUT_TICKS)
            return blocked(actions,"접근 위치에서 안전하게 정지하지 못했습니다.");
        return Result.MOVING;
    }

    private void clearEndpointSettle() {
        endpointSettleTick=-1; endpointSampleTick=0; endpointQuietTicks=0; endpointSample=null;
    }

    private void observeMotion(PlayerState player) {
        if (player != null && previousPlayer != null && previousMoving) {
            double distance = Math.hypot(player.x() - previousPlayer.x(), player.z() - previousPlayer.z());
            if (distance < 2) {
                if (previousSprint) sprintingDistance += distance;
                else walkingDistance += distance;
            }
        }
        previousPlayer = player;
    }

    private static Pos closedDoor(WorldAccess world, Pos feet) {
        for (int dy : new int[]{0,1}) {
            Pos pos = feet.offset(0,dy,0);
            BlockData block = world.block(pos);
            if (block != null && block.id().endsWith("_door") && !block.flag("open")) return pos;
        }
        return null;
    }

    private static double distanceToCenter(PlayerState player, Pos feet) {
        return Math.sqrt(Math.pow(player.x() - feet.x() - 0.5, 2) + Math.pow(player.y() - feet.y(), 2)
            + Math.pow(player.z() - feet.z() - 0.5, 2));
    }

    private static boolean nearWaypoint(WorldAccess world, PlayerState player, Pos feet) {
        return Math.hypot(player.x() - feet.x() - .5,player.z() - feet.z() - .5) < .38
            && player.onGround() && Math.abs(player.y() - waypointHeight(world,feet)) <= .10001;
    }

    private static double waypointHeight(WorldAccess world, Pos feet) {
        double height = world.standingY(feet);
        // Legacy/pure adapters can still navigate flat integer floors, but
        // unknown heights NEVER authorize the descending-edge exception below.
        return Double.isFinite(height) ? height : feet.y();
    }

    private boolean verifiedDescendingEdge(WorldAccess world, PlayerState player, Pos next) {
        if (nextIndex <= 0) return false;
        Pos from = path.get(nextIndex - 1);
        int dx = next.x() - from.x(), dz = next.z() - from.z();
        if (Math.abs(dx) + Math.abs(dz) != 1 || next.y() > from.y()) return false;
        double fromHeight = world.standingY(from), toHeight = world.standingY(next);
        if (!Double.isFinite(fromHeight) || !Double.isFinite(toHeight)
            || fromHeight - toHeight <= .10001 || fromHeight - toHeight > 1.00001
            || player.y() < toHeight - .10001 || player.y() > fromHeight + .10001) return false;
        double relativeX = player.x() - from.x() - .5, relativeZ = player.z() - from.z() - .5;
        double along = relativeX * dx + relativeZ * dz;
        double lateral = Math.abs(relativeX * dz - relativeZ * dx);
        // The exception is local to this one center-to-center corridor. It
        // cannot authorize a side fall, a skipped step, or an unobserved floor.
        if (along < 0 || along > 1.00001 || lateral > .38) return false;
        Pos actual = player.feet();
        if (!(actual.x() == from.x() && actual.z() == from.z())
            && !(actual.x() == next.x() && actual.z() == next.z())) return false;
        return world.loaded(from) && world.loaded(next) && world.canStand(from)
            && world.canStand(next) && world.canTraverse(from,next);
    }

    private static Pos walkingFeet(WorldAccess world, PlayerState player) {
        Pos feet = player.feet();
        // Farmland, bottom slabs, and stair halves put the physical feet inside their block cell.
        return !world.canStand(feet) && world.canStand(feet.offset(0,1,0)) ? feet.offset(0,1,0) : feet;
    }

    private Result blocked(ActionPort actions, String reason) {
        actions.stopMovement();
        previousMoving = false;
        failure = reason;
        path = List.of();
        clearEndpointSettle();
        return Result.BLOCKED;
    }

    @Override public void reset() {
        path = List.of();
        destination = null;
        doorTicket = -1;
        lastDistance = Double.POSITIVE_INFINITY;
        progressTick = 0;
        nextIndex = 0;
        previousMoving = false;
        failure = "";
        rejectedEndpoints.clear(); clearEndpointSettle();
    }
}
