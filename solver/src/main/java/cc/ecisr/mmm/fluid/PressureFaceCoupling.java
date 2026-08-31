package cc.ecisr.mmm.fluid;

/**
 * The pressure-gradient metric of one oriented face. The first-to-second pressure jump is
 * {@code p_first(interface) - p_second(interface)}; the corrected oriented difference is
 * therefore {@code mobility * (p_second - p_first + jump)}. This convention makes a static
 * capillary interface ({@code p_first - p_second = jump}) produce exactly zero face impulse.
 *
 * <p>The mobility is the inverse series mass resistance through the two centre-to-interface
 * segments. A phase without a spatial momentum segment contributes zero distance on that side,
 * so the same algebra covers an open ghost-fluid boundary, the current lumped gas-pressure
 * unknown and a future two-sided spatial liquid/gas solve. No density-ratio branch or interface
 * regularization belongs here.</p>
 */
final class PressureFaceCoupling {
	private final float mobilityScale;
	private final float pressureJumpFirstMinusSecond;

	private PressureFaceCoupling(float mobilityScale, float pressureJumpFirstMinusSecond) {
		if (!(Float.isFinite(mobilityScale) && mobilityScale > 0.0F)) {
			throw new IllegalArgumentException("Pressure-face mobility must be finite and positive");
		}
		if (!Float.isFinite(pressureJumpFirstMinusSecond)) {
			throw new IllegalArgumentException("Pressure-face jump must be finite");
		}
		this.mobilityScale = mobilityScale;
		this.pressureJumpFirstMinusSecond = pressureJumpFirstMinusSecond;
	}

	static PressureFaceCoupling centered(float densityScale) {
		return new PressureFaceCoupling(densityScale, 0.0F);
	}

	static PressureFaceCoupling oneSided(
			float densityScale, float pressureDistanceInCells, float pressureJumpFirstMinusSecond
	) {
		if (!(Float.isFinite(pressureDistanceInCells) && pressureDistanceInCells > 0.0F)) {
			throw new IllegalArgumentException("One-sided pressure distance must be finite and positive");
		}
		return new PressureFaceCoupling(densityScale / pressureDistanceInCells,
				pressureJumpFirstMinusSecond);
	}

	static PressureFaceCoupling twoSided(
			double referenceDensity,
			double firstDensity, double firstDistanceInCells,
			double secondDensity, double secondDistanceInCells,
			double pressureJumpFirstMinusSecond
	) {
		if (!(Double.isFinite(referenceDensity) && referenceDensity > 0.0
				&& Double.isFinite(firstDensity) && firstDensity > 0.0
				&& Double.isFinite(secondDensity) && secondDensity > 0.0
				&& Double.isFinite(firstDistanceInCells) && firstDistanceInCells >= 0.0
				&& Double.isFinite(secondDistanceInCells) && secondDistanceInCells >= 0.0
				&& firstDistanceInCells + secondDistanceInCells > 0.0
				&& Double.isFinite(pressureJumpFirstMinusSecond))) {
			throw new IllegalArgumentException("Two-sided pressure-face state is invalid");
		}
		double seriesMassResistance = firstDensity * firstDistanceInCells
				+ secondDensity * secondDistanceInCells;
		double mobility = referenceDensity / seriesMassResistance;
		if (!(Double.isFinite(mobility) && mobility > 0.0
				&& mobility <= Float.MAX_VALUE
				&& pressureJumpFirstMinusSecond >= -Float.MAX_VALUE
				&& pressureJumpFirstMinusSecond <= Float.MAX_VALUE)) {
			throw new IllegalArgumentException("Two-sided pressure-face metric exceeds solver precision");
		}
		return new PressureFaceCoupling((float) mobility, (float) pressureJumpFirstMinusSecond);
	}

	PressureFaceCoupling reversed() {
		return new PressureFaceCoupling(mobilityScale, -pressureJumpFirstMinusSecond);
	}

	float mobilityScale() { return mobilityScale; }
	float pressureJumpFirstMinusSecond() { return pressureJumpFirstMinusSecond; }

	float matrixCoefficient(float openArea) {
		if (!(Float.isFinite(openArea) && openArea >= 0.0F)) {
			throw new IllegalArgumentException("Pressure-face open area is invalid");
		}
		return openArea * mobilityScale;
	}

	float orientedPressureDifference(float firstPressure, float secondPressure) {
		if (!Float.isFinite(firstPressure) || !Float.isFinite(secondPressure)) {
			throw new IllegalArgumentException("Pressure-face state is non-finite");
		}
		return mobilityScale * (secondPressure - firstPressure + pressureJumpFirstMinusSecond);
	}

	/** RHS contribution after eliminating the second pressure as a Dirichlet boundary. */
	float firstDirichletRhs(float openArea, float secondPressure) {
		if (!Float.isFinite(secondPressure)) {
			throw new IllegalArgumentException("Pressure boundary is non-finite");
		}
		return matrixCoefficient(openArea) * secondPressure + firstJumpRhs(openArea);
	}

	float firstJumpRhs(float openArea) {
		return matrixCoefficient(openArea) * pressureJumpFirstMinusSecond;
	}

	float secondJumpRhs(float openArea) {
		return -firstJumpRhs(openArea);
	}
}
