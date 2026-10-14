package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import static mmm.fluid.GasGeometryOverlap.HalfSpace;

/** Positive convex pieces shared by liquid integration and its gas complement. */
final class LiquidShapeGeometry {
	private static final SignedDyadic ONE = SignedDyadic.of(1), HALF = SignedDyadic.of(1, -1);
	private LiquidShapeGeometry() { }

	static List<List<HalfSpace>> wetSupports(FlipPressurePhaseGeometry phase, int cell, int micro) {
		var shape = phase.liquidShape(cell);
		if (shape == null) return plicSupport(phase, cell, micro, true);
		var box = box(shape);
		var clipped = intersection(box, microBox(micro));
		return clipped == null ? List.of() : List.of(microPlanes(clipped, micro));
	}
	static List<List<HalfSpace>> faceWetSupports(FlipPressurePhaseGeometry phase, int cell, int micro) {
		var shape = phase.liquidShape(cell);
		if (shape == null) return plicSupport(phase, cell, micro, true);
		var clipped = intersection(box(shape), microBox(micro));
		return clipped == null ? List.of() : clipped.equals(microBox(micro)) ? List.of(List.of()) : List.of(planes(box(shape)));
	}
	static List<List<HalfSpace>> drySupports(FlipPressurePhaseGeometry phase, int cell, int micro) {
		var shape = phase.liquidShape(cell);
		if (shape == null) return plicSupport(phase, cell, micro, false);
		var whole = microBox(micro); var wet = intersection(box(shape), whole);
		if (wet == null) return List.of(List.of());
		var result = new ArrayList<List<HalfSpace>>(6);
		var lower = lower(whole); var upper = upper(whole);
		// Remove the wet box by peeling disjoint slabs in cyclic axis order.
		for (int axis = 0; axis < 3; axis++) {
			if (lower[axis].compareTo(wet.lower(axis)) < 0) {
				var end = upper.clone(); end[axis] = wet.lower(axis);
				result.add(microPlanes(create(lower, end), micro)); lower[axis] = wet.lower(axis);
			}
			if (upper[axis].compareTo(wet.upper(axis)) > 0) {
				var start = lower.clone(); start[axis] = wet.upper(axis);
				result.add(microPlanes(create(start, upper), micro)); upper[axis] = wet.upper(axis);
			}
		}
		return List.copyOf(result);
	}
	static ExactRational[] wetMoments(FlipPressurePhaseGeometry phase, int cell, int micro, boolean second) {
		var result = new ExactRational[second ? 10 : 4]; java.util.Arrays.fill(result, ExactRational.ZERO);
		for (var support : wetSupports(phase, cell, micro)) {
			var part = second ? GasGeometryOverlap.normalizedSecondMoments(micro, support)
					: GasGeometryOverlap.normalizedMoments(micro, support);
			for (int i = 0; i < result.length; i++) result[i] = result[i].add(part[i]);
		}
		return result;
	}
	static ExactRational maximumWetLinear(FlipPressurePhaseGeometry phase, int cell, int micro, HalfSpace function) {
		if (phase.liquidShape(cell) == null) {
			var support = wetSupports(phase, cell, micro);
			if (support.isEmpty()) return ExactRational.ZERO;
			var plane = support.getFirst().isEmpty() ? new HalfSpace(SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO)
					: support.getFirst().getFirst();
			return GasGeometryOverlap.maximumLinearValue(micro, plane, function);
		}
		var clipped = intersection(box(phase.liquidShape(cell)), microBox(micro));
		if (clipped == null) return ExactRational.ZERO;
		SignedDyadic[] normal = {function.nx(), function.ny(), function.nz()}; var maximum = function.threshold().negate();
		for (int axis = 0; axis < 3; axis++) maximum = maximum.add(normal[axis].multiply(
				normal[axis].signum() >= 0 ? clipped.upper(axis) : clipped.lower(axis)));
		return maximum.signum() <= 0 ? ExactRational.ZERO : PlicComponentGeometry.ratio(maximum, ONE);
	}
	static List<HalfSpace> planes(LiquidCellShape.Box box) {
		var result = new ArrayList<HalfSpace>(6);
		for (int axis = 0; axis < 3; axis++) {
			var normal = new SignedDyadic[]{SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO}; normal[axis] = ONE;
			result.add(new HalfSpace(normal[0], normal[1], normal[2], box.lower(axis)));
			result.add(new HalfSpace(normal[0].negate(), normal[1].negate(), normal[2].negate(), box.upper(axis).negate()));
		}
		return List.copyOf(result);
	}
	private static List<HalfSpace> microPlanes(LiquidCellShape.Box box, int micro) {
		var whole = microBox(micro); var constraints = planes(box); var result = new ArrayList<HalfSpace>(6);
		for (int axis = 0; axis < 3; axis++) {
			if (!box.lower(axis).equals(whole.lower(axis))) result.add(constraints.get(2 * axis));
			if (!box.upper(axis).equals(whole.upper(axis))) result.add(constraints.get(2 * axis + 1));
		}
		return List.copyOf(result);
	}
	static LiquidCellShape.Box box(LiquidCellShape shape) {
		if (shape.boxes().size() != 1)
			throw new GasTopologyBlockedException("Liquid shape requires connected positive-piece integration at cell " + shape.cell());
		return shape.boxes().getFirst();
	}
	private static List<List<HalfSpace>> plicSupport(FlipPressurePhaseGeometry phase, int cell, int micro, boolean wet) {
		double fraction = phase.liquidFractionExact(cell, micro);
		if (wet ? fraction == 0 : fraction >= 1) return List.of();
		if (wet ? fraction >= 1 : fraction == 0) return List.of(List.of());
		var plane = new HalfSpace(SignedDyadic.of(phase.planeNormal(cell, micro, 0)),
				SignedDyadic.of(phase.planeNormal(cell, micro, 1)), SignedDyadic.of(phase.planeNormal(cell, micro, 2)),
				SignedDyadic.of(phase.planeOffset(cell, micro)));
		return List.of(List.of(wet ? new HalfSpace(plane.nx().negate(), plane.ny().negate(), plane.nz().negate(), plane.threshold().negate()) : plane));
	}
	private static LiquidCellShape.Box microBox(int micro) {
		var lower = new SignedDyadic[3]; var upper = new SignedDyadic[3];
		for (int axis = 0; axis < 3; axis++) { lower[axis] = SignedDyadic.of(micro >>> axis & 1, -1); upper[axis] = lower[axis].add(HALF); }
		return create(lower, upper);
	}
	private static LiquidCellShape.Box intersection(LiquidCellShape.Box a, LiquidCellShape.Box b) {
		var lower = new SignedDyadic[3]; var upper = new SignedDyadic[3];
		for (int axis = 0; axis < 3; axis++) {
			lower[axis] = a.lower(axis).compareTo(b.lower(axis)) > 0 ? a.lower(axis) : b.lower(axis);
			upper[axis] = a.upper(axis).compareTo(b.upper(axis)) < 0 ? a.upper(axis) : b.upper(axis);
			if (lower[axis].compareTo(upper[axis]) >= 0) return null;
		}
		return create(lower, upper);
	}
	private static SignedDyadic[] lower(LiquidCellShape.Box box) { return new SignedDyadic[]{box.lower(0), box.lower(1), box.lower(2)}; }
	private static SignedDyadic[] upper(LiquidCellShape.Box box) { return new SignedDyadic[]{box.upper(0), box.upper(1), box.upper(2)}; }
	private static LiquidCellShape.Box create(SignedDyadic[] lower, SignedDyadic[] upper) {
		return new LiquidCellShape.Box(lower[0], lower[1], lower[2], upper[0], upper[1], upper[2]);
	}
}
