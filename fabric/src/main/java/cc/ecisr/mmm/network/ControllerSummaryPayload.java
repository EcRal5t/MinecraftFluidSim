package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.controller.ControllerSummary;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Clientbound bounded controller summary. The client receives no authority-bearing world object. */
public record ControllerSummaryPayload(ControllerSummary summary) implements CustomPacketPayload {
	public static final Type<ControllerSummaryPayload> TYPE = new Type<>(MmmMod.id("controller_summary"));
	public static final StreamCodec<FriendlyByteBuf, ControllerSummaryPayload> CODEC = StreamCodec.of(
			ControllerSummaryPayload::encode, ControllerSummaryPayload::decode);

	public ControllerSummaryPayload {
		if (summary == null) throw new IllegalArgumentException("Controller summary payload is missing");
	}

	@Override public Type<ControllerSummaryPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, ControllerSummaryPayload payload) {
		writeSummary(buffer, payload.summary);
	}

	static void writeSummary(FriendlyByteBuf buffer, ControllerSummary value) {
		buffer.writeVarLong(value.summaryGeneration());
		buffer.writeUUID(value.regionId());
		buffer.writeUtf(value.dimensionId(), 256);
		buffer.writeUtf(value.profileFingerprint(), 64);
		buffer.writeVarLong(value.checkpointGeneration());
		buffer.writeVarLong(value.networkGeneration());
		ControllerSummary.Footprint footprint = value.footprint();
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
		return new ControllerSummaryPayload(readSummary(buffer));
	}

	static ControllerSummary readSummary(FriendlyByteBuf buffer) {
		long summaryGeneration = buffer.readVarLong();
		java.util.UUID regionId = buffer.readUUID();
		String dimension = buffer.readUtf(256), profile = buffer.readUtf(64);
		long checkpoint = buffer.readVarLong(), network = buffer.readVarLong();
		ControllerSummary.Footprint footprint = new ControllerSummary.Footprint(
				buffer.readInt(), buffer.readInt(), buffer.readInt(),
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
		writeCapacity(buffer, usage.markers());
		writeCapacity(buffer, usage.chemicalParcels());
		writeCapacity(buffer, usage.activeParcels());
		writeCapacity(buffer, usage.depositGroups());
		writeCapacity(buffer, usage.coherentGroups());
		writeCapacity(buffer, usage.gasNodes());
		writeCapacity(buffer, usage.finiteDevices());
	}

	private static ControllerSummary.Usage readUsage(FriendlyByteBuf buffer) {
		return new ControllerSummary.Usage(readCapacity(buffer), readCapacity(buffer), readCapacity(buffer),
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
		buffer.writeDouble(inventory.totalInternalEnergyEu());
		ControllerSummary.TemperatureRange range = inventory.temperatureRange();
		buffer.writeBoolean(range.available());
		buffer.writeDouble(range.minimumK());
		buffer.writeDouble(range.maximumK());
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
		return new ControllerSummary.Inventory(live, liquidVolume, liquidMass,
				species, granularVolume, granularMass, coherentVolume, coherentMass,
				gasVolume, gasMass, energyAvailable, energy, range);
	}
}
