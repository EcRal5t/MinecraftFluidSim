package mmm.network;

import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/** A bounded, mod-owned diagnostic event or a replacement reading. */
public record DebugDiagnosticPayload(Kind kind, Level level, String dimension, String source, String id, String text, Snapshot snapshot)
        implements CustomPacketPayload {
    public record Snapshot(boolean selected, String region, String state, boolean paused, int sizeX, int sizeY, int sizeZ,
                           long tick, double stepMillis, int interval, boolean hasStats,
                           int substeps, int pressureIterations, double residual, double volumeError, double massError) {
        public Snapshot { region = limit(region, 128); state = limit(state, 128); }
    }
    public DebugDiagnosticPayload(Kind kind, Level level, String dimension, String source, String id, String text) {
        this(kind, level, dimension, source, id, text, null);
    }
    public enum Kind { EVENT, SNAPSHOT }
    public enum Level { INFO, WARN, ERROR }
    public static final Type<DebugDiagnosticPayload> TYPE = new Type<>(MmmMod.id("debug_diagnostic_v2"));
    public static final StreamCodec<FriendlyByteBuf, DebugDiagnosticPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.kind);
                buffer.writeEnum(payload.level);
                buffer.writeUtf(payload.dimension, 256);
                buffer.writeUtf(payload.source, 128);
                buffer.writeUtf(payload.id, 128);
                buffer.writeUtf(payload.text, 2048);
                buffer.writeBoolean(payload.snapshot != null);
                if (payload.snapshot != null) {
                    var s = payload.snapshot;
                    buffer.writeBoolean(s.selected()); buffer.writeUtf(s.region(), 128); buffer.writeUtf(s.state(), 128); buffer.writeBoolean(s.paused());
                    buffer.writeInt(s.sizeX()); buffer.writeInt(s.sizeY()); buffer.writeInt(s.sizeZ());
                    buffer.writeLong(s.tick()); buffer.writeDouble(s.stepMillis()); buffer.writeInt(s.interval());
                    buffer.writeBoolean(s.hasStats()); buffer.writeInt(s.substeps()); buffer.writeInt(s.pressureIterations());
                    buffer.writeDouble(s.residual()); buffer.writeDouble(s.volumeError()); buffer.writeDouble(s.massError());
                }
            },
            buffer -> new DebugDiagnosticPayload(buffer.readEnum(Kind.class), buffer.readEnum(Level.class),
                    buffer.readUtf(256), buffer.readUtf(128), buffer.readUtf(128), buffer.readUtf(2048),
                    buffer.readBoolean() ? new Snapshot(buffer.readBoolean(), buffer.readUtf(128), buffer.readUtf(128), buffer.readBoolean(),
                            buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readLong(), buffer.readDouble(),
                            buffer.readInt(), buffer.readBoolean(), buffer.readInt(), buffer.readInt(), buffer.readDouble(),
                            buffer.readDouble(), buffer.readDouble()) : null));

    public DebugDiagnosticPayload {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(level);
        dimension = limit(dimension, 256);
        source = limit(source, 128);
        id = limit(id, 128);
        text = limit(text, 2048);
    }

    private static String limit(String value, int maximum) {
        Objects.requireNonNull(value);
        if (value.length() <= maximum) return value;
        int end = maximum;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    @Override public Type<DebugDiagnosticPayload> type() { return TYPE; }
}
