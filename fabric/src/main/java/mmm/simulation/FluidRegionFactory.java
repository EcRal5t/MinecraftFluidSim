package mmm.simulation;

import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
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

/** Allocates CPU regions and performs read-only vanilla-water sampling into exact marker volume. */
final class FluidRegionFactory {
	static final int CELLS_PER_BLOCK = 2;
	static final int CELL_VOLUMES_PER_BLOCK = CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;

	private FluidRegionFactory() { }

	static FluidRegion createCommandRegion(
			ServerLevel level,
			BlockPos origin,
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ,
			long markerCapacity
	) {
		FluidDomain domain = createDomain(
				blockSizeX, blockSizeY, blockSizeZ, markerCapacity
		);
		return new FluidRegion(UUID.randomUUID(), level.dimension(), origin, domain, markerCapacity);
	}

	static FluidRegion createControllerRegion(
			ServerLevel level,
			FluidRegionFootprint footprint,
			long markerCapacity
	) {
		FluidDomain domain = createDomain(
				footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(), markerCapacity
		);
		return new FluidRegion(
				UUID.randomUUID(), level.dimension(), footprint.minimumInterior(), domain, markerCapacity, footprint
		);
	}

	private static FluidDomain createDomain(
			int blockSizeX,
			int blockSizeY,
			int blockSizeZ,
			long markerCapacity
	) {
		float cellSize = 1.0F / CELLS_PER_BLOCK;
		FluidSolverConfig config = new FluidSolverConfig(
				cellSize, 1_000.0F, 9.81F, 1.0F / 20.0F,
				240, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F
		);
		return new FluidDomain(
				Math.multiplyExact(blockSizeX, CELLS_PER_BLOCK),
				Math.multiplyExact(blockSizeY, CELLS_PER_BLOCK),
				Math.multiplyExact(blockSizeZ, CELLS_PER_BLOCK),
				config,
				Math.toIntExact(markerCapacity)
		);
	}

	/**
	 * Gives a pure-water domain its default single-material column. It is a pure function of the
	 * domain itself, so both controller activation and the debug fixtures share this one path.
	 */
	static void bindDefaultMaterialState(FluidDomain domain) {
		if (domain.hasMaterialState()) return;
		FlipParticleState pure = domain.exportParticleState();
		float[] positionVolume = pure.copyPositionVolume();
		double[] markerMass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		double totalVolume = 0.0, totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			double volume = positionVolume[marker * 4 + 3];
			markerMass[marker] = volume * BuiltinFluidMaterials.WATER.referenceDensity();
			totalVolume += volume;
			totalMass += markerMass[marker];
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), totalVolume, totalMass,
				BuiltinFluidMaterials.DEFAULT_PALETTE, positionVolume, pure.copyParticleVelocities(), pure.copySolidMask(), markerMass, material
		));
	}

	static int captureVanillaWater(ServerLevel level, FluidRegion region) {
		FluidDomain domain = region.domain();
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
