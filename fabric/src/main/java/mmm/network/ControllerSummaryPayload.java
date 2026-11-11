package mmm.network;

import mmm.MmmMod;
import mmm.api.RegionAvailabilityReason;
import mmm.controller.ControllerSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Clientbound bounded controller summary. The client receives no authority-bearing world object. */
public record ControllerSummaryPayload(java.util.UUID regionId, long requestGeneration, ControllerSummary summary, String failure) implements CustomPacketPayload {
	public static final Type<ControllerSummaryPayload> TYPE = new Type<>(MmmMod.id("controller_summary"));
	public static final StreamCodec<FriendlyByteBuf, ControllerSummaryPayload> CODEC = StreamCodec.of(
			ControllerSummaryPayload::encode, ControllerSummaryPayload::decode);

	public ControllerSummaryPayload {
		if (regionId == null || requestGeneration < 0 || (summary == null && requestGeneration == 0)
				|| (summary != null && !regionId.equals(summary.regionId())))
			throw new IllegalArgumentException("Controller summary response identity is invalid");
		if (failure != null && failure.length() > 1024) failure = failure.substring(0, 1024);
	}
	public ControllerSummaryPayload(java.util.UUID regionId, long requestGeneration, ControllerSummary summary) { this(regionId, requestGeneration, summary, null); }
	public ControllerSummaryPayload(ControllerSummary summary) { this(summary.regionId(), 0, summary, null); }

	@Override public Type<ControllerSummaryPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, ControllerSummaryPayload payload) {
		buffer.writeUUID(payload.regionId);
		buffer.writeVarLong(payload.requestGeneration);
		buffer.writeBoolean(payload.summary != null);
		if (payload.summary != null) writeSummary(buffer, payload.summary);
		buffer.writeBoolean(payload.failure != null);
		if (payload.failure != null) buffer.writeUtf(payload.failure, 1024);
	}

	static void writeSummary(FriendlyByteBuf buffer, ControllerSummary value) {
		buffer.writeVarLong(value.summaryGeneration());
		buffer.writeUUID(value.regionId());
		buffer.writeUtf(value.dimensionId(), 256);
		buffer.writeUtf(value.profileFingerprint(), 64);
		buffer.writeVarLong(value.checkpointGeneration());
		buffer.writeVarLong(value.networkGeneration());
		ControllerSummary.Footprint footprint = value.footprint();
        buffer.writeUtf(footprint.name(), 64);
		buffer.writeInt(footprint.controllerX());
		buffer.writeInt(footprint.controllerY());
		buffer.writeInt(footprint.controllerZ());
		buffer.writeVarInt(footprint.blockSizeX());
		buffer.writeVarInt(footprint.blockSizeY());
		buffer.writeVarInt(footprint.blockSizeZ());
		buffer.writeVarLong(footprint.interiorBlocks());
		buffer.writeVarInt(footprint.requiredChunks());
		buffer.writeBoolean(value.selected());
		buffer.writeEnum(value.operatorIntent());
		buffer.writeEnum(value.motionState());
		buffer.writeEnum(value.thermalState());
		ControllerSummary.Availability availability = value.availability();
		buffer.writeBoolean(!availability.ready());
		if (!availability.ready()) {
			buffer.writeEnum(availability.reason());
			buffer.writeUtf(availability.detail(), 1_024);
		}
		writeUsage(buffer, value.usage());
		writeInventory(buffer, value.inventory());
		ControllerSummary.Gates gates = value.gates();
		buffer.writeBoolean(gates.ownershipReady());
		buffer.writeBoolean(gates.containerWalPending());
		buffer.writeBoolean(gates.phaseWorkPending());
		buffer.writeBoolean(gates.worldBridgeEnabled());
		buffer.writeBoolean(gates.checkpointAllowed());
		ControllerSummary.Diagnostics diagnostics = value.diagnostics();
		buffer.writeDouble(diagnostics.lastStepMilliseconds());
		buffer.writeVarInt(diagnostics.pressureIterations());
		buffer.writeDouble(diagnostics.pressureResidual());
		buffer.writeBoolean(diagnostics.numericalFailure() != null);
		if (diagnostics.numericalFailure() != null) buffer.writeUtf(diagnostics.numericalFailure(), 1_024);
	}

	private static ControllerSummaryPayload decode(FriendlyByteBuf buffer) {
		var region = buffer.readUUID(); long generation = buffer.readVarLong();
		var summary = buffer.readBoolean() ? readSummary(buffer) : null;
		return new ControllerSummaryPayload(region, generation, summary, buffer.readBoolean() ? buffer.readUtf(1024) : null);
	}

	static ControllerSummary readSummary(FriendlyByteBuf buffer) {
		long summaryGeneration = buffer.readVarLong();
		java.util.UUID regionId = buffer.readUUID();
		String dimension = buffer.readUtf(256), profile = buffer.readUtf(64);
		long checkpoint = buffer.readVarLong(), network = buffer.readVarLong();
		ControllerSummary.Footprint footprint = new ControllerSummary.Footprint(
                buffer.readUtf(64), buffer.readInt(), buffer.readInt(), buffer.readInt(),
				buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
				buffer.readVarLong(), buffer.readVarInt());
		boolean selected = buffer.readBoolean();
		ControllerSummary.OperatorIntent intent = buffer.readEnum(ControllerSummary.OperatorIntent.class);
		ControllerSummary.MotionState motion = buffer.readEnum(ControllerSummary.MotionState.class);
		ControllerSummary.ThermalState thermal = buffer.readEnum(ControllerSummary.ThermalState.class);
		ControllerSummary.Availability availability = buffer.readBoolean()
				? new ControllerSummary.Availability(buffer.readEnum(RegionAvailabilityReason.class), buffer.readUtf(1_024))
				: new ControllerSummary.Availability(null, null);
		ControllerSummary.Usage usage = readUsage(buffer);
		ControllerSummary.Inventory inventory = readInventory(buffer);
		ControllerSummary.Gates gates = new ControllerSummary.Gates(buffer.readBoolean(), buffer.readBoolean(),
				buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
		ControllerSummary.Diagnostics diagnostics = new ControllerSummary.Diagnostics(buffer.readDouble(),
				buffer.readVarInt(), buffer.readDouble(), buffer.readBoolean() ? buffer.readUtf(1_024) : null);
		return new ControllerSummary(summaryGeneration, regionId, dimension, profile,
				checkpoint, network, footprint, selected, intent, motion, thermal, availability, usage, inventory,
				gates, diagnostics);
	}

	private static void writeUsage(FriendlyByteBuf buffer, ControllerSummary.Usage usage) {
		writeCapacity(buffer, usage.cells());
		writeCapacity(buffer, usage.activeParcels());
		writeCapacity(buffer, usage.depositGroups());
		writeCapacity(buffer, usage.coherentGroups());
		writeCapacity(buffer, usage.gasNodes());
		writeCapacity(buffer, usage.finiteDevices());
	}

	private static ControllerSummary.Usage readUsage(FriendlyByteBuf buffer) {
		return new ControllerSummary.Usage(readCapacity(buffer),
				readCapacity(buffer), readCapacity(buffer), readCapacity(buffer), readCapacity(buffer), readCapacity(buffer));
	}

	private static void writeCapacity(FriendlyByteBuf buffer, ControllerSummary.Capacity capacity) {
		buffer.writeBoolean(capacity.available());
		buffer.writeVarLong(capacity.used());
		buffer.writeVarLong(capacity.limit());
	}

	private static ControllerSummary.Capacity readCapacity(FriendlyByteBuf buffer) {
		return new ControllerSummary.Capacity(buffer.readBoolean(), buffer.readVarLong(), buffer.readVarLong());
	}

	private static void writeInventory(FriendlyByteBuf buffer, ControllerSummary.Inventory inventory) {
		buffer.writeBoolean(inventory.live());
		buffer.writeDouble(inventory.liquidVolumeCubicMetres());
		buffer.writeDouble(inventory.liquidMassKilograms());
		buffer.writeVarLong(inventory.speciesQuanta());
		buffer.writeVarLong(inventory.granularSolidVolumeSvq());
		buffer.writeDouble(inventory.granularMassKilograms());
		buffer.writeVarLong(inventory.coherentSolidVolumeSvq());
		buffer.writeDouble(inventory.coherentMassKilograms());
		buffer.writeVarLong(inventory.gasStandardVolumeNanos());
		buffer.writeDouble(inventory.gasMassKilograms());
		buffer.writeBoolean(inventory.internalEnergyAvailable());
		buffer.writeDouble(inventory.totalInternalEnergyJ());
		ControllerSummary.TemperatureRange range = inventory.temperatureRange();
		buffer.writeBoolean(range.available());
		buffer.writeDouble(range.minimumK());
		buffer.writeDouble(range.maximumK());
        buffer.writeVarInt(inventory.components().size());
        for (var row : inventory.components()) {
            buffer.writeUtf(row.id(), 128);
            buffer.writeEnum(row.phase());
            buffer.writeDouble(row.massKilograms());
            buffer.writeDouble(row.volumeCubicMetres());
            buffer.writeBoolean(row.residualMass());
            buffer.writeDouble(row.amountMoles());
            buffer.writeBoolean(row.ion());
            buffer.writeDouble(row.medianTemperatureK());
            buffer.writeBoolean(row.temperatureRange().available());
            buffer.writeDouble(row.temperatureRange().minimumK());
            buffer.writeDouble(row.temperatureRange().maximumK());
        }
	}

	private static ControllerSummary.Inventory readInventory(FriendlyByteBuf buffer) {
		boolean live = buffer.readBoolean();
		double liquidVolume = buffer.readDouble(), liquidMass = buffer.readDouble();
		long species = buffer.readVarLong(), granularVolume = buffer.readVarLong();
		double granularMass = buffer.readDouble();
		long coherentVolume = buffer.readVarLong();
		double coherentMass = buffer.readDouble();
		long gasVolume = buffer.readVarLong();
		double gasMass = buffer.readDouble();
		boolean energyAvailable = buffer.readBoolean();
		double energy = buffer.readDouble();
		ControllerSummary.TemperatureRange range = new ControllerSummary.TemperatureRange(
				buffer.readBoolean(), buffer.readDouble(), buffer.readDouble());
        int count = buffer.readVarInt();
        if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid controller component count");
        var components = new java.util.ArrayList<ControllerSummary.ComponentRow>(count);
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf(128);
            var phase = buffer.readEnum(ControllerSummary.Phase.class);
            double mass = buffer.readDouble(), volume = buffer.readDouble();
            boolean residual = buffer.readBoolean();
            double amount = buffer.readDouble();
            boolean ion = buffer.readBoolean();
            double median = buffer.readDouble();
            var componentRange = new ControllerSummary.TemperatureRange(buffer.readBoolean(), buffer.readDouble(), buffer.readDouble());
            components.add(new ControllerSummary.ComponentRow(id, phase, mass, volume, componentRange, residual, amount, ion, median));
        }
		return new ControllerSummary.Inventory(live, liquidVolume, liquidMass,
				species, granularVolume, granularMass, coherentVolume, coherentMass,
				gasVolume, gasMass, energyAvailable, energy, range, components);
	}
}
