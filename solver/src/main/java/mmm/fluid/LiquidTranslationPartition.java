package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Exact inverse-image cuboids of a translated full unit cell, including edge and corner receivers. */
final class LiquidTranslationPartition {
	private static final SignedDyadic ONE = SignedDyadic.of(1), HALF = SignedDyadic.of(1, -1);
	private record AxisPart(int shift, SignedDyadic width, SignedDyadic centroid, SignedDyadic sourceCentroid) { }
	static final class Piece {
		private final AxisPart x, y, z;
		private final SignedDyadic volume;
		private Piece(AxisPart x, AxisPart y, AxisPart z) {
			this.x = x; this.y = y; this.z = z;
			volume = x.width.multiply(y.width).multiply(z.width);
		}
		int dx() { return x.shift; }
		int dy() { return y.shift; }
		int dz() { return z.shift; }
		SignedDyadic volume() { return volume; }
		/** Coordinates relative to the receiving cell centre; widths are in cell-length units. */
		SignedDyadic centroid(int axis) { return part(axis).centroid; }
		SignedDyadic width(int axis) { return part(axis).width; }
		SignedDyadic sourceCentroid(int axis) { return part(axis).sourceCentroid; }
		private AxisPart part(int axis) {
			return switch (axis) { case 0 -> x; case 1 -> y; case 2 -> z;
				default -> throw new IllegalArgumentException("Invalid translation axis"); };
		}
	}
	private LiquidTranslationPartition() { }

	static List<Piece> fullCell(SignedDyadic[] displacement) {
		if (displacement == null || displacement.length != 3)
			throw new IllegalArgumentException("Full-cell translation requires three exact displacements");
		for (var delta : displacement) if (delta == null || (delta.signum() < 0 ? delta.negate() : delta).compareTo(ONE) > 0)
			throw new IllegalArgumentException("Full-cell translation displacement exceeds one cell");
		var x = partition(displacement[0]); var y = partition(displacement[1]); var z = partition(displacement[2]);
		var result = new ArrayList<Piece>(x.size() * y.size() * z.size());
		for (var px : x) for (var py : y) for (var pz : z) result.add(new Piece(px, py, pz));
		return List.copyOf(result);
	}
	private static List<AxisPart> partition(SignedDyadic delta) {
		int sign = delta.signum(); var magnitude = sign < 0 ? delta.negate() : delta;
		var result = new ArrayList<AxisPart>(2);
		if (magnitude.compareTo(ONE) < 0) result.add(part(0, delta,
				sign > 0 ? HALF.negate() : HALF.negate().subtract(delta), sign > 0 ? HALF.subtract(delta) : HALF));
		if (sign != 0) result.add(part(sign, delta,
				sign > 0 ? HALF.subtract(delta) : HALF.negate(), sign > 0 ? HALF : HALF.negate().subtract(delta)));
		return List.copyOf(result);
	}
	private static AxisPart part(int shift, SignedDyadic delta, SignedDyadic lower, SignedDyadic upper) {
		var sourceCentroid = lower.add(upper).multiply(HALF);
		return new AxisPart(shift, upper.subtract(lower), sourceCentroid.add(delta).subtract(SignedDyadic.of(shift)), sourceCentroid);
	}
}
