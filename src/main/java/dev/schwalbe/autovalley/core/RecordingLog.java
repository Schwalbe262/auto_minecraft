package dev.schwalbe.autovalley.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded local JSONL journal. Gameplay data is allowlisted by the caller, never raw packets/NBT. */
public final class RecordingLog {
    private static final Gson GSON=new GsonBuilder().serializeNulls().create();
    public static final int MAX_EVENTS=100_000;
    /** Strict gameplay payload cap, including the initial state; stop/name records use the allowance below. */
    public static final long MAX_BYTES=32L*1024*1024;
    /** Total extra bytes permitted for one stop record and the final (replaceable) name record. */
    public static final int MAX_METADATA_BYTES=4096;
    @FunctionalInterface interface FileMover { void move(Path source,Path target) throws IOException; }
    private final Path directory;
    private final int eventLimit;
    private final long byteLimit;
    private final FileMover mover;
    private Path pending;
    private BufferedWriter writer;
    private int events;
    private long payloadBytes, metadataBytes, namingOffset=-1;
    private int namingSequence=-1;
    private String stopReason;
    public RecordingLog(Path directory) { this(directory,MAX_EVENTS); }
    RecordingLog(Path directory,int eventLimit) { this(directory,eventLimit,MAX_BYTES,Files::move); }
    RecordingLog(Path directory,int eventLimit,long byteLimit,FileMover mover) {
        if (eventLimit<1 || byteLimit<1) throw new IllegalArgumentException("Recording limits must be positive");
        this.directory=directory.toAbsolutePath().normalize(); this.eventLimit=eventLimit; this.byteLimit=byteLimit; this.mover=mover;
    }
    public boolean active() { return writer!=null; }
    public boolean pending() { return pending!=null; }
    public int events() { return events; }
    public String stopReason() { return stopReason; }
    public Path pendingPath() { return pending; }
    public void start(long tick,Object initialState) throws IOException {
        if (pending()) throw new IllegalStateException("Save the previous recording first");
        String initial=serialize(0,"recording_start",tick,Map.of("schemaVersion",1,"startedAt",Instant.now().toString(),"initialState",initialState));
        if (encodedLength(initial)>byteLimit) throw new IOException("Initial recording state exceeds the payload limit");
        Files.createDirectories(directory);
        Path path=Files.createTempFile(directory,"pending-",".jsonl");
        pending=path; events=0; payloadBytes=0; metadataBytes=0; namingOffset=-1; namingSequence=-1; stopReason=null;
        writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        write(initial); payloadBytes=encodedLength(initial);
        flush();
    }
    public void event(String type,long tick,Object data) throws IOException {
        if (!active()) return;
        if (events>=eventLimit) { stop(tick,"size_limit"); return; }
        String line=serialize(events,type,tick,data);
        int length=encodedLength(line);
        if (length>byteLimit-payloadBytes) { stop(tick,"size_limit"); return; }
        write(line); payloadBytes+=length;
    }
    private static String serialize(int sequence,String type,long tick,Object data) {
        Map<String,Object> event=new LinkedHashMap<>();
        event.put("sequence",sequence); event.put("tick",tick); event.put("type",type); event.put("data",data);
        return GSON.toJson(event)+"\n";
    }
    private static int encodedLength(String line) { return line.getBytes(StandardCharsets.UTF_8).length; }
    private void write(String line) throws IOException { writer.write(line); events++; }
    public void flush() throws IOException { if (writer!=null) writer.flush(); }
    public void stop(long tick,String reason) throws IOException {
        if (writer==null) return;
        stopReason=reason;
        try {
            String line=serialize(events,"recording_stop",tick,Map.of("reason",reason));
            int length=encodedLength(line);
            if (length>MAX_METADATA_BYTES-metadataBytes) throw new IOException("Recording metadata exceeds its limit");
            write(line); metadataBytes+=length;
        }
        finally { BufferedWriter previous=writer; writer=null; previous.close(); }
    }
    public Path save(String requestedName,long tick) throws IOException {
        String name=cleanName(requestedName);
        if (pending==null) throw new IllegalStateException("No recording to save");
        if (active()) stop(tick,"saved_by_user");
        writeName(name,tick);
        Path target=directory.resolve("recording-"+name+"-"+UUID.randomUUID()+".jsonl").normalize();
        if (!target.getParent().equals(directory)) throw new IOException("Invalid recording path");
        // Unique destination; no overwrite and no removal of the recoverable pending file on failure.
        mover.move(pending,target);
        pending=null;
        return target;
    }
    private void writeName(String name,long tick) throws IOException {
        if (namingOffset<0) { namingOffset=Files.size(pending); namingSequence=events; }
        byte[] line=serialize(namingSequence,"recording_name",tick,Map.of("name",name)).getBytes(StandardCharsets.UTF_8);
        if (line.length>MAX_METADATA_BYTES-metadataBytes) throw new IOException("Recording metadata exceeds its limit");
        try (FileChannel channel=FileChannel.open(pending,StandardOpenOption.WRITE)) {
            if (channel.size()<namingOffset) throw new IOException("The pending recording was changed externally");
            // Retries replace ONLY our final naming metadata; no captured gameplay bytes are removed.
            channel.truncate(namingOffset); channel.position(namingOffset);
            ByteBuffer buffer=ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(false);
        }
        events=namingSequence+1;
    }
    public static String cleanName(String value) {
        if (value==null || value.isBlank()) throw new IllegalArgumentException("기록 이름을 입력하세요.");
        String result=value.strip().replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]","_").replaceAll("[. ]+$","");
        if (result.codePointCount(0,result.length())>64) result=result.substring(0,result.offsetByCodePoints(0,64));
        if (result.isBlank() || result.equals(".") || result.equals("..")) throw new IllegalArgumentException("다른 기록 이름을 입력하세요.");
        return result;
    }
}
