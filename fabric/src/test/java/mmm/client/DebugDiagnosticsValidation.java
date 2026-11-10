package mmm.client;

import mmm.network.DebugDiagnosticPayload;
import static mmm.network.DebugDiagnosticPayload.Kind.*;
import static mmm.network.DebugDiagnosticPayload.Level.*;

/** Exercises the client session's bounded event and replacement-reading state. */
public final class DebugDiagnosticsValidation {
    public static void main(String[] args) { run(); }
    static void run() {
        var data = new DebugDiagnostics();
        data.accept(new DebugDiagnosticPayload(EVENT, INFO, "minecraft:overworld", "solver", "step", "ready"));
        data.accept(new DebugDiagnosticPayload(EVENT, INFO, "minecraft:overworld", "solver", "step", "ready"));
        check(data.events().size() == 1 && data.events().getFirst().repeats() == 2, "repeat coalescing");
        for (int i = 0; i < 120; i++)
            data.accept(new DebugDiagnosticPayload(EVENT, WARN, "minecraft:overworld", "solver", "step", "event " + i));
        check(data.events().size() == 100 && data.events().getFirst().text().equals("event 20"), "bounded events");
        data.accept(new DebugDiagnosticPayload(SNAPSHOT, INFO, "minecraft:overworld", "solver", "region", "first"));
        data.accept(new DebugDiagnosticPayload(SNAPSHOT, INFO, "minecraft:overworld", "solver", "region", "updated"));
        check(data.snapshots().size() == 1 && data.snapshots().getFirst().text().equals("updated"), "snapshot replacement");
        var typed = new DebugDiagnosticPayload.Snapshot(true, "region-1", "RUNNING", false, 12, 8, 4, 42, 3.5,
                2, true, 3, 25, 0.001, 0.002, 0.003);
        data.accept(new DebugDiagnosticPayload(SNAPSHOT, INFO, "minecraft:overworld", "solver", "region", "", typed));
        check(data.snapshots().getFirst().snapshot().equals(typed), "typed reading replacement");
        var reading = data.snapshots().getFirst();
        check(!reading.stale(reading.receivedNanos()) && reading.stale(reading.receivedNanos() + 2_000_000_001L), "snapshot age");
        for (int i = 0; i < 140; i++) data.accept(new DebugDiagnosticPayload(SNAPSHOT, INFO, "minecraft:overworld", "solver", "key" + i, "value"));
        check(data.snapshots().size() == 128, "bounded snapshots");
        data.clear();
        check(data.events().isEmpty() && data.snapshots().isEmpty(), "session cleanup");

    }
    private static void check(boolean condition, String name) { if (!condition) throw new AssertionError(name); }
}
