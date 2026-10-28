package mmm.simulation;

import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.FluidDomain;
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

	static FluidRegion createCommandRegion(
			ServerLevel level,
			BlockPos origin,
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ
	) {
		FluidDomain domain = createDomain(
				blockSizeX, blockSizeY, blockSizeZ
		);
		return new FluidRegion(UUID.randomUUID(), level.dimension(), origin, domain);
	}

	static FluidRegion createControllerRegion(
			ServerLevel level,
			FluidRegionFootprint footprint
	) {
		return createControllerRegion(level.dimension(), footprint);
	}

	static FluidRegion createControllerRegion(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			FluidRegionFootprint footprint) {
		FluidDomain domain = createDomain(footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ());
		return new FluidRegion(UUID.randomUUID(), dimension, footprint.minimumInterior(), domain, footprint);
	}

	static FluidDomain createDomain(
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ
	) {
		float cellSize = 1.0F / CELLS_PER_BLOCK;
		FluidSolverConfig config = new FluidSolverConfig(
				cellSize, 1_000.0F, 9.81F, 1.0F / 20.0F,
				240, 1.0E-4F, 2.0E-3F);
		return new FluidDomain(
				Math.multiplyExact(blockSizeX, CELLS_PER_BLOCK),
				Math.multiplyExact(blockSizeY, CELLS_PER_BLOCK),
				Math.multiplyExact(blockSizeZ, CELLS_PER_BLOCK),
				config);
	}

	/** Shared production configuration for the transient air and wetting demonstrations. */
	static FluidDomain createDebugDomain() {
		return new FluidDomain(MinecraftDebugFixtures.BLOCK_SIZE_X * CELLS_PER_BLOCK,
				MinecraftDebugFixtures.BLOCK_SIZE_Y * CELLS_PER_BLOCK,
				MinecraftDebugFixtures.BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				new FluidSolverConfig(0.5F, 1000.0F, 9.81F, 1.0F / 40.0F, 300, 1.0E-5F, 2.0E-3F));
	}

	/** Scaled wetting demonstration: two-cell capillary length with unchanged world cell size. */
	static FluidDomain createTensionDomain() {
		return new FluidDomain(MinecraftDebugFixtures.BLOCK_SIZE_X * CELLS_PER_BLOCK,
				MinecraftDebugFixtures.BLOCK_SIZE_Y * CELLS_PER_BLOCK,
				MinecraftDebugFixtures.BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				new FluidSolverConfig(0.5F, 1000.0F, 0.981F, 1.0F / 40.0F, 300, 1.0E-5F, 2.0E-3F));
	}

	/**
	 * Gives a pure-water domain its default single-material column. It is a pure function of the
	 * domain itself, so both controller activation and the debug fixtures share this one path.
	 */
	static void bindDefaultMaterialState(FluidDomain domain) {
		if (domain.hasMaterialState()) return;
		domain.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
	}

	static int captureVanillaWater(ServerLevel level, FluidRegion region) {
		return captureVanillaWater(level, region, false);
	}

	static int captureVanillaWater(ServerLevel level, FluidRegion region, boolean standaloneWaterOnly) {
		return captureWater(region, position -> {
			BlockState state = level.getBlockState(position);
			if ((region.controllerManaged() || standaloneWaterOnly) && !state.is(net.minecraft.world.level.block.Blocks.WATER)) return 0.0F;
			FluidState fluid = state.getFluidState();
			if (!fluid.is(FluidTags.WATER)) return 0.0F;
			return region.footprint() != null
					&& region.footprint().kind() == mmm.model.region.RegionGeometry.RegionKind.POOL
					&& fluid.isSource() ? 1.0F : fluid.getHeight(level, position);
		});
	}

	/** Samples the same world water heights used by the Minecraft adapter. */
	static int captureWater(FluidRegion region, java.util.function.Function<BlockPos, Float> waterHeightAt) {
		FluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellSize = domain.config().cellSize();
		int wetCells = 0;
		float[] volumes = new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
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
					volumes[(z*domain.sizeY()+y)*domain.sizeX()+x] = fraction;
					wetCells++;
				}
			}
		}
		domain.setVolumes(volumes);
		return wetCells;
	}
}
