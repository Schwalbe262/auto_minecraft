package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.schwalbe.autovalley.core.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class ProfileStore {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    public ProfileStore(Path directory) { this.directory=directory; }
    public static String key(String identity) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0,24); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public Profile load(String key) throws IOException {
        Path file=path(key);
        if (!Files.exists(file)) return new Profile();
        if (Files.size(file)>2_000_000) throw new IOException("Profile is too large");
        try {
            Profile profile=GSON.fromJson(Files.readString(file,StandardCharsets.UTF_8),Profile.class);
            validate(profile);
            return profile;
        } catch (RuntimeException e) { throw new IOException("Profile is invalid; original file has been preserved",e); }
    }
    public void save(String key,Profile profile) throws IOException {
        validate(profile);
        Files.createDirectories(directory);
        Path file=path(key), temp=Files.createTempFile(directory,key,".tmp");
        try {
            Files.writeString(temp,GSON.toJson(profile),StandardCharsets.UTF_8);
            if (Files.size(temp)>2_000_000) throw new IOException("Profile is too large; previous file preserved");
            if (Files.exists(file)) Files.copy(file,directory.resolve(key+".json.bak"),StandardCopyOption.REPLACE_EXISTING);
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    private Path path(String key) {
        if (!key.matches("[a-f0-9]{24}")) throw new IllegalArgumentException("Invalid profile key");
        return directory.resolve(key+".json");
    }
    public static void validate(Profile profile) {
        if (profile==null || profile.schemaVersion<1 || profile.schemaVersion>5 || profile.pois==null || profile.farms==null || profile.enabled==null
            || profile.nextEligibleDay==null || profile.disposalDirections==null) throw new IllegalArgumentException("Unsupported or incomplete profile");
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
            || profile.sleepAtTick<12000 || profile.sleepAtTick>23000) throw new IllegalArgumentException("Profile settings outside supported range");
        for (var entry:profile.nextEligibleDay.entrySet()) if (entry.getKey()==null || entry.getValue()==null || entry.getValue()<0) throw new IllegalArgumentException("Invalid scheduled date");
        for (Look look:profile.disposalDirections.values()) if (look==null || !Float.isFinite(look.yaw()) || !Float.isFinite(look.pitch()) || Math.abs(look.pitch())>90) throw new IllegalArgumentException("Invalid disposal direction");
        // Obsolete desired-grade layouts are inert compatibility data, not a
        // migration requirement or a reason to block otherwise valid settings.
        MachineGroupRules.validate(profile);
        WineBatchRules.validate(profile);
        MachineOutputLedger.validate(profile);
        LoggingRules.validate(profile);
        CoordinateDestinationRules.validate(profile);
        AdditionalWorkRules.validate(profile);
        for (Feature feature:Feature.values()) profile.enabled.putIfAbsent(feature,feature.defaultEnabled());
        // Older clients must not silently lose crop/store/job definitions, navigation
        // drafts or logging obligations. Upgrade only after validation; load never rewrites disk.
        profile.schemaVersion=5;
    }
}
