package mmm.client;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mmm.network.DebugDiagnosticPayload;

/** Client-session diagnostics, with bounded storage for untrusted server data. */
final class DebugDiagnostics {
    private static final int MAX_EVENTS = 100, MAX_SNAPSHOTS = 128;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    record Row(String time, String source, String id, DebugDiagnosticPayload.Level level, String text, int repeats, long receivedNanos, DebugDiagnosticPayload.Snapshot snapshot) {
        boolean stale(long now) { return now - receivedNanos > 2_000_000_000L; }
    }
    private final ArrayDeque<Row> events = new ArrayDeque<>();
    private final Map<String, Row> snapshots = new LinkedHashMap<>();

    void accept(DebugDiagnosticPayload payload) {
        String time = LocalTime.now().format(TIME);
        var row = new Row(time, payload.source(), payload.id(), payload.level(), payload.text(), 1, System.nanoTime(), payload.snapshot());
        if (payload.kind() == DebugDiagnosticPayload.Kind.SNAPSHOT) {
            String key = payload.source() + "\u0000" + payload.id();
            if (!snapshots.containsKey(key) && snapshots.size() >= MAX_SNAPSHOTS)
                snapshots.remove(snapshots.keySet().iterator().next());
            snapshots.put(key, row);
        } else {
            Row last = events.peekLast();
            if (last != null && last.source().equals(row.source()) && last.id().equals(row.id()) && last.level() == row.level() && last.text().equals(row.text())) {
                events.removeLast();
                row = new Row(time, row.source(), row.id(), row.level(), row.text(), last.repeats() + 1, row.receivedNanos(), row.snapshot());
            }
            events.addLast(row);
            if (events.size() > MAX_EVENTS) events.removeFirst();
        }
    }
    List<Row> snapshots() { return List.copyOf(snapshots.values()); }
    List<Row> events() { return new ArrayList<>(events); }
    void clear() { snapshots.clear(); events.clear(); }
}
