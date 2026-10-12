package mmm.thermal;

import java.util.Map;

/** Exact-batch equilibrium planner. It changes phase inventory only; total cell energy stays fixed. */
public final class ThermalPhaseChangeEngine {
	public record Plan(long signedBatches, double temperatureBeforeK, double temperatureAfterK) {
		public boolean changed() { return signedBatches != 0L; }
		public boolean freezing() { return signedBatches > 0L; }
	}

	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;

	public ThermalPhaseChangeEngine(ThermalRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Thermal phase registry is missing");
		this.registry = registry;
		this.stateFunction = new ThermalStateFunction(registry);
	}

	/**
	 * Positive batches move upper-energy phase to lower-energy phase (freezing/condensation).
	 * Negative batches move lower to upper. Composition-bearing phase transfer is deliberately
	 * rejected until the caller supplies its exact species allocation plan.
	 */
	public Plan planInert(
			ThermalPhaseTransition transition, double lowerMass, double upperMass,
			double totalInternalEnergy, long maximumMagnitudeBatches, double absolutePressurePa
	) {
		if (transition == null || lowerMass < 0.0 || upperMass < 0.0
				|| !(Double.isFinite(lowerMass) && Double.isFinite(upperMass)
				&& Double.isFinite(totalInternalEnergy)) || lowerMass + upperMass <= 0.0
				|| maximumMagnitudeBatches < 0L || maximumMagnitudeBatches > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Thermal phase plan inventory/budget is invalid");
		}
		int lowerHandle = registry.materialHandle(transition.lowerEnergyMaterialId());
		int upperHandle = registry.materialHandle(transition.upperEnergyMaterialId());
		double batchMass = transition.massMuPerBatch().toDouble();
		if (!(Double.isFinite(batchMass) && batchMass > 0.0)) {
			throw new IllegalArgumentException("Thermal transition batch mass is not representable");
		}
		double original = solveTemperature(lowerHandle, lowerMass, upperHandle, upperMass, totalInternalEnergy);
		double equilibrium = transition.equilibriumTemperatureK(absolutePressurePa);
		if (maximumMagnitudeBatches == 0L || original == equilibrium) {
			return new Plan(0L, original, original);
		}
		boolean freezing = original < equilibrium;
		double lower = lowerMass, upper = upperMass;
		double acceptedTemperature = original;
		double acceptedError = Math.abs(original - equilibrium);
		long signed = 0L;
		for (long operation = 0; operation < maximumMagnitudeBatches; operation++) {
			double candidateLower;
			double candidateUpper;
			if (freezing && upper + tolerance(upper) >= batchMass) {
				candidateUpper = upper - batchMass;
				if (candidateUpper < 0.0 && candidateUpper >= -tolerance(upper)) candidateUpper = 0.0;
				candidateLower = lower + batchMass;
			} else if (!freezing && lower + tolerance(lower) >= batchMass) {
				candidateLower = lower - batchMass;
				if (candidateLower < 0.0 && candidateLower >= -tolerance(lower)) candidateLower = 0.0;
				candidateUpper = upper + batchMass;
			} else break;
			double candidateTemperature = solveTemperature(
					lowerHandle, candidateLower, upperHandle, candidateUpper, totalInternalEnergy);
			double candidateError = Math.abs(candidateTemperature - equilibrium);
			if (!strictlyCloser(candidateError, acceptedError, candidateTemperature, equilibrium)) break;
			lower = candidateLower;
			upper = candidateUpper;
			acceptedTemperature = candidateTemperature;
			acceptedError = candidateError;
			signed += freezing ? 1L : -1L;
			if (freezing ? candidateTemperature >= equilibrium : candidateTemperature <= equilibrium) break;
		}
		return new Plan(signed, original, acceptedTemperature);
	}

	private double solveTemperature(
			int lowerHandle, double lowerMass, int upperHandle, double upperMass, double energy
	) {
		java.util.ArrayList<ThermalStateFunction.InventoryPortion> portions = new java.util.ArrayList<>(2);
		if (lowerMass > 0.0) portions.add(new ThermalStateFunction.InventoryPortion(lowerHandle, lowerMass, Map.of()));
		if (upperMass > 0.0) portions.add(new ThermalStateFunction.InventoryPortion(upperHandle, upperMass, Map.of()));
		return stateFunction.stateForInventory(portions, energy).temperatureK();
	}

	private static double tolerance(double value) { return Math.max(1.0E-12, Math.ulp(value) * 16.0); }

	private static boolean strictlyCloser(
			double candidateError, double acceptedError, double candidateTemperature, double equilibrium
	) {
		double threshold = Math.max(1.0E-12,
				Math.max(Math.ulp(candidateTemperature), Math.ulp(equilibrium)) * 16.0);
		return candidateError < acceptedError - threshold;
	}
}
