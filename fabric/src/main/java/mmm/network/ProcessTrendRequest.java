package mmm.network;

import java.util.UUID;
import mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ProcessTrendRequest(BlockPos position, String dimension, UUID session, long sequence, boolean open) implements CustomPacketPayload {
    public static final Type<ProcessTrendRequest> TYPE = new Type<>(MmmMod.id("process_trend_request"));
    public static final StreamCodec<FriendlyByteBuf, ProcessTrendRequest> CODEC = StreamCodec.of(
        (b,p)->{ b.writeBlockPos(p.position); b.writeUtf(p.dimension,256); b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeBoolean(p.open); },
        b->new ProcessTrendRequest(b.readBlockPos(),b.readUtf(256),b.readUUID(),b.readVarLong(),b.readBoolean()));
    public ProcessTrendRequest { if(sequence<0) throw new IllegalArgumentException("Invalid trend sequence"); }
    @Override public Type<ProcessTrendRequest> type() { return TYPE; }
}
