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
		int granularParcelCount();
		void connectTransferSupport(MarkerTransferBudget budget);
		boolean completelyFilled(int cell);
	}

	private final Workspace state;
	private final Access access;
	private float[] picX = new float[0], picY = new float[0], picZ = new float[0];
	private float[] flipDeltaX = new float[0], flipDeltaY = new float[0], flipDeltaZ = new float[0];
	private double preProjectionEnergy = Double.NaN;
	private double[] incomingMarkerEnergy = new double[0];
	private final boolean[] fullCells;
	private final boolean[][] resolvedValid;
	private static final ThreadLocal<float[]> SAMPLED_PAIR = ThreadLocal.withInitial(() -> new float[2]);
	private boolean completePressureSpace;
	private final ResolvedMarkerVelocity resolvedVelocity;
	private final DivergenceFreeVelocityExtension velocityExtension;
	private final MarkerTransferBudget componentBudget;

	GridToParticleStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
		fullCells = new boolean[state.pressureFluid.length];
		resolvedValid = new boolean[][] {new boolean[state.mac.velocityX.length], new boolean[state.mac.velocityY.length], new boolean[state.mac.velocityZ.length]};
		componentBudget = new MarkerTransferBudget(state.sizeX, state.sizeY, state.sizeZ, state.cellSize, state.mac, state.geometry.cutCells);
		velocityExtension = new DivergenceFreeVelocityExtension(state.sizeX, state.sizeY, state.sizeZ, state.geometry.cutCells);
		resolvedVelocity = new ResolvedMarkerVelocity(state.sizeX, state.sizeY, state.sizeZ,
				state.cellSize, state.geometry.cutCells);
	}

	/** Extrapolates grid velocities into air and transfers their change to marker velocities. */
	void extrapolateAndTransfer() {
		MarkerTransferBudget dependencies = componentBudget.begin(state.particles, access.granularParcelCount()) ? componentBudget : null;
		if (dependencies != null) access.connectTransferSupport(dependencies);
		extrapolateVelocityIntoAir(dependencies);
		velocityExtension.extend(state.mac, state.pressureFluid, state.geometry.solid, state.markerGrid.markerCount, dependencies);
		componentBudget.prepareSupport(state.particles, fullCells);
		gridToParticles();
	}

	boolean[] advectionCells() { return velocityExtension.advectionCells; }

	void prepareAdvectionCells() {
		velocityExtension.prepareAdvectionCells(state.pressureFluid, state.geometry.solid, state.markerGrid.markerCount);
	}

	void extrapolateVelocityIntoAir() {
		extrapolateVelocityIntoAir(null);
	}

	private void extrapolateVelocityIntoAir(MarkerTransferBudget dependencies) {
		MacVelocityGrid mac = state.mac;
		completePressureSpace = true;
		for (int cell = 0; cell < state.pressureFluid.length; cell++) {
			fullCells[cell] = state.pressureFluid[cell] && access.completelyFilled(cell);
			if (!state.geometry.solid[cell] && !fullCells[cell]) completePressureSpace = false;
		}
		System.arraycopy(state.pressureFluid, 0, velocityExtension.advectionCells, 0, state.pressureFluid.length);
		state.velocityExtrapolator.rebuildAndExtrapolate(
				state.geometry.solid, state.markerGrid.markerCount, state.pressureFluid, completePressureSpace,
				mac.velocityX, mac.velocityY, mac.velocityZ,
				mac.velocityValidX, mac.velocityValidY, mac.velocityValidZ,
				mac.velocityWeightX, mac.velocityWeightY, mac.velocityWeightZ, dependencies
		);
	}

	/** Captures predicted energy so pressure redistribution supplies no additional transfer budget. */
	void capturePreProjectionEnergy() {
		preProjectionEnergy = transferGridKineticEnergy();
		componentBudget.capture(access.variableDensityActive() ? 1.0 : access.referenceDensity());
	}

	private void gridToParticles() {
		LiquidParticleStore particles = state.particles;
		MacVelocityGrid mac = state.mac;
		ensureCandidateCapacity(particles.count());
		for (int i = 0; i < particles.count(); i++) {
			flipDeltaX[i] = particles.velocityX(i); flipDeltaY[i] = particles.velocityY(i); flipDeltaZ[i] = particles.velocityZ(i);
		}
		if (componentBudget.anyResolved()) {
			boolean[][] valid = {mac.velocityValidX, mac.velocityValidY, mac.velocityValidZ};
			for (int a = 0; a < 3; a++) {
				float[] open = state.geometry.cutCells.openFaceField(a);
				for (int f = 0; f < open.length; f++) resolvedValid[a][f] = valid[a][f] || open[f] == 0;
			}
			resolvedVelocity.project(particles, 0, resolvedValid[0], flipDeltaX, componentBudget);
			resolvedVelocity.project(particles, 1, resolvedValid[1], flipDeltaY, componentBudget);
			resolvedVelocity.project(particles, 2, resolvedValid[2], flipDeltaZ, componentBudget);
		}
		double quadratic = 0.0, linear = 0.0, picEnergy = 0.0, incomingEnergy = 0.0;
		for (int i = 0; i < particles.count(); i++) {
			double mass = particles.mass(i);
			incomingEnergy += incomingMarkerEnergy[i] = 0.5 * mass * (flipDeltaX[i] * (double) flipDeltaX[i]
					+ flipDeltaY[i] * (double) flipDeltaY[i] + flipDeltaZ[i] * (double) flipDeltaZ[i]);
		}
		CpuWorkBatch.run(particles.count(), (from, until) -> {
			float[] sampledPair = SAMPLED_PAIR.get();
			for (int i = from; i < until; i++) {
				float x = particles.positionX(i), y = particles.positionY(i), z = particles.positionZ(i);
				samplePair(i, 0, mac.velocityX, mac.p2gVelocityX, x, y, z, sampledPair);
				picX[i] = sampledPair[0]; flipDeltaX[i] -= sampledPair[1];
				samplePair(i, 1, mac.velocityY, mac.p2gVelocityY, x, y, z, sampledPair);
				picY[i] = sampledPair[0]; flipDeltaY[i] -= sampledPair[1];
				samplePair(i, 2, mac.velocityZ, mac.p2gVelocityZ, x, y, z, sampledPair);
				picZ[i] = sampledPair[0]; flipDeltaZ[i] -= sampledPair[1];
			}
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
		if (componentBudget.singleComponent() && componentBudget.anyResolved()) transferBudget += incomingEnergy
				- transferGridKineticEnergy(mac.p2gVelocityX, mac.p2gVelocityY, mac.p2gVelocityZ);
		preProjectionEnergy = Double.NaN;
		double transfer = FlipTransferScheme.maximumEnergyStableTransfer(
				quadratic, linear, picEnergy, Math.max(picEnergy, transferBudget));
		if (!componentBudget.singleComponent()) componentBudget.prepare(particles, picX, picY, picZ,
				flipDeltaX, flipDeltaY, flipDeltaZ, incomingMarkerEnergy, access.variableDensityActive() ? 1.0 : access.referenceDensity());
		for (int i = 0; i < particles.count(); i++) {
			double alpha = componentBudget.singleComponent() ? transfer : componentBudget.transfer(i);
			particles.setVelocityX(i, (float) (picX[i] + alpha * flipDeltaX[i]));
			particles.setVelocityY(i, (float) (picY[i] + alpha * flipDeltaY[i]));
			particles.setVelocityZ(i, (float) (picZ[i] + alpha * flipDeltaZ[i]));
		}
	}

	private void samplePair(int marker, int axis, float[] field, float[] previous, float x, float y, float z, float[] result) {
		if (!componentBudget.resolved(marker)) { access.samplePair(axis, field, previous, x, y, z, result); return; }
		FluidDomainMechanicalFields.trilinearValidPair(field, previous, resolvedValid[axis],
				state.sizeX + (axis == 0 ? 1 : 0), state.sizeY + (axis == 1 ? 1 : 0), state.sizeZ + (axis == 2 ? 1 : 0),
				x / state.cellSize - (axis == 0 ? 0 : 0.5F), y / state.cellSize - (axis == 1 ? 0 : 0.5F),
				z / state.cellSize - (axis == 2 ? 0 : 0.5F), result);
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
		incomingMarkerEnergy = new double[count];
		flipDeltaX = new float[count]; flipDeltaY = new float[count]; flipDeltaZ = new float[count];
	}
}
