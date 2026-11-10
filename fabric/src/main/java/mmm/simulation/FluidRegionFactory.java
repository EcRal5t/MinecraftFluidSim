package mmm.simulation;

import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.HostFluidState;
import mmm.fluid.FluidSolverConfig;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.UUID;

/** Allocates CPU regions and performs read-only vanilla-water sampling into persistent cell inventory. */
final class FluidRegionFactory {
	static final int CELLS_PER_BLOCK = 2;
	static final int CELL_VOLUMES_PER_BLOCK = CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;

	private FluidRegionFactory() { }

	static FluidRegion createSelectedRegion(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			FluidRegionFootprint footprint, CommittedWorldRegistries world) {
		float cellSize = 1.0F / CELLS_PER_BLOCK;
        var config = new FluidSolverConfig(cellSize, 9.81F, 1.0F / 20.0F, 240, 1.0E-4F, 2.0E-3F);
        HostFluidState domain = new HostFluidState(grid(footprint), config, world.definitions());
		return new FluidRegion(UUID.randomUUID(), dimension, footprint.minimumInterior(), domain, footprint);
	}

    static mmm.spatial.SpatialGrid grid(FluidRegionFootprint footprint) {
        int sx = Math.multiplyExact(footprint.blockSizeX(), CELLS_PER_BLOCK);
        int sy = Math.multiplyExact(footprint.blockSizeY(), CELLS_PER_BLOCK);
        int sz = Math.multiplyExact(footprint.blockSizeZ(), CELLS_PER_BLOCK);
        var members = new java.util.HashSet<mmm.spatial.SpatialGrid.Block>();
        var origin = footprint.minimumInterior();
        for (long packed : footprint.interiorBlocks()) {
            var block = BlockPos.of(packed);
            int x = (block.getX() - origin.getX()) * CELLS_PER_BLOCK;
            int y = (block.getY() - origin.getY()) * CELLS_PER_BLOCK;
            int z = (block.getZ() - origin.getZ()) * CELLS_PER_BLOCK;
            members.add(new mmm.spatial.SpatialGrid.Block(x / 8, y / 8, z / 8));
        }
        return new mmm.spatial.SpatialGrid(sx, sy, sz, members);
    }

	static HostFluidState createDomain(
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ,
			CommittedWorldRegistries world
	) {
		float cellSize = 1.0F / CELLS_PER_BLOCK;
		FluidSolverConfig config = new FluidSolverConfig(
				cellSize, 9.81F, 1.0F / 20.0F,
				240, 1.0E-4F, 2.0E-3F);
		return new HostFluidState(
				Math.multiplyExact(blockSizeX, CELLS_PER_BLOCK),
				Math.multiplyExact(blockSizeY, CELLS_PER_BLOCK),
				Math.multiplyExact(blockSizeZ, CELLS_PER_BLOCK),
				config, world.definitions());
	}

	/** Shared production configuration for the transient air demonstration. */
	static HostFluidState createAirDomain(CommittedWorldRegistries world) {
		return new HostFluidState(DemoSceneLayout.AIR_SIZE_X * CELLS_PER_BLOCK,
				DemoSceneLayout.AIR_SIZE_Y * CELLS_PER_BLOCK,
				DemoSceneLayout.AIR_SIZE_Z * CELLS_PER_BLOCK,
				new FluidSolverConfig(0.5F, 9.81F, 1.0F / 40.0F, 300, 1.0E-5F, 2.0E-3F), world.definitions());
	}

	/** Scaled wetting demonstration: two-cell capillary length with unchanged world cell size. */
	static HostFluidState createTensionDomain(CommittedWorldRegistries world) {
		return new HostFluidState(MinecraftDebugFixtures.BLOCK_SIZE_X * CELLS_PER_BLOCK,
				MinecraftDebugFixtures.BLOCK_SIZE_Y * CELLS_PER_BLOCK,
				MinecraftDebugFixtures.BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				new FluidSolverConfig(0.5F, 0.981F, 1.0F / 40.0F, 300, 1.0E-5F, 2.0E-3F), world.definitions());
	}

	/**
	 * Gives a pure-water domain its default single-material column. It is a pure function of the
	 * domain itself, so both controller activation and the debug fixtures share this one path.
	 */
	static void bindDefaultMaterialState(HostFluidState domain, mmm.fluid.MaterialRegistry definitions) {
		if (domain.hasMaterialState()) return;
		var materials = new java.util.ArrayList<mmm.fluid.FluidMaterial>();
		for (String id : java.util.List.of("mmm:water", "mmm:oil", "mmm:mercury"))
			materials.add(definitions.entry(id).material());
		for (var entry : definitions.entries())
			if (materials.stream().noneMatch(material -> material.id().equals(entry.material().id()))) materials.add(entry.material());
		domain.bindMaterialRegistry(new FluidMaterialRegistry(materials));
	}

	static int captureVanillaWater(ServerLevel level, FluidRegion region) {
		return captureVanillaWater(level, region, false);
	}

	static int captureVanillaWater(ServerLevel level, FluidRegion region, boolean standaloneWaterOnly) {
		return captureWater(region, position -> {
			BlockState state = level.getBlockState(position);
			if ((region.hasSelectedVolume() || standaloneWaterOnly) && !state.is(net.minecraft.world.level.block.Blocks.WATER)) return 0.0F;
			FluidState fluid = state.getFluidState();
			if (!fluid.is(FluidTags.WATER)) return 0.0F;
			return region.footprint() != null
					&& region.footprint().kind() == mmm.model.region.RegionGeometry.RegionKind.POOL
					&& fluid.isSource() ? 1.0F : fluid.getHeight(level, position);
		});
	}

	/** Adds water from newly acquired world blocks without replacing existing liquid rows. */
	static int captureAddedWater(ServerLevel level, HostFluidState domain, BlockPos origin, java.util.Set<Long> positions) {
		int changed = 0;
		float h = domain.config().cellSize();
		for (long packed : positions) {
			BlockPos position = BlockPos.of(packed);
			BlockState state = level.getBlockState(position);
			if (!state.is(net.minecraft.world.level.block.Blocks.WATER)) continue;
			float height = state.getFluidState().getHeight(level, position);
			for (int z = 0; z < CELLS_PER_BLOCK; z++) for (int y = 0; y < CELLS_PER_BLOCK; y++)
				for (int x = 0; x < CELLS_PER_BLOCK; x++) {
					int cx = (position.getX() - origin.getX()) * CELLS_PER_BLOCK + x;
					int cy = (position.getY() - origin.getY()) * CELLS_PER_BLOCK + y;
					int cz = (position.getZ() - origin.getZ()) * CELLS_PER_BLOCK + z;
					if (domain.grid().cell(cx, cy, cz) < 0 || domain.isSolid(cx, cy, cz)) continue;
					float volume = Mth.clamp((height - y * h) / h, 0.0F, 1.0F);
					if (volume > 0) { domain.setVolume(cx, cy, cz, volume); changed++; }
				}
		}
		return changed;
	}

	/** Samples the same world water heights used by the Minecraft adapter. */
	static int captureWater(FluidRegion region, java.util.function.Function<BlockPos, Float> waterHeightAt) {
		long generation = region.solverGeneration();
		HostFluidState domain = region.beginHostEdit();
		BlockPos origin = region.origin();
		float cellSize = domain.config().cellSize();
		int wetCells = 0;
		float[] volumes = new float[domain.grid().cellCount()];
		for (int cell = 0; cell < domain.grid().cellCount(); cell++) {
            int x = domain.grid().x(cell), y = domain.grid().y(cell), z = domain.grid().z(cell);
            if (domain.isSolid(x, y, z)) continue;
            double worldX = origin.getX() + (x + 0.5) * cellSize;
            double worldY = origin.getY() + (y + 0.5) * cellSize;
            double worldZ = origin.getZ() + (z + 0.5) * cellSize;
            BlockPos blockPosition = BlockPos.containing(worldX, worldY, worldZ);
            if (region.footprint() != null && !region.footprint().containsInterior(blockPosition)) continue;
            float waterHeight = waterHeightAt.apply(blockPosition);
            if (waterHeight <= 0.0F) continue;
            double cellBottom = origin.getY() + y * cellSize;
            double localBottom = cellBottom - Math.floor(cellBottom);
            float fraction = Mth.clamp((float) ((waterHeight - localBottom) / cellSize), 0.0F, 1.0F);
            if (fraction <= 0.0F) continue;
            volumes[cell] = fraction;
            wetCells++;
        }

		domain.setVolumes(volumes);
		domain.resetAmbientGas();
		region.commitHostEdit(generation, domain);
		return wetCells;
	}
}
