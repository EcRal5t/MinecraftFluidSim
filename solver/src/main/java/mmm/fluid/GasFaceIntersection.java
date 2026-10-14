package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact dry-half-space intersection on one local open-face rectangle. */
final class GasFaceIntersection {
	/** The dry half-space a*tangent+b*other >= threshold, before any binary64 rounding. */
	record Plane(SignedDyadic a, SignedDyadic b, SignedDyadic threshold) {
		Plane { Objects.requireNonNull(a); Objects.requireNonNull(b); Objects.requireNonNull(threshold); }
	}
	private record Point(ExactRational x, ExactRational y) { }
	private GasFaceIntersection() { }

	/** A linear dry half-space covers a rectangle exactly when it contains all four corners. */
	static boolean coversRectangle(double tangentStart, double otherStart, double width, List<Plane> planes) {
		if (!Double.isFinite(tangentStart) || !Double.isFinite(otherStart) || !(width > 0 && Double.isFinite(width))
				|| planes == null || planes.size() > 2 || planes.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Invalid complete gas face rectangle");
		SignedDyadic x = SignedDyadic.of(tangentStart), y = SignedDyadic.of(otherStart), span = SignedDyadic.of(width);
		for (Plane plane : planes) for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++)
			if (plane.a().multiply(a == 0 ? x : x.add(span)).add(plane.b().multiply(b == 0 ? y : y.add(span)))
					.compareTo(plane.threshold()) < 0) return false;
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

	/** Exact primitive for one rectangle and at most two immutable dry half-spaces. */
	static double area(double tangentStart, double otherStart, double width, List<Plane> planes) {
		if (!Double.isFinite(tangentStart) || !Double.isFinite(otherStart) || !(width >= 0 && Double.isFinite(width))
				|| planes == null || planes.size() > 2 || planes.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Invalid exact gas face primitive");
		if (width == 0) return 0;
		if (planes.isEmpty()) return positiveArea(width * width);
		try {
			ExactRational x = exact(tangentStart), y = exact(otherStart), span = exact(width);
			ExactRational endX = x.add(span), endY = y.add(span);
			List<Point> polygon = List.of(new Point(x, y), new Point(endX, y), new Point(endX, endY), new Point(x, endY));
			for (Plane plane : planes) polygon = clip(polygon, plane);
			if (polygon.size() < 3) return 0;
			ExactRational twiceArea = ExactRational.ZERO;
			Point previous = polygon.getLast();
			for (Point point : polygon) {
				twiceArea = twiceArea.add(previous.x.multiply(point.y).subtract(previous.y.multiply(point.x)));
				previous = point;
			}
			if (twiceArea.isZero()) return 0;
			if (twiceArea.signum() < 0) twiceArea = twiceArea.multiply(-1);
			return positiveArea(twiceArea.divide(2).toDouble());
		} catch (IllegalArgumentException magnitude) {
			// Inputs have been validated; rational arithmetic owns its bounded magnitude failure.
			throw new GasTopologyBlockedException("Exact gas face intersection exceeds the rational representation budget: " + magnitude.getMessage());
		}
	}

	private static Plane plane(FlipPressurePhaseGeometry phase, int cell, int micro, int axis, double coordinate) {
		double offset = phase.planeOffset(cell,micro);
		if (!Double.isFinite(offset)) throw new IllegalArgumentException("Nonfinite gas face phase plane");
		for (int a = 0; a < 3; a++) if (!Float.isFinite(phase.planeNormal(cell,micro,a))) throw new IllegalArgumentException("Nonfinite gas face phase normal");
		SignedDyadic threshold = SignedDyadic.of(offset).subtract(SignedDyadic.of(phase.planeNormal(cell,micro,axis)).multiply(SignedDyadic.of(coordinate)));
		return new Plane(SignedDyadic.of(phase.planeNormal(cell,micro,(axis+1)%3)),SignedDyadic.of(phase.planeNormal(cell,micro,(axis+2)%3)),threshold);
	}
	private static List<Point> clip(List<Point> polygon, Plane plane) {
		if (polygon.isEmpty()) return polygon;
		ExactRational nx = exact(plane.a()), ny = exact(plane.b()), offset = exact(plane.threshold());
		var result = new ArrayList<Point>(polygon.size() + 2);
		Point previous = polygon.getLast(); ExactRational previousDistance = distance(previous, nx, ny, offset);
		for (Point point : polygon) {
			ExactRational currentDistance = distance(point, nx, ny, offset);
			if ((currentDistance.signum() >= 0) != (previousDistance.signum() >= 0)) {
				ExactRational t = divide(previousDistance, previousDistance.subtract(currentDistance));
				result.add(new Point(previous.x.add(t.multiply(point.x.subtract(previous.x))), previous.y.add(t.multiply(point.y.subtract(previous.y)))));
			}
			if (currentDistance.signum() >= 0) result.add(point);
			previous = point; previousDistance = currentDistance;
		}
		return result;
	}
	private static ExactRational distance(Point point, ExactRational nx, ExactRational ny, ExactRational offset) {
		return nx.multiply(point.x).add(ny.multiply(point.y)).subtract(offset);
	}
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator()));
	}
	private static ExactRational exact(double value) {
		return exact(SignedDyadic.of(value));
	}
	private static ExactRational exact(SignedDyadic dyadic) {
		return dyadic.exponent() >= 0
				? new ExactRational(dyadic.significand().shiftLeft(dyadic.exponent()), BigInteger.ONE)
				: new ExactRational(dyadic.significand(), BigInteger.ONE.shiftLeft(-dyadic.exponent()));
	}
	private static double positiveArea(double value) {
		if (!(value > 0 && Double.isFinite(value))) throw new GasTopologyBlockedException("Positive dry face area is not representable");
		return value;
	}
}
