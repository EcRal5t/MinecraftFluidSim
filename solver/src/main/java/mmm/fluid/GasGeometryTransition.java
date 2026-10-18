package mmm.fluid;


import java.util.List;

/** Shared old/new microbox intersections for inventory transfer and surviving phase contacts. */
final class GasGeometryTransition {
	private final GasComplementGeometry before;
	private final GasComplementGeometry after;
	private final double[] intersections;
	private final byte[] positive;

	GasGeometryTransition(GasRuntimeGeometry before, GasRuntimeGeometry after) {
		this.before = before.geometry(); this.after = after.geometry();
		int pieces = 8 * this.before.sizeX() * this.before.sizeY() * this.before.sizeZ();
		intersections = new double[pieces]; java.util.Arrays.fill(intersections, Double.NaN); positive = new byte[pieces];
	}

	boolean positive(int piece) {
		if (positive[piece] != 0) return positive[piece] == 2;
		var first = before.piece(piece); var second = after.piece(piece);
		boolean present = first != null && second != null;
		if (present && first.dryPlane() != null && second.dryPlane() != null
				&& !first.dryPlane().equals(second.dryPlane()))
			present = GasGeometryOverlap.hasPositiveOverlap(first.micro(), first.dryPlane(), second.dryPlane());
		positive[piece] = (byte) (present ? 2 : 1);
		return present;
	}

	double volume(int piece) {
		if (Double.isNaN(intersections[piece])) {
			var first = before.piece(piece); var second = after.piece(piece);
			intersections[piece] = positive[piece] == 1 || first == null || second == null ? 0
					: intersectionVolume(first, second, positive[piece] == 2);
			positive[piece] = (byte) (intersections[piece] > 0 ? 2 : 1);
		}
		return intersections[piece];
	}

	static double intersectionVolume(GasComplementGeometry.Piece before, GasComplementGeometry.Piece after) {
		return intersectionVolume(before, after, false);
	}

	/** Reuses captured piece volumes when the half-spaces contain one another. */
	private static double intersectionVolume(GasComplementGeometry.Piece before, GasComplementGeometry.Piece after, boolean positiveKnown) {
		if (before.id() != after.id() || before.cell() != after.cell() || before.micro() != after.micro())
			throw new IllegalArgumentException("Gas intersection requires the same fixed microbox");

		if (after.dryPlane() == null || java.util.Objects.equals(before.dryPlane(), after.dryPlane()))
			return before.normalizedVolume();
		if (before.dryPlane() == null) return after.normalizedVolume();
		int micro = before.micro();
		if (!positiveKnown && !GasGeometryOverlap.hasPositiveOverlap(micro, before.dryPlane(), after.dryPlane())) return 0;
		if (!GasGeometryOverlap.hasPositiveOverlap(micro, before.dryPlane(), opposite(after.dryPlane())))
			return before.normalizedVolume();
		if (!GasGeometryOverlap.hasPositiveOverlap(micro, after.dryPlane(), opposite(before.dryPlane())))
			return after.normalizedVolume();
		return GasGeometryOverlap.normalizedVolume(micro, List.of(before.dryPlane(), after.dryPlane()));
	}

	private static GasGeometryOverlap.HalfSpace opposite(GasGeometryOverlap.HalfSpace plane) {
		return new GasGeometryOverlap.HalfSpace(-plane.nx(), -plane.ny(), -plane.nz(), -plane.threshold());
	}
}
