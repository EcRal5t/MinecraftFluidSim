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
