package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.List;

/** Shared old/new microbox intersections for inventory transfer and surviving phase contacts. */
final class GasGeometryTransition {
	private final GasComplementGeometry before;
	private GasComplementGeometry after;
	private FlipPressurePhaseGeometry phase;
	private final ExactRational[] intersections;
	private final byte[] positive;

	GasGeometryTransition(GasRuntimeGeometry before, GasRuntimeGeometry after) {
		this(before.geometry()); this.after = after.geometry();
	}
	GasGeometryTransition(GasRuntimeGeometry before, FlipPressurePhaseGeometry phase) {
		this(before.geometry()); this.phase = phase;
	}
	private GasGeometryTransition(GasComplementGeometry before) {
		this.before = before;
		int pieces = 8 * before.sizeX() * before.sizeY() * before.sizeZ();
		intersections = new ExactRational[pieces]; positive = new byte[pieces];
	}

	void complete(GasComplementGeometry geometry) { after = geometry; phase = null; }
	boolean startsAt(GasRuntimeGeometry source) { return before == source.geometry(); }
	boolean hasContact(List<Integer> pieces) {
		for (int piece : pieces) if (positive(piece)) return true;
		return false;
	}

	boolean positive(int piece) {
		if (positive[piece] != 0) return positive[piece] == 2;
		if (after == null) return phasePositive(piece);
		var first = before.piece(piece); var second = after.piece(piece);
		boolean present = first != null && second != null;
		if (present && first.dryPlane() != null && second.dryPlane() != null
				&& !first.dryPlane().equals(second.dryPlane()))
			present = GasGeometryOverlap.hasPositiveOverlap(first.micro(), first.dryPlane(), second.dryPlane());
		positive[piece] = (byte) (present ? 2 : 1);
		return present;
	}

	/** Contacts need only the local phase geometry; chamber topology and stock remain unplanned. */
	private boolean phasePositive(int piece) {
		var first = before.piece(piece); int cell = piece / 8, micro = piece & 7;
		boolean present = false;
		if (first != null) {
			{
				double fraction = phase.liquidFractionExact(cell, micro);
				if (fraction == 0) present = true;
				else if (fraction < 1) {
					var plane = new GasGeometryOverlap.HalfSpace(
							SignedDyadic.of(phase.planeNormal(cell, micro, 0)),
							SignedDyadic.of(phase.planeNormal(cell, micro, 1)),
							SignedDyadic.of(phase.planeNormal(cell, micro, 2)),
							SignedDyadic.of(phase.planeOffset(cell, micro)));
					present = GasGeometryOverlap.hasPositiveOverlap(micro, first.dryPlane(), plane);
				}
			}
		}
		positive[piece] = (byte) (present ? 2 : 1); return present;
	}

	ExactRational volume(int piece) {
		if (intersections[piece] == null) {
			var first = before.piece(piece); var second = after.piece(piece);
			intersections[piece] = positive[piece] == 1 || first == null || second == null ? ExactRational.ZERO
					: intersectionVolume(first, second, positive[piece] == 2);
			positive[piece] = (byte) (intersections[piece].signum() > 0 ? 2 : 1);
		}
		return intersections[piece];
	}

	static ExactRational intersectionVolume(GasComplementGeometry.Piece before, GasComplementGeometry.Piece after) {
		return intersectionVolume(before, after, false);
	}

	/** Reuses certified piece volumes when exact positive-volume tests prove containment. */
	private static ExactRational intersectionVolume(GasComplementGeometry.Piece before, GasComplementGeometry.Piece after, boolean positiveKnown) {
		if (before.id() != after.id() || before.cell() != after.cell() || before.micro() != after.micro())
			throw new IllegalArgumentException("Gas intersection requires the same fixed microbox");

		if (after.dryPlane() == null || java.util.Objects.equals(before.dryPlane(), after.dryPlane()))
			return before.normalizedVolume();
		if (before.dryPlane() == null) return after.normalizedVolume();
		int micro = before.micro();
		if (!positiveKnown && !GasGeometryOverlap.hasPositiveOverlap(micro, before.dryPlane(), after.dryPlane())) return ExactRational.ZERO;
		if (!GasGeometryOverlap.hasPositiveOverlap(micro, before.dryPlane(), opposite(after.dryPlane())))
			return before.normalizedVolume();
		if (!GasGeometryOverlap.hasPositiveOverlap(micro, after.dryPlane(), opposite(before.dryPlane())))
			return after.normalizedVolume();
		return GasGeometryOverlap.normalizedVolume(micro, List.of(before.dryPlane(), after.dryPlane()));
	}

	private static GasGeometryOverlap.HalfSpace opposite(GasGeometryOverlap.HalfSpace plane) {
		return new GasGeometryOverlap.HalfSpace(plane.nx().negate(), plane.ny().negate(), plane.nz().negate(), plane.threshold().negate());
	}
}
