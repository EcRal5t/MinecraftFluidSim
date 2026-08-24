package com.example.thermal;

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
			double totalInternalEnergy, long maximumMagnitudeBatches
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
		double before = solveTemperature(lowerHandle, lowerMass, upperHandle, upperMass, totalInternalEnergy);
		double lower = lowerMass, upper = upperMass;
		long signed = 0L;
		for (long operation = 0; operation < maximumMagnitudeBatches; operation++) {
			if (before < transition.equilibriumTemperatureK() && upper + tolerance(upper) >= batchMass) {
				double candidateUpper = upper - batchMass;
				if (candidateUpper < 0.0 && candidateUpper >= -tolerance(upper)) candidateUpper = 0.0;
				double candidateLower = lower + batchMass;
				double candidateTemperature = solveTemperature(
						lowerHandle, candidateLower, upperHandle, candidateUpper, totalInternalEnergy);
				if (candidateTemperature > transition.equilibriumTemperatureK()
						&& signed > 0L) break;
				lower = candidateLower; upper = candidateUpper; before = candidateTemperature; signed++;
			} else if (before > transition.equilibriumTemperatureK() && lower + tolerance(lower) >= batchMass) {
				double candidateLower = lower - batchMass;
				if (candidateLower < 0.0 && candidateLower >= -tolerance(lower)) candidateLower = 0.0;
				double candidateUpper = upper + batchMass;
				double candidateTemperature = solveTemperature(
						lowerHandle, candidateLower, upperHandle, candidateUpper, totalInternalEnergy);
				if (candidateTemperature < transition.equilibriumTemperatureK()
						&& signed < 0L) break;
				lower = candidateLower; upper = candidateUpper; before = candidateTemperature; signed--;
			} else break;
		}
		double original = solveTemperature(lowerHandle, lowerMass, upperHandle, upperMass, totalInternalEnergy);
		return new Plan(signed, original, before);
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
}
