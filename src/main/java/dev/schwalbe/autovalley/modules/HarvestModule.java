package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.LocalNavigator;
import java.util.*;

/** Right-click-only tomato harvest, with verified inventory or magnet-carried ground output. */
public final class HarvestModule implements AutomationModule {
    private enum Stage { PREPARE, APPROACH, USING, PICKUP }
    private Stage stage = Stage.PREPARE;
    private final Deque<Pos> pending = new ArrayDeque<>();
    private final Set<String> harvestedThisPass = new HashSet<>();
    private Pos target;
    private long ticket = -1;
    private long cooldownUntil;
    private long cooldownDay = Long.MIN_VALUE;
    private long pickupStarted;
    private long sampleStarted;
    private int beforePickup;
    private int beforeGround;
    private Map<String,Integer> beforeObservedByItem = Map.of();
    private String unresolvedOutput;
    private int useAttempts;
    private int sweep;
    private boolean active;
    private boolean calibration;
    private Profile calibrationProfile;
    private boolean sampleSprint;
    private double sampleMovementStarted;
    private final Sample walking = new Sample();
    private final Sample sprinting = new Sample();
    private record HarvestObservation(Map<String,Integer> totals, int inventoryCount, int groundCount) { }
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
        if (!active && (context.session().magnetHaulPending || !context.session().magnetHaulRemaining.isEmpty()))
            return WorkResult.blocked("자석이 운반 중인 수확물을 먼저 저장해야 다음 수확을 시작할 수 있습니다.");
        // Dew Drop performs daily growth around day tick 5..14; inspect after that morning update.
        if (!active && Math.floorMod(world.dayTime(),24000L) < 20) return WorkResult.idle();
        if (!active && world.tick() < cooldownUntil && gameDay(world) == cooldownDay) return WorkResult.idle();
        if (profile.farms.isEmpty()) return fail(context, "토마토밭의 두 모서리를 먼저 등록하세요.");
        if (world.menu() != null && (world.menu().container() || !world.menu().carried().empty()))
            return fail(context, "수확을 시작하려면 열린 상자와 커서의 아이템을 정리하세요.");
        if (!active) {
            String error = scan(context);
            if (error != null) return fail(context, error);
            if (pending.isEmpty()) return finish(context);
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
            stage = Stage.APPROACH;
        }
        if (stage == Stage.APPROACH) {
            if (target == null) {
                while (!pending.isEmpty()) {
                    Pos candidate = pending.removeFirst();
                    if (isMature(world, candidate)) { target = candidate; break; }
                }
                if (target == null) {
                    if (sweep++ == 0) {
                        String error = scan(context);
                        if (error != null) return fail(context, error);
                        if (!pending.isEmpty()) return WorkResult.busy("남아 있는 익은 토마토를 다시 확인합니다.");
                    }
                    return finish(context);
                }
                sampleStarted = world.tick();
                sampleSprint = calibration ? walking.attempted >= 4 : profile.sprintCalibrated && profile.sprintHarvest;
                useAttempts = 0;
                if (context.navigation() instanceof LocalNavigator navigator) {
                    sampleMovementStarted = navigator.measuredDistance(sampleSprint,world);
                    navigator.setSprint(sampleSprint);
                }
            }
            if (!profile.magnetOverflowHarvest && !hasHarvestRoom(world.inventory()))
                return fail(context, "토마토와 썩은 토마토를 담을 공간이 부족합니다.");
            if (!isMature(world, target)) { target = null; return WorkResult.busy("이미 수확된 작물을 건너뜁니다."); }
            ItemSlot held = inventorySlot(world, profile.hoeHotbarSlot);
            if (world.player().selectedSlot() != profile.hoeHotbarSlot || held == null || !usableHoe(held.item()))
                return fail(context, "주 손의 괭이 또는 내구도가 바뀌었습니다.");
            Navigation.Result arrival = context.navigation().moveTo(target, calibration ? 1.6 : 2.15, context);
            if (arrival == Navigation.Result.BLOCKED) return fail(context, navigationFailure(context));
            if (arrival == Navigation.Result.MOVING) return WorkResult.busy(sampleSprint ? "달리며 익은 토마토에 접근합니다." : "익은 토마토에 접근합니다.");
            if (actions.busy()) return WorkResult.busy("이전 조작을 기다립니다.");
            beforePickup = harvestItems(world);
            beforeGround = 0;
            beforeObservedByItem = Map.of();
            if (profile.magnetOverflowHarvest) {
                HarvestObservation observation = observeHarvest(world);
                beforePickup = observation.inventoryCount();
                beforeGround = observation.groundCount();
                beforeObservedByItem = observation.totals();
            }
            ticket = actions.submit(new Action.UseBlock(target, Action.Use.HARVEST));
            useAttempts++;
            stage = Stage.USING;
            return WorkResult.busy("괭이 우클릭 수확 결과를 기다립니다.");
        }
        if (stage == Stage.USING) {
            ActionOutcome outcome = actions.outcome(ticket);
            if (!outcome.done()) return WorkResult.busy("토마토 성장 상태 변경을 기다립니다.");
            ticket = -1;
            if (!outcome.success() || isMature(world, target)) {
                if (useAttempts < 2 && isMature(world, target)) { stage = Stage.APPROACH; return WorkResult.busy("우클릭 수확을 한 번 다시 확인합니다."); }
                recordSample(context, true);
                if (!isMature(world,target)) unresolvedOutput = "작물은 바뀌었지만 수확 응답이 확인되지 않았습니다. 수확물을 확인한 뒤 F8로 다시 시작하세요.";
                return fail(context, unresolvedOutput != null ? unresolvedOutput : "우클릭 수확이 확인되지 않았습니다: " + outcome.message());
            }
            pickupStarted = world.tick();
            stage = Stage.PICKUP;
        }
        if (stage == Stage.PICKUP) {
            HarvestObservation observation = profile.magnetOverflowHarvest ? observeHarvest(world) : null;
            int inventoryNow = observation == null ? harvestItems(world) : observation.inventoryCount();
            int groundNow = observation == null ? 0 : observation.groundCount();
            boolean outputConfirmed = profile.magnetOverflowHarvest
                ? (long)inventoryNow + groundNow > (long)beforePickup + beforeGround
                : inventoryNow > beforePickup;
            long elapsed = world.tick() - pickupStarted;
            if (outputConfirmed && elapsed >= (profile.magnetOverflowHarvest ? 2 : 10)) {
                if (observation != null) recordMagnetOutput(context,observation);
                recordSample(context, false);
                markCompletedFarm(context,target);
                actions.stopMovement();
                context.navigation().reset();
                target = null;
                stage = Stage.APPROACH;
                return WorkResult.busy(groundNow > 0 ? "수확물 생성을 확인했습니다. 자석 운반을 유지하며 수확합니다." : "토마토를 주웠습니다.");
            }
            if (profile.magnetOverflowHarvest) actions.stopMovement();
            if (!profile.magnetOverflowHarvest && !outputConfirmed && elapsed >= 10) {
                Pos pickup = pickupFeet(world, target);
                if (pickup == null) { recordSample(context, true); return fail(context, "수확물 옆에 안전하게 설 자리가 없습니다."); }
                Navigation.Result arrival = context.navigation().moveTo(pickup, 1.0, context);
                if (arrival == Navigation.Result.BLOCKED) {
                    recordSample(context, true);
                    return fail(context, "수확물에 접근하지 못했습니다. " + navigationFailure(context));
                }
            }
            if (elapsed > Math.max(60, profile.interactionTimeoutTicks)) {
                recordSample(context, true);
                unresolvedOutput = profile.magnetOverflowHarvest
                    ? "수확 후 인벤토리와 바닥의 수확물 증가를 확인하지 못했습니다. 확인 후 F8로 다시 시작하세요."
                    : "수확 후 아이템 획득을 확인하지 못했습니다. 확인 후 F8로 다시 시작하세요.";
                return fail(context,unresolvedOutput);
            }
            return WorkResult.busy(profile.magnetOverflowHarvest ? "자석이 운반할 수확물 생성을 확인하고 있습니다." : "바닥에 나온 토마토를 줍습니다.");
        }
        return WorkResult.busy("수확 준비 중입니다.");
    }

    private String scan(Context context) {
        pending.clear();
        Set<Pos> seen = new HashSet<>();
        for (Farm farm : context.profile().farms) {
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
                        if (!context.world().loaded(p)) return "토마토밭 일부가 로드되지 않았습니다. 밭 근처에서 다시 시작하세요.";
                        if (isMature(context.world(), p)) {
                            matureCount++;
                            if (seen.add(p)) pending.addLast(p);
                        }
                    }
                }
            }
            if (matureCount == 0) context.profile().nextEligibleDay.put(farmKey(farm),gameDay(context.world())
                + (harvestedThisPass.contains(farmKey(farm)) ? Math.max(1,context.profile().harvestCycleDays) : 1));
        }
        return null;
    }

    private void markCompletedFarm(Context context, Pos harvested) {
        for (Farm farm : context.profile().farms) {
            if (!farm.contains(harvested) || farm.volume() > 32768) continue;
            harvestedThisPass.add(farmKey(farm));
            if (pending.stream().anyMatch(farm::contains)) continue;
            boolean remaining = false;
            for (int x = Math.min(farm.first().x(),farm.second().x()); x <= Math.max(farm.first().x(),farm.second().x()) && !remaining; x++) {
                for (int y = Math.min(farm.first().y(),farm.second().y()); y <= Math.max(farm.first().y(),farm.second().y()) && !remaining; y++) {
                    for (int z = Math.min(farm.first().z(),farm.second().z()); z <= Math.max(farm.first().z(),farm.second().z()); z++) {
                        Pos p = new Pos(x,y,z);
                        if (!context.world().loaded(p) || isMature(context.world(),p)) { remaining = true; break; }
                    }
                }
            }
            if (!remaining) context.profile().nextEligibleDay.put(farmKey(farm),gameDay(context.world()) + Math.max(1,context.profile().harvestCycleDays));
        }
    }

    private static String farmKey(Farm farm) { return "harvest:" + farm.name(); }
    private static long gameDay(WorldAccess world) { return Math.floorDiv(world.dayTime(),24000L); }

    /** Reserve capacity for a normal quality roll and rotten output before every click. */
    public static boolean hasHarvestRoom(List<ItemSlot> inventory) {
        int empty = 0, rottenSpace = 0;
        int[] tomatoSpace = new int[4];
        for (ItemSlot slot : inventory) {
            if (!slot.player() || slot.inventoryIndex() < 0 || slot.inventoryIndex() > 35) continue;
            ItemData item = slot.item();
            if (item.empty()) empty++;
            else if (item.is(ItemData.ROTTEN)) rottenSpace += Math.max(0, 64 - item.count());
            else if (item.is(ItemData.TOMATO) && item.quality() >= 0 && item.quality() < 4)
                tomatoSpace[item.quality()] += Math.max(0, 64 - item.count());
        }
        boolean everyQualityFits = Arrays.stream(tomatoSpace).allMatch(space -> space >= 4);
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
        pending.clear();
        harvestedThisPass.clear();
        target = null;
        ticket = -1;
        sweep = 0;
        useAttempts = 0;
        stage = Stage.PREPARE;
        beforeObservedByItem = Map.of();
    }

    private static boolean usableHoe(ItemData item) { return item.hoe() && !item.empty() && item.durability() > 1; }
    private static Pos pickupFeet(WorldAccess world, Pos tomato) {
        for (int dy = 0; dy >= -2; dy--) {
            Pos feet = tomato.offset(0, dy, 0);
            if (world.loaded(feet) && world.canStand(feet)) return feet;
        }
        return null;
    }
    private static ItemSlot inventorySlot(WorldAccess world, int index) { return world.inventory().stream().filter(s -> s.inventoryIndex() == index).findFirst().orElse(null); }
    private static boolean isMature(WorldAccess world, Pos p) { BlockData b = world.block(p); return b != null && b.matureTomato(); }
    private static int harvestItems(WorldAccess world) { return world.inventory().stream().map(ItemSlot::item).filter(i -> i.is(ItemData.TOMATO) || i.is(ItemData.ROTTEN)).mapToInt(ItemData::count).sum(); }
    private static HarvestObservation observeHarvest(WorldAccess world) {
        Map<String,Integer> totals = new HashMap<>();
        int inventoryCount = 0, groundCount = 0;
        for (ItemSlot slot : world.inventory()) {
            ItemData item = slot.item();
            if (item.is(ItemData.TOMATO) || item.is(ItemData.ROTTEN)) {
                inventoryCount += item.count();
                totals.merge(item.id(),item.count(),Integer::sum);
            }
        }
        PlayerState player = world.player();
        for (GroundItem ground : world.groundItems()) {
            ItemData item = ground.item();
            if ((item.is(ItemData.TOMATO) || item.is(ItemData.ROTTEN))
                && Math.pow(ground.x()-player.x(),2) + Math.pow(ground.y()-player.y(),2) + Math.pow(ground.z()-player.z(),2) <= 48*48) {
                groundCount += item.count();
                totals.merge(item.id(),item.count(),Integer::sum);
            }
        }
        return new HarvestObservation(Map.copyOf(totals),inventoryCount,groundCount);
    }

    private void recordMagnetOutput(Context context, HarvestObservation observation) {
        SessionState session = context.session();
        if (session.magnetHaulRemaining.isEmpty()) {
            if (observation.groundCount() == 0 && !session.magnetHaulPending) return;
            // The first overflow includes crops held or on the ground before this particular click.
            session.magnetHaulRemaining.putAll(observation.totals());
        } else {
            for (var entry : observation.totals().entrySet()) {
                int produced = entry.getValue() - beforeObservedByItem.getOrDefault(entry.getKey(),0);
                if (produced > 0) session.magnetHaulRemaining.merge(entry.getKey(),produced,Integer::sum);
            }
        }
        if (!session.magnetHaulRemaining.isEmpty()) session.magnetHaulPending = true;
    }
    private static String navigationFailure(Context context) {
        return context.navigation() instanceof LocalNavigator navigator ? navigator.failureReason() : "익은 토마토로 가는 경로가 막혔습니다.";
    }
}
