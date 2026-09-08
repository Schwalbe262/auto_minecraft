package dev.schwalbe.autovalley.core;

import java.util.*;

/** Shared, world-independent draft validation. Runtime additionally checks dimension height and world border. */
public final class CoordinateDestinationRules {
    private CoordinateDestinationRules() { }
    public static final int MAX_COORDINATE = 29_999_984;
    public static final List<PoiKind> FACILITY_KINDS = List.of(PoiKind.PRESERVES_JAR, PoiKind.WINE_KEG,
            PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST, PoiKind.SHIPPING_BIN, PoiKind.BED,
            PoiKind.STORAGE_CANDIDATE, PoiKind.WOOD_CHEST, PoiKind.LOGGING_CRAFTING_TABLE);

    public static List<PoiKind> kinds(BlockData block) {
        if (block == null) return List.of();
        return switch (block.id()) {
            case "society:wine_keg" -> List.of(PoiKind.WINE_KEG);
            case "society:preserves_jar" -> List.of(PoiKind.PRESERVES_JAR);
            case "shippingbin:smart_shipping_bin" -> List.of(PoiKind.SHIPPING_BIN);
            case "minecraft:crafting_table" -> List.of(PoiKind.LOGGING_CRAFTING_TABLE);
            default -> block.id().endsWith("_bed") ? List.of(PoiKind.BED)
                    : StorageSurveyRules.ordinaryStorage(block) ? List.of(PoiKind.STORAGE_CANDIDATE, PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST, PoiKind.WOOD_CHEST)
                    : block.flag("container") ? List.of(PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST) : List.of();
        };
    }

    public static boolean matches(PoiKind kind, BlockData block) { return kind != null && kinds(block).contains(kind); }
    public static boolean requiresContentsConfirmation(PoiKind kind) {
        return kind == PoiKind.TOMATO_CHEST || kind == PoiKind.WINE_CHEST || kind == PoiKind.WOOD_CHEST;
    }
    public static boolean validPosition(Pos pos) {
        return pos != null && Math.abs((long) pos.x()) <= MAX_COORDINATE
                && Math.abs((long) pos.z()) <= MAX_COORDINATE && pos.y() >= -2048 && pos.y() <= 2047;
    }
    public static Pos parsePosition(String x, String y, String z) {
        try {
            Pos pos = new Pos(Integer.parseInt(x.trim()), Integer.parseInt(y.trim()), Integer.parseInt(z.trim()));
            if (!validPosition(pos)) throw new IllegalArgumentException("Coordinates outside supported range");
            return pos;
        } catch (NullPointerException | NumberFormatException e) { throw new IllegalArgumentException("Integer coordinates required", e); }
    }
    public static void validate(CoordinateDestination draft) {
        if (draft == null || draft.name() == null || draft.name().isBlank() || draft.name().length() > 64
                || draft.name().chars().anyMatch(Character::isISOControl) || !validPosition(draft.pos()))
            throw new IllegalArgumentException("Invalid coordinate destination");
        PoiKind kind = draft.facilityKind();
        if (kind != null && !FACILITY_KINDS.contains(kind)) throw new IllegalArgumentException("Unsupported coordinate facility");
        if (kind == PoiKind.WINE_CHEST) {
            if (draft.classifier() == null || draft.classifier() < 0) throw new IllegalArgumentException("Wine production cohort required");
        } else if (draft.classifier() != null) throw new IllegalArgumentException("Unexpected coordinate classifier");
        if (draft.contentsConfirmed() != requiresContentsConfirmation(kind))
            throw new IllegalArgumentException("Explicit storage contents confirmation required");
    }
    public static void validate(Profile profile) {
        if (profile.navigationMode == null || profile.coordinateDestinations == null || profile.coordinateDestinations.size() > 4096)
            throw new IllegalArgumentException("Invalid navigation settings");
        Set<String> names = new HashSet<>();
        Set<Pos> positions = new HashSet<>();
        for (CoordinateDestination draft : profile.coordinateDestinations) {
            validate(draft);
            if (!names.add(draft.name()) || !positions.add(draft.pos())) throw new IllegalArgumentException("Duplicate coordinate destination");
        }
    }
}
