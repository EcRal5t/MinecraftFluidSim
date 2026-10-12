package mmm.fluid;

import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.function.IntToLongFunction;
import java.util.function.IntUnaryOperator;

/** Exact liquid inventory and remaining solid capacity for one phase reaction candidate. */
record JointPhaseCapacity(SignedDyadic[] liquid, long[] solid) {

	static SignedDyadic[] collectLiquid(int cellCount, LiquidParticleStore particles, IntUnaryOperator particleCell) {
		SignedDyadic[] liquid = new SignedDyadic[cellCount];
		Arrays.fill(liquid, SignedDyadic.ZERO);
		for (int marker = 0; marker < particles.count(); marker++) {
			int cell = particleCell.applyAsInt(marker);
			liquid[cell] = liquid[cell].add(SignedDyadic.of(particles.volume(marker)));
		}
		return liquid;
	}

	static JointPhaseCapacity plan(SignedDyadic[] currentLiquid,
			LiquidParticleStore particles, IntUnaryOperator particleCell,
			IntToLongFunction geometricOpenSvq, GranularPhaseTransferEngine.Plan reaction) {
		SignedDyadic[] liquid = currentLiquid.clone();
		for (var update : reaction.markerUpdates()) {
			int cell = particleCell.applyAsInt(update.marker());
			liquid[cell] = liquid[cell].add(SignedDyadic.of(update.cellVolume()))
					.subtract(SignedDyadic.of(particles.volume(update.marker())));
		}
		long[] solid = new long[liquid.length];
		for (int cell = 0; cell < liquid.length; cell++) {
			solid[cell] = geometricOpenSvq.applyAsLong(cell) - liquidSvqCeil(liquid[cell]);
		}
		return new JointPhaseCapacity(liquid, solid);
	}

	/** Conservative occupancy bound preserves the liquid inventory's sub-SVQ dyadic tail. */
	static long liquidSvqCeil(SignedDyadic liquid) {
		int exponent = liquid.exponent() + 24;
		BigInteger occupied = liquid.significand();
		if (exponent >= 0) occupied = occupied.shiftLeft(exponent);
		else {
			BigInteger divisor = BigInteger.ONE.shiftLeft(-exponent);
			BigInteger[] division = occupied.divideAndRemainder(divisor);
			occupied = division[0].add(division[1].signum() == 0 ? BigInteger.ZERO : BigInteger.ONE);
		}
		return occupied.longValueExact();
	}

	boolean fitsReplacement(GranularCouplingFields coupling, GranularCouplingFields.Position previous,
			long previousSvq, GranularCouplingFields.Position next, long nextSvq) {
		long[] occupied = coupling.copySolidSvq();
		if (previous != null) coupling.removePlannedParcel(occupied, previous, previousSvq);
		return (nextSvq == 0 || coupling.tryPlanParcel(occupied, next, nextSvq, cell -> solid[cell]))
				&& fits(occupied);
	}

	boolean fitsDepositRemoval(GranularCouplingFields coupling, int cell, long removedSvq) {
		long[] occupied = coupling.copySolidSvq();
		occupied[cell] -= removedSvq;
		return fits(occupied);
	}

	private boolean fits(long[] occupied) {
		for (int cell = 0; cell < occupied.length; cell++) if (occupied[cell] > solid[cell]) return false;
		return true;
	}
}
