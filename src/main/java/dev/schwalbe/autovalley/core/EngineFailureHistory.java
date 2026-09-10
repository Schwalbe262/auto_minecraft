package dev.schwalbe.autovalley.core;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded, connection-local observations only; never supplies scheduler or action authority. */
public final class EngineFailureHistory {
    public static final int LIMIT=16,MAX_MESSAGE_LENGTH=512;
    public enum Kind { BLOCKED, DEFERRED, PAUSED, ERROR }
    public record Entry(long tick,long lastTick,int occurrences,Feature feature,Kind state,String message) { }
    private record Key(Feature feature,Kind state,String message) { }
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>();
    private static final Set<String> ORDINARY_STOPS=Set.of("Checking current state","F8","OFF","Manual stop",
        "단축키로 일시정지","긴급 정지 — 다시 시작하려면 F8","직접 조작을 감지해 일시정지했습니다.",
        "설정 중","기능 설정 변경","직접 플레이 기록 준비","한 번 실행 준비","좌표 이동 준비",
        "산출물 수동 처리 확인","수확 속도 측정을 예약했습니다.","이동 경유지 등록");
    private static final Pattern STRUCTURED=Pattern.compile("\\b(?:Pos|BlockPos|ItemData)\\[[^\\]]*\\]");
    private static final Pattern COORDINATES=Pattern.compile("(?<![\\w.])[-+]?\\d+(?:\\.\\d+)?\\s*,\\s*[-+]?\\d+(?:\\.\\d+)?\\s*,\\s*[-+]?\\d+(?:\\.\\d+)?(?![\\w.])");
    private static final Pattern POSITION_KEY=Pattern.compile("(?<![\\w:])-?\\d+:-?\\d+:-?\\d+(?![\\w:])");
    private static final Pattern DETAILS=Pattern.compile("(?i)\\b[a-z0-9_.-]+:[a-z0-9_./-]+\\b|\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b|[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PATH_OR_URL=Pattern.compile("(?i)(?:https?://|[a-z]:[\\\\/])[^\\s]+|/(?:home|Users|mnt)/[^\\s]+");

    public void recordWork(long tick,Feature feature,WorkResult result) {
        if(result==null)return;
        if(result.state()==WorkResult.State.BLOCKED) add(tick,feature,Kind.BLOCKED,result.message());
        else if(result.state()==WorkResult.State.DEFERRED) add(tick,feature,Kind.DEFERRED,result.message());
    }
    public void recordStop(long tick,Feature feature,AutomationEngine.State state,String reason) {
        if(state!=AutomationEngine.State.PAUSED && state!=AutomationEngine.State.ERROR || reason==null
            || reason.isBlank() || ORDINARY_STOPS.contains(reason.strip()))return;
        add(tick,feature,state==AutomationEngine.State.ERROR ? Kind.ERROR : Kind.PAUSED,reason);
    }
    private void add(long tick,Feature feature,Kind kind,String message) {
        String safe=message(message);if(safe.isBlank())return;
        Key key=new Key(feature,kind,safe);Entry prior=entries.remove(key);
        entries.put(key,new Entry(prior==null ? tick : prior.tick(),tick,
            prior==null ? 1 : prior.occurrences()==Integer.MAX_VALUE ? Integer.MAX_VALUE : prior.occurrences()+1,feature,kind,safe));
        if(entries.size()>LIMIT)entries.remove(entries.keySet().iterator().next());
    }
    /** Known structured locations, native item IDs and account/path details are not retained in history. */
    static String message(String raw) {
        if(raw==null)return "";
        String text=raw.length()>4096 ? raw.substring(0,4096) : raw;
        text=STRUCTURED.matcher(text).replaceAll("[detail]");
        text=COORDINATES.matcher(text).replaceAll("[position]");
        text=POSITION_KEY.matcher(text).replaceAll("[position]");
        text=PATH_OR_URL.matcher(text).replaceAll("[path]");
        text=DETAILS.matcher(text).replaceAll("[detail]");
        text=text.replaceAll("[\\p{Cntrl}\\s]+"," ").strip();
        return text.length()>MAX_MESSAGE_LENGTH ? text.substring(0,MAX_MESSAGE_LENGTH-1)+"…" : text;
    }
    /** An immutable oldest-to-newest snapshot; a repeat updates its last tick/count without adding a slot. */
    public List<Entry> snapshot() { return List.copyOf(entries.values()); }
    public void clear() { entries.clear(); }
}
