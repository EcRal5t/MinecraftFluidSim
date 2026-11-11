package mmm.network;

import java.util.UUID;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;

public record SampleAnalysisRequest(UUID session, String dimension, InteractionHand hand) implements CustomPacketPayload {
    public static final Type<SampleAnalysisRequest> TYPE = new Type<>(MmmMod.id("sample_analysis_request_v4"));
    public static final StreamCodec<FriendlyByteBuf,SampleAnalysisRequest> CODEC = StreamCodec.of(
        (b,p)->{b.writeUUID(p.session);b.writeUtf(p.dimension,256);b.writeEnum(p.hand);},
        b->new SampleAnalysisRequest(b.readUUID(),b.readUtf(256),b.readEnum(InteractionHand.class)));
    @Override public Type<SampleAnalysisRequest> type(){return TYPE;}
}
