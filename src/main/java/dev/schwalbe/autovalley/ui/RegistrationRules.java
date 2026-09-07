package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Pure registration checks, independent of the game client. */
public final class RegistrationRules {
    private RegistrationRules() {}

    public enum Group { ALL, FARMS, MACHINES, CONTAINERS, BEDS }

    public static Group group(BlockData block) {
        if (block.tomato()) return Group.FARMS;
        if (block.id().equals("society:wine_keg") || block.id().equals("society:preserves_jar")
                || block.id().equals("shippingbin:smart_shipping_bin")) return Group.MACHINES;
        if (block.id().endsWith("_bed")) return Group.BEDS;
        if (block.flag("container")) return Group.CONTAINERS;
        return null;
    }

    public static List<PoiKind> kinds(BlockData block) {
        return switch (block.id()) {
            case "society:wine_keg" -> List.of(PoiKind.WINE_KEG);
            case "society:preserves_jar" -> List.of(PoiKind.PRESERVES_JAR);
            case "shippingbin:smart_shipping_bin" -> List.of(PoiKind.SHIPPING_BIN);
            default -> block.id().endsWith("_bed") ? List.of(PoiKind.BED)
                    : block.flag("container") ? List.of(PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST) : List.of();
        };
    }

    public static Integer classifier(PoiKind kind, String text) {
        if (kind != PoiKind.TOMATO_CHEST && kind != PoiKind.WINE_CHEST) return null;
        int number;
        try { number = Integer.parseInt(text.trim()); }
        catch (RuntimeException e) { throw new IllegalArgumentException("autovalley.error.classifier"); }
        if (number < 0 || (kind == PoiKind.TOMATO_CHEST && number > 3))
            throw new IllegalArgumentException("autovalley.error.classifier");
        return number;
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

    /** Adjacent vines and crop rows separated by one block form a suggestion, never a registration. */
    public static List<Farm> suggestFarms(List<BlockData> scan) {
        Set<Pos> remaining = new HashSet<>();
        scan.stream().filter(BlockData::tomato).forEach(b -> remaining.add(b.pos()));
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
            result.add(new Farm("", new Pos(minX, minY, minZ), new Pos(maxX, maxY, maxZ)));
        }
        result.sort(Comparator.comparingInt((Farm f) -> f.first().x()).thenComparingInt(f -> f.first().z()));
        return result;
    }
}
