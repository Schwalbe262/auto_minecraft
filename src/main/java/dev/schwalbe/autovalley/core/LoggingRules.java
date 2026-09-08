package dev.schwalbe.autovalley.core;

import java.util.*;

/** Narrow, recorded spruce routine. Neither generic tree destruction nor arbitrary crafting. */
public final class LoggingRules {
    public static final String LOG="minecraft:spruce_log";
    public static final String SAPLING="minecraft:spruce_sapling";
    public static final String CHOPPED_LOG="treechop:chopped_log";
    public static final String FIRE_LOG="meadow:fire_log";
    public static final String TWIG="twigs:twig";
    public static final String BERRY="society:mossberry";
    public static final String AXE="minecraft:netherite_axe";
    public static final String RECIPE="meadow:fire_log";
    public static final String DUE_KEY="logging:batch";
    private LoggingRules() { }

    public static void validate(Profile p) {
        if (p.loggingPlots==null || p.loggingPlots.size()>256 || p.loggingMode==null
            || p.loggingAxeHotbarSlot < -1 || p.loggingAxeHotbarSlot>8
            || p.loggingAxeHotbarSlot>=0 && p.loggingAxeHotbarSlot==p.hoeHotbarSlot
            || p.loggingSaplingReserve<0 || p.loggingSaplingReserve>2304
            || p.loggingCheckTicks<20 || p.loggingCheckTicks>24000
            || p.loggingCycleDays<1 || p.loggingCycleDays>28)
            throw new IllegalArgumentException("Invalid logging configuration");
        Set<String> names=new HashSet<>(); Set<Pos> bases=new HashSet<>();
        for (LoggingPlot plot:p.loggingPlots) {
            if (plot==null || plot.name()==null || plot.name().isBlank() || plot.name().length()>80 || plot.corner()==null
                || !names.add(plot.name()) || Math.abs((long)plot.corner().x())>29_999_984
                || Math.abs((long)plot.corner().z())>29_999_984 || plot.corner().y() < -2032 || plot.corner().y()>1967)
                throw new IllegalArgumentException("Invalid or duplicate logging plot");
            for (Pos base:plot.plantingPositions()) {
                if (!bases.add(base) || p.farms.stream().anyMatch(f -> f.contains(base)))
                    throw new IllegalArgumentException("Logging plots overlap each other or a tomato farm");
            }
        }
        for (Poi poi:p.pois) if ((poi.kind()==PoiKind.WOOD_CHEST || poi.kind()==PoiKind.LOGGING_CRAFTING_TABLE) && poi.classifier()!=null)
            throw new IllegalArgumentException("Logging destinations do not have grade classifiers");
        if (p.loggingRemainingPlots==null || p.loggingReplantingPlots==null
            || p.loggingRemainingPlots.size()>256 || p.loggingReplantingPlots.size()>256
            || !p.loggingRunActive && (!p.loggingRemainingPlots.isEmpty() || !p.loggingReplantingPlots.isEmpty() || p.loggingHotbarLease!=null))
            throw new IllegalArgumentException("Invalid logging run checkpoint");
        Set<Pos> corners=new HashSet<>();
        for (LoggingPlot plot:p.loggingPlots) corners.add(plot.corner());
        Set<Pos> remaining=new HashSet<>(p.loggingRemainingPlots),replanting=new HashSet<>(p.loggingReplantingPlots);
        if (remaining.size()!=p.loggingRemainingPlots.size() || replanting.size()!=p.loggingReplantingPlots.size()
            || !corners.containsAll(remaining) || !remaining.containsAll(replanting))
            throw new IllegalArgumentException("A logging checkpoint references missing or duplicate plots");
        LoggingHotbarLease lease=p.loggingHotbarLease;
        if (lease!=null && (lease.sourceIndex()<9 || lease.sourceIndex()>35 || lease.hotbarSlot()<0 || lease.hotbarSlot()>8
            || lease.hotbarSlot()==p.hoeHotbarSlot || lease.hotbarSlot()==p.loggingAxeHotbarSlot
            || lease.original()==null || lease.original().empty() || lease.original().hoe() || lease.original().is(AXE)
            || lease.fingerprint()==null || !lease.fingerprint().matches("[0-9a-fA-F]{64}") || lease.stage()==null))
            throw new IllegalArgumentException("Invalid logging hotbar reservation");
    }
    public static boolean allowed(Context c) { return c.session().allows(c.profile(),Feature.LOGGING); }
    public static boolean base(Profile p,Pos pos) {
        return pos!=null && p.loggingPlots.stream().anyMatch(plot -> plot.plantingPositions().contains(pos));
    }
    /** Geometry-only target validation; execution additionally requires an active replant obligation. */
    public static boolean plantingTargets(Profile p,List<Pos> targets) {
        return p!=null && targets!=null && !targets.isEmpty() && targets.size()<=4
            && targets.stream().noneMatch(Objects::isNull) && new HashSet<>(targets).size()==targets.size()
            && p.loggingPlots.stream().anyMatch(plot -> plot.plantingPositions().containsAll(targets));
    }
    public static boolean wood(ItemData item) { return item.is(LOG) || item.is(FIRE_LOG); }
    public static boolean byproduct(ItemData item) { return item.is(BERRY); }
    public static boolean waste(ItemData item) { return item.is(SAPLING) || item.is(TWIG); }
    public static boolean stump(BlockData block) { return block!=null && (block.id().equals(LOG) || block.id().equals(CHOPPED_LOG)); }
    /** A coherent 2x2 planting pattern, not proof of a particular generated canopy. */
    public static boolean completePlanting(WorldAccess world,LoggingPlot plot) {
        return plot.plantingPositions().stream().allMatch(p -> world.loaded(p) && world.block(p).id().equals(SAPLING))
            || plot.plantingPositions().stream().allMatch(p -> world.loaded(p) && world.block(p).id().equals(LOG));
    }
    public static boolean partiallyGrown(WorldAccess world,LoggingPlot plot) {
        long logs=plot.plantingPositions().stream().filter(p -> world.loaded(p) && world.block(p).id().equals(LOG)).count();
        return logs>0 && logs<4;
    }
    public static int count(WorldAccess world,String id) {
        return world.inventory().stream().filter(s -> s.player() && s.inventoryIndex()>=0 && s.inventoryIndex()<36)
            .map(ItemSlot::item).filter(i -> i.is(id)).mapToInt(ItemData::count).sum();
    }
    public static String chopRejection(Pos pos,Context c) {
        if (!allowed(c) || !base(c.profile(),pos)) return "벌목은 등록한 2x2 나무 밑동에서만 가능합니다.";
        if (c.world().menu().container() || !c.world().loaded(pos) || !c.world().canInteract(pos,4))
            return "벌목 대상의 청크·시야·거리를 확인하세요.";
        ItemData held=SafetyPolicy.held(c.world());
        if (c.profile().loggingAxeHotbarSlot<0 || c.world().player().selectedSlot()!=c.profile().loggingAxeHotbarSlot
            || !held.is(AXE) || held.durability()<=1 || !c.world().loggingAxe(c.profile().loggingAxeHotbarSlot))
            return "등록한 사용 가능한 네더라이트 도끼가 필요합니다.";
        if (!stump(c.world().block(pos))) return "확인된 가문비나무 밑동만 벨 수 있습니다.";
        return c.world().loggingTreeRejection(pos,c.profile().loggingPlots);
    }
    public static String plantRejection(Pos pos,Context c) {
        for (LoggingPlot plot:c.profile().loggingPlots)
            if (plot.plantingPositions().contains(pos) && c.profile().loggingReplantingPlots.contains(plot.corner())
                && partiallyGrown(c.world(),plot)) return "2x2 재식재 중 일부 나무만 자랐습니다. 자동으로 다시 베거나 남은 칸을 채우지 않습니다.";
        if (!allowed(c) || !base(c.profile(),pos) || c.world().menu().container()
            || !SafetyPolicy.held(c.world()).is(SAPLING) || !c.world().loaded(pos)
            || !c.world().canPlantLoggingSapling(pos)) return "등록한 빈 식재 칸에 가문비나무 묘목만 심을 수 있습니다.";
        return null;
    }
    public static String trashRejection(Action.TrashLogging trash,Context c) {
        if (!allowed(c) || !c.actions().supportsInventoryTrash() || c.world().menu().container()
            || MachineOutputLedger.hasPending(c) || trash.expected()==null || !waste(trash.expected())
            || trash.inventoryIndex()<0 || trash.inventoryIndex()>=36)
            return "벌목 부산물 폐기 조건이 맞지 않습니다.";
        var matches=c.world().inventory().stream().filter(s -> s.player() && s.inventoryIndex()==trash.inventoryIndex()).toList();
        var menu=c.world().menu().slots().stream().filter(s -> s.player() && s.inventoryIndex()==trash.inventoryIndex()).toList();
        if (matches.size()!=1 || menu.size()!=1 || !trash.expected().equals(matches.get(0).item())
            || !trash.expected().equals(menu.get(0).item())) return "폐기할 벌목 부산물의 재고가 바뀌었습니다.";
        if (c.profile().loggingPlots.isEmpty()) return "재식재 구역을 먼저 등록하세요.";
        for (LoggingPlot plot:c.profile().loggingPlots)
            if (!completePlanting(c.world(),plot)) return "모든 구역의 2x2 묘목 또는 네 기둥을 먼저 확인한 뒤 부산물을 폐기합니다.";
        if (trash.expected().is(SAPLING)) {
            if (count(c.world(),SAPLING)-trash.expected().count()<c.profile().loggingSaplingReserve)
                return "설정한 예비 묘목 수량을 남겨야 합니다.";
        }
        return null;
    }
}
