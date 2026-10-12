package mmm.fluid;

/** Mixes the momentum carried by actual mass transfers within one cell and phase. */
final class CarrierMassMomentum {
	private CarrierMassMomentum() { }

	record Plan(int[] markers, float[] x, float[] y, float[] z, float incomingX, float incomingY, float incomingZ) {
		void apply(LiquidParticleStore particles) {
			for (int index = 0; index < markers.length; index++) {
				particles.setVelocityX(markers[index], x[index]);
				particles.setVelocityY(markers[index], y[index]);
				particles.setVelocityZ(markers[index], z[index]);
			}
		}
	}

	static Plan plan(LiquidParticleStore particles, int[] markers, double[] masses,
	                 double externalDonorMass, float donorX, float donorY, float donorZ) {
		double donorMass = externalDonorMass, vx = donorX, vy = donorY, vz = donorZ;
		for (int index = 0; index < markers.length; index++) {
			int marker = markers[index];
			double removed = particles.mass(marker) - masses[index];
			if (removed <= 0) continue;
			double total = donorMass + removed;
			if (!Double.isFinite(total)) throw new IllegalArgumentException("Transferred mass exceeds momentum representation");
			double fraction = removed / total;
			vx += fraction * (particles.velocityX(marker) - vx);
			vy += fraction * (particles.velocityY(marker) - vy);
			vz += fraction * (particles.velocityZ(marker) - vz);
			donorMass = total;
		}
		float[] x = new float[markers.length], y = new float[markers.length], z = new float[markers.length];
		for (int index = 0; index < markers.length; index++) {
			int marker = markers[index];
			double received = Math.max(0, masses[index] - particles.mass(marker));
			if (received > 0 && donorMass == 0) throw new IllegalArgumentException("Mass transfer has no momentum donor");
			double fraction = received / masses[index];
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
		if (!Float.isFinite(value)) throw new IllegalArgumentException("Transferred momentum exceeds carrier velocity representation");
		return value;
	}
}
