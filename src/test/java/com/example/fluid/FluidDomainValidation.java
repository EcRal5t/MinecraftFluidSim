package com.example.fluid;

/**
 * Dependency-free numerical validation. Run with Java assertions enabled or
 * call main directly; failures throw AssertionError in either case.
 */
public final class FluidDomainValidation {
	private FluidDomainValidation() { }

	public static void main(String[] args) {
		hydrostaticPressureIncreasesWithDepth();
		damBreakMovesAndConservesVolume();
		System.out.println("FluidDomain validation passed");
	}

	private static void hydrostaticPressureIncreasesWithDepth() {
		FluidSolverConfig config = new FluidSolverConfig(1.0F, 1_000.0F, 9.81F, 0.025F, 300, 1.0E-6F, 0.45F, 1.0E-5F);
		FluidDomain domain = new FluidDomain(5, 10, 5, config);
		for (int z = 0; z < 5; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 5; x++) domain.setVolume(x, y, z, 1.0F);

		FluidDomain.StepStats stats = domain.step();
		float previous = Float.POSITIVE_INFINITY;
		for (int y = 0; y < 6; y++) {
			float pressure = domain.pressure(2, y, 2);
			if (!(pressure > 0.0F)) throw new AssertionError("Hydrostatic pressure must be positive at y=" + y + ": " + pressure);
			if (y > 0 && !(pressure < previous)) throw new AssertionError("Pressure must decrease toward the surface");
			previous = pressure;
		}
		float bottom = domain.pressure(2, 0, 2);
		float top = domain.pressure(2, 5, 2);
		if (!(bottom > top * 4.0F)) throw new AssertionError("Water-column pressure gradient is too small: bottom=" + bottom + ", top=" + top);
		if (!(stats.pressureResidual() < 1.0F)) throw new AssertionError("Hydrostatic pressure solve did not converge: " + stats.pressureResidual());
	}

	private static void damBreakMovesAndConservesVolume() {
		FluidSolverConfig config = new FluidSolverConfig(1.0F, 1_000.0F, 9.81F, 0.025F, 250, 2.0E-5F, 0.40F, 1.0E-5F);
		FluidDomain domain = new FluidDomain(16, 8, 6, config);
		for (int z = 1; z < 5; z++) for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) domain.setVolume(x, y, z, 1.0F);
		float initialVolume = domain.totalVolume();
		float initialCenter = domain.centerOfMassX();
		float worstRelativeError = 0.0F;

		for (int step = 0; step < 80; step++) {
			FluidDomain.StepStats stats = domain.step();
			worstRelativeError = Math.max(worstRelativeError, stats.relativeVolumeError());
		}

		float finalVolume = domain.totalVolume();
		float finalCenter = domain.centerOfMassX();
		float totalRelativeError = Math.abs(finalVolume - initialVolume) / initialVolume;
		if (!(finalCenter > initialCenter + 0.35F)) {
			throw new AssertionError("Dam-break front did not move far enough: initial=" + initialCenter + ", final=" + finalCenter);
		}
		if (!(totalRelativeError < 2.0E-5F && worstRelativeError < 2.0E-5F)) {
			throw new AssertionError("Volume was not conserved: total=" + totalRelativeError + ", per-step worst=" + worstRelativeError);
		}
	}
}
