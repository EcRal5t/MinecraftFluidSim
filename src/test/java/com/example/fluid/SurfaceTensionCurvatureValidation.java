package com.example.fluid;

/** Standalone M4 surface-tension geometry gate: normal, mean curvature and the Laplace pressure-jump relation. */
public final class SurfaceTensionCurvatureValidation {
	private SurfaceTensionCurvatureValidation() { }

	public static void main(String[] args) {
		validatePlanarSlab();
		validateSphereCurvature();
		validateLaplacePressureJump();
		System.out.println("M4 surface-tension curvature gate passed: "
				+ "planar kappa=0, sphere kappa=2/R, Laplace jump=2*sigma/R");
	}

	private static void validatePlanarSlab() {
		int size = 16;
		double cellSize = 0.5, interfaceZ = 8.0 * cellSize;
		double[] colour = SurfaceTensionCurvatureExperiment.smooth(
				SurfaceTensionCurvatureExperiment.slabColour(size, cellSize, interfaceZ), size);
		double kappa = SurfaceTensionCurvatureExperiment.meanCurvature(colour, size, 8, 8, 8, cellSize);
		check(Math.abs(kappa) <= 1.0E-6, "planar interface curvature must vanish, got " + kappa);
	}

	private static void validateSphereCurvature() {
		int size = 24;
		double cellSize = 0.5;
		double[] radii = {3.0, 4.0, 5.0};
		for (double radius : radii) {
			double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(size, radius, cellSize);
			double kappa = SurfaceTensionCurvatureExperiment.interfaceMeanCurvature(colour, size, cellSize);
			double analytic = 2.0 / radius;
			double relativeError = Math.abs(kappa - analytic) / analytic;
			check(relativeError <= 0.06, "sphere R=" + radius + " curvature " + kappa
					+ " vs analytic " + analytic + " (error " + relativeError + ")");
			System.out.printf("  sphere R=%.1f kappa=%.4f analytic=%.4f error=%.3g%n",
					radius, kappa, analytic, relativeError);
		}
	}

	private static void validateLaplacePressureJump() {
		double sigma = 0.0728;
		int size = 24;
		double cellSize = 0.5, radius = 4.0;
		double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(size, radius, cellSize);
		double kappa = SurfaceTensionCurvatureExperiment.interfaceMeanCurvature(colour, size, cellSize);
		double jump = sigma * kappa;
		double analytic = 2.0 * sigma / radius;
		double relativeError = Math.abs(jump - analytic) / analytic;
		check(relativeError <= 0.06, "Laplace jump " + jump + " vs analytic " + analytic
				+ " (error " + relativeError + ")");
		System.out.printf("  Laplace jump sigma=%.4f kappa=%.4f -> %.5f vs %.5f (error %.3g)%n",
				sigma, kappa, jump, analytic, relativeError);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
