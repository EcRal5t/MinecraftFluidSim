package cc.ecisr.mmm.fluid;

/**
 * Numerical parameters for the standalone fluid prototype.
 *
 * <p>The solver uses Minecraft's positive Y direction as "up" and stores
 * pressure as gauge pressure, so atmospheric pressure is zero. Pressure tolerance is the
 * absolute RMS algebraic residual accepted from the projection.</p>
 */
public record FluidSolverConfig(
		float cellSize,
		float density,
		float gravity,
		float timeStep,
		int maxPressureIterations,
		float pressureTolerance,
		float maxCourantNumber,
		float fluidEpsilon,
		float interfaceCompression
) {
	public FluidSolverConfig {
		if (!(cellSize > 0.0F)) throw new IllegalArgumentException("cellSize must be positive");
		if (!(density > 0.0F)) throw new IllegalArgumentException("density must be positive");
		if (!(gravity >= 0.0F)) throw new IllegalArgumentException("gravity cannot be negative");
		if (!(timeStep > 0.0F)) throw new IllegalArgumentException("timeStep must be positive");
		if (maxPressureIterations < 1) throw new IllegalArgumentException("maxPressureIterations must be positive");
		if (!(pressureTolerance > 0.0F)) throw new IllegalArgumentException("pressureTolerance must be positive");
		if (!(maxCourantNumber > 0.0F && maxCourantNumber <= 1.0F)) {
			throw new IllegalArgumentException("maxCourantNumber must be in (0, 1]");
		}
		if (!(fluidEpsilon > 0.0F && fluidEpsilon < 1.0F)) {
			throw new IllegalArgumentException("fluidEpsilon must be in (0, 1)");
		}
		if (!(interfaceCompression >= 0.0F && interfaceCompression <= 1.0F)) {
			throw new IllegalArgumentException("interfaceCompression must be in [0, 1]");
		}
	}

	public static FluidSolverConfig prototypeDefaults() {
		return new FluidSolverConfig(
				1.0F,
				1_000.0F,
				9.81F,
				1.0F / 20.0F,
				200,
				1.0E-5F,
				0.45F,
				2.0E-3F,
				0.80F
		);
	}
}
