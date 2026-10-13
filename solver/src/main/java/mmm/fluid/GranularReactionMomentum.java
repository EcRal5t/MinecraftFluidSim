package mmm.fluid;

import mmm.granular.GranularPhaseTransferEngine;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularParcelStore;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

/** Moves momentum with actual phase-transfer mass, including same-cell species redistribution. */
final class GranularReactionMomentum {
	private GranularReactionMomentum() { }

	record Plan(float[] x, float[] y, float[] z, float solidX, float solidY, float solidZ) { }
	record MassMoment(SignedDyadic x, SignedDyadic y, SignedDyadic z) {
		GranularCouplingFields.Position position(double mass) { return position(x, y, z, mass); }
		GranularCouplingFields.Position mergedPosition(GranularParcelStore.Parcel existing, double combinedMass) {
			var mass = SignedDyadic.of(existing.mass());
			return position(x.add(mass.multiply(SignedDyadic.of(existing.x()))),
					y.add(mass.multiply(SignedDyadic.of(existing.y()))),
					z.add(mass.multiply(SignedDyadic.of(existing.z()))), combinedMass);
		}
		private static GranularCouplingFields.Position position(SignedDyadic x, SignedDyadic y, SignedDyadic z, double mass) {
			var storedMass = SignedDyadic.of(mass);
			float px = coordinate(x, storedMass), py = coordinate(y, storedMass), pz = coordinate(z, storedMass);
			return Float.isFinite(px) && Float.isFinite(py) && Float.isFinite(pz)
					? new GranularCouplingFields.Position(px, py, pz) : null;
		}
	}

	/** Includes receiving markers as signed mass changes, before any marker is mutated. */
	static MassMoment precipitationMoment(LiquidParticleStore particles,
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
		return new MassMoment(x, y, z);
	}

	/** Adjacent-float exact residuals remove any double-rounding ambiguity in the final stored coordinate. */
	private static float coordinate(SignedDyadic moment, SignedDyadic mass) {
		int shift = Math.subtractExact(moment.exponent(), mass.exponent());
		var ratio = new ExactRational(shift >= 0 ? moment.significand().shiftLeft(shift) : moment.significand(),
				shift < 0 ? mass.significand().shiftLeft(-shift) : mass.significand());
		float rounded = (float) ratio.toDouble();
		if (!Float.isFinite(rounded)) return rounded;
		SignedDyadic error = mass.multiply(SignedDyadic.of(rounded)).subtract(moment);
		if (error.signum() < 0) error = error.negate();
		for (float candidate : new float[]{Math.nextDown(rounded), Math.nextUp(rounded)}) {
			if (!Float.isFinite(candidate)) continue;
			var otherError = mass.multiply(SignedDyadic.of(candidate)).subtract(moment);
			if (otherError.signum() < 0) otherError = otherError.negate();
			int comparison = otherError.compareTo(error);
			if (comparison < 0 || comparison == 0 && (Float.floatToRawIntBits(candidate) & 1) == 0) {
				rounded = candidate; error = otherError;
			}
		}
		return rounded;
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
