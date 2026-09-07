package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.List;

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

    public void setSprint(boolean sprint) { this.sprint = sprint; }
    public String failureReason() { return failure; }
    /** Measured displacement while movement was requested; teleports are excluded. */
    public double measuredDistance(boolean sprinting, WorldAccess world) {
        observeMotion(world.player());
        return sprinting ? sprintingDistance : walkingDistance;
    }

    @Override public Result moveTo(Pos target, double reach, Context context) {
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
        if (player.distance(target) <= reach + 2.5 && world.canInteract(target, reach)) {
            actions.stopMovement();
            previousMoving = false;
            path = List.of();
            failure = "";
            return Result.ARRIVED;
        }
        if (world.menu() != null && world.menu().container()) return blocked(actions, "상자가 열린 동안 이동하지 않습니다.");
        if (path.isEmpty()) {
            path = pathfinder.find(walkingFeet, target, reach, world, context.profile());
            if (path.isEmpty()) return blocked(actions, "등록된 통로에 통행 가능한 경로가 없습니다.");
            nextIndex = path.size() > 1 ? 1 : 0;
            lastDistance = Double.POSITIVE_INFINITY;
            progressTick = world.tick();
        }
        while (nextIndex < path.size() - 1 && nearWaypoint(player, path.get(nextIndex))) {
            nextIndex++;
            lastDistance = Double.POSITIVE_INFINITY;
            progressTick = world.tick();
        }
        Pos next = path.get(nextIndex);
        if (!world.loaded(next) || !world.canStand(next)) return blocked(actions, "이동 경로가 바뀌었습니다.");
        if (!walkingFeet.equals(next) && walkingFeet.distanceSquared(next) <= 2
            && !world.canTraverse(walkingFeet, next)) return blocked(actions, "이동 경로가 막혔습니다.");
        if (distanceToCenter(player, next) > 3) return blocked(actions, "경로에서 벗어났습니다.");
        if (nextIndex == path.size() - 1
            && Math.hypot(player.x() - next.x() - .5,player.z() - next.z() - .5) < Math.min(.04,reach / 4)
            && Math.abs(player.y() - next.y()) <= 1.05)
            return blocked(actions, "접근 위치에 도착했지만 목표가 보이지 않거나 손이 닿지 않습니다.");
        Pos door = closedDoor(world, next);
        if (door != null) {
            actions.stopMovement();
            previousMoving = false;
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

    private static boolean nearWaypoint(PlayerState player, Pos feet) {
        return Math.hypot(player.x() - feet.x() - .5,player.z() - feet.z() - .5) < .38
            && Math.abs(player.y() - feet.y()) <= 1.05;
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
    }
}
