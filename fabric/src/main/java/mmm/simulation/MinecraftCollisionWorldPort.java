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
	private final java.util.function.IntPredicate availableCell;
	private final java.util.function.IntUnaryOperator previousMask;
    private final mmm.spatial.SpatialGrid grid;

	MinecraftCollisionWorldPort(ServerLevel level) {
		this(level, (mmm.fluid.HostFluidState)null);
	}

	MinecraftCollisionWorldPort(ServerLevel level, mmm.fluid.HostFluidState previous) {
		this.level = java.util.Objects.requireNonNull(level, "level");
		this.availableCell = previous==null?null:previous::groupAvailable;
        this.grid = previous == null ? null : previous.grid();
        this.previousMask = previous==null?null:cell->previous.solidMicroMask(grid.x(cell),grid.y(cell),grid.z(cell));
	}

	MinecraftCollisionWorldPort(ServerLevel level, FluidRegion region) {
		this.level = java.util.Objects.requireNonNull(level);
		this.availableCell = region::groupAvailable;
        this.grid = region.grid();
        this.previousMask = cell->region.solidMicroMask(grid.x(cell),grid.y(cell),grid.z(cell));
	}

	@Override public CollisionField capture(CollisionRegion region) {
		if (!level.dimension().identifier().toString().equals(region.dimensionId())) {
			throw new IllegalArgumentException("Collision port dimension mismatch");
		}
		var topology = region.grid();
        if (grid != null && !grid.equals(topology))
            throw new IllegalArgumentException("Collision port topology differs from capture");
        byte[] masks = new byte[topology.cellCount()];
        var shapeMasks = new java.util.IdentityHashMap<VoxelShape, byte[]>();
        var blockMasks = new java.util.HashMap<BlockPos, byte[]>();
        int n = region.cellsPerBlock();
        for (int cell=0;cell<topology.cellCount();cell++) {
            int x=topology.x(cell),y=topology.y(cell),z=topology.z(cell);
            int bx=x/n,by=y/n,bz=z/n;
            BlockPos position=new BlockPos(region.originX()+bx,region.originY()+by,region.originZ()+bz);
            boolean available=MinecraftGroupAvailability.loaded(level,position);
            if (previousMask!=null && (!available || !availableCell.test(cell))) {
                masks[cell]=(byte)previousMask.applyAsInt(cell); continue;
            }
            if (!region.isInteriorBlock(bx,by,bz) || !available) { masks[cell]=(byte)255; continue; }
            byte[] block=blockMasks.get(position);
            if (block==null) {
                BlockState state=level.getBlockState(position);
                VoxelShape shape=state.isCollisionShapeFullBlock(level,position) ? Shapes.block() : state.getCollisionShape(level,position);
                block=shapeMasks.computeIfAbsent(shape,value->sampleBlockMasks(value,n));
                blockMasks.put(position,block);
            }
            masks[cell]=block[((z%n)*n+y%n)*n+x%n];
        }
        return new CollisionField(topology,masks);
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
