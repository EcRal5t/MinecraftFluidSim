package mmm.simulation;

import mmm.world.CollisionField;
import mmm.world.CollisionRegion;
import mmm.world.CollisionWorldPort;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Fabric/Minecraft implementation of the loader-neutral collision reader. */
final class MinecraftCollisionWorldPort implements CollisionWorldPort {
	private static final double SAMPLE_INSET = 1.0E-5;
	private final ServerLevel level;

	MinecraftCollisionWorldPort(ServerLevel level) {
		this.level = java.util.Objects.requireNonNull(level, "level");
	}

	@Override public CollisionField capture(CollisionRegion region) {
		if (!level.dimension().identifier().toString().equals(region.dimensionId())) {
			throw new IllegalArgumentException("Collision port dimension mismatch");
		}
		byte[] masks = new byte[Math.multiplyExact(Math.multiplyExact(
				region.cellsX(), region.cellsY()), region.cellsZ())];
		var shapeMasks = new java.util.IdentityHashMap<VoxelShape, byte[]>();
		for (int blockZ = 0; blockZ < region.blocksZ(); blockZ++) {
			for (int blockY = 0; blockY < region.blocksY(); blockY++) {
				for (int blockX = 0; blockX < region.blocksX(); blockX++) {
					boolean interior = region.isInteriorBlock(blockX, blockY, blockZ);
					BlockPos position = new BlockPos(region.originX() + blockX,
							region.originY() + blockY, region.originZ() + blockZ);
					BlockState state = interior ? level.getBlockState(position) : null;
					boolean full = !interior || state.isCollisionShapeFullBlock(level, position);
					VoxelShape shape = full ? Shapes.block() : state.getCollisionShape(level, position);
					byte[] blockMasks = full ? null : shapeMasks.computeIfAbsent(shape,
							value -> sampleBlockMasks(value, region.cellsPerBlock()));
					for (int localZ = 0; localZ < region.cellsPerBlock(); localZ++) {
						for (int localY = 0; localY < region.cellsPerBlock(); localY++) {
							for (int localX = 0; localX < region.cellsPerBlock(); localX++) {
								int cellX = blockX * region.cellsPerBlock() + localX;
								int cellY = blockY * region.cellsPerBlock() + localY;
								int cellZ = blockZ * region.cellsPerBlock() + localZ;
								masks[(cellZ * region.cellsY() + cellY) * region.cellsX() + cellX] =
										full ? (byte) 0xff : blockMasks[(localZ * region.cellsPerBlock() + localY)
												* region.cellsPerBlock() + localX];
							}
						}
					}
				}
			}
		}
		return new CollisionField(region.cellsX(), region.cellsY(), region.cellsZ(), masks);
	}

	static byte[] sampleBlockMasks(VoxelShape shape, int cellsPerBlock) {
		byte[] masks = new byte[Math.multiplyExact(Math.multiplyExact(cellsPerBlock, cellsPerBlock), cellsPerBlock)];
		for (int z = 0; z < cellsPerBlock; z++) for (int y = 0; y < cellsPerBlock; y++) {
			for (int x = 0; x < cellsPerBlock; x++) masks[(z * cellsPerBlock + y) * cellsPerBlock + x] =
					(byte) sampleCutCellMask(shape, x, y, z, cellsPerBlock, SAMPLE_INSET);
		}
		return masks;
	}

	static int sampleCutCellMask(
			VoxelShape shape, int localX, int localY, int localZ, int cellsPerBlock, double inset
	) {
		if (shape.isEmpty()) return 0;
		int subdivisions = cellsPerBlock * 2;
		double size = 1.0 / subdivisions;
		int mask = 0;
		for (int microZ = 0; microZ < 2; microZ++) for (int microY = 0; microY < 2; microY++) {
			for (int microX = 0; microX < 2; microX++) {
				int sampleX = localX * 2 + microX;
				int sampleY = localY * 2 + microY;
				int sampleZ = localZ * 2 + microZ;
				double minX = sampleX * size + inset, minY = sampleY * size + inset;
				double minZ = sampleZ * size + inset;
				double maxX = (sampleX + 1) * size - inset;
				double maxY = (sampleY + 1) * size - inset;
				double maxZ = (sampleZ + 1) * size - inset;
				if (Shapes.joinIsNotEmpty(shape, Shapes.box(minX, minY, minZ, maxX, maxY, maxZ), BooleanOp.AND)) {
					mask |= 1 << (microX + 2 * (microY + 2 * microZ));
				}
			}
		}
		return mask;
	}
}
