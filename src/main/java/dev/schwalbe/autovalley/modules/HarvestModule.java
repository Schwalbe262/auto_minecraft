package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.LocalNavigator;
import dev.schwalbe.autovalley.navigation.HarvestRoutePlanner;
import java.util.*;

/** One registered-crop harvest engine, confirmed by native acknowledgement and observed crop state. */
public final class HarvestModule implements AutomationModule {
    private enum Stage { PREPARE, APPROACH, USING, PICKUP }
    private Stage stage = Stage.PREPARE;
    private final Deque<Pos> pending = new ArrayDeque<>();
    private final Map<Pos,Integer> primaryLanes = new HashMap<>();
    private final Set<String> harvestedThisPass = new HashSet<>();
    private final Set<String> completedFields = new HashSet<>();
    private Farm observingFarm;
    private CropDefinition observingCrop;
    private final ModuleSupport.ObservationWindow observationWindow=new ModuleSupport.ObservationWindow();
    private Pos target;
    private Pos continuation;
    private boolean continuationMoving;
    private boolean continuationStopped;
    private int harvestRadius;
    private long useStarted;
    private long ticket = -1;
    private long cooldownUntil;
    private long cooldownDay = Long.MIN_VALUE;
    private long pickupStarted;
    private long sampleStarted;
    private String unresolvedOutput;
    private int useAttempts;
    private int sweep;
    private boolean active;
    private boolean routeOrdered;
    private boolean calibration;
    private Profile calibrationProfile;
    private boolean sampleSprint;
    private double sampleMovementStarted;
    private final Sample walking = new Sample();
    private final Sample sprinting = new Sample();
    private String calibrationStatus = "아직 빠른 수확을 측정하지 않았습니다.";

    private static final class Sample {
        int attempted, missed;
        long ticks;
        double distance;
        void clear() { attempted = 0; missed = 0; ticks = 0; distance = 0; }
        double average() { return attempted == 0 ? Double.POSITIVE_INFINITY : (double)ticks / attempted; }
        double missRate() { return attempted == 0 ? 1 : (double)missed / attempted; }
    }

    public void startCalibration() {
        reset();
        cooldownUntil = 0;
        calibration = true;
        calibrationProfile = null;
        walking.clear();
        sprinting.clear();
        calibrationStatus = "작은 구간을 걷기 4회, 달리기 4회로 측정합니다.";
    }

    public String calibrationStatus() { return calibrationStatus; }
    public boolean calibrating() { return calibration; }
    public void cancelCalibration() {
        calibration = false;
        calibrationProfile = null;
        walking.clear();
        sprinting.clear();
        calibrationStatus = "수확 속도 측정이 취소되었습니다.";
    }
    @Override public Feature feature() { return Feature.HARVEST; }
    @Override public int priority() { return 50; }

    @Override public WorkResult tick(Context context) {
        WorldAccess world = context.world();
        ActionPort actions = context.actions();
        Profile profile = context.profile();
        if (unresolvedOutput != null) return WorkResult.blocked(unresolvedOutput);
        if (calibration && calibrationProfile != null && calibrationProfile != profile) cancelCalibration();
        if (calibration) calibrationProfile = profile;
        // Dew Drop performs daily growth around day tick 5..14; inspect after that morning update.
        if (!active && observingFarm==null && completedFields.isEmpty() && Math.floorMod(world.dayTime(),24000L) < 20) return WorkResult.idle();
        if (!active && observingFarm==null && completedFields.isEmpty() && world.tick() < cooldownUntil && gameDay(world) == cooldownDay) return WorkResult.idle();
        if (profile.farms.isEmpty()) return fail(context, "수확할 밭의 작물과 두 모서리를 먼저 등록하세요.");
        if (world.menu() != null && (world.menu().container() || !world.menu().carried().empty()))
            return fail(context, "수확을 시작하려면 열린 상자와 커서의 아이템을 정리하세요.");
        if(observingFarm!=null && (!profile.farms.contains(observingFarm)
                || observingCrop!=null && !observingCrop.equals(CropRules.definition(profile,observingFarm))))
            return fail(context,"수확 중 밭 또는 작물 정의가 바뀌었습니다. 등록 내용을 다시 확인하세요.");
        if (!active) {
            if (observingFarm==null) observingFarm=profile.farms.stream()
                .filter(f -> !completedFields.contains(farmKey(f)) && profile.nextEligibleDay.getOrDefault(farmKey(f),Long.MIN_VALUE)<=gameDay(world))
                .min(Comparator.comparingDouble(f -> world.player().distance(f.first()))).orElse(null);
            if (observingFarm==null) return finish(context);
            observingCrop=CropRules.definition(profile,observingFarm);
            if(observingCrop==null)return fail(context,"등록된 밭의 작물 정의가 없거나 올바르지 않습니다: "+observingFarm.cropId());
            WorkResult observing=observeFarm(context);
            if (observing!=null) return observing;
            String error = scan(context);
            if (error != null) return fail(context, error);
            observationWindow.clear();
            if (pending.isEmpty()) return finishField(context);
            active = true;
            stage = Stage.PREPARE;
        }
        if (stage == Stage.PREPARE) {
            if (ticket >= 0) {
                ActionOutcome outcome = actions.outcome(ticket);
                if (!outcome.done()) return WorkResult.busy("수확용 괭이를 준비하고 있습니다.");
                ticket = -1;
                if (!outcome.success()) return fail(context, "괭이를 준비하지 못했습니다: " + outcome.message());
            }
            int hotbar = profile.hoeHotbarSlot;
            if (hotbar < 0 || hotbar > 8) return fail(context, "괭이 단축바 슬롯은 1~9여야 합니다.");
            ItemSlot held = inventorySlot(world, hotbar);
            if (held == null || !usableHoe(held.item())) {
                ItemSlot hoe = world.inventory().stream().filter(s -> usableHoe(s.item())).findFirst().orElse(null);
                if (hoe == null) return fail(context, "사용 가능한 괭이가 없습니다. 내구도가 1 이하이면 수확하지 않습니다.");
                if (actions.busy()) return WorkResult.busy("이전 조작을 기다립니다.");
                ticket = actions.submit(new Action.SwapHotbar(hoe.inventoryIndex(), hotbar));
                return WorkResult.busy("괭이를 지정한 단축바 슬롯으로 옮깁니다.");
            }
            if (world.player().selectedSlot() != hotbar) {
                if (actions.busy()) return WorkResult.busy("이전 조작을 기다립니다.");
                ticket = actions.submit(new Action.SelectHotbar(hotbar));
                return WorkResult.busy("괭이를 듭니다.");
            }
            if (!routeOrdered) {
                orderPending(context);
                routeOrdered = true;
            }
            stage = Stage.APPROACH;
        }
        if (stage == Stage.APPROACH) {
            if (target == null) {
                while (!pending.isEmpty()) {
                    Pos candidate = pending.peekFirst();
                    if (!world.loaded(candidate)) return ModuleSupport.observe(context,candidate,8,"수확 대상 청크 확인");
                    pending.removeFirst();
                    if (isMature(context, candidate)) { target = candidate; break; }
                }
                if (target == null) {
                    if (sweep++ == 0) {
                        WorkResult observing=observeFarm(context);
                        if (observing!=null) { sweep--; return observing; }
                        String error = scan(context);
                        if (error != null) return fail(context, error);
                        orderPending(context);
                        if (!pending.isEmpty()) return WorkResult.busy("남아 있는 익은 작물을 다시 확인합니다.");
                    }
                    return finishField(context);
                }
                sampleStarted = world.tick();
                sampleSprint = calibration ? walking.attempted >= 4 : profile.sprintCalibrated && profile.sprintHarvest;
                useAttempts = 0;
                if (context.navigation() instanceof LocalNavigator navigator) {
                    sampleMovementStarted = navigator.measuredDistance(sampleSprint,world);
                    navigator.setSprint(sampleSprint);
                }
            }
            if (!profile.continueHarvestWhenFull && !hasHarvestRoom(world.inventory(),observingCrop))
                return fail(context, "선택한 작물의 수확물을 담을 공간이 부족합니다.");
            if (!world.loaded(target)) return ModuleSupport.observe(context,target,8,"수확 대상 청크 확인");
            if (!isMature(context, target)) { target = null; return WorkResult.busy("이미 수확된 작물을 건너뜁니다."); }
            ItemSlot held = inventorySlot(world, profile.hoeHotbarSlot);
            if (world.player().selectedSlot() != profile.hoeHotbarSlot || held == null || !usableHoe(held.item()))
                return fail(context, "주 손의 괭이 또는 내구도가 바뀌었습니다.");
            Navigation.Result arrival = context.navigation().moveTo(target, calibration ? 1.6 : 2.15, context);
            if (arrival == Navigation.Result.BLOCKED) return ModuleSupport.navigationResult(context,navigationFailure(context));
            if (arrival == Navigation.Result.MOVING) return WorkResult.busy(sampleSprint ? "달리며 익은 작물에 접근합니다." : "익은 작물에 접근합니다.");
            if (actions.busy()) return WorkResult.busy("이전 조작을 기다립니다.");
            String footprintRejection = HarvestSafety.rejection(context,target);
            if (footprintRejection != null) return fail(context,footprintRejection);
            harvestRadius = world.harvestFootprint(target).radius();
            ticket = actions.submit(new Action.UseBlock(target, Action.Use.HARVEST));
            useAttempts++;
            stage = Stage.USING;
            useStarted = world.tick();
            continuation = null;
            continuationStopped = false;
            continuationMoving = false;
            // The use has already been dispatched, so no lookahead can steal
            // its slot with a door click. Native aiming remains on this crop.
            if (!actions.outcome(ticket).done()) continueHarvestMovement(context);
            return WorkResult.busy("괭이 우클릭 수확 결과를 기다립니다.");
        }
        if (stage == Stage.USING) {
            ActionOutcome outcome = actions.outcome(ticket);
            if (!outcome.done()) {
                continueHarvestMovement(context);
                return WorkResult.busy("작물 성장 상태 변경을 기다립니다.");
            }
            if(!world.loaded(target)) {stopContinuation(context);return ModuleSupport.observe(context,target,8,"수확한 작물 상태 확인");}
            ticket = -1;
            if (!outcome.success() || isMature(context, target)) {
                if (useAttempts < 2 && isMature(context, target)) {
                    stopContinuation(context);
                    stage = Stage.APPROACH;
                    return WorkResult.busy("우클릭 수확을 한 번 다시 확인합니다.");
                }
                recordSample(context, true);
                if (!isMature(context,target)) unresolvedOutput = "작물은 바뀌었지만 수확 응답이 확인되지 않았습니다. 수확물을 확인한 뒤 F8로 다시 시작하세요.";
                return fail(context, unresolvedOutput != null ? unresolvedOutput : "우클릭 수확이 확인되지 않았습니다: " + outcome.message());
            }
            pickupStarted = world.tick();
            stage = Stage.PICKUP;
        }
        if (stage == Stage.PICKUP) {
            // Output pickup is the player's concern. The successful native
            // action and mature-to-harvested transition above are the completion evidence.
            Pos repair = localRepair(context);
            // A successful use confirms the clicked crop, not every neighbour or
            // the conditional upper-vine fallback. Do not walk away from observed
            // leftovers while the existing short block-update settle completes.
            if (repair != null) stopContinuation(context);
            else if (profile.continueHarvestWhenFull) continueHarvestMovement(context);
            if (world.tick() - pickupStarted < 2) return WorkResult.busy("확인된 수확 동작을 마무리합니다.");
            if (repair != null) {
                pending.remove(repair);
                pending.addFirst(repair);
            }
            recordSample(context, false);
            markCompletedFarm(context,target);
            if (!continuationMoving) {
                actions.stopMovement();
                context.navigation().reset();
            }
            target = null;
            stage = Stage.APPROACH;
            return WorkResult.busy("작물의 수확 상태를 확인했습니다.");
        }
        return WorkResult.busy("수확 준비 중입니다.");
    }

    /** Observe nearby misses before leaving this footprint, without predicting completion. */
    private Pos localRepair(Context context) {
        if (calibration || harvestRadius <= 0 || target == null) return null;
        WorldAccess world = context.world();
        List<Pos> nearby = pending.stream()
            .filter(p -> HarvestRoutePlanner.withinFootprint(target,p,harvestRadius))
            .filter(world::loaded).filter(p -> isMature(context,p)).toList();
        Pos best = null;
        int bestCoverage = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        // Prefer one nearby center covering many leftovers, rather than walking
        // through each crop. In particular an unchanged upper layer gets its own
        // area click before traversing the rest of the field.
        for (Pos candidate : nearby) {
            int coverage = 0;
            for (Pos crop : nearby)
                if (HarvestRoutePlanner.withinFootprint(candidate,crop,harvestRadius)) coverage++;
            double distance = world.player().distance(candidate);
            if (coverage > bestCoverage || coverage == bestCoverage && distance < bestDistance) {
                best = candidate;
                bestCoverage = coverage;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void continueHarvestMovement(Context context) {
        WorldAccess world = context.world();
        if (calibration || !context.profile().continueHarvestWhenFull || !context.actions().supportsMovingHarvest()
            || world.tick()-useStarted>=10 || continuationStopped) {
            stopContinuation(context);
            return;
        }
        // Client-side crop prediction may precede the server reply. Look past
        // currently changed cells for steering only; never remove them here.
        // APPROACH rechecks after the current use has actually been confirmed.
        Pos next=null;
        for (Pos candidate:pending) {
            if (!world.loaded(candidate) || isMature(context,candidate)) { next=candidate; break; }
        }
        Integer lane=primaryLanes.get(target);
        boolean sameLane=lane!=null && next!=null && lane.equals(primaryLanes.get(next));
        if (next==null || !sameLane || !world.loaded(next)
                || HarvestRoutePlanner.withinFootprint(target,next,harvestRadius)
                || Math.hypot((long)next.x()-target.x(),(long)next.z()-target.z())>harvestRadius*2+2) {
            stopContinuation(context);
            return;
        }
        continuation=next;
        Navigation.Result result = context.navigation().moveToWithoutInteraction(continuation,2.15,context);
        continuationMoving = result == Navigation.Result.MOVING;
        if (result != Navigation.Result.MOVING) stopContinuation(context);
    }

    private void stopContinuation(Context context) {
        context.actions().stopMovement();
        continuationMoving = false;
        continuationStopped = true;
    }

    private String scan(Context context) {
        pending.clear();
        Set<Pos> seen = new HashSet<>();
        for (Farm farm : observingFarm==null ? List.<Farm>of() : List.of(observingFarm)) {
            if (context.profile().nextEligibleDay.getOrDefault(farmKey(farm),Long.MIN_VALUE) > gameDay(context.world())) continue;
            if (farm.volume() > 32768) return "밭 하나의 등록 범위는 32768블록 이하여야 합니다.";
            int matureCount = 0;
            int minX = Math.min(farm.first().x(), farm.second().x()), maxX = Math.max(farm.first().x(), farm.second().x());
            int minY = Math.min(farm.first().y(), farm.second().y()), maxY = Math.max(farm.first().y(), farm.second().y());
            int minZ = Math.min(farm.first().z(), farm.second().z()), maxZ = Math.max(farm.first().z(), farm.second().z());
            for (int z = minZ; z <= maxZ; z++) {
                boolean reverse = (z - minZ) % 2 == 1;
                for (int xi = minX; xi <= maxX; xi++) {
                    int x = reverse ? maxX - (xi - minX) : xi;
                    for (int y = minY; y <= maxY; y++) {
                        Pos p = new Pos(x,y,z);
                        if (!context.world().loaded(p)) return "밭 일부가 로드되지 않았습니다. 밭 근처에서 다시 시작하세요.";
                        if(CropRules.matches(observingCrop,context.world().block(p))
                                && !observingCrop.equals(CropRules.registeredCrop(context.profile(),p)))
                            return "서로 다른 작물의 밭 등록이 겹칩니다. 수확 범위를 다시 확인하세요.";
                        if (isMature(context, p)) {
                            matureCount++;
                            if (seen.add(p)) pending.addLast(p);
                        }
                    }
                }
            }
            if (matureCount == 0) context.profile().nextEligibleDay.put(farmKey(farm),gameDay(context.world())
                + (harvestedThisPass.contains(farmKey(farm)) ? CropRules.cycleDays(context.profile(),farm) : 1));
        }
        return null;
    }

    private WorkResult observeFarm(Context c) {
        Farm farm=observingFarm;
        if (farm==null) return null;
        if (farm.volume()>32768) return WorkResult.blocked("밭 하나의 등록 범위는 32768블록 이하여야 합니다.");
        for(int x=Math.min(farm.first().x(),farm.second().x());x<=Math.max(farm.first().x(),farm.second().x());x++)
            for(int y=Math.min(farm.first().y(),farm.second().y());y<=Math.max(farm.first().y(),farm.second().y());y++)
                for(int z=Math.min(farm.first().z(),farm.second().z());z<=Math.max(farm.first().z(),farm.second().z());z++) {
                    Pos cell=new Pos(x,y,z);
                    if (!c.world().loaded(cell)) return observationWindow.observe(c,cell,8,"밭 "+farm.name()+" 관측");
                }
        return null;
    }

    private WorkResult finishField(Context c) {
        completedFields.add(farmKey(observingFarm)); observingFarm=null; observingCrop=null;
        observationWindow.clear();
        active=false; target=null; routeOrdered=false; sweep=0; stage=Stage.PREPARE;
        pending.clear(); primaryLanes.clear(); c.actions().stopMovement(); c.navigation().reset();
        if (c.profile().farms.stream().noneMatch(f -> !completedFields.contains(farmKey(f))
            && c.profile().nextEligibleDay.getOrDefault(farmKey(f),Long.MIN_VALUE)<=gameDay(c.world()))) return finish(c);
        return WorkResult.busy("확인한 밭을 마치고 다음 작업장 확인");
    }

    private void orderPending(Context context) {
        // Calibration compares the existing short approaches, not two different
        // coverage routes; normal harvest uses the now-selected native hoe hint.
        primaryLanes.clear();
        if (calibration || pending.isEmpty()) return;
        HarvestFootprint footprint = context.world().harvestFootprint(pending.getFirst());
        if (footprint == null || !footprint.known() || footprint.radius() <= 0) return;
        LinkedHashSet<Pos> remaining = new LinkedHashSet<>(pending);
        pending.clear();
        int nextLane=0;
        for (Farm farm:context.profile().farms) {
            CropDefinition crop=CropRules.definition(context.profile(),farm);
            if(crop==null || !crop.equals(observingCrop))continue;
            List<Pos> sector = remaining.stream().filter(farm::contains).toList();
            if (sector.isEmpty()) continue;
            List<Pos> layout=new ArrayList<>();
            for (int x=Math.min(farm.first().x(),farm.second().x());x<=Math.max(farm.first().x(),farm.second().x());x++)
                for (int y=Math.min(farm.first().y(),farm.second().y());y<=Math.max(farm.first().y(),farm.second().y());y++)
                    for (int z=Math.min(farm.first().z(),farm.second().z());z<=Math.max(farm.first().z(),farm.second().z());z++) {
                        Pos position=new Pos(x,y,z);
                        if (!context.world().loaded(position)) continue;
                        BlockData block=context.world().block(position);
                        if (CropRules.matches(crop,block)) layout.add(position);
                    }
            HarvestRoutePlanner.Route route=HarvestRoutePlanner.plan(sector,layout,footprint.radius());
            Map<String,Integer> lanes=new HashMap<>();
            for (Pos position:route.primary()) {
                String row=position.y()+":"+(route.alongX() ? position.z() : position.x());
                Integer lane=lanes.get(row);
                if (lane==null) { lane=nextLane++; lanes.put(row,lane); }
                primaryLanes.put(position,lane);
            }
            pending.addAll(route.ordered());
            remaining.removeAll(sector);
        }
        pending.addAll(remaining);
    }

    private void markCompletedFarm(Context context, Pos harvested) {
        for (Farm farm : context.profile().farms) {
            if (!farm.contains(harvested) || farm.volume() > 32768
                    || !Objects.equals(observingCrop,CropRules.definition(context.profile(),farm))) continue;
            harvestedThisPass.add(farmKey(farm));
            if (pending.stream().anyMatch(farm::contains)) continue;
            boolean remaining = false;
            for (int x = Math.min(farm.first().x(),farm.second().x()); x <= Math.max(farm.first().x(),farm.second().x()) && !remaining; x++) {
                for (int y = Math.min(farm.first().y(),farm.second().y()); y <= Math.max(farm.first().y(),farm.second().y()) && !remaining; y++) {
                    for (int z = Math.min(farm.first().z(),farm.second().z()); z <= Math.max(farm.first().z(),farm.second().z()); z++) {
                        Pos p = new Pos(x,y,z);
                        if (!context.world().loaded(p) || isMature(context,p)) { remaining = true; break; }
                    }
                }
            }
            if (!remaining) context.profile().nextEligibleDay.put(farmKey(farm),gameDay(context.world()) + CropRules.cycleDays(context.profile(),farm));
        }
    }

    private static String farmKey(Farm farm) { return CropRules.farmKey(farm); }
    private static long gameDay(WorldAccess world) { return Math.floorDiv(world.dayTime(),24000L); }

    /** Reserve capacity for a normal quality roll and rotten output before every click. */
    public static boolean hasHarvestRoom(List<ItemSlot> inventory) {
        return hasHarvestRoom(inventory,CropRules.defaults().get(CropRules.TOMATO));
    }
    public static boolean hasHarvestRoom(List<ItemSlot> inventory,CropDefinition crop) {
        if(!CropRules.valid(crop))return false;
        int empty = 0, rottenSpace = 0;
        int[] productSpace = new int[4];
        for (ItemSlot slot : inventory) {
            if (!slot.player() || slot.inventoryIndex() < 0 || slot.inventoryIndex() > 35) continue;
            ItemData item = slot.item();
            if (item.empty()) empty++;
            else if (item.is(ItemData.ROTTEN)) rottenSpace += Math.max(0, 64 - item.count());
            else if (item.is(crop.itemId()) && item.quality() >= 0 && item.quality() < 4)
                productSpace[item.quality()] += Math.max(0, 64 - item.count());
        }
        boolean everyQualityFits = Arrays.stream(productSpace).allMatch(space -> space >= 4);
        // Only tomatoes have the existing proven rotten-tomato reserve. Other
        // crop definitions keep a spare slot for secondary native output rather
        // than borrowing capacity from an unrelated tomato/rotten stack.
        if(!ItemData.TOMATO.equals(crop.itemId()))return empty>=2 || everyQualityFits && empty>=1;
        return empty >= 2 || empty >= 1 && rottenSpace >= 4 || everyQualityFits && (empty >= 1 || rottenSpace >= 4);
    }

    private void recordSample(Context context, boolean missed) {
        if (!calibration) return;
        Sample sample = sampleSprint ? sprinting : walking;
        sample.attempted++;
        sample.ticks += Math.max(1, context.world().tick() - sampleStarted);
        if (context.navigation() instanceof LocalNavigator navigator)
            sample.distance += Math.max(0,navigator.measuredDistance(sampleSprint,context.world()) - sampleMovementStarted);
        if (missed) sample.missed++;
        calibrationStatus = "걷기 " + walking.attempted + "/4, 달리기 " + sprinting.attempted + "/4 측정 중";
        if (walking.attempted >= 4 && sprinting.attempted >= 4) {
            boolean comparableMovement = walking.distance >= 1 && sprinting.distance >= 1
                && sprinting.distance / walking.distance >= .5 && sprinting.distance / walking.distance <= 2;
            boolean enabled = comparableMovement && sprinting.average() < walking.average() * 0.95
                && walking.missed == 0 && sprinting.missRate() <= walking.missRate();
            context.profile().sprintCalibrated = true;
            context.profile().sprintHarvest = enabled;
            calibration = false;
            calibrationStatus = String.format(Locale.ROOT, "걷기 %.1f틱/개 %.1f블록 (누락 %d/4), 달리기 %.1f틱/개 %.1f블록 (누락 %d/4): %s",
                walking.average(), walking.distance, walking.missed, sprinting.average(), sprinting.distance,
                sprinting.missed, enabled ? "달리기 적용" : comparableMovement ? "걷기 유지" : "비교 가능한 이동 거리 부족: 걷기 유지");
        }
    }

    private WorkResult finish(Context context) {
        resetWork(context);
        cooldownUntil = context.world().tick() + Math.max(20,context.profile().harvestCheckTicks);
        cooldownDay = gameDay(context.world());
        return WorkResult.idle();
    }

    private WorkResult fail(Context context, String message) {
        resetWork(context);
        return WorkResult.blocked(message);
    }

    private void resetWork(Context context) {
        context.actions().stopMovement();
        context.navigation().reset();
        if (context.navigation() instanceof LocalNavigator navigator) navigator.setSprint(false);
        clearWork();
    }

    @Override public void reset() {
        unresolvedOutput = null;
        clearWork();
    }

    private void clearWork() {
        active = false;
        routeOrdered = false;
        pending.clear();
        primaryLanes.clear();
        harvestedThisPass.clear();
        completedFields.clear(); observingFarm=null; observingCrop=null;
        observationWindow.clear();
        target = null;
        continuation = null;
        continuationMoving = false;
        continuationStopped = false;
        harvestRadius = 0;
        ticket = -1;
        sweep = 0;
        useAttempts = 0;
        stage = Stage.PREPARE;
    }

    private static boolean usableHoe(ItemData item) { return item.hoe() && !item.empty() && item.durability() > 1; }
    private static ItemSlot inventorySlot(WorldAccess world, int index) { return world.inventory().stream().filter(s -> s.inventoryIndex() == index).findFirst().orElse(null); }
    private boolean isMature(Context context,Pos p) {
        return observingCrop!=null && observingCrop.equals(CropRules.registeredCrop(context.profile(),p))
            && CropRules.mature(observingCrop,context.world().block(p));
    }
    private static String navigationFailure(Context context) {
        return context.navigation() instanceof LocalNavigator navigator ? navigator.failureReason() : "익은 작물로 가는 경로가 막혔습니다.";
    }
}
