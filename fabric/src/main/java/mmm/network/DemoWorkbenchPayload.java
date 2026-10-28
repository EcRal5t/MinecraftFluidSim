package mmm.network;

import java.util.UUID;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record DemoWorkbenchPayload(UUID sessionId, long requestGeneration, long generation, String dimensionId,
								   DemoPreset preset, UUID regionId, boolean paused, String failure,
								   boolean initializeDisplay, boolean blocked) implements CustomPacketPayload {
	public static final Type<DemoWorkbenchPayload> TYPE = new Type<>(MmmMod.id("demo_workbench"));
	public static final StreamCodec<FriendlyByteBuf, DemoWorkbenchPayload> CODEC = StreamCodec.of(
			DemoWorkbenchPayload::encode, DemoWorkbenchPayload::decode);
	public DemoWorkbenchPayload {
		if (sessionId == null || requestGeneration < 0 || generation <= 0 || dimensionId == null || preset == null)
			throw new IllegalArgumentException("Invalid demo response");
		if (failure != null && failure.length() > 1024) failure = failure.substring(0, 1024);
	}
	public DemoWorkbenchPayload(UUID sessionId, long requestGeneration, long generation, String dimensionId,
			DemoPreset preset, UUID regionId, boolean paused, String failure) {
		this(sessionId, requestGeneration, generation, dimensionId, preset, regionId, paused, failure, false, false);
	}
	public DemoWorkbenchPayload(UUID sessionId, long requestGeneration, long generation, String dimensionId,
			DemoPreset preset, UUID regionId, boolean paused, String failure, boolean initializeDisplay) {
		this(sessionId, requestGeneration, generation, dimensionId, preset, regionId, paused, failure, initializeDisplay, false);
	}
	@Override public Type<DemoWorkbenchPayload> type() { return TYPE; }
	private static void encode(FriendlyByteBuf buffer, DemoWorkbenchPayload value) {
		buffer.writeUUID(value.sessionId); buffer.writeVarLong(value.requestGeneration); buffer.writeVarLong(value.generation);
		buffer.writeUtf(value.dimensionId, 256); buffer.writeEnum(value.preset);
		buffer.writeBoolean(value.regionId != null); if (value.regionId != null) buffer.writeUUID(value.regionId);
		buffer.writeBoolean(value.paused); buffer.writeBoolean(value.failure != null);
		if (value.failure != null) buffer.writeUtf(value.failure, 1024);
		buffer.writeBoolean(value.initializeDisplay);
		buffer.writeBoolean(value.blocked);
	}
	private static DemoWorkbenchPayload decode(FriendlyByteBuf buffer) {
		return new DemoWorkbenchPayload(buffer.readUUID(), buffer.readVarLong(), buffer.readVarLong(), buffer.readUtf(256),
				buffer.readEnum(DemoPreset.class), buffer.readBoolean() ? buffer.readUUID() : null,
				buffer.readBoolean(), buffer.readBoolean() ? buffer.readUtf(1024) : null, buffer.readBoolean(), buffer.readBoolean());
	}
}
