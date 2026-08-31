package cc.ecisr.mmm.fluid;

/**
 * The grid-to-particle handoff: air-cell velocity extrapolation into the boundary fringe
 * followed by the largest energy-admissible FLIP increment into authoritative marker momentum.
 * There is no fixed PIC ratio: the projected grid and current state determine whether the full
 * increment is admissible. This transfer does not impersonate viscosity; physical viscous diffusion
 * requires its own momentum stage. The extrapolator and its workspace stay with the domain lifecycle;
 * the stage only sequences them.
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
		boolean variableDensityActive();
		float referenceDensity();
	}

	private final Workspace state;
	private final Access access;
	private float[] picX = new float[0], picY = new float[0], picZ = new float[0];
	private float[] flipDeltaX = new float[0], flipDeltaY = new float[0], flipDeltaZ = new float[0];
	private double preProjectionEnergy = Double.NaN;

	GridToParticleStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	/** Extrapolates grid velocities into air and transfers their change to marker velocities. */
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

	/** Captures the body-force energy budget before pressure can redistribute the grid field. */
	void capturePreProjectionEnergy() {
		preProjectionEnergy = transferGridKineticEnergy();
	}

	private void gridToParticles() {
		LiquidParticleStore particles = state.particles;
		MacVelocityGrid mac = state.mac;
		ensureCandidateCapacity(particles.count());
		double quadratic = 0.0, linear = 0.0, picEnergy = 0.0;
		for (int i = 0; i < particles.count(); i++) {
			float x = particles.positionX(i), y = particles.positionY(i), z = particles.positionZ(i);
			picX[i] = access.sampleX(mac.velocityX, x, y, z);
			picY[i] = access.sampleY(mac.velocityY, x, y, z);
			picZ[i] = access.sampleZ(mac.velocityZ, x, y, z);
			flipDeltaX[i] = particles.velocityX(i) - access.sampleX(mac.p2gVelocityX, x, y, z);
			flipDeltaY[i] = particles.velocityY(i) - access.sampleY(mac.p2gVelocityY, x, y, z);
			flipDeltaZ[i] = particles.velocityZ(i) - access.sampleZ(mac.p2gVelocityZ, x, y, z);
			double mass = particles.mass(i);
			double picSquared = picX[i] * (double) picX[i] + picY[i] * (double) picY[i]
					+ picZ[i] * (double) picZ[i];
			double deltaSquared = flipDeltaX[i] * (double) flipDeltaX[i]
					+ flipDeltaY[i] * (double) flipDeltaY[i]
					+ flipDeltaZ[i] * (double) flipDeltaZ[i];
			quadratic += 0.5 * mass * deltaSquared;
			linear += mass * (picX[i] * (double) flipDeltaX[i]
					+ picY[i] * (double) flipDeltaY[i] + picZ[i] * (double) flipDeltaZ[i]);
			picEnergy += 0.5 * mass * picSquared;
		}
		if (!Double.isFinite(preProjectionEnergy)) {
			throw new IllegalStateException("G2P transfer energy was not captured before pressure projection");
		}
		double transferBudget = Math.min(preProjectionEnergy, transferGridKineticEnergy());
		preProjectionEnergy = Double.NaN;
		double transfer = FlipTransferScheme.maximumEnergyStableTransfer(
				quadratic, linear, picEnergy, Math.max(picEnergy, transferBudget));
		for (int i = 0; i < particles.count(); i++) {
			particles.setVelocityX(i, (float) (picX[i] + transfer * flipDeltaX[i]));
			particles.setVelocityY(i, (float) (picY[i] + transfer * flipDeltaY[i]));
			particles.setVelocityZ(i, (float) (picZ[i] + transfer * flipDeltaZ[i]));
		}
	}

	private double transferGridKineticEnergy() {
		MacVelocityGrid mac = state.mac;
		double densityScale = access.variableDensityActive() ? 1.0 : access.referenceDensity();
		return faceEnergy(mac.velocityX, mac.p2gWeightX, densityScale)
				+ faceEnergy(mac.velocityY, mac.p2gWeightY, densityScale)
				+ faceEnergy(mac.velocityZ, mac.p2gWeightZ, densityScale);
	}

	private static double faceEnergy(float[] velocity, float[] weight, double densityScale) {
		double energy = 0.0;
		for (int face = 0; face < velocity.length; face++) {
			energy += 0.5 * densityScale * weight[face] * velocity[face] * (double) velocity[face];
		}
		return energy;
	}

	private void ensureCandidateCapacity(int count) {
		if (picX.length >= count) return;
		picX = new float[count]; picY = new float[count]; picZ = new float[count];
		flipDeltaX = new float[count]; flipDeltaY = new float[count]; flipDeltaZ = new float[count];
	}
}
