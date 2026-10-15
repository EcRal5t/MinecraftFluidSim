package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Focused source/capacity constraints for geometric-volume storage. */
final class LiquidGeometricAllocationValidation {
	private LiquidGeometricAllocationValidation() { }
	static void run() {
		thirds(); competingFullReceivers(); subnormalPieceRejects(); dyadicIdentity();
	}
	public static void main(String[] args) { run(); System.out.println("Liquid geometric allocation validation passed"); }

	private static void thirds() {
		var pieces = List.of(piece(0, 0, 1, 3), piece(0, 1, 1, 3), piece(0, 2, 1, 3));
		var sources = amounts(1); var capacities = amounts(1, 1, 1);
		var allocation = LiquidGeometricAllocation.allocate(sources, capacities, pieces);
		audit(sources, capacities, pieces, allocation);
		check(allocation.equals(LiquidGeometricAllocation.allocate(sources, capacities, pieces)), "Allocation must be deterministic");
		boolean immutable = false;
		try { allocation.quantities().set(0, SignedDyadic.ZERO); } catch (UnsupportedOperationException expected) { immutable = true; }
		check(immutable, "Allocation quantities must be immutable");
	}
	private static void competingFullReceivers() {
		// The first source initially fills receiver 0. The second source requires a reverse residual path.
		var pieces = List.of(piece(0, 0, 1, 3), piece(0, 0, 2, 3), piece(0, 1, 1, 3), piece(0, 1, 2, 3),
				piece(1, 0, 1, 3), piece(1, 0, 2, 3));
		var sources = amounts(2, 1); var capacities = amounts(2, 1);
		var allocation = LiquidGeometricAllocation.allocate(sources, capacities, pieces);
		audit(sources, capacities, pieces, allocation);
		check(allocation.quantities().get(2).add(allocation.quantities().get(3)).equals(SignedDyadic.of(1)), "Full receiver must retain exact capacity");
	}
	private static void subnormalPieceRejects() {
		var small = new ExactRational(BigInteger.ONE, BigInteger.ONE.shiftLeft(150));
		var pieces = new ArrayList<LiquidGeometricAllocation.Piece>();
		pieces.add(new LiquidGeometricAllocation.Piece(0, 0, small));
		pieces.add(new LiquidGeometricAllocation.Piece(0, 1, ExactRational.ONE.subtract(small)));
		var source = amounts(1); var capacities = amounts(1, 1); var original = List.copyOf(pieces);
		boolean rejected = false;
		try { LiquidGeometricAllocation.allocate(source, capacities, pieces); } catch (IllegalArgumentException expected) { rejected = true; }
		check(rejected, "A positive piece below binary32 storage must reject");
		check(source[0].equals(SignedDyadic.of(1)) && capacities[0].equals(SignedDyadic.of(1)) && pieces.equals(original), "Rejection must preserve all inputs");
	}
	private static void dyadicIdentity() {
		var tail = SignedDyadic.of(1, -100); var first = SignedDyadic.of(1, -1).add(tail); var second = SignedDyadic.of(1, -1).subtract(tail);
		var pieces = List.of(new LiquidGeometricAllocation.Piece(0, 0, exact(first)), new LiquidGeometricAllocation.Piece(0, 1, exact(second)));
		var sources = amounts(1); var capacities = amounts(1, 1);
		var allocation = LiquidGeometricAllocation.allocate(sources, capacities, pieces);
		audit(sources, capacities, pieces, allocation);
		check(allocation.quantities().equals(List.of(first, second)), "Dyadic tails must remain exact");
		for (var delta : allocation.geometricDeltas()) check(delta.isZero(), "Dyadic geometry must have zero storage delta");
		for (var quantity : allocation.quantities()) {
			var sum = SignedDyadic.ZERO;
			for (float stored : LiquidCellQuadrature.volumes(quantity, 1)) sum = sum.add(SignedDyadic.of(stored));
			check(sum.equals(quantity), "Allocated dyadic must expand exactly into positive float chunks");
		}
	}
	private static void audit(SignedDyadic[] sources, SignedDyadic[] capacities, List<LiquidGeometricAllocation.Piece> pieces,
	                          LiquidGeometricAllocation.Allocation allocation) {
		var rows = new SignedDyadic[sources.length]; var columns = new SignedDyadic[capacities.length];
		java.util.Arrays.fill(rows, SignedDyadic.ZERO); java.util.Arrays.fill(columns, SignedDyadic.ZERO);
		for (int i = 0; i < pieces.size(); i++) {
			var quantity = allocation.quantities().get(i); var piece = pieces.get(i);
			check(quantity.signum() > 0, "Every positive geometric edge must retain positive inventory");
			rows[piece.source()] = rows[piece.source()].add(quantity); columns[piece.receiver()] = columns[piece.receiver()].add(quantity);
			check(exact(quantity).subtract(piece.volume()).equals(allocation.geometricDeltas().get(i)), "Geometric delta must describe the exact stored difference");
		}
		for (int i = 0; i < rows.length; i++) check(rows[i].equals(sources[i]), "Source inventory must be exact");
		for (int i = 0; i < columns.length; i++) check(columns[i].compareTo(capacities[i]) <= 0, "Receiver capacity must hold");
	}
	private static LiquidGeometricAllocation.Piece piece(int source, int receiver, long numerator, long denominator) {
		return new LiquidGeometricAllocation.Piece(source, receiver, new ExactRational(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator)));
	}
	private static SignedDyadic[] amounts(long... values) { var result = new SignedDyadic[values.length]; for (int i = 0; i < values.length; i++) result[i] = SignedDyadic.of(values[i]); return result; }
	private static ExactRational exact(SignedDyadic value) { return value.exponent() >= 0
			? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
			: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent())); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
