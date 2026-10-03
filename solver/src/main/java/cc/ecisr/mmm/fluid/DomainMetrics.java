package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.units.SignedDyadic;

/**
 * Read-only aggregate metrics over the committed marker and cell truth: totals, extrema,
 * distributions and nearby-volume queries. It reads package-private domain state but never
 * mutates it; every method is safe on a frozen candidate and allocates only its result.
 */
final class DomainMetrics {
	private final FluidDomain domain;

	DomainMetrics(FluidDomain domain) {
		this.domain = domain;
	}

	float totalVolume() {
		return (float) totalVolumeExact();
	}

	/** Exact raw binary32 inventory, rounded once at the binary64 observation boundary. */
	double totalVolumeExact() {
		return SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count()).toDouble();
	}

	/** Total open cell volume across the domain; global capacity for exact bucket insertion. */
	float totalOpenVolume() {
		return (float) totalOpenVolumeExact();
	}

	double totalOpenVolumeExact() {
		if (domain.granularCoupling != null && domain.gridDirty) domain.ensureInventoryGrid();
		double result = 0.0;
		for (int cell = 0; cell < domain.cellCount; cell++) result += domain.effectiveOpenCellVolume(cell);
		return result;
	}

	/** Exact marker-mass inventory; reconstructed grid density is never used for this ledger. */
	double totalMass() {
		return SignedDyadic.sum(domain.particles.directArrays().mass, domain.particles.count()).toDouble();
	}

	float maximumParticleSpeed() {
		float maximumSquared = 0.0F;
		for (int i = 0; i < domain.particles.count(); i++) {
			float squared = domain.particles.velocityX(i) * domain.particles.velocityX(i)
					+ domain.particles.velocityY(i) * domain.particles.velocityY(i)
					+ domain.particles.velocityZ(i) * domain.particles.velocityZ(i);
			maximumSquared = Math.max(maximumSquared, squared);
		}
		return (float) Math.sqrt(maximumSquared);
	}

	float maximumPressure() {
		float maximum = 0.0F;
		for (int cell = 0; cell < domain.cellCount; cell++) if (domain.isFluid(cell)) {
			maximum = Math.max(maximum, domain.pressureReference + domain.projection.pressure[cell]);
		}
		return maximum;
	}

	float centerOfMassX() {
		double weighted = 0.0, mass = 0.0;
		for (int i = 0; i < domain.particles.count(); i++) {
			weighted += domain.particles.positionX(i) / domain.config.cellSize() * domain.particles.volume(i);
			mass += domain.particles.volume(i);
		}
		return mass == 0.0 ? Float.NaN : (float) (weighted / mass);
	}

	FluidDomain.VolumeDistribution volumeDistribution(float visibleThreshold) {
		domain.ensureInventoryGrid();
		int active = 0, visible = 0, dense = 0;
		for (float value : domain.volume) {
			if (value > domain.config.fluidEpsilon()) active++;
			if (value >= visibleThreshold) visible++;
			if (value >= 0.90F) dense++;
		}
		return new FluidDomain.VolumeDistribution(active, visible, dense);
	}

	FluidDomain.SolidDistribution solidDistribution() {
		int partial = 0, full = 0;
		for (int index = 0; index < domain.cellCount; index++) {
			int mask = domain.geometry.cutCells.mask(index);
			if (mask == FlipCutCellGeometry.FULL_MASK) full++;
			else if (mask != 0) partial++;
		}
		return new FluidDomain.SolidDistribution(partial, full);
	}

	boolean containsFluidAt(float localX, float localY, float localZ) {
		domain.ensureInventoryGrid();
		float h = domain.config.cellSize();
		int x = (int) Math.floor(localX / h), y = (int) Math.floor(localY / h), z = (int) Math.floor(localZ / h);
		if (!domain.inside(x, y, z)) return false;
		int index = domain.cellIndex(x, y, z);
		if (domain.geometry.solid[index] || domain.geometry.cutCells.pointInsideSolid(localX, localY, localZ, h)
				|| domain.volume[index] <= domain.config.fluidEpsilon()) return false;
		float openVolume = domain.effectiveOpenCellVolume(index);
		float reconstructedFill = openVolume <= 0.0F ? 0.0F : FluidDomain.clamp01(domain.volume[index] / openVolume);
		return localY - y * h < reconstructedFill * h;
	}

	/** Derived local mass/volume density for observation and acceptance; dry cells return NaN. */
	double densityInCell(int x, int y, int z) {
		int cell = domain.cellIndexChecked(x, y, z);
		domain.ensureInventoryGrid();
		return domain.markerGrid.markerCount[cell] == 0 ? Double.NaN : domain.markerGrid.cellDensity[cell];
	}

	int markerCellCount() {
		domain.ensureInventoryGrid();
		int result = 0;
		for (int count : domain.markerGrid.markerCount) if (count > 0) result++;
		return result;
	}

	/** Returns the material handle with the largest exact marker volume in the given cell. */
	int dominantMaterialInCell(int x, int y, int z) {
		int cell = domain.cellIndexChecked(x, y, z);
		if (domain.materialRegistry == null) return 0;
		float[] volumes = new float[domain.materialRegistry.size()];
		float h = domain.config.cellSize();
		for (int particle = 0; particle < domain.particles.count(); particle++) {
			if (domain.particleCellIndex(particle) != cell) continue;
			volumes[domain.particles.materialHandle(particle)] += domain.particles.volume(particle);
		}
		int best = 0;
		for (int handle = 1; handle < volumes.length; handle++) {
			if (volumes[handle] > volumes[best]) best = handle;
		}
		return best;
	}

	/** Exact nearby marker volume for planning a partial container extraction; -1 selects all materials. */
	double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) {
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		boolean[] near = new boolean[domain.cellCount];
		for (FluidDomain.CellCandidate candidate : domain.nearbyCells(centerX, centerY, centerZ, maxRadius)) {
			near[candidate.index()] = true;
		}
		double result = 0.0;
		for (int particle = 0; particle < domain.particles.count(); particle++) {
			if (materialHandle >= 0 && domain.particles.materialHandle(particle) != materialHandle) continue;
			if (near[domain.particleCellIndex(particle)]) result += domain.particles.volume(particle);
		}
		return result;
	}

	boolean hasPendingPhaseVolumeWork() {
		for (SignedDyadic value : domain.pendingPhaseCellVolume) if (!value.equals(SignedDyadic.ZERO)) return true;
		return false;
	}

	boolean hasAmbientGasCells() {
		if (domain.gasState.cellNodeId == null) return false;
		for (int node : domain.gasState.cellNodeId) if (node == AirNetwork.AMBIENT_NODE) return true;
		return false;
	}
}
