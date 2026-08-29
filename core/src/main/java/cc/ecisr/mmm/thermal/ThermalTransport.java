package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Arrays;

/** Canonical pairwise explicit finite-volume conduction over a rebuilt LTE grid. */
public final class ThermalTransport {
	public static final int DEFAULT_MAXIMUM_SUBSTEPS = 8;

	@FunctionalInterface
	public interface FaceArea {
		/** Positive-axis shared material contact area in square metres. */
		double area(int x, int y, int z, int axis);
	}

	@FunctionalInterface
	public interface BoundaryFace {
		/** Returns null for the default adiabatic face; direction is -x,+x,-y,+y,-z,+z in ordinal order. */
		FixedBoundary boundary(int x, int y, int z, int direction);
	}

	public record FixedBoundary(double contactAreaSquareMeters, double surfaceTemperatureK) {
		public FixedBoundary {
			if (!(Double.isFinite(contactAreaSquareMeters) && contactAreaSquareMeters >= 0.0)
					|| !(Double.isFinite(surfaceTemperatureK)
					&& surfaceTemperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
				throw new IllegalArgumentException("Fixed thermal boundary is invalid");
			}
		}
	}

	public record Stats(int substeps, int evaluatedFaces, double maximumTemperatureChangeK,
	                    double maximumAbsoluteTransferEu,
	                    SignedDyadic roundoffResidual, SignedDyadic reservoirExchange) {
		public Stats(int substeps, int evaluatedFaces, double maximumTemperatureChangeK,
		             SignedDyadic roundoffResidual, SignedDyadic reservoirExchange) {
			this(substeps, evaluatedFaces, maximumTemperatureChangeK, 0.0, roundoffResidual, reservoirExchange);
		}
	}

	private final int sizeX, sizeY, sizeZ;
	private final double cellSizeMeters;
	private final int maximumSubsteps;
	private final double[] conductanceSum;
	private final double[] deltaEnergy;

	public ThermalTransport(int sizeX, int sizeY, int sizeZ, double cellSizeMeters, int maximumSubsteps) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Thermal grid dimensions are invalid");
		if (!(Double.isFinite(cellSizeMeters) && cellSizeMeters > 0.0)) {
			throw new IllegalArgumentException("Thermal cell size must be finite and positive");
		}
		if (maximumSubsteps <= 0 || maximumSubsteps > DEFAULT_MAXIMUM_SUBSTEPS) {
			throw new IllegalArgumentException("Thermal substep budget is invalid");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellSizeMeters = cellSizeMeters;
		this.maximumSubsteps = maximumSubsteps;
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.conductanceSum = new double[cells];
		this.deltaEnergy = new double[cells];
	}

	public int maximumSubsteps() { return maximumSubsteps; }

	public Stats step(ThermalCellGrid grid, FaceArea faceArea, double logicalDtSeconds) {
		return step(grid, faceArea, (x, y, z, direction) -> null, logicalDtSeconds);
	}

	public Stats step(
			ThermalCellGrid grid, FaceArea faceArea, BoundaryFace boundaryFace, double logicalDtSeconds
	) {
		if (grid == null || grid.cellCount() != conductanceSum.length || faceArea == null || boundaryFace == null) {
			throw new IllegalArgumentException("Thermal transport grid/geometry is incompatible");
		}
		if (!(Double.isFinite(logicalDtSeconds) && logicalDtSeconds > 0.0)) {
			throw new IllegalArgumentException("Thermal timestep must be finite and positive");
		}
		double[] markerSnapshot = grid.copyCarrierInternalEnergy();
		try {
			return stepValidated(grid, faceArea, boundaryFace, logicalDtSeconds);
		} catch (RuntimeException failure) {
			grid.restoreCarrierInternalEnergy(markerSnapshot);
			throw failure;
		}
	}

	private Stats stepValidated(
			ThermalCellGrid grid, FaceArea faceArea, BoundaryFace boundaryFace, double logicalDtSeconds
	) {
		SignedDyadic before = inventory(grid);
		double[] initialTemperature = new double[grid.cellCount()];
		for (int cell = 0; cell < initialTemperature.length; cell++) initialTemperature[cell] = grid.temperature(cell);

		Arrays.fill(conductanceSum, 0.0);
		int evaluatedFaces = forEachFace(grid, faceArea, (first, second, conductance) -> {
			conductanceSum[first] += conductance;
			conductanceSum[second] += conductance;
		});
		evaluatedFaces += forEachBoundary(grid, boundaryFace,
				(cell, conductance, surfaceTemperature) -> conductanceSum[cell] += conductance);
		double stableDt = Double.POSITIVE_INFINITY;
		for (int cell = 0; cell < conductanceSum.length; cell++) if (conductanceSum[cell] > 0.0) {
			// A pair of equal cells both move during one Jacobi update. The usual positivity bound C/sum(G)
			// permits an exact temperature swap at equality; half that bound preserves pair ordering and
			// gives monotone approach to LTE without a mesh- or material-specific damping heuristic.
			stableDt = Math.min(stableDt, 0.5 * grid.heatCapacity(cell) / conductanceSum[cell]);
		}
		int substeps = stableDt == Double.POSITIVE_INFINITY ? 1 : (int) Math.ceil(logicalDtSeconds / stableDt);
		if (substeps > maximumSubsteps) {
			throw new IllegalArgumentException("Thermal stability substep budget exceeded: " + substeps);
		}
		double substepDt = logicalDtSeconds / substeps;
		SignedDyadic[] reservoirExchange = {SignedDyadic.ZERO};
		double[] maximumAbsoluteTransfer = {0.0};
		for (int step = 0; step < substeps; step++) {
			Arrays.fill(deltaEnergy, 0.0);
			forEachFace(grid, faceArea, (first, second, conductance) -> {
				double transfer = conductance * (grid.temperature(second) - grid.temperature(first)) * substepDt;
				if (!Double.isFinite(transfer)) throw new IllegalArgumentException("Thermal face transfer is not finite");
				maximumAbsoluteTransfer[0] = Math.max(maximumAbsoluteTransfer[0], Math.abs(transfer));
				deltaEnergy[first] += transfer;
				deltaEnergy[second] -= transfer;
			});
			forEachBoundary(grid, boundaryFace, (cell, conductance, surfaceTemperature) -> {
				double transfer = conductance * (surfaceTemperature - grid.temperature(cell)) * substepDt;
				if (!Double.isFinite(transfer)) throw new IllegalArgumentException("Thermal reservoir transfer is not finite");
				maximumAbsoluteTransfer[0] = Math.max(maximumAbsoluteTransfer[0], Math.abs(transfer));
				deltaEnergy[cell] += transfer;
				reservoirExchange[0] = reservoirExchange[0].add(SignedDyadic.of(transfer));
			});
			if (maximumAbsoluteTransfer[0] == 0.0 && grid.carriersMatchSolvedTemperature()) continue;
			double[] target = grid.copyInternalEnergy();
			for (int cell = 0; cell < target.length; cell++) target[cell] += deltaEnergy[cell];
			grid.setCellInternalEnergies(target);
		}
		double maximumChange = 0.0;
		for (int cell = 0; cell < initialTemperature.length; cell++) if (grid.occupied(cell)) {
			maximumChange = Math.max(maximumChange, Math.abs(grid.temperature(cell) - initialTemperature[cell]));
		}
		SignedDyadic residual = inventory(grid).subtract(before).subtract(reservoirExchange[0]);
		return new Stats(substeps, evaluatedFaces, maximumChange, maximumAbsoluteTransfer[0],
				residual, reservoirExchange[0]);
	}

	private int forEachBoundary(ThermalCellGrid grid, BoundaryFace boundaryFace, BoundaryConsumer consumer) {
		int evaluated = 0;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			if (!grid.occupied(cell)) continue;
			for (int direction = 0; direction < 6; direction++) {
				int neighborX = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
				int neighborY = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
				int neighborZ = z + (direction == 4 ? -1 : direction == 5 ? 1 : 0);
				if (inside(neighborX, neighborY, neighborZ)
						&& grid.occupied(cellIndex(neighborX, neighborY, neighborZ))) continue;
				FixedBoundary boundary = boundaryFace.boundary(x, y, z, direction);
				if (boundary == null || boundary.contactAreaSquareMeters() == 0.0 || grid.conductivity(cell) == 0.0) continue;
				if (boundary.contactAreaSquareMeters() > cellSizeMeters * cellSizeMeters) {
					throw new IllegalArgumentException("Thermal boundary contact area exceeds one face");
				}
				double conductance = 2.0 * grid.conductivity(cell)
						* boundary.contactAreaSquareMeters() / cellSizeMeters;
				if (!(Double.isFinite(conductance) && conductance > 0.0)) {
					throw new IllegalArgumentException("Thermal boundary conductance is invalid");
				}
				consumer.accept(cell, conductance, boundary.surfaceTemperatureK());
				evaluated++;
			}
		}
		return evaluated;
	}

	private int forEachFace(ThermalCellGrid grid, FaceArea faceArea, FaceConsumer consumer) {
		int evaluated = 0;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int first = cellIndex(x, y, z);
			if (x + 1 < sizeX && grid.occupied(first) && grid.occupied(first + 1)) {
				evaluated += consumeFace(grid, faceArea, consumer, first, first + 1, x, y, z, 0);
			}
			if (y + 1 < sizeY && grid.occupied(first) && grid.occupied(first + sizeX)) {
				evaluated += consumeFace(grid, faceArea, consumer, first, first + sizeX, x, y, z, 1);
			}
			if (z + 1 < sizeZ && grid.occupied(first) && grid.occupied(first + sizeX * sizeY)) {
				evaluated += consumeFace(grid, faceArea, consumer, first, first + sizeX * sizeY, x, y, z, 2);
			}
		}
		return evaluated;
	}

	private int consumeFace(
			ThermalCellGrid grid, FaceArea faceArea, FaceConsumer consumer,
			int first, int second, int x, int y, int z, int axis
	) {
		double area = faceArea.area(x, y, z, axis);
		if (!(Double.isFinite(area) && area >= 0.0 && area <= cellSizeMeters * cellSizeMeters)) {
			throw new IllegalArgumentException("Thermal face contact area is invalid");
		}
		double firstK = grid.conductivity(first), secondK = grid.conductivity(second);
		if (area == 0.0 || firstK == 0.0 || secondK == 0.0) return 0;
		double faceK = 2.0 * firstK * secondK / (firstK + secondK);
		double conductance = faceK * area / cellSizeMeters;
		if (!(Double.isFinite(conductance) && conductance > 0.0)) {
			throw new IllegalArgumentException("Thermal face conductance is invalid");
		}
		consumer.accept(first, second, conductance);
		return 1;
	}

	private SignedDyadic inventory(ThermalCellGrid grid) {
		SignedDyadic result = SignedDyadic.ZERO;
		for (int cell = 0; cell < grid.cellCount(); cell++) if (grid.occupied(cell)) {
			result = result.add(SignedDyadic.of(grid.internalEnergy(cell)));
		}
		return result;
	}

	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private boolean inside(int x, int y, int z) {
		return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
	}

	@FunctionalInterface
	private interface FaceConsumer { void accept(int first, int second, double conductance); }
	@FunctionalInterface
	private interface BoundaryConsumer { void accept(int cell, double conductance, double surfaceTemperature); }
}
