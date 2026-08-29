package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Rebuilds the derived marker grid from the authoritative marker columns in the former
 * {@code rebuildGridFromParticles} order. The per-marker loop also scatters the P2G cell
 * fields (center volume, pressure volume, face velocities) because the original loop
 * interleaved them; splitting the loops would reorder those float accumulations, so the
 * scatter stays here until the MAC workspace owns it.
 */
final class MarkerGridBuilder {
	private MarkerGridBuilder() { }

	static void build(FlipFluidDomain domain) {
		MarkerGridIndex grid = domain.markerGrid;
		Arrays.fill(grid.markerCount, 0);
		Arrays.fill(grid.cellMarkerHead, -1);
		Arrays.fill(grid.cellMarkerTail, -1);
		Arrays.fill(grid.exactCellMarkerVolume, 0.0);
		Arrays.fill(grid.exactCellMarkerMass, 0.0);
		Arrays.fill(grid.cellDensity, domain.config.density());
		Arrays.fill(grid.cellPhaseHead, -1);
		grid.phaseEntryCount = 0;
		boolean granularInventoryPresent = domain.hasGranularInventory();
		if (domain.granularCarrierMaterialByCell != null && granularInventoryPresent) {
			Arrays.fill(domain.granularCarrierMaterialByCell, -1);
			Arrays.fill(domain.granularCarrierVolumeByCell, -1.0);
		}
		for (int i = 0; i < domain.particles.count(); i++) {
			int particleCell = domain.particleCellIndex(i);
			int previous = grid.cellMarkerTail[particleCell];
			grid.particleCellPrevious[i] = previous;
			grid.particleCellNext[i] = -1;
			if (previous < 0) grid.cellMarkerHead[particleCell] = i;
			else grid.particleCellNext[previous] = i;
			grid.cellMarkerTail[particleCell] = i;
			grid.markerCount[particleCell]++;
			grid.exactCellMarkerVolume[particleCell] += domain.particles.volume(i);
			grid.exactCellMarkerMass[particleCell] += domain.particles.mass(i);
			grid.addCellPhaseVolume(particleCell, domain.particles.phaseLabel(i), domain.particles.volume(i));
			if (domain.granularCarrierMaterialByCell != null && granularInventoryPresent
					&& (domain.particles.volume(i) > domain.granularCarrierVolumeByCell[particleCell]
					|| domain.particles.volume(i) == domain.granularCarrierVolumeByCell[particleCell]
					&& domain.particles.materialHandle(i) < domain.granularCarrierMaterialByCell[particleCell])) {
				domain.granularCarrierVolumeByCell[particleCell] = domain.particles.volume(i);
				domain.granularCarrierMaterialByCell[particleCell] = domain.particles.materialHandle(i);
			}
			domain.freeSurfaceGeometry.addExactVolume(particleCell, domain.particles.volume(i));
			domain.splatCenter(domain.particles.positionX(i), domain.particles.positionY(i),
					domain.particles.positionZ(i), domain.particles.volume(i));
			domain.splatPressureVolume(domain.particles.positionX(i), domain.particles.positionY(i),
					domain.particles.positionZ(i), domain.particles.volume(i));
			float transferWeight = domain.variableDensityActive
					? (float) domain.particles.mass(i) : domain.particles.volume(i);
			domain.splatFaceField(domain.velocityX, domain.velocityWeightX, 0,
					domain.particles.positionX(i), domain.particles.positionY(i),
					domain.particles.positionZ(i), domain.particles.velocityX(i), transferWeight);
			domain.splatFaceField(domain.velocityY, domain.velocityWeightY, 1,
					domain.particles.positionX(i), domain.particles.positionY(i),
					domain.particles.positionZ(i), domain.particles.velocityY(i), transferWeight);
			domain.splatFaceField(domain.velocityZ, domain.velocityWeightZ, 2,
					domain.particles.positionX(i), domain.particles.positionY(i),
					domain.particles.positionZ(i), domain.particles.velocityZ(i), transferWeight);
		}
		for (int cell = 0; cell < grid.cellCount; cell++) if (grid.markerCount[cell] > 0) {
			grid.cellDensity[cell] = grid.exactCellMarkerMass[cell] / grid.exactCellMarkerVolume[cell];
			if (!(Double.isFinite(grid.cellDensity[cell]) && grid.cellDensity[cell] > 0.0)) {
				throw new IllegalStateException("Marker inventory produced invalid cell density");
			}
		}
	}
}
