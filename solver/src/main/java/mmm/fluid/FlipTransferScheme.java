package mmm.fluid;

/** Backend-neutral FLIP transfer invariants shared by CPU and GPU implementations. */
public final class FlipTransferScheme {
	/** Two layers cover trilinear support plus the tangential H(div) slope stencil. */
	public static final int VELOCITY_EXTRAPOLATION_LAYERS = 2;

	private FlipTransferScheme() { }

	/**
	 * Selects the largest FLIP increment in {@code [0,1]} whose particle kinetic
	 * energy does not exceed the supplied particle/grid work budget. The candidate
	 * energy is {@code quadratic * alpha^2 + linear * alpha + constant}; the
	 * caller supplies a limit no smaller than the PIC constant term.
	 */
	public static double maximumEnergyStableTransfer(
			double quadratic, double linear, double constant, double energyLimit
	) {
		double available = energyLimit - constant;
		if (quadratic + linear <= available) return 1.0;
		if (!(quadratic > 0.0)) return 0.0;
		if (!(available >= 0.0 && Double.isFinite(available)
				&& Double.isFinite(quadratic) && Double.isFinite(linear))) return 0.0;
		// The admissible increment is independent of the common energy scale. Scale
		// before squaring, and form the geometric mean from square roots so tiny
		// available energy survives even when available / scale would underflow.
		double scale = Math.max(quadratic, Math.max(Math.abs(linear), available));
		double normalizedLinear = linear / scale;
		double sqrtScale = Math.sqrt(scale), sqrtAvailable = Math.sqrt(available) / sqrtScale;
		double discriminantRoot = Math.hypot(normalizedLinear,
				2 * (Math.sqrt(quadratic) / sqrtScale) * sqrtAvailable);
		double root;
		if (linear >= 0.0) {
			if (available == 0.0) return 0.0;
			// Rationalizing the positive root avoids subtracting two nearly equal values.
			root = sqrtAvailable * (sqrtAvailable / (0.5 * normalizedLinear + 0.5 * discriminantRoot));
		} else {
			root = (0.5 * discriminantRoot - 0.5 * normalizedLinear) / (quadratic / scale);
		}
		return Math.clamp(root, 0.0, 1.0);
	}
}
