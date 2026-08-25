package cc.ecisr.mmm.gpuprobe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.Map;

/** Captures one read-only, fixed-resolution collision mask without loading chunks. */
final class WorldCollisionSnapshot {
	private static final int CELLS_PER_BLOCK = 2;
	private static final double SUBCELL_INSET = 1.0E-5;

	private WorldCollisionSnapshot() { }

	static CaptureResult capture(ServerLevel level, BlockPos origin, int blocksX, int blocksY, int blocksZ) {
		Map<Long, LevelChunk> loadedChunks = new HashMap<>();
		// Include a one-block horizontal halo because contextual collision shapes may inspect neighbours.
		for (int blockZ = -1; blockZ <= blocksZ; blockZ++) {
			for (int blockX = -1; blockX <= blocksX; blockX++) {
				BlockPos sample = origin.offset(blockX, 0, blockZ);
				int chunkX = Math.floorDiv(sample.getX(), 16);
				int chunkZ = Math.floorDiv(sample.getZ(), 16);
				long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
				if (loadedChunks.containsKey(chunkKey)) continue;
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					return CaptureResult.failure("所需区块 " + chunkX + ", " + chunkZ
							+ " 未加载；已拒绝读取且未创建 probe");
				}
				loadedChunks.put(chunkKey, chunk);
			}
		}

		byte[] blockMasks = new byte[blocksX * blocksY * blocksZ];
		int solidBlocks = 0;
		for (int blockZ = 0; blockZ < blocksZ; blockZ++) {
			for (int blockY = 0; blockY < blocksY; blockY++) {
				for (int blockX = 0; blockX < blocksX; blockX++) {
					BlockPos position = origin.offset(blockX, blockY, blockZ);
					int chunkX = Math.floorDiv(position.getX(), 16);
					int chunkZ = Math.floorDiv(position.getZ(), 16);
					long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
					BlockState state = loadedChunks.get(chunkKey).getBlockState(position);
					int mask = collisionMask(state, level, position);
					blockMasks[blockIndex(blockX, blockY, blockZ, blocksX, blocksY)] = (byte) mask;
					if (mask != 0) solidBlocks++;
				}
			}
		}

		byte[] solidCells = expandBlockMasks(blocksX, blocksY, blocksZ, blockMasks);
		return CaptureResult.success(solidCells, countSolidCells(solidCells), solidBlocks, loadedChunks.size());
	}

	/** Expands each block's eight local collision bits into x-major solver-cell storage. */
	static byte[] expandBlockMasks(int blocksX, int blocksY, int blocksZ, byte[] blockMasks) {
		if (blocksX <= 0 || blocksY <= 0 || blocksZ <= 0) {
			throw new IllegalArgumentException("invalid block collision mask dimensions");
		}
		int expectedBlocks = Math.multiplyExact(Math.multiplyExact(blocksX, blocksY), blocksZ);
		if (blockMasks.length != expectedBlocks) {
			throw new IllegalArgumentException("invalid block collision mask dimensions");
		}
		int nx = blocksX * CELLS_PER_BLOCK;
		int ny = blocksY * CELLS_PER_BLOCK;
		int nz = blocksZ * CELLS_PER_BLOCK;
		byte[] result = new byte[Math.multiplyExact(Math.multiplyExact(nx, ny), nz)];
		for (int blockZ = 0; blockZ < blocksZ; blockZ++) {
			for (int blockY = 0; blockY < blocksY; blockY++) {
				for (int blockX = 0; blockX < blocksX; blockX++) {
					int mask = Byte.toUnsignedInt(blockMasks[blockIndex(blockX, blockY, blockZ, blocksX, blocksY)]);
					for (int localZ = 0; localZ < CELLS_PER_BLOCK; localZ++) {
						for (int localY = 0; localY < CELLS_PER_BLOCK; localY++) {
							for (int localX = 0; localX < CELLS_PER_BLOCK; localX++) {
								int bit = localX + CELLS_PER_BLOCK * (localY + CELLS_PER_BLOCK * localZ);
								if ((mask & (1 << bit)) == 0) continue;
								int x = blockX * CELLS_PER_BLOCK + localX;
								int y = blockY * CELLS_PER_BLOCK + localY;
								int z = blockZ * CELLS_PER_BLOCK + localZ;
								result[x + nx * (y + ny * z)] = 1;
							}
						}
					}
				}
			}
		}
		return result;
	}

	static int countSolidCells(byte[] cells) {
		int count = 0;
		for (byte cell : cells) if (cell != 0) count++;
		return count;
	}

	private static int collisionMask(BlockState state, ServerLevel level, BlockPos position) {
		if (state.isCollisionShapeFullBlock(level, position)) return 0xff;
		VoxelShape shape = state.getCollisionShape(level, position);
		if (shape.isEmpty()) return 0;
		int mask = 0;
		for (int localZ = 0; localZ < CELLS_PER_BLOCK; localZ++) {
			for (int localY = 0; localY < CELLS_PER_BLOCK; localY++) {
				for (int localX = 0; localX < CELLS_PER_BLOCK; localX++) {
					if (!intersectsSubcell(shape, localX, localY, localZ)) continue;
					int bit = localX + CELLS_PER_BLOCK * (localY + CELLS_PER_BLOCK * localZ);
					mask |= 1 << bit;
				}
			}
		}
		return mask;
	}

	private static boolean intersectsSubcell(VoxelShape shape, int localX, int localY, int localZ) {
		double size = 1.0 / CELLS_PER_BLOCK;
		double minX = localX * size + SUBCELL_INSET;
		double minY = localY * size + SUBCELL_INSET;
		double minZ = localZ * size + SUBCELL_INSET;
		double maxX = (localX + 1) * size - SUBCELL_INSET;
		double maxY = (localY + 1) * size - SUBCELL_INSET;
		double maxZ = (localZ + 1) * size - SUBCELL_INSET;
		return Shapes.joinIsNotEmpty(shape, Shapes.box(minX, minY, minZ, maxX, maxY, maxZ), BooleanOp.AND);
	}

	private static int blockIndex(int x, int y, int z, int blocksX, int blocksY) {
		return x + blocksX * (y + blocksY * z);
	}

	record CaptureResult(boolean captured, byte[] solidCells, int solidCellCount, int solidBlockCount,
	                     int loadedChunkCount, String failure) {
		private static CaptureResult success(byte[] solidCells, int solidCellCount, int solidBlockCount, int loadedChunkCount) {
			return new CaptureResult(true, solidCells, solidCellCount, solidBlockCount, loadedChunkCount, null);
		}

		private static CaptureResult failure(String failure) {
			return new CaptureResult(false, null, 0, 0, 0, failure);
		}
	}
}
