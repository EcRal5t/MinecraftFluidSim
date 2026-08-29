package cc.ecisr.mmm.fluid;

/**
 * The grid-to-particle handoff: air-cell velocity extrapolation into the boundary fringe
 * followed by the FLIP/PIC blend into authoritative marker momentum. The extrapolator and
 * its workspace stay with the domain lifecycle; the stage only sequences them.
 */
final class GridToParticleStage {
	private final FlipFluidDomain domain;

	GridToParticleStage(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/** Extrapolates grid velocities into air and blends them into the marker velocities. */
	void extrapolateAndTransfer() {
		extrapolateVelocityIntoAir();
		gridToParticles();
	}

	void extrapolateVelocityIntoAir() {
		domain.velocityExtrapolator.rebuildAndExtrapolate(
				domain.geometry.solid, domain.markerGrid.markerCount,
				domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ,
				domain.mac.velocityValidX, domain.mac.velocityValidY, domain.mac.velocityValidZ,
				domain.mac.velocityWeightX, domain.mac.velocityWeightY, domain.mac.velocityWeightZ
		);
	}

	private void gridToParticles() {
		for (int i = 0; i < domain.particles.count(); i++) {
			float x = domain.particles.positionX(i), y = domain.particles.positionY(i), z = domain.particles.positionZ(i);
			float picX = domain.sampleX(domain.mac.velocityX, x, y, z),
					picY = domain.sampleY(domain.mac.velocityY, x, y, z),
					picZ = domain.sampleZ(domain.mac.velocityZ, x, y, z);
			float deltaX = picX - domain.sampleX(domain.mac.p2gVelocityX, x, y, z);
			float deltaY = picY - domain.sampleY(domain.mac.p2gVelocityY, x, y, z);
			float deltaZ = picZ - domain.sampleZ(domain.mac.p2gVelocityZ, x, y, z);
			domain.particles.setVelocityX(i, lerp(picX, domain.particles.velocityX(i) + deltaX, FLIP_RATIO));
			domain.particles.setVelocityY(i, lerp(picY, domain.particles.velocityY(i) + deltaY, FLIP_RATIO));
			domain.particles.setVelocityZ(i, lerp(picZ, domain.particles.velocityZ(i) + deltaZ, FLIP_RATIO));
		}
	}

	private static final float FLIP_RATIO = 0.90F;

	private static float lerp(float first, float second, float t) { return first + (second - first) * t; }
}
