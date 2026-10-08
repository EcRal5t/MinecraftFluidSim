package cc.ecisr.mmm.fluid;

/** A geometric trajectory needs a shorter interval before its candidate can be accepted. */
final class LiquidCharacteristicTimeResolutionRequired extends IllegalStateException {
	record NegativeWinding(int cell, LiquidExactPolygon.Vertex point) { }
	final NegativeWinding negativeWinding;
	final java.util.List<LiquidExactPolygon.Vertex> collapsedSurface;
	private final double maximumTimeStep;
	LiquidCharacteristicTimeResolutionRequired() {
		this("Liquid characteristic requires a shorter interval within incompressible interface support");
	}
	LiquidCharacteristicTimeResolutionRequired(String message) { this(message, (NegativeWinding) null); }
	LiquidCharacteristicTimeResolutionRequired(String message, NegativeWinding negativeWinding) {
		super(message); maximumTimeStep = Double.NaN; this.negativeWinding = negativeWinding; collapsedSurface = null;
	}
	LiquidCharacteristicTimeResolutionRequired(String message, java.util.List<LiquidExactPolygon.Vertex> collapsedSurface) {
		super(message); maximumTimeStep = Double.NaN; negativeWinding = null;
		if (collapsedSurface.size() != 3) throw new IllegalArgumentException("Incomplete collapsed surface witness");
		this.collapsedSurface = java.util.List.copyOf(collapsedSurface);
	}
	LiquidCharacteristicTimeResolutionRequired(String message, double maximumTimeStep) {
		super(message);
		if (!(maximumTimeStep > 0) || !Double.isFinite(maximumTimeStep)) throw new IllegalArgumentException("Invalid certified geometric time interval");
		this.maximumTimeStep = maximumTimeStep; negativeWinding = null; collapsedSurface = null;
	}
	int minimumSubsteps(double logicalStep, double attemptedStep) {
		double interval = attemptedStep / 2;
		if (!Double.isNaN(maximumTimeStep)) interval = Math.min(interval, maximumTimeStep);
		double required = Math.ceil(logicalStep / interval);
		return required >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(2, (int) required);
	}
}
