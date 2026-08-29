package cc.ecisr.mmm.fluid;

/**
 * The grid-to-particle handoff: air-cell velocity extrapolation into the boundary fringe
 * followed by the FLIP/PIC blend into authoritative marker momentum. The extrapolator and
 * its workspace stay with the domain lifecycle; the stage only sequences them.
 */
final class GridToParticleStage {
	record Workspace(
			DomainGeometry geometry, MarkerGridIndex markerGrid, MacVelocityGrid mac,
			LiquidParticleStore particles, FlipVelocityExtrapolator velocityExtrapolator
	) { }

	interface Access {
		float sampleX(float[] field, float x, float y, float z);
		float sampleY(float[] field, float x, float y, float z);
		float sampleZ(float[] field, float x, float y, float z);
	}

	private final Workspace state;
	private final Access access;

	GridToParticleStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	/** Extrapolates grid velocities into air and blends them into the marker velocities. */
	void extrapolateAndTransfer() {
		extrapolateVelocityIntoAir();
		gridToParticles();
	}

	void extrapolateVelocityIntoAir() {
		MacVelocityGrid mac = state.mac;
		state.velocityExtrapolator.rebuildAndExtrapolate(
				state.geometry.solid, state.markerGrid.markerCount,
				mac.velocityX, mac.velocityY, mac.velocityZ,
				mac.velocityValidX, mac.velocityValidY, mac.velocityValidZ,
				mac.velocityWeightX, mac.velocityWeightY, mac.velocityWeightZ
		);
	}

	private void gridToParticles() {
		LiquidParticleStore particles = state.particles;
		MacVelocityGrid mac = state.mac;
		for (int i = 0; i < particles.count(); i++) {
			float x = particles.positionX(i), y = particles.positionY(i), z = particles.positionZ(i);
			float picX = access.sampleX(mac.velocityX, x, y, z),
					picY = access.sampleY(mac.velocityY, x, y, z),
					picZ = access.sampleZ(mac.velocityZ, x, y, z);
			float deltaX = picX - access.sampleX(mac.p2gVelocityX, x, y, z);
			float deltaY = picY - access.sampleY(mac.p2gVelocityY, x, y, z);
			float deltaZ = picZ - access.sampleZ(mac.p2gVelocityZ, x, y, z);
			particles.setVelocityX(i, lerp(picX, particles.velocityX(i) + deltaX, FLIP_RATIO));
			particles.setVelocityY(i, lerp(picY, particles.velocityY(i) + deltaY, FLIP_RATIO));
			particles.setVelocityZ(i, lerp(picZ, particles.velocityZ(i) + deltaZ, FLIP_RATIO));
		}
	}

	private static final float FLIP_RATIO = 0.90F;

	private static float lerp(float first, float second, float t) { return first + (second - first) * t; }
}
