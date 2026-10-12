package mmm.network;

import mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Serverbound thermal setpoint proposal; every field is revalidated against current world truth. */
public record ThermalReservoirSetpointPayload(
		BlockPos position, long summaryGeneration, double temperatureK
) implements CustomPacketPayload {
	public static final Type<ThermalReservoirSetpointPayload> TYPE = new Type<>(MmmMod.id("thermal_reservoir_setpoint"));
	public static final StreamCodec<FriendlyByteBuf, ThermalReservoirSetpointPayload> CODEC = StreamCodec.of(
			ThermalReservoirSetpointPayload::encode, ThermalReservoirSetpointPayload::decode);

	public ThermalReservoirSetpointPayload {
		if (position == null || summaryGeneration <= 0L) {
			throw new IllegalArgumentException("Thermal reservoir setpoint identity is invalid");
		}
		position = position.immutable();
	}

	@Override public Type<ThermalReservoirSetpointPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, ThermalReservoirSetpointPayload payload) {
		buffer.writeBlockPos(payload.position);
		buffer.writeVarLong(payload.summaryGeneration);
		buffer.writeDouble(payload.temperatureK);
	}

	private static ThermalReservoirSetpointPayload decode(FriendlyByteBuf buffer) {
		return new ThermalReservoirSetpointPayload(buffer.readBlockPos(), buffer.readVarLong(), buffer.readDouble());
	}
}
