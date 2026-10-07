package cc.ecisr.mmm.fluid;

/** The finite coordinate representation cannot certify the resolved geometry or its mechanical rates. */
final class LiquidCoordinateResolutionRequired extends IllegalStateException {
	LiquidCoordinateResolutionRequired(String message) { super(message); }
}
