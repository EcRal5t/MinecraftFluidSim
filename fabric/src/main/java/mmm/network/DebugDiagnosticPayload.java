package mmm.network;

import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/** A bounded, mod-owned diagnostic event or a replacement reading. */
public record DebugDiagnosticPayload(Kind kind, Level level, String dimension, String source, String id, String text)
        implements CustomPacketPayload {
    public enum Kind { EVENT, SNAPSHOT }
    public enum Level { INFO, WARN, ERROR }
    public static final Type<DebugDiagnosticPayload> TYPE = new Type<>(MmmMod.id("debug_diagnostic"));
    public static final StreamCodec<FriendlyByteBuf, DebugDiagnosticPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeEnum(payload.kind);
                buffer.writeEnum(payload.level);
                buffer.writeUtf(payload.dimension, 256);
                buffer.writeUtf(payload.source, 128);
                buffer.writeUtf(payload.id, 128);
                buffer.writeUtf(payload.text, 2048);
            },
            buffer -> new DebugDiagnosticPayload(buffer.readEnum(Kind.class), buffer.readEnum(Level.class),
                    buffer.readUtf(256), buffer.readUtf(128), buffer.readUtf(128), buffer.readUtf(2048)));

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
