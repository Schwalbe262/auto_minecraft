package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit local start/pause requests; all existing runtime checks remain authoritative. */
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
        if (now < nextPoll) return;
        nextPoll = now + POLL_NANOS;
        try {
            Path directory = FMLPaths.CONFIGDIR.get().toAbsolutePath().normalize().resolve("autovalley");
            Path request = directory.resolve("control.request.json");
            if (!Files.exists(request,LinkOption.NOFOLLOW_LINKS)) return;
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
            String command;
            try {
                command = parseCommand(content);
            } catch (IOException | RuntimeException e) {
                logOnce("Malformed control request rejected");
                writeResult(directory,runtime,null,false,false,"Rejected: expected only a start or pause command in JSON under 1024 bytes");
                return;
            }
            try {
                if (command.equals("start")) {
                    if (!runtime.running()) runtime.toggle();
                } else {
                    runtime.emergencyStop();
                }
                boolean acknowledged = command.equals("start") ? runtime.running() : !runtime.running();
                writeResult(directory,runtime,command,true,acknowledged,
                    acknowledged ? "Requested state confirmed" : "Runtime did not enter the requested state; inspect status");
            } catch (RuntimeException e) {
                logOnce("Runtime rejected local control request");
                writeResult(directory,runtime,command,true,false,"Runtime could not complete the requested state transition");
            }
        } catch (IOException | RuntimeException e) {
            logOnce("Local control I/O failed: " + e.getClass().getSimpleName());
        }
    }

    /** Streaming parsing preserves duplicate-field and strict-JSON rejection. */
    static String parseCommand(byte[] input) throws IOException {
        if (input == null || input.length == 0 || input.length >= MAX_BYTES) throw new IOException("Invalid request size");
        String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Expected object");
            reader.beginObject();
            if (!reader.hasNext() || !reader.nextName().equals("command") || reader.peek() != JsonToken.STRING)
                throw new IOException("Expected command string");
            String command = reader.nextString();
            if (!command.equals("start") && !command.equals("pause")) throw new IOException("Unsupported command");
            if (reader.hasNext()) throw new IOException("Extra or duplicate field");
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing input");
            return command;
        }
    }

    private static void writeResult(Path directory, ClientRuntime runtime, String command,
                                    boolean accepted, boolean acknowledged, String message) throws IOException {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("timestamp",Instant.now().toString());
        result.put("command",command);
        result.put("accepted",accepted);
        result.put("ack",acknowledged);
        result.put("running",runtime.running());
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
