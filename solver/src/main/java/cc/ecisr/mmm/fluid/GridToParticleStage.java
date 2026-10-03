package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.compute.CpuWorkBatch;

/**
 * The grid-to-particle handoff: air-cell velocity extrapolation into the boundary fringe
 * followed by the largest energy-admissible FLIP increment into marker momentum.
 * In a complete liquid pressure space, a mass-orthogonal projection removes velocities
 * invisible to MAC sampling and the budget retains resolved energy plus actual grid work.
 * Free surfaces retain the grid energy budget. Newtonian diffusion has its own momentum stage.
 */
final class GridToParticleStage {
	record Workspace(
			DomainGeometry geometry, MarkerGridIndex markerGrid, MacVelocityGrid mac, boolean[] pressureFluid,
			LiquidParticleStore particles, FlipVelocityExtrapolator velocityExtrapolator,
			int sizeX, int sizeY, int sizeZ, float cellSize
	) { }

	interface Access {
		void samplePair(int axis, float[] field, float[] previous, float x, float y, float z, float[] result);
		boolean variableDensityActive();
		float referenceDensity();
	}

	private final Workspace state;
	private final Access access;
	private float[] picX = new float[0], picY = new float[0], picZ = new float[0];
	private float[] flipDeltaX = new float[0], flipDeltaY = new float[0], flipDeltaZ = new float[0];
	private double preProjectionEnergy = Double.NaN;
	private static final ThreadLocal<float[]> SAMPLED_PAIR = ThreadLocal.withInitial(() -> new float[2]);
	private boolean completePressureSpace;
	private final ResolvedMarkerVelocity resolvedVelocity;
	private final DivergenceFreeVelocityExtension velocityExtension;

	GridToParticleStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
		velocityExtension = new DivergenceFreeVelocityExtension(state.sizeX, state.sizeY, state.sizeZ, state.geometry.cutCells);
		resolvedVelocity = new ResolvedMarkerVelocity(state.sizeX, state.sizeY, state.sizeZ,
				state.cellSize, state.geometry.cutCells);
	}

	/** Extrapolates grid velocities into air and transfers their change to marker velocities. */
	void extrapolateAndTransfer() {
		extrapolateVelocityIntoAir();
		velocityExtension.extend(state.mac, state.pressureFluid, state.geometry.solid, state.markerGrid.markerCount);
		gridToParticles();
	}

	boolean[] advectionCells() { return velocityExtension.advectionCells; }

	void prepareAdvectionCells() {
		velocityExtension.prepareAdvectionCells(state.pressureFluid, state.geometry.solid, state.markerGrid.markerCount);
	}

	void extrapolateVelocityIntoAir() {
		MacVelocityGrid mac = state.mac;
		completePressureSpace = true;
		for (int cell = 0; cell < state.pressureFluid.length; cell++)
			if (!state.geometry.solid[cell] && !state.pressureFluid[cell]) { completePressureSpace = false; break; }
		System.arraycopy(state.pressureFluid, 0, velocityExtension.advectionCells, 0, state.pressureFluid.length);
		state.velocityExtrapolator.rebuildAndExtrapolate(
				state.geometry.solid, state.markerGrid.markerCount, state.pressureFluid, completePressureSpace,
				mac.velocityX, mac.velocityY, mac.velocityZ,
				mac.velocityValidX, mac.velocityValidY, mac.velocityValidZ,
				mac.velocityWeightX, mac.velocityWeightY, mac.velocityWeightZ
		);
	}

	/** Captures predicted energy so pressure redistribution supplies no additional transfer budget. */
	void capturePreProjectionEnergy() {
		preProjectionEnergy = transferGridKineticEnergy();
	}

	private void gridToParticles() {
		LiquidParticleStore particles = state.particles;
		MacVelocityGrid mac = state.mac;
		ensureCandidateCapacity(particles.count());
		for (int i = 0; i < particles.count(); i++) {
			flipDeltaX[i] = particles.velocityX(i); flipDeltaY[i] = particles.velocityY(i); flipDeltaZ[i] = particles.velocityZ(i);
		}
		if (completePressureSpace) {
			resolvedVelocity.project(particles, 0, mac.velocityValidX, flipDeltaX);
			resolvedVelocity.project(particles, 1, mac.velocityValidY, flipDeltaY);
			resolvedVelocity.project(particles, 2, mac.velocityValidZ, flipDeltaZ);
		}
		double quadratic = 0.0, linear = 0.0, picEnergy = 0.0, incomingEnergy = 0.0;
		for (int i = 0; i < particles.count(); i++) {
			double mass = particles.mass(i);
			incomingEnergy += 0.5 * mass * (flipDeltaX[i] * (double) flipDeltaX[i]
					+ flipDeltaY[i] * (double) flipDeltaY[i] + flipDeltaZ[i] * (double) flipDeltaZ[i]);
		}
		CpuWorkBatch.run(particles.count(), i -> {
			float[] sampledPair = SAMPLED_PAIR.get();
			float x = particles.positionX(i), y = particles.positionY(i), z = particles.positionZ(i);
			access.samplePair(0, mac.velocityX, mac.p2gVelocityX, x, y, z, sampledPair);
			picX[i] = sampledPair[0]; flipDeltaX[i] -= sampledPair[1];
			access.samplePair(1, mac.velocityY, mac.p2gVelocityY, x, y, z, sampledPair);
			picY[i] = sampledPair[0]; flipDeltaY[i] -= sampledPair[1];
			access.samplePair(2, mac.velocityZ, mac.p2gVelocityZ, x, y, z, sampledPair);
			picZ[i] = sampledPair[0]; flipDeltaZ[i] -= sampledPair[1];
		});
		for (int i = 0; i < particles.count(); i++) {
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
		if (completePressureSpace) transferBudget += incomingEnergy
				- transferGridKineticEnergy(mac.p2gVelocityX, mac.p2gVelocityY, mac.p2gVelocityZ);
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
		return transferGridKineticEnergy(mac.velocityX, mac.velocityY, mac.velocityZ);
	}

	private double transferGridKineticEnergy(float[] x, float[] y, float[] z) {
		MacVelocityGrid mac = state.mac;
		double densityScale = access.variableDensityActive() ? 1.0 : access.referenceDensity();
		return faceEnergy(x, mac.p2gWeightX, densityScale)
				+ faceEnergy(y, mac.p2gWeightY, densityScale)
				+ faceEnergy(z, mac.p2gWeightZ, densityScale);
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
