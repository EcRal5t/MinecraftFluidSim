package mmm.network;

import mmm.MmmMod;
import mmm.controller.LifecycleOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Serverbound preview or same-token confirmation request. */
public record LifecycleOperationRequestPayload(
		UUID regionId, BlockPos controllerPosition, long summaryGeneration,
		LifecycleOperation operation, Kind kind, UUID nonce
) implements CustomPacketPayload {
	public static final Type<LifecycleOperationRequestPayload> TYPE = new Type<>(MmmMod.id("lifecycle_operation"));
	public static final StreamCodec<FriendlyByteBuf, LifecycleOperationRequestPayload> CODEC = StreamCodec.of(
			LifecycleOperationRequestPayload::encode, LifecycleOperationRequestPayload::decode);

	public LifecycleOperationRequestPayload {
		if (regionId == null || controllerPosition == null || summaryGeneration <= 0L
				|| operation == null || kind == null || (kind == Kind.CONFIRM) != (nonce != null)) {
			throw new IllegalArgumentException("Lifecycle operation request identity is invalid");
		}
		controllerPosition = controllerPosition.immutable();
	}

	public static LifecycleOperationRequestPayload preview(
			UUID regionId, BlockPos position, long summaryGeneration, LifecycleOperation operation
	) {
		return new LifecycleOperationRequestPayload(regionId, position, summaryGeneration, operation, Kind.PREVIEW, null);
	}

	public static LifecycleOperationRequestPayload confirm(
			UUID regionId, BlockPos position, long summaryGeneration,
			LifecycleOperation operation, UUID nonce
	) {
		return new LifecycleOperationRequestPayload(regionId, position, summaryGeneration, operation, Kind.CONFIRM, nonce);
	}

	@Override public Type<LifecycleOperationRequestPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, LifecycleOperationRequestPayload payload) {
		buffer.writeUUID(payload.regionId); buffer.writeBlockPos(payload.controllerPosition);
		buffer.writeVarLong(payload.summaryGeneration); buffer.writeEnum(payload.operation); buffer.writeEnum(payload.kind);
		if (payload.nonce != null) buffer.writeUUID(payload.nonce);
	}

	private static LifecycleOperationRequestPayload decode(FriendlyByteBuf buffer) {
		UUID region = buffer.readUUID(); BlockPos position = buffer.readBlockPos(); long generation = buffer.readVarLong();
		LifecycleOperation operation = buffer.readEnum(LifecycleOperation.class);
		Kind kind = buffer.readEnum(Kind.class);
		return new LifecycleOperationRequestPayload(region, position, generation, operation, kind,
				kind == Kind.CONFIRM ? buffer.readUUID() : null);
	}

	public enum Kind { PREVIEW, CONFIRM }
}
