package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Exact intersection of at most two half-spaces inside one open microbox. */
final class GasGeometryOverlap {
	/** Dry support nx*x+ny*y+nz*z >= threshold in cell-local coordinates. */
	record HalfSpace(SignedDyadic nx, SignedDyadic ny, SignedDyadic nz, SignedDyadic threshold) {
		HalfSpace { Objects.requireNonNull(nx); Objects.requireNonNull(ny); Objects.requireNonNull(nz); Objects.requireNonNull(threshold); }
	}
	private record Point(ExactRational x, ExactRational y, ExactRational z) {
		ExactRational component(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
		Point subtract(Point p) { return new Point(x.subtract(p.x), y.subtract(p.y), z.subtract(p.z)); }
		Point interpolate(Point p, ExactRational t) {
			return new Point(x.add(t.multiply(p.x.subtract(x))), y.add(t.multiply(p.y.subtract(y))), z.add(t.multiply(p.z.subtract(z))));
		}
	}
	private record Plane(ExactRational nx, ExactRational ny, ExactRational nz, ExactRational threshold) {
		ExactRational normal(int axis) { return axis == 0 ? nx : axis == 1 ? ny : nz; }
		ExactRational distance(Point p) { return nx.multiply(p.x).add(ny.multiply(p.y)).add(nz.multiply(p.z)).subtract(threshold); }
	}
	private static final ExactRational HALF = ExactRational.ONE.divide(2);
	private static final ExactRational MICRO_VOLUME = ExactRational.ONE.divide(8);
	private GasGeometryOverlap() { }

	/** Exact cell-normalized volume. The caller supplies only microboxes open in both geometries. */
	static ExactRational normalizedVolume(int micro, List<HalfSpace> planes) {
		return integrate(micro, planes, 0)[0];
	}

	/** Exact positivity only; null denotes the complete microbox. */
	static boolean hasPositiveOverlap(int micro, HalfSpace before, HalfSpace after) {
		if (micro < 0 || micro >= 8) throw new IllegalArgumentException("Invalid gas overlap microbox");
		boolean unrestricted = before == null || zeroNormal(before);
		if (before != null && unrestricted && before.threshold().signum() > 0) return false;
		SignedDyadic[] first = new SignedDyadic[8];
		boolean positiveBefore = unrestricted;
		for (int corner = 0; corner < 8; corner++) {
			first[corner] = unrestricted ? SignedDyadic.of(1) : cornerDistance(micro, corner, before);
			positiveBefore |= first[corner].signum() > 0;
		}
		if (!positiveBefore) return false;
		if (after == null || zeroNormal(after)) return after == null || after.threshold().signum() <= 0;
		SignedDyadic[] second = new SignedDyadic[8];
		for (int corner = 0; corner < 8; corner++) {
			second[corner] = cornerDistance(micro, corner, after);
			if (first[corner].signum() >= 0 && second[corner].signum() > 0) return true;
		}
		// A linear function on the positive-volume clipped box reaches its maximum at a
		// box corner or a first-plane/box-edge intersection. A strict maximum permits an
		// interior perturbation into both dry supports; a zero maximum is only tangency.
		for (int corner = 0; corner < 8; corner++) for (int axis = 0; axis < 3; axis++) {
			if ((corner & 1 << axis) != 0) continue;
			int peer = corner | 1 << axis;
			if (first[corner].signum() * first[peer].signum() >= 0) continue;
			SignedDyadic numerator = second[corner].multiply(first[peer])
					.subtract(second[peer].multiply(first[corner]));
			if (numerator.signum() * first[peer].subtract(first[corner]).signum() > 0) return true;
		}
		return false;
	}

	private static boolean zeroNormal(HalfSpace plane) {
		return plane.nx().signum() == 0 && plane.ny().signum() == 0 && plane.nz().signum() == 0;
	}
	private static SignedDyadic cornerDistance(int micro, int corner, HalfSpace plane) {
		return plane.nx().multiply(SignedDyadic.of((micro & 1) + (corner & 1), -1))
				.add(plane.ny().multiply(SignedDyadic.of((micro >>> 1 & 1) + (corner >>> 1 & 1), -1)))
				.add(plane.nz().multiply(SignedDyadic.of((micro >>> 2 & 1) + (corner >>> 2 & 1), -1)))
				.subtract(plane.threshold());
	}

	/** Exact normalized volume and signed first moments about the whole-cell centre. */
	static ExactRational[] normalizedMoments(int micro, List<HalfSpace> planes) {
		return integrate(micro, planes, 1);
	}

	/** V, X, Y, Z, XX, XY, XZ, YY, YZ, ZZ, with coordinates about the whole-cell centre. */
	static ExactRational[] normalizedSecondMoments(int micro, List<HalfSpace> planes) {
		return integrate(micro, planes, 2);
	}

	private static ExactRational[] integrate(int micro, List<HalfSpace> planes, int order) {
		boolean moments = order > 0;
		if (micro < 0 || micro >= 8 || planes == null || planes.size() > 2 || planes.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Invalid gas overlap microbox or half-spaces");
		}
		List<HalfSpace> constraints = List.copyOf(planes);
		try {
			Point origin = new Point(ExactRational.of(micro & 1).divide(2), ExactRational.of(micro >>> 1 & 1).divide(2), ExactRational.of(micro >>> 2 & 1).divide(2));
			ExactRational[] result = new ExactRational[order == 2 ? 10 : 4]; java.util.Arrays.fill(result, ExactRational.ZERO);
			var active = new ArrayList<Plane>(2);
			for (HalfSpace halfSpace : constraints) {
				Plane plane = new Plane(exact(halfSpace.nx), exact(halfSpace.ny), exact(halfSpace.nz), exact(halfSpace.threshold));
				ExactRational low = plane.distance(origin), high = low;
				for (int axis = 0; axis < 3; axis++) {
					ExactRational spread = plane.normal(axis).multiply(HALF);
					if (spread.signum() < 0) low = low.add(spread); else high = high.add(spread);
				}
				if (low.signum() < 0 && high.signum() <= 0) return result;
				if (low.signum() < 0) active.add(plane);
			}
			if (active.isEmpty()) {
				result[0] = MICRO_VOLUME;
				if (moments) for (int axis = 0; axis < 3; axis++)
					result[axis + 1] = MICRO_VOLUME.multiply(origin.component(axis).subtract(HALF.divide(2)));
				if (order == 2) for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++) {
					var product = origin.component(a).subtract(HALF.divide(2)).multiply(origin.component(b).subtract(HALF.divide(2)));
					result[index++] = MICRO_VOLUME.multiply(a == b ? product.add(ExactRational.ONE.divide(48)) : product);
				}
				return result;
			}
			List<List<Point>> faces = box(origin);
			for (Plane plane : active) {
				faces = clip(faces, plane);
				if (faces.isEmpty()) return result;
			}
			ExactRational sixVolume = ExactRational.ZERO;
			for (List<Point> face : faces) {
				Point first = face.getFirst().subtract(origin);
				for (int i = 1; i + 1 < face.size(); i++) {
					ExactRational determinant = determinant(first, face.get(i).subtract(origin), face.get(i + 1).subtract(origin));
					if (determinant.isZero()) continue;
					sixVolume = sixVolume.add(determinant);
					if (moments) for (int axis = 0; axis < 3; axis++) {
						// A tetrahedron's centroid is the average of its four vertices.
						ExactRational sum = origin.component(axis).add(face.getFirst().component(axis))
								.add(face.get(i).component(axis)).add(face.get(i + 1).component(axis)).subtract(ExactRational.of(2));
						result[axis + 1] = result[axis + 1].add(determinant.multiply(sum).divide(24));
					}
					if (order == 2) addSecondMoments(result, determinant, origin, face.getFirst(), face.get(i), face.get(i + 1));
				}
			}
			ExactRational volume = sixVolume.divide(6);
			if (volume.signum() < 0 || volume.compareTo(MICRO_VOLUME) > 0) throw new IllegalStateException("Gas overlap polyhedron orientation does not close");
			result[0] = volume;
			if (moments) for (int axis = 0; axis < 3; axis++) {
				ExactRational low = origin.component(axis).subtract(HALF).multiply(volume);
				ExactRational high = origin.component(axis).multiply(volume);
				if (result[axis + 1].compareTo(low) < 0 || result[axis + 1].compareTo(high) > 0)
					throw new IllegalStateException("Overlap first moment lies outside its microbox");
			}
			return result;
		} catch (IllegalArgumentException magnitude) {
			throw new GasTopologyBlockedException("Exact gas overlap exceeds the rational representation budget: " + magnitude.getMessage());
		}
	}

	private static void addSecondMoments(ExactRational[] result, ExactRational determinant, Point... vertices) {
		var centered = new ExactRational[4][3];
		var sum = new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		for (int p = 0; p < 4; p++) for (int a = 0; a < 3; a++) {
			centered[p][a] = vertices[p].component(a).subtract(HALF); sum[a] = sum[a].add(centered[p][a]);
		}
		// A simplex quadratic mean is (sum(x)*sum(y) + sum(x*y))/20; signed volume is det/6.
		for (int a = 0, index = 4; a < 3; a++) for (int b = a; b < 3; b++) {
			var product = sum[a].multiply(sum[b]);
			for (int p = 0; p < 4; p++) product = product.add(centered[p][a].multiply(centered[p][b]));
			result[index] = result[index].add(determinant.multiply(product).divide(120)); index++;
		}
	}

	/** Six outward-oriented rectangles; cyclic tangent order has positive normal along the chosen axis. */
	private static List<List<Point>> box(Point origin) {
		var faces = new ArrayList<List<Point>>(6);
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
			var face = new ArrayList<Point>(4);
			for (int corner : new int[]{0, 1, 3, 2}) {
				ExactRational[] p = {origin.x, origin.y, origin.z};
				if (sign > 0) p[axis] = p[axis].add(HALF);
				if ((corner & 1) != 0) p[(axis + 1) % 3] = p[(axis + 1) % 3].add(HALF);
				if ((corner & 2) != 0) p[(axis + 2) % 3] = p[(axis + 2) % 3].add(HALF);
				face.add(new Point(p[0], p[1], p[2]));
			}
			if (sign < 0) java.util.Collections.reverse(face);
			faces.add(face);
		}
		return faces;
	}

	private static List<List<Point>> clip(List<List<Point>> faces, Plane plane) {
		boolean outside = false, inside = false;
		for (List<Point> face : faces) for (Point p : face) {
			int sign = plane.distance(p).signum(); outside |= sign < 0; inside |= sign > 0;
		}
		if (!outside) return faces;
		if (!inside) return List.of(); // Retaining only a plane has zero three-dimensional measure.
		var result = new ArrayList<List<Point>>(8);
		var boundary = new ArrayList<Point>();
		for (List<Point> face : faces) {
			var clipped = new ArrayList<Point>(face.size() + 1);
			Point previous = face.getLast(); ExactRational previousDistance = plane.distance(previous);
			for (Point point : face) {
				ExactRational distance = plane.distance(point);
				if ((distance.signum() >= 0) != (previousDistance.signum() >= 0)) {
					Point intersection = previous.interpolate(point, divide(previousDistance, previousDistance.subtract(distance)));
					append(clipped, intersection); boundary.add(intersection);
				}
				if (distance.signum() >= 0) append(clipped, point);
				if (distance.isZero()) boundary.add(point);
				previous = point; previousDistance = distance;
			}
			if (clipped.size() > 1 && clipped.getFirst().equals(clipped.getLast())) clipped.removeLast();
			if (clipped.size() >= 3) result.add(clipped);
		}
		int axis = 0; while (plane.normal(axis).isZero()) axis++;
		List<Point> cap = hull(boundary, axis);
		if (cap.size() < 3) throw new IllegalStateException("A gas overlap cut lacks its closing face");
		if (plane.normal(axis).signum() > 0) java.util.Collections.reverse(cap); // Retained half-space points along +n; outward cap is -n.
		result.add(cap);
		return result;
	}

	/** Coplanar projected convex hull, with exact orientation and no angular sorting. */
	private static List<Point> hull(List<Point> boundary, int axis) {
		int tangent = (axis + 1) % 3, other = (axis + 2) % 3;
		List<Point> points = boundary.stream().distinct().sorted(Comparator.comparing((Point p) -> p.component(tangent)).thenComparing(p -> p.component(other))).toList();
		var lower = new ArrayList<Point>(); var upper = new ArrayList<Point>();
		for (Point p : points) hullAppend(lower, p, tangent, other);
		for (int i = points.size() - 1; i >= 0; i--) hullAppend(upper, points.get(i), tangent, other);
		if (!lower.isEmpty()) lower.removeLast();
		if (!upper.isEmpty()) upper.removeLast();
		lower.addAll(upper);
		return lower;
	}
	private static void hullAppend(List<Point> hull, Point p, int tangent, int other) {
		while (hull.size() >= 2 && turn(hull.get(hull.size() - 2), hull.getLast(), p, tangent, other).signum() <= 0) hull.removeLast();
		hull.add(p);
	}
	private static ExactRational turn(Point a, Point b, Point c, int tangent, int other) {
		return b.component(tangent).subtract(a.component(tangent)).multiply(c.component(other).subtract(a.component(other)))
				.subtract(b.component(other).subtract(a.component(other)).multiply(c.component(tangent).subtract(a.component(tangent))));
	}
	private static void append(List<Point> polygon, Point point) {
		if (polygon.isEmpty() || !polygon.getLast().equals(point)) polygon.add(point);
	}
	private static ExactRational determinant(Point a, Point b, Point c) {
		return a.x.multiply(b.y.multiply(c.z).subtract(b.z.multiply(c.y)))
				.add(a.y.multiply(b.z.multiply(c.x).subtract(b.x.multiply(c.z))))
				.add(a.z.multiply(b.x.multiply(c.y).subtract(b.y.multiply(c.x))));
	}
	private static ExactRational divide(ExactRational a, ExactRational b) {
		return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator()));
	}
	private static ExactRational exact(SignedDyadic value) {
		long exponent = value.exponent();
		long numeratorBits = value.significand().abs().bitLength() + Math.max(exponent, 0);
		if (numeratorBits > ChemicalLimits.MAX_RATIONAL_BITS || Math.max(-exponent, 0) + 1 > ChemicalLimits.MAX_RATIONAL_BITS) {
			throw new GasTopologyBlockedException("Gas overlap half-space exceeds the rational representation budget");
		}
		return exponent >= 0 ? new ExactRational(value.significand().shiftLeft((int) exponent), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft((int) -exponent));
	}
}
