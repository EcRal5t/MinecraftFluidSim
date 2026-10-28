package mmm.network;

import mmm.MmmMod;
import mmm.thermal.ThermalReservoirSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Clientbound world-owned thermal reservoir setpoint and frozen registry validity interval. */
public record ThermalReservoirSummaryPayload(net.minecraft.core.BlockPos position, long requestGeneration,
		ThermalReservoirSummary summary) implements CustomPacketPayload {
	public static final Type<ThermalReservoirSummaryPayload> TYPE = new Type<>(MmmMod.id("thermal_reservoir_summary"));
	public static final StreamCodec<FriendlyByteBuf, ThermalReservoirSummaryPayload> CODEC = StreamCodec.of(
			ThermalReservoirSummaryPayload::encode, ThermalReservoirSummaryPayload::decode);

	public ThermalReservoirSummaryPayload {
		if (position == null || requestGeneration < 0 || (summary == null && requestGeneration == 0)
				|| (summary != null && (position.getX() != summary.blockX() || position.getY() != summary.blockY()
				|| position.getZ() != summary.blockZ())))
			throw new IllegalArgumentException("Thermal reservoir response identity is invalid");
		position = position.immutable();
	}
	public ThermalReservoirSummaryPayload(ThermalReservoirSummary summary) {
		this(new net.minecraft.core.BlockPos(summary.blockX(), summary.blockY(), summary.blockZ()), 0, summary);
	}

	@Override public Type<ThermalReservoirSummaryPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, ThermalReservoirSummaryPayload payload) {
		buffer.writeBlockPos(payload.position);
		buffer.writeVarLong(payload.requestGeneration);
		buffer.writeBoolean(payload.summary != null);
		if (payload.summary == null) return;
		ThermalReservoirSummary value = payload.summary;
		buffer.writeVarLong(value.summaryGeneration());
		buffer.writeUtf(value.dimensionId(), 256);
		buffer.writeInt(value.blockX());
		buffer.writeInt(value.blockY());
		buffer.writeInt(value.blockZ());
		buffer.writeDouble(value.temperatureK());
		buffer.writeBoolean(value.temperatureValid());
		buffer.writeDouble(value.minimumTemperatureK());
		buffer.writeDouble(value.maximumTemperatureK());
	}

	private static ThermalReservoirSummaryPayload decode(FriendlyByteBuf buffer) {
		var position = buffer.readBlockPos();
		long requestGeneration = buffer.readVarLong();
		return new ThermalReservoirSummaryPayload(position, requestGeneration, buffer.readBoolean() ? new ThermalReservoirSummary(buffer.readVarLong(), buffer.readUtf(256),
				buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readDouble(), buffer.readBoolean(),
				buffer.readDouble(), buffer.readDouble()) : null);
	}
}
