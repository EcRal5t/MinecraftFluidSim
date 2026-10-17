package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Floating-point dry-half-space intersection on a local open-face rectangle. */
final class GasFaceIntersection {
	/** The dry half-space a*tangent+b*other >= threshold, before any binary64 rounding. */
	record Plane(SignedDyadic a, SignedDyadic b, SignedDyadic threshold) {
		Plane { Objects.requireNonNull(a); Objects.requireNonNull(b); Objects.requireNonNull(threshold); }
	}
	private GasFaceIntersection() { }

	/** A linear dry half-space covers a rectangle exactly when it contains all four corners. */
	static boolean coversRectangle(double tangentStart, double otherStart, double width, List<Plane> planes) {
		if (!Double.isFinite(tangentStart) || !Double.isFinite(otherStart) || !(width > 0 && Double.isFinite(width))
				|| planes == null || planes.size() > 12 || planes.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Invalid complete gas face rectangle");
		for (Plane plane : planes) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
			if (plane.a().toDouble() * (tangentStart + a * width) + plane.b().toDouble() * (otherStart + b * width)
					< plane.threshold().toDouble()) return false;
		return true;
	}

	/** All four microfaces on one physical cell face must be completely dry. */
	static boolean fullDryFace(FlipPressurePhaseGeometry phase, int cell, int axis, int coordinate) {
		if (phase == null || cell < 0 || axis < 0 || axis > 2 || coordinate < 0 || coordinate > 1)
			throw new IllegalArgumentException("Invalid complete gas cell face");
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			double tangent = .5 * a, other = .5 * b;
			int micro = phase.microAtFace(cell, axis, coordinate, tangent, other);

			double fraction = phase.liquidFractionExact(cell, micro);
			if (!(fraction >= 0 && fraction <= 1)) throw new IllegalArgumentException("Invalid gas face phase fraction");
			if (fraction >= 1 || fraction > 0 && !coversRectangle(tangent, other, .5,
					List.of(plane(phase, cell, micro, axis, coordinate)))) return false;
		}
		return true;
	}

	/** The second cell is -1 for a single-sided outer or internal microface. */
	static double area(FlipPressurePhaseGeometry phase, int firstCell, int secondCell, int axis,
			double firstCoordinate, double secondCoordinate, double tangentStart, double otherStart, double width) {
		if (phase == null || firstCell < 0 || secondCell < -1 || axis < 0 || axis > 2
				|| !Double.isFinite(firstCoordinate) || !Double.isFinite(secondCoordinate)
				|| !Double.isFinite(tangentStart) || !Double.isFinite(otherStart)
				|| !(width >= 0 && Double.isFinite(width))) throw new IllegalArgumentException("Invalid gas face rectangle");
		int firstMicro = phase.microAtFace(firstCell,axis,firstCoordinate,tangentStart,otherStart);
		int secondMicro = secondCell < 0 ? 0 : phase.microAtFace(secondCell,axis,secondCoordinate,tangentStart,otherStart);


		double firstFraction = phase.liquidFractionExact(firstCell,firstMicro);
		double secondFraction = secondCell < 0 ? 0 : phase.liquidFractionExact(secondCell,secondMicro);
		if (!(firstFraction >= 0 && firstFraction <= 1 && secondFraction >= 0 && secondFraction <= 1))
			throw new IllegalArgumentException("Invalid gas face phase fraction");
		if (width == 0 || firstFraction >= 1 || secondFraction >= 1) return 0;
		var planes = new ArrayList<Plane>(2);
		if (firstFraction > 0) planes.add(plane(phase, firstCell, firstMicro, axis, firstCoordinate));
		if (secondFraction > 0) planes.add(plane(phase, secondCell, secondMicro, axis, secondCoordinate));
		return area(tangentStart, otherStart, width, planes);
	}

	/** Exact primitive for one rectangle and up to twelve immutable half-spaces. */
	static double area(double tangentStart, double otherStart, double width, List<Plane> planes) {
		if (!Double.isFinite(tangentStart) || !Double.isFinite(otherStart) || !(width >= 0 && Double.isFinite(width))
				|| planes == null || planes.size() > 12 || planes.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Invalid exact gas face primitive");
		if (width == 0) return 0;
		return planes.isEmpty() ? positiveArea(width * width) : observedArea(exactArea(tangentStart, otherStart, width, planes));
	}
	static ExactRational exactArea(double tangentStart, double otherStart, double width, List<Plane> planes) {
		if (!(width >= 0 && Double.isFinite(width))) throw new IllegalArgumentException("Invalid face width");
		List<double[]> polygon = List.of(new double[]{0,0}, new double[]{width,0}, new double[]{width,width}, new double[]{0,width});
		for (var plane : planes) {
			if (polygon.isEmpty()) break;
			double nx = plane.a().toDouble(), ny = plane.b().toDouble();
			double offset = plane.threshold().toDouble() - nx * tangentStart - ny * otherStart;
			var clipped = new ArrayList<double[]>(polygon.size() + 1);
			var previous = polygon.getLast(); double dp = nx * previous[0] + ny * previous[1] - offset;
			for (var point : polygon) {
				double dc = nx * point[0] + ny * point[1] - offset;
				if ((dc >= 0) != (dp >= 0)) {
					double t = dp / (dp - dc);
					clipped.add(new double[]{Math.fma(t, point[0] - previous[0], previous[0]), Math.fma(t, point[1] - previous[1], previous[1])});
				}
				if (dc >= 0) clipped.add(point);
				previous = point; dp = dc;
			}
			polygon = clipped;
		}
		double twice = 0;
		if (polygon.size() >= 3) {
			var origin = polygon.getFirst();
			for (int i = 1; i + 1 < polygon.size(); i++) {
				var a = polygon.get(i); var b = polygon.get(i + 1);
				twice += (a[0] - origin[0]) * (b[1] - origin[1]) - (a[1] - origin[1]) * (b[0] - origin[0]);
			}
		}
		return MicroBoxPlaneVolume.rational(Math.clamp(Math.abs(twice) * .5, 0, width * width));
	}
	private static double observedArea(ExactRational value) { return value.isZero() ? 0 : positiveArea(value.toDouble()); }
	static List<Plane> project(List<GasGeometryOverlap.HalfSpace> planes, int axis, double coordinate) {
		return project(planes, axis, SignedDyadic.of(coordinate));
	}
	private static List<Plane> project(List<GasGeometryOverlap.HalfSpace> planes, int axis, SignedDyadic coordinate) {
		var result = new ArrayList<Plane>(planes.size());
		for (var plane : planes) {
			SignedDyadic[] n = {plane.nx(), plane.ny(), plane.nz()};
			result.add(new Plane(n[(axis + 1) % 3], n[(axis + 2) % 3],
					plane.threshold().subtract(n[axis].multiply(coordinate))));
		}
		return result;
	}
	/** Every positive convex dry part must have a positive shared face path. */
	static boolean connected(int micro, List<List<GasGeometryOverlap.HalfSpace>> supports) {
		if (supports.isEmpty()) return false;
		var reached = new boolean[supports.size()]; reached[0] = true;
		for (int pass = 0; pass < supports.size(); pass++) for (int a = 0; a < supports.size(); a++) if (reached[a])
			for (int b = 0; b < supports.size(); b++) if (!reached[b] && sharedFace(micro, supports.get(a), supports.get(b))) reached[b] = true;
		for (boolean value : reached) if (!value) return false;
		return true;
	}
	private static boolean sharedFace(int micro, List<GasGeometryOverlap.HalfSpace> first, List<GasGeometryOverlap.HalfSpace> second) {
		for (var plane : first) {
			SignedDyadic[] n = {plane.nx(), plane.ny(), plane.nz()}; int axis = 0;
			while (axis < 3 && n[axis].signum() == 0) axis++;
			if (axis == 3) continue;
			var opposite = new GasGeometryOverlap.HalfSpace(plane.nx().negate(), plane.ny().negate(), plane.nz().negate(), plane.threshold().negate());
			if (!second.contains(opposite)) continue;
			if (n[(axis + 1) % 3].signum() == 0 && n[(axis + 2) % 3].signum() == 0
					&& magnitude(n[axis]).equals(SignedDyadic.of(1))) {
				var coordinate = n[axis].signum() < 0 ? plane.threshold().negate() : plane.threshold();
				var planes = new ArrayList<Plane>(); planes.addAll(project(first, axis, coordinate)); planes.addAll(project(second, axis, coordinate));
				if (exactArea(.5 * (micro >>> ((axis + 1) % 3) & 1), .5 * (micro >>> ((axis + 2) % 3) & 1), .5, planes).signum() > 0) return true;
			} else if (sharedSlopedFace(micro, plane, n, first, second)) return true;
		}
		return false;
	}
	private static boolean sharedSlopedFace(int micro, GasGeometryOverlap.HalfSpace face, SignedDyadic[] normal,
	                                        List<GasGeometryOverlap.HalfSpace> first, List<GasGeometryOverlap.HalfSpace> second) {
		int axis = 0;
		for (int a = 1; a < 3; a++) if (magnitude(normal[a]).compareTo(magnitude(normal[axis])) > 0) axis = a;
		var planes = new ArrayList<Plane>();
		for (var constraint : first) if (!projectOnFace(planes, constraint, face, normal, axis)) return false;
		for (var constraint : second) if (!projectOnFace(planes, constraint, face, normal, axis)) return false;
		// The integration rectangle supplies four microbox faces. These two constrain the eliminated coordinate.
		var unit = new SignedDyadic[]{SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO}; unit[axis] = SignedDyadic.of(1);
		var lower = SignedDyadic.of(micro >>> axis & 1, -1); var upper = lower.add(SignedDyadic.of(1, -1));
		if (!projectOnFace(planes, new GasGeometryOverlap.HalfSpace(unit[0], unit[1], unit[2], lower), face, normal, axis)
				|| !projectOnFace(planes, new GasGeometryOverlap.HalfSpace(unit[0].negate(), unit[1].negate(), unit[2].negate(), upper.negate()),
						face, normal, axis)) return false;
		return exactArea(.5 * (micro >>> ((axis + 1) % 3) & 1), .5 * (micro >>> ((axis + 2) % 3) & 1), .5, planes).signum() > 0;
	}
	private static boolean projectOnFace(List<Plane> result, GasGeometryOverlap.HalfSpace constraint,
	                                     GasGeometryOverlap.HalfSpace face, SignedDyadic[] normal, int axis) {
		var n = new SignedDyadic[]{constraint.nx(), constraint.ny(), constraint.nz()};
		var scale = magnitude(normal[axis]); var eliminated = normal[axis].signum() > 0 ? n[axis] : n[axis].negate();
		var a = scale.multiply(n[(axis + 1) % 3]).subtract(eliminated.multiply(normal[(axis + 1) % 3]));
		var b = scale.multiply(n[(axis + 2) % 3]).subtract(eliminated.multiply(normal[(axis + 2) % 3]));
		var threshold = scale.multiply(constraint.threshold()).subtract(eliminated.multiply(face.threshold()));
		if (a.signum() == 0 && b.signum() == 0) return threshold.signum() <= 0;
		var projected = new Plane(a, b, threshold);
		if (!result.contains(projected)) result.add(projected);
		return true;
	}
	private static SignedDyadic magnitude(SignedDyadic value) { return value.signum() < 0 ? value.negate() : value; }

	private static Plane plane(FlipPressurePhaseGeometry phase, int cell, int micro, int axis, double coordinate) {
		double offset = phase.planeOffset(cell,micro);
		if (!Double.isFinite(offset)) throw new IllegalArgumentException("Nonfinite gas face phase plane");
		for (int a = 0; a < 3; a++) if (!Float.isFinite(phase.planeNormal(cell,micro,a))) throw new IllegalArgumentException("Nonfinite gas face phase normal");
		SignedDyadic threshold = SignedDyadic.of(offset).subtract(SignedDyadic.of(phase.planeNormal(cell,micro,axis)).multiply(SignedDyadic.of(coordinate)));
		return new Plane(SignedDyadic.of(phase.planeNormal(cell,micro,(axis+1)%3)),SignedDyadic.of(phase.planeNormal(cell,micro,(axis+2)%3)),threshold);
	}
	private static double positiveArea(double value) {
		if (!(value > 0 && Double.isFinite(value))) throw new GasTopologyBlockedException("Positive dry face area is not representable");
		return value;
	}
}
