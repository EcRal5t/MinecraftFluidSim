package com.example.fluid;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Standalone selection gate for variable-density pressure-face coefficients. */
public final class VariableDensityFaceCoefficientValidation {
	private static final int CELLS = 12;
	private static final int INTERFACE_FACE = 5;

	private VariableDensityFaceCoefficientValidation() { }

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		List<VariableDensityPressureExperiment.Scenario> scenarios = scenarios();
		Map<VariableDensityPressureExperiment.FaceCoefficient, Double> scores =
				new EnumMap<>(VariableDensityPressureExperiment.FaceCoefficient.class);
		for (VariableDensityPressureExperiment.FaceCoefficient candidate
				: VariableDensityPressureExperiment.FaceCoefficient.values()) scores.put(candidate, 0.0);

		for (VariableDensityPressureExperiment.Scenario scenario : scenarios) {
			for (VariableDensityPressureExperiment.FaceCoefficient candidate
					: VariableDensityPressureExperiment.FaceCoefficient.values()) {
				VariableDensityPressureExperiment.Result result = VariableDensityPressureExperiment.run(scenario, candidate);
				validateNumerics(result);
				if (scenario.lowerDensity() == scenario.upperDensity()) validateConstantDensity(result);
				if (candidate == VariableDensityPressureExperiment.FaceCoefficient.INTERFACE_DISTANCE_WEIGHTED) {
					validateDistanceWeightedHydrostatics(result);
				}
				scores.compute(candidate, (ignored, score) -> score + result.score());
				print(result);
			}
		}

		validateCenteredInterface(scenarios);
		validateRanking(scores);
		System.out.printf("aggregate scores: average(1/rho)=%.6g 1/average(rho)=%.6g interface-distance=%.6g%n",
				scores.get(VariableDensityPressureExperiment.FaceCoefficient.AVERAGE_INVERSE_DENSITY),
				scores.get(VariableDensityPressureExperiment.FaceCoefficient.INVERSE_AVERAGE_DENSITY),
				scores.get(VariableDensityPressureExperiment.FaceCoefficient.INTERFACE_DISTANCE_WEIGHTED));
		System.out.println("Variable-density face coefficient validation passed: interface-distance conservative candidate selected");
	}

	private static List<VariableDensityPressureExperiment.Scenario> scenarios() {
		List<VariableDensityPressureExperiment.Scenario> result = new ArrayList<>();
		result.add(new VariableDensityPressureExperiment.Scenario(
				"equal-1:1/theta=0.25", CELLS, INTERFACE_FACE, 1_000.0, 1_000.0, 0.25));
		for (double fraction : new double[] {0.25, 0.50, 0.75}) {
			result.add(new VariableDensityPressureExperiment.Scenario(
					String.format("water-oil-1.25:1/theta=%.2f", fraction), CELLS, INTERFACE_FACE,
					1_000.0, 800.0, fraction));
			result.add(new VariableDensityPressureExperiment.Scenario(
					String.format("mercury-water-13.6:1/theta=%.2f", fraction), CELLS, INTERFACE_FACE,
					13_600.0, 1_000.0, fraction));
		}
		return result;
	}

	private static void validateNumerics(VariableDensityPressureExperiment.Result result) {
		check(result.symmetryError() <= 1.0E-15, result, "matrix lost symmetry");
		check(result.minimumCholeskyPivot() > 0.0, result, "matrix is not positive definite");
		check(result.pcgIterations() > 0 && result.pcgIterations() <= 32, result, "PCG iteration count escaped small-domain bound");
		check(result.pcgResidualRms() <= 1.0E-8, result, "PCG residual is too large");
		check(result.projectedMaximumVelocity() <= 1.0E-8, result, "projection left face velocity");
		check(result.projectedMaximumDivergence() <= 1.0E-8, result, "projection left divergence");
	}

	private static void validateConstantDensity(VariableDensityPressureExperiment.Result result) {
		check(result.pressureRelativeError() <= 1.0E-11, result, "1:1 density changed constant-density pressure");
		check(result.normalizedHydrostaticVelocityDefect() <= 1.0E-11, result, "1:1 density created hydrostatic motion");
		check(Math.abs(result.interfaceEffectiveDensity() - 1_000.0) <= 1.0E-9, result,
				"1:1 density changed the interface coefficient");
	}

	private static void validateDistanceWeightedHydrostatics(VariableDensityPressureExperiment.Result result) {
		check(result.pressureRelativeError() <= 1.0E-10, result, "distance-weighted pressure missed analytic hydrostatics");
		check(result.normalizedHydrostaticVelocityDefect() <= 1.0E-10, result,
				"distance-weighted coefficient generated an interface parasitic velocity");
		check(Math.abs(result.interfaceEffectiveDensity() - result.scenario().interfaceEffectiveDensity()) <= 1.0E-9,
				result, "distance-weighted effective density is not conservative");
	}

	private static void validateCenteredInterface(List<VariableDensityPressureExperiment.Scenario> scenarios) {
		for (VariableDensityPressureExperiment.Scenario scenario : scenarios) {
			if (scenario.lowerDensity() == scenario.upperDensity() || scenario.lowerMaterialDistanceFraction() != 0.5) continue;
			VariableDensityPressureExperiment.Result interpolated = VariableDensityPressureExperiment.run(
					scenario, VariableDensityPressureExperiment.FaceCoefficient.INVERSE_AVERAGE_DENSITY);
			VariableDensityPressureExperiment.Result weighted = VariableDensityPressureExperiment.run(
					scenario, VariableDensityPressureExperiment.FaceCoefficient.INTERFACE_DISTANCE_WEIGHTED);
			VariableDensityPressureExperiment.Result averagedInverse = VariableDensityPressureExperiment.run(
					scenario, VariableDensityPressureExperiment.FaceCoefficient.AVERAGE_INVERSE_DENSITY);
			check(interpolated.pressureRelativeError() <= 1.0E-10, interpolated,
					"1/average(rho) should match a centered sharp interface");
			check(Math.abs(interpolated.interfaceEffectiveDensity() - weighted.interfaceEffectiveDensity()) <= 1.0E-9,
					interpolated, "centered interface candidates disagree");
			check(averagedInverse.pressureRelativeError() > interpolated.pressureRelativeError() + 1.0E-5,
					averagedInverse, "average(1/rho) did not expose its high-ratio hydrostatic error");
		}
	}

	private static void validateRanking(Map<VariableDensityPressureExperiment.FaceCoefficient, Double> scores) {
		double averagedInverse = scores.get(VariableDensityPressureExperiment.FaceCoefficient.AVERAGE_INVERSE_DENSITY);
		double inverseAverage = scores.get(VariableDensityPressureExperiment.FaceCoefficient.INVERSE_AVERAGE_DENSITY);
		double distanceWeighted = scores.get(VariableDensityPressureExperiment.FaceCoefficient.INTERFACE_DISTANCE_WEIGHTED);
		check(distanceWeighted <= 1.0E-8, null, "distance-weighted aggregate error is not near zero");
		check(distanceWeighted < inverseAverage && inverseAverage < averagedInverse, null,
				"candidate ranking is not interface-distance < 1/average(rho) < average(1/rho)");
	}

	private static void print(VariableDensityPressureExperiment.Result result) {
		System.out.printf("%-35s | %-31s | rhoFace=%9.3f | pressureErr=%9.3g | hydroDefect/gdt=%9.3g | PCG=%2d residual=%9.3g | pivot=%9.3g%n",
				result.scenario().name(), result.candidate().label(), result.interfaceEffectiveDensity(),
				result.pressureRelativeError(), result.normalizedHydrostaticVelocityDefect(),
				result.pcgIterations(), result.pcgResidualRms(), result.minimumCholeskyPivot());
	}

	private static void check(boolean condition, VariableDensityPressureExperiment.Result result, String message) {
		if (condition) return;
		throw new AssertionError(result == null ? message : message + ": " + result.scenario().name() + "/" + result.candidate().label());
	}
}
