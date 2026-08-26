package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.thermal.ThermalReservoirSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Clientbound world-owned thermal reservoir setpoint and frozen registry validity interval. */
public record ThermalReservoirSummaryPayload(ThermalReservoirSummary summary) implements CustomPacketPayload {
	public static final Type<ThermalReservoirSummaryPayload> TYPE = new Type<>(MmmMod.id("thermal_reservoir_summary"));
	public static final StreamCodec<FriendlyByteBuf, ThermalReservoirSummaryPayload> CODEC = StreamCodec.of(
			ThermalReservoirSummaryPayload::encode, ThermalReservoirSummaryPayload::decode);

	public ThermalReservoirSummaryPayload {
		if (summary == null) throw new IllegalArgumentException("Thermal reservoir summary payload is missing");
	}

	@Override public Type<ThermalReservoirSummaryPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, ThermalReservoirSummaryPayload payload) {
		ThermalReservoirSummary value = payload.summary;
		buffer.writeVarLong(value.summaryGeneration()); buffer.writeUtf(value.dimensionId(), 256);
		buffer.writeInt(value.blockX()); buffer.writeInt(value.blockY()); buffer.writeInt(value.blockZ());
		buffer.writeDouble(value.temperatureK()); buffer.writeBoolean(value.temperatureValid());
		buffer.writeDouble(value.minimumTemperatureK());
		buffer.writeDouble(value.maximumTemperatureK());
	}

	private static ThermalReservoirSummaryPayload decode(FriendlyByteBuf buffer) {
		return new ThermalReservoirSummaryPayload(new ThermalReservoirSummary(buffer.readVarLong(), buffer.readUtf(256),
				buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readDouble(), buffer.readBoolean(),
				buffer.readDouble(), buffer.readDouble()));
	}
}
