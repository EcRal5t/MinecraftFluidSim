package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;

import java.util.List;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

abstract class ThermalValidationSupport {
	static final String CHEMICAL_FINGERPRINT = "1".repeat(64);

	static ThermalMaterialDefinition material(
			String id, String carrier, double referenceSpecificEnergy,
			List<ThermalMaterialDefinition.CaloricSegment> segments
	) {
		return new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.LIQUID, carrier,
				300.0, referenceSpecificEnergy, 0.5, segments);
	}

	static boolean close(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-10,
				Math.max(Math.ulp(first), Math.ulp(second)) * 8.0);
	}

	static double relativeError(double actual, double expected) {
		return Math.abs(actual - expected) / Math.abs(expected);
	}

	static void expectFailure(Runnable action, String message) {
		try { action.run(); }
		catch (RuntimeException expected) { return; }
		throw new AssertionError(message);
	}

	static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
