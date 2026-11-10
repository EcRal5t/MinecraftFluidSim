package mmm.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded, server-authored display snapshot of one held container. */
public record SampleAnalysisPayload(java.util.UUID session, String dimension, Phase phase,
                                    List<String> lines, List<Fraction> fractions) implements CustomPacketPayload {
    private static final int MAX_LINES = 2048;
    private static final int MAX_LINE_LENGTH = 256;
    public enum Phase { LIQUID, GAS, SOLID }
    public record Fraction(int line, double value) {
        public Fraction {
            if (line < 0 || !Double.isFinite(value) || value < 0 || value > 1)
                throw new IllegalArgumentException("Invalid sample fraction");
        }
    }
    public static final Type<SampleAnalysisPayload> TYPE = new Type<>(MmmMod.id("sample_analysis_v2"));
    public static final StreamCodec<FriendlyByteBuf, SampleAnalysisPayload> CODEC = StreamCodec.of(
            SampleAnalysisPayload::encode, SampleAnalysisPayload::decode);
    public SampleAnalysisPayload {
        Objects.requireNonNull(session); Objects.requireNonNull(dimension); Objects.requireNonNull(phase);
        lines = List.copyOf(lines); fractions = List.copyOf(fractions);
        if (lines.size() > MAX_LINES || lines.stream().anyMatch(s -> s.length() > MAX_LINE_LENGTH)
                || fractions.size() > lines.size()) throw new IllegalArgumentException("Sample analysis exceeds packet limits");
        boolean[] seen = new boolean[lines.size()];
        for (Fraction fraction : fractions) {
            if (fraction.line() >= lines.size() || seen[fraction.line()]) throw new IllegalArgumentException("Invalid sample fraction line");
            seen[fraction.line()] = true;
        }
    }
    public SampleAnalysisPayload(Phase phase, List<String> lines, List<Fraction> fractions) {
        this(new java.util.UUID(0,0), "", phase, lines, fractions);
    }
    @Override public Type<SampleAnalysisPayload> type() { return TYPE; }
    private static void encode(FriendlyByteBuf buf, SampleAnalysisPayload payload) {
        buf.writeUUID(payload.session); buf.writeUtf(payload.dimension,256); buf.writeVarInt(payload.phase.ordinal());
        buf.writeVarInt(payload.lines.size());
        for (String line : payload.lines) buf.writeUtf(line, MAX_LINE_LENGTH);
        buf.writeVarInt(payload.fractions.size());
        for (Fraction fraction : payload.fractions) { buf.writeVarInt(fraction.line()); buf.writeDouble(fraction.value()); }
    }
    private static SampleAnalysisPayload decode(FriendlyByteBuf buf) {
        var session=buf.readUUID(); var dimension=buf.readUtf(256);
        int phase=buf.readVarInt();
        if (phase < 0 || phase >= Phase.values().length) throw new IllegalArgumentException("Invalid sample phase");
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LINES) throw new IllegalArgumentException("Invalid sample line count");
        var lines = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) lines.add(buf.readUtf(MAX_LINE_LENGTH));
        int fractionCount = buf.readVarInt();
        if (fractionCount < 0 || fractionCount > count) throw new IllegalArgumentException("Invalid sample fraction count");
        var fractions = new ArrayList<Fraction>(fractionCount);
        for (int i = 0; i < fractionCount; i++) fractions.add(new Fraction(buf.readVarInt(), buf.readDouble()));
        return new SampleAnalysisPayload(session, dimension, Phase.values()[phase], lines, fractions);
    }
}
