package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Feature;
import dev.schwalbe.autovalley.core.RunMode;
import java.nio.file.AccessDeniedException;
import java.util.Objects;

/** Save-only retry permission. Never owns a profile snapshot, action, receipt or ledger mutation. */
final class PersistenceRecovery {
    private static final long[] BACKOFF_TICKS={20,60,200};
    record Identity(Object context,Object connection,Object profile,String key) {
        boolean same(Identity other) {
            return other!=null && context!=null && connection!=null && profile!=null && key!=null
                && context==other.context && connection==other.connection && profile==other.profile && key.equals(other.key);
        }
    }
    record Resume(RunMode mode,Feature feature) {
        Resume {
            Objects.requireNonNull(mode);
            if(mode==RunMode.ONCE && feature==null)throw new IllegalArgumentException("One-shot recovery needs its original feature");
        }
    }
    record Attempt(Identity identity,boolean automatic,long epoch) { }
    /** Permission belongs to the active engine call, not a later UI/pause save. */
    static final class SaveScope {
        private Resume current;
        private long epoch;
        Resume current() { return current; }
        void run(Resume resume,Runnable phase) {
            Resume previous=current; long before=epoch; current=resume;
            try { phase.run(); } finally { current=before==epoch ? previous : null; }
        }
        void cancel() { current=null; epoch++; }
    }
    private Identity owner;
    private Resume automaticResume;
    private Attempt startAttempt;
    private boolean retryable;
    private int automaticAttempts;
    private long nextRetryTick=-1,failedAtTick=-1,epoch;
    private String outcome="NONE";

    static boolean retryable(ProfileStore.Diagnostic diagnostic,Throwable failure) {
        if(diagnostic==null || diagnostic.committed()
            || diagnostic.stage()!=ProfileStore.Stage.BACKUP_COPY
                && diagnostic.stage()!=ProfileStore.Stage.BACKUP_REPLACE
                && diagnostic.stage()!=ProfileStore.Stage.REPLACE)return false;
        // Windows reports a refused atomic replacement this way. This can also be
        // a permanent ACL problem, hence a small fixed budget, never an endless retry.
        for(int depth=0;failure!=null && depth<8;depth++,failure=failure.getCause())
            if(failure instanceof AccessDeniedException)return true;
        return false;
    }
    void failed(Identity identity,ProfileStore.Diagnostic diagnostic,Throwable failure,long tick,Resume resume) {
        // A successful save can be followed immediately by the normal start's
        // ledger checkpoint. Its failure belongs to this SAME finite episode.
        if(startAttempt!=null && owner!=null && owner.same(identity) && startAttempt.epoch()==epoch
                && resume!=null && resume.equals(automaticResume)) {
            retryFailed(startAttempt,diagnostic,failure,tick); return;
        }
        clear(); owner=identity; failedAtTick=tick;
        retryable=retryable(diagnostic,failure) && identity!=null && identity.same(identity);
        automaticResume=retryable ? resume : null;
        nextRetryTick=automaticResume==null ? -1 : after(tick,BACKOFF_TICKS[0]);
        outcome=retryable ? automaticResume==null ? "EXPLICIT_RETRY_REQUIRED" : "BACKOFF" : "NON_RETRYABLE";
    }
    Attempt begin(Identity current,long tick,boolean explicit) {
        if(owner==null || !owner.same(current)) {
            cancelAutomatic("IDENTITY_CHANGED"); retryable=false; return null;
        }
        if(explicit)cancelAutomatic("EXPLICIT_RETRY");
        if(!retryable)return null;
        if(!explicit) {
            if(tick<failedAtTick) { cancelAutomatic("CLOCK_CHANGED"); return null; }
            if(!automaticPending() || tick<nextRetryTick)return null;
            automaticAttempts++; nextRetryTick=-1;
        }
        outcome="SAVING_CURRENT_MEMORY";
        return new Attempt(owner,!explicit,epoch);
    }
    void retryFailed(Attempt attempt,ProfileStore.Diagnostic diagnostic,Throwable failure,long tick) {
        if(attempt==null || owner==null || !owner.same(attempt.identity()))return;
        startAttempt=null;
        retryable=retryable(diagnostic,failure);
        if(attempt.automatic() && attempt.epoch()==epoch && automaticResume!=null && retryable
                && automaticAttempts<BACKOFF_TICKS.length) {
            nextRetryTick=after(tick,BACKOFF_TICKS[automaticAttempts]); outcome="BACKOFF";
        } else {
            automaticResume=null; nextRetryTick=-1;
            outcome=retryable ? "EXPLICIT_RETRY_REQUIRED" : "NON_RETRYABLE";
        }
    }
    Resume saved(Attempt attempt,Identity current) {
        Resume resume=attempt!=null && owner!=null && owner.same(current) && owner.same(attempt.identity())
            && attempt.automatic() && attempt.epoch()==epoch ? automaticResume : null;
        retryable=false; nextRetryTick=-1;
        startAttempt=resume==null ? null : attempt;
        if(resume==null)automaticResume=null;
        outcome=resume==null ? "SAVED" : "START_GATES";
        return resume;
    }
    void resumeResult(boolean running) {
        automaticResume=null; startAttempt=null; nextRetryTick=-1;
        outcome=running ? "RESUMED_THROUGH_START_GATES" : "SAVED_START_GATE_BLOCKED";
    }
    void cancelAutomatic(String reason) {
        boolean pending=automaticResume!=null;
        automaticResume=null; startAttempt=null; nextRetryTick=-1; epoch++;
        if(pending)outcome=reason;
    }
    void clear() {
        owner=null; automaticResume=null; startAttempt=null; retryable=false; automaticAttempts=0;
        nextRetryTick=-1; failedAtTick=-1; outcome="NONE"; epoch++;
    }
    boolean automaticPending() { return automaticResume!=null && nextRetryTick>=0; }
    boolean starting() { return startAttempt!=null; }
    boolean retryable() { return retryable; }
    int automaticAttempts() { return automaticAttempts; }
    long nextRetryTick() { return nextRetryTick; }
    String outcome() { return outcome; }
    private static long after(long tick,long delay) { return tick>Long.MAX_VALUE-delay ? Long.MAX_VALUE : tick+delay; }
}
