package dev.schwalbe.autovalley.core;

import java.util.*;

/** One persisted rack pass; each remaining member needs a refill ACK or an explicit skip. */
public record WineBatchSchedule(long nextDueDay, boolean active, List<Pos> remaining, Long latestFeedDay,
                                List<SkippedMember> skipped) {
    /** Legacy callers and profiles have no skipped members. */
    public WineBatchSchedule(long nextDueDay,boolean active,List<Pos> remaining,Long latestFeedDay) {
        this(nextDueDay,active,remaining,latestFeedDay,List.of());
    }

    public record SkippedMember(Pos pos,String reason,long day) {
        public SkippedMember {
            if (pos==null || reason==null || reason.isBlank() || reason.length()>160 || day<0)
                throw new IllegalArgumentException("Invalid skipped wine batch member");
        }
    }

    public WineBatchSchedule {
        Objects.requireNonNull(remaining,"remaining");
        // Gson passes null for this field in profiles written before skip tracking existed.
        if (skipped==null) skipped=List.of();
        if (nextDueDay<0 || remaining.size()>4096 || skipped.size()>4096 || remaining.size()+skipped.size()>4096
                || new HashSet<>(remaining).size()!=remaining.size()
                || remaining.stream().anyMatch(Objects::isNull) || latestFeedDay!=null && latestFeedDay<0
                || !active && !remaining.isEmpty() || active && remaining.isEmpty() && latestFeedDay==null && skipped.isEmpty())
            throw new IllegalArgumentException("Invalid wine batch schedule");
        Set<Pos> members=new HashSet<>(remaining);
        for (SkippedMember member:skipped) {
            if (member==null || !members.add(member.pos())) throw new IllegalArgumentException("Invalid or duplicate skipped wine batch member");
            new SkippedMember(member.pos(),member.reason(),member.day());
        }
        remaining=List.copyOf(remaining);
        skipped=List.copyOf(skipped);
    }
}
