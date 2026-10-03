package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.compute.CpuWorkBatch;
import java.util.Arrays;

/**
 * Rebuilds the derived marker grid from authoritative marker columns. Cell inventories and each
 * MAC axis retain marker ordinal accumulation order; the three independent face arrays can
 * be scattered concurrently after the cell index is complete.
 */
final class MarkerGridBuilder {
	private MarkerGridBuilder() { }

	static void build(FluidDomain domain) {
		boolean conservativeVolume = CellLiquidVolumeTransport.enabled(domain);
		domain.closedLiquidVolume.prepare();
		domain.carrierPropertiesWorkspace.invalidate();
		MarkerGridIndex grid = domain.markerGrid;
		Arrays.fill(grid.markerCount, 0);
		Arrays.fill(grid.cellMarkerHead, -1);
		Arrays.fill(grid.cellMarkerTail, -1);
		Arrays.fill(grid.exactCellMarkerVolume, 0.0);
		Arrays.fill(grid.exactCellMarkerMass, 0.0);
		Arrays.fill(grid.cellDensity, domain.config.density());
		Arrays.fill(grid.cellPhaseHead, -1);
		grid.phaseEntryCount = 0;
		if (domain.liquidCarrierMaterialByCell != null) {
			Arrays.fill(domain.liquidCarrierMaterialByCell, -1);
			Arrays.fill(domain.liquidCarrierVolumeByCell, -1.0);
		}
		for (int i = 0; i < domain.particles.count(); i++) {
			float positionX = domain.particles.positionX(i);
			float positionY = domain.particles.positionY(i);
			float positionZ = domain.particles.positionZ(i);
			float particleVolume = domain.particles.volume(i);
			double particleMass = domain.particles.mass(i);
			int materialHandle = domain.particles.materialHandle(i);
			int particleCell = domain.particleCellIndex(positionX, positionY, positionZ);
			int previous = grid.cellMarkerTail[particleCell];
			grid.particleCellPrevious[i] = previous;
			grid.particleCellNext[i] = -1;
			if (previous < 0) grid.cellMarkerHead[particleCell] = i;
			else grid.particleCellNext[previous] = i;
			grid.cellMarkerTail[particleCell] = i;
			grid.markerCount[particleCell]++;
			grid.exactCellMarkerMass[particleCell] += particleMass;
			grid.addCellPhaseVolume(particleCell, domain.particles.phaseLabel(i), particleVolume);
			if (domain.liquidCarrierMaterialByCell != null
					&& (particleVolume > domain.liquidCarrierVolumeByCell[particleCell]
					|| particleVolume == domain.liquidCarrierVolumeByCell[particleCell]
					&& materialHandle < domain.liquidCarrierMaterialByCell[particleCell])) {
				domain.liquidCarrierVolumeByCell[particleCell] = particleVolume;
				domain.liquidCarrierMaterialByCell[particleCell] = materialHandle;
			}
			if (!conservativeVolume) domain.splatCenter(positionX, positionY, positionZ, particleVolume);
			if (!conservativeVolume) domain.splatPressureVolume(positionX, positionY, positionZ, particleVolume);
		}
		CpuWorkBatch.run(3, domain.particles.count() >= 2_048 ? 1 : 3, axis -> scatterAxis(domain, axis));
		grid.exactCellVolumeInventory = ExactCellVolumeSums.sum(domain.particles.directArrays().volume,
				domain.particles.count(), grid.cellMarkerHead, grid.particleCellNext);
		for (int cell = 0; cell < grid.cellCount; cell++) if (grid.markerCount[cell] > 0) {
			grid.exactCellMarkerVolume[cell] = grid.exactCellVolumeInventory[cell].toDouble();
			domain.freeSurfaceGeometry.addExactVolume(cell, (float) grid.exactCellMarkerVolume[cell]);
			if (conservativeVolume) {
				domain.projection.pressureVolume[cell] = grid.exactCellMarkerVolume[cell];
				domain.volume[cell] = (float) grid.exactCellMarkerVolume[cell];
			}
			grid.cellDensity[cell] = grid.exactCellMarkerMass[cell] / grid.exactCellMarkerVolume[cell];
			if (!(Double.isFinite(grid.cellDensity[cell]) && grid.cellDensity[cell] > 0.0)) {
				throw new IllegalStateException("Marker inventory produced invalid cell density");
			}
		}
	}

	private static void scatterAxis(FluidDomain domain, int axis) {
		float[] sums = axis == 0 ? domain.mac.velocityX : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
		float[] weights = axis == 0 ? domain.mac.velocityWeightX : axis == 1 ? domain.mac.velocityWeightY : domain.mac.velocityWeightZ;
		float h = domain.config.cellSize();
		for (int i = 0; i < domain.particles.count(); i++) {
			float transferWeight = domain.variableDensityActive ? (float) domain.particles.mass(i) : domain.particles.volume(i);
			float velocity = axis == 0 ? domain.particles.velocityX(i) : axis == 1 ? domain.particles.velocityY(i) : domain.particles.velocityZ(i);
			domain.splatFaceField(sums, weights, axis, domain.particles.positionX(i) / h,
					domain.particles.positionY(i) / h, domain.particles.positionZ(i) / h, velocity, transferWeight);
		}
	}
}
