package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Rebuilds the derived marker grid from the authoritative marker columns in the former
 * {@code rebuildGridFromParticles} order. The per-marker loop also scatters the P2G cell
 * fields (center volume, projection.pressure volume, face velocities) because the original loop
 * interleaved them; splitting the loops would reorder those float accumulations, so the
 * scatter stays here until the MAC workspace owns it.
 */
final class MarkerGridBuilder {
	private MarkerGridBuilder() { }

	static void build(FluidDomain domain) {
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
			grid.exactCellMarkerVolume[particleCell] += particleVolume;
			grid.exactCellMarkerMass[particleCell] += particleMass;
			grid.addCellPhaseVolume(particleCell, domain.particles.phaseLabel(i), particleVolume);
			if (domain.liquidCarrierMaterialByCell != null
					&& (particleVolume > domain.liquidCarrierVolumeByCell[particleCell]
					|| particleVolume == domain.liquidCarrierVolumeByCell[particleCell]
					&& materialHandle < domain.liquidCarrierMaterialByCell[particleCell])) {
				domain.liquidCarrierVolumeByCell[particleCell] = particleVolume;
				domain.liquidCarrierMaterialByCell[particleCell] = materialHandle;
			}
			domain.freeSurfaceGeometry.addExactVolume(particleCell, particleVolume);
			domain.splatCenter(positionX, positionY, positionZ, particleVolume);
			domain.splatPressureVolume(positionX, positionY, positionZ, particleVolume);
			float transferWeight = domain.variableDensityActive
					? (float) particleMass : particleVolume;
			domain.splatFaceField(domain.mac.velocityX, domain.mac.velocityWeightX, 0,
					positionX, positionY, positionZ, domain.particles.velocityX(i), transferWeight);
			domain.splatFaceField(domain.mac.velocityY, domain.mac.velocityWeightY, 1,
					positionX, positionY, positionZ, domain.particles.velocityY(i), transferWeight);
			domain.splatFaceField(domain.mac.velocityZ, domain.mac.velocityWeightZ, 2,
					positionX, positionY, positionZ, domain.particles.velocityZ(i), transferWeight);
		}
		for (int cell = 0; cell < grid.cellCount; cell++) if (grid.markerCount[cell] > 0) {
			grid.cellDensity[cell] = grid.exactCellMarkerMass[cell] / grid.exactCellMarkerVolume[cell];
			if (!(Double.isFinite(grid.cellDensity[cell]) && grid.cellDensity[cell] > 0.0)) {
				throw new IllegalStateException("Marker inventory produced invalid cell density");
			}
		}
	}
}
