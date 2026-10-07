package cc.ecisr.mmm.fluid;

/** A geometric trajectory needs a shorter interval before its candidate can be accepted. */
final class LiquidCharacteristicTimeResolutionRequired extends IllegalStateException {
	private final double maximumTimeStep;
	LiquidCharacteristicTimeResolutionRequired() {
		this("Liquid characteristic requires a shorter interval within incompressible interface support");
	}
	LiquidCharacteristicTimeResolutionRequired(String message) { super(message); maximumTimeStep = Double.NaN; }
	LiquidCharacteristicTimeResolutionRequired(String message, double maximumTimeStep) {
		super(message);
		if (!(maximumTimeStep > 0) || !Double.isFinite(maximumTimeStep)) throw new IllegalArgumentException("Invalid certified geometric time interval");
		this.maximumTimeStep = maximumTimeStep;
	}
	int minimumSubsteps(double logicalStep, double attemptedStep) {
		double interval = attemptedStep / 2;
		if (!Double.isNaN(maximumTimeStep)) interval = Math.min(interval, maximumTimeStep);
		double required = Math.ceil(logicalStep / interval);
		return required >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(2, (int) required);
	}
}
