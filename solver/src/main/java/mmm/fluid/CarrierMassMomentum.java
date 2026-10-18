package mmm.fluid;

/** Mixes the momentum carried by actual mass transfers within one cell and phase. */
final class CarrierMassMomentum {
	private CarrierMassMomentum() { }
	static final class RepresentationException extends IllegalArgumentException {
		RepresentationException(String message) { super(message); }
	}

	record Plan(int[] markers, float[] x, float[] y, float[] z, float incomingX, float incomingY, float incomingZ) {
		void apply(LiquidParticleStore particles) {
			for (int index = 0; index < markers.length; index++) {
				particles.setVelocityX(markers[index], x[index]);
				particles.setVelocityY(markers[index], y[index]);
				particles.setVelocityZ(markers[index], z[index]);
			}
		}
	}

	static Plan plan(LiquidParticleStore particles, int[] markers, long[] masses,
	                 long externalDonorMass, float donorX, float donorY, float donorZ) {
		if (particles == null || markers == null || masses == null || markers.length != masses.length
				|| !Double.isFinite(externalDonorMass) || externalDonorMass < 0
				|| !Float.isFinite(donorX) || !Float.isFinite(donorY) || !Float.isFinite(donorZ))
			throw new IllegalArgumentException("Mass momentum transfer input is invalid");
		long donorMass = externalDonorMass; double vx = donorX, vy = donorY, vz = donorZ;
		for (int index = 0; index < markers.length; index++) {
			int marker = markers[index];
			if (!Double.isFinite(masses[index]) || masses[index] < 0)
				throw new IllegalArgumentException("Transferred carrier mass is invalid");
			long removed = particles.massUnits(marker) - masses[index];
			if (removed <= 0) continue;
			long total = Math.addExact(donorMass, removed);
			if (!Double.isFinite(total)) throw new RepresentationException("Transferred mass exceeds momentum representation");
			double fraction = (double) removed / total;
			vx += fraction * (particles.velocityX(marker) - vx);
			vy += fraction * (particles.velocityY(marker) - vy);
			vz += fraction * (particles.velocityZ(marker) - vz);
			donorMass = total;
		}
		float[] x = new float[markers.length], y = new float[markers.length], z = new float[markers.length];
		for (int index = 0; index < markers.length; index++) {
			int marker = markers[index];
			long received = Math.max(0, masses[index] - particles.massUnits(marker));
			if (received > 0 && donorMass == 0) throw new IllegalArgumentException("Mass transfer has no momentum donor");
			double fraction = received == 0 ? 0 : (double) received / masses[index];
			x[index] = mix(particles.velocityX(marker), fraction, vx);
			y[index] = mix(particles.velocityY(marker), fraction, vy);
			z[index] = mix(particles.velocityZ(marker), fraction, vz);
		}
		return new Plan(markers.clone(), x, y, z, finite(vx), finite(vy), finite(vz));
	}

	private static float mix(float previous, double fraction, double incoming) {
		return fraction == 0 ? previous : finite(previous + fraction * (incoming - previous));
	}

	private static float finite(double velocity) {
		float value = (float) velocity;
		if (!Float.isFinite(value)) throw new RepresentationException("Transferred momentum exceeds carrier velocity representation");
		return value;
	}
}
