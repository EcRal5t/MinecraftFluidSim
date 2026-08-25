package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.chemistry.ChemicalDisplaySnapshot;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Independent protocol-v3 display payload; exact chemical inventory and mass residuals are deliberately absent. */
public record FluidChemicalSnapshotPayload(
		int protocolVersion,
		FluidMaterialSnapshotPayload material,
		String registryFingerprint,
		List<SpeciesEntry> species,
		short[] topSpecies,
		byte[] topWeights,
		byte[] residualWeights,
		byte[] ph,
		byte[] indicatorFraction
) implements CustomPacketPayload {
	private static final int MAX_CELLS = 1 << 20;
	private static final int MAX_KEY_LENGTH = 128;
	private static final int MAX_COMPONENT_BYTES = 16 << 20;
	public static final Type<FluidChemicalSnapshotPayload> TYPE = new Type<>(MmmMod.id("fluid_snapshot_v3"));
	public static final StreamCodec<FriendlyByteBuf, FluidChemicalSnapshotPayload> CODEC = StreamCodec.of(
			FluidChemicalSnapshotPayload::encode, FluidChemicalSnapshotPayload::decode);

	public FluidChemicalSnapshotPayload {
		material = Objects.requireNonNull(material, "material");
		registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
		species = List.copyOf(Objects.requireNonNull(species, "species"));
		topSpecies = Objects.requireNonNull(topSpecies, "topSpecies").clone();
		topWeights = Objects.requireNonNull(topWeights, "topWeights").clone();
		residualWeights = Objects.requireNonNull(residualWeights, "residualWeights").clone();
		ph = Objects.requireNonNull(ph, "ph").clone();
		indicatorFraction = Objects.requireNonNull(indicatorFraction, "indicatorFraction").clone();
		if (protocolVersion != FluidProtocol.CHEMICAL_SNAPSHOT_VERSION) {
			throw new IllegalArgumentException("Chemical snapshot must use protocol v3");
		}
		if (material.protocolVersion() != FluidProtocol.MATERIAL_SNAPSHOT_VERSION) {
			throw new IllegalArgumentException("Chemical snapshot geometry must retain material v2 fields");
		}
		if (material.isSnapshot()) {
			int cells = Math.multiplyExact(Math.multiplyExact(material.sizeX(), material.sizeY()), material.sizeZ());
			if (!registryFingerprint.matches("[0-9a-f]{64}") || species.isEmpty()
					|| species.size() > ChemicalLimits.MAX_SPECIES || cells > MAX_CELLS
					|| topSpecies.length != cells * ChemicalDisplaySnapshot.TOP_SPECIES
					|| topWeights.length != topSpecies.length || residualWeights.length != cells
					|| ph.length != cells || indicatorFraction.length != cells) {
				throw new IllegalArgumentException("Invalid chemical snapshot layout");
			}
			HashSet<String> ids = new HashSet<>();
			for (SpeciesEntry entry : species) if (!ids.add(entry.id())) {
				throw new IllegalArgumentException("Duplicate chemical display species");
			}
			for (int cell = 0; cell < cells; cell++) {
				int sum = Byte.toUnsignedInt(residualWeights[cell]);
				for (int slot = 0; slot < ChemicalDisplaySnapshot.TOP_SPECIES; slot++) {
					int offset = cell * ChemicalDisplaySnapshot.TOP_SPECIES + slot;
					int index = topSpecies[offset], weight = Byte.toUnsignedInt(topWeights[offset]);
					if ((index < 0 && weight != 0) || index >= species.size()) {
						throw new IllegalArgumentException("Invalid chemical snapshot species index");
					}
					sum += weight;
				}
				if (sum != 0 && sum != 255) throw new IllegalArgumentException("Chemical snapshot weights do not close");
			}
			long bytes = (long) topSpecies.length * Short.BYTES + topWeights.length + residualWeights.length
					+ ph.length + indicatorFraction.length;
			if (bytes > MAX_COMPONENT_BYTES) throw new IllegalArgumentException("Chemical snapshot fields are too large");
		} else if (!registryFingerprint.isEmpty() || !species.isEmpty() || topSpecies.length != 0
				|| topWeights.length != 0 || residualWeights.length != 0 || ph.length != 0 || indicatorFraction.length != 0) {
			throw new IllegalArgumentException("Chemical removal payloads cannot carry display state");
		}
	}

	@Override public short[] topSpecies() { return topSpecies.clone(); }
	@Override public byte[] topWeights() { return topWeights.clone(); }
	@Override public byte[] residualWeights() { return residualWeights.clone(); }
	@Override public byte[] ph() { return ph.clone(); }
	@Override public byte[] indicatorFraction() { return indicatorFraction.clone(); }

	public static FluidChemicalSnapshotPayload snapshot(
			FluidMaterialSnapshotPayload material, String fingerprint, ChemicalDisplaySnapshot display
	) {
		return new FluidChemicalSnapshotPayload(FluidProtocol.CHEMICAL_SNAPSHOT_VERSION, material, fingerprint,
				display.species().stream().map(entry -> new SpeciesEntry(entry.id(), entry.argb())).toList(),
				display.copyTopSpecies(), display.copyTopWeights(), display.copyResidualWeights(),
				display.copyPh(), display.copyIndicatorFraction());
	}

	public static FluidChemicalSnapshotPayload removeRegion(FluidMaterialSnapshotPayload materialRemoval) {
		return empty(materialRemoval);
	}

	public static FluidChemicalSnapshotPayload clearAll() {
		return empty(FluidMaterialSnapshotPayload.clearAll());
	}

	private static FluidChemicalSnapshotPayload empty(FluidMaterialSnapshotPayload material) {
		return new FluidChemicalSnapshotPayload(FluidProtocol.CHEMICAL_SNAPSHOT_VERSION, material, "", List.of(),
				new short[0], new byte[0], new byte[0], new byte[0], new byte[0]);
	}

	public boolean isSnapshot() { return material.isSnapshot(); }
	public boolean removesRegion() { return material.removesRegion(); }
	public boolean clearsAll() { return material.clearsAll(); }
	@Override public Type<FluidChemicalSnapshotPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, FluidChemicalSnapshotPayload payload) {
		buffer.writeVarInt(payload.protocolVersion);
		FluidMaterialSnapshotPayload.encode(buffer, payload.material);
		buffer.writeUtf(payload.registryFingerprint, 64);
		buffer.writeVarInt(payload.species.size());
		for (SpeciesEntry species : payload.species) {
			buffer.writeUtf(species.id(), MAX_KEY_LENGTH); buffer.writeInt(species.argb());
		}
		buffer.writeVarInt(payload.topSpecies.length);
		for (short index : payload.topSpecies) buffer.writeShort(index);
		buffer.writeByteArray(payload.topWeights);
		buffer.writeByteArray(payload.residualWeights);
		buffer.writeByteArray(payload.ph);
		buffer.writeByteArray(payload.indicatorFraction);
	}

	private static FluidChemicalSnapshotPayload decode(FriendlyByteBuf buffer) {
		int version = buffer.readVarInt();
		FluidMaterialSnapshotPayload material = FluidMaterialSnapshotPayload.decode(buffer);
		String fingerprint = buffer.readUtf(64);
		int speciesCount = bounded(buffer.readVarInt(), ChemicalLimits.MAX_SPECIES, "species palette");
		List<SpeciesEntry> species = new ArrayList<>(speciesCount);
		for (int i = 0; i < speciesCount; i++) species.add(new SpeciesEntry(buffer.readUtf(MAX_KEY_LENGTH), buffer.readInt()));
		int topCount = bounded(buffer.readVarInt(), Math.multiplyExact(MAX_CELLS, ChemicalDisplaySnapshot.TOP_SPECIES), "top species");
		short[] top = new short[topCount]; for (int i = 0; i < top.length; i++) top[i] = buffer.readShort();
		byte[] weights = buffer.readByteArray(MAX_COMPONENT_BYTES);
		byte[] residual = buffer.readByteArray(MAX_CELLS);
		byte[] ph = buffer.readByteArray(MAX_CELLS);
		byte[] indicator = buffer.readByteArray(MAX_CELLS);
		return new FluidChemicalSnapshotPayload(version, material, fingerprint, species, top, weights, residual, ph, indicator);
	}

	private static int bounded(int count, int maximum, String label) {
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid chemical " + label + " count");
		return count;
	}

	public record SpeciesEntry(String id, int argb) {
		public SpeciesEntry {
			if (id == null || id.isBlank() || id.length() > MAX_KEY_LENGTH) {
				throw new IllegalArgumentException("Invalid chemical display species");
			}
		}
	}
}
