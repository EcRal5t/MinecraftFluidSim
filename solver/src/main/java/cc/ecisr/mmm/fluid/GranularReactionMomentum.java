package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.units.SolverUnitScale;

/** Moves momentum with actual phase-transfer mass, including same-cell species redistribution. */
final class GranularReactionMomentum {
	private GranularReactionMomentum() { }

	record Plan(float[] x, float[] y, float[] z, float solidX, float solidY, float solidZ) { }

	static Plan plan(LiquidParticleStore particles, SolverUnitScale units,
	                 GranularPhaseTransferEngine.Plan transfer, float solidX, float solidY, float solidZ) {
		double donorMass = 0, momentumX = 0, momentumY = 0, momentumZ = 0;
		if (!transfer.precipitation()) {
			donorMass = units.solverMass(-transfer.signedSolidMass());
			momentumX = donorMass * solidX;
			momentumY = donorMass * solidY;
			momentumZ = donorMass * solidZ;
		}
		for (var update : transfer.markerUpdates()) {
			int marker = update.marker();
			double removed = particles.mass(marker) - update.mass();
			if (removed <= 0) continue;
			donorMass += removed;
			momentumX += removed * particles.velocityX(marker);
			momentumY += removed * particles.velocityY(marker);
			momentumZ += removed * particles.velocityZ(marker);
		}
		if (!(Double.isFinite(donorMass) && donorMass > 0)) {
			throw new GranularPhaseTransferEngine.BlockedException("Phase transfer has no representable mass donor");
		}
		double vx = momentumX / donorMass, vy = momentumY / donorMass, vz = momentumZ / donorMass;
		int count = transfer.markerUpdates().size();
		float[] x = new float[count], y = new float[count], z = new float[count];
		for (int index = 0; index < count; index++) {
			var update = transfer.markerUpdates().get(index);
			int marker = update.marker();
			double received = Math.max(0, update.mass() - particles.mass(marker));
			double retained = update.mass() - received;
			x[index] = velocity(retained, particles.velocityX(marker), received, vx, update.mass());
			y[index] = velocity(retained, particles.velocityY(marker), received, vy, update.mass());
			z[index] = velocity(retained, particles.velocityZ(marker), received, vz, update.mass());
		}
		return new Plan(x, y, z, finite(vx), finite(vy), finite(vz));
	}

	private static float velocity(double retained, float original, double received, double incoming, double mass) {
		return received == 0 ? original : finite((retained * original + received * incoming) / mass);
	}

	private static float finite(double velocity) {
		float result = (float) velocity;
		if (!Float.isFinite(result)) throw new GranularPhaseTransferEngine.BlockedException(
				"Phase-transfer momentum cannot be represented by carrier velocity");
		return result;
	}
}
