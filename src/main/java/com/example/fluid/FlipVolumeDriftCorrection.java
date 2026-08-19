package com.example.fluid;

/**
 * Builds a pressure-only rest-volume constraint from the reconstructed P2G field.
 * This numerical target never edits or settles the exact marker-volume ledger.
 */
final class FlipVolumeDriftCorrection {
	private static final float RELAXATION_PER_STEP = 0.05F;
	private static final float MAXIMUM_CORRECTION_PER_STEP = 0.10F;

	private FlipVolumeDriftCorrection() { }

	static float targetDivergence(
			float reconstructedVolume, float exactCellVolume, float openVolume,
			boolean hasAirNeighbor, float dt
	) {
		if (openVolume <= 0.0F) return 0.0F;
		float relativeError = reconstructedVolume / openVolume - 1.0F;
		// A free-surface deficit is legitimate. An interior deficit indicates marker drift,
		// except when the exact containing-cell parcels already fill the open capacity.
		if (relativeError < 0.0F
				&& (hasAirNeighbor || exactCellVolume >= openVolume - 1.0E-6F)) return 0.0F;
		float correctionFraction = clamp(
				relativeError * RELAXATION_PER_STEP,
				-MAXIMUM_CORRECTION_PER_STEP, MAXIMUM_CORRECTION_PER_STEP
		);
		return correctionFraction / dt;
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
