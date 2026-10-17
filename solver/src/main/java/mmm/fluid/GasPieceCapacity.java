package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Assigns raw component capacity inside each selected gas piece's inverse-volume certificate. */
final class GasPieceCapacity {
	record Piece(int id, ExactRational normalizedVolume, ExactRational lower, ExactRational upper,
	             double cubicMeters, double roundingErrorBound) { }
	private record Interval(int id, ExactRational lower, ExactRational upper) { }
	private GasPieceCapacity() { }

	/** The component has checked its raw capacity and supplies the common PLIC target and pore scale. */
	static List<Piece> capture(int cell, int support, SignedDyadic available, ExactRational porosity,
	                           ExactRational wetTarget, GasComplementGeometry geometry) {
		if (geometry == null || cell < 0 || cell >= geometry.sizeX() * geometry.sizeY() * geometry.sizeZ()
				|| available == null || available.signum() < 0 || porosity == null || wetTarget == null)
			throw new IllegalArgumentException("Gas piece capacity input is invalid");
		try { return allocate(cell, support, available, porosity, wetTarget, geometry); }
		catch (IllegalArgumentException | ArithmeticException magnitude) {
			throw blocked("Gas piece capacity exceeds its exact representation budget", cell);
		}
	}
	private static List<Piece> allocate(int cell, int support, SignedDyadic available, ExactRational porosity,
	                                    ExactRational wetTarget, GasComplementGeometry geometry) {
		var intervals = new ArrayList<Interval>(8);
		double total = 0;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			var piece = geometry.piece(8 * cell + micro);
			if (piece == null) continue;
			var value = piece.normalizedVolume().multiply(porosity);
			intervals.add(new Interval(piece.id(), value, value)); total += value.toDouble();
		}
		if (available.signum() == 0) return List.of();
		if (!(total > 0)) throw blocked("Positive gas capacity has no dry geometry", cell);
		var capacities = new ArrayList<ExactRational>(intervals.size()); int pivot = 0;
		ExactRational assignedNormalized = ExactRational.ZERO, target = exact(available);
		for (int i = 0; i < intervals.size(); i++) {
			capacities.add(ExactRational.ZERO);
			if (intervals.get(i).lower().compareTo(intervals.get(pivot).lower()) > 0) pivot = i;
		}
		for (int i = 0; i < intervals.size(); i++) if (i != pivot) {
			var value = MicroBoxPlaneVolume.rational(available.toDouble() * (intervals.get(i).lower().toDouble() / total));
			capacities.set(i, value); assignedNormalized = assignedNormalized.add(value);
		}
		capacities.set(pivot, target.subtract(assignedNormalized));
		SignedDyadic scale = SignedDyadic.of(geometry.cellVolumeCubicMeters());
		ExactRational rationalScale = exact(scale);
		ExactRational[] physical = new ExactRational[capacities.size()];
		double[] stored = new double[capacities.size()]; SignedDyadic assigned = SignedDyadic.ZERO;
		for (int i = 0; i < stored.length; i++) {
			physical[i] = capacities.get(i).multiply(rationalScale);
			if (i != pivot) {
				stored[i] = positive(physical[i].toDouble(), cell);
				assigned = assigned.add(SignedDyadic.of(stored[i]));
			}
		}
		SignedDyadic remainder = available.multiply(scale).subtract(assigned);
		stored[pivot] = positive(remainder.toDouble(), cell);
		var result = new ArrayList<Piece>(stored.length);
		for (int i = 0; i < stored.length; i++) {
			ExactRational error = exact(SignedDyadic.of(stored[i])).subtract(physical[i]);
			if (error.signum() < 0) error = error.multiply(-1);
			double bound = error.isZero() ? 0 : Math.nextUp(error.toDouble());
			if (!Double.isFinite(bound)) throw blocked("Gas piece capacity error is not representable", cell);
			var interval = intervals.get(i);
			result.add(new Piece(interval.id(), capacities.get(i), interval.lower(), interval.upper(), stored[i], bound));
		}
		return List.copyOf(result);
	}

	private static GasGeometryOverlap.HalfSpace at(GasGeometryOverlap.HalfSpace plane, double offset) {
		return new GasGeometryOverlap.HalfSpace(plane.nx(), plane.ny(), plane.nz(), SignedDyadic.of(offset));
	}
	private static double positive(double value, int cell) {
		if (!(Double.isFinite(value) && value > 0)) throw blocked("Positive gas piece capacity is not representable", cell);
		return value;
	}
	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0 ? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	private static GasTopologyBlockedException blocked(String message, int cell) {
		return new GasTopologyBlockedException(message + " at cell " + cell);
	}
}
