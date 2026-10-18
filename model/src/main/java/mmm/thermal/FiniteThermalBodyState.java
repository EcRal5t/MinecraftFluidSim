package mmm.thermal;

/** Immutable loader-neutral body truth stored by a finite thermal device owner. */
public record FiniteThermalBodyState(
		long generation,
		String thermalRegistryFingerprint,
		String thermalMaterialId,
		long massUnits,
		double internalEnergyJ
) {
	public double massKilograms() { return mmm.units.Mass.kilograms(massUnits); }
	public FiniteThermalBodyState {
		if (generation < 0L) throw new IllegalArgumentException("Thermal body generation must not be negative");
		if (thermalRegistryFingerprint == null || !thermalRegistryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Thermal body registry fingerprint is invalid");
		}
		ThermalIds.requireId(thermalMaterialId, "finite thermal body material id");
		if (!(massUnits > 0)
				|| !Double.isFinite(internalEnergyJ)) {
			throw new IllegalArgumentException("Finite thermal body mass or energy is invalid");
		}
	}
}
