package cc.ecisr.mmm.fluid;

/** Liquid reached the compressible part of its gas extension during this time interval. */
final class LiquidCharacteristicTimeResolutionRequired extends IllegalStateException {
	LiquidCharacteristicTimeResolutionRequired() {
		super("Liquid characteristic requires a shorter interval within incompressible interface support");
	}
}
