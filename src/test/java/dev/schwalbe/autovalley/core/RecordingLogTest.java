package dev.schwalbe.autovalley.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecordingLogTest {
    @TempDir Path directory;
    @Test void savesUtf8NamedEventsInOrderAndDoesNotOverwriteSameName() throws Exception {
        RecordingLog log=new RecordingLog(directory);
        log.start(10,Map.of("dimension","minecraft:overworld"));
        log.event("use_block_intent",11,Map.of("id","society:wine_keg"));
        Path first=log.save("와인통 채우기",12);
        assertFalse(log.active()); assertFalse(log.pending());
        var rows=Files.readAllLines(first,StandardCharsets.UTF_8);
        assertEquals(4,rows.size());
        for (int n=0;n<rows.size();n++) assertEquals(n,JsonParser.parseString(rows.get(n)).getAsJsonObject().get("sequence").getAsInt());
        assertTrue(rows.get(3).contains("와인통 채우기"));
        log.start(20,Map.of()); Path second=log.save("와인통 채우기",21);
        assertNotEquals(first,second); assertTrue(Files.exists(first));
    }
    @Test void flushMakesRecoverablePendingJournalAndNewStartCannotDiscardIt() throws Exception {
        RecordingLog log=new RecordingLog(directory);
        log.start(1,Map.of()); log.event("inventory_observed",2,Map.of("tomatoes",64)); log.flush();
        Path pending=log.pendingPath(); assertEquals(2,Files.readAllLines(pending).size());
        assertThrows(IllegalStateException.class,() -> log.start(3,Map.of()));
        log.stop(4,"disconnected"); assertFalse(log.active()); assertTrue(log.pending());
        Path saved=log.save("연결 종료 후 저장",5);
        assertTrue(Files.exists(saved)); assertFalse(Files.exists(pending));
    }
    @Test void boundedCaptureStopsAndRemainsAvailableForNaming() throws Exception {
        RecordingLog log=new RecordingLog(directory,3);
        log.start(0,Map.of()); log.event("one",1,Map.of()); log.event("two",2,Map.of());
        log.event("overflow",3,Map.of());
        assertFalse(log.active()); assertTrue(log.pending()); assertEquals("size_limit",log.stopReason());
        log.event("ignored",4,Map.of());
        String contents=Files.readString(log.save("bounded",5));
        assertFalse(contents.contains("overflow")); assertFalse(contents.contains("ignored"));
        assertTrue(contents.contains("size_limit"));
    }
    @Test void unsafeNamesStayInsideDirectoryAndInvalidNamePreservesCapture() throws Exception {
        RecordingLog log=new RecordingLog(directory); log.start(1,Map.of());
        assertThrows(IllegalArgumentException.class,() -> log.save("   ",2));
        assertTrue(log.active()); assertTrue(log.pending());
        Path target=log.save("../../CON:*? 와인",3);
        assertEquals(directory.toAbsolutePath().normalize(),target.getParent());
        assertFalse(target.getFileName().toString().contains(":"));
        assertEquals(64,RecordingLog.cleanName("한".repeat(100)).codePointCount(0,64));
    }
    @Test void failedRenamePreservesJournalForRetry() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        RecordingLog log=new RecordingLog(directory,100,RecordingLog.MAX_BYTES,(source,target) -> {
            if (attempts.getAndIncrement()==0) Files.move(source,directory.resolve("missing-parent").resolve(target.getFileName()));
            else Files.move(source,target);
        });
        log.start(1,Map.of()); log.event("keep_gameplay",2,Map.of("count",64)); log.flush();
        Path source=log.pendingPath(); String captured=Files.readString(source);
        assertThrows(IOException.class,() -> log.save("첫 번째 이름",3));
        assertTrue(Files.exists(source)); assertTrue(log.pending());
        assertFalse(log.active()); assertTrue(Files.readString(source).startsWith(captured));
        Path saved=log.save("복구 이름",4);
        String recovered=Files.readString(saved);
        assertTrue(recovered.startsWith(captured)); assertTrue(recovered.contains("복구 이름"));
        assertFalse(recovered.contains("첫 번째 이름"));
        assertFalse(log.pending()); assertFalse(Files.exists(source));
        var rows=Files.readAllLines(saved);
        for (int n=0;n<rows.size();n++) assertEquals(n,JsonParser.parseString(rows.get(n)).getAsJsonObject().get("sequence").getAsInt());
    }
    @Test void disconnectedAndLimitedSessionsStillReceiveTheChosenName() throws Exception {
        for (String reason:new String[]{"disconnected","one_hour_limit","size_limit"}) {
            RecordingLog log=new RecordingLog(directory);
            log.start(0,Map.of()); log.stop(1,reason);
            var rows=Files.readAllLines(log.save("자동 정지 이후 이름",2));
            assertEquals(3,rows.size());
            var named=JsonParser.parseString(rows.get(2)).getAsJsonObject();
            assertEquals("recording_name",named.get("type").getAsString());
            assertEquals("자동 정지 이후 이름",named.getAsJsonObject("data").get("name").getAsString());
            assertEquals(reason,log.stopReason());
        }
    }
    @Test void oversizeUtf8PayloadIsRejectedBeforeWritingWithBoundedStopAndNameMetadata() throws Exception {
        long payloadLimit=512;
        RecordingLog log=new RecordingLog(directory,100,payloadLimit,Files::move);
        log.start(0,Map.of()); log.event("small_event",1,Map.of("text","한글"));
        log.event("oversize_event",2,Map.of("text","한".repeat(512)));
        assertFalse(log.active()); assertEquals("size_limit",log.stopReason());
        Path saved=log.save("한".repeat(64),3);
        long payloadBytes=Files.readAllLines(saved,StandardCharsets.UTF_8).stream().filter(row -> {
            String type=JsonParser.parseString(row).getAsJsonObject().get("type").getAsString();
            return !type.equals("recording_stop") && !type.equals("recording_name");
        }).mapToLong(row -> (row+"\n").getBytes(StandardCharsets.UTF_8).length).sum();
        assertTrue(payloadBytes<=payloadLimit); assertTrue(Files.size(saved)<=payloadLimit+RecordingLog.MAX_METADATA_BYTES);
        assertFalse(Files.readString(saved).contains("oversize_event"));
        assertTrue(Files.readString(saved).contains("small_event"));
    }
    @Test void repeatedActualRenameFailuresReplaceOnlyBoundedNamingTail() throws Exception {
        AtomicInteger attempts=new AtomicInteger();
        RecordingLog log=new RecordingLog(directory,100,512,(source,target) -> {
            if (attempts.getAndIncrement()<32) Files.move(source,directory.resolve("missing-parent").resolve(target.getFileName()));
            else Files.move(source,target);
        });
        log.start(0,Map.of()); log.stop(1,"disconnected");
        String gameplayAndStop=Files.readString(log.pendingPath());
        for (int n=0;n<32;n++) {
            final int attempt=n;
            assertThrows(IOException.class,() -> log.save("한".repeat(60)+attempt,attempt+2));
            assertTrue(Files.readString(log.pendingPath()).startsWith(gameplayAndStop));
            assertTrue(Files.size(log.pendingPath())<=512+RecordingLog.MAX_METADATA_BYTES);
            assertEquals(3,Files.readAllLines(log.pendingPath()).size());
        }
        Path saved=log.save("최종 복구 이름",34);
        assertEquals(3,Files.readAllLines(saved).size());
        assertTrue(Files.readString(saved).endsWith("\n"));
        assertTrue(Files.readString(saved).contains("최종 복구 이름"));
    }
    @Test void oversizedInitialStateDoesNotCreateAnUnboundedPendingFile() {
        RecordingLog log=new RecordingLog(directory.resolve("not-created"),100,128,Files::move);
        assertThrows(IOException.class,() -> log.start(0,Map.of("text","한".repeat(512))));
        assertFalse(log.active()); assertFalse(log.pending());
        assertFalse(Files.exists(directory.resolve("not-created")));
    }
}
