package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;

/**
 * Marker advection through the collision resolver, bookending the chemical dirty-cell
 * trail: markers that move mark both their source and destination cells so the reaction
 * scan sees exactly the disturbed neighbourhood. The collision resolver and the trail
 * columns stay with the domain lifecycle.
 */
final class ParticleAdvectionStage {
	record Workspace(LiquidParticleStore particles, FlipParticleCollision collision) { }

	interface Access {
		ChemicalParcelStore chemicalParcels();
		int[] chemicalPreviousCells();
		int[] chemicalMovingMarkers();
		int particleCellIndex(int marker);
		void markGridDirty();
	}

	private final Workspace state;
	private final Access access;

	ParticleAdvectionStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	void advect(float dt) {
		LiquidParticleStore particles = state.particles;
		ChemicalParcelStore chemicalParcels = access.chemicalParcels();
		int chemicalMovingCount = 0;
		if (chemicalParcels != null) {
			for (int marker = 0; marker < particles.count(); marker++) {
				if (particles.velocityX(marker) == 0.0F && particles.velocityY(marker) == 0.0F
						&& particles.velocityZ(marker) == 0.0F) continue;
				access.chemicalPreviousCells()[marker] = access.particleCellIndex(marker);
				access.chemicalMovingMarkers()[chemicalMovingCount++] = marker;
			}
		}
		LiquidParticleStore.DirectArrays advectArrays = particles.directArrays();
		state.collision.advect(
				advectArrays.positionX, advectArrays.positionY, advectArrays.positionZ,
				advectArrays.velocityX, advectArrays.velocityY, advectArrays.velocityZ,
				particles.count(), dt
		);
		for (int moving = 0; moving < chemicalMovingCount; moving++) {
			int marker = access.chemicalMovingMarkers()[moving];
			int previous = access.chemicalPreviousCells()[marker], current = access.particleCellIndex(marker);
			if (previous != current) {
				chemicalParcels.markDirtyCell(previous);
				chemicalParcels.markDirtyCell(current);
			}
		}
		access.markGridDirty();
	}
}
