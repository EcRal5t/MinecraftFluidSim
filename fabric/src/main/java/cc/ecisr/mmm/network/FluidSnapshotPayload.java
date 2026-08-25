package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Clientbound pure-fluid region snapshot. Density bytes remain unsigned reconstruction data, never exact water. */
public record FluidSnapshotPayload(
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
		byte[] volume,
		byte[] markers,
		byte[] solids,
		boolean debugOverlays,
		List<Extension> extensions
) implements CustomPacketPayload {
	private static final int MAX_CELLS = 1 << 20;
	public static final int MAX_EXTENSIONS = 16;
	public static final int MAX_EXTENSION_BYTES = 1 << 16;
	private static final UUID NIL_REGION_ID = new UUID(0L, 0L);
	private static final Identifier CLEAR_DIMENSION = Identifier.fromNamespaceAndPath("minecraft", "overworld");

	public static final Type<FluidSnapshotPayload> TYPE = new Type<>(MmmMod.id("fluid_snapshot"));

	/** Extension type carrying the per-cell sealed gas node mask for debug overlays. */
	public static final int EXTENSION_GAS_NODES = 1;

	public static final StreamCodec<FriendlyByteBuf, FluidSnapshotPayload> CODEC = StreamCodec.of(
			FluidSnapshotPayload::encode,
			FluidSnapshotPayload::decode
	);

	public FluidSnapshotPayload {
		if (layout != FluidProtocol.Layout.PURE) throw new IllegalArgumentException("Pure snapshot layout is required");
		kind = Objects.requireNonNull(kind, "kind");
		dimension = Objects.requireNonNull(dimension, "dimension");
		regionId = Objects.requireNonNull(regionId, "regionId");
		origin = Objects.requireNonNull(origin, "origin").immutable();
		volume = Objects.requireNonNull(volume, "volume");
		markers = Objects.requireNonNull(markers, "markers");
		solids = Objects.requireNonNull(solids, "solids");
		extensions = List.copyOf(Objects.requireNonNull(extensions, "extensions"));
		if (extensions.size() > MAX_EXTENSIONS) throw new IllegalArgumentException("Too many snapshot extensions");
		int extensionBytes = 0;
		for (Extension extension : extensions) extensionBytes = Math.addExact(extensionBytes, extension.data().length);
		if (extensionBytes > MAX_EXTENSION_BYTES) throw new IllegalArgumentException("Snapshot extensions are too large");

		if (kind == Kind.SNAPSHOT) {
			if (regionId.equals(NIL_REGION_ID) || generation <= 0L || sequence <= 0L) {
				throw new IllegalArgumentException("Snapshot identity, generation and sequence must be positive");
			}
			if (!(cellSize > 0.0F) || !Float.isFinite(cellSize)) {
				throw new IllegalArgumentException("Snapshot cell size must be finite and positive");
			}
			if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
				throw new IllegalArgumentException("Snapshot dimensions must be positive");
			}
			int expected = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
			if (expected > MAX_CELLS || expected != volume.length || expected != markers.length || expected != solids.length) {
				throw new IllegalArgumentException("Invalid snapshot array length");
			}
		} else {
			if (sizeX != 0 || sizeY != 0 || sizeZ != 0 || volume.length != 0 || markers.length != 0 || solids.length != 0
					|| !extensions.isEmpty()) {
				throw new IllegalArgumentException("Removal payloads cannot carry grid or extension data");
			}
			if (kind == Kind.REMOVE_REGION) {
				if (regionId.equals(NIL_REGION_ID) || generation <= 0L || sequence <= 0L) {
					throw new IllegalArgumentException("Region removal requires ordered region identity");
				}
			} else if (!regionId.equals(NIL_REGION_ID) || generation != 0L || sequence != 0L) {
				throw new IllegalArgumentException("Clear-all payload cannot identify one region");
			}
		}
	}

	public static FluidSnapshotPayload snapshot(
			Identifier dimension,
			UUID regionId,
			long generation,
			long sequence,
			BlockPos origin,
			float cellSize,
			int sizeX,
			int sizeY,
			int sizeZ,
			byte[] volume,
			byte[] markers,
			byte[] solids,
			boolean debugOverlays
	) {
		return new FluidSnapshotPayload(
				FluidProtocol.Layout.PURE, Kind.SNAPSHOT, dimension, regionId, generation, sequence,
				origin, cellSize, sizeX, sizeY, sizeZ,
				volume, markers, solids, debugOverlays, List.of()
		);
	}

	public static FluidSnapshotPayload snapshot(
			Identifier dimension,
			UUID regionId,
			long generation,
			long sequence,
			BlockPos origin,
			float cellSize,
			int sizeX,
			int sizeY,
			int sizeZ,
			byte[] volume,
			byte[] markers,
			byte[] solids,
			boolean debugOverlays,
			List<Extension> extensions
	) {
		return new FluidSnapshotPayload(
				FluidProtocol.Layout.PURE, Kind.SNAPSHOT, dimension, regionId, generation, sequence,
				origin, cellSize, sizeX, sizeY, sizeZ,
				volume, markers, solids, debugOverlays, extensions
		);
	}

	public static FluidSnapshotPayload removeRegion(
			Identifier dimension,
			UUID regionId,
			long generation,
			long sequence
	) {
		return empty(Kind.REMOVE_REGION, dimension, regionId, generation, sequence);
	}

	public static FluidSnapshotPayload clearAll() {
		return empty(Kind.CLEAR_ALL, CLEAR_DIMENSION, NIL_REGION_ID, 0L, 0L);
	}

	private static FluidSnapshotPayload empty(
			Kind kind,
			Identifier dimension,
			UUID regionId,
			long generation,
			long sequence
	) {
		return new FluidSnapshotPayload(
				FluidProtocol.Layout.PURE, kind, dimension, regionId, generation, sequence,
				BlockPos.ZERO, 1.0F, 0, 0, 0, new byte[0], new byte[0], new byte[0], false, List.of()
		);
	}

	public boolean isSnapshot() { return kind == Kind.SNAPSHOT; }
	public boolean removesRegion() { return kind == Kind.REMOVE_REGION; }
	public boolean clearsAll() { return kind == Kind.CLEAR_ALL; }

	@Override
	public Type<FluidSnapshotPayload> type() {
		return TYPE;
	}

	private static void encode(FriendlyByteBuf buffer, FluidSnapshotPayload payload) {
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
		buffer.writeByteArray(payload.volume);
		buffer.writeByteArray(payload.markers);
		buffer.writeByteArray(payload.solids);
		buffer.writeBoolean(payload.debugOverlays);
		buffer.writeVarInt(payload.extensions.size());
		for (Extension extension : payload.extensions) {
			buffer.writeVarInt(extension.type());
			buffer.writeByteArray(extension.data());
		}
	}

	private static FluidSnapshotPayload decode(FriendlyByteBuf buffer) {
		FluidProtocol.Layout layout = buffer.readEnum(FluidProtocol.Layout.class);
		Kind kind = buffer.readEnum(Kind.class);
		Identifier dimension = buffer.readIdentifier();
		UUID regionId = buffer.readUUID();
		long generation = buffer.readVarLong();
		long sequence = buffer.readVarLong();
		BlockPos origin = buffer.readBlockPos();
		float cellSize = buffer.readFloat();
		int sizeX = buffer.readVarInt();
		int sizeY = buffer.readVarInt();
		int sizeZ = buffer.readVarInt();
		byte[] volume = buffer.readByteArray(MAX_CELLS);
		byte[] markers = buffer.readByteArray(MAX_CELLS);
		byte[] solids = buffer.readByteArray(MAX_CELLS);
		boolean debugOverlays = buffer.readBoolean();
		int extensionCount = buffer.readVarInt();
		if (extensionCount < 0 || extensionCount > MAX_EXTENSIONS) {
			throw new IllegalArgumentException("Invalid snapshot extension count");
		}
		List<Extension> extensions = new ArrayList<>(extensionCount);
		int extensionBytes = 0;
		for (int index = 0; index < extensionCount; index++) {
			int type = buffer.readVarInt();
			byte[] data = buffer.readByteArray(MAX_EXTENSION_BYTES - extensionBytes);
			extensionBytes = Math.addExact(extensionBytes, data.length);
			extensions.add(new Extension(type, data));
		}
		return new FluidSnapshotPayload(
				layout, kind, dimension, regionId, generation, sequence,
				origin, cellSize, sizeX, sizeY, sizeZ, volume, markers, solids, debugOverlays, extensions
		);
	}

	public enum Kind {
		SNAPSHOT,
		REMOVE_REGION,
		CLEAR_ALL
	}

	public record Extension(int type, byte[] data) {
		public Extension {
			if (type < 0) throw new IllegalArgumentException("Extension type cannot be negative");
			data = Objects.requireNonNull(data, "data").clone();
			if (data.length > MAX_EXTENSION_BYTES) throw new IllegalArgumentException("Snapshot extension is too large");
		}

		@Override
		public byte[] data() {
			return data.clone();
		}
	}
}
