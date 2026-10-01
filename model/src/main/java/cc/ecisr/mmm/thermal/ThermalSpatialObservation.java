package cc.ecisr.mmm.thermal;

/** Read-only caloric portions for a spatial observation; no portion can publish carrier energy. */
final class ThermalSpatialObservation implements ThermalCellGrid.CarrierAccess {
	private int count;
	private int[] owner = new int[0], cell = new int[0];
	private double[] weight = new double[0];
	private double[] mass = new double[0], volume = new double[0], base = new double[0], energy = new double[0];
	private ThermalMaterialDefinition[] material = new ThermalMaterialDefinition[0];

	void prepare(ThermalCellGrid.CarrierAccess source, ThermalCarrierContext markers) {
		count = 0;
		int carriers = source.carrierCount();
		int capacity = Math.addExact(Math.multiplyExact(markers.markerCount(), 8), carriers - markers.markerCount());
		if (capacity > owner.length) {
			owner = new int[capacity]; cell = new int[capacity]; weight = new double[capacity];
		}
		if (carriers > mass.length) {
			mass = new double[carriers]; volume = new double[carriers]; base = new double[carriers];
			energy = new double[carriers];
			material = new ThermalMaterialDefinition[carriers];
		}
		for (int ordinal = 0; ordinal < carriers; ordinal++) {
			material[ordinal] = source.material(ordinal);
			mass[ordinal] = source.mass(ordinal); volume[ordinal] = source.volume(ordinal);
			base[ordinal] = source.baseEnergy(ordinal); energy[ordinal] = source.internalEnergy(ordinal);
			if (!(Double.isFinite(mass[ordinal]) && mass[ordinal] > 0 && Double.isFinite(volume[ordinal])
					&& volume[ordinal] > 0 && Double.isFinite(base[ordinal]) && Double.isFinite(energy[ordinal])))
				throw new IllegalArgumentException("Thermal observation carrier inventory is invalid");
			// Validate each real owner before averaging; a valid mean cannot hide an invalid carrier.
			material[ordinal].temperatureForSensibleSpecificEnergy(
					(energy[ordinal] - base[ordinal]) / mass[ordinal]);
			if (ordinal < markers.markerCount()) {
				int original = ordinal, start = count;
				markers.observationSupport(ordinal, (target, contribution) -> append(original, target, contribution));
				double sum = 0.0;
				for (int part = start; part < count; part++) sum += weight[part];
				if (count == start || count - start > 8 || Math.abs(sum - 1.0) > 1e-12)
					throw new IllegalArgumentException("Thermal observation weights do not form a partition of unity");
			} else append(ordinal, source.cell(ordinal), 1.0);
		}
	}

	private void append(int ordinal, int target, double contribution) {
		if (!(Double.isFinite(contribution) && contribution > 0.0) || count == owner.length)
			throw new IllegalArgumentException("Thermal observation support is invalid");
		owner[count] = ordinal; cell[count] = target; weight[count++] = contribution;
	}

	@Override public int carrierCount() { return count; }
	@Override public int cell(int part) { return cell[part]; }
	@Override public ThermalMaterialDefinition material(int part) { return material[owner[part]]; }
	@Override public double mass(int part) { return weight[part] * mass[owner[part]]; }
	@Override public double volume(int part) { return weight[part] * volume[owner[part]]; }
	@Override public double baseEnergy(int part) { return weight[part] * base[owner[part]]; }
	@Override public double internalEnergy(int part) { return weight[part] * energy[owner[part]]; }
	@Override public void setInternalEnergy(int part, double value) {
		throw new UnsupportedOperationException("Thermal spatial observations are read-only");
	}
}
