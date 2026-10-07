package cc.ecisr.mmm.fluid;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import static cc.ecisr.mmm.fluid.LiquidIntegerArithmetic.gcd;

/** Exact plane intersections and planar clipping; integration rounds each coordinate once. */
final class LiquidExactPolygon {
	/** A binary64 observation needs the exact ratio, independently of its integer reduction. */
	static double roundedRatio(BigInteger numerator, BigInteger denominator) {
		if (denominator.signum() <= 0) throw new IllegalArgumentException("Rational observation requires a positive denominator");
		return Vertex.ratio(numerator, denominator);
	}
	record Vertex(BigInteger x, BigInteger y, BigInteger z, BigInteger w) {
		Vertex {
			if (w.signum() == 0) throw new IllegalArgumentException("Singular geometric vertex");
			BigInteger gcd = gcd(gcd(gcd(x, y), z), w);
			if (w.signum() < 0) gcd = gcd.negate();
			x = x.divide(gcd); y = y.divide(gcd); z = z.divide(gcd); w = w.divide(gcd);
		}
		BigInteger coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
		BigInteger distance(List<BigInteger> plane) {
			return plane.get(0).multiply(x).add(plane.get(1).multiply(y)).add(plane.get(2).multiply(z)).subtract(plane.get(3).multiply(w));
		}
		LiquidPolyhedron.Point rounded() { return new LiquidPolyhedron.Point(ratio(x, w), ratio(y, w), ratio(z, w)); }
		Fraction[] coordinates() { return new Fraction[] {new Fraction(x, w), new Fraction(y, w), new Fraction(z, w)}; }
		private static double ratio(BigInteger numerator, BigInteger denominator) {
			if (numerator.signum() == 0) return 0;
			var magnitude = numerator.abs(); int exponent = magnitude.bitLength() - denominator.bitLength();
			if ((exponent >= 0 ? magnitude.compareTo(denominator.shiftLeft(exponent)) : magnitude.shiftLeft(-exponent).compareTo(denominator)) < 0) exponent--;
			int shift = Math.min(52 - exponent, 1074); // Binary64 significand, including subnormal rounding.
			var dividend = shift >= 0 ? magnitude.shiftLeft(shift) : magnitude;
			var divisor = shift >= 0 ? denominator : denominator.shiftLeft(-shift);
			var division = dividend.divideAndRemainder(divisor); int comparison = division[1].shiftLeft(1).compareTo(divisor);
			if (comparison > 0 || comparison == 0 && division[0].testBit(0)) division[0] = division[0].add(BigInteger.ONE);
			return Math.copySign(Math.scalb(division[0].doubleValue(), -shift), numerator.signum());
		}
	}
	private final List<Vertex> vertices;
	private volatile Vertex centroidVertex;
	private volatile List<Fraction> integratedCentroid;
	private volatile List<BigInteger> planeCoefficients;
	private volatile List<Fraction> integratedArea;
	private volatile LiquidGeometryPlane supportingPlane;
	private volatile List<Vertex> integrationCorners;
	private volatile List<List<BigInteger>> edgeLines;
	static final class Fraction {
		private final BigInteger numerator, denominator;
		static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
		Fraction(BigInteger numerator, BigInteger denominator) {
			if (denominator.signum() == 0) throw new IllegalArgumentException("Singular geometric fraction");
			var divisor = gcd(numerator, denominator); if (denominator.signum() < 0) divisor = divisor.negate();
			this.numerator = numerator.divide(divisor); this.denominator = denominator.divide(divisor);
		}
		/** Arithmetic below proves these operands coprime before constructing the result. */
		private Fraction(BigInteger numerator, BigInteger denominator, boolean reduced) {
			this.numerator = denominator.signum() < 0 ? numerator.negate() : numerator;
			this.denominator = denominator.abs();
		}
		BigInteger numerator() { return numerator; }
		BigInteger denominator() { return denominator; }
		@Override public boolean equals(Object other) { return other instanceof Fraction value && numerator.equals(value.numerator) && denominator.equals(value.denominator); }
		@Override public int hashCode() { return 31 * numerator.hashCode() + denominator.hashCode(); }
		@Override public String toString() { return numerator + "/" + denominator; }
		Fraction add(Fraction other) {
			if (signum() == 0) return other; if (other.signum() == 0) return this;
			var common = gcd(denominator, other.denominator); var first = denominator.divide(common); var second = other.denominator.divide(common);
			var sum = numerator.multiply(second).add(other.numerator.multiply(first)); if (sum.signum() == 0) return ZERO;
			var cancellation = gcd(sum, common);
			return new Fraction(sum.divide(cancellation), first.multiply(other.denominator.divide(cancellation)), true);
		}
		Fraction multiply(Fraction other) {
			if (signum() == 0 || other.signum() == 0) return ZERO;
			var first = gcd(numerator, other.denominator); var second = gcd(other.numerator, denominator);
			return new Fraction(numerator.divide(first).multiply(other.numerator.divide(second)), denominator.divide(second).multiply(other.denominator.divide(first)), true);
		}
		Fraction negate() { return signum() == 0 ? this : new Fraction(numerator.negate(), denominator, true); }
		Fraction subtract(Fraction other) { return add(other.negate()); }
		Fraction divide(Fraction other) {
			if (other.signum() == 0) throw new IllegalArgumentException("Singular geometric fraction"); if (signum() == 0) return ZERO;
			var first = gcd(numerator, other.numerator); var second = gcd(denominator, other.denominator);
			return new Fraction(numerator.divide(first).multiply(other.denominator.divide(second)), denominator.divide(second).multiply(other.numerator.divide(first)), true);
		}
		double rounded() { return Vertex.ratio(numerator, denominator); }
		double toDouble() { return rounded(); }
		int signum() { return numerator.signum(); }
		Fraction absolute() { return signum() < 0 ? negate() : this; }
		static Fraction of(cc.ecisr.mmm.units.SignedDyadic value) {
			return value.exponent() >= 0 ? new Fraction(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
					: new Fraction(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
		}
		static Fraction of(double value) {
			return of(cc.ecisr.mmm.units.SignedDyadic.of(value));
		}
	}
	private final List<BigInteger> normal;
	private LiquidExactPolygon(List<Vertex> vertices, List<BigInteger> normal) { this.vertices = List.copyOf(vertices); this.normal = normal; }
	static LiquidExactPolygon restored(List<Vertex> vertices, List<BigInteger> coefficients) {
		var result = new LiquidExactPolygon(vertices, coefficients.subList(0, 3));
		result.requirePlane(coefficients);
		return result;
	}
	void requirePlane(List<BigInteger> coefficients) {
		if (!hasArea()) throw new IllegalArgumentException("Liquid geometry contains an empty face");
		for (var vertex : vertices) if (vertex.distance(coefficients).signum() != 0) throw new IllegalArgumentException("Liquid geometry face is non-planar");
		int axis = 0; while (axis < 3 && coefficients.get(axis).signum() == 0) axis++;
		if (axis == 3 || areaVector()[axis].signum() != coefficients.get(axis).signum()) throw new IllegalArgumentException("Liquid supporting plane has reversed orientation");
		for (int edge = 0; edge < vertices.size(); edge++) {
			var a = vertices.get(edge); var b = vertices.get((edge + 1) % vertices.size());
			for (var point : vertices) if (cross(difference(b, a), difference(point, a)).get(axis).signum() * coefficients.get(axis).signum() < 0)
				throw new IllegalArgumentException("Liquid geometry face is non-convex or self-intersecting");
		}
	}
	LiquidExactPolygon cycled(int start) {
		if (start < 0 || start >= vertices.size()) throw new IllegalArgumentException("Invalid polygon cycle start");
		if (start == 0) return this;
		var points = new ArrayList<Vertex>(); for (int v = 0; v < vertices.size(); v++) points.add(vertices.get((start + v) % vertices.size()));
		var result = new LiquidExactPolygon(points, normal); result.centroidVertex = centroidVertex; result.integratedCentroid = integratedCentroid; result.integratedArea = integratedArea;
		result.planeCoefficients = planeCoefficients; result.supportingPlane = supportingPlane; return result;
	}
	List<LiquidPolyhedron.Point> rounded() { return vertices.stream().map(Vertex::rounded).toList(); }
	List<Vertex> vertices() { return vertices; }
	List<Vertex> vertexKey() {
		return vertices.stream().sorted(java.util.Comparator.comparing(Vertex::x).thenComparing(Vertex::y).thenComparing(Vertex::z).thenComparing(Vertex::w)).toList();
	}
	LiquidPolyhedron.Point centroid() { return center().rounded(); }
	Fraction[] centroidCoordinates() {
		center(); return integratedCentroid.toArray(Fraction[]::new);
	}
	private Vertex center() {
		var cached = centroidVertex; if (cached != null) return cached;
		var corners = integrationCorners();
		if (corners.size() == 3) {
			var result = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (var vertex : corners) { var x = vertex.coordinates(); for (int a = 0; a < 3; a++) result[a] = result[a].add(x[a]); }
			for (int a = 0; a < 3; a++) result[a] = result[a].divide(Fraction.of(3));
			return cacheCenter(result);
		}
		int axis = 0; while (normal.get(axis).signum() == 0) axis++;
		var zero = Fraction.ZERO; var weight = zero; Fraction[] moment = {zero, zero, zero}; var a = corners.getFirst().coordinates();
		for (int i = 1; i + 1 < corners.size(); i++) {
			var b = corners.get(i).coordinates(); var c = corners.get(i + 1).coordinates();
			var area = triangleCross(corners.getFirst(), corners.get(i), corners.get(i + 1))[axis]; weight = weight.add(area);
			for (int coordinate = 0; coordinate < 3; coordinate++) {
				var sum = a[coordinate].add(b[coordinate]).add(c[coordinate]);
				moment[coordinate] = moment[coordinate].add(area.multiply(sum).multiply(new Fraction(BigInteger.ONE, BigInteger.valueOf(3))));
			}
		}
		if (weight.numerator.signum() == 0) throw new IllegalArgumentException("Empty exact polygon centroid");
		Fraction[] result = new Fraction[3];
		for (int coordinate = 0; coordinate < 3; coordinate++) {
			result[coordinate] = moment[coordinate].divide(weight);
		}
		return cacheCenter(result);
	}
	private Vertex cacheCenter(Fraction[] coordinates) {
		var point = vertex(coordinates); integratedCentroid = List.of(coordinates); return centroidVertex = point;
	}
	static Vertex vertex(Fraction[] coordinates) {
		var denominator = BigInteger.ONE;
		for (var value : coordinates) denominator = denominator.divide(gcd(denominator, value.denominator)).multiply(value.denominator);
		return new Vertex(coordinates[0].numerator.multiply(denominator.divide(coordinates[0].denominator)),
				coordinates[1].numerator.multiply(denominator.divide(coordinates[1].denominator)), coordinates[2].numerator.multiply(denominator.divide(coordinates[2].denominator)), denominator);
	}
	LiquidExactPolygon reversed() { return new LiquidExactPolygon(vertices.reversed(), normal.stream().map(BigInteger::negate).toList()); }
	List<Vertex> tetrahedronVertices(Fraction[] apex, int edge) {
		return List.of(vertex(apex), center(), vertices.get(edge), vertices.get((edge + 1) % vertices.size()));
	}
	List<LiquidExactPolygon> tetrahedron(Fraction[] apex, int edge) {
		return tetrahedron(tetrahedronVertices(apex, edge));
	}
	static List<LiquidExactPolygon> tetrahedron(List<Vertex> points) {
		if (points.size() != 4) throw new IllegalArgumentException("A geometric tetrahedron needs four vertices");
		var result = new ArrayList<LiquidExactPolygon>();
		for (int f = 0; f < 4; f++) {
			var polygon = new ArrayList<Vertex>(); for (int i = 0; i < 4; i++) if (i != f) polygon.add(points.get(i));
			var n = cross(difference(polygon.get(1), polygon.getFirst()), difference(polygon.get(2), polygon.getFirst()));
			var inward = difference(points.get(f), polygon.getFirst()); var orientation = BigInteger.ZERO;
			for (int a = 0; a < 3; a++) orientation = orientation.add(n.get(a).multiply(inward.get(a)));
			if (orientation.signum() > 0) { java.util.Collections.reverse(polygon); n = n.stream().map(BigInteger::negate).toList(); }
			result.add(new LiquidExactPolygon(polygon, n));
		}
		return List.copyOf(result);
	}
	/** Signed spatial determinant scaled by the positive product of homogeneous denominators. */
	static BigInteger homogeneousTetrahedronDeterminant(List<Vertex> points) {
		if (points.size() != 4) throw new IllegalArgumentException("A geometric tetrahedron needs four vertices");
		var determinant = BigInteger.ZERO;
		for (int omitted = 0; omitted < 4; omitted++) {
			var minor = new Vertex[3]; for (int i = 0, out = 0; i < 4; i++) if (i != omitted) minor[out++] = points.get(i);
			var a = minor[0]; var b = minor[1]; var c = minor[2];
			var triple = a.x().multiply(b.y().multiply(c.z()).subtract(b.z().multiply(c.y())))
					.add(a.y().multiply(b.z().multiply(c.x()).subtract(b.x().multiply(c.z()))))
					.add(a.z().multiply(b.x().multiply(c.y()).subtract(b.y().multiply(c.x()))));
			var contribution = points.get(omitted).w().multiply(triple);
			determinant = omitted % 2 == 0 ? determinant.add(contribution) : determinant.subtract(contribution);
		}
		return determinant;
	}
	static Fraction tetrahedronVolume(List<Vertex> points) {
		var determinant = homogeneousTetrahedronDeterminant(points); var denominator = BigInteger.valueOf(6);
		for (var point : points) denominator = denominator.multiply(point.w());
		return new Fraction(determinant.abs(), denominator);
	}
	static Measure tetrahedronMeasure(List<Vertex> points) {
		var volume = tetrahedronVolume(points); var x = points.stream().map(Vertex::coordinates).toList(); var moment = new Fraction[3];
		for (int i = 0; i < 3; i++) { var sum = Fraction.ZERO; for (var p : x) sum = sum.add(p[i]); moment[i] = volume.multiply(sum).divide(Fraction.of(4)); }
		return new Measure(volume, moment);
	}
	Fraction[] areaVector() {
		var cached = integratedArea; if (cached != null) return cached.toArray(Fraction[]::new);
		var corners = integrationCorners();
		var result = new Fraction[] {Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; var origin = corners.getFirst();
		for (int i = 1; i + 1 < corners.size(); i++) {
			var vector = triangleCross(origin, corners.get(i), corners.get(i + 1));
			for (int a = 0; a < 3; a++) result[a] = result[a].add(vector[a].divide(Fraction.of(2)));
		}
		integratedArea = List.of(result);
		return result;
	}
	/** Collinear edge subdivisions carry no additional area or first moment. Mesh incidence retains every vertex. */
	private List<Vertex> integrationCorners() {
		var cached = integrationCorners; if (cached != null) return cached;
		var corners = new ArrayList<Vertex>();
		for (var vertex : vertices) {
			while (corners.size() >= 2 && collinear(corners.get(corners.size() - 2), corners.getLast(), vertex)) corners.removeLast();
			corners.add(vertex);
		}
		while (corners.size() > 2) {
			if (collinear(corners.get(corners.size() - 2), corners.getLast(), corners.getFirst())) corners.removeLast();
			else if (collinear(corners.getLast(), corners.getFirst(), corners.get(1))) corners.removeFirst();
			else break;
		}
		return integrationCorners = List.copyOf(corners);
	}
	private static boolean collinear(Vertex a, Vertex b, Vertex c) {
		return cross(difference(b, a), difference(c, a)).stream().allMatch(value -> value.signum() == 0);
	}
	private static Fraction[] triangleCross(Vertex a, Vertex b, Vertex c) {
		var result = new Fraction[3]; var denominator = a.w().multiply(b.w()).multiply(c.w());
		for (int i = 0; i < 3; i++) {
			int j = (i + 1) % 3, k = (i + 2) % 3;
			var numerator = a.w().multiply(b.coordinate(j).multiply(c.coordinate(k)).subtract(b.coordinate(k).multiply(c.coordinate(j))))
					.add(b.w().multiply(c.coordinate(j).multiply(a.coordinate(k)).subtract(c.coordinate(k).multiply(a.coordinate(j)))))
					.add(c.w().multiply(a.coordinate(j).multiply(b.coordinate(k)).subtract(a.coordinate(k).multiply(b.coordinate(j)))));
			result[i] = new Fraction(numerator, denominator);
		}
		return result;
	}
	LiquidPolyhedron.Surface surface() {
		var vector = areaVector(); var n = new LiquidPolyhedron.Point(vector[0].rounded(), vector[1].rounded(), vector[2].rounded());
		return new LiquidPolyhedron.Surface(Math.hypot(Math.hypot(n.x(), n.y()), n.z()), n, centroid());
	}
	static final class Measure {
		private final Fraction volume; private final Fraction[] moment;
		Measure(Fraction volume, Fraction[] moment) { this.volume = volume; this.moment = moment; }
		int signum() { return volume.numerator.signum(); }
		Fraction volume() { return volume; }
		Fraction firstMoment(int axis) { return moment[axis]; }
		Measure add(Measure other) {
			return new Measure(volume.add(other.volume), new Fraction[] {moment[0].add(other.moment[0]), moment[1].add(other.moment[1]), moment[2].add(other.moment[2])});
		}
		boolean same(Measure other) { return volume.equals(other.volume) && java.util.Arrays.equals(moment, other.moment); }
		boolean isVolume(Measure other) { return volume.equals(other.volume); }
		boolean isSum(Measure a, Measure b) { return same(a.add(b)); }
		LiquidPolyhedron.Moments moments() {
			return new LiquidPolyhedron.Moments(Vertex.ratio(volume.numerator, volume.denominator), Vertex.ratio(moment[0].numerator, moment[0].denominator),
					Vertex.ratio(moment[1].numerator, moment[1].denominator), Vertex.ratio(moment[2].numerator, moment[2].denominator));
		}
		LiquidPolyhedron.Point centroid() {
			var coordinates = centroidCoordinates(); return new LiquidPolyhedron.Point(coordinates[0].rounded(), coordinates[1].rounded(), coordinates[2].rounded());
		}
		Fraction[] centroidCoordinates() {
			if (signum() <= 0) throw new IllegalArgumentException("Empty exact control-volume centroid"); var result = new Fraction[3];
			for (int coordinate = 0; coordinate < 3; coordinate++) {
				result[coordinate] = moment[coordinate].divide(volume);
			}
			return result;
		}
	}
	static Measure measure(List<LiquidExactPolygon> faces) {
		var volume = Fraction.ZERO; Fraction[] moment = {Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		for (var face : faces) {
			var points = face.integrationCorners();
			for (int i = 1; i + 1 < points.size(); i++) {
				var a = points.getFirst(); var b = points.get(i); var c = points.get(i + 1);
				var determinant = a.x().multiply(b.y().multiply(c.z()).subtract(b.z().multiply(c.y())))
						.add(a.y().multiply(b.z().multiply(c.x()).subtract(b.x().multiply(c.z()))))
						.add(a.z().multiply(b.x().multiply(c.y()).subtract(b.y().multiply(c.x()))));
				if (determinant.signum() == 0) continue;
				var wa = b.w().multiply(c.w()); var wb = a.w().multiply(c.w()); var wc = a.w().multiply(b.w()); var w = a.w().multiply(wa);
				// Oriented cones from the origin integrate the same closed surface. Triangle first
				// moments use its three vertices directly, avoiding division by a polygon's area.
				var contribution = new Fraction(determinant, w.multiply(BigInteger.valueOf(6))); volume = volume.add(contribution);
				var denominator = contribution.denominator.multiply(w).shiftLeft(2);
				for (int axis = 0; axis < 3; axis++) {
					var sum = a.coordinate(axis).multiply(wa).add(b.coordinate(axis).multiply(wb)).add(c.coordinate(axis).multiply(wc));
					moment[axis] = moment[axis].add(new Fraction(contribution.numerator.multiply(sum), denominator));
				}
			}
		}
		return new Measure(volume, moment);
	}
	boolean hasArea() {
		if (vertices.size() < 3) return false;
		var a = vertices.getFirst();
		for (int i = 1; i + 1 < vertices.size(); i++) {
			var b = difference(vertices.get(i), a); var c = difference(vertices.get(i + 1), a);
			for (var value : cross(b, c)) if (value.signum() != 0) return true;
		}
		return false;
	}

	/** One exact arrangement of collinear mesh edges gives both incident cells identical triangles. */
	static List<LiquidExactPolygon> splitEdges(List<LiquidExactPolygon> polygons) {
		var lines = new java.util.HashMap<List<BigInteger>, java.util.TreeSet<Vertex>>();
		for (var polygon : polygons) for (int i = 0; i < polygon.vertices.size(); i++) {
			var a = polygon.vertices.get(i); var b = polygon.vertices.get((i + 1) % polygon.vertices.size()); var key = polygon.edgeLines().get(i);
			int axis = 0; while (key.get(axis).signum() == 0) axis++; final int coordinate = axis;
			var points = lines.computeIfAbsent(key, ignored -> new java.util.TreeSet<>((x, y) -> compareCoordinate(x, y, coordinate)));
			points.add(a); points.add(b);
		}
		var result = new ArrayList<LiquidExactPolygon>();
		for (var polygon : polygons) {
			var vertices = new ArrayList<Vertex>();
			for (int i = 0; i < polygon.vertices.size(); i++) {
				var a = polygon.vertices.get(i); var b = polygon.vertices.get((i + 1) % polygon.vertices.size()); var key = polygon.edgeLines().get(i);
				int axis = 0; while (key.get(axis).signum() == 0) axis++;
				var points = lines.get(key); boolean ascending = compareCoordinate(a, b, axis) < 0;
				var segment = ascending ? points.subSet(a, true, b, false) : points.subSet(b, false, a, true).descendingSet();
				vertices.addAll(segment);
			}
			if (vertices.equals(polygon.vertices)) result.add(polygon);
			else {
				// Exact collinear insertions preserve the face's plane, area and integral centroid.
				var divided = new LiquidExactPolygon(vertices, polygon.normal);
				divided.planeCoefficients = polygon.planeCoefficients; divided.supportingPlane = polygon.supportingPlane;
				divided.integratedArea = polygon.integratedArea; divided.centroidVertex = polygon.centroidVertex;
				divided.integratedCentroid = polygon.integratedCentroid; divided.integrationCorners = polygon.integrationCorners;
				result.add(divided);
			}
		}
		return List.copyOf(result);
	}
	private List<List<BigInteger>> edgeLines() {
		var cached = edgeLines; if (cached != null) return cached;
		var keys = new ArrayList<List<BigInteger>>();
		for (int i = 0; i < vertices.size(); i++) keys.add(line(vertices.get(i), vertices.get((i + 1) % vertices.size())));
		return edgeLines = List.copyOf(keys);
	}
	private static int compareCoordinate(Vertex a, Vertex b, int axis) { return a.coordinate(axis).multiply(b.w).compareTo(b.coordinate(axis).multiply(a.w)); }
	private static List<BigInteger> line(Vertex a, Vertex b) {
		var direction = difference(b, a); var moment = cross(List.of(a.x, a.y, a.z), List.of(b.x, b.y, b.z));
		var values = new ArrayList<BigInteger>(direction); values.addAll(moment); BigInteger divisor = BigInteger.ZERO;
		for (var value : values) divisor = gcd(divisor, value);
		if (divisor.signum() == 0) throw new IllegalArgumentException("Zero-length mesh edge");
		for (var value : values) if (value.signum() != 0) { if (value.signum() < 0) divisor = divisor.negate(); break; }
		final var scale = divisor; return values.stream().map(v -> v.divide(scale)).toList();
	}

	/** Certify the existing surface incidence first; ambiguous geometry uses the complete plane arrangement. */
	static List<LiquidExactPolygon> faces(LiquidPolyhedron geometry) {
		var value = geometry.exactFaces(); if (value != null) return value;
		value = calculateFaces(geometry); geometry.exactFaces(value); return value;
	}
	private static List<LiquidExactPolygon> calculateFaces(LiquidPolyhedron geometry) {
		var planes = geometry.planes(); var coefficients = planes.stream().map(p -> p.key().coefficients()).toList();
		var incidence = new java.util.LinkedHashMap<LiquidPolyhedron.Point, ArrayList<Integer>>();
		for (int f = 0; f < geometry.faces().size(); f++) for (var point : geometry.faces().get(f))
			incidence.computeIfAbsent(point, ignored -> new ArrayList<>()).add(f);
		var vertices = new java.util.HashMap<LiquidPolyhedron.Point, Vertex>(); var distinct = new java.util.HashSet<Vertex>();
		for (var entry : incidence.entrySet()) {
			var support = entry.getValue(); Vertex vertex = null;
			for (int a = 0; a < support.size() && vertex == null; a++) for (int b = 0; b < a && vertex == null; b++) for (int c = 0; c < b && vertex == null; c++)
				vertex = intersect(coefficients.get(support.get(a)), coefficients.get(support.get(b)), coefficients.get(support.get(c)));
			if (vertex == null) return completeFaces(geometry);
			for (int p = 0; p < planes.size(); p++) {
				int sign = vertex.distance(coefficients.get(p)).signum() * (planes.get(p).positive() ? 1 : -1);
				if (sign > 0 || sign != 0 && support.contains(p)) return completeFaces(geometry);
			}
			if (!distinct.add(vertex)) return completeFaces(geometry);
			vertices.put(entry.getKey(), vertex);
		}
		var result = new ArrayList<LiquidExactPolygon>(); var edges = new java.util.HashMap<List<Vertex>, Integer>();
		for (int f = 0; f < planes.size(); f++) {
			var face = geometry.faces().get(f).stream().map(vertices::get).toList(); var n = orientedNormal(planes.get(f)); boolean area = false;
			for (int v = 0; v < face.size(); v++) {
				var a = face.get(v); var b = face.get((v + 1) % face.size()); var c = face.get((v + 2) % face.size());
				var cross = cross(difference(b, a), difference(c, b)); var orientation = BigInteger.ZERO;
				for (int axis = 0; axis < 3; axis++) orientation = orientation.add(n.get(axis).multiply(cross.get(axis)));
				if (orientation.signum() < 0 || a.equals(b)) return completeFaces(geometry);
				area |= orientation.signum() > 0; edges.merge(List.of(a, b), 1, Math::addExact);
			}
			if (!area) return completeFaces(geometry);
			result.add(new LiquidExactPolygon(face, n));
		}
		for (var edge : edges.entrySet()) if (edge.getValue() != 1 || edges.getOrDefault(edge.getKey().reversed(), 0) != 1) return completeFaces(geometry);
		return List.copyOf(result);
	}
	private static List<LiquidExactPolygon> completeFaces(LiquidPolyhedron geometry) {
		var result = new ArrayList<LiquidExactPolygon>();
		for (int f = 0; f < geometry.faces().size(); f++) result.add(face(geometry, f));
		return List.copyOf(result);
	}
	private static List<BigInteger> orientedNormal(LiquidGeometryPlane plane) {
		var normal = new ArrayList<>(plane.key().coefficients().subList(0, 3));
		if (!plane.positive()) normal.replaceAll(BigInteger::negate);
		return List.copyOf(normal);
	}

	static LiquidExactPolygon face(LiquidPolyhedron geometry, int face) {
		var planes = geometry.planes(); var coefficients = planes.stream().map(p -> p.key().coefficients()).toList();
		var points = new LinkedHashSet<Vertex>(); var selected = coefficients.get(face);
		for (int a = 0; a < planes.size(); a++) if (a != face) for (int b = 0; b < a; b++) if (b != face) {
			var vertex = intersect(selected, coefficients.get(a), coefficients.get(b)); if (vertex == null) continue;
			boolean inside = true;
			for (int p = 0; p < planes.size() && inside; p++) inside = vertex.distance(coefficients.get(p)).signum() * (planes.get(p).positive() ? 1 : -1) <= 0;
			if (inside) points.add(vertex);
		}
		var normal = orientedNormal(planes.get(face));
		var ordered = new ArrayList<>(points);
		order(ordered, normal);
		return new LiquidExactPolygon(ordered, List.copyOf(normal));
	}
	private static void order(ArrayList<Vertex> points, List<BigInteger> normal) {
		if (points.size() < 3) return; int axis = 0; while (normal.get(axis).signum() == 0) axis++;
		final int u = (axis + 1) % 3, v = (axis + 2) % 3;
		points.sort((a, b) -> { int comparison = compareCoordinate(a, b, u); return comparison != 0 ? comparison : compareCoordinate(a, b, v); });
		var lower = new ArrayList<Vertex>(); var upper = new ArrayList<Vertex>();
		for (var point : points) appendHull(lower, point, axis);
		for (int i = points.size() - 1; i >= 0; i--) appendHull(upper, points.get(i), axis);
		lower.removeLast(); upper.removeLast(); lower.addAll(upper);
		if (normal.get(axis).signum() < 0) java.util.Collections.reverse(lower);
		points.clear(); points.addAll(lower);
	}
	private static void appendHull(ArrayList<Vertex> hull, Vertex point, int axis) {
		// Collinear edge vertices remain in cyclic order so both incident faces retain identical edges.
		while (hull.size() >= 2 && cross(difference(hull.getLast(), hull.get(hull.size() - 2)), difference(point, hull.get(hull.size() - 2))).get(axis).signum() < 0) hull.removeLast();
		hull.add(point);
	}
	static List<LiquidExactPolygon> clipVolume(List<LiquidExactPolygon> faces, LiquidGeometryPlane plane) {
		return clipVolume(faces, plane.key().coefficients(), plane.positive() ? 1 : -1);
	}
	static List<LiquidExactPolygon> clipVolume(List<LiquidExactPolygon> faces, List<BigInteger> coefficients, int sign) {
		boolean inside = false, outside = false;
		for (var face : faces) for (var vertex : face.vertices) { int distance = sign * vertex.distance(coefficients).signum(); inside |= distance < 0; outside |= distance > 0; }
		if (!outside) return faces; if (!inside) return List.of();
		var result = new ArrayList<LiquidExactPolygon>(); var cap = new LinkedHashSet<Vertex>();
		for (var face : faces) {
			var clipped = face.clip(coefficients, sign < 0); if (!clipped.hasArea()) continue; result.add(clipped);
			for (var vertex : clipped.vertices) if (vertex.distance(coefficients).signum() == 0) cap.add(vertex);
		}
		var normal = coefficients.subList(0, 3).stream().map(n -> sign < 0 ? n.negate() : n).toList(); var ordered = new ArrayList<>(cap); order(ordered, normal);
		var surface = new LiquidExactPolygon(ordered, normal); if (surface.hasArea()) result.add(surface);
		return List.copyOf(result);
	}
	boolean inside(List<BigInteger> plane) { return vertices.stream().allMatch(v -> v.distance(plane).signum() <= 0); }
	List<BigInteger> coefficients() {
		var cached = planeCoefficients; if (cached != null) return cached;
		var a = vertices.getFirst(); var coefficients = new ArrayList<BigInteger>();
		for (int axis = 0; axis < 3; axis++) coefficients.add(normal.get(axis).multiply(a.w));
		coefficients.add(normal.get(0).multiply(a.x).add(normal.get(1).multiply(a.y)).add(normal.get(2).multiply(a.z)));
		var divisor = BigInteger.ZERO; for (var value : coefficients) divisor = gcd(divisor, value);
		for (int i = 0; i < 4; i++) coefficients.set(i, coefficients.get(i).divide(divisor));
		return planeCoefficients = List.copyOf(coefficients);
	}
	LiquidGeometryPlane plane() {
		var cached = supportingPlane; if (cached == null) supportingPlane = cached = LiquidGeometryPlane.exact(coefficients()); return cached;
	}
	LiquidExactPolygon transformed(List<Fraction> map) {
		var output = new ArrayList<Vertex>();
		for (var vertex : vertices) {
			var coordinates = new Fraction[3]; var denominator = BigInteger.ONE;
			for (int i = 0; i < 3; i++) {
				var value = map.get(4 * i + 3);
				for (int j = 0; j < 3; j++) value = value.add(map.get(4 * i + j).multiply(new Fraction(vertex.coordinate(j), vertex.w)));
				coordinates[i] = value; denominator = denominator.divide(gcd(denominator, value.denominator)).multiply(value.denominator);
			}
			output.add(new Vertex(coordinates[0].numerator.multiply(denominator.divide(coordinates[0].denominator)),
					coordinates[1].numerator.multiply(denominator.divide(coordinates[1].denominator)), coordinates[2].numerator.multiply(denominator.divide(coordinates[2].denominator)), denominator));
		}
		var origin = output.getFirst(); List<BigInteger> n = List.of(BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
		for (int i = 1; i + 1 < output.size(); i++) {
			n = cross(difference(output.get(i), origin), difference(output.get(i + 1), origin)); if (n.stream().anyMatch(c -> c.signum() != 0)) break;
		}
		return new LiquidExactPolygon(output, n);
	}

	LiquidExactPolygon intersection(LiquidExactPolygon cutter) {
		if (sameSupport(cutter)) return this;
		var result = this;
		for (int edge = 0; edge < cutter.vertices.size() && result.hasArea(); edge++) result = result.clip(cutter.edgePlane(edge), false);
		return result;
	}
	LiquidExactPolygon clippedTo(LiquidConvexRegion region) {
		return clippedTo(region.faces());
	}
	LiquidExactPolygon clippedTo(List<LiquidExactPolygon> faces) {
		var result = this;
		for (var face : faces) { result = result.clip(face.coefficients(), false); if (!result.hasArea()) break; }
		return result;
	}
	List<LiquidExactPolygon> subtract(LiquidExactPolygon cutter) {
		if (sameSupport(cutter)) return List.of();
		var remaining = new ArrayList<LiquidExactPolygon>(); var inside = this;
		for (int edge = 0; edge < cutter.vertices.size() && inside.hasArea(); edge++) {
			var plane = cutter.edgePlane(edge); var outside = inside.clip(plane, true);
			if (outside.hasArea()) remaining.add(outside);
			inside = inside.clip(plane, false);
		}
		return remaining;
	}
	private boolean sameSupport(LiquidExactPolygon other) {
		return vertices.size() == other.vertices.size() && vertices.containsAll(other.vertices);
	}
	private List<BigInteger> edgePlane(int edge) {
		var a = vertices.get(edge); var b = vertices.get((edge + 1) % vertices.size());
		var outward = cross(difference(b, a), normal);
		return List.of(outward.get(0).multiply(a.w), outward.get(1).multiply(a.w), outward.get(2).multiply(a.w),
				outward.get(0).multiply(a.x).add(outward.get(1).multiply(a.y)).add(outward.get(2).multiply(a.z)));
	}
	List<List<BigInteger>> edgePlanes() {
		var result = new ArrayList<List<BigInteger>>(); for (int edge = 0; edge < vertices.size(); edge++) result.add(edgePlane(edge)); return List.copyOf(result);
	}
	private LiquidExactPolygon clip(List<BigInteger> plane, boolean reverse) {
		if (vertices.isEmpty()) return this;
		var output = new ArrayList<Vertex>(); var previous = vertices.getLast(); var before = previous.distance(plane);
		for (var current : vertices) {
			var after = current.distance(plane);
			if (before.signum() * after.signum() < 0) output.add(new Vertex(after.multiply(previous.x).subtract(before.multiply(current.x)),
					after.multiply(previous.y).subtract(before.multiply(current.y)), after.multiply(previous.z).subtract(before.multiply(current.z)),
					after.multiply(previous.w).subtract(before.multiply(current.w))));
			if (after.signum() * (reverse ? -1 : 1) <= 0) output.add(current);
			previous = current; before = after;
		}
		var result = new LiquidExactPolygon(output, normal);
		// Clipping changes the finite polygon and preserves its original supporting plane exactly.
		result.planeCoefficients = coefficients();
		result.supportingPlane = plane();
		return result;
	}
	private static Vertex intersect(List<BigInteger> a, List<BigInteger> b, List<BigInteger> c) {
		var bc = cross(b, c); var ca = cross(c, a); var ab = cross(a, b);
		var determinant = a.get(0).multiply(bc.get(0)).add(a.get(1).multiply(bc.get(1))).add(a.get(2).multiply(bc.get(2)));
		if (determinant.signum() == 0) return null;
		var coordinates = new BigInteger[3];
		for (int axis = 0; axis < 3; axis++) coordinates[axis] = a.get(3).multiply(bc.get(axis)).add(b.get(3).multiply(ca.get(axis))).add(c.get(3).multiply(ab.get(axis)));
		return new Vertex(coordinates[0], coordinates[1], coordinates[2], determinant);
	}
	private static List<BigInteger> difference(Vertex b, Vertex a) {
		return List.of(b.x.multiply(a.w).subtract(a.x.multiply(b.w)), b.y.multiply(a.w).subtract(a.y.multiply(b.w)), b.z.multiply(a.w).subtract(a.z.multiply(b.w)));
	}
	private static List<BigInteger> cross(List<BigInteger> a, List<BigInteger> b) {
		return List.of(a.get(1).multiply(b.get(2)).subtract(a.get(2).multiply(b.get(1))), a.get(2).multiply(b.get(0)).subtract(a.get(0).multiply(b.get(2))),
				a.get(0).multiply(b.get(1)).subtract(a.get(1).multiply(b.get(0))));
	}
}
