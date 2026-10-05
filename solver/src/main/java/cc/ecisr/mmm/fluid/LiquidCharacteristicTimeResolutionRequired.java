package cc.ecisr.mmm.fluid;

/** A geometric trajectory needs a shorter interval before its candidate can be accepted. */
final class LiquidCharacteristicTimeResolutionRequired extends IllegalStateException {
	LiquidCharacteristicTimeResolutionRequired() {
		this("Liquid characteristic requires a shorter interval within incompressible interface support");
	}
	LiquidCharacteristicTimeResolutionRequired(String message) { super(message); }
}
