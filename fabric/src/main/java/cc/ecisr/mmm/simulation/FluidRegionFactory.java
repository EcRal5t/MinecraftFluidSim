package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.UUID;

/** Allocates CPU regions and performs read-only vanilla-water sampling into exact marker volume. */
final class FluidRegionFactory {
	private FluidRegionFactory() { }

	static FluidRegion createCommandRegion(
			ServerLevel level,
			BlockPos origin,
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ,
			int cellsPerBlock,
			long markerCapacity
	) {
		FlipFluidDomain domain = createDomain(
				blockSizeX, blockSizeY, blockSizeZ, cellsPerBlock, markerCapacity
		);
		return new FluidRegion(UUID.randomUUID(), level.dimension(), origin, domain, markerCapacity);
	}

	static FluidRegion createControllerRegion(
			ServerLevel level,
			FluidRegionFootprint footprint,
			int cellsPerBlock,
			long markerCapacity
	) {
		FlipFluidDomain domain = createDomain(
				footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(), cellsPerBlock, markerCapacity
		);
		return new FluidRegion(
				UUID.randomUUID(), level.dimension(), footprint.minimumInterior(), domain, markerCapacity, footprint
		);
	}

	private static FlipFluidDomain createDomain(
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ,
			int cellsPerBlock,
			long markerCapacity
	) {
		float cellSize = 1.0F / cellsPerBlock;
		FluidSolverConfig config = new FluidSolverConfig(
				cellSize, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F
		);
		return new FlipFluidDomain(
				Math.multiplyExact(blockSizeX, cellsPerBlock),
				Math.multiplyExact(blockSizeY, cellsPerBlock),
				Math.multiplyExact(blockSizeZ, cellsPerBlock),
				config,
				Math.toIntExact(markerCapacity)
		);
	}

	static int captureVanillaWater(ServerLevel level, FluidRegion region) {
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellSize = domain.config().cellSize();
		int wetCells = 0;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < domain.sizeY(); y++) {
				for (int x = 0; x < domain.sizeX(); x++) {
					if (domain.isSolid(x, y, z)) continue;
					double worldX = origin.getX() + (x + 0.5) * cellSize;
					double worldY = origin.getY() + (y + 0.5) * cellSize;
					double worldZ = origin.getZ() + (z + 0.5) * cellSize;
					BlockPos blockPosition = BlockPos.containing(worldX, worldY, worldZ);
					if (region.footprint() != null && !region.footprint().containsInterior(blockPosition)) continue;
					BlockState state = level.getBlockState(blockPosition);
					if (region.controllerManaged() && !state.is(net.minecraft.world.level.block.Blocks.WATER)) continue;
					FluidState fluid = state.getFluidState();
					if (!fluid.is(FluidTags.WATER)) continue;
					float waterHeight = fluid.getHeight(level, blockPosition);
					double cellBottom = origin.getY() + y * cellSize;
					double localBottom = cellBottom - Math.floor(cellBottom);
					float fraction = Mth.clamp((float) ((waterHeight - localBottom) / cellSize), 0.0F, 1.0F);
					if (fraction <= 0.0F) continue;
					domain.setVolume(x, y, z, fraction);
					wetCells++;
				}
			}
		}
		return wetCells;
	}
}
