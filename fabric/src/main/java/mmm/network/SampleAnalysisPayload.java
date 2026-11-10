package mmm.network;

import java.util.ArrayList;
import java.util.List;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded, server-authored display snapshot of one held container. */
public record SampleAnalysisPayload(List<String> lines) implements CustomPacketPayload {
    private static final int MAX_LINES = 2048;
    private static final int MAX_LINE_LENGTH = 256;
    public static final Type<SampleAnalysisPayload> TYPE = new Type<>(MmmMod.id("sample_analysis"));
    public static final StreamCodec<FriendlyByteBuf, SampleAnalysisPayload> CODEC = StreamCodec.of(
            SampleAnalysisPayload::encode, SampleAnalysisPayload::decode);
    public SampleAnalysisPayload {
        lines = List.copyOf(lines);
        if (lines.size() > MAX_LINES || lines.stream().anyMatch(s -> s == null || s.length() > MAX_LINE_LENGTH))
            throw new IllegalArgumentException("Sample analysis exceeds packet limits");
    }
    @Override public Type<SampleAnalysisPayload> type() { return TYPE; }
    private static void encode(FriendlyByteBuf buf, SampleAnalysisPayload payload) {
        buf.writeVarInt(payload.lines.size());
        for (String line : payload.lines) buf.writeUtf(line, MAX_LINE_LENGTH);
    }
    private static SampleAnalysisPayload decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_LINES) throw new IllegalArgumentException("Invalid sample line count");
        var lines = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) lines.add(buf.readUtf(MAX_LINE_LENGTH));
        return new SampleAnalysisPayload(lines);
    }
}
