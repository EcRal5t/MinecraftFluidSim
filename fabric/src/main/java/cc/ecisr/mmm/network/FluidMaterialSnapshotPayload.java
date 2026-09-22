package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidMaterialRenderSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Clientbound material reconstruction payload. It never carries exact marker mass. */
public record FluidMaterialSnapshotPayload(
		FluidProtocol.Layout layout,
		Kind kind,
		Identifier dimension,
		UUID regionId,
		long generation,
		long sequence,
		BlockPos origin,
		float cellSize,
		int sizeX,
		int sizeY,
		int sizeZ,
		List<MaterialEntry> materials,
		List<String> phaseGroups,
		byte[] totalVolume,
		byte[] markers,
		byte[] solids,
		byte[][] materialFractions,
		byte[][] phaseFractions,
		boolean debugOverlays,
		List<Extension> extensions
) implements CustomPacketPayload {
	private static final int MAX_CELLS = 1 << 20;
	private static final int MAX_PALETTE_ENTRIES = 16;
	private static final int MAX_TOTAL_FIELD_BYTES = 16 << 20;
	private static final int MAX_EXTENSIONS = 16;
	private static final int MAX_EXTENSION_BYTES = 1 << 16;
	private static final int MAX_KEY_LENGTH = 128;
	private static final UUID NIL_REGION_ID = new UUID(0L, 0L);
	private static final Identifier CLEAR_DIMENSION = Identifier.fromNamespaceAndPath("minecraft", "overworld");

	public static final Type<FluidMaterialSnapshotPayload> TYPE = new Type<>(MmmMod.id("fluid_material_snapshot"));
	public static final StreamCodec<FriendlyByteBuf, FluidMaterialSnapshotPayload> CODEC = StreamCodec.of(
			FluidMaterialSnapshotPayload::encode,
			FluidMaterialSnapshotPayload::decode
	);

	/** Fields that determine local surface geometry and material attribution. */
	public boolean sameSurface(FluidMaterialSnapshotPayload other) {
		return other != null && sizeX == other.sizeX && sizeY == other.sizeY && sizeZ == other.sizeZ
				&& Float.compare(cellSize, other.cellSize) == 0 && materials.equals(other.materials)
				&& phaseGroups.equals(other.phaseGroups)
				&& java.util.Arrays.equals(totalVolume, other.totalVolume)
				&& java.util.Arrays.equals(markers, other.markers) && java.util.Arrays.equals(solids, other.solids)
				&& java.util.Arrays.deepEquals(materialFractions, other.materialFractions)
				&& java.util.Arrays.deepEquals(phaseFractions, other.phaseFractions);
	}

	public FluidMaterialSnapshotPayload {
		if (layout != FluidProtocol.Layout.MATERIAL) throw new IllegalArgumentException("Material snapshot layout is required");
		kind = Objects.requireNonNull(kind, "kind");
		dimension = Objects.requireNonNull(dimension, "dimension");
		regionId = Objects.requireNonNull(regionId, "regionId");
		origin = Objects.requireNonNull(origin, "origin").immutable();
		materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
		phaseGroups = List.copyOf(Objects.requireNonNull(phaseGroups, "phaseGroups"));
		totalVolume = Objects.requireNonNull(totalVolume, "totalVolume").clone();
		markers = Objects.requireNonNull(markers, "markers").clone();
		solids = Objects.requireNonNull(solids, "solids").clone();
		materialFractions = deepCopy(Objects.requireNonNull(materialFractions, "materialFractions"));
		phaseFractions = deepCopy(Objects.requireNonNull(phaseFractions, "phaseFractions"));
		extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
		if (materials.size() > MAX_PALETTE_ENTRIES || phaseGroups.size() > MAX_PALETTE_ENTRIES) {
			throw new IllegalArgumentException("Material snapshot palette is too large");
		}
		validatePalette(materials, phaseGroups);
		if (extensions.size() > MAX_EXTENSIONS) throw new IllegalArgumentException("Too many material snapshot extensions");
		int extensionBytes = 0;
		for (Extension extension : extensions) extensionBytes = Math.addExact(extensionBytes, extension.data().length);
		if (extensionBytes > MAX_EXTENSION_BYTES) throw new IllegalArgumentException("Material snapshot extensions are too large");

		if (kind == Kind.SNAPSHOT) {
			if (regionId.equals(NIL_REGION_ID) || generation <= 0L || sequence <= 0L) {
				throw new IllegalArgumentException("Material snapshot identity is invalid");
			}
			if (!(Float.isFinite(cellSize) && cellSize > 0.0F) || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
				throw new IllegalArgumentException("Material snapshot geometry is invalid");
			}
			int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
			if (cells > MAX_CELLS || totalVolume.length != cells || markers.length != cells || solids.length != cells
					|| materialFractions.length != materials.size() || phaseFractions.length != phaseGroups.size()) {
				throw new IllegalArgumentException("Material snapshot field layout is invalid");
			}
			int fieldBytes = Math.multiplyExact(cells, Math.addExact(3, materialFractions.length + phaseFractions.length));
			if (fieldBytes > MAX_TOTAL_FIELD_BYTES) throw new IllegalArgumentException("Material snapshot fields are too large");
			validateFieldLengths(materialFractions, cells);
			validateFieldLengths(phaseFractions, cells);
			validateNormalizedFractions(markers, materialFractions, "material");
			validateNormalizedFractions(markers, phaseFractions, "phase");
		} else {
			if (sizeX != 0 || sizeY != 0 || sizeZ != 0 || !materials.isEmpty()
					|| !phaseGroups.isEmpty() || totalVolume.length != 0 || markers.length != 0 || solids.length != 0
					|| materialFractions.length != 0 || phaseFractions.length != 0 || !extensions.isEmpty()) {
				throw new IllegalArgumentException("Material removal payloads cannot carry reconstruction state");
			}
			if (kind == Kind.REMOVE_REGION) {
				if (regionId.equals(NIL_REGION_ID) || generation <= 0L || sequence <= 0L) {
					throw new IllegalArgumentException("Material region removal requires ordered identity");
				}
			} else if (!regionId.equals(NIL_REGION_ID) || generation != 0L || sequence != 0L) {
				throw new IllegalArgumentException("Material clear-all cannot identify one region");
			}
		}
	}

	@Override public byte[] totalVolume() { return totalVolume.clone(); }
	@Override public byte[] markers() { return markers.clone(); }
	@Override public byte[] solids() { return solids.clone(); }
	@Override public byte[][] materialFractions() { return deepCopy(materialFractions); }
	@Override public byte[][] phaseFractions() { return deepCopy(phaseFractions); }

	public static FluidMaterialSnapshotPayload removeRegion(
			Identifier dimension, UUID regionId, long generation, long sequence
	) {
		return empty(Kind.REMOVE_REGION, dimension, regionId, generation, sequence);
	}

	public static FluidMaterialSnapshotPayload snapshot(
			Identifier dimension, UUID regionId, long generation, long sequence, BlockPos origin,
			FluidMaterialRenderSnapshot snapshot, boolean debugOverlays
	) {
		List<MaterialEntry> materials = snapshot.materials().stream()
				.map(material -> new MaterialEntry(material.id(), material.referenceDensity(), material.miscibilityGroup(),
						BuiltinFluidMaterials.displayArgb(material.id())))
				.toList();
		byte[][] materialFractions = new byte[materials.size()][];
		for (int material = 0; material < materialFractions.length; material++) {
			materialFractions[material] = snapshot.copyMaterialFractions(material);
		}
		byte[][] phaseFractions = new byte[snapshot.phaseGroups().size()][];
		for (int phase = 0; phase < phaseFractions.length; phase++) phaseFractions[phase] = snapshot.copyPhaseFractions(phase);
		return new FluidMaterialSnapshotPayload(
				FluidProtocol.Layout.MATERIAL, Kind.SNAPSHOT, dimension, regionId, generation, sequence,
				origin, snapshot.cellSize(), snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(),
				materials, snapshot.phaseGroups(), snapshot.copyTotalVolume(), snapshot.copyMarkers(), snapshot.copySolids(),
				materialFractions, phaseFractions, debugOverlays, List.of()
		);
	}

	public FluidMaterialRenderSnapshot toRenderSnapshot() {
		if (!isSnapshot()) throw new IllegalStateException("Only material snapshot payloads contain mesher fields");
		List<FluidMaterial> definitions = materials.stream()
				.map(material -> new FluidMaterial(material.id(), material.referenceDensity(), material.phaseGroup(), 0.0, Math.PI / 2.0))
				.toList();
		return FluidMaterialRenderSnapshot.fromQuantizedFields(
				sizeX, sizeY, sizeZ, cellSize, definitions, phaseGroups, totalVolume, markers, solids,
				materialFractions, phaseFractions
		);
	}

	public static FluidMaterialSnapshotPayload clearAll() {
		return empty(Kind.CLEAR_ALL, CLEAR_DIMENSION, NIL_REGION_ID, 0L, 0L);
	}

	private static FluidMaterialSnapshotPayload empty(
			Kind kind, Identifier dimension, UUID regionId, long generation, long sequence
	) {
		return new FluidMaterialSnapshotPayload(
				FluidProtocol.Layout.MATERIAL, kind, dimension, regionId, generation, sequence,
				BlockPos.ZERO, 1.0F, 0, 0, 0, List.of(), List.of(), new byte[0], new byte[0], new byte[0],
				new byte[0][], new byte[0][], false, List.of()
		);
	}

	public boolean isSnapshot() { return kind == Kind.SNAPSHOT; }
	public boolean removesRegion() { return kind == Kind.REMOVE_REGION; }
	public boolean clearsAll() { return kind == Kind.CLEAR_ALL; }

	@Override public Type<FluidMaterialSnapshotPayload> type() { return TYPE; }

	static void encode(FriendlyByteBuf buffer, FluidMaterialSnapshotPayload payload) {
		buffer.writeEnum(payload.layout);
		buffer.writeEnum(payload.kind);
		buffer.writeIdentifier(payload.dimension);
		buffer.writeUUID(payload.regionId);
		buffer.writeVarLong(payload.generation);
		buffer.writeVarLong(payload.sequence);
		buffer.writeBlockPos(payload.origin);
		buffer.writeFloat(payload.cellSize);
		buffer.writeVarInt(payload.sizeX);
		buffer.writeVarInt(payload.sizeY);
		buffer.writeVarInt(payload.sizeZ);
		buffer.writeVarInt(payload.materials.size());
		for (MaterialEntry material : payload.materials) {
			buffer.writeUtf(material.id(), MAX_KEY_LENGTH);
			buffer.writeLong(Double.doubleToRawLongBits(material.referenceDensity()));
			buffer.writeUtf(material.phaseGroup(), MAX_KEY_LENGTH);
			buffer.writeInt(material.argb());
		}
		buffer.writeVarInt(payload.phaseGroups.size());
		for (String phase : payload.phaseGroups) buffer.writeUtf(phase, MAX_KEY_LENGTH);
		buffer.writeByteArray(payload.totalVolume);
		buffer.writeByteArray(payload.markers);
		buffer.writeByteArray(payload.solids);
		for (byte[] field : payload.materialFractions) buffer.writeByteArray(field);
		for (byte[] field : payload.phaseFractions) buffer.writeByteArray(field);
		buffer.writeBoolean(payload.debugOverlays);
		buffer.writeVarInt(payload.extensions.size());
		for (Extension extension : payload.extensions) {
			buffer.writeVarInt(extension.type());
			buffer.writeByteArray(extension.data());
		}
	}

	static FluidMaterialSnapshotPayload decode(FriendlyByteBuf buffer) {
		FluidProtocol.Layout layout = buffer.readEnum(FluidProtocol.Layout.class);
		Kind kind = buffer.readEnum(Kind.class);
		Identifier dimension = buffer.readIdentifier();
		UUID regionId = buffer.readUUID();
		long generation = buffer.readVarLong();
		long sequence = buffer.readVarLong();
		BlockPos origin = buffer.readBlockPos();
		float cellSize = buffer.readFloat();
		int sizeX = buffer.readVarInt(), sizeY = buffer.readVarInt(), sizeZ = buffer.readVarInt();
		int materialCount = readBoundedCount(buffer, MAX_PALETTE_ENTRIES, "material palette");
		List<MaterialEntry> materials = new ArrayList<>(materialCount);
		for (int material = 0; material < materialCount; material++) {
			materials.add(new MaterialEntry(buffer.readUtf(MAX_KEY_LENGTH), Double.longBitsToDouble(buffer.readLong()),
					buffer.readUtf(MAX_KEY_LENGTH), buffer.readInt()));
		}
		int phaseCount = readBoundedCount(buffer, MAX_PALETTE_ENTRIES, "phase palette");
		List<String> phases = new ArrayList<>(phaseCount);
		for (int phase = 0; phase < phaseCount; phase++) phases.add(buffer.readUtf(MAX_KEY_LENGTH));
		byte[] totalVolume = buffer.readByteArray(MAX_CELLS);
		byte[] markers = buffer.readByteArray(MAX_CELLS);
		byte[] solids = buffer.readByteArray(MAX_CELLS);
		byte[][] materialFractions = readFields(buffer, materialCount);
		byte[][] phaseFractions = readFields(buffer, phaseCount);
		boolean debugOverlays = buffer.readBoolean();
		int extensionCount = readBoundedCount(buffer, MAX_EXTENSIONS, "material snapshot extensions");
		List<Extension> extensions = new ArrayList<>(extensionCount);
		int extensionBytes = 0;
		for (int extension = 0; extension < extensionCount; extension++) {
			int type = buffer.readVarInt();
			byte[] data = buffer.readByteArray(MAX_EXTENSION_BYTES - extensionBytes);
			extensionBytes = Math.addExact(extensionBytes, data.length);
			extensions.add(new Extension(type, data));
		}
		return new FluidMaterialSnapshotPayload(
				layout, kind, dimension, regionId, generation, sequence,
				origin, cellSize, sizeX, sizeY, sizeZ, materials, phases, totalVolume, markers, solids,
				materialFractions, phaseFractions, debugOverlays, extensions
		);
	}

	private static int readBoundedCount(FriendlyByteBuf buffer, int maximum, String label) {
		int count = buffer.readVarInt();
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
		return count;
	}

	private static byte[][] readFields(FriendlyByteBuf buffer, int count) {
		byte[][] result = new byte[count][];
		for (int field = 0; field < count; field++) result[field] = buffer.readByteArray(MAX_CELLS);
		return result;
	}

	private static void validatePalette(List<MaterialEntry> materials, List<String> phaseGroups) {
		Set<String> materialIds = new HashSet<>(), phases = new HashSet<>();
		for (String phase : phaseGroups) {
			new FluidMaterial("mmm:validation", 1.0, phase, 0.0, Math.PI / 2.0);
			if (!phases.add(phase)) throw new IllegalArgumentException("Duplicate phase group " + phase);
		}
		for (MaterialEntry material : materials) {
			if (!materialIds.add(material.id())) throw new IllegalArgumentException("Duplicate material " + material.id());
			if (!phases.contains(material.phaseGroup())) throw new IllegalArgumentException("Material references an unknown phase group");
		}
	}

	private static void validateFieldLengths(byte[][] fields, int cells) {
		for (byte[] field : fields) if (field.length != cells) throw new IllegalArgumentException("Material field length mismatch");
	}

	private static void validateNormalizedFractions(byte[] markers, byte[][] fields, String label) {
		for (int cell = 0; cell < markers.length; cell++) {
			int total = 0;
			for (byte[] field : fields) total += Byte.toUnsignedInt(field[cell]);
			int expected = markers[cell] == 0 ? 0 : 255;
			if (total != expected) throw new IllegalArgumentException("Material snapshot " + label + " fractions do not close");
		}
	}

	private static byte[][] deepCopy(byte[][] fields) {
		byte[][] result = new byte[fields.length][];
		for (int field = 0; field < fields.length; field++) {
			result[field] = Objects.requireNonNull(fields[field], "field").clone();
		}
		return result;
	}

	public enum Kind { SNAPSHOT, REMOVE_REGION, CLEAR_ALL }

	public record MaterialEntry(String id, double referenceDensity, String phaseGroup, int argb) {
		public MaterialEntry {
			new FluidMaterial(id, referenceDensity, phaseGroup, 0.0, Math.PI / 2.0);
		}
	}

	public record Extension(int type, byte[] data) {
		public Extension {
			if (type < 0) throw new IllegalArgumentException("Extension type cannot be negative");
			data = Objects.requireNonNull(data, "data").clone();
			if (data.length > MAX_EXTENSION_BYTES) throw new IllegalArgumentException("Extension is too large");
		}
		@Override public byte[] data() { return data.clone(); }
	}
}
