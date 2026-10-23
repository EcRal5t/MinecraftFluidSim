package mmm.network;

import mmm.MmmMod;
import mmm.fluid.FluidMaterial;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FluidMaterialRenderSnapshot;
import mmm.fluid.FluidSurfaceInput;
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

/** Clientbound local liquid geometry and display fields. */
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
		FluidSurfaceInput geometry,
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
				&& geometry.equals(other.geometry)
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
		geometry = Objects.requireNonNull(geometry, "geometry");
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
			int cells = boundedCells(sizeX, sizeY, sizeZ);
			if (cells > MAX_CELLS || geometry.sizeX() != sizeX || geometry.sizeY() != sizeY || geometry.sizeZ() != sizeZ
					|| Float.compare(geometry.cellSize(), cellSize) != 0
					|| materialFractions.length != materials.size() || phaseFractions.length != phaseGroups.size()) {
				throw new IllegalArgumentException("Material snapshot field layout is invalid");
			}
			int fieldBytes = Math.multiplyExact(cells, materialFractions.length + phaseFractions.length);
			if ((long) fieldBytes + geometryBytes(geometry) > MAX_TOTAL_FIELD_BYTES) throw new IllegalArgumentException("Material snapshot fields are too large");
			validateFieldLengths(materialFractions, cells);
			validateFieldLengths(phaseFractions, cells);
			validateNormalizedFractions(materialFractions, phaseFractions, cells);
			for (FluidSurfaceInput.Piece piece : geometry.pieces()) {
				if (piece.material() >= materials.size() || piece.phase() >= phaseGroups.size()
						|| !materials.get(piece.material()).phaseGroup().equals(phaseGroups.get(piece.phase()))) {
					throw new IllegalArgumentException("Surface piece palette identity is invalid");
				}
			}
		} else {
			if (sizeX != 0 || sizeY != 0 || sizeZ != 0 || !materials.isEmpty()
					|| !phaseGroups.isEmpty() || !geometry.pieces().isEmpty()
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
				materials, snapshot.phaseGroups(), snapshot.geometry(),
				materialFractions, phaseFractions, debugOverlays, List.of()
		);
	}

	public FluidMaterialRenderSnapshot toRenderSnapshot() {
		if (!isSnapshot()) throw new IllegalStateException("Only material snapshot payloads contain mesher fields");
		List<FluidMaterial> definitions = materials.stream()
				.map(material -> new FluidMaterial(material.id(), material.referenceDensity(), material.phaseGroup(), 0.0, Math.PI / 2.0))
				.toList();
		return FluidMaterialRenderSnapshot.fromDisplayFields(
				sizeX, sizeY, sizeZ, cellSize, definitions, phaseGroups,
				materialFractions, phaseFractions, geometry
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
				BlockPos.ZERO, 1.0F, 0, 0, 0, List.of(), List.of(),
				new FluidSurfaceInput(0, 0, 0, 1.0F, List.of()),
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
		writeGeometry(buffer, payload.geometry);
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
		int cells = boundedCells(sizeX, sizeY, sizeZ);
		int materialCount = readBoundedCount(buffer, MAX_PALETTE_ENTRIES, "material palette");
		List<MaterialEntry> materials = new ArrayList<>(materialCount);
		for (int material = 0; material < materialCount; material++) {
			materials.add(new MaterialEntry(buffer.readUtf(MAX_KEY_LENGTH), Double.longBitsToDouble(buffer.readLong()),
					buffer.readUtf(MAX_KEY_LENGTH), buffer.readInt()));
		}
		int phaseCount = readBoundedCount(buffer, MAX_PALETTE_ENTRIES, "phase palette");
		List<String> phases = new ArrayList<>(phaseCount);
		for (int phase = 0; phase < phaseCount; phase++) phases.add(buffer.readUtf(MAX_KEY_LENGTH));
		FluidSurfaceInput geometry = readGeometry(buffer, sizeX, sizeY, sizeZ, cellSize,
				(long) cells * (materialCount + phaseCount));
		byte[][] materialFractions = readFields(buffer, materialCount, cells);
		byte[][] phaseFractions = readFields(buffer, phaseCount, cells);
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
				origin, cellSize, sizeX, sizeY, sizeZ, materials, phases, geometry,
				materialFractions, phaseFractions, debugOverlays, extensions
		);
	}

	private static int boundedCells(int sizeX, int sizeY, int sizeZ) {
		if (sizeX < 0 || sizeY < 0 || sizeZ < 0
				|| sizeX > MAX_CELLS || sizeY > MAX_CELLS || sizeZ > MAX_CELLS
				|| (long) sizeX * sizeY > MAX_CELLS || (long) sizeX * sizeY * sizeZ > MAX_CELLS) {
			throw new IllegalArgumentException("Material snapshot grid is too large");
		}
		return sizeX * sizeY * sizeZ;
	}

	private static int readBoundedCount(FriendlyByteBuf buffer, int maximum, String label) {
		int count = buffer.readVarInt();
		if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
		return count;
	}

	private static byte[][] readFields(FriendlyByteBuf buffer, int count, int cells) {
		byte[][] result = new byte[count][];
		for (int field = 0; field < count; field++) result[field] = buffer.readByteArray(cells);
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

	private static void validateNormalizedFractions(byte[][] materials, byte[][] phases, int cells) {
		for (int cell = 0; cell < cells; cell++) {
			int materialTotal = 0, phaseTotal = 0;
			for (byte[] field : materials) materialTotal += Byte.toUnsignedInt(field[cell]);
			for (byte[] field : phases) phaseTotal += Byte.toUnsignedInt(field[cell]);
			if ((materialTotal != 0 && materialTotal != 255) || phaseTotal != materialTotal) {
				throw new IllegalArgumentException("Material snapshot fractions do not close");
			}
		}
	}

	private static long geometryBytes(FluidSurfaceInput geometry) {
		long bytes = 4;
		for (FluidSurfaceInput.Piece piece : geometry.pieces()) bytes += 17L + 32L * piece.clips().size();
		return bytes;
	}

	private static void writeGeometry(FriendlyByteBuf buffer, FluidSurfaceInput geometry) {
		buffer.writeVarInt(geometry.pieces().size());
		for (FluidSurfaceInput.Piece piece : geometry.pieces()) {
			buffer.writeVarInt(piece.cell());
			buffer.writeByte(piece.micro());
			buffer.writeVarInt(piece.material());
			buffer.writeVarInt(piece.phase());
			buffer.writeByte(piece.clips().size());
			for (FluidSurfaceInput.Plane plane : piece.clips()) {
				buffer.writeDouble(plane.nx());
				buffer.writeDouble(plane.ny());
				buffer.writeDouble(plane.nz());
				buffer.writeDouble(plane.offset());
			}
		}
	}

	private static FluidSurfaceInput readGeometry(
			FriendlyByteBuf buffer, int sizeX, int sizeY, int sizeZ, float cellSize, long fieldBytes
	) {
		int count = readBoundedCount(buffer, FluidSurfaceInput.MAX_PIECES, "surface pieces");
		long bytes = 4L + fieldBytes;
		if (fieldBytes < 0 || bytes + 17L * count > MAX_TOTAL_FIELD_BYTES) {
			throw new IllegalArgumentException("Material snapshot fields are too large");
		}
		List<FluidSurfaceInput.Piece> pieces = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			int cell = buffer.readVarInt(), micro = buffer.readUnsignedByte();
			int material = buffer.readVarInt(), phase = buffer.readVarInt();
			int clips = buffer.readUnsignedByte();
			if (clips > FluidSurfaceInput.MAX_CLIPS) throw new IllegalArgumentException("Too many surface clips");
			bytes += 17L + 32L * clips;
			if (bytes > MAX_TOTAL_FIELD_BYTES) throw new IllegalArgumentException("Material snapshot fields are too large");
			List<FluidSurfaceInput.Plane> planes = new ArrayList<>(clips);
			for (int clip = 0; clip < clips; clip++) {
				planes.add(new FluidSurfaceInput.Plane(buffer.readDouble(), buffer.readDouble(),
						buffer.readDouble(), buffer.readDouble()));
			}
			pieces.add(new FluidSurfaceInput.Piece(cell, micro, material, phase, planes));
		}
		return new FluidSurfaceInput(sizeX, sizeY, sizeZ, cellSize, pieces);
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
