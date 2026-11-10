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
    private final FluidRegion region;
    private final MinecraftWorldUpdates worldUpdates;

	MinecraftCollisionWorldPort(ServerLevel level) {
		this(level, (mmm.fluid.HostFluidState)null);
	}

	MinecraftCollisionWorldPort(ServerLevel level, mmm.fluid.HostFluidState previous) {
		this.level = java.util.Objects.requireNonNull(level, "level");
		this.availableCell = previous==null?null:previous::groupAvailable;
        this.grid = previous == null ? null : previous.grid();
        this.region = null; this.worldUpdates = null;
        this.previousMask = previous==null?null:cell->previous.solidMicroMask(grid.x(cell),grid.y(cell),grid.z(cell));
	}

	MinecraftCollisionWorldPort(ServerLevel level, FluidRegion region) { this(level,region,null); }

	MinecraftCollisionWorldPort(ServerLevel level, FluidRegion region, MinecraftWorldUpdates worldUpdates) {
		this.level = java.util.Objects.requireNonNull(level);
        this.region = region; this.worldUpdates = worldUpdates;
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
                if (worldUpdates != null
                        && level.getBlockEntity(position) instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity piston)
                    worldUpdates.pistonTick(level,position,piston);
                VoxelShape shape;
                if (state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock
                        || state.getBlock() instanceof net.minecraft.world.level.block.piston.PistonHeadBlock) {
                    shape = Shapes.block();
                } else if (worldUpdates != null && worldUpdates.movingBlock(this.region, position)) {
                    var moving = level.getBlockEntity(position);
                    if (moving instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity piston
                            && piston.isSourcePiston() && !piston.isExtending()
                            && piston.getMovedState().getBlock() instanceof net.minecraft.world.level.block.piston.PistonBaseBlock)
                        shape = Shapes.block();
                    else shape = Shapes.empty();
                } else if(PipeThermalGeometry.isPipe(state)) shape=Shapes.empty();
                else shape=state.isCollisionShapeFullBlock(level,position) ? Shapes.block() : state.getCollisionShape(level,position);
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

    /** Decompose the same sampled block geometry into nonoverlapping moving boxes. */
    static java.util.List<net.minecraft.world.phys.AABB> movingBoxes(VoxelShape shape, int cellsPerBlock) {
        int n = 2 * cellsPerBlock;
        boolean[] filled = new boolean[n*n*n];
        byte[] masks = sampleBlockMasks(shape, cellsPerBlock);
        for (int z=0;z<n;z++) for (int y=0;y<n;y++) for (int x=0;x<n;x++) {
            int cell=((z/2)*cellsPerBlock+y/2)*cellsPerBlock+x/2;
            int bit=(x&1)+2*((y&1)+2*(z&1));
            filled[(z*n+y)*n+x]=(masks[cell] & (1 << bit)) != 0;
        }
        var boxes = new java.util.ArrayList<net.minecraft.world.phys.AABB>();
        for (int z=0;z<n;z++) for (int y=0;y<n;y++) for (int x=0;x<n;x++) {
            if (!filled[(z*n+y)*n+x]) continue;
            int endX=x+1; while(endX<n && filled[(z*n+y)*n+endX]) endX++;
            int endY=y+1;
            while(endY<n) {
                boolean full=true;for(int xx=x;xx<endX;xx++)full &= filled[(z*n+endY)*n+xx];
                if(!full)break;endY++;
            }
            int endZ=z+1;
            while(endZ<n) {
                boolean full=true;for(int yy=y;yy<endY;yy++)for(int xx=x;xx<endX;xx++)full &= filled[(endZ*n+yy)*n+xx];
                if(!full)break;endZ++;
            }
            for(int zz=z;zz<endZ;zz++)for(int yy=y;yy<endY;yy++)for(int xx=x;xx<endX;xx++)filled[(zz*n+yy)*n+xx]=false;
            boxes.add(new net.minecraft.world.phys.AABB((double)x/n,(double)y/n,(double)z/n,(double)endX/n,(double)endY/n,(double)endZ/n));
        }
        return java.util.List.copyOf(boxes);
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
