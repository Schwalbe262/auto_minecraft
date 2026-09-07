package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import dev.schwalbe.autovalley.core.Feature;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded explicit local workflow requests; existing runtime checks remain authoritative. */
public final class ClientControl {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private static final int MAX_BYTES = 1024;
    private static final long POLL_NANOS = 1_000_000_000L;
    private static long nextPoll;
    private static boolean errorReported;

    private ClientControl() { }

    /** Call only on the client thread; this method never sends raw input or edits a profile. */
    public static void tick(ClientRuntime runtime) {
        long now = System.nanoTime();
        if (!EmergencyStartGate.shouldPoll(runtime.automationStartBlocked(),now,nextPoll)) return;
        nextPoll = now + POLL_NANOS;
        boolean requestSettled=false;
        try {
            Path directory = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().resolve("autovalley");
            Path request = directory.resolve("control.request.json");
            // exists=false also means access is unknown. Only proven absence clears a stop fence.
            if (Files.notExists(request,LinkOption.NOFOLLOW_LINKS)) { requestSettled=true; return; }
            if (!Files.isRegularFile(request,LinkOption.NOFOLLOW_LINKS)) {
                logOnce("Non-regular control request ignored");
                return;
            }
            byte[] content = null;
            if (Files.size(request) < MAX_BYTES) {
                try (InputStream input = Files.newInputStream(request,LinkOption.NOFOLLOW_LINKS)) {
                    content = input.readNBytes(MAX_BYTES);
                }
            }
            // Consume exactly this explicit request before any gameplay state transition.
            if (!Files.deleteIfExists(request)) return;
            requestSettled=true;
            Request parsed;
            try {
                parsed = parseRequest(content);
            } catch (IOException | RuntimeException e) {
                logOnce("Malformed control request rejected");
                writeResult(directory,runtime,null,false,false,"Rejected: expected a supported command with only its required string fields in JSON under 1024 bytes");
                return;
            }
            try {
                if (EmergencyStartGate.rejectsCommand(runtime.automationStartBlocked(),parsed.command())) {
                    writeResult(directory,runtime,parsed,true,false,"Emergency stop priority; start request discarded");
                    return;
                }
                boolean acknowledged = switch (parsed.command()) {
                    case "start" -> {
                        if (!runtime.running()) runtime.toggle();
                        yield runtime.running();
                    }
                    case "pause" -> {
                        runtime.emergencyStop();
                        yield !runtime.running();
                    }
                    case "once" -> runtime.runOnce(parsed.feature());
                    case "haul_resolved" -> runtime.acknowledgeManualHaul();
                    case "record_start" -> {
                        // Never replace an unsaved recording, including one suspended by an I/O failure.
                        if (!runtime.recording()) runtime.startRecording();
                        yield runtime.recordingActive();
                    }
                    case "record_stop" -> {
                        runtime.stopRecording(parsed.name());
                        yield !runtime.recording();
                    }
                    default -> throw new IllegalStateException("Unsupported parsed command");
                };
                writeResult(directory,runtime,parsed,true,acknowledged,
                    acknowledged ? "Requested state confirmed" : "Runtime did not enter the requested state; inspect status");
            } catch (RuntimeException e) {
                logOnce("Runtime rejected local control request");
                writeResult(directory,runtime,parsed,true,false,"Runtime could not complete the requested state transition");
            }
        } catch (IOException | RuntimeException e) {
            logOnce("Local control I/O failed: " + e.getClass().getSimpleName());
        } finally {
            // Keep the old request fenced through I/O errors, non-regular paths and
            // unknown existence. Resolve only after confirmed absence or consumption.
            runtime.emergencyControlChecked(requestSettled);
        }
    }

    record Request(String command, Feature feature, String name) { }

    /** Retained for callers that only need the command; arguments still undergo full validation. */
    static String parseCommand(byte[] input) throws IOException {
        return parseRequest(input).command();
    }

    /** Streaming parsing preserves duplicate-field and strict-JSON rejection. */
    static Request parseRequest(byte[] input) throws IOException {
        if (input == null || input.length == 0 || input.length >= MAX_BYTES) throw new IOException("Invalid request size");
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input)).toString();
        rejectUnescapedControls(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Expected object");
            reader.beginObject();
            Map<String,String> fields = new LinkedHashMap<>();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (!Set.of("command","feature","name").contains(key) || fields.containsKey(key)
                    || reader.peek() != JsonToken.STRING) throw new IOException("Unknown, duplicate, or non-string field");
                fields.put(key,reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing input");
            String command = fields.get("command");
            if (command == null) throw new IOException("Missing command");
            return switch (command) {
                case "start", "pause", "record_start", "haul_resolved" -> {
                    if (!fields.keySet().equals(Set.of("command"))) throw new IOException("Unexpected command arguments");
                    yield new Request(command,null,null);
                }
                case "once" -> {
                    if (!fields.keySet().equals(Set.of("command","feature"))) throw new IOException("Expected only feature argument");
                    Feature feature;
                    try { feature = Feature.valueOf(fields.get("feature")); }
                    catch (IllegalArgumentException e) { throw new IOException("Unknown feature",e); }
                    yield new Request(command,feature,null);
                }
                case "record_stop" -> {
                    if (!fields.keySet().equals(Set.of("command","name"))) throw new IOException("Expected only recording name");
                    String name = fields.get("name");
                    int length = name.codePointCount(0,name.length());
                    if (length < 1 || length > 64 || name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
                        throw new IOException("Recording name must contain 1 to 64 nonblank characters");
                    yield new Request(command,null,name);
                }
                default -> throw new IOException("Unsupported command");
            };
        }
    }

    /** Gson's streaming reader can tolerate raw control characters even in non-lenient mode. */
    private static void rejectUnescapedControls(String json) throws IOException {
        boolean quoted=false,escaped=false;
        for (int i=0;i<json.length();i++) {
            char c=json.charAt(i);
            if (c<0x20 && (quoted || c!='\n' && c!='\r' && c!='\t')) throw new IOException("Unescaped JSON control character");
            if (quoted) {
                if (escaped) {
                    if ("\"\\/bfnrtu".indexOf(c)<0) throw new IOException("Invalid JSON string escape");
                    escaped=false;
                }
                else if (c=='\\') escaped=true;
                else if (c=='"') quoted=false;
            } else if (c=='"') quoted=true;
        }
    }

    private static void writeResult(Path directory, ClientRuntime runtime, Request request,
                                    boolean accepted, boolean acknowledged, String message) throws IOException {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("timestamp",Instant.now().toString());
        result.put("command",request==null ? null : request.command());
        result.put("feature",request==null || request.feature()==null ? null : request.feature().name());
        result.put("accepted",accepted);
        result.put("ack",acknowledged);
        result.put("running",runtime.running());
        result.put("executionMode",runtime.executionMode());
        result.put("recording",runtime.recording());
        result.put("recordingActive",runtime.recordingActive());
        result.put("recordingStatus",runtime.recordingStatus());
        result.put("status",runtime.status());
        result.put("message",message);
        Path temporary = Files.createTempFile(directory,"control-result-",".tmp");
        try {
            Files.writeString(temporary,GSON.toJson(result),StandardCharsets.UTF_8);
            Files.move(temporary,directory.resolve("control.result.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void logOnce(String message) {
        if (errorReported) return;
        errorReported = true;
        LogUtils.getLogger().warn("Auto Valley local control: {}",message);
    }
}
