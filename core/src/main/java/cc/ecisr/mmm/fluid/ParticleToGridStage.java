package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * The particle-to-grid stage: clears and rescatters the marker grid, normalizes the PIC
 * velocity with its weights, snapshots the P2G weights, rebuilds the free-surface, pressure
 * classification, granular-coupling, gas-lineage and surface-tension-pressure derived views
 * and finishes with solid-face enforcement and air extrapolation. Every derived view the
 * later stages consume is rebuilt here, exactly once per grid generation.
 */
final class ParticleToGridStage {
	private final FlipFluidDomain domain;
	private final BodyForceStage bodyForceStage;
	private final GridToParticleStage gridToParticleStage;

	ParticleToGridStage(FlipFluidDomain domain, BodyForceStage bodyForceStage, GridToParticleStage gridToParticleStage) {
		this.domain = domain;
		this.bodyForceStage = bodyForceStage;
		this.gridToParticleStage = gridToParticleStage;
	}

	void rebuild() {
		Arrays.fill(domain.volume, 0.0F);
		Arrays.fill(domain.projection.pressureVolume, 0.0);
		domain.freeSurfaceGeometry.clearExactVolumes();
		Arrays.fill(domain.mac.velocityX, 0.0F);
		Arrays.fill(domain.mac.velocityY, 0.0F);
		Arrays.fill(domain.mac.velocityZ, 0.0F);
		Arrays.fill(domain.mac.velocityWeightX, 0.0F);
		Arrays.fill(domain.mac.velocityWeightY, 0.0F);
		Arrays.fill(domain.mac.velocityWeightZ, 0.0F);
		domain.variableDensityActive = domain.usesMassWeightedP2G();
		domain.surfaceTensionActive = domain.hasAnySurfaceTension();
		domain.liquidPairTensionActive = domain.hasAnyLiquidPairTension();
		MarkerGridBuilder.build(domain);
		domain.normalize(domain.mac.velocityX, domain.mac.velocityWeightX);
		domain.normalize(domain.mac.velocityY, domain.mac.velocityWeightY);
		domain.normalize(domain.mac.velocityZ, domain.mac.velocityWeightZ);
		System.arraycopy(domain.mac.velocityWeightX, 0, domain.mac.p2gWeightX, 0, domain.mac.velocityWeightX.length);
		System.arraycopy(domain.mac.velocityWeightY, 0, domain.mac.p2gWeightY, 0, domain.mac.velocityWeightY.length);
		System.arraycopy(domain.mac.velocityWeightZ, 0, domain.mac.p2gWeightZ, 0, domain.mac.velocityWeightZ.length);
		if (domain.granularCoupling == null || !domain.hasGranularInventory() && !(domain.thermalState != null
				&& domain.thermalState.coherentPhases().groupCount() != 0)) {
			if (domain.granularCoupling != null) domain.granularCoupling.clear();
			domain.freeSurfaceGeometry.rebuild(domain.geometry.solid, domain.markerGrid.markerCount, domain.geometry.cutCells);
			domain.pressureGranularCoupling = null;
		} else {
			if (domain.hasGranularInventory()) domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			else domain.granularCoupling.rebuildFixed();
			domain.pressureGranularCoupling = domain.granularCoupling;
			domain.freeSurfaceGeometry.rebuild(domain.geometry.solid, domain.markerGrid.markerCount, domain.geometry.cutCells, domain.granularCoupling);
		}
		domain.pressurePhaseGeometry.rebuild(
				domain.geometry.solid, domain.markerGrid.markerCount, domain.projection.pressureVolume, domain.geometry.cutCells,
				domain.pressureGranularCoupling, domain.projection.pressureFluid);
		domain.rebuildPressureBoundaryLineage();
		domain.rebuildSurfaceTensionPressure();
		bodyForceStage.enforceSolidFaceVelocities();
		gridToParticleStage.extrapolateVelocityIntoAir();
		domain.gridDirty = false;
	}
}
