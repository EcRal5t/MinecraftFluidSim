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

	static List<Piece> capture(int cell, int support, SignedDyadic pore, SignedDyadic available,
	                           GasComplementGeometry geometry) {
		if (geometry == null || cell < 0 || cell >= geometry.sizeX() * geometry.sizeY() * geometry.sizeZ()
				|| pore == null || available == null || available.signum() < 0 || available.compareTo(pore) > 0)
			throw new IllegalArgumentException("Gas piece capacity input is invalid");
		int[] supports = CellRemapMicroTopology.components(geometry.collisionMask(cell));
		SignedDyadic[] capacities = CellRemapMicroTopology.capacities(geometry.collisionMask(cell), geometry.poreVolume(cell));
		int component = 0; while (component < supports.length && supports[component] != support) component++;
		if (component == supports.length || !capacities[component].equals(pore))
			throw new IllegalArgumentException("Gas piece capacity differs from its captured fixed component");
		try { return allocate(cell, support, pore, available, geometry); }
		catch (IllegalArgumentException | ArithmeticException magnitude) {
			throw blocked("Gas piece capacity exceeds its exact representation budget", cell);
		}
	}
	private static List<Piece> allocate(int cell, int support, SignedDyadic pore, SignedDyadic available,
	                                    GasComplementGeometry geometry) {
		var intervals = new ArrayList<Interval>(8);
		ExactRational porosity = exact(pore).multiply(8).divide(Integer.bitCount(support));
		ExactRational lower = ExactRational.ZERO, width = ExactRational.ZERO;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			var piece = geometry.piece(8 * cell + micro);
			if (piece == null) continue;
			ExactRational lo, hi;
			if (piece.dryPlane() == null) lo = hi = piece.normalizedVolume().multiply(porosity);
			else {
				var plane = piece.dryPlane(); var bracket = piece.offsetBracket();
				if (bracket == null) throw blocked("Gas piece has no inverse-volume certificate", cell);
				ExactRational wetTarget = PlicComponentGeometry.ratio(pore.subtract(available), pore)
						.multiply(Integer.bitCount(support)).divide(8);
				if (!wetTarget.equals(bracket.targetVolume())) throw blocked("Gas piece raw capacity differs from its PLIC target", cell);
				lo = (plane.threshold().equals(SignedDyadic.of(bracket.upper())) ? piece.normalizedVolume()
						: MicroBoxPlaneVolume.dryVolume(micro, at(plane, bracket.upper()))).multiply(porosity);
				hi = (plane.threshold().equals(SignedDyadic.of(bracket.lower())) ? piece.normalizedVolume()
						: MicroBoxPlaneVolume.dryVolume(micro, at(plane, bracket.lower()))).multiply(porosity);
			}
			if (lo.signum() < 0 || hi.compareTo(lo) < 0) throw blocked("Gas piece capacity interval is invalid", cell);
			intervals.add(new Interval(piece.id(), lo, hi));
			lower = lower.add(lo); width = width.add(hi.subtract(lo));
		}
		ExactRational target = exact(available), remaining = target.subtract(lower);
		if (remaining.signum() < 0 || remaining.compareTo(width) > 0)
			throw blocked("Raw gas capacity cannot fit its selected positive pieces", cell);
		if (intervals.isEmpty()) return List.of();
		ExactRational fraction = width.isZero() ? ExactRational.ZERO
				: remaining.multiply(new ExactRational(width.denominator(), width.numerator()));
		var capacities = new ArrayList<ExactRational>(intervals.size()); int pivot = 0;
		ExactRational sum = ExactRational.ZERO;
		for (int i = 0; i < intervals.size(); i++) {
			var interval = intervals.get(i);
			ExactRational capacity = interval.lower().add(interval.upper().subtract(interval.lower()).multiply(fraction));
			if (capacity.signum() <= 0) throw blocked("Positive gas piece has no raw capacity", cell);
			capacities.add(capacity); sum = sum.add(capacity);
			if (capacity.compareTo(capacities.get(pivot)) > 0) pivot = i;
		}
		if (!sum.equals(target)) throw new IllegalStateException("Gas piece capacity allocation does not close");
		SignedDyadic scale = SignedDyadic.of(geometry.cellVolumeCubicMeters());
		ExactRational rationalScale = exact(scale);
		double[] stored = new double[capacities.size()]; SignedDyadic assigned = SignedDyadic.ZERO;
		for (int i = 0; i < stored.length; i++) if (i != pivot) {
			stored[i] = positive(capacities.get(i).multiply(rationalScale).toDouble(), cell);
			assigned = assigned.add(SignedDyadic.of(stored[i]));
		}
		SignedDyadic remainder = available.multiply(scale).subtract(assigned);
		stored[pivot] = positive(remainder.toDouble(), cell);
		var result = new ArrayList<Piece>(stored.length);
		for (int i = 0; i < stored.length; i++) {
			ExactRational error = exact(SignedDyadic.of(stored[i])).subtract(capacities.get(i).multiply(rationalScale));
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
