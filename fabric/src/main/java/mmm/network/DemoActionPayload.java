package mmm.network;

import java.util.UUID;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record DemoActionPayload(UUID sessionId, long generation, UUID regionId, DemoPreset preset,
                                DemoAction action) implements CustomPacketPayload {
	public static final Type<DemoActionPayload> TYPE = new Type<>(MmmMod.id("demo_action"));
	public static final StreamCodec<FriendlyByteBuf, DemoActionPayload> CODEC = StreamCodec.of(
			DemoActionPayload::encode, DemoActionPayload::decode);
	public DemoActionPayload {
		if (sessionId == null || generation <= 0 || preset == null || action == null)
			throw new IllegalArgumentException("Invalid demo request");
	}
	@Override public Type<DemoActionPayload> type() { return TYPE; }
	private static void encode(FriendlyByteBuf buffer, DemoActionPayload value) {
		buffer.writeUUID(value.sessionId); buffer.writeVarLong(value.generation);
		buffer.writeBoolean(value.regionId != null); if (value.regionId != null) buffer.writeUUID(value.regionId);
		buffer.writeEnum(value.preset); buffer.writeEnum(value.action);
	}
	private static DemoActionPayload decode(FriendlyByteBuf buffer) {
		return new DemoActionPayload(buffer.readUUID(), buffer.readVarLong(),
				buffer.readBoolean() ? buffer.readUUID() : null, buffer.readEnum(DemoPreset.class), buffer.readEnum(DemoAction.class));
	}
}
