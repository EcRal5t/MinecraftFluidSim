package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.units.SolverUnitScale;

/** Moves momentum with actual phase-transfer mass, including same-cell species redistribution. */
final class GranularReactionMomentum {
	private GranularReactionMomentum() { }

	record Plan(float[] x, float[] y, float[] z, float solidX, float solidY, float solidZ) { }

	static Plan plan(LiquidParticleStore particles, SolverUnitScale units,
	                 GranularPhaseTransferEngine.Plan transfer, float solidX, float solidY, float solidZ) {
		int[] markers = transfer.markerUpdates().stream().mapToInt(GranularPhaseTransferEngine.MarkerUpdate::marker).toArray();
		double[] masses = transfer.markerUpdates().stream().mapToDouble(GranularPhaseTransferEngine.MarkerUpdate::mass).toArray();
		try {
			var plan = CarrierMassMomentum.plan(particles, markers, masses,
					transfer.precipitation() ? 0 : units.solverMass(-transfer.signedSolidMass()), solidX, solidY, solidZ);
			return new Plan(plan.x(), plan.y(), plan.z(), plan.incomingX(), plan.incomingY(), plan.incomingZ());
		} catch (IllegalArgumentException unavailable) {
			throw new GranularPhaseTransferEngine.BlockedException(unavailable.getMessage());
		}
	}
}
