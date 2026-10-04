package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** Convex liquid geometry with volume and first moments integrated over the same faces. */
final class LiquidPolyhedron {
	static final LiquidPolyhedron EMPTY = new LiquidPolyhedron(List.of());
	record Point(double x, double y, double z) {
		Point {
			if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
				throw new IllegalArgumentException("Non-finite liquid vertex");
		}
		double coordinate(int axis) { return switch (axis) { case 0 -> x; case 1 -> y; case 2 -> z; default -> throw new IllegalArgumentException("Invalid axis"); }; }
		Point subtract(Point p) { return new Point(x - p.x, y - p.y, z - p.z); }
		Point cross(Point p) { return new Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x); }
		double dot(Point p) { return x * p.x + y * p.y + z * p.z; }
	}
	record Moments(double volume, double x, double y, double z) {
		static final Moments ZERO = new Moments(0, 0, 0, 0);
		Moments add(Moments other) { return new Moments(volume + other.volume, x + other.x, y + other.y, z + other.z); }
		double centroid(int axis) {
			if (!(volume > 0)) throw new IllegalStateException("Empty liquid geometry has no centroid");
			return switch (axis) { case 0 -> x / volume; case 1 -> y / volume; case 2 -> z / volume; default -> throw new IllegalArgumentException("Invalid axis"); };
		}
	}
	record Surface(double area, Point areaVector, Point centroid) { }
	private record Edge(Point first, Point second) {
		static Edge ordered(Point a, Point b) { return compare(a, b) < 0 ? new Edge(a, b) : new Edge(b, a); }
	}
	private final List<List<Point>> faces;
	private volatile Moments integratedMoments;
	private LiquidPolyhedron(List<List<Point>> faces) { this.faces = List.copyOf(faces); }
	static LiquidPolyhedron restored(List<List<Point>> faces) {
		var copy = new ArrayList<List<Point>>();
		for (var face : faces) copy.add(List.copyOf(face));
		return new LiquidPolyhedron(copy);
	}
	List<List<Point>> faces() { return faces; }

	List<Surface> surfaces() {
		var result = new ArrayList<Surface>();
		for (var face : faces) {
			Surface surface = surface(face);
			if (surface.area > 0) result.add(surface);
		}
		return List.copyOf(result);
	}

	static Surface surface(List<Point> face) {
		Point origin = face.getFirst();
		double area = 0, nx = 0, ny = 0, nz = 0, x = 0, y = 0, z = 0;
		for (int i = 1; i + 1 < face.size(); i++) {
			Point b = face.get(i).subtract(origin), c = face.get(i + 1).subtract(origin), vector = b.cross(c);
			double triangle = 0.5 * Math.sqrt(vector.dot(vector));
			area += triangle; nx += 0.5 * vector.x; ny += 0.5 * vector.y; nz += 0.5 * vector.z;
			x += triangle * (b.x + c.x) / 3; y += triangle * (b.y + c.y) / 3; z += triangle * (b.z + c.z) / 3;
		}
		return new Surface(area, new Point(nx, ny, nz), area > 0
				? new Point(origin.x + x / area, origin.y + y / area, origin.z + z / area) : origin);
	}

	LiquidPolyhedron scaled(double scale) {
		if (!(scale > 0) || !Double.isFinite(scale)) throw new IllegalArgumentException("Invalid liquid geometry scale");
		var output = new ArrayList<List<Point>>();
		for (var face : faces) {
			var transformed = new ArrayList<Point>();
			for (Point point : face) transformed.add(new Point(point.x * scale, point.y * scale, point.z * scale));
			output.add(List.copyOf(transformed));
		}
		return new LiquidPolyhedron(output);
	}

	LiquidPolyhedron translated(double dx, double dy, double dz) {
		var output = new ArrayList<List<Point>>();
		for (var face : faces) {
			var moved = new ArrayList<Point>();
			for (Point point : face) moved.add(new Point(point.x + dx, point.y + dy, point.z + dz));
			output.add(List.copyOf(moved));
		}
		return new LiquidPolyhedron(output);
	}

	LiquidPolyhedron transformed(double[] matrix, Point center, Point displacement) {
		if (matrix.length != 9) throw new IllegalArgumentException("Invalid liquid characteristic matrix");
		double determinant = matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7])
				- matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6]) + matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6]);
		if (!(determinant > 0) || !Double.isFinite(determinant)) throw new IllegalStateException("Liquid characteristic reverses or collapses its geometry");
		var vertices = new HashMap<Point, Point>(); var output = new ArrayList<List<Point>>();
		for (var face : faces) {
			var mapped = new ArrayList<Point>();
			for (Point point : face) mapped.add(vertices.computeIfAbsent(point, p -> {
				var d = p.subtract(center);
				return new Point(center.x + displacement.x + matrix[0] * d.x + matrix[1] * d.y + matrix[2] * d.z,
						center.y + displacement.y + matrix[3] * d.x + matrix[4] * d.y + matrix[5] * d.z,
						center.z + displacement.z + matrix[6] * d.x + matrix[7] * d.y + matrix[8] * d.z);
			}));
			output.add(List.copyOf(mapped));
		}
		return new LiquidPolyhedron(output);
	}

	static LiquidPolyhedron box(double x0, double y0, double z0, double x1, double y1, double z1) {
		if (!(x1 > x0 && y1 > y0 && z1 > z0)) throw new IllegalArgumentException("Empty liquid box");
		Point[] p = new Point[8];
		for (int i = 0; i < 8; i++) p[i] = new Point((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
		return new LiquidPolyhedron(List.of(List.of(p[0], p[4], p[6], p[2]), List.of(p[1], p[3], p[7], p[5]),
				List.of(p[0], p[1], p[5], p[4]), List.of(p[2], p[6], p[7], p[3]),
				List.of(p[0], p[2], p[3], p[1]), List.of(p[4], p[5], p[7], p[6])));
	}

	/** Intersects with n dot x <= offset. Shared original edges produce identical new vertices. */
	LiquidPolyhedron clip(double nx, double ny, double nz, double offset) {
		Point n = new Point(nx, ny, nz);
		if (!Double.isFinite(offset) || !(n.dot(n) > 0) || !Double.isFinite(n.dot(n)))
			throw new IllegalArgumentException("Invalid liquid clipping plane");
		var output = new ArrayList<List<Point>>();
		var cap = new LinkedHashSet<Point>();
		var intersections = new HashMap<Edge, Point>();
		boolean removed = false;
		for (var face : faces) {
			var polygon = new ArrayList<Point>();
			Point previous = face.getLast(); double before = n.dot(previous) - offset;
			for (Point current : face) {
				double after = n.dot(current) - offset;
				removed |= after > 0;
				if ((before < 0 && after > 0) || (before > 0 && after < 0)) {
					Point crossing = intersections.computeIfAbsent(Edge.ordered(previous, current), edge -> {
						double a = n.dot(edge.first) - offset, b = n.dot(edge.second) - offset;
						double t = a / (a - b);
						double x = Math.fma(t, edge.second.x - edge.first.x, edge.first.x);
						double y = Math.fma(t, edge.second.y - edge.first.y, edge.first.y);
						double z = Math.fma(t, edge.second.z - edge.first.z, edge.first.z);
						// Dyadic partition planes have an exact coordinate shared by both cells.
						if (ny == 0 && nz == 0) x = offset / nx;
						if (nx == 0 && nz == 0) y = offset / ny;
						if (nx == 0 && ny == 0) z = offset / nz;
						return new Point(x, y, z);
					});
					polygon.add(crossing); cap.add(crossing);
				}
				if (after <= 0) polygon.add(current);
				if (after == 0) cap.add(current);
				previous = current; before = after;
			}
			if (polygon.size() >= 3 && surface(polygon).area() > 0) output.add(List.copyOf(polygon));
		}
		if (!removed) return this;
		if (output.isEmpty()) return EMPTY;
		if (cap.size() >= 3) {
			Point center = mean(cap);
			Point tangent = Math.abs(nx) <= Math.abs(ny) && Math.abs(nx) <= Math.abs(nz)
					? n.cross(new Point(1, 0, 0)) : Math.abs(ny) <= Math.abs(nz) ? n.cross(new Point(0, 1, 0)) : n.cross(new Point(0, 0, 1));
			Point bitangent = n.cross(tangent);
			var polygon = new ArrayList<>(cap);
			polygon.sort(Comparator.comparingDouble(p -> Math.atan2(p.subtract(center).dot(bitangent), p.subtract(center).dot(tangent))));
			output.add(List.copyOf(polygon));
		}
		return new LiquidPolyhedron(output);
	}

	Moments moments() {
		Moments cached = integratedMoments; if (cached != null) return cached;
		return integratedMoments = integrateMoments();
	}
	private Moments integrateMoments() {
		if (faces.isEmpty()) return Moments.ZERO;
		var vertices = new LinkedHashSet<Point>(); for (var face : faces) vertices.addAll(face);
		Point origin = mean(vertices);
		double volume = 0, x = 0, y = 0, z = 0;
		for (var face : faces) {
			Point a = face.getFirst();
			for (int i = 1; i + 1 < face.size(); i++) {
				Point b = face.get(i), c = face.get(i + 1);
				double v = Math.abs(a.subtract(origin).dot(b.subtract(origin).cross(c.subtract(origin)))) / 6;
				volume += v;
				x += v * ((a.x - origin.x) + (b.x - origin.x) + (c.x - origin.x)) / 4;
				y += v * ((a.y - origin.y) + (b.y - origin.y) + (c.y - origin.y)) / 4;
				z += v * ((a.z - origin.z) + (b.z - origin.z) + (c.z - origin.z)) / 4;
			}
		}
		return new Moments(volume, x + volume * origin.x, y + volume * origin.y, z + volume * origin.z);
	}

	private static Point mean(Iterable<Point> points) {
		double x = 0, y = 0, z = 0; int count = 0;
		for (Point p : points) { x += p.x; y += p.y; z += p.z; count++; }
		return new Point(x / count, y / count, z / count);
	}
	private static int compare(Point a, Point b) {
		int c = Double.compare(a.x, b.x); if (c != 0) return c;
		c = Double.compare(a.y, b.y); return c != 0 ? c : Double.compare(a.z, b.z);
	}
}
