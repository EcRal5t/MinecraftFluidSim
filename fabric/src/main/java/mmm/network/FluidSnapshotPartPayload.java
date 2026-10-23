package mmm.network;

import io.netty.buffer.Unpooled;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordered transport chunks; the complete snapshot enters region ordering only after assembly. */
public record FluidSnapshotPartPayload(FluidProtocol.Layout layout, int totalBytes, int offset, byte[] data)
		implements CustomPacketPayload {
	public static final int MAX_PART_BYTES = 512 << 10;
	public static final int MAX_SNAPSHOT_BYTES = 32 << 20;
	public static final Type<FluidSnapshotPartPayload> TYPE = new Type<>(MmmMod.id("fluid_snapshot_part"));
	public static final StreamCodec<FriendlyByteBuf, FluidSnapshotPartPayload> CODEC = StreamCodec.of(
			(buffer, part) -> {
				buffer.writeEnum(part.layout);
				buffer.writeVarInt(part.totalBytes);
				buffer.writeVarInt(part.offset);
				buffer.writeByteArray(part.data);
			},
			buffer -> new FluidSnapshotPartPayload(buffer.readEnum(FluidProtocol.Layout.class),
					buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(MAX_PART_BYTES)));

	public FluidSnapshotPartPayload {
		Objects.requireNonNull(layout, "layout");
		data = Objects.requireNonNull(data, "data").clone();
		if (layout == FluidProtocol.Layout.PURE || totalBytes <= 0 || totalBytes > MAX_SNAPSHOT_BYTES
				|| offset < 0 || data.length == 0 || data.length > MAX_PART_BYTES
				|| (long) offset + data.length > totalBytes) {
			throw new IllegalArgumentException("Invalid snapshot transport part");
		}
	}

	@Override public byte[] data() { return data.clone(); }
	@Override public Type<FluidSnapshotPartPayload> type() { return TYPE; }

	public static List<FluidSnapshotPartPayload> split(CustomPacketPayload payload) {
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidProtocol.Layout layout = switch (payload) {
				case FluidMaterialSnapshotPayload value -> { FluidMaterialSnapshotPayload.CODEC.encode(buffer, value); yield FluidProtocol.Layout.MATERIAL; }
				case FluidChemicalSnapshotPayload value -> { FluidChemicalSnapshotPayload.CODEC.encode(buffer, value); yield FluidProtocol.Layout.CHEMICAL; }
				case FluidGranularSnapshotPayload value -> { FluidGranularSnapshotPayload.CODEC.encode(buffer, value); yield FluidProtocol.Layout.GRANULAR; }
				case FluidThermalSnapshotPayload value -> { FluidThermalSnapshotPayload.CODEC.encode(buffer, value); yield FluidProtocol.Layout.THERMAL; }
				default -> throw new IllegalArgumentException("Unsupported snapshot transport layout");
			};
			int total = buffer.readableBytes();
			if (total > MAX_SNAPSHOT_BYTES) throw new IllegalArgumentException("Snapshot transport limit exceeded");
			List<FluidSnapshotPartPayload> parts = new ArrayList<>();
			for (int offset = 0; offset < total; offset += MAX_PART_BYTES) {
				byte[] data = new byte[Math.min(MAX_PART_BYTES, total - offset)];
				buffer.readBytes(data);
				parts.add(new FluidSnapshotPartPayload(layout, total, offset, data));
			}
			return List.copyOf(parts);
		} finally {
			buffer.release();
		}
	}

	/** One connection, one contiguous server send; partial data never changes the displayed frame. */
	public static final class Assembler {
		private byte[] pending;
		private FluidProtocol.Layout layout;
		private int received;

		public synchronized CustomPacketPayload accept(FluidSnapshotPartPayload part) {
			if (part.offset == 0) {
				pending = new byte[part.totalBytes];
				layout = part.layout;
				received = 0;
			}
			if (pending == null || part.layout != layout || part.totalBytes != pending.length || part.offset != received) {
				clear();
				throw new IllegalArgumentException("Snapshot transport parts are not contiguous");
			}
			System.arraycopy(part.data, 0, pending, received, part.data.length);
			received += part.data.length;
			if (received != pending.length) return null;
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(pending));
			FluidProtocol.Layout completedLayout = layout;
			clear();
			try {
				CustomPacketPayload payload = switch (completedLayout) {
					case MATERIAL -> FluidMaterialSnapshotPayload.CODEC.decode(buffer);
					case CHEMICAL -> FluidChemicalSnapshotPayload.CODEC.decode(buffer);
					case GRANULAR -> FluidGranularSnapshotPayload.CODEC.decode(buffer);
					case THERMAL -> FluidThermalSnapshotPayload.CODEC.decode(buffer);
					default -> throw new IllegalArgumentException("Unsupported snapshot transport layout");
				};
				if (buffer.isReadable()) throw new IllegalArgumentException("Trailing snapshot transport data");
				return payload;
			} finally {
				buffer.release();
			}
		}

		public synchronized void clear() {
			pending = null;
			layout = null;
			received = 0;
		}
	}
}
