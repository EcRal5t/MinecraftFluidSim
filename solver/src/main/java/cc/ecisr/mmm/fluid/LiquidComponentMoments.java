package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;

/** Exact liquid inventory and physical-position moments in one open collision component. */
record LiquidComponentMoments(
		int cell, int supportMask, int markerCount, SignedDyadic volume,
		SignedDyadic firstMomentX, SignedDyadic firstMomentY, SignedDyadic firstMomentZ) {
	LiquidComponentMoments {
		if (cell < 0 || supportMask <= 0 || supportMask > 255 || markerCount < 0 || volume == null
				|| firstMomentX == null || firstMomentY == null || firstMomentZ == null
				|| volume.signum() < 0 || (markerCount == 0) != (volume.signum() == 0)
				|| volume.compareTo(SignedDyadic.of(Integer.bitCount(supportMask), -3)) > 0
				|| volume.signum() == 0 && (firstMomentX.signum() != 0 || firstMomentY.signum() != 0 || firstMomentZ.signum() != 0))
			throw new IllegalArgumentException("Invalid liquid component moments");
	}

	/** Volume is in cell-volume units; its first moments multiply that volume by physical metres. */
	SignedDyadic firstMoment(int axis) {
		return switch (axis) { case 0 -> firstMomentX; case 1 -> firstMomentY; case 2 -> firstMomentZ;
			default -> throw new IllegalArgumentException("Invalid liquid component moment axis"); };
	}
}
