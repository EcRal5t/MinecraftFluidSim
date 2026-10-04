package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Convex liquid geometry with volume and first moments integrated over the same faces. */
final class LiquidPolyhedron {
	static final LiquidPolyhedron EMPTY = new LiquidPolyhedron(List.of(), List.of());
	private volatile Point centroid;
	Point centroid() {
		var value = centroid; if (value == null) value = centroid(LiquidExactPolygon.faces(this)); return value;
	}
	Point centroid(List<LiquidExactPolygon> faces) {
		var value = centroid; if (value == null) centroid = value = LiquidExactPolygon.volumeCentroid(faces); return value;
	}
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
	private final List<List<Point>> faces;
	private final List<LiquidGeometryPlane> planes;
	private volatile List<LiquidExactPolygon> exactFaces;
	List<LiquidExactPolygon> exactFaces() { return exactFaces; }
	void exactFaces(List<LiquidExactPolygon> value) { exactFaces = value; }
	private volatile Moments integratedMoments;
	private volatile LiquidExactPolygon.Measure exactMeasure;
	LiquidExactPolygon.Fraction[] centroidCoordinates() { return measure().centroidCoordinates(); }
	private LiquidExactPolygon.Measure measure() {
		var value = exactMeasure; if (value == null) exactMeasure = value = LiquidExactPolygon.measure(LiquidExactPolygon.faces(this)); return value;
	}
	private LiquidPolyhedron(List<List<Point>> faces, List<LiquidGeometryPlane> planes) {
		if (faces.size() != planes.size()) throw new IllegalArgumentException("Liquid polygons and supporting planes differ");
		this.faces = List.copyOf(faces); this.planes = List.copyOf(planes);
	}
	static LiquidPolyhedron restored(List<List<Point>> faces, List<LiquidGeometryPlane> planes) {
		var copy = new ArrayList<List<Point>>();
		for (var face : faces) copy.add(List.copyOf(face));
		return new LiquidPolyhedron(copy, planes);
	}
	static LiquidPolyhedron restoredExact(List<LiquidExactPolygon> faces) {
		var result = new LiquidPolyhedron(faces.stream().map(LiquidExactPolygon::rounded).toList(), faces.stream().map(LiquidExactPolygon::plane).toList());
		result.exactFaces = faces; return result;
	}
	List<List<Point>> faces() { return faces; }
	List<LiquidGeometryPlane> planes() { return planes; }

	List<Surface> surfaces() {
		var result = new ArrayList<Surface>();
		for (var face : LiquidExactPolygon.faces(this)) {
			Surface surface = face.surface();
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
		if (scale == 1) return this;
		return transformed(new double[] {scale, 0, 0, 0, scale, 0, 0, 0, scale}, new Point(0, 0, 0), new Point(0, 0, 0));
	}

	LiquidPolyhedron translated(double dx, double dy, double dz) {
		return transformed(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1}, new Point(0, 0, 0), new Point(dx, dy, dz));
	}
	LiquidPolyhedron transformed(double[] matrix, Point center, Point displacement) {
		if (matrix.length != 9) throw new IllegalArgumentException("Invalid liquid characteristic matrix");
		if (faces.isEmpty()) return this;
		var map = new ArrayList<LiquidExactPolygon.Fraction>();
		for (int i = 0; i < 3; i++) {
			var offset = LiquidExactPolygon.Fraction.of(center.coordinate(i)).add(LiquidExactPolygon.Fraction.of(displacement.coordinate(i)));
			for (int j = 0; j < 3; j++) {
				var coefficient = LiquidExactPolygon.Fraction.of(matrix[3 * i + j]); map.add(coefficient);
				offset = offset.subtract(coefficient.multiply(LiquidExactPolygon.Fraction.of(center.coordinate(j))));
			}
			map.add(offset);
		}
		var mapped = LiquidConvexRegion.of(this).transformed(map);
		if (!mapped.hasVolume()) throw new IllegalStateException("Liquid characteristic collapses its geometry");
		return mapped.geometry();
	}
	static LiquidPolyhedron box(double x0, double y0, double z0, double x1, double y1, double z1) {
		if (!(x1 > x0 && y1 > y0 && z1 > z0)) throw new IllegalArgumentException("Empty liquid box");
		Point[] p = new Point[8];
		for (int i = 0; i < 8; i++) p[i] = new Point((i & 1) == 0 ? x0 : x1, (i & 2) == 0 ? y0 : y1, (i & 4) == 0 ? z0 : z1);
		return new LiquidPolyhedron(List.of(List.of(p[0], p[4], p[6], p[2]), List.of(p[1], p[3], p[7], p[5]),
				List.of(p[0], p[1], p[5], p[4]), List.of(p[2], p[6], p[7], p[3]),
				List.of(p[0], p[2], p[3], p[1]), List.of(p[4], p[5], p[7], p[6])),
				List.of(new LiquidGeometryPlane(new Point(-1, 0, 0), -x0), new LiquidGeometryPlane(new Point(1, 0, 0), x1),
						new LiquidGeometryPlane(new Point(0, -1, 0), -y0), new LiquidGeometryPlane(new Point(0, 1, 0), y1),
						new LiquidGeometryPlane(new Point(0, 0, -1), -z0), new LiquidGeometryPlane(new Point(0, 0, 1), z1)));
	}

	/** Intersects with n dot x <= offset. Shared original edges produce identical new vertices. */
	LiquidPolyhedron clip(double nx, double ny, double nz, double offset) {
		return clip(new LiquidGeometryPlane(new Point(nx, ny, nz), offset));
	}
	LiquidPolyhedron clip(LiquidGeometryPlane plane) {
		if (faces.isEmpty()) return this;
		var region = LiquidConvexRegion.of(this); var clipped = region.clip(plane);
		return clipped == region ? this : clipped.geometry();
	}
	Moments moments() {
		Moments cached = integratedMoments; if (cached != null) return cached;
		return integratedMoments = integrateMoments();
	}
	private Moments integrateMoments() {
		if (faces.isEmpty()) return Moments.ZERO;
		var measure = measure();
		centroid = measure.centroid(); return measure.moments();
	}
}
