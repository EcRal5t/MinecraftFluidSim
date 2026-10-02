package cc.ecisr.mmm.fluid;

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
		double fullEnergy = quadratic + linear + constant;
		if (fullEnergy <= energyLimit) return 1.0;
		if (!(quadratic > 0.0)) return 0.0;
		double discriminant = linear * linear - 4.0 * quadratic * (constant - energyLimit);
		if (!(discriminant >= 0.0 && Double.isFinite(discriminant))) return 0.0;
		double root = (-linear + Math.sqrt(discriminant)) / (2.0 * quadratic);
		return Math.clamp(root, 0.0, 1.0);
	}
}
