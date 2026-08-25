package cc.ecisr.mmm.units;

/** Single conversion boundary between normalized solver volume/mass and SI cubic metres/kilograms. */
public record SolverUnitScale(double cubicMetersPerNormalizedVolume) {
	public SolverUnitScale {
		if (!(Double.isFinite(cubicMetersPerNormalizedVolume) && cubicMetersPerNormalizedVolume > 0.0)) {
			throw new IllegalArgumentException("Solver unit scale must be finite and positive");
		}
	}

	public static SolverUnitScale forCellSize(float cellSizeMeters) {
		if (!(Float.isFinite(cellSizeMeters) && cellSizeMeters > 0.0F)) {
			throw new IllegalArgumentException("Cell size must be finite and positive");
		}
		double h = cellSizeMeters;
		return new SolverUnitScale(h * h * h);
	}

	public double kilograms(double solverMass) {
		double result = solverMass * cubicMetersPerNormalizedVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Physical mass is invalid");
		}
		return result;
	}

	public double signedKilograms(double solverMass) {
		double result = solverMass * cubicMetersPerNormalizedVolume;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Signed physical mass is invalid");
		return result;
	}

	public double solverMass(double kilograms) {
		double result = kilograms / cubicMetersPerNormalizedVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Solver mass is invalid");
		}
		return result;
	}

	public double signedSolverMass(double kilograms) {
		double result = kilograms / cubicMetersPerNormalizedVolume;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Signed solver mass is invalid");
		return result;
	}

	public double cubicMeters(double normalizedVolume) {
		double result = normalizedVolume * cubicMetersPerNormalizedVolume;
		if (!(Double.isFinite(result) && result >= 0.0)) {
			throw new IllegalArgumentException("Physical volume is invalid");
		}
		return result;
	}
}
