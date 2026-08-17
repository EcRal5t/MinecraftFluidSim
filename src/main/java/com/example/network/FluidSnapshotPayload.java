package com.example.network;

import com.example.RealWaterMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A compact clientbound debug snapshot. Volume bytes are unsigned [0, 255]. */
public record FluidSnapshotPayload(
		BlockPos origin,
		float cellSize,
		int sizeX,
		int sizeY,
		int sizeZ,
		byte[] volume,
		byte[] solids,
		boolean debugOverlays
) implements CustomPacketPayload {
	private static final int MAX_CELLS = 1 << 20;

	public static final Type<FluidSnapshotPayload> TYPE = new Type<>(RealWaterMod.id("fluid_snapshot"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FluidSnapshotPayload> CODEC = StreamCodec.of(
			FluidSnapshotPayload::encode,
			FluidSnapshotPayload::decode
	);

	public FluidSnapshotPayload {
		if (sizeX < 0 || sizeY < 0 || sizeZ < 0) throw new IllegalArgumentException("Snapshot dimensions cannot be negative");
		int expected = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (expected != volume.length || expected != solids.length || expected > MAX_CELLS) {
			throw new IllegalArgumentException("Invalid snapshot array length");
		}
	}

	public static FluidSnapshotPayload clear() {
		return new FluidSnapshotPayload(BlockPos.ZERO, 1.0F, 0, 0, 0, new byte[0], new byte[0], false);
	}

	public boolean isClear() {
		return volume.length == 0;
	}

	@Override
	public Type<FluidSnapshotPayload> type() {
		return TYPE;
	}

	private static void encode(RegistryFriendlyByteBuf buffer, FluidSnapshotPayload payload) {
		buffer.writeBlockPos(payload.origin);
		buffer.writeFloat(payload.cellSize);
		buffer.writeVarInt(payload.sizeX);
		buffer.writeVarInt(payload.sizeY);
		buffer.writeVarInt(payload.sizeZ);
		buffer.writeByteArray(payload.volume);
		buffer.writeByteArray(payload.solids);
		buffer.writeBoolean(payload.debugOverlays);
	}

	private static FluidSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
		BlockPos origin = buffer.readBlockPos();
		float cellSize = buffer.readFloat();
		int sizeX = buffer.readVarInt();
		int sizeY = buffer.readVarInt();
		int sizeZ = buffer.readVarInt();
		byte[] volume = buffer.readByteArray(MAX_CELLS);
		byte[] solids = buffer.readByteArray(MAX_CELLS);
		boolean debugOverlays = buffer.readBoolean();
		return new FluidSnapshotPayload(origin, cellSize, sizeX, sizeY, sizeZ, volume, solids, debugOverlays);
	}
}
