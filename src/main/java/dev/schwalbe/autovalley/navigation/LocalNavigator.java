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
    private long lastTravelTick=Long.MIN_VALUE;
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
    private boolean loggingPath;
    private List<Pos> plantingTargets=List.of();
    private LoggingJumpController loggingJump;
    private boolean interruptedLanding;
    /** A cancelled/uncertain launch is never silently retried by reset or another path search. */
    private final Set<LoggingJumpEdge> interruptedJumps=new HashSet<>();
    private TravelDomain domain;
    private Profile requestProfile;
    private SessionState requestSession;
    private NavigationMode requestMode;
    /** Lookahead is walking only, including after its harvest ticket completes. */
    private boolean requestInteractions;
    private TerrainPathSearch.Goal goal=TerrainPathSearch.Goal.INTERACTION;
    private TerrainPathSearch search;
    private TerrainPathSearch.Frontier frontier;
    private final Set<TerrainPathSearch.Frontier> rejectedFrontiers=new HashSet<>();
    private long frontierWait=-1,searchBudgetTick=Long.MIN_VALUE;
    private int frontierAttempts,replans,requestNodes,sliceNodes,sliceLos;
    private long sliceNanos;
    private Failure failureKind=Failure.NONE;
    private String diagnostic="IDLE";
    private ActionPort lastActions;
    private WorldAccess lastWorld;
    private DescentController descent;
    private boolean interruptedDescent;
    private java.util.Map<String,Object> lastFailure=java.util.Map.of();
    /** One bounded original controller failure; subsequent routing guards cannot overwrite it. */
    private java.util.Map<String,Object> lastAscentFailure=java.util.Map.of();

    public void setSprint(boolean sprint) { this.sprint = sprint; }
    public String failureReason() { return failure; }
    @Override public Failure failureKind() { return failureKind; }
    @Override public boolean retryableFailure() {
        return failureKind==Failure.NO_PATH || failureKind==Failure.UNLOADED || failureKind==Failure.SEARCH_LIMIT
            || failureKind==Failure.OBSTACLE || failureKind==Failure.STALLED || failureKind==Failure.REACH;
    }
    @Override public Pos failureDestination() { return failureKind==Failure.NONE ? null : destination; }
    @Override public String diagnosticStatus() { return diagnostic; }
    @Override public java.util.Map<String,Object> diagnostics() {
        java.util.Map<String,Object> report=new java.util.LinkedHashMap<>();
        report.put("state",diagnostic);report.put("mode",requestMode==null ? "NONE" : requestMode.name());
        report.put("goalKind",goal.name());report.put("failure",failureKind.name());report.put("destination",destination);
        report.put("sliceNodes",sliceNodes);report.put("sliceLos",sliceLos);report.put("sliceElapsedNanos",sliceNanos);
        report.put("requestExpanded",requestNodes);report.put("replans",replans);report.put("frontierAttempts",frontierAttempts);
        report.put("pathLength",path.size());report.put("nextIndex",nextIndex);
        report.put("descentHandoffs",descent==null ? 0 : descent.completedEdges());
        report.put("descentFlow",descent!=null && descent.flowing());
        report.put("searchLimit",search==null ? 0 : search.nodeLimit());
        report.put("lastFailure",lastFailure);report.put("lastAscentFailure",lastAscentFailure);
        return java.util.Collections.unmodifiableMap(report);
    }
    @Override public boolean permitsTransit(Pos feet,Context c) {
        return domain!=null && destination!=null && c.profile()==requestProfile && c.session()==requestSession
            && c.profile().navigationMode==requestMode && failureKind==Failure.NONE && domain.contains(feet) && c.world().loaded(feet);
    }
    /** No controller, action, route or ownership state is changed by this optional-work query. */
    @Override public boolean canYieldTravel(Context c) {
        try {
            if (c==null || c.navigation()!=this || c.world()!=lastWorld || c.actions()!=lastActions
                || c.profile()!=requestProfile || c.session()!=requestSession || requestMode==null
                || c.profile().navigationMode!=requestMode || !requestInteractions || loggingPath || !plantingTargets.isEmpty()
                || !"FOLLOWING".equals(diagnostic) || !previousMoving || failureKind!=Failure.NONE || destination==null || domain==null
                || loggingJump!=null || descent!=null || interruptedLanding || interruptedDescent || !interruptedJumps.isEmpty()
                || doorTicket>=0 || endpointSettleTick>=0 || search!=null || frontier!=null || frontierWait>=0
                || nextIndex<=0 || nextIndex>=path.size()) return false;
            WorldAccess world=c.world();PlayerState player=world.player();MenuData menu=world.menu();
            long now=world.tick();
            if (lastTravelTick==Long.MIN_VALUE || now<lastTravelTick || now-lastTravelTick>1
                || player==null || !player.connected() || !player.onGround() || player.sleeping()
                || !player.focused() && !c.profile().allowBackground
                || !Double.isFinite(player.x()) || !Double.isFinite(player.y()) || !Double.isFinite(player.z())
                || c.actions().busy() || menu==null || menu.container() || menu.carried()==null || !menu.carried().empty()) return false;
            Pos from=path.get(nextIndex-1),next=path.get(nextIndex),feet=NavigationFeet.resolve(world,player);
            int dx=Math.abs(next.x()-from.x()),dz=Math.abs(next.z()-from.z());
            if (from.y()!=next.y() || dx>1 || dz>1 || dx+dz==0 || !feet.equals(from) && !feet.equals(next)) return false;
            double surface=world.standingY(from);
            if (!Double.isFinite(surface) || Math.abs(player.y()-surface)>1.0e-4) return false;
            // The actual feet must already equal one of these two cells.
            for (Pos cell:List.of(from,next)) {
                if (!domain.contains(cell) || !TerrainPathSearch.loadedStance(world,cell) || !world.canStand(cell)
                    || !world.fullFlatSupport(cell) || !Double.isFinite(world.standingY(cell))
                    || Math.abs(world.standingY(cell)-surface)>1.0e-4) return false;
                for (int dy=0;dy<=1;dy++) {
                    BlockData block=world.block(cell.offset(0,dy,0));
                    if (block==null || block.id()==null || !cell.offset(0,dy,0).equals(block.pos()) || block.id().endsWith("_door")) return false;
                }
            }
            return canTraverse(from,next,world,domain);
        } catch (RuntimeException unavailable) { return false; }
    }
    @Override public boolean permitsStepUp(LoggingJumpEdge edge,Context c) {
        return requestInteractions && loggingJump instanceof StepUpController && loggingJump.edge().equals(edge)
            && loggingJump.phase()!=LoggingJumpController.Phase.FAILED && loggingJump.phase()!=LoggingJumpController.Phase.COMPLETE
            && permitsTransit(edge.from(),c) && permitsTransit(edge.to(),c);
    }
    /** Measured displacement while movement was requested; teleports are excluded. */
    public double measuredDistance(boolean sprinting, WorldAccess world) {
        observeMotion(world.player());
        return sprinting ? sprintingDistance : walkingDistance;
    }

    @Override public Result moveTo(Pos target, double reach, Context context) {
        return moveTo(target,reach,context,true,false);
    }
    @Override public Result moveToPosition(Pos target,double reach,Context context) {
        return moveTo(target,reach,context,true,false,List.of(),TerrainPathSearch.Goal.POSITION);
    }
    @Override public Result moveToObserve(Pos target,double reach,Context context) {
        return moveTo(target,reach,context,true,false,List.of(),TerrainPathSearch.Goal.OBSERVE);
    }

    @Override public Result moveToLogging(Pos target,double reach,Context context) {
        return moveTo(target,reach,context,true,true);
    }

    @Override public Result moveToLoggingPlanting(List<Pos> targets,Context context) {
        if (!LoggingRules.plantingTargets(context.profile(),targets)
            || context.profile().loggingPlots.stream().noneMatch(p -> p.plantingPositions().containsAll(targets)
                && context.profile().loggingReplantingPlots.contains(p.corner())))
            return blocked(context.actions(),"등록된 미완료 2x2 재식재 구역만 접근할 수 있습니다.");
        return moveTo(targets.get(0).offset(0,-1,0),3.25,context,true,true,List.copyOf(targets));
    }

    /** Harvest lookahead must never open a door while another click is being verified. */
    @Override public Result moveToWithoutInteraction(Pos target,double reach,Context context) {
        return moveTo(target,reach,context,false,false);
    }

    private Result moveTo(Pos target,double reach,Context context,boolean allowDoors,boolean logging) {
        return moveTo(target,reach,context,allowDoors,logging,List.of());
    }

    private Result moveTo(Pos target,double reach,Context context,boolean allowDoors,boolean logging,List<Pos> planting) {
        return moveTo(target,reach,context,allowDoors,logging,planting,TerrainPathSearch.Goal.INTERACTION);
    }
    private Result moveTo(Pos target,double reach,Context context,boolean allowDoors,boolean logging,List<Pos> planting,TerrainPathSearch.Goal requestedGoal) {
        WorldAccess world = context.world();
        lastWorld=world;
        ActionPort actions = context.actions();
        if (lastActions!=null && lastActions!=actions) lastActions.stopMovement();
        lastActions=actions;
        PlayerState player = world.player();
        observeMotion(player);
        if (player == null || !player.connected() || !player.focused() && !context.profile().allowBackground)
            return blocked(actions, "플레이어가 게임을 조작할 수 없습니다.");
        if (logging && (!context.profile().loggingRunActive || !context.session().allows(context.profile(),Feature.LOGGING)))
            return blocked(actions,"벌목 작업이 활성화된 실행에서만 전용 이동을 사용할 수 있습니다.");
        if (target==null || !Double.isFinite(reach) || reach<0) return blocked(actions,Failure.SAFETY,"이동 목적지 또는 도달 거리가 올바르지 않습니다.");
        if (!target.equals(destination) || Double.compare(reach, destinationReach) != 0 || loggingPath!=logging || !plantingTargets.equals(planting)
            || goal!=requestedGoal || requestProfile!=context.profile() || requestSession!=context.session() || requestMode!=context.profile().navigationMode
            || requestInteractions!=allowDoors) {
            reset();
            destination = target;
            destinationReach = reach;
            loggingPath=logging;
            plantingTargets=planting;
            goal=requestedGoal;requestProfile=context.profile();requestSession=context.session();requestMode=context.profile().navigationMode;
            requestInteractions=allowDoors;
            domain=new TravelDomain(context.profile(),NavigationFeet.resolve(world,player),target);
            progressTick = world.tick();
        }
        if (interruptedLanding) {
            if (!player.onGround()) return blocked(actions,"중단된 벌목 오르기의 착지가 확인되지 않았습니다.");
            Pos landed=NavigationFeet.resolve(world,player);
            if (!world.canStand(landed) || !Double.isFinite(world.standingY(landed))
                || Math.abs(player.y()-world.standingY(landed))>LoggingJumpRules.HEIGHT_TOLERANCE)
                return blocked(actions,"중단된 벌목 오르기 뒤 실제 지면을 확인할 수 없습니다.");
            interruptedLanding=false;
        }
        if (interruptedDescent) {
            Pos landed=NavigationFeet.resolve(world,player);
            if (!player.onGround() || !world.canStand(landed) || !Double.isFinite(world.standingY(landed))
                || Math.abs(player.y()-world.standingY(landed))>.10001)
                return blocked(actions,"중단된 하강의 안전한 착지가 아직 확인되지 않았습니다.");
            interruptedDescent=false;
        }
        // In flight, raw feet can occupy an unsupported cell. Only this controller
        // may steer, and neither interaction proximity nor normal waypoint skipping
        // may finish the move before two grounded landing observations.
        if (loggingJump!=null) { diagnostic="STEP_UP";return continueLoggingJump(context); }
        if (descent!=null) return continueDescent(context);
        Pos walkingFeet = NavigationFeet.resolve(world,player);
        if (!domain.contains(walkingFeet)) return blocked(actions,Failure.INVALID_START,"현재 이동 요청의 안전 탐색 영역 밖입니다.");
        if (!world.loaded(target) && !domain.terrain()) return blocked(actions,Failure.UNLOADED,"목표 청크가 로드되지 않았습니다.");
        if (doorTicket >= 0) {
            actions.stopMovement();
            ActionOutcome outcome = actions.outcome(doorTicket);
            if (!outcome.done()) return Result.MOVING;
            doorTicket = -1;
            if (!outcome.success()) return blocked(actions, "문을 열지 못했습니다: " + outcome.message());
            progressTick = world.tick();
        }
        if (endpointSettleTick < 0 && (!logging || player.onGround())
            && player.distance(target) <= reach + 2.5 && interactionReady(world,target,reach)) {
            actions.stopMovement();
            previousMoving = false;
            path = List.of();
            rejectedEndpoints.clear();
            failure = "";
            failureKind=Failure.NONE;diagnostic="ARRIVED";search=null;frontier=null;
            return Result.ARRIVED;
        }
        if (world.menu() != null && world.menu().container()) return blocked(actions, "상자가 열린 동안 이동하지 않습니다.");
        if (path.isEmpty()) {
            if (rejectedEndpoints.size() >= MAX_REJECTED_ENDPOINTS)
                return blocked(actions,Failure.REACH,"정지 후에도 확인한 접근 위치 4곳에서 목표가 보이지 않거나 손이 닿지 않습니다.");
            if (domain.terrain() || goal!=TerrainPathSearch.Goal.INTERACTION) {
                Result planned=planTerrain(context,walkingFeet);
                if (planned!=null) return planned;
            } else {
                path = !plantingTargets.isEmpty() ? pathfinder.findLoggingPlanting(walkingFeet,plantingTargets,reach,world,context.profile(),rejectedEndpoints)
                    : logging ? pathfinder.findLogging(walkingFeet,target,reach,world,context.profile(),rejectedEndpoints)
                    : pathfinder.find(walkingFeet, target, reach, world, context.profile(), rejectedEndpoints);
                if (path.isEmpty()) return blocked(actions,Failure.NO_PATH,"등록된 통로에 통행 가능한 경로가 없습니다.");
            }
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
        if (frontier!=null && nextIndex==path.size()-1 && standingNear(world,player,next,.15)) return arriveFrontier(context,walkingFeet);
        if (!world.loaded(next) || !world.canStand(next)) return replan(context,Failure.OBSTACLE,"이동 경로가 바뀌었습니다.");
        if (nextIndex>0 && DescentController.descending(path.get(nextIndex-1),next,world) && closedDoor(world,next)==null) {
            if (!requestInteractions) return blocked(actions,"수확을 이어가는 이동에서는 계단 하강을 시작하지 않습니다.");
            descent=new DescentController(path.get(nextIndex-1),next,world);
            return continueDescent(context);
        }
        if (requestInteractions && (logging || domain.terrain()) && nextIndex>0) {
            LoggingJumpEdge edge=new LoggingJumpEdge(path.get(nextIndex-1),next);
            if (!world.canTraverse(edge.from(),edge.to()) && LoggingJumpRules.validShape(edge)) {
                if (interruptedJumps.contains(edge) || interruptedJumps.size()>=256)
                    return blocked(actions,"이전 벌목 오르기가 중단되어 같은 점프를 자동 재시도하지 않습니다.");
                boolean useLogging=logging && LoggingJumpRules.permitted(edge,context);
                boolean useGeneral=!useLogging && domain.terrain() && StepUpRules.permitted(edge,context);
                if (!useLogging && !useGeneral)
                    return replan(context,Failure.OBSTACLE,"한 칸 오르기 경로의 네이티브 안전 확인이 실패했습니다.");
                // Keep the selected authority for this entire edge. A logging
                // approach may use independently verified terrain transit, but
                // an in-flight controller never switches its permission model.
                loggingJump=useLogging ? new LoggingJumpController(edge) : new StepUpController(edge);
                return continueLoggingJump(context);
            }
        }
        boolean descendingEdge = verifiedDescendingEdge(world, player, next);
        // A body's center can cross into the lower cell while its rear still
        // rests on the previous step. The integer feet cell then has no floor.
        // Revalidate only that already planned edge, never a guessed new fall.
        if (!world.canStand(walkingFeet) && !descendingEdge)
            return blocked(actions, "현재 발밑의 안전한 경로를 확인할 수 없습니다.");
        if (!walkingFeet.equals(next) && walkingFeet.distanceSquared(next) <= 2
            && !canTraverse(walkingFeet,next,world,domain) && !descendingEdge) return replan(context,Failure.OBSTACLE,"이동 경로가 막혔습니다.");
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
        if (world.tick() - progressTick > 60) return replan(context,Failure.STALLED,"이동이 3초 동안 진행되지 않았습니다.");
        double dx = next.x() + 0.5 - player.x(), dz = next.z() + 0.5 - player.z();
        float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
        boolean flat = next.y() == walkingFeet.y();
        previousMoving = true;
        previousSprint = sprint && flat && distance > 0.55;
        diagnostic="FOLLOWING";
        lastTravelTick=world.tick();
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
            if (interactionReady(world,target,reach)) {
                clearEndpointSettle(); rejectedEndpoints.clear(); path=List.of(); failure="";
                return Result.ARRIVED;
            }
            rejectedEndpoints.add(endpoint); clearEndpointSettle(); path=List.of();
            lastDistance=Double.POSITIVE_INFINITY; progressTick=world.tick();
            if (rejectedEndpoints.size()>=MAX_REJECTED_ENDPOINTS)
                return blocked(actions,Failure.REACH,"정지 후에도 확인한 접근 위치 4곳에서 목표가 보이지 않거나 손이 닿지 않습니다.");
            return Result.MOVING; // Replan next tick; this tick remains stopped.
        }
        if (world.tick()-endpointSettleTick>=ENDPOINT_SETTLE_TIMEOUT_TICKS)
            return blocked(actions,Failure.REACH,"접근 위치에서 안전하게 정지하지 못했습니다.");
        return Result.MOVING;
    }

    private void clearEndpointSettle() {
        endpointSettleTick=-1; endpointSampleTick=0; endpointQuietTicks=0; endpointSample=null;
    }

    private boolean interactionReady(WorldAccess world,Pos target,double reach) {
        if (!world.loaded(target)) return false;
        if (goal!=TerrainPathSearch.Goal.INTERACTION) {
            PlayerState p=world.player();Pos feet=NavigationFeet.resolve(world,p);
            if (!p.onGround() || !TerrainPathSearch.loadedStance(world,feet) || !world.canStand(feet)) return false;
            if (goal==TerrainPathSearch.Goal.OBSERVE) return p.distance(target)<=reach;
            return TerrainPathSearch.loadedStance(world,target) && world.canStand(target) && standingNear(world,p,target,reach);
        }
        return plantingTargets.isEmpty() ? world.canInteract(target,reach)
            : plantingTargets.stream().allMatch(p -> world.canPlantLoggingSapling(p,reach));
    }

    /** Null means a complete safe path was obtained; no other return may issue movement. */
    private Result planTerrain(Context c,Pos walkingFeet) {
        ActionPort actions=c.actions();WorldAccess world=c.world();actions.stopMovement();previousMoving=false;
        if (frontierWait>=0) {
            if (frontier!=null && world.loaded(frontier.crossing())) {
                frontier=null;frontierWait=-1;search=null;progressTick=world.tick();
                rejectedFrontiers.removeIf(f -> !f.domainEdge() && world.loaded(f.crossing()));
            } else {
                diagnostic="WAITING_CHUNKS";
                if (world.tick()-frontierWait<100) return Result.MOVING;
                if (frontier!=null) rejectedFrontiers.add(frontier);
                frontier=null;frontierWait=-1;search=null;
                if (++frontierAttempts>=3) return blocked(actions,Failure.UNLOADED,"안전한 경계에서 청크 로드를 기다렸지만 진전이 없습니다. 잠시 뒤 다시 시도합니다.");
                return Result.MOVING;
            }
        }
        if (requestNodes>=TerrainPathSearch.MAX_VISITED)
            return blocked(actions,Failure.SEARCH_LIMIT,"지형 탐색의 전체 노드 예산에 도달했습니다. 이동을 멈춥니다.");
        if (searchBudgetTick!=world.tick()) { searchBudgetTick=world.tick();sliceNodes=0;sliceLos=0;sliceNanos=0; }
        diagnostic="SEARCHING";
        if (sliceNodes>=TerrainPathSearch.NODES_PER_TICK || sliceLos>=TerrainPathSearch.LOS_PER_TICK || sliceNanos>=TerrainPathSearch.SLICE_NANOS)
            return Result.MOVING;
        long began=System.nanoTime();
        if (search==null) search=new TerrainPathSearch(walkingFeet,destination,destinationReach,world,c.profile(),domain,
            rejectedEndpoints,rejectedFrontiers,loggingPath,goal,plantingTargets,domain.terrain(),requestInteractions,interruptedJumps);
        long remaining=TerrainPathSearch.SLICE_NANOS-sliceNanos-(System.nanoTime()-began);
        TerrainPathSearch.Status state=search.status();
        if (remaining>0 && state==TerrainPathSearch.Status.SEARCHING) {
            state=search.advance(world,Math.min(TerrainPathSearch.NODES_PER_TICK-sliceNodes,TerrainPathSearch.MAX_VISITED-requestNodes),
                TerrainPathSearch.LOS_PER_TICK-sliceLos,remaining);
            sliceNodes+=search.lastExpanded();sliceLos+=search.lastLosChecks();requestNodes+=search.lastExpanded();
        }
        sliceNanos+=System.nanoTime()-began;
        if (state==TerrainPathSearch.Status.SEARCHING) return Result.MOVING;
        if (state==TerrainPathSearch.Status.INVALID_START) return blocked(actions,Failure.INVALID_START,"지형 탐색 출발점의 안전한 바닥을 확인할 수 없습니다.");
        if (state==TerrainPathSearch.Status.NO_PATH) return blocked(actions,Failure.NO_PATH,"현재 로드된 안전 지형에서 목적지로 가는 경로를 찾지 못했습니다.");
        if (state==TerrainPathSearch.Status.SEARCH_LIMIT) return blocked(actions,Failure.SEARCH_LIMIT,"지형 탐색 예산 안에서 안전한 경로를 찾지 못했습니다.");
        path=search.path();frontier=state==TerrainPathSearch.Status.FRONTIER ? search.frontier() : null;
        search=null;diagnostic=frontier==null ? "FOLLOWING" : "FRONTIER";
        if (path.isEmpty()) return blocked(actions,Failure.NO_PATH,"안전한 보행 경로가 비어 있습니다.");
        return null;
    }

    private Result arriveFrontier(Context c,Pos walkingFeet) {
        c.actions().stopMovement();previousMoving=false;path=List.of();search=null;
        if (frontier.domainEdge()) {
            domain=new TravelDomain(c.profile(),walkingFeet,destination);frontier=null;frontierWait=-1;
            diagnostic="SEARCHING";progressTick=c.world().tick();
        } else { frontierWait=c.world().tick();diagnostic="WAITING_CHUNKS"; }
        return Result.MOVING;
    }

    private Result replan(Context c,Failure kind,String message) {
        c.actions().stopMovement();previousMoving=false;
        if (domain==null || !domain.terrain() || replans>=3) return blocked(c.actions(),kind,message);
        replans++;path=List.of();search=null;frontier=null;frontierWait=-1;clearEndpointSettle();
        lastDistance=Double.POSITIVE_INFINITY;progressTick=c.world().tick();diagnostic="REPLANNING";
        return Result.MOVING;
    }

    private static boolean standingNear(WorldAccess world,PlayerState player,Pos feet,double reach) {
        double height=world.standingY(feet);
        if (!player.onGround() || !Double.isFinite(height)) return false;
        return Math.pow(player.x()-feet.x()-.5,2)+Math.pow(player.z()-feet.z()-.5,2)+Math.pow(player.y()-height,2)<=reach*reach;
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

    private static boolean canTraverse(Pos from,Pos to,WorldAccess world,TravelDomain domain) {
        if (from.y()==to.y() && Math.abs(from.x()-to.x())==1 && Math.abs(from.z()-to.z())==1)
            return DiagonalTraversal.canTraverse(from,to,world,domain);
        return world.canTraverse(from,to);
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
        if (Math.abs(dx) + Math.abs(dz) != 1 || next.y() > from.y() || from.y()-next.y()>1) return false;
        double fromHeight = world.standingY(from), toHeight = world.standingY(next);
        if (!Double.isFinite(fromHeight) || !Double.isFinite(toHeight)
            || fromHeight - toHeight <= .10001 || fromHeight - toHeight > WalkingSurfaceRules.MAX_DESCENT_HEIGHT+1.0e-5
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

    private Result blocked(ActionPort actions, String reason) {
        return blocked(actions,Failure.SAFETY,reason);
    }
    private Result blocked(ActionPort actions,Failure kind,String reason) {
        java.util.Map<String,Object> evidence=new java.util.LinkedHashMap<>();
        evidence.put("failure",kind.name());evidence.put("reason",reason);evidence.put("destination",destination);
        evidence.put("nextIndex",nextIndex);
        int start=Math.max(0,nextIndex-2),end=Math.min(path.size(),start+5);
        evidence.put("nearbyPath",start<=end ? List.copyOf(path.subList(start,end)) : List.of());
        if (lastWorld!=null) {
            evidence.put("tick",lastWorld.tick());PlayerState p=lastWorld.player();
            if (p!=null) evidence.put("player",java.util.Map.of("x",p.x(),"y",p.y(),"z",p.z(),"onGround",p.onGround()));
        }
        lastFailure=java.util.Collections.unmodifiableMap(evidence);
        cancelDescent();
        cancelLoggingJump();
        actions.stopMovement();
        previousMoving = false;
        failure = reason;
        failureKind=kind;diagnostic="BLOCKED_"+kind.name();search=null;
        path = List.of();
        clearEndpointSettle();
        return Result.BLOCKED;
    }

    @Override public void reset() {
        cancelDescent();
        cancelLoggingJump();
        if (lastActions!=null) lastActions.stopMovement();
        path = List.of();
        destination = null;
        plantingTargets=List.of();
        doorTicket = -1;
        lastDistance = Double.POSITIVE_INFINITY;
        progressTick = 0;
        nextIndex = 0;
        previousMoving = false;
        lastTravelTick=Long.MIN_VALUE;
        failure = "";
        rejectedEndpoints.clear(); clearEndpointSettle();
        domain=null;requestProfile=null;requestSession=null;requestMode=null;requestInteractions=false;goal=TerrainPathSearch.Goal.INTERACTION;
        search=null;frontier=null;frontierWait=-1;rejectedFrontiers.clear();frontierAttempts=0;replans=0;requestNodes=0;
        failureKind=Failure.NONE;diagnostic="IDLE";
    }

    private Result continueLoggingJump(Context context) {
        LoggingJumpController.Phase priorPhase=loggingJump.phase();
        Result result=loggingJump.tick(context);
        previousMoving=result==Result.MOVING;
        previousSprint=false;
        if (result==Result.BLOCKED) {
            rememberAscentFailure(priorPhase,loggingJump.failureReason());
            return blocked(context.actions(),Failure.JUMP_UNCERTAIN,loggingJump.failureReason());
        }
        if (result==Result.ARRIVED) {
            loggingJump=null;
            if (settledAtFrontier(context)) return arriveFrontier(context,frontier.standing());
            // The final approach may require a center tighter than the landing
            // margin. Replan FROM the actual landing, never redispatch this edge.
            path=List.of(); nextIndex=0;
            lastDistance=Double.POSITIVE_INFINITY; progressTick=context.world().tick();
            previousMoving=false;
        }
        // Landing only completes this edge, not an interaction in the same tick.
        return Result.MOVING;
    }

    private Result continueDescent(Context context) {
        diagnostic="DESCENT_"+descent.phase().name();
        int completed=descent.completedEdges();
        Result result=descent.tick(context,descentPreview(context));previousMoving=result==Result.MOVING;previousSprint=false;
        if (descent.completedEdges()!=completed) {
            // Only the controller's actually grounded landing advances this
            // finite path. A preview, camera turn or airborne proximity cannot.
            nextIndex+=descent.completedEdges()-completed;lastDistance=Double.POSITIVE_INFINITY;progressTick=context.world().tick();
        }
        if (result==Result.BLOCKED) return blocked(context.actions(),descent.failureReason());
        if (result==Result.ARRIVED) {
            descent=null;
            if (settledAtFrontier(context)) return arriveFrontier(context,frontier.standing());
            path=List.of();search=null;nextIndex=0;lastDistance=Double.POSITIVE_INFINITY;
            progressTick=context.world().tick();previousMoving=false;
        }
        return Result.MOVING;
    }
    /** Called only after a controller observed its completed, quiet landing. */
    private boolean settledAtFrontier(Context context) {
        if (frontier==null || path.isEmpty() || nextIndex!=path.size()-1
            || !path.get(nextIndex).equals(frontier.standing())) return false;
        WorldAccess world=context.world();Pos feet=NavigationFeet.resolve(world,world.player());
        return feet.equals(frontier.standing()) && TerrainPathSearch.loadedStance(world,feet)
            && world.canStand(feet) && standingNear(world,world.player(),feet,.15);
    }
    private List<Pos> descentPreview(Context context) {
        if (!requestInteractions || domain==null || nextIndex<=0 || nextIndex>=path.size()) return List.of();
        int start=nextIndex-1,end=nextIndex+1;
        Pos a=path.get(start),b=path.get(nextIndex);
        if (!domain.contains(a) || !domain.contains(b)) return List.of();
        int dx=b.x()-a.x(),dz=b.z()-a.z();
        while(end<path.size() && end-start<4) {
            Pos prior=path.get(end-1),next=path.get(end);
            if (!domain.contains(next) || next.x()-prior.x()!=dx || next.z()-prior.z()!=dz
                || !DescentController.descending(prior,next,context.world())) break;
            end++;
        }
        return List.copyOf(path.subList(start,end));
    }
    private void cancelDescent() {
        if (descent==null) return;
        if (descent.airborne()) interruptedDescent=true;
        descent.cancel();descent=null;
    }

    private void cancelLoggingJump() {
        if (loggingJump==null) return;
        LoggingJumpController.Phase priorPhase=loggingJump.phase();
        if (loggingJump.attempted() && loggingJump.phase()!=LoggingJumpController.Phase.COMPLETE) {
            interruptedJumps.add(loggingJump.edge());
            interruptedLanding=true;
        }
        loggingJump.cancel();
        if (priorPhase!=LoggingJumpController.Phase.FAILED && priorPhase!=LoggingJumpController.Phase.COMPLETE)
            rememberAscentFailure(priorPhase,loggingJump.failureReason());
        loggingJump=null;
    }
    private void rememberAscentFailure(LoggingJumpController.Phase priorPhase,String reason) {
        java.util.Map<String,Object> evidence=new java.util.LinkedHashMap<>();
        evidence.put("reason",reason.length()>512 ? reason.substring(0,512) : reason);
        evidence.put("phase",priorPhase.name());evidence.put("edge",loggingJump.edge());
        evidence.put("attempted",loggingJump.attempted());
        evidence.put("authority",loggingJump instanceof StepUpController ? "TRANSIT" : "LOGGING");
        if (lastWorld!=null) evidence.put("tick",lastWorld.tick());
        lastAscentFailure=java.util.Collections.unmodifiableMap(evidence);
    }
}
