package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Profile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Fault injection uses only isolated test files and records retry pauses without sleeping. */
class ProfileStoreRetryTest {
    @TempDir Path directory;

    @Test void transientMainReplacementRetriesTheSameSerializedBytesAndSameTemporaryFile() throws Exception {
        Seed seed=seed(); Faults io=new Faults(seed);io.failPrimary=2;
        List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(directory,io,pauses::add);
        Profile changed=profile(5);ObservedTargets targets=new ObservedTargets(false,"inert");changed.tomatoStorageTargets=targets;
        io.beforePrimary=()->changed.lastSeenDay=99;
        store.save(seed.key(),changed);
        assertEquals(3,io.primaryCalls);assertEquals(1,io.copyCalls);assertEquals(1,io.backupCalls);
        assertEquals(List.of(10L,10L),pauses);assertEquals(1,targets.reads,"Serialization is outside the retry loop");
        assertEquals(1,new HashSet<>(io.primarySources).size(),"Retries retain the same fully written temporary file");
        for(byte[] attempt:io.primaryBytes)assertArrayEquals(io.primaryBytes.get(0),attempt);
        assertArrayEquals(io.primaryBytes.get(0),Files.readAllBytes(seed.file()));
        assertArrayEquals(seed.original(),Files.readAllBytes(seed.backup()));
        assertEquals(5,store.load(seed.key()).lastSeenDay,"Later in-memory mutation cannot change the retried snapshot");
        assertEquals(99,changed.lastSeenDay);
    }

    @Test void persistentMainReplacementFailureIsBoundedAndPreservesTheOriginalAndRecoverableBackup() throws Exception {
        Seed seed=seed();Faults io=new Faults(seed);io.failPrimary=Integer.MAX_VALUE;
        List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(directory,io,pauses::add);
        ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),profile(5)));
        assertEquals("REPLACE",failure.stage().name());assertEquals(3,failure.attempts());assertSame(io.primaryFailure,failure.getCause());
        assertEquals(3,io.primaryCalls);assertEquals(List.of(10L,10L),pauses);
        assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));
        assertArrayEquals(seed.original(),Files.readAllBytes(seed.backup()));
        assertFalse(store.lastDiagnostic().committed());assertEquals("REPLACE",store.lastDiagnostic().stage().name());
        assertEquals(4,new ProfileStore(directory).load(seed.key()).lastSeenDay);
    }

    @Test void backupCopyOrReplacementFailureCannotPartiallyOverwriteTheExistingBackup() throws Exception {
        for(boolean failCopy:new boolean[]{true,false}) {
            Path run=Files.createDirectory(directory.resolve(failCopy?"copy":"backup-replace"));Seed seed=seed(run);Faults io=new Faults(seed);
            if(failCopy){io.failCopy=Integer.MAX_VALUE;io.partialCopy=true;}else io.failBackup=Integer.MAX_VALUE;
            List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(run,io,pauses::add);
            ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),profile(5)));
            assertEquals(failCopy?"BACKUP_COPY":"BACKUP_REPLACE",failure.stage().name());assertEquals(3,failure.attempts());
            assertSame(failCopy?io.copyFailure:io.backupFailure,failure.getCause());assertEquals(0,io.primaryCalls);
            assertEquals(List.of(10L,10L),pauses);assertFalse(store.lastDiagnostic().committed());
            assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));assertArrayEquals(seed.priorBackup(),Files.readAllBytes(seed.backup()));
        }
    }

    @Test void retryBudgetIsSharedAcrossBackupCopyBackupReplacementAndMainReplacement() throws Exception {
        Seed seed=seed();Faults io=new Faults(seed);io.failCopy=1;io.failBackup=1;io.failPrimary=1;
        List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(directory,io,pauses::add);
        ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),profile(5)));
        assertEquals("REPLACE",failure.stage().name());assertEquals(1,failure.attempts());
        assertEquals(2,io.copyCalls);assertEquals(2,io.backupCalls);assertEquals(1,io.primaryCalls);
        assertEquals(List.of(10L,10L),pauses);assertEquals(20,pauses.stream().mapToLong(Long::longValue).sum());
        assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));assertArrayEquals(seed.original(),Files.readAllBytes(seed.backup()));
    }

    @Test void nontransientIoErrorsDoNotSpendTheRetryBudget() throws Exception {
        List<IOException> failures=List.of(new IOException("non-filesystem I/O"),new NoSuchFileException("missing-test-temp"),
            new FileAlreadyExistsException("unexpected-test-target"),new NotDirectoryException("test-parent"),
            new AtomicMoveNotSupportedException("test-source","test-target","no atomic replacement"));
        for(int index=0;index<failures.size();index++) {
            Path run=Files.createDirectory(directory.resolve("nontransient-"+index));Seed seed=seed(run);Faults io=new Faults(seed);
            io.failPrimary=Integer.MAX_VALUE;io.primaryFailure=failures.get(index);
            List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(run,io,pauses::add);
            ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),profile(5)));
            assertEquals("REPLACE",failure.stage().name());assertEquals(1,failure.attempts());assertSame(failures.get(index),failure.getCause());
            assertEquals(1,io.primaryCalls);assertTrue(pauses.isEmpty());assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));
        }
    }

    @Test void validationSerializationAndUtf8SizeFailuresNeverEnterIoOrRetry() throws Exception {
        for(int invalid=0;invalid<3;invalid++) {
            Path run=Files.createDirectory(directory.resolve("preflight-"+invalid));Seed seed=seed(run);Faults io=new Faults(seed);
            List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(run,io,pauses::add);Profile changed=profile(5);
            ObservedTargets targets=null;
            if(invalid==0)changed.wineCycleDays=0;
            else {targets=new ObservedTargets(invalid==1,invalid==2?"가".repeat(700_000):"inert");changed.tomatoStorageTargets=targets;}
            if(invalid==0)assertThrows(IllegalArgumentException.class,()->store.save(seed.key(),changed));
            else if(invalid==1)assertThrows(IllegalStateException.class,()->store.save(seed.key(),changed));
            else {
                ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),changed));
                assertEquals("SIZE",failure.stage().name());assertEquals(1,failure.attempts());
            }
            assertEquals(new String[]{"VALIDATE","SERIALIZE","SIZE"}[invalid],store.lastDiagnostic().stage().name());
            assertFalse(store.lastDiagnostic().committed());assertEquals(1,store.lastDiagnostic().attempts());
            if(targets!=null)assertEquals(1,targets.reads,"An invalid serialized snapshot must not be recaptured");
            assertEquals(0,io.copyCalls+io.backupCalls+io.primaryCalls+io.cleanupCalls);assertTrue(pauses.isEmpty());
            assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));assertArrayEquals(seed.priorBackup(),Files.readAllBytes(seed.backup()));
            try(var files=Files.list(run)){assertEquals(2,files.count(),"No temporary file is created before validation, serialization and the UTF-8 byte limit pass");}
        }
    }

    @Test void interruptionStopsBeforeAnotherAttemptAndRetainsTheOriginalIoCause() throws Exception {
        Seed seed=seed();Faults io=new Faults(seed);io.failPrimary=Integer.MAX_VALUE;List<Long> pauses=new ArrayList<>();
        ProfileStore store=new ProfileStore(directory,io,millis->{pauses.add(millis);throw new InterruptedException("test retry interrupted");});
        try {
            ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),profile(5)));
            assertEquals("REPLACE",failure.stage().name());assertEquals(1,failure.attempts());
            assertInstanceOf(java.io.InterruptedIOException.class,failure.getCause());
            assertTrue(Thread.interrupted(),"The store restores the interrupt; the test clears it before reading its isolated file");
            assertTrue(Arrays.asList(failure.getCause().getSuppressed()).contains(io.primaryFailure));
            assertEquals(1,io.primaryCalls);assertEquals(List.of(10L),pauses);
            assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));assertFalse(store.lastDiagnostic().committed());
        } finally {Thread.interrupted();}
    }

    @Test void cleanupFailureAfterCommitIsACommittedDiagnosticNotAFalseOriginalPreservationFailure() throws Exception {
        Seed seed=seed();Faults io=new Faults(seed);io.cleanupFailure=new FileSystemException("test-temp",null,"cleanup denied");
        List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(directory,io,pauses::add);
        assertDoesNotThrow(()->store.save(seed.key(),profile(5)));
        assertEquals(5,store.load(seed.key()).lastSeenDay);assertEquals(1,io.primaryCalls);assertTrue(pauses.isEmpty());
        assertArrayEquals(seed.original(),Files.readAllBytes(seed.backup()));
        assertTrue(store.lastDiagnostic().committed());assertEquals("CLEANUP",store.lastDiagnostic().stage().name());
        assertTrue(store.lastDiagnostic().exceptionClass().endsWith("FileSystemException"));
        assertTrue(io.cleanupCalls>=1 && io.cleanupCalls<=2,"Cleanup is bounded independently of save retries");
    }

    @Test void cleanupFailureBeforeCommitDoesNotHideThePrimaryReplacementFailure() throws Exception {
        Seed seed=seed();Faults io=new Faults(seed);io.failPrimary=Integer.MAX_VALUE;
        io.cleanupFailure=new FileSystemException("test-temp",null,"secondary cleanup failure");
        List<Long> pauses=new ArrayList<>();ProfileStore store=new ProfileStore(directory,io,pauses::add);
        ProfileStore.Failure failure=assertThrows(ProfileStore.Failure.class,()->store.save(seed.key(),profile(5)));
        assertEquals("REPLACE",failure.stage().name());assertSame(io.primaryFailure,failure.getCause());
        assertEquals(3,io.primaryCalls);assertEquals(List.of(10L,10L),pauses);
        assertArrayEquals(seed.original(),Files.readAllBytes(seed.file()));assertFalse(store.lastDiagnostic().committed());
    }

    private Seed seed() throws Exception{return seed(directory);}
    private static Seed seed(Path directory) throws Exception {
        String key=ProfileStore.key("isolated-save-retry-test");ProfileStore store=new ProfileStore(directory);
        store.save(key,profile(3));store.save(key,profile(4));
        Path file=directory.resolve(key+".json"),backup=directory.resolve(key+".json.bak");
        return new Seed(key,file,backup,Files.readAllBytes(file),Files.readAllBytes(backup));
    }
    private static Profile profile(long day){Profile p=new Profile();p.lastSeenDay=day;return p;}
    private record Seed(String key,Path file,Path backup,byte[] original,byte[] priorBackup) { }
    private static final class ObservedTargets extends AbstractMap<String,Integer> {
        final boolean fail;final String key;int reads;
        ObservedTargets(boolean fail,String key){this.fail=fail;this.key=key;}
        @Override public Set<Entry<String,Integer>> entrySet(){reads++;if(fail)throw new IllegalStateException("serialization-only test failure");return Set.of(Map.entry(key,0));}
    }
    private static final class Faults implements ProfileStore.FileOperations {
        final Seed seed;int copyCalls,backupCalls,primaryCalls,cleanupCalls,failCopy,failBackup,failPrimary;boolean partialCopy;
        IOException copyFailure=new FileSystemException("test-backup-temp",null,"copy sharing violation");
        IOException backupFailure=new FileSystemException("test-backup",null,"backup sharing violation");
        IOException primaryFailure=new FileSystemException("test-profile",null,"replace sharing violation");
        IOException cleanupFailure;Runnable beforePrimary=()->{};
        final List<Path> primarySources=new ArrayList<>();final List<byte[]> primaryBytes=new ArrayList<>();
        Faults(Seed seed){this.seed=seed;}
        @Override public void copy(Path source,Path target) throws IOException {
            copyCalls++;if(copyCalls<=failCopy){if(partialCopy)Files.writeString(target,"partial backup temporary data");throw copyFailure;}
            ProfileStore.FileOperations.super.copy(source,target);
        }
        @Override public void replace(Path source,Path target) throws IOException {
            if(target.equals(seed.file())) {
                primaryCalls++;primarySources.add(source);primaryBytes.add(Files.readAllBytes(source));beforePrimary.run();
                if(primaryCalls<=failPrimary)throw primaryFailure;
            } else {
                assertEquals(seed.backup(),target);backupCalls++;if(backupCalls<=failBackup)throw backupFailure;
            }
            ProfileStore.FileOperations.super.replace(source,target);
        }
        @Override public boolean deleteIfExists(Path path) throws IOException {
            cleanupCalls++;if(cleanupFailure!=null)throw cleanupFailure;
            return ProfileStore.FileOperations.super.deleteIfExists(path);
        }
    }
}
