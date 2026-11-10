package mmm.network;

import mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ProcessTrendRequest(BlockPos position) implements CustomPacketPayload {
    public static final Type<ProcessTrendRequest> TYPE = new Type<>(MmmMod.id("process_trend_request"));
    public static final StreamCodec<FriendlyByteBuf, ProcessTrendRequest> CODEC = StreamCodec.of(
        (b,p)->b.writeBlockPos(p.position), b->new ProcessTrendRequest(b.readBlockPos()));
    @Override public Type<ProcessTrendRequest> type() { return TYPE; }
}
