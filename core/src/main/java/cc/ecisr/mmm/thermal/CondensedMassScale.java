package cc.ecisr.mmm.thermal;

/**
 * The single boundary between solver mass weights and SI kilograms.
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

	/** Converts a signed mass correction such as a chemical residual without treating it as inventory. */
	public double signedKilograms(double solverMassWeight) {
		double result = solverMassWeight * cubicMetersPerNormalizedCellVolume;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Signed condensed mass is invalid");
		return result;
	}

	public double solverMassWeight(double kilograms) {
		double result = kilograms / cubicMetersPerNormalizedCellVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Condensed solver mass weight is invalid");
		}
		return result;
	}

	/** Converts a signed physical mass correction into the normalized condensed-mass basis. */
	public double signedSolverMassWeight(double kilograms) {
		double result = kilograms / cubicMetersPerNormalizedCellVolume;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Signed condensed mass weight is invalid");
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
