package cc.ecisr.mmm.fluid;

/**
 * Marker advection through the collision resolver, bookending the chemical dirty-cell
 * trail: markers that move mark both their source and destination cells so the reaction
 * scan sees exactly the disturbed neighbourhood. The collision resolver and the trail
 * columns stay with the domain lifecycle.
 */
final class ParticleAdvectionStage {
	private final FlipFluidDomain domain;

	ParticleAdvectionStage(FlipFluidDomain domain) {
		this.domain = domain;
	}

	void advect(float dt) {
		int chemicalMovingCount = 0;
		if (domain.chemicalParcels != null) {
			for (int marker = 0; marker < domain.particles.count(); marker++) {
				if (domain.particles.velocityX(marker) == 0.0F && domain.particles.velocityY(marker) == 0.0F
						&& domain.particles.velocityZ(marker) == 0.0F) continue;
				domain.chemicalPreviousCells[marker] = domain.particleCellIndex(marker);
				domain.chemicalMovingMarkers[chemicalMovingCount++] = marker;
			}
		}
		LiquidParticleStore.DirectArrays advectArrays = domain.particles.directArrays();
		domain.particleCollision.advect(
				advectArrays.positionX, advectArrays.positionY, advectArrays.positionZ,
				advectArrays.velocityX, advectArrays.velocityY, advectArrays.velocityZ,
				domain.particles.count(), dt
		);
		for (int moving = 0; moving < chemicalMovingCount; moving++) {
			int marker = domain.chemicalMovingMarkers[moving];
			int previous = domain.chemicalPreviousCells[marker], current = domain.particleCellIndex(marker);
			if (previous != current) {
				domain.chemicalParcels.markDirtyCell(previous);
				domain.chemicalParcels.markDirtyCell(current);
			}
		}
		domain.gridDirty = true;
	}
}
