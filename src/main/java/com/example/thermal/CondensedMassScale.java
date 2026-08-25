package com.example.thermal;

/**
 * The single M9 boundary between frozen M3/M8 solver mass weights and SI kilograms.
 * Gas inventories are already kilograms and must never pass through this scale.
 */
public record CondensedMassScale(double cubicMetersPerNormalizedCellVolume) {
	public CondensedMassScale {
		if (!(Double.isFinite(cubicMetersPerNormalizedCellVolume)
				&& cubicMetersPerNormalizedCellVolume > 0.0)) {
			throw new IllegalArgumentException("Condensed mass scale must be finite and positive");
		}
	}

	public static CondensedMassScale forCellSize(float cellSizeMeters) {
		if (!(Float.isFinite(cellSizeMeters) && cellSizeMeters > 0.0F)) {
			throw new IllegalArgumentException("Cell size must be finite and positive");
		}
		double h = cellSizeMeters;
		return new CondensedMassScale(h * h * h);
	}

	public double kilograms(double solverMassWeight) {
		double result = solverMassWeight * cubicMetersPerNormalizedCellVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Condensed physical mass is invalid");
		}
		return result;
	}

	public double solverMassWeight(double kilograms) {
		double result = kilograms / cubicMetersPerNormalizedCellVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Condensed solver mass weight is invalid");
		}
		return result;
	}

	public double cubicMeters(double normalizedCellVolume) {
		double result = normalizedCellVolume * cubicMetersPerNormalizedCellVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Condensed physical volume is invalid");
		}
		return result;
	}
}
