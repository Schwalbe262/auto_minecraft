package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.Function;

/** Pure registration checks, independent of the game client. */
public final class RegistrationRules {
    private RegistrationRules() {}
    private static final Map<String,CropDefinition> DEFAULT_CROPS=CropRules.defaults();

    public enum Group { ALL, FARMS, MACHINES, CONTAINERS, BEDS }
    public enum CoordinateState { MOVE_ONLY, UNLOADED, TYPE_MISMATCH, READY_TO_CONFIRM }

    public static int coordinateRows(int height) { return Math.max(1, (height - 57 - 104) / 23); }
    public static CoordinateDestination coordinateDraft(String name, String x, String y, String z, PoiKind kind,
            String classifier, Integer formYear, Integer currentYear, boolean contentsChecked) {
        if (requiresContentsConfirmation(kind) && !contentsChecked)
            throw new IllegalArgumentException("autovalley.error.container_unchecked");
        Integer cohort = kind == PoiKind.WINE_CHEST ? WineCohortRules.parseChecked(classifier, formYear, currentYear) : null;
        CoordinateDestination draft = new CoordinateDestination(name == null ? "" : name.trim(),
                CoordinateDestinationRules.parsePosition(x, y, z), kind, cohort, contentsChecked);
        CoordinateDestinationRules.validate(draft); return draft;
    }
    public static CoordinateState coordinateState(CoordinateDestination draft, Predicate<Pos> loaded, Function<Pos, BlockData> blocks) {
        if (draft.facilityKind() == null) return CoordinateState.MOVE_ONLY;
        if (!loaded.test(draft.pos())) return CoordinateState.UNLOADED;
        BlockData block = blocks.apply(draft.pos());
        return block != null && draft.pos().equals(block.pos()) && CoordinateDestinationRules.matches(draft.facilityKind(), block)
                ? CoordinateState.READY_TO_CONFIRM : CoordinateState.TYPE_MISMATCH;
    }

    public static Group group(BlockData block) {
        return group(null,block);
    }
    public static CropDefinition crop(Profile profile,BlockData block) {
        if(block==null)return null;
        Map<String,CropDefinition> definitions=profile==null || profile.crops==null ? DEFAULT_CROPS : profile.crops;
        CropDefinition selected=null;
        for(var entry:definitions.entrySet()) {
            CropDefinition candidate=entry.getValue();
            if(!CropRules.valid(candidate) || !candidate.key().equals(entry.getKey()) || !CropRules.matches(candidate,block))continue;
            if(selected!=null)return null;
            selected=candidate;
        }
        return selected;
    }
    public static Group group(Profile profile,BlockData block) {
        if(block==null)return null;
        if (crop(profile,block)!=null) return Group.FARMS;
        if (block.id().equals("society:wine_keg") || block.id().equals("society:preserves_jar")
                || block.id().equals("shippingbin:smart_shipping_bin") || block.id().equals("minecraft:crafting_table")) return Group.MACHINES;
        if (block.id().endsWith("_bed")) return Group.BEDS;
        if (block.flag("container")) return Group.CONTAINERS;
        return null;
    }

    public static List<PoiKind> kinds(BlockData block) {
        return CoordinateDestinationRules.kinds(block);
    }

    public static Integer classifier(PoiKind kind, String text) {
        return classifier(kind, text, null);
    }

    /** Tomatoes are commodity-only; wine AGE/+N still maps to the production cohort. */
    public static Integer classifier(PoiKind kind, String text, Integer currentWineYear) {
        if (kind == PoiKind.WINE_CHEST) return WineCohortRules.parse(text, currentWineYear);
        return null;
    }

    public static Integer wineAge(Integer cohort, Integer currentWineYear) {
        WineCohortRules.Display display = WineCohortRules.describe(cohort, currentWineYear);
        return display == null || display.future() ? null : display.years();
    }

    /** Keep the main module controls within the same three rows as features grow. */
    public static int moduleColumns(int count) { return Math.max(3,(count+2)/3); }
    public static int tomatoStorageLimitPercent(String value) {
        if (value==null || !value.trim().matches("[0-9]{1,3}")) throw new IllegalArgumentException("Enter an integer percentage");
        int percent=Integer.parseInt(value.trim());
        if (percent<1 || percent>100) throw new IllegalArgumentException("Tomato storage percentage must be 1 to 100");
        return percent;
    }

    /** Only an explicitly aimed spruce planting/trunk block can seed a plot draft. */
    public static boolean loggingCorner(BlockData block) {
        return block != null && (block.id().equals("minecraft:spruce_sapling") || block.id().equals("minecraft:spruce_log"));
    }

    public static boolean loggingBaseReady(Pos corner, Predicate<Pos> loaded, Function<Pos,BlockData> blocks) {
        if (corner == null || corner.x() == Integer.MAX_VALUE || corner.z() == Integer.MAX_VALUE) return false;
        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            Pos position = corner.offset(dx, 0, dz);
            if (!loaded.test(position)) return false;
            BlockData block = blocks.apply(position);
            if (!loggingCorner(block) || !position.equals(block.pos())) return false;
        }
        return true;
    }

    public static boolean loggingAxe(ItemData held, int selectedSlot, int hoeSlot) {
        return selectedSlot >= 0 && selectedSlot < 9 && selectedSlot != hoeSlot
                && held != null && held.is("minecraft:netherite_axe") && held.durability() > 1;
    }

    public static boolean requiresContentsConfirmation(PoiKind kind) {
        return CoordinateDestinationRules.requiresContentsConfirmation(kind);
    }

    public static int loggingCheckTicks(String seconds) {
        try {
            int ticks = new java.math.BigDecimal(seconds.trim()).multiply(java.math.BigDecimal.valueOf(20)).intValueExact();
            if (ticks < 20 || ticks > 24000) throw new IllegalArgumentException("Invalid logging interval");
            return ticks;
        } catch (NullPointerException | ArithmeticException e) { throw new IllegalArgumentException("Invalid logging interval", e); }
    }

    public static String loggingCheckSeconds(int ticks) {
        return java.math.BigDecimal.valueOf(ticks).divide(java.math.BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
    }

    public static boolean validBounds(Pos first, Pos second) {
        if (first == null || second == null) return false;
        long x = 1L + Math.abs((long) first.x() - second.x());
        long y = 1L + Math.abs((long) first.y() - second.y());
        long z = 1L + Math.abs((long) first.z() - second.z());
        // Check each factor before multiplication to avoid overflow from malformed input.
        return x <= 32768 && y <= 32768 && z <= 32768 && x * y * z <= 32768;
    }

    public static boolean overlap(Farm first, Farm second) {
        return Math.max(Math.min(first.first().x(), first.second().x()), Math.min(second.first().x(), second.second().x()))
                    <= Math.min(Math.max(first.first().x(), first.second().x()), Math.max(second.first().x(), second.second().x()))
            && Math.max(Math.min(first.first().y(), first.second().y()), Math.min(second.first().y(), second.second().y()))
                    <= Math.min(Math.max(first.first().y(), first.second().y()), Math.max(second.first().y(), second.second().y()))
            && Math.max(Math.min(first.first().z(), first.second().z()), Math.min(second.first().z(), second.second().z()))
                    <= Math.min(Math.max(first.first().z(), first.second().z()), Math.max(second.first().z(), second.second().z()));
    }

    /** Conservative warning: suggestions cannot establish a complete field across unobserved chunks. */
    public static boolean mayBePartial(Farm farm, Pos center, int horizontal, int vertical, Predicate<Pos> loaded) {
        return mayBePartial(farm, center, horizontal, vertical, loaded, 2);
    }

    public static boolean mayBePartial(Farm farm, Pos center, int horizontal, int vertical, Predicate<Pos> loaded, int neighborhood) {
        if (neighborhood < 1 || neighborhood > 3) throw new IllegalArgumentException("Invalid grouping neighborhood");
        int minX = Math.min(farm.first().x(), farm.second().x()), maxX = Math.max(farm.first().x(), farm.second().x());
        int minY = Math.min(farm.first().y(), farm.second().y()), maxY = Math.max(farm.first().y(), farm.second().y());
        int minZ = Math.min(farm.first().z(), farm.second().z()), maxZ = Math.max(farm.first().z(), farm.second().z());
        if (minX <= center.x() - horizontal || maxX >= center.x() + horizontal
                || minZ <= center.z() - horizontal || maxZ >= center.z() + horizontal
                || minY <= center.y() - vertical || maxY >= center.y() + vertical) return true;
        // Check the same two-block row neighborhood used by grouping, including interior chunk holes.
        // Loaded chunks cover entire vertical columns; upper/lower checks also detect build-height edges.
        for (int x = minX - neighborhood; x <= maxX + neighborhood; x++) for (int z = minZ - neighborhood; z <= maxZ + neighborhood; z++) {
            if (!loaded.test(new Pos(x, minY - 1, z)) || !loaded.test(new Pos(x, maxY + 1, z))) return true;
        }
        return false;
    }

    /** Only explicit artisan machine types may be bulk registered; storage never receives inferred grades/ages. */
    public static List<BlockData> connectedMachines(List<BlockData> candidates, BlockData selected) {
        if (!selected.id().equals("society:wine_keg") && !selected.id().equals("society:preserves_jar")) return List.of();
        Map<Pos,BlockData> remaining = new HashMap<>();
        for (BlockData candidate : candidates) if (candidate.id().equals(selected.id())) remaining.put(candidate.pos(),candidate);
        BlockData seed = remaining.remove(selected.pos());
        if (seed == null) return List.of();
        List<BlockData> group = new ArrayList<>();
        ArrayDeque<BlockData> queue = new ArrayDeque<>(); queue.add(seed);
        while (!queue.isEmpty()) {
            BlockData current = queue.removeFirst(); group.add(current);
            for (int dx = -3; dx <= 3; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -3; dz <= 3; dz++) {
                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 3) continue;
                BlockData neighbor = remaining.remove(current.pos().offset(dx,dy,dz));
                if (neighbor != null) queue.add(neighbor);
            }
        }
        group.sort(Comparator.comparingInt((BlockData b) -> b.pos().x()).thenComparingInt(b -> b.pos().y()).thenComparingInt(b -> b.pos().z()));
        return List.copyOf(group);
    }

    public static Farm blockBounds(List<BlockData> blocks) {
        if (blocks.isEmpty()) throw new IllegalArgumentException("Cannot bound an empty group");
        IntSummaryStatistics x = blocks.stream().mapToInt(b -> b.pos().x()).summaryStatistics();
        IntSummaryStatistics y = blocks.stream().mapToInt(b -> b.pos().y()).summaryStatistics();
        IntSummaryStatistics z = blocks.stream().mapToInt(b -> b.pos().z()).summaryStatistics();
        return new Farm("",new Pos(x.getMin(),y.getMin(),z.getMin()),new Pos(x.getMax(),y.getMax(),z.getMax()));
    }

    /** Adjacent vines and crop rows separated by one block form a suggestion, never a registration. */
    public static List<Farm> suggestFarms(List<BlockData> scan) {
        return suggestFarms(scan,null);
    }
    public static List<Farm> suggestFarms(List<BlockData> scan,Profile profile) {
        Map<String,Set<Pos>> crops=new TreeMap<>();
        for(BlockData block:scan) {
            CropDefinition crop=crop(profile,block);
            if(crop!=null)crops.computeIfAbsent(crop.key(),ignored->new HashSet<>()).add(block.pos());
        }
        List<Farm> result=new ArrayList<>();
        crops.forEach((crop,positions)->result.addAll(suggestCropFarms(positions,crop)));
        result.sort(Comparator.comparingInt((Farm f)->f.first().x()).thenComparingInt(f->f.first().z())
            .thenComparingInt(f->f.first().y()).thenComparing(Farm::cropId));
        return result;
    }
    private static List<Farm> suggestCropFarms(Set<Pos> positions,String cropId) {
        Set<Pos> remaining = new HashSet<>();
        remaining.addAll(positions);
        List<Farm> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            Pos seed = remaining.iterator().next();
            remaining.remove(seed);
            ArrayDeque<Pos> queue = new ArrayDeque<>();
            queue.add(seed);
            int minX = seed.x(), maxX = seed.x(), minY = seed.y(), maxY = seed.y(), minZ = seed.z(), maxZ = seed.z();
            while (!queue.isEmpty()) {
                Pos p = queue.removeFirst();
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) > 2) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        Pos neighbor = p.offset(dx, dy, dz);
                        if (remaining.remove(neighbor)) queue.add(neighbor);
                    }
                }
            }
            result.add(new Farm("", new Pos(minX, minY, minZ), new Pos(maxX, maxY, maxZ),cropId));
        }
        result.sort(Comparator.comparingInt((Farm f) -> f.first().x()).thenComparingInt(f -> f.first().z()));
        return result;
    }
}
