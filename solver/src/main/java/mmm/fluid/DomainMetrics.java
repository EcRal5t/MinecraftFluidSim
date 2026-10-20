package mmm.fluid;

import mmm.fluid.air.AirNetwork;

/**
 * Read-only aggregate metrics over the committed cell inventory: totals, extrema,
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

	/** Exact committed cell inventory, rounded once at the binary64 observation boundary. */
	double totalVolumeExact() {
		return domain.liquidCells.exactVolume().toDouble();
	}

	/** Total open cell volume across the domain; global capacity for exact bucket insertion. */
	float totalOpenVolume() {
		return (float) totalOpenVolumeExact();
	}

	double totalOpenVolumeExact() {
		if (domain.granularCoupling != null && domain.gridDirty) domain.domainFlowFields.ensureInventoryGrid();
		double result = 0.0;
		for (int cell = 0; cell < domain.cellCount; cell++) result += domain.domainCollision.effectiveOpenCellVolume(cell);
		return result;
	}

	/** Exact integer mass inventory; reconstructed grid density is never used for this ledger. */
	double totalMass() {
		return domain.unitScale.normalizedMass(totalMassUnits());
	}

	long totalMassUnits() { return domain.liquidCells.massUnits(); }

	float maximumParticleSpeed() {
		domain.domainFlowFields.ensureVelocityGrid();
		double sum = 0;
		for (float[] velocity : new float[][]{domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ}) {
			double maximum = 0;
			for (float value : velocity) maximum = Math.max(maximum, Math.abs(value));
			sum += maximum * maximum;
		}
		return (float) Math.sqrt(sum);
	}

	float maximumPressure() {
		domain.domainFlowFields.ensurePressureGeometry();
		float maximum = 0.0F;
		for (int cell = 0; cell < domain.cellCount; cell++) if (domain.liquidGrid.stockCount[cell] > 0) {
			maximum = Math.max(maximum, (float) domain.pressureSystem.liquidPressureAt(cell, ~domain.geometry.cutCells.mask(cell) & 255));
		}
		return maximum;
	}

	float centerOfMassX() {
		double weighted = 0; long mass = 0;
		for (var stock : domain.liquidCells.physicalStocks()) {
			weighted += (stock.key().cell() % domain.sizeX + .5) * stock.massUnits();
			mass = Math.addExact(mass, stock.massUnits());
		}
		return mass == 0 ? Float.NaN : (float) (weighted / mass);
	}

	FluidDomain.VolumeDistribution volumeDistribution(float visibleThreshold) {
		domain.domainFlowFields.ensureInventoryGrid();
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
		domain.domainFlowFields.ensureInventoryGrid();
		float h = domain.config.cellSize();
		int x = (int) Math.floor(localX / h), y = (int) Math.floor(localY / h), z = (int) Math.floor(localZ / h);
		if (!domain.domainFlowFields.inside(x, y, z)) return false;
		int index = domain.domainFlowFields.cellIndex(x, y, z);
		if (domain.geometry.solid[index] || domain.geometry.cutCells.pointInsideSolid(localX, localY, localZ, h)
				|| domain.volume[index] <= domain.config.fluidEpsilon()) return false;
		float openVolume = domain.domainCollision.effectiveOpenCellVolume(index);
		float reconstructedFill = openVolume <= 0.0F ? 0.0F : FluidDomain.clamp01(domain.volume[index] / openVolume);
		return localY - y * h < reconstructedFill * h;
	}

	/** Derived local mass/volume density for observation and acceptance; dry cells return NaN. */
	double densityInCell(int x, int y, int z) {
		int cell = domain.domainFlowFields.cellIndexChecked(x, y, z);
		domain.domainFlowFields.ensureInventoryGrid();
		return domain.liquidGrid.stockCount[cell] == 0 ? Double.NaN : domain.liquidGrid.cellDensity[cell];
	}

	int markerCellCount() {
		domain.domainFlowFields.ensureInventoryGrid();
		int result = 0;
		for (int count : domain.liquidGrid.stockCount) if (count > 0) result++;
		return result;
	}

	/** Returns the material handle with the largest exact marker volume in the given cell. */
	int dominantMaterialInCell(int x, int y, int z) {
		int cell = domain.domainFlowFields.cellIndexChecked(x, y, z);
		double[] amounts = new double[domain.materialRegistry == null ? 1 : domain.materialRegistry.size()];
		for (var stock : domain.liquidCells.stocksInCell(cell)) amounts[stock.key().material()] += stock.volumeFraction();
		int best = 0;
		for (int material = 1; material < amounts.length; material++) if (amounts[material] > amounts[best]) best = material;
		return best;
	}

	/** Exact nearby marker volume for planning a partial container extraction; -1 selects all materials. */
	double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) {
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		boolean[] near = new boolean[domain.cellCount];
		for (FluidDomain.CellCandidate candidate : domain.domainFlowFields.nearbyCells(centerX, centerY, centerZ, maxRadius)) {
			near[candidate.index()] = true;
		}
		double result = 0.0;
		for (var stock : domain.liquidCells.stocks()) {
			if (materialHandle >= 0 && stock.key().material() != materialHandle) continue;
			if (near[stock.key().cell()]) result += stock.volumeFraction();
		}
		return result;
	}


	boolean hasAmbientGasCells() {
		var frame = domain.gasState.frame;
		if (frame == null) return false;
		for (var piece : frame.geometry().pieces()) if (frame.nodeOfPiece(piece.id()) == AirNetwork.AMBIENT_NODE) return true;
		return false;
	}
}
