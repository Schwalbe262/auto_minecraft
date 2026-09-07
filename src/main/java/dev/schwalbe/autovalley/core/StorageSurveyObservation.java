package dev.schwalbe.autovalley.core;

import java.util.List;

/** Local inspection evidence, not permission to withdraw or relocate any item. */
public record StorageSurveyObservation(Pos pos, long observedTick, int storageSlots, int emptySlots,
                                      List<Stock> contents, Status status, Integer classifier) {
    public StorageSurveyObservation { contents=List.copyOf(contents); }
    public record Stock(String itemId, int count, int quality, Integer cohort) { }
    public enum Status { EMPTY, TOMATO, WINE, MIXED, OTHER, UNKNOWN }
    public PoiKind classifiedKind() {
        return switch (status) {
            case TOMATO -> PoiKind.TOMATO_CHEST;
            case WINE -> PoiKind.WINE_CHEST;
            default -> null;
        };
    }
}
