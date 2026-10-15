package mmm.fluid;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import mmm.units.SignedDyadic;

/** Positive polynomial integration on the captured wet solids and their actual shared polygons. */
final class LiquidViscousQuadrature {
	private static final double[] GAUSS_1 = {.5}, WEIGHT_1 = {1};
	private static final double[] GAUSS_2 = {.5 - 1 / Math.sqrt(12), .5 + 1 / Math.sqrt(12)}, WEIGHT_2 = {.5,.5};
	static double[] nodes(int count) { return switch(count) { case 1 -> GAUSS_1; case 2 -> GAUSS_2; case 3 -> GAUSS_3; case 4 -> GAUSS_4; default -> throw new IllegalArgumentException("Unsupported positive integration order"); }; }
	static double[] weights(int count) { return switch(count) { case 1 -> WEIGHT_1; case 2 -> WEIGHT_2; case 3 -> WEIGHT_3; case 4 -> WEIGHT_4; default -> throw new IllegalArgumentException("Unsupported positive integration order"); }; }
	static final double[] GAUSS_3 = {.5 - Math.sqrt(.6) / 2, .5, .5 + Math.sqrt(.6) / 2};
	static final double[] WEIGHT_3 = {5.0 / 18, 4.0 / 9, 5.0 / 18};
	private static final double A = Math.sqrt((3 + 2 * Math.sqrt(1.2)) / 7), B = Math.sqrt((3 - 2 * Math.sqrt(1.2)) / 7);
	static final double[] GAUSS_4 = {(.5 - A / 2), (.5 - B / 2), (.5 + B / 2), (.5 + A / 2)};
	static final double[] WEIGHT_4 = {(18 - Math.sqrt(30)) / 72, (18 + Math.sqrt(30)) / 72,
			(18 + Math.sqrt(30)) / 72, (18 - Math.sqrt(30)) / 72};
	private final LiquidPressureGeometry.MicroSnapshot[][] pieces;
	private final Map<Object, List<double[][]>> polygons;
	private final double h;
	private final Map<Long, LiquidPressureGeometry.Quadrature> volumes = new HashMap<>();
	private final Map<Object, Map<Integer, LiquidPressureGeometry.Quadrature>> surfaces = new IdentityHashMap<>();

	LiquidViscousQuadrature(LiquidPressureGeometry.MicroSnapshot[][] pieces, Map<Object, List<double[][]>> polygons, double h) {
		this.pieces = pieces; this.polygons = polygons; this.h = h;
	}
	LiquidPressureGeometry.Quadrature volume(int cell, int support, int degree) {
		if (degree < 0 || degree > 4) throw new IllegalArgumentException("Unsupported viscous volume degree");
		return volumes.computeIfAbsent(((long) cell << 11) | (support << 3) | degree, ignored -> captureVolume(cell, support, degree));
	}
	private LiquidPressureGeometry.Quadrature captureVolume(int cell, int support, int degree) {
		var result = new Points(); double scale = h * h * h;
		if (degree == 0) {
			var sum = new double[4];
			if (pieces[cell] != null) for (int micro = 0; micro < 8; micro++)
				if ((support & 1 << micro) != 0 && pieces[cell][micro] != null)
					for (int a = 0; a < 4; a++) sum[a] += pieces[cell][micro].first[a];
			if (sum[0] > 0) result.add(sum[1]/sum[0],sum[2]/sum[0],sum[3]/sum[0],sum[0]);
			return result.finish();
		}
		boolean full = support == 255;
		for (int micro = 0; micro < 8 && full; micro++)
			full = pieces[cell] != null && pieces[cell][micro] != null && pieces[cell][micro].wetSupports.equals(List.of(List.of()));
		if (full) box(result, new double[]{0, 0, 0}, new double[]{1, 1, 1}, scale, degree);
		else if (pieces[cell] != null) for (int micro = 0; micro < 8; micro++)
			if ((support & 1 << micro) != 0 && pieces[cell][micro] != null)
				for (var convex : pieces[cell][micro].wetSupports) {
					var bounds = bounds(micro, convex);
					if (bounds != null) box(result, bounds[0], bounds[1], scale, degree);
					else {
						var polyhedron = new ConvexPolyhedron(); polyhedron.resetBox(micro, convex);
						polyhedron.quadrature(degree, (x, y, z, weight) -> result.add(x, y, z, weight * scale));
					}
				}
		return result.finish();
	}
	private static void box(Points result, double[] lower, double[] upper, double scale, int degree) {
		double x = upper[0] - lower[0], y = upper[1] - lower[1], z = upper[2] - lower[2];
		if (!(x > 0 && y > 0 && z > 0)) return;
		int count = (degree + 2) / 2; var node = nodes(count); var weight = weights(count);
		for (int i = 0; i < count; i++) for (int j = 0; j < count; j++) for (int k = 0; k < count; k++)
			result.add(Math.fma(x, node[i], lower[0] - .5), Math.fma(y, node[j], lower[1] - .5),
					Math.fma(z, node[k], lower[2] - .5), scale * x * y * z * weight[i] * weight[j] * weight[k]);
	}
	LiquidPressureGeometry.Quadrature shared(List<LiquidPressureGeometry.SharedFace> faces, int degree) {
		if (faces.isEmpty()) throw new IllegalArgumentException("Empty viscous shared face");
		var full = faces.getFirst().moments().fullFace();
		for (int i = 1; i < faces.size() && full != null; i++) full = full.add(faces.get(i).moments().fullFace());
		return joinedSurface(faces,full,faces.getFirst().axis(),1,degree);
	}
	LiquidPressureGeometry.Quadrature wall(List<LiquidPressureGeometry.Facet> faces,int degree) {
		if (faces.isEmpty()) throw new IllegalArgumentException("Empty viscous wall face");
		var first = faces.getFirst(); var full = first.moments().fullFace();
		for (var face : faces) if (face.kind() != LiquidPressureGeometry.FacetKind.WALL)
			throw new IllegalArgumentException("Viscous wall quadrature requires wall facets");
		for (int i = 1; i < faces.size() && full != null; i++) full = full.add(faces.get(i).moments().fullFace());
		double coordinate = .5*((first.microKey()/6>>first.axis()&1)+(first.sign()>0?1:0));
		return joinedSurface(faces,full,first.axis(),coordinate,degree);
	}
	private LiquidPressureGeometry.Quadrature joinedSurface(List<?> keys,LiquidPressureMoments.FullFace full,int axis,double coordinate,int degree) {
		return cachedSurface(keys,degree, () -> {
			var result = new Points();
			if (full != null && full.mask() == 15)
				addPolygon(result,new double[][]{{0,1,1,0},{0,0,1,1}},axis,coordinate,h*h,degree);
			else for (var key : keys) for (var polygon : polygons.get(key)) addPolygon(result,polygon,axis,coordinate,h*h,degree);
			return result.finish();
		});
	}
	LiquidPressureGeometry.Quadrature surface(Object key, int axis, double coordinate, int degree) {
		return cachedSurface(key,degree, () -> {
			var result = new Points();
			for (var polygon : polygons.get(key)) addPolygon(result,polygon,axis,coordinate,h*h,degree);
			return result.finish();
		});
	}
	private LiquidPressureGeometry.Quadrature cachedSurface(Object key,int degree,java.util.function.Supplier<LiquidPressureGeometry.Quadrature> capture) {
		if (degree < 0 || degree > 6) throw new IllegalArgumentException("Unsupported viscous surface degree");
		return surfaces.computeIfAbsent(key,ignored -> new HashMap<>()).computeIfAbsent(degree,ignored -> capture.get());
	}
	private static void addPolygon(Points result, double[][] polygon, int axis, double coordinate, double scale, int degree) {
		int count = polygon[0].length; if (count < 3) return;
		int first = (axis + 1) % 3, second = (axis + 2) % 3;
		double[] point = new double[3]; point[axis] = coordinate - .5;
		int ns = (degree + 2) / 2; var secondNode = nodes(ns); var secondWeight = weights(ns);
		if (rectangle(polygon)) {
			double loX = Arrays.stream(polygon[0]).min().orElseThrow(), hiX = Arrays.stream(polygon[0]).max().orElseThrow();
			double loY = Arrays.stream(polygon[1]).min().orElseThrow(), hiY = Arrays.stream(polygon[1]).max().orElseThrow();
			for (int i = 0; i < ns; i++) for (int j = 0; j < ns; j++) {
				point[first] = Math.fma(hiX - loX, secondNode[i], loX - .5); point[second] = Math.fma(hiY - loY, secondNode[j], loY - .5);
				result.add(point[0], point[1], point[2], scale * (hiX - loX) * (hiY - loY) * secondWeight[i] * secondWeight[j]);
			}
			return;
		}
		int nr = (degree + 3) / 2; var firstNode = nodes(nr); var firstWeight = weights(nr);
		for (int corner = 1; corner + 1 < count; corner++) {
			double ax = polygon[0][corner] - polygon[0][0], ay = polygon[1][corner] - polygon[1][0];
			double bx = polygon[0][corner + 1] - polygon[0][0], by = polygon[1][corner + 1] - polygon[1][0];
			double product = ay * bx, twiceArea = Math.abs(Math.fma(ax, by, -product) - Math.fma(ay, bx, -product));
			for (int i = 0; i < nr; i++) for (int j = 0; j < ns; j++) {
				double r = firstNode[i], s = (1 - r) * secondNode[j];
				point[first] = Math.fma(r, ax, Math.fma(s, bx, polygon[0][0] - .5));
				point[second] = Math.fma(r, ay, Math.fma(s, by, polygon[1][0] - .5));
				result.add(point[0], point[1], point[2], scale * twiceArea * (1 - r) * firstWeight[i] * secondWeight[j]);
			}
		}
	}
	private static boolean rectangle(double[][] polygon) {
		if (polygon[0].length != 4) return false;
		double loX = Arrays.stream(polygon[0]).min().orElseThrow(), hiX = Arrays.stream(polygon[0]).max().orElseThrow();
		double loY = Arrays.stream(polygon[1]).min().orElseThrow(), hiY = Arrays.stream(polygon[1]).max().orElseThrow(); int corners = 0;
		for (int i = 0; i < 4; i++) {
			double x = polygon[0][i], y = polygon[1][i];
			if (!(x == loX || x == hiX) || !(y == loY || y == hiY)) return false;
			corners |= 1 << ((x == hiX ? 1 : 0) | (y == hiY ? 2 : 0));
		}
		return corners == 15;
	}
	/** Axis half-spaces retain the inexpensive tensor rule, including thin positive boxes. */
	private static double[][] bounds(int micro, List<GasGeometryOverlap.HalfSpace> planes) {
		var result = new double[2][3];
		for (int a = 0; a < 3; a++) { result[0][a] = .5 * (micro >> a & 1); result[1][a] = result[0][a] + .5; }
		for (var plane : planes) {
			var normal = new SignedDyadic[]{plane.nx(), plane.ny(), plane.nz()}; int axis = -1;
			for (int a = 0; a < 3; a++) if (normal[a].signum() != 0) { if (axis >= 0) return null; axis = a; }
			if (axis < 0) { if (plane.threshold().signum() > 0) return new double[2][3]; continue; }
			double threshold = PlicComponentGeometry.ratio(plane.threshold(), normal[axis]).toDouble();
			if (normal[axis].signum() > 0) result[0][axis] = Math.max(result[0][axis], threshold);
			else result[1][axis] = Math.min(result[1][axis], threshold);
		}
		return result;
	}
	private static final class Points {
		private double[] x = new double[32], y = new double[32], z = new double[32], weight = new double[32]; private int size;
		void add(double a, double b, double c, double w) {
			if (w == 0) return;
			if (!(w > 0 && Double.isFinite(w))) throw new IllegalStateException("Invalid positive viscous quadrature weight");
			if (size == x.length) { int capacity = 2 * size; x = Arrays.copyOf(x, capacity); y = Arrays.copyOf(y, capacity); z = Arrays.copyOf(z, capacity); weight = Arrays.copyOf(weight, capacity); }
			x[size] = a; y[size] = b; z[size] = c; weight[size++] = w;
		}
		LiquidPressureGeometry.Quadrature finish() { return new LiquidPressureGeometry.Quadrature(Arrays.copyOf(x, size), Arrays.copyOf(y, size), Arrays.copyOf(z, size), Arrays.copyOf(weight, size)); }
	}
}
