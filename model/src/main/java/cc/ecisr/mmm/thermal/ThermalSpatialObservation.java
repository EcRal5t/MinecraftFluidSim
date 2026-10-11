package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

/** Read-only caloric portions for a spatial observation; no portion can publish carrier energy. */
final class ThermalSpatialObservation implements ThermalCellGrid.CarrierAccess {
	private int count;
	private int[] owner = new int[0], cell = new int[0];
	private double[] weight = new double[0];
	private double[] mass = new double[0], volume = new double[0];
	private SignedDyadic[] sensible = new SignedDyadic[0], base = new SignedDyadic[0];
	private ThermalMaterialDefinition[] material = new ThermalMaterialDefinition[0];

	void prepare(ThermalCellGrid.CarrierAccess source, ThermalCarrierContext markers) {
		count = 0;
		int carriers = source.carrierCount();
		int capacity = Math.addExact(Math.multiplyExact(markers.markerCount(), 8), carriers - markers.markerCount());
		if (capacity > owner.length) {
			owner = new int[capacity]; cell = new int[capacity]; weight = new double[capacity];
		}
		if (carriers > mass.length) {
			mass = new double[carriers]; volume = new double[carriers]; sensible = new SignedDyadic[carriers]; base = new SignedDyadic[carriers];
			material = new ThermalMaterialDefinition[carriers];
		}
		for (int ordinal = 0; ordinal < carriers; ordinal++) {
			material[ordinal] = source.material(ordinal);
			mass[ordinal] = source.mass(ordinal); volume[ordinal] = source.volume(ordinal);
			double reference = source.baseEnergy(ordinal), energy = source.internalEnergy(ordinal);
			if (!(Double.isFinite(mass[ordinal]) && mass[ordinal] > 0 && Double.isFinite(volume[ordinal])
					&& volume[ordinal] > 0 && Double.isFinite(reference) && Double.isFinite(energy)))
				throw new IllegalArgumentException("Thermal observation carrier inventory is invalid");
			// Validate each real owner before averaging; a valid mean cannot hide an invalid carrier.
			base[ordinal] = SignedDyadic.of(reference);
			sensible[ordinal] = SignedDyadic.of(energy).subtract(base[ordinal]);
			ThermalCaloricInversion.single(material[ordinal], mass[ordinal], sensible[ordinal]);
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
	@Override public SignedDyadic exactMass(int part) { return SignedDyadic.of(mass[owner[part]]).multiply(SignedDyadic.of(weight[part])); }
	@Override public double volume(int part) { return weight[part] * volume[owner[part]]; }
	// Preserve the same exact weight for the caloric coefficient, energy and reference.
	@Override public double baseEnergy(int part) { return exactBaseEnergy(part).toDouble(); }
	@Override public SignedDyadic exactBaseEnergy(int part) { return base[owner[part]].multiply(SignedDyadic.of(weight[part])); }
	@Override public double internalEnergy(int part) { return exactInternalEnergy(part).toDouble(); }
	@Override public SignedDyadic exactInternalEnergy(int part) { return exactBaseEnergy(part).add(sensible[owner[part]].multiply(SignedDyadic.of(weight[part]))); }
	@Override public void setInternalEnergy(int part, double value) {
		throw new UnsupportedOperationException("Thermal spatial observations are read-only");
	}
}
