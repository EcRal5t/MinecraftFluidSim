package com.example.thermal;

import java.util.Arrays;

/** Canonical pairwise explicit finite-volume conduction over a rebuilt LTE grid. */
public final class ThermalTransport {
	public static final int DEFAULT_MAXIMUM_SUBSTEPS = 8;

	@FunctionalInterface
	public interface FaceArea {
		/** Positive-axis shared material contact area in square metres. */
		double area(int x, int y, int z, int axis);
	}

	public record Stats(int substeps, int evaluatedFaces, double maximumTemperatureChangeK,
	                    SignedDyadic roundoffResidual) { }

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

	public Stats step(ThermalCellGrid grid, FaceArea faceArea, double logicalDtSeconds) {
		if (grid == null || grid.cellCount() != conductanceSum.length || faceArea == null) {
			throw new IllegalArgumentException("Thermal transport grid/geometry is incompatible");
		}
		if (!(Double.isFinite(logicalDtSeconds) && logicalDtSeconds > 0.0)) {
			throw new IllegalArgumentException("Thermal timestep must be finite and positive");
		}
		SignedDyadic before = inventory(grid);
		double[] initialTemperature = new double[grid.cellCount()];
		for (int cell = 0; cell < initialTemperature.length; cell++) initialTemperature[cell] = grid.temperature(cell);

		Arrays.fill(conductanceSum, 0.0);
		int evaluatedFaces = forEachFace(grid, faceArea, (first, second, conductance) -> {
			conductanceSum[first] += conductance;
			conductanceSum[second] += conductance;
		});
		double stableDt = Double.POSITIVE_INFINITY;
		for (int cell = 0; cell < conductanceSum.length; cell++) if (conductanceSum[cell] > 0.0) {
			stableDt = Math.min(stableDt, grid.heatCapacity(cell) / conductanceSum[cell]);
		}
		int substeps = stableDt == Double.POSITIVE_INFINITY ? 1 : (int) Math.ceil(logicalDtSeconds / stableDt);
		if (substeps > maximumSubsteps) {
			throw new IllegalArgumentException("Thermal stability substep budget exceeded: " + substeps);
		}
		double substepDt = logicalDtSeconds / substeps;
		for (int step = 0; step < substeps; step++) {
			Arrays.fill(deltaEnergy, 0.0);
			forEachFace(grid, faceArea, (first, second, conductance) -> {
				double transfer = conductance * (grid.temperature(second) - grid.temperature(first)) * substepDt;
				if (!Double.isFinite(transfer)) throw new IllegalArgumentException("Thermal face transfer is not finite");
				deltaEnergy[first] += transfer;
				deltaEnergy[second] -= transfer;
			});
			double[] target = grid.copyInternalEnergy();
			for (int cell = 0; cell < target.length; cell++) target[cell] += deltaEnergy[cell];
			grid.setCellInternalEnergies(target);
		}
		double maximumChange = 0.0;
		for (int cell = 0; cell < initialTemperature.length; cell++) if (grid.occupied(cell)) {
			maximumChange = Math.max(maximumChange, Math.abs(grid.temperature(cell) - initialTemperature[cell]));
		}
		SignedDyadic residual = inventory(grid).subtract(before);
		return new Stats(substeps, evaluatedFaces, maximumChange, residual);
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

	@FunctionalInterface
	private interface FaceConsumer { void accept(int first, int second, double conductance); }
}
