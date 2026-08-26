package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.controller.ControllerConsoleAction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/** Serverbound controller action carrying only identity and the last server-issued viewer token. */
public record ControllerActionPayload(
		UUID regionId, BlockPos controllerPosition, long summaryGeneration, ControllerConsoleAction action
) implements CustomPacketPayload {
	public static final Type<ControllerActionPayload> TYPE = new Type<>(MmmMod.id("controller_action"));
	public static final StreamCodec<FriendlyByteBuf, ControllerActionPayload> CODEC = StreamCodec.of(
			ControllerActionPayload::encode, ControllerActionPayload::decode);

	public ControllerActionPayload {
		if (regionId == null || controllerPosition == null || summaryGeneration <= 0L || action == null) {
			throw new IllegalArgumentException("Controller action identity is invalid");
		}
		controllerPosition = controllerPosition.immutable();
	}

	@Override public Type<ControllerActionPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, ControllerActionPayload payload) {
		buffer.writeUUID(payload.regionId); buffer.writeBlockPos(payload.controllerPosition);
		buffer.writeVarLong(payload.summaryGeneration); buffer.writeEnum(payload.action);
	}

	private static ControllerActionPayload decode(FriendlyByteBuf buffer) {
		return new ControllerActionPayload(buffer.readUUID(), buffer.readBlockPos(), buffer.readVarLong(),
				buffer.readEnum(ControllerConsoleAction.class));
	}
}
