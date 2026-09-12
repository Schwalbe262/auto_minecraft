package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Save-only policy: no Minecraft singleton, connection, native action or live profile file. */
class PersistenceRecoveryTest {
    private static final PersistenceRecovery.Resume CONTINUOUS=new PersistenceRecovery.Resume(RunMode.CONTINUOUS,null);
    private static final ProfileStore.Diagnostic DENIED=new ProfileStore.Diagnostic(ProfileStore.Stage.REPLACE,"AccessDeniedException",3,false);
    private static final Throwable FAILURE=new IllegalStateException("checkpoint",new AccessDeniedException("isolated-test-profile"));
    @TempDir Path directory;

    @Test void onlyUncommittedAccessDeniedAtAnAtomicReplacementBoundaryIsRetryable() {
        for(ProfileStore.Stage stage:ProfileStore.Stage.values()) {
            boolean allowed=stage==ProfileStore.Stage.BACKUP_COPY || stage==ProfileStore.Stage.BACKUP_REPLACE || stage==ProfileStore.Stage.REPLACE;
            assertEquals(allowed,PersistenceRecovery.retryable(new ProfileStore.Diagnostic(stage,"AccessDeniedException",3,false),FAILURE),stage.name());
            assertFalse(PersistenceRecovery.retryable(new ProfileStore.Diagnostic(stage,"AccessDeniedException",3,true),FAILURE));
        }
        assertFalse(PersistenceRecovery.retryable(null,FAILURE));
        assertFalse(PersistenceRecovery.retryable(DENIED,null));
        for(Throwable permanent:List.of(new IOException("not a sharing failure"),new NoSuchFileException("missing"),
                new FileSystemException("arbitrary filesystem failure"),new AtomicMoveNotSupportedException("from","to","unsupported")))
            assertFalse(PersistenceRecovery.retryable(DENIED,permanent),"A diagnostic class name alone is not an AccessDenied cause");
    }

    @Test void causeInspectionIsBoundedAndDoesNotTreatSuppressedDenialAsPermission() {
        Throwable deep=FAILURE;
        for(int i=0;i<9;i++)deep=new IOException("wrapper",deep);
        assertFalse(PersistenceRecovery.retryable(DENIED,deep));
        IOException interrupted=new java.io.InterruptedIOException("interrupted");interrupted.addSuppressed(FAILURE);
        assertFalse(PersistenceRecovery.retryable(DENIED,interrupted));
        Throwable cycle=new IOException("cycle");cycle.initCause(new IOException("tail",cycle));
        assertFalse(PersistenceRecovery.retryable(DENIED,cycle));
    }

    @Test void automaticAttemptsUseThreeBoundedBackoffsWithoutBlockingOrResettingBudget() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,100,CONTINUOUS);
        assertTrue(p.automaticPending());assertEquals(120,p.nextRetryTick());
        assertNull(p.begin(id,119,false));assertEquals(0,p.automaticAttempts());
        var first=p.begin(id,120,false);assertNotNull(first);assertEquals(1,p.automaticAttempts());
        assertNull(p.begin(id,121,false),"One in-flight save cannot be duplicated");
        p.retryFailed(first,DENIED,FAILURE,120);assertEquals(180,p.nextRetryTick());
        assertNull(p.begin(id,179,false));var second=p.begin(id,180,false);assertEquals(2,p.automaticAttempts());
        p.retryFailed(second,DENIED,FAILURE,180);assertEquals(380,p.nextRetryTick());
        var third=p.begin(id,380,false);assertEquals(3,p.automaticAttempts());p.retryFailed(third,DENIED,FAILURE,380);
        assertFalse(p.automaticPending());assertEquals(-1,p.nextRetryTick());assertTrue(p.retryable());
        assertNull(p.begin(id,Long.MAX_VALUE,false));assertEquals("EXPLICIT_RETRY_REQUIRED",p.outcome());
        assertNotNull(p.begin(id,381,true),"Exhaustion forbids automatic looping, not a later explicit F8 save retry");
    }

    @Test void checkpointFailureDuringNormalStartConsumesTheSameEpisodeNotANewBudget() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,100,CONTINUOUS);
        long[] due={120,180,380};
        for(int i=0;i<due.length;i++) {
            var attempt=p.begin(id,due[i],false);assertEquals(i+1,p.automaticAttempts());
            assertSame(CONTINUOUS,p.saved(attempt,id));assertTrue(p.starting());assertFalse(p.automaticPending());
            // Existing Engine.begin() attempts a second, distinct ledger checkpoint.
            p.failed(id,DENIED,FAILURE,due[i],CONTINUOUS);
            assertFalse(p.starting());assertEquals(i+1,p.automaticAttempts(),"A successful save cannot reset this failure episode");
        }
        assertFalse(p.automaticPending());assertTrue(p.retryable());assertNull(p.begin(id,1000,false));
        assertEquals("EXPLICIT_RETRY_REQUIRED",p.outcome());
    }

    @Test void sameEpisodeMayResumeAfterASecondCheckpointFailureFinallySaves() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
        p.saved(p.begin(id,20,false),id);p.failed(id,DENIED,FAILURE,20,CONTINUOUS);
        assertSame(CONTINUOUS,p.saved(p.begin(id,80,false),id));p.resumeResult(true);
        assertEquals(2,p.automaticAttempts());assertEquals("RESUMED_THROUGH_START_GATES",p.outcome());
        assertFalse(p.automaticPending());assertFalse(p.retryable());assertFalse(p.starting());
    }

    @Test void wrongContextConnectionProfileOrKeyCannotSaveOrRestart() {
        var id=identity();
        List<PersistenceRecovery.Identity> changed=List.of(
            new PersistenceRecovery.Identity(new Object(),id.connection(),id.profile(),id.key()),
            new PersistenceRecovery.Identity(id.context(),new Object(),id.profile(),id.key()),
            new PersistenceRecovery.Identity(id.context(),id.connection(),new Object(),id.key()),
            new PersistenceRecovery.Identity(id.context(),id.connection(),id.profile(),"different"));
        for(var current:changed) {
            PersistenceRecovery p=new PersistenceRecovery();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
            assertNull(p.begin(current,20,true));assertFalse(p.automaticPending());assertFalse(p.retryable());
            assertNull(p.begin(id,20,true),"Returning to the old identity cannot restore revoked permission");
        }
    }

    @Test void missingOrUnloadedIdentityCannotGrantRetry() {
        for(var id:List.of(new PersistenceRecovery.Identity(null,new Object(),new Object(),"key"),
                new PersistenceRecovery.Identity(new Object(),null,new Object(),"key"),
                new PersistenceRecovery.Identity(new Object(),new Object(),null,"key"),
                new PersistenceRecovery.Identity(new Object(),new Object(),new Object(),null))) {
            PersistenceRecovery p=new PersistenceRecovery();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
            assertFalse(p.retryable());assertFalse(p.automaticPending());assertNull(p.begin(id,20,true));
        }
    }

    @Test void manualPauseSettingsAndInPlaceProfileSavesCancelBeforeAnyGuardCanThrow() {
        for(String reason:List.of("MANUAL_INPUT","EXPLICIT_OR_SAFETY_PAUSE","EXPLICIT_SETTINGS_CHANGE","EXPLICIT_PROFILE_SAVE","EXPLICIT_START_REQUEST")) {
            PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
            p.cancelAutomatic(reason);assertEquals(reason,p.outcome());assertNull(p.begin(id,2000,false));
            assertTrue(p.retryable());var explicit=p.begin(id,2000,true);assertNotNull(explicit);
            assertNull(p.saved(explicit,id),"An explicit start, not a saved former owner, must decide what to run");
            assertFalse(p.automaticPending());
        }
    }

    @Test void cancellationDuringAnAutomaticSaveCannotGrantAutomaticRestartAfterCommit() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
        var attempt=p.begin(id,20,false);p.cancelAutomatic("EXPLICIT_SETTINGS_CHANGE");
        assertNull(p.saved(attempt,id));assertFalse(p.starting());assertFalse(p.automaticPending());
    }

    @Test void cancellationDuringStartPreventsAFollowingCheckpointFailureFromRearming() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
        p.saved(p.begin(id,20,false),id);p.cancelAutomatic("EXPLICIT_OR_SAFETY_PAUSE");
        p.failed(id,DENIED,FAILURE,21,null);
        assertFalse(p.automaticPending());assertEquals("EXPLICIT_RETRY_REQUIRED",p.outcome());
        assertNull(p.begin(id,1000,false));assertNotNull(p.begin(id,1000,true));
    }

    @Test void oneShotRecoveryKeepsItsExactFeatureAndCannotBecomeContinuous() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();
        var once=new PersistenceRecovery.Resume(RunMode.ONCE,Feature.LOGGING);p.failed(id,DENIED,FAILURE,10,once);
        var resume=p.saved(p.begin(id,30,false),id);assertEquals(RunMode.ONCE,resume.mode());assertEquals(Feature.LOGGING,resume.feature());
        p.resumeResult(true);assertFalse(p.automaticPending());
        assertThrows(IllegalArgumentException.class,()->new PersistenceRecovery.Resume(RunMode.ONCE,null));
    }

    @Test void nativeGateOrPendingLedgerRejectionConsumesResumeWithoutPretendingSuccess() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
        assertNotNull(p.saved(p.begin(id,20,false),id));p.resumeResult(false);
        assertEquals("SAVED_START_GATE_BLOCKED",p.outcome());assertFalse(p.automaticPending());assertFalse(p.starting());
        assertNull(p.begin(id,10000,false));assertFalse(p.retryable());
    }

    @Test void manualOrPausedSaveFailureNeverReceivesAutomaticStartPermission() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,null);
        assertTrue(p.retryable());assertFalse(p.automaticPending());assertNull(p.begin(id,10000,false));
        assertNotNull(p.begin(id,1,true));
    }

    @Test void aPermanentRetryFailureStopsTheEpisodeInsteadOfHidingItAsAnotherWait() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,0,CONTINUOUS);
        p.retryFailed(p.begin(id,20,false),new ProfileStore.Diagnostic(ProfileStore.Stage.SIZE,"Failure",1,false),FAILURE,20);
        assertEquals("NON_RETRYABLE",p.outcome());assertFalse(p.retryable());assertFalse(p.automaticPending());
        assertNull(p.begin(id,1000,true));
    }

    @Test void rewindCancelsAutomaticPermissionAndLargeTickDeadlinesCannotWrap() {
        PersistenceRecovery p=new PersistenceRecovery();var id=identity();p.failed(id,DENIED,FAILURE,100,CONTINUOUS);
        assertNull(p.begin(id,99,false));assertFalse(p.automaticPending());assertEquals("CLOCK_CHANGED",p.outcome());
        p.failed(id,DENIED,FAILURE,Long.MAX_VALUE-10,CONTINUOUS);assertEquals(Long.MAX_VALUE,p.nextRetryTick());
        assertNull(p.begin(id,Long.MAX_VALUE-1,false));assertNotNull(p.begin(id,Long.MAX_VALUE,false));
    }

    @Test void cancelledSaveScopeCannotRestoreAStaleOwnerEvenAcrossNestedCallsAndExceptions() {
        PersistenceRecovery.SaveScope scope=new PersistenceRecovery.SaveScope();
        var once=new PersistenceRecovery.Resume(RunMode.ONCE,Feature.PRESERVES);
        scope.run(CONTINUOUS,()->{
            assertSame(CONTINUOUS,scope.current());
            assertThrows(IllegalStateException.class,()->scope.run(once,()->{
                assertSame(once,scope.current());scope.cancel();assertNull(scope.current());
                throw new IllegalStateException("pause/settings guard or subsequent save failed");
            }));
            assertNull(scope.current(),"The outer engine call cannot regain permission after public pause");
        });
        assertNull(scope.current());
        scope.run(CONTINUOUS,()->{scope.run(once,()->assertSame(once,scope.current()));assertSame(CONTINUOUS,scope.current());});
        assertNull(scope.current());
    }

    @Test void retriesSerializePostRollbackCurrentMemoryAndPreserveDurableCustody() throws Exception {
        Profile profile=new Profile();profile.lastSeenDay=770;profile.nextEligibleDay.put("logging:batch",774L);
        profile.loggingRunActive=true;profile.hoeHotbarSlot=1;profile.loggingAxeHotbarSlot=2;
        var lease=new LoggingHotbarLease(11,0,new ItemData("meadow:fire_log",9,0,null,false,64),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
        profile.loggingHotbarLease=lease;
        String key=ProfileStore.key("isolated-post-unwind-recovery");ProfileStore clean=new ProfileStore(directory);clean.save(key,profile);
        Path target=directory.resolve(key+".json");int[] writes={0};boolean[] denied={true};
        ProfileStore store=new ProfileStore(directory,new ProfileStore.FileOperations(){
            @Override public void replace(Path from,Path to)throws IOException {
                if(to.equals(target)) {writes[0]++;if(denied[0])throw new AccessDeniedException("isolated-profile");}
                ProfileStore.FileOperations.super.replace(from,to);
            }
        },millis->{});
        var id=new PersistenceRecovery.Identity(new Object(),new Object(),profile,key);PersistenceRecovery policy=new PersistenceRecovery();
        profile.nextEligibleDay.put("logging:batch",775L);profile.loggingHotbarLease=null;
        ProfileStore.Failure first=assertThrows(ProfileStore.Failure.class,()->store.save(key,profile));
        policy.failed(id,store.lastDiagnostic(),first,0,CONTINUOUS);
        // The checkpoint's actual owner unwinds AFTER the failed save, before any later tick can retry.
        profile.nextEligibleDay.put("logging:batch",774L);profile.loggingHotbarLease=lease;
        profile.lastSeenDay=771;
        assertNull(policy.begin(id,19,false));assertEquals(3,writes[0]);
        var retry=policy.begin(id,20,false);ProfileStore.Failure again=assertThrows(ProfileStore.Failure.class,()->store.save(key,profile));
        policy.retryFailed(retry,store.lastDiagnostic(),again,20);
        assertSame(lease,profile.loggingHotbarLease);assertEquals(774L,clean.load(key).nextEligibleDay.get("logging:batch"));
        denied[0]=false;var successful=policy.begin(id,80,false);assertNotNull(successful);store.save(key,profile);
        assertSame(CONTINUOUS,policy.saved(successful,id));
        Profile disk=clean.load(key);assertEquals(771,disk.lastSeenDay);assertEquals(774L,disk.nextEligibleDay.get("logging:batch"));
        assertEquals(lease,disk.loggingHotbarLease,"Recovery saves the retained debt, never the failed optimistic state");
        assertSame(lease,profile.loggingHotbarLease);assertEquals(7,writes[0]);
        policy.resumeResult(false);assertFalse(policy.automaticPending(),"Existing custody gates still decide whether the engine may start");
    }

    private static PersistenceRecovery.Identity identity() {
        return new PersistenceRecovery.Identity(new Object(),new Object(),new Object(),"profile-key");
    }
}
