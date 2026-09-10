package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class ProfileStore {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final FileOperations files;
    private final RetryPause retryPause;
    private Diagnostic lastDiagnostic;
    public enum Stage { VALIDATE, SERIALIZE, SIZE, DIRECTORY, TEMP_CREATE, TEMP_WRITE, BACKUP_COPY, BACKUP_REPLACE, REPLACE, CLEANUP }
    public record Diagnostic(Stage stage,String exceptionClass,int attempts,boolean committed) { }
    public static final class Failure extends IOException {
        private final Stage stage;
        private final int attempts;
        Failure(Stage stage,int attempts,IOException cause) {
            super("Profile persistence " + stage + " failed (" + cause.getClass().getSimpleName() + ")",cause);
            this.stage=stage;this.attempts=attempts;
        }
        public Stage stage() { return stage; }
        public int attempts() { return attempts; }
    }
    interface FileOperations {
        default void copy(Path source,Path target) throws IOException { Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING); }
        default void replace(Path source,Path target) throws IOException {
            Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }
        default boolean deleteIfExists(Path path) throws IOException { return Files.deleteIfExists(path); }
    }
    @FunctionalInterface interface RetryPause { void pause(long millis) throws InterruptedException; }
    @FunctionalInterface private interface IoOperation { void run() throws IOException; }
    private static final class RetryBudget { int retries; }
    public ProfileStore(Path directory) { this(directory,new FileOperations() { },Thread::sleep); }
    ProfileStore(Path directory,FileOperations files,RetryPause retryPause) {
        this.directory=Objects.requireNonNull(directory);this.files=Objects.requireNonNull(files);this.retryPause=Objects.requireNonNull(retryPause);
    }
    /** No profile contents or filesystem paths are included in this summary. */
    public Diagnostic lastDiagnostic() { return lastDiagnostic; }
    public static String key(String identity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0,24); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Profile load(String key) throws IOException {
        Path file=path(key);
        if (!Files.exists(file)) return new Profile();
        if (Files.size(file)>2_000_000) throw new IOException("Profile is too large");
        try {
            String json=Files.readString(file,StandardCharsets.UTF_8);
            // Keep stream deserialization's strict integer handling; a JsonElement
            // conversion would silently truncate malformed fractional settings.
            Profile profile=GSON.fromJson(json,Profile.class);
            var timingVersion=com.google.gson.JsonParser.parseString(json).getAsJsonObject().get("strictHarvestTimingVersion");
            if(timingVersion==null || timingVersion.isJsonNull())
                profile.strictHarvestTimingVersion=0;
            validate(profile);
            CrystalCollectionMigration.migrate(profile);
            validate(profile);
            return profile;
        } catch (RuntimeException e) { throw new IOException("Profile is invalid; original file has been preserved",e); }
    }
    public void save(String key,Profile profile) throws IOException {
        lastDiagnostic=null;
        try { validate(profile); }
        catch (RuntimeException failure) { diagnostic(Stage.VALIDATE,failure,1,false);throw failure; }
        Path file=path(key);
        final byte[] serialized;
        try { serialized=GSON.toJson(profile).getBytes(StandardCharsets.UTF_8); }
        catch (RuntimeException failure) { diagnostic(Stage.SERIALIZE,failure,1,false);throw failure; }
        if (serialized.length>2_000_000) throw failure(Stage.SIZE,new IOException("Profile is too large; previous file preserved"),1);
        runOnce(Stage.DIRECTORY,() -> Files.createDirectories(directory));
        Path temp=createTemp(key),backupTemp=null;
        boolean committed=false;
        Throwable primary=null;
        RetryBudget budget=new RetryBudget();
        try {
            // Serialize/write once. Every retry moves these same bytes; it never
            // recaptures a possibly changed live checkpoint or repeats a game action.
            runOnce(Stage.TEMP_WRITE,() -> Files.write(temp,serialized));
            if (Files.exists(file)) {
                backupTemp=createTemp(key+".bak");
                Path preparedBackup=backupTemp;
                retry(Stage.BACKUP_COPY,() -> files.copy(file,preparedBackup),budget);
                retry(Stage.BACKUP_REPLACE,() -> files.replace(preparedBackup,directory.resolve(key+".json.bak")),budget);
            } else if (!Files.notExists(file)) {
                throw failure(Stage.BACKUP_COPY,new AccessDeniedException("Profile existence is unknown"),1);
            }
            // No non-atomic fallback: a refused replacement cannot destroy the
            // original, and the previous contents have a complete atomic backup.
            retry(Stage.REPLACE,() -> files.replace(temp,file),budget);
            committed=true;
        } catch (IOException | RuntimeException failure) {
            primary=failure;throw failure;
        } finally {
            cleanup(temp,committed,primary);
            if (backupTemp!=null) cleanup(backupTemp,committed,primary);
        }
    }
    private Path createTemp(String prefix) throws IOException {
        try { return Files.createTempFile(directory,prefix,".tmp"); }
        catch (IOException failure) { throw failure(Stage.TEMP_CREATE,failure,1); }
        catch (RuntimeException failure) { diagnostic(Stage.TEMP_CREATE,failure,1,false);throw failure; }
    }
    private void runOnce(Stage stage,IoOperation operation) throws IOException {
        try { operation.run(); }
        catch (IOException failure) { throw failure(stage,failure,1); }
        catch (RuntimeException failure) { diagnostic(stage,failure,1,false);throw failure; }
    }
    private void retry(Stage stage,IoOperation operation,RetryBudget budget) throws IOException {
        for (int attempt=1;;attempt++) {
            try { operation.run();return; }
            catch (IOException failure) {
                // Two additional attempts are shared by ALL backup/replacement
                // stages of this save, with at most 20 ms of requested delay.
                if (!potentiallyTransient(failure) || budget.retries>=2 || Thread.currentThread().isInterrupted())
                    throw failure(stage,failure,attempt);
                budget.retries++;
                try { retryPause.pause(10); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException stopped=new InterruptedIOException("Profile persistence retry interrupted");
                    stopped.initCause(interrupted);stopped.addSuppressed(failure);
                    throw failure(stage,stopped,attempt);
                }
            } catch (RuntimeException failure) { diagnostic(stage,failure,attempt,false);throw failure; }
        }
    }
    private static boolean potentiallyTransient(IOException failure) {
        return failure instanceof FileSystemException && !(failure instanceof AtomicMoveNotSupportedException)
            && !(failure instanceof NoSuchFileException) && !(failure instanceof FileAlreadyExistsException)
            && !(failure instanceof DirectoryNotEmptyException) && !(failure instanceof NotDirectoryException);
    }
    private Failure failure(Stage stage,IOException cause,int attempts) {
        diagnostic(stage,cause,attempts,false);return new Failure(stage,attempts,cause);
    }
    private void diagnostic(Stage stage,Throwable cause,int attempts,boolean committed) {
        lastDiagnostic=new Diagnostic(stage,cause.getClass().getSimpleName(),attempts,committed);
    }
    private void cleanup(Path path,boolean committed,Throwable primary) {
        try { files.deleteIfExists(path); }
        catch (IOException | RuntimeException cleanupFailure) {
            if (primary!=null) primary.addSuppressed(cleanupFailure);
            else diagnostic(Stage.CLEANUP,cleanupFailure,1,committed);
            // A cleanup problem after successful atomic replacement is not a
            // failed checkpoint. Keep only its bounded, content-free diagnostic.
            System.getLogger(ProfileStore.class.getName()).log(System.Logger.Level.WARNING,
                "Profile temporary cleanup failed: " + cleanupFailure.getClass().getSimpleName() + "; committed=" + committed);
        }
    }
    private Path path(String key) {
        if (!key.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("Invalid profile key");
        return directory.resolve(key+".json");
    }
    public static void validate(Profile profile) {
        if (profile==null || profile.schemaVersion<1 || profile.schemaVersion>7 || profile.pois==null || profile.farms==null || profile.enabled==null
            || profile.nextEligibleDay==null || profile.disposalDirections==null) throw new IllegalArgumentException("Unsupported or incomplete profile");
        if(profile.strictHarvestTimingVersion<0 || profile.strictHarvestTimingVersion>1)
            throw new IllegalArgumentException("Unsupported harvest timing policy");
        if (profile.pois.size()>4096) throw new IllegalArgumentException("Too many registered locations");
        Set<String> farms=new HashSet<>();
        for (Farm farm:profile.farms) {
            if (farm==null || farm.first()==null || farm.second()==null || farm.name()==null || farm.name().isBlank()
                || farm.volume()>32768 || !farms.add(farm.name())) throw new IllegalArgumentException("Invalid farm bounds or duplicate name");
        }
        Set<Pos> positions=new HashSet<>();
        for (Poi poi:profile.pois) {
            if (poi==null || poi.pos()==null || poi.kind()==null || poi.label()==null || !positions.add(poi.pos())) throw new IllegalArgumentException("Invalid or duplicate location");
            // Tomato classifiers are inert legacy labels, regardless of value.
            // New registrations write null; no tomato grade can block profile load.
            if (poi.kind()==PoiKind.WINE_CHEST && (poi.classifier()==null || poi.classifier()<0)) throw new IllegalArgumentException("Wine production year is required");
            if (poi.kind()==PoiKind.STORAGE_CANDIDATE && poi.classifier()!=null) throw new IllegalArgumentException("Unclassified storage must not have a guessed classifier");
        }
        if (profile.hoeHotbarSlot<0 || profile.hoeHotbarSlot>8 || profile.harvestCycleDays<1 || profile.harvestCycleDays>28
            || profile.wineCycleDays<1 || profile.wineCycleDays>28 || profile.preservesCycleDays<1 || profile.preservesCycleDays>28
            || profile.corridorRadius<1 || profile.corridorRadius>8 || profile.interactionTimeoutTicks<20 || profile.interactionTimeoutTicks>600
            || profile.sleepAtTick<12000 || profile.sleepAtTick>23000
            || profile.tomatoStorageLimitPercent<1 || profile.tomatoStorageLimitPercent>100
            || profile.tomatoStockRefreshDays<1 || profile.tomatoStockRefreshDays>28) throw new IllegalArgumentException("Profile settings outside supported range");
        for (var entry:profile.nextEligibleDay.entrySet()) if (entry.getKey()==null || entry.getValue()==null || entry.getValue()<0) throw new IllegalArgumentException("Invalid scheduled date");
        for (Look look:profile.disposalDirections.values()) if (look==null || !Float.isFinite(look.yaw()) || !Float.isFinite(look.pitch()) || Math.abs(look.pitch())>90) throw new IllegalArgumentException("Invalid disposal direction");
        // Obsolete desired-grade layouts are inert compatibility data, not a
        // migration requirement or a reason to block otherwise valid settings.
        MachineGroupRules.validate(profile);
        WineBatchRules.validate(profile);
        MachineOutputLedger.validate(profile);
        LoggingRules.validate(profile);
        HotbarLease workLease=profile.workHotbarLease;
        if (workLease!=null && (!workLease.valid() || workLease.hotbarSlot()==profile.hoeHotbarSlot
                || workLease.hotbarSlot()==profile.loggingAxeHotbarSlot || profile.loggingHotbarLease!=null))
            throw new IllegalArgumentException("Invalid or overlapping work hotbar restoration obligation");
        CoordinateDestinationRules.validate(profile);
        AdditionalWorkRules.validate(profile);
        WineProductionRules.validate(profile);
        for (Feature feature:Feature.values()) profile.enabled.putIfAbsent(feature,feature.defaultEnabled());
        // Older clients must not silently lose crop/store/job definitions, navigation
        // drafts or logging obligations. A work lease requires schema 7 so an older
        // client cannot silently discard its custody record. Do not downgrade after
        // restoration; unchanged legacy profiles remain schema 6. Load never rewrites disk.
        profile.schemaVersion=Math.max(profile.schemaVersion,workLease==null ? 6 : 7);
    }
}
