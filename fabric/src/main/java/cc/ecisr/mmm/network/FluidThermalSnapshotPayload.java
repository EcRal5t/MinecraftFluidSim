package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.fluid.air.GasLimits;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Protocol-v5 display payload. Raw U, source/sink/work and exact phase inventories never cross this boundary. */
public record FluidThermalSnapshotPayload(
		int protocolVersion,
		FluidMaterialSnapshotPayload material,
		int paletteVersion,
		String registryFingerprint,
		short[] cellTemperatureQ,
		byte[] coherentFraction,
		byte[] debugFlags,
		List<GasNodeDisplay> gasNodes,
		FluidChemicalSnapshotPayload chemicalLayer,
		FluidGranularSnapshotPayload granularLayer
) implements CustomPacketPayload {
	public static final int PALETTE_VERSION = 1;
	public static final int EMPTY_TEMPERATURE_Q = 0xffff;
	private static final int MAX_CELLS = 1 << 20;
	public static final Type<FluidThermalSnapshotPayload> TYPE = new Type<>(MmmMod.id("fluid_snapshot_v5"));
	public static final StreamCodec<FriendlyByteBuf, FluidThermalSnapshotPayload> CODEC = StreamCodec.of(
			FluidThermalSnapshotPayload::encode, FluidThermalSnapshotPayload::decode);

	public FluidThermalSnapshotPayload {
		material = Objects.requireNonNull(material, "material");
		registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
		cellTemperatureQ = Objects.requireNonNull(cellTemperatureQ, "cellTemperatureQ").clone();
		coherentFraction = Objects.requireNonNull(coherentFraction, "coherentFraction").clone();
		debugFlags = Objects.requireNonNull(debugFlags, "debugFlags").clone();
		gasNodes = List.copyOf(Objects.requireNonNull(gasNodes, "gasNodes"));
		if (protocolVersion != FluidProtocol.THERMAL_SNAPSHOT_VERSION
				|| material.protocolVersion() != FluidProtocol.MATERIAL_SNAPSHOT_VERSION) {
			throw new IllegalArgumentException("Thermal snapshot must retain protocol-v2 material geometry inside v5");
		}
		if (chemicalLayer != null && granularLayer != null) {
			throw new IllegalArgumentException("Thermal snapshot cannot duplicate chemical and granular display layers");
		}
		if (material.isSnapshot()) {
			int cells = Math.multiplyExact(Math.multiplyExact(material.sizeX(), material.sizeY()), material.sizeZ());
			if (cells > MAX_CELLS || paletteVersion != PALETTE_VERSION
					|| !registryFingerprint.matches("[0-9a-f]{64}")
					|| cellTemperatureQ.length != cells || coherentFraction.length != cells || debugFlags.length != cells
					|| gasNodes.size() > GasLimits.MAX_NODES_PER_REGION) {
				throw new IllegalArgumentException("Thermal snapshot field layout or identity is invalid");
			}
			int previousNode = 0;
			for (GasNodeDisplay gas : gasNodes) {
				if (gas.nodeId() <= previousNode) throw new IllegalArgumentException("Thermal gas display is not canonical");
				previousNode = gas.nodeId();
			}
			if (chemicalLayer != null && !sameMaterialIdentity(material, chemicalLayer.material())
					|| granularLayer != null && !sameMaterialIdentity(material, granularLayer.material())) {
				throw new IllegalArgumentException("Thermal optional display layer names another snapshot");
			}
		} else if (paletteVersion != 0 || !registryFingerprint.isEmpty() || cellTemperatureQ.length != 0
				|| coherentFraction.length != 0 || debugFlags.length != 0 || !gasNodes.isEmpty()
				|| chemicalLayer != null || granularLayer != null) {
			throw new IllegalArgumentException("Thermal removal payload cannot carry display state");
		}
	}

	@Override public short[] cellTemperatureQ() { return cellTemperatureQ.clone(); }
	@Override public byte[] coherentFraction() { return coherentFraction.clone(); }
	@Override public byte[] debugFlags() { return debugFlags.clone(); }
	public boolean isSnapshot() { return material.isSnapshot(); }
	public boolean removesRegion() { return material.removesRegion(); }
	public boolean clearsAll() { return material.clearsAll(); }

	public static FluidThermalSnapshotPayload removeRegion(FluidMaterialSnapshotPayload removal) { return empty(removal); }
	public static FluidThermalSnapshotPayload clearAll() { return empty(FluidMaterialSnapshotPayload.clearAll()); }
	private static FluidThermalSnapshotPayload empty(FluidMaterialSnapshotPayload material) {
		return new FluidThermalSnapshotPayload(FluidProtocol.THERMAL_SNAPSHOT_VERSION, material, 0, "",
				new short[0], new byte[0], new byte[0], List.of(), null, null);
	}

	@Override public Type<FluidThermalSnapshotPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, FluidThermalSnapshotPayload payload) {
		buffer.writeVarInt(payload.protocolVersion);
		FluidMaterialSnapshotPayload.CODEC.encode(buffer, payload.material);
		buffer.writeVarInt(payload.paletteVersion); buffer.writeUtf(payload.registryFingerprint, 64);
		buffer.writeVarInt(payload.cellTemperatureQ.length);
		for (short temperature : payload.cellTemperatureQ) buffer.writeShort(temperature);
		buffer.writeByteArray(payload.coherentFraction); buffer.writeByteArray(payload.debugFlags);
		buffer.writeVarInt(payload.gasNodes.size());
		for (GasNodeDisplay gas : payload.gasNodes) {
			buffer.writeVarInt(gas.nodeId()); buffer.writeShort(gas.temperatureQ()); buffer.writeInt(gas.pressureDecaPa());
		}
		buffer.writeBoolean(payload.chemicalLayer != null);
		if (payload.chemicalLayer != null) FluidChemicalSnapshotPayload.CODEC.encode(buffer, payload.chemicalLayer);
		buffer.writeBoolean(payload.granularLayer != null);
		if (payload.granularLayer != null) FluidGranularSnapshotPayload.CODEC.encode(buffer, payload.granularLayer);
	}

	private static FluidThermalSnapshotPayload decode(FriendlyByteBuf buffer) {
		int version = buffer.readVarInt();
		FluidMaterialSnapshotPayload material = FluidMaterialSnapshotPayload.CODEC.decode(buffer);
		int palette = buffer.readVarInt(); String fingerprint = buffer.readUtf(64);
		int temperatureCount = bounded(buffer.readVarInt(), MAX_CELLS, "cell temperature");
		short[] temperatures = new short[temperatureCount];
		for (int cell = 0; cell < temperatureCount; cell++) temperatures[cell] = buffer.readShort();
		byte[] coherent = buffer.readByteArray(MAX_CELLS), flags = buffer.readByteArray(MAX_CELLS);
		int gasCount = bounded(buffer.readVarInt(), GasLimits.MAX_NODES_PER_REGION, "gas node");
		ArrayList<GasNodeDisplay> gas = new ArrayList<>(gasCount);
		for (int node = 0; node < gasCount; node++) gas.add(
				new GasNodeDisplay(buffer.readVarInt(), buffer.readUnsignedShort(), buffer.readInt()));
		FluidChemicalSnapshotPayload chemical = buffer.readBoolean()
				? FluidChemicalSnapshotPayload.CODEC.decode(buffer) : null;
		FluidGranularSnapshotPayload granular = buffer.readBoolean()
				? FluidGranularSnapshotPayload.CODEC.decode(buffer) : null;
		return new FluidThermalSnapshotPayload(version, material, palette, fingerprint, temperatures,
				coherent, flags, gas, chemical, granular);
	}

	private static int bounded(int count, int maximum, String label) {
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid thermal " + label + " count");
		return count;
	}

	private static boolean sameMaterialIdentity(
			FluidMaterialSnapshotPayload first, FluidMaterialSnapshotPayload second
	) {
		return first.kind() == second.kind() && first.dimension().equals(second.dimension())
				&& first.regionId().equals(second.regionId()) && first.generation() == second.generation()
				&& first.sequence() == second.sequence() && first.sizeX() == second.sizeX()
				&& first.sizeY() == second.sizeY() && first.sizeZ() == second.sizeZ();
	}

	public record GasNodeDisplay(int nodeId, int temperatureQ, int pressureDecaPa) {
		public GasNodeDisplay {
			if (nodeId <= 0 || temperatureQ <= 0 || temperatureQ >= EMPTY_TEMPERATURE_Q) {
				throw new IllegalArgumentException("Invalid thermal gas-node display");
			}
		}
	}
}
