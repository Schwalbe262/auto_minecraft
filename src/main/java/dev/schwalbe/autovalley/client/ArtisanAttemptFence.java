package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;
import java.util.function.Predicate;

/** RAM-only sent-use uncertainty, scoped to artisan targets; never an output debt or a resend queue. */
final class ArtisanAttemptFence<T> {
    private final int capacity;
    private final Map<Pos,T> attempts=new LinkedHashMap<>();
    private long generation=Long.MIN_VALUE;
    ArtisanAttemptFence(int capacity) { if(capacity<1 || capacity>4096)throw new IllegalArgumentException("Invalid attempt capacity");this.capacity=capacity; }
    private void generation(long current) {
        if(generation!=current) { attempts.clear();generation=current; } // Old channel ended, not historical success.
    }
    boolean blocked(Pos target,long current,Predicate<T> confirmed) {
        generation(current);T old=attempts.get(target);
        if(old!=null && confirmed.test(old))attempts.remove(target);
        if(attempts.size()>=capacity)attempts.entrySet().removeIf(entry->confirmed.test(entry.getValue()));
        return attempts.containsKey(target) || attempts.size()>=capacity;
    }
    boolean sent(Pos target,long current,T attempt) {
        generation(current);
        if(target==null || attempt==null || attempts.containsKey(target) || attempts.size()>=capacity)return false;
        attempts.put(target,attempt);return true;
    }
    void confirmed(Pos target,T exactAttempt) { if(attempts.get(target)==exactAttempt)attempts.remove(target); }
    int size() { return attempts.size(); }
}
