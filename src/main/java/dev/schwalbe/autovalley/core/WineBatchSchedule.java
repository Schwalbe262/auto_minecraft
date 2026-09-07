package dev.schwalbe.autovalley.core;

import java.util.*;

/** One persisted whole-rack cycle; remaining entries require native refill confirmation. */
public record WineBatchSchedule(long nextDueDay, boolean active, List<Pos> remaining, Long latestFeedDay) {
    public WineBatchSchedule {
        Objects.requireNonNull(remaining,"remaining");
        if (nextDueDay<0 || remaining.size()>4096 || new HashSet<>(remaining).size()!=remaining.size()
                || remaining.stream().anyMatch(Objects::isNull) || latestFeedDay!=null && latestFeedDay<0
                || !active && !remaining.isEmpty() || active && remaining.isEmpty() && latestFeedDay==null)
            throw new IllegalArgumentException("Invalid wine batch schedule");
        remaining=List.copyOf(remaining);
    }
}
