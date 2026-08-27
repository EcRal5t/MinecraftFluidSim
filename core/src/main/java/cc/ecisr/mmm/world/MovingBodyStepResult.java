package cc.ecisr.mmm.world;

/** Complete loader-neutral settlement for one moving body in a fluid candidate. */
public record MovingBodyStepResult(
		long transientId,
		MovingBodySurfaceSnapshot.CouplingMode couplingMode,
		double initialVelocityXMetresPerSecond,
		double initialVelocityYMetresPerSecond,
		double initialVelocityZMetresPerSecond,
		double finalVelocityXMetresPerSecond,
		double finalVelocityYMetresPerSecond,
		double finalVelocityZMetresPerSecond,
		double pressureImpulseXKilogramMetresPerSecond,
		double pressureImpulseYKilogramMetresPerSecond,
		double pressureImpulseZKilogramMetresPerSecond,
		double viscousImpulseXKilogramMetresPerSecond,
		double viscousImpulseYKilogramMetresPerSecond,
		double viscousImpulseZKilogramMetresPerSecond,
		double displacementWorkJoules,
		double unacceptedBoundaryImpulseXKilogramMetresPerSecond,
		double unacceptedBoundaryImpulseYKilogramMetresPerSecond,
		double unacceptedBoundaryImpulseZKilogramMetresPerSecond
) {
	public MovingBodyStepResult {
		if (transientId < 0L || couplingMode == null
				|| !finite(initialVelocityXMetresPerSecond, initialVelocityYMetresPerSecond,
				initialVelocityZMetresPerSecond, finalVelocityXMetresPerSecond,
				finalVelocityYMetresPerSecond, finalVelocityZMetresPerSecond,
				pressureImpulseXKilogramMetresPerSecond, pressureImpulseYKilogramMetresPerSecond,
				pressureImpulseZKilogramMetresPerSecond, viscousImpulseXKilogramMetresPerSecond,
				viscousImpulseYKilogramMetresPerSecond, viscousImpulseZKilogramMetresPerSecond,
				displacementWorkJoules, unacceptedBoundaryImpulseXKilogramMetresPerSecond,
				unacceptedBoundaryImpulseYKilogramMetresPerSecond,
				unacceptedBoundaryImpulseZKilogramMetresPerSecond)) {
			throw new IllegalArgumentException("Moving-body settlement is invalid");
		}
		if (couplingMode == MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL
				&& (initialVelocityXMetresPerSecond != finalVelocityXMetresPerSecond
				|| initialVelocityYMetresPerSecond != finalVelocityYMetresPerSecond
				|| initialVelocityZMetresPerSecond != finalVelocityZMetresPerSecond)) {
			throw new IllegalArgumentException("Kinematic moving-body velocity must remain prescribed");
		}
	}

	private static boolean finite(double... values) {
		for (double value : values) if (!Double.isFinite(value)) return false;
		return true;
	}
}
