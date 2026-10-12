package mmm.fluid;

import mmm.granular.GranularPhaseTransferEngine;
import mmm.granular.GranularCouplingFields;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

/** Moves momentum with actual phase-transfer mass, including same-cell species redistribution. */
final class GranularReactionMomentum {
	private GranularReactionMomentum() { }

	record Plan(float[] x, float[] y, float[] z, float solidX, float solidY, float solidZ) { }

	/** Includes receiving markers as signed mass changes, before any marker is mutated. */
	static GranularCouplingFields.Position precipitationPosition(LiquidParticleStore particles,
			SolverUnitScale units, GranularPhaseTransferEngine.Plan transfer) {
		SignedDyadic x = SignedDyadic.ZERO, y = SignedDyadic.ZERO, z = SignedDyadic.ZERO;
		for (var update : transfer.markerUpdates()) {
			int marker = update.marker();
			SignedDyadic removed = SignedDyadic.of(units.kilograms(particles.mass(marker)))
					.subtract(SignedDyadic.of(units.kilograms(update.mass())));
			x = x.add(removed.multiply(SignedDyadic.of(particles.positionX(marker))));
			y = y.add(removed.multiply(SignedDyadic.of(particles.positionY(marker))));
			z = z.add(removed.multiply(SignedDyadic.of(particles.positionZ(marker))));
		}
		float px = (float) (x.toDouble() / transfer.signedSolidMass());
		float py = (float) (y.toDouble() / transfer.signedSolidMass());
		float pz = (float) (z.toDouble() / transfer.signedSolidMass());
		return Float.isFinite(px) && Float.isFinite(py) && Float.isFinite(pz)
				? new GranularCouplingFields.Position(px, py, pz) : null;
	}

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
