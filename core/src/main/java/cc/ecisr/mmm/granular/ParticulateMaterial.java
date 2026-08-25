package cc.ecisr.mmm.granular;

/** Immutable particulate material definition; runtime handles are assigned by GranularRegistry. */
public record ParticulateMaterial(
		String id,
		double referenceDensity,
		double primaryGrainDiameterMeters,
		int restPorosityQ,
		double reposeAngleRadians,
		double staticFriction,
		double dynamicFriction,
		double restitution,
		double criticalShields,
		long erosionRateSvqPerSecond,
		long maximumActiveParcelSvq,
		int baseArgb,
		String displayTextureId,
		boolean reactive
) {
	private static final double MAXIMUM_GRAIN_DIAMETER_METERS = 0.05;

	public ParticulateMaterial {
		GranularIds.requireId(id, "particulate material id");
		GranularIds.requireId(displayTextureId, "particulate texture id");
		if (!(Double.isFinite(referenceDensity) && referenceDensity > 0.0)) {
			throw new IllegalArgumentException("Particulate reference density must be finite and positive");
		}
		if (!(Double.isFinite(primaryGrainDiameterMeters) && primaryGrainDiameterMeters > 0.0
				&& primaryGrainDiameterMeters <= MAXIMUM_GRAIN_DIAMETER_METERS)) {
			throw new IllegalArgumentException("Primary grain diameter is outside the supported range");
		}
		SolidVolume.requirePorosity(restPorosityQ);
		if (!(Double.isFinite(reposeAngleRadians) && reposeAngleRadians > 0.0
				&& reposeAngleRadians < Math.PI / 2.0)) {
			throw new IllegalArgumentException("Repose angle must be within (0, PI/2)");
		}
		if (!(Double.isFinite(staticFriction) && Double.isFinite(dynamicFriction)
				&& staticFriction >= dynamicFriction && dynamicFriction >= 0.0)) {
			throw new IllegalArgumentException("Particulate friction coefficients are invalid");
		}
		if (!(Double.isFinite(restitution) && restitution >= 0.0 && restitution <= 1.0)) {
			throw new IllegalArgumentException("Particulate restitution must be within [0, 1]");
		}
		if (!(Double.isFinite(criticalShields) && criticalShields >= 0.0)) {
			throw new IllegalArgumentException("Critical Shields value must be finite and non-negative");
		}
		if (erosionRateSvqPerSecond <= 0L) throw new IllegalArgumentException("Erosion rate must be positive");
		if (maximumActiveParcelSvq <= 0L || maximumActiveParcelSvq > GranularLimits.MAX_PARCEL_VOLUME_SVQ) {
			throw new IllegalArgumentException("Maximum active parcel volume is invalid");
		}
	}
}
