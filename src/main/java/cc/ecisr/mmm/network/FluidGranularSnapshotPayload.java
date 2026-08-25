package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.chemistry.ChemicalDisplaySnapshot;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.granular.GranularDisplaySnapshot;
import cc.ecisr.mmm.granular.GranularLimits;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Protocol-v4 display payload. Exact solid volume, mass and composition are deliberately absent. */
public record FluidGranularSnapshotPayload(
		int protocolVersion,
		FluidMaterialSnapshotPayload material,
		int paletteVersion,
		String registryFingerprint,
		List<GranularDisplaySnapshot.MaterialEntry> granularMaterials,
		byte[] depositBulkFill,
		byte[] depositPorosity,
		short[] depositTopMaterial,
		List<GranularDisplaySnapshot.ParcelSample> activeParcels,
		ChemicalLayer chemical
) implements CustomPacketPayload {
	private static final int MAX_CELLS = 1 << 20;
	private static final int MAX_KEY_LENGTH = 128;
	private static final int MAX_CHEMICAL_BYTES = 16 << 20;
	public static final Type<FluidGranularSnapshotPayload> TYPE = new Type<>(MmmMod.id("fluid_snapshot_v4"));
	public static final StreamCodec<FriendlyByteBuf, FluidGranularSnapshotPayload> CODEC = StreamCodec.of(
			FluidGranularSnapshotPayload::encode, FluidGranularSnapshotPayload::decode);

	public FluidGranularSnapshotPayload {
		material = Objects.requireNonNull(material, "material");
		registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
		granularMaterials = List.copyOf(Objects.requireNonNull(granularMaterials, "granularMaterials"));
		depositBulkFill = Objects.requireNonNull(depositBulkFill, "depositBulkFill").clone();
		depositPorosity = Objects.requireNonNull(depositPorosity, "depositPorosity").clone();
		depositTopMaterial = Objects.requireNonNull(depositTopMaterial, "depositTopMaterial").clone();
		activeParcels = List.copyOf(Objects.requireNonNull(activeParcels, "activeParcels"));
		if (protocolVersion != FluidProtocol.GRANULAR_SNAPSHOT_VERSION
				|| material.protocolVersion() != FluidProtocol.MATERIAL_SNAPSHOT_VERSION) {
			throw new IllegalArgumentException("Granular snapshot must retain protocol-v2 material geometry inside v4");
		}
		if (material.isSnapshot()) {
			if (paletteVersion != FluidProtocol.GRANULAR_PALETTE_VERSION) {
				throw new IllegalArgumentException("Unsupported granular display palette");
			}
			new GranularDisplaySnapshot(material.sizeX(), material.sizeY(), material.sizeZ(), material.cellSize(),
					registryFingerprint, granularMaterials, depositBulkFill, depositPorosity,
					depositTopMaterial, activeParcels);
			if (chemical != null) chemical.validate(material);
		} else if (paletteVersion != 0 || !registryFingerprint.isEmpty() || !granularMaterials.isEmpty()
				|| depositBulkFill.length != 0 || depositPorosity.length != 0 || depositTopMaterial.length != 0
				|| !activeParcels.isEmpty() || chemical != null) {
			throw new IllegalArgumentException("Granular removal payloads cannot carry display state");
		}
	}

	@Override public byte[] depositBulkFill() { return depositBulkFill.clone(); }
	@Override public byte[] depositPorosity() { return depositPorosity.clone(); }
	@Override public short[] depositTopMaterial() { return depositTopMaterial.clone(); }

	public static FluidGranularSnapshotPayload snapshot(
			FluidMaterialSnapshotPayload material, GranularDisplaySnapshot granular,
			String chemicalFingerprint, ChemicalDisplaySnapshot chemical
	) {
		ChemicalLayer layer = chemical == null ? null : ChemicalLayer.from(chemicalFingerprint, chemical);
		return new FluidGranularSnapshotPayload(FluidProtocol.GRANULAR_SNAPSHOT_VERSION, material,
				FluidProtocol.GRANULAR_PALETTE_VERSION, granular.registryFingerprint(), granular.materials(),
				granular.depositBulkFill(), granular.depositPorosity(), granular.depositTopMaterial(),
				granular.activeParcels(), layer);
	}

	public static FluidGranularSnapshotPayload removeRegion(FluidMaterialSnapshotPayload removal) { return empty(removal); }
	public static FluidGranularSnapshotPayload clearAll() { return empty(FluidMaterialSnapshotPayload.clearAll()); }
	private static FluidGranularSnapshotPayload empty(FluidMaterialSnapshotPayload material) {
		return new FluidGranularSnapshotPayload(FluidProtocol.GRANULAR_SNAPSHOT_VERSION, material, 0, "",
				List.of(), new byte[0], new byte[0], new short[0], List.of(), null);
	}

	public boolean isSnapshot() { return material.isSnapshot(); }
	public boolean removesRegion() { return material.removesRegion(); }
	public boolean clearsAll() { return material.clearsAll(); }

	public FluidChemicalSnapshotPayload toChemicalPayload() {
		if (chemical == null) return null;
		return new FluidChemicalSnapshotPayload(FluidProtocol.CHEMICAL_SNAPSHOT_VERSION, material,
				chemical.registryFingerprint(), chemical.species(), chemical.topSpecies(), chemical.topWeights(),
				chemical.residualWeights(), chemical.ph(), chemical.indicatorFraction());
	}

	@Override public Type<FluidGranularSnapshotPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, FluidGranularSnapshotPayload payload) {
		buffer.writeVarInt(payload.protocolVersion);
		FluidMaterialSnapshotPayload.encode(buffer, payload.material);
		buffer.writeVarInt(payload.paletteVersion);
		buffer.writeUtf(payload.registryFingerprint, 64);
		buffer.writeVarInt(payload.granularMaterials.size());
		for (GranularDisplaySnapshot.MaterialEntry entry : payload.granularMaterials) {
			buffer.writeUtf(entry.id(), MAX_KEY_LENGTH); buffer.writeInt(entry.argb());
		}
		buffer.writeByteArray(payload.depositBulkFill);
		buffer.writeByteArray(payload.depositPorosity);
		buffer.writeVarInt(payload.depositTopMaterial.length);
		for (short material : payload.depositTopMaterial) buffer.writeShort(material);
		buffer.writeVarInt(payload.activeParcels.size());
		for (GranularDisplaySnapshot.ParcelSample sample : payload.activeParcels) {
			buffer.writeShort(sample.xQ()); buffer.writeShort(sample.yQ()); buffer.writeShort(sample.zQ());
			buffer.writeByte(sample.diameterQ()); buffer.writeShort(sample.materialIndex());
		}
		buffer.writeBoolean(payload.chemical != null);
		if (payload.chemical != null) payload.chemical.encode(buffer);
	}

	private static FluidGranularSnapshotPayload decode(FriendlyByteBuf buffer) {
		int version = buffer.readVarInt();
		FluidMaterialSnapshotPayload material = FluidMaterialSnapshotPayload.decode(buffer);
		int paletteVersion = buffer.readVarInt();
		String fingerprint = buffer.readUtf(64);
		int materialCount = bounded(buffer.readVarInt(), GranularLimits.MAX_MATERIALS, "material palette");
		ArrayList<GranularDisplaySnapshot.MaterialEntry> materials = new ArrayList<>(materialCount);
		for (int index = 0; index < materialCount; index++) {
			materials.add(new GranularDisplaySnapshot.MaterialEntry(buffer.readUtf(MAX_KEY_LENGTH), buffer.readInt()));
		}
		byte[] bulk = buffer.readByteArray(MAX_CELLS), porosity = buffer.readByteArray(MAX_CELLS);
		int topCount = bounded(buffer.readVarInt(), MAX_CELLS, "deposit top-material field");
		short[] top = new short[topCount]; for (int index = 0; index < top.length; index++) top[index] = buffer.readShort();
		int parcelCount = bounded(buffer.readVarInt(), GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION, "parcel samples");
		ArrayList<GranularDisplaySnapshot.ParcelSample> parcels = new ArrayList<>(parcelCount);
		for (int index = 0; index < parcelCount; index++) parcels.add(new GranularDisplaySnapshot.ParcelSample(
				buffer.readUnsignedShort(), buffer.readUnsignedShort(), buffer.readUnsignedShort(),
				buffer.readUnsignedByte(), buffer.readUnsignedShort()));
		ChemicalLayer chemical = buffer.readBoolean() ? ChemicalLayer.decode(buffer) : null;
		return new FluidGranularSnapshotPayload(version, material, paletteVersion, fingerprint, materials,
				bulk, porosity, top, parcels, chemical);
	}

	private static int bounded(int count, int maximum, String label) {
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid granular " + label + " count");
		return count;
	}

	public record ChemicalLayer(
			String registryFingerprint, List<FluidChemicalSnapshotPayload.SpeciesEntry> species,
			short[] topSpecies, byte[] topWeights, byte[] residualWeights, byte[] ph, byte[] indicatorFraction
	) {
		public ChemicalLayer {
			registryFingerprint = Objects.requireNonNull(registryFingerprint, "registryFingerprint");
			species = List.copyOf(Objects.requireNonNull(species, "species"));
			topSpecies = Objects.requireNonNull(topSpecies, "topSpecies").clone();
			topWeights = Objects.requireNonNull(topWeights, "topWeights").clone();
			residualWeights = Objects.requireNonNull(residualWeights, "residualWeights").clone();
			ph = Objects.requireNonNull(ph, "ph").clone();
			indicatorFraction = Objects.requireNonNull(indicatorFraction, "indicatorFraction").clone();
		}
		@Override public short[] topSpecies() { return topSpecies.clone(); }
		@Override public byte[] topWeights() { return topWeights.clone(); }
		@Override public byte[] residualWeights() { return residualWeights.clone(); }
		@Override public byte[] ph() { return ph.clone(); }
		@Override public byte[] indicatorFraction() { return indicatorFraction.clone(); }

		static ChemicalLayer from(String fingerprint, ChemicalDisplaySnapshot display) {
			return new ChemicalLayer(fingerprint, display.species().stream().map(entry ->
					new FluidChemicalSnapshotPayload.SpeciesEntry(entry.id(), entry.argb())).toList(),
					display.copyTopSpecies(), display.copyTopWeights(), display.copyResidualWeights(),
					display.copyPh(), display.copyIndicatorFraction());
		}

		void validate(FluidMaterialSnapshotPayload material) {
			new FluidChemicalSnapshotPayload(FluidProtocol.CHEMICAL_SNAPSHOT_VERSION, material,
					registryFingerprint, species, topSpecies, topWeights, residualWeights, ph, indicatorFraction);
		}

		void encode(FriendlyByteBuf buffer) {
			buffer.writeUtf(registryFingerprint, 64); buffer.writeVarInt(species.size());
			for (FluidChemicalSnapshotPayload.SpeciesEntry entry : species) {
				buffer.writeUtf(entry.id(), MAX_KEY_LENGTH); buffer.writeInt(entry.argb());
			}
			buffer.writeVarInt(topSpecies.length); for (short value : topSpecies) buffer.writeShort(value);
			buffer.writeByteArray(topWeights); buffer.writeByteArray(residualWeights);
			buffer.writeByteArray(ph); buffer.writeByteArray(indicatorFraction);
		}

		static ChemicalLayer decode(FriendlyByteBuf buffer) {
			String fingerprint = buffer.readUtf(64);
			int speciesCount = bounded(buffer.readVarInt(), ChemicalLimits.MAX_SPECIES, "chemical species palette");
			ArrayList<FluidChemicalSnapshotPayload.SpeciesEntry> species = new ArrayList<>(speciesCount);
			for (int index = 0; index < speciesCount; index++) species.add(
					new FluidChemicalSnapshotPayload.SpeciesEntry(buffer.readUtf(MAX_KEY_LENGTH), buffer.readInt()));
			int topCount = bounded(buffer.readVarInt(), Math.multiplyExact(MAX_CELLS, ChemicalDisplaySnapshot.TOP_SPECIES),
					"chemical top species");
			short[] top = new short[topCount]; for (int index = 0; index < top.length; index++) top[index] = buffer.readShort();
			return new ChemicalLayer(fingerprint, species, top, buffer.readByteArray(MAX_CHEMICAL_BYTES),
					buffer.readByteArray(MAX_CELLS), buffer.readByteArray(MAX_CELLS), buffer.readByteArray(MAX_CELLS));
		}
	}
}
