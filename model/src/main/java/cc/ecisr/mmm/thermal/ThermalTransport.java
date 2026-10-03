package cc.ecisr.mmm.thermal;

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
		if (grid == null || grid.cellCount() != cellCount || faceArea == null || boundaryFace == null)
			throw new IllegalArgumentException("Thermal transport grid/geometry is incompatible");
		var owners = grid.carrierAccess();
		var contacts = new ThermalCarrierContacts(Math.multiplyExact(owners.carrierCount(), 8));
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z);
			if (!grid.occupied(cell)) continue;
			// Subcell thermal geometry is unresolved here: use the cell mixing length h/2.
			// The symmetric volume quadrature retains a finite rate for every positive k.
			for (int p = grid.firstCarrier(cell); p >= 0; p = grid.nextCarrier(p))
				for (int q = grid.nextCarrier(p); q >= 0; q = grid.nextCarrier(q))
					addPair(owners, contacts, p, q, 2 * cellSizeMeters / grid.carrierVolume(cell));
			for (int axis = 0; axis < 3; axis++) {
				int nx = x + (axis == 0 ? 1 : 0), ny = y + (axis == 1 ? 1 : 0), nz = z + (axis == 2 ? 1 : 0);
				if (!inside(nx, ny, nz)) continue;
				int neighbor = cellIndex(nx, ny, nz);
				if (!grid.occupied(neighbor)) continue;
				double area = faceArea.area(x, y, z, axis);
				validateArea(area);
				if (area == 0) continue;
				double scale = area / (cellSizeMeters * grid.carrierVolume(cell) * grid.carrierVolume(neighbor));
				for (int p = grid.firstCarrier(cell); p >= 0; p = grid.nextCarrier(p))
					for (int q = grid.firstCarrier(neighbor); q >= 0; q = grid.nextCarrier(q))
						addPair(owners, contacts, p, q, scale);
			}
			for (int direction = 0; direction < 6; direction++) {
				int nx = x + (direction == 0 ? -1 : direction == 1 ? 1 : 0);
				int ny = y + (direction == 2 ? -1 : direction == 3 ? 1 : 0);
				int nz = z + (direction == 4 ? -1 : direction == 5 ? 1 : 0);
				if (inside(nx, ny, nz) && grid.occupied(cellIndex(nx, ny, nz))) continue;
				FixedBoundary boundary = boundaryFace.boundary(x, y, z, direction);
				if (boundary == null) continue;
				validateArea(boundary.contactAreaSquareMeters());
				for (int p = grid.firstCarrier(cell); p >= 0; p = grid.nextCarrier(p)) {
					double g = 2 * owners.material(p).conductivityEuPerSecondMeterK() * boundary.contactAreaSquareMeters()
							* owners.volume(p) / (cellSizeMeters * grid.carrierVolume(cell));
					if (g > 0) contacts.add(p, -1, g, boundary.surfaceTemperatureK());
				}
			}
		}
		double[] snapshot = grid.copyCarrierInternalEnergy();
		try {
			Stats result = ThermalCarrierTransport.step(owners, contacts, dt, maximumSubsteps);
			grid.rebuild(owners);
			return result;
		} catch (RuntimeException failure) {
			grid.restoreCarrierInternalEnergy(snapshot);
			throw failure;
		}
	}

	private static void addPair(ThermalCellGrid.CarrierAccess owners,
	                           ThermalCarrierContacts contacts, int p, int q, double scale) {
		double low = Math.min(owners.material(p).conductivityEuPerSecondMeterK(), owners.material(q).conductivityEuPerSecondMeterK());
		double high = Math.max(owners.material(p).conductivityEuPerSecondMeterK(), owners.material(q).conductivityEuPerSecondMeterK());
		if (low == 0) return;
		double g = low * (2 / (1 + low / high)) * owners.volume(p) * owners.volume(q) * scale;
		contacts.add(p, q, g, Double.NaN);
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
