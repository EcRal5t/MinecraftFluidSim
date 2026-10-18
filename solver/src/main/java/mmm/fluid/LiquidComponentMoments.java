package mmm.fluid;

import mmm.units.SignedDyadic;

/** Exact liquid volume and floating point physical-position moments in one open collision component. */
record LiquidComponentMoments(
		int cell, int supportMask, int markerCount, SignedDyadic volume,
		double firstMomentX, double firstMomentY, double firstMomentZ) {
	LiquidComponentMoments {
		if (cell < 0 || supportMask <= 0 || supportMask > 255 || markerCount < 0 || volume == null
				|| !Double.isFinite(firstMomentX) || !Double.isFinite(firstMomentY) || !Double.isFinite(firstMomentZ)
				|| volume.signum() < 0 || (markerCount == 0) != (volume.signum() == 0)
				|| volume.compareTo(SignedDyadic.of(Integer.bitCount(supportMask), -3)) > 0
				|| volume.signum() == 0 && (firstMomentX != 0 || firstMomentY != 0 || firstMomentZ != 0))
			throw new IllegalArgumentException("Invalid liquid component moments");
	}

	/** Volume is in cell-volume units; its first moments multiply that volume by physical metres. */
	double firstMoment(int axis) {
		return switch (axis) { case 0 -> firstMomentX; case 1 -> firstMomentY; case 2 -> firstMomentZ;
			default -> throw new IllegalArgumentException("Invalid liquid component moment axis"); };
	}
}
