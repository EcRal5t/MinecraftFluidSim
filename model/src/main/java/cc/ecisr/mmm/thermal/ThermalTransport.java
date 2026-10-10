package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.compute.CpuWorkBatch;

import cc.ecisr.mmm.units.SignedDyadic;
/** Finite carrier contacts using cell mixing lengths and PLIC/cut-face thermal resistance. */
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

	public record Stats(int substeps, int evaluatedContacts, double maximumTemperatureChangeK,
	                    double maximumAbsoluteTransferEu,
	                    SignedDyadic roundoffResidual, SignedDyadic reservoirExchange) {
		public Stats(int substeps, int evaluatedContacts, double maximumTemperatureChangeK,
		             SignedDyadic roundoffResidual, SignedDyadic reservoirExchange) {
			this(substeps, evaluatedContacts, maximumTemperatureChangeK, 0.0, roundoffResidual, reservoirExchange);
		}
	}

	private final int sizeX, sizeY, sizeZ, cellCount;
	private final double cellSizeMeters;
	private final int maximumSubsteps;

	public ThermalTransport(int sizeX, int sizeY, int sizeZ, double cellSizeMeters, int maximumSubsteps) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Thermal grid dimensions are invalid");
		if (!(Double.isFinite(cellSizeMeters) && cellSizeMeters > 0))
			throw new IllegalArgumentException("Thermal cell size must be finite and positive");
		if (maximumSubsteps <= 0 || maximumSubsteps > DEFAULT_MAXIMUM_SUBSTEPS)
			throw new IllegalArgumentException("Thermal substep budget is invalid");
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.cellSizeMeters = cellSizeMeters; this.maximumSubsteps = maximumSubsteps;
	}

	public int maximumSubsteps() { return maximumSubsteps; }

	public Stats step(ThermalCellGrid grid, FaceArea faceArea, double dt) {
		return step(grid, faceArea, (x, y, z, direction) -> null, dt);
	}

	public Stats step(ThermalCellGrid grid, FaceArea faceArea, BoundaryFace boundaryFace, double dt) {
		return step(grid, faceArea, boundaryFace, dt, false);
	}

	/** The caller has rebuilt this grid immediately before the contact solve. */
	Stats stepPrepared(ThermalCellGrid grid, FaceArea faceArea, BoundaryFace boundaryFace, double dt) {
		return step(grid, faceArea, boundaryFace, dt, true);
	}

	private Stats step(ThermalCellGrid grid, FaceArea faceArea, BoundaryFace boundaryFace, double dt, boolean prepared) {
		if (grid == null || grid.cellCount() != cellCount || faceArea == null || boundaryFace == null)
			throw new IllegalArgumentException("Thermal transport grid/geometry is incompatible");
		var owners = grid.carrierAccess();
		int count = owners.carrierCount();
		double[] conductivity = new double[count], volume = new double[count];
		int[] conductingCarriers = new int[cellCount];
		for (int p = 0; p < count; p++) {
			conductivity[p] = owners.material(p).conductivityEuPerSecondMeterK(); volume[p] = owners.volume(p);
			if (conductivity[p] > 0) conductingCarriers[owners.cell(p)]++;
		}
		int[] starts = contactOffsets(grid, conductingCarriers), ends = new int[cellCount];
		double[][] areas = new double[3][cellCount];
		FixedBoundary[][] boundaries = new FixedBoundary[6][cellCount];
		// Geometry callbacks run in their original order before independent numerical work.
		for (int cell = 0; cell < cellCount; cell++) if (grid.occupied(cell)) {
			int x = cell % sizeX, y = cell / sizeX % sizeY, z = cell / (sizeX * sizeY);
			for (int axis = 0; axis < 3; axis++) {
				int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
				if (!inside(nx, ny, nz) || !grid.occupied(cellIndex(nx, ny, nz))) continue;
				validateArea(areas[axis][cell] = faceArea.area(x, y, z, axis));
			}
			for (int direction = 0; direction < 6; direction++) {
				int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
				int ny = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
				int nz = z + (direction == 4 ? -1 : direction == 5 ? 1 : 0);
				if (inside(nx, ny, nz) && grid.occupied(cellIndex(nx, ny, nz))) continue;
				FixedBoundary boundary = boundaries[direction][cell] = boundaryFace.boundary(x, y, z, direction);
				if (boundary != null) validateArea(boundary.contactAreaSquareMeters());
			}
		}
		double commonTemperature = uniformCarrierTemperature(owners);
		for (var direction : boundaries) for (var boundary : direction)
			if (boundary != null && boundary.surfaceTemperatureK() != commonTemperature) commonTemperature = Double.NaN;
		double[] noFluxDegree = Double.isNaN(commonTemperature) ? null : new double[count];
		var contacts = new ThermalCarrierContacts(starts[cellCount], noFluxDegree);
		CpuWorkBatch.RangeWork buildContacts = (from, until) -> {
			for (int cell = from; cell < until; cell++) {
				int cursor = starts[cell];
				if (!grid.occupied(cell)) { ends[cell] = cursor; continue; }
				int x = cell % sizeX, y = cell / sizeX % sizeY, z = cell / (sizeX * sizeY);
				// Subcell thermal geometry is unresolved here: use the cell mixing length h/2.
				// The symmetric volume quadrature retains a finite rate for every positive k.
				for (int p = grid.firstCarrier(cell); p >= 0; p = grid.nextCarrier(p))
					for (int q = grid.nextCarrier(p); q >= 0; q = grid.nextCarrier(q))
						cursor = addPair(conductivity, volume, contacts, cursor, p, q, 2 * cellSizeMeters / grid.carrierVolume(cell));
				for (int axis = 0; axis < 3; axis++) {
					int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
					if (!inside(nx, ny, nz)) continue;
					int neighbor = cellIndex(nx, ny, nz);
					if (!grid.occupied(neighbor)) continue;
					double area = areas[axis][cell];
					if (area == 0) continue;
					double scale = area / (cellSizeMeters * grid.carrierVolume(cell) * grid.carrierVolume(neighbor));
					for (int p = grid.firstCarrier(cell); p >= 0; p = grid.nextCarrier(p))
						for (int q = grid.firstCarrier(neighbor); q >= 0; q = grid.nextCarrier(q))
							cursor = addPair(conductivity, volume, contacts, cursor, p, q, scale);
				}
				for (int direction = 0; direction < 6; direction++) {
					int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
					int ny = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
					int nz = z + (direction == 4 ? -1 : direction == 5 ? 1 : 0);
					if (inside(nx, ny, nz) && grid.occupied(cellIndex(nx, ny, nz))) continue;
					FixedBoundary boundary = boundaries[direction][cell];
					if (boundary == null) continue;
					for (int p = grid.firstCarrier(cell); p >= 0; p = grid.nextCarrier(p)) {
						double g = 2 * conductivity[p] * boundary.contactAreaSquareMeters()
								* volume[p] / (cellSizeMeters * grid.carrierVolume(cell));
						if (g > 0) contacts.put(cursor++, p, -1, g, boundary.surfaceTemperatureK());
					}
				}
				if (cursor > starts[cell + 1]) throw new IllegalStateException("Thermal contact range exceeded its capacity");
				ends[cell] = cursor;
			}
		};
		if (noFluxDegree == null) CpuWorkBatch.run(cellCount, buildContacts);
		else buildContacts.run(0, cellCount); // Matches the original compact contact order for degree accumulation.
		contacts.compact(starts, ends);
		if (noFluxDegree != null) {
			if (!(Double.isFinite(dt) && dt > 0)) throw new IllegalArgumentException("Invalid carrier conduction input");
			for (double degree : noFluxDegree) if (!Double.isFinite(degree))
				throw new IllegalArgumentException("Non-finite conduction degree");
			if (prepared) grid.refreshCarrierEnergy(); else grid.rebuild(owners);
			return new Stats(1, contacts.size(), 0, 0, SignedDyadic.ZERO, SignedDyadic.ZERO);
		}
		double[] snapshot = grid.copyCarrierInternalEnergy();
		try {
			Stats result = ThermalCarrierTransport.step(owners, contacts, dt, maximumSubsteps);
			if (prepared) grid.refreshCarrierEnergy(); else grid.rebuild(owners);
			return result;
		} catch (RuntimeException failure) {
			grid.restoreCarrierInternalEnergy(snapshot);
			throw failure;
		}
	}

	private static double uniformCarrierTemperature(ThermalCellGrid.CarrierAccess owners) {
		double common = Double.NaN;
		for (int p = 0; p < owners.carrierCount(); p++) {
			double temperature = new ThermalContactExchange.Carrier(owners.material(p), owners.mass(p),
					owners.baseEnergy(p), owners.internalEnergy(p)).temperatureK();
			if (p == 0) common = temperature;
			else if (temperature != common) return Double.NaN;
		}
		return common;
	}

	/** Topological upper bound; closed/adiabatic faces can leave unused slots. */
	private int[] contactOffsets(ThermalCellGrid grid, int[] conductingCarriers) {
		int[] starts = new int[cellCount + 1];
		long capacity = 0;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			starts[cell] = Math.toIntExact(capacity);
			long local = conductingCarriers[cell];
			if (local == 0) continue;
			capacity = Math.addExact(capacity, local * (local - 1) / 2);
			for (int direction = 0; direction < 6; direction++) {
				int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
				int ny = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
				int nz = z + (direction == 4 ? -1 : direction == 5 ? 1 : 0);
				if (inside(nx, ny, nz) && grid.occupied(cellIndex(nx, ny, nz))) {
					if ((direction & 1) != 0)
						capacity = Math.addExact(capacity, local * conductingCarriers[cellIndex(nx, ny, nz)]);
				} else capacity = Math.addExact(capacity, local);
			}
		}
		starts[cellCount] = Math.toIntExact(capacity);
		return starts;
	}

	private static int addPair(double[] conductivity, double[] volume,
	                           ThermalCarrierContacts contacts, int cursor, int p, int q, double scale) {
		double low = Math.min(conductivity[p], conductivity[q]);
		double high = Math.max(conductivity[p], conductivity[q]);
		if (low == 0) return cursor;
		double g = low * (2 / (1 + low / high)) * volume[p] * volume[q] * scale;
		contacts.put(cursor, p, q, g, Double.NaN);
		return cursor + 1;
	}

	private void validateArea(double area) {
		if (!(Double.isFinite(area) && area >= 0 && area <= cellSizeMeters * cellSizeMeters))
			throw new IllegalArgumentException("Thermal contact area exceeds its face");
	}
	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private boolean inside(int x, int y, int z) {
		return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ;
	}
}
