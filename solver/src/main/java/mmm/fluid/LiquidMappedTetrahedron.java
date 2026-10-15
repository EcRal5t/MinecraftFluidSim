package mmm.fluid;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import mmm.chemistry.ExactRational;
import mmm.fluid.GasGeometryOverlap.HalfSpace;
import mmm.fluid.GasGeometryOverlap.Point;
import mmm.units.SignedDyadic;

/** Positive receiver intersections and their inverse images under one affine moving simplex. */
final class LiquidMappedTetrahedron {
	/** Arrival support also requires micro; source support includes its inverse image. Moments use each cell centre. */
	record Piece(int receiver, int micro, List<HalfSpace> sourceSupport, List<HalfSpace> arrivalSupport,
	             List<ExactRational> sourceMoments, List<ExactRational> arrivalMoments) {
		Piece {
			sourceSupport = List.copyOf(sourceSupport); arrivalSupport = List.copyOf(arrivalSupport);
			sourceMoments = List.copyOf(sourceMoments); arrivalMoments = List.copyOf(arrivalMoments);
		}
	}
	private final List<Point> source, arrival;
	private final ExactRational sourceDeterminant, arrivalDeterminant;
	private final ExactRational[][] forward, inverse;

	LiquidMappedTetrahedron(List<Point> source, List<Point> arrival) {
		this.source = vertices(source); this.arrival = vertices(arrival);
		var before = edges(this.source); var after = edges(this.arrival);
		sourceDeterminant = GasGeometryOverlap.determinant(before[0], before[1], before[2]);
		arrivalDeterminant = GasGeometryOverlap.determinant(after[0], after[1], after[2]);
		if (sourceDeterminant.signum() <= 0 || arrivalDeterminant.signum() <= 0)
			throw new GasTopologyBlockedException("Moving liquid simplex has zero volume or reversed orientation");
		forward = transform(after, before, sourceDeterminant);
		inverse = transform(before, after, arrivalDeterminant);
	}
	ExactRational sourceVolume() { return sourceDeterminant.divide(6); }
	ExactRational arrivalVolume() { return arrivalDeterminant.divide(6); }
	ExactRational jacobian() { return divide(arrivalDeterminant, sourceDeterminant); }

	List<Piece> partition(int sourceCell, int sizeX, int sizeY, int sizeZ, int maximumMicroboxes) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || maximumMicroboxes <= 0)
			throw new IllegalArgumentException("Moving liquid simplex requires a positive grid and work budget");
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (sourceCell < 0 || sourceCell >= cells)
			throw new IllegalArgumentException("Moving liquid simplex requires a valid source grid");
		int[] origin = coordinate(sourceCell, sizeX, sizeY), size = {sizeX, sizeY, sizeZ};
		int[] low = new int[3], high = new int[3];
		for (int a = 0; a < 3; a++) {
			var minimum = arrival.getFirst().component(a); var maximum = minimum;
			for (var point : arrival) {
				if (point.component(a).compareTo(minimum) < 0) minimum = point.component(a);
				if (point.component(a).compareTo(maximum) > 0) maximum = point.component(a);
			}
			if (minimum.signum() < 0 || maximum.compareTo(ExactRational.of(size[a])) > 0)
				throw new GasTopologyBlockedException("Moving liquid simplex leaves the available receiver grid");
			low[a] = minimum.numerator().divide(minimum.denominator()).intValueExact();
			high[a] = maximum.numerator().add(maximum.denominator()).subtract(BigInteger.ONE)
					.divide(maximum.denominator()).intValueExact() - 1;
			for (var point : source) if (point.component(a).compareTo(ExactRational.of(origin[a])) < 0
					|| point.component(a).compareTo(ExactRational.of(origin[a] + 1)) > 0)
				throw new IllegalArgumentException("Moving liquid simplex is outside its source cell");
		}
		long microboxes = 8;
		for (int a = 0; a < 3; a++) {
			int span = high[a] - low[a] + 1;
			if (microboxes > maximumMicroboxes / span)
				throw new GasTopologyBlockedException("Moving liquid simplex exceeds its intersection work budget");
			microboxes *= span;
		}
		var result = new ArrayList<Piece>();
		var sumSource = ExactRational.ZERO; var sumArrival = ExactRational.ZERO;
		for (int z = low[2]; z <= high[2]; z++) for (int y = low[1]; y <= high[1]; y++) for (int x = low[0]; x <= high[0]; x++) {
			int[] receiver = {x, y, z}; var arrivalSupport = support(arrival, receiver);
			for (int micro = 0; micro < 8; micro++) {
				var moments = GasGeometryOverlap.normalizedSecondMoments(micro, arrivalSupport);
				if (moments[0].isZero()) continue;
				var sourceMoments = pullbackMoments(moments, origin, receiver);
				result.add(new Piece(x + sizeX * (y + sizeY * z), micro, sourceSupport(origin, receiver, micro),
						arrivalSupport, List.of(sourceMoments), List.of(moments)));
				sumSource = sumSource.add(sourceMoments[0]); sumArrival = sumArrival.add(moments[0]);
			}
		}
		if (!sumSource.equals(sourceVolume()) || !sumArrival.equals(arrivalVolume()))
			throw new IllegalStateException("Positive moving simplex intersections do not partition both volumes");
		return List.copyOf(result);
	}

	private List<HalfSpace> sourceSupport(int[] sourceOrigin, int[] receiver, int micro) {
		var result = new ArrayList<>(support(source, sourceOrigin));
		for (int a = 0; a < 3; a++) {
			var intercept = arrival.getFirst().component(a);
			for (int b = 0; b < 3; b++) intercept = intercept.add(forward[a][b].multiply(
					ExactRational.of(sourceOrigin[b]).subtract(source.getFirst().component(b))));
			var lower = ExactRational.of(2L * receiver[a] + (micro >>> a & 1)).divide(2);
			var upper = lower.add(ExactRational.ONE.divide(2));
			result.add(plane(forward[a][0], forward[a][1], forward[a][2], lower.subtract(intercept)));
			result.add(plane(forward[a][0].multiply(-1), forward[a][1].multiply(-1), forward[a][2].multiply(-1), intercept.subtract(upper)));
		}
		return List.copyOf(result);
	}
	private ExactRational[] pullbackMoments(ExactRational[] moments, int[] sourceOrigin, int[] receiver) {
		var shift = new ExactRational[3];
		for (int a = 0; a < 3; a++) {
			shift[a] = source.getFirst().component(a).subtract(ExactRational.of(2L * sourceOrigin[a] + 1).divide(2));
			for (int b = 0; b < 3; b++) shift[a] = shift[a].add(inverse[a][b].multiply(
					ExactRational.of(2L * receiver[b] + 1).divide(2).subtract(arrival.getFirst().component(b))));
		}
		var result = new ExactRational[10]; result[0] = moments[0];
		for (int a = 0; a < 3; a++) {
			result[a + 1] = shift[a].multiply(moments[0]);
			for (int b = 0; b < 3; b++) result[a + 1] = result[a + 1].add(inverse[a][b].multiply(moments[b + 1]));
		}
		for (int a = 0, entry = 4; a < 3; a++) for (int b = a; b < 3; b++, entry++) {
			var value = shift[a].multiply(shift[b]).multiply(moments[0]);
			for (int i = 0; i < 3; i++) {
				value = value.add(shift[a].multiply(inverse[b][i]).add(shift[b].multiply(inverse[a][i])).multiply(moments[i + 1]));
				for (int j = 0; j < 3; j++) value = value.add(inverse[a][i].multiply(inverse[b][j]).multiply(moments[secondIndex(i, j)]));
			}
			result[entry] = value;
		}
		var scale = divide(sourceDeterminant, arrivalDeterminant);
		for (int i = 0; i < result.length; i++) result[i] = result[i].multiply(scale);
		return result;
	}
	private static List<HalfSpace> support(List<Point> tetra, int[] origin) {
		var result = new ArrayList<HalfSpace>(4);
		for (int omitted = 0; omitted < 4; omitted++) {
			var face = new ArrayList<Point>(3);
			for (int i = 0; i < 4; i++) if (i != omitted) face.add(tetra.get(i));
			var normal = cross(face.get(1).subtract(face.get(0)), face.get(2).subtract(face.get(0)));
			if (dot(normal, tetra.get(omitted).subtract(face.get(0))).signum() < 0)
				normal = new Point(normal.x().multiply(-1), normal.y().multiply(-1), normal.z().multiply(-1));
			var threshold = dot(normal, face.get(0)).subtract(normal.x().multiply(origin[0]))
					.subtract(normal.y().multiply(origin[1])).subtract(normal.z().multiply(origin[2]));
			result.add(plane(normal.x(), normal.y(), normal.z(), threshold));
		}
		return List.copyOf(result);
	}
	private static HalfSpace plane(ExactRational x, ExactRational y, ExactRational z, ExactRational threshold) {
		var values = new ExactRational[]{x, y, z, threshold}; var common = BigInteger.ONE;
		for (var value : values) common = common.divide(common.gcd(value.denominator())).multiply(value.denominator());
		var integers = new BigInteger[4]; var divisor = BigInteger.ZERO;
		for (int i = 0; i < 4; i++) { integers[i] = values[i].numerator().multiply(common.divide(values[i].denominator())); divisor = divisor.gcd(integers[i]); }
		var dyadic = new SignedDyadic[4];
		for (int i = 0; i < 4; i++) dyadic[i] = divisor.signum() == 0 ? SignedDyadic.ZERO : new SignedDyadic(integers[i].divide(divisor), 0);
		return new HalfSpace(dyadic[0], dyadic[1], dyadic[2], dyadic[3]);
	}
	private static ExactRational[][] transform(Point[] destination, Point[] from, ExactRational determinant) {
		var rows = new Point[]{cross(from[1], from[2]), cross(from[2], from[0]), cross(from[0], from[1])};
		var result = new ExactRational[3][3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			var value = ExactRational.ZERO;
			for (int i = 0; i < 3; i++) value = value.add(destination[i].component(a).multiply(rows[i].component(b)));
			result[a][b] = divide(value, determinant);
		}
		return result;
	}
	private static List<Point> vertices(List<Point> vertices) {
		if (vertices == null || vertices.size() != 4 || vertices.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Moving liquid simplex requires four corresponding vertices");
		return List.copyOf(vertices);
	}
	private static Point[] edges(List<Point> vertices) { return new Point[]{vertices.get(1).subtract(vertices.get(0)), vertices.get(2).subtract(vertices.get(0)), vertices.get(3).subtract(vertices.get(0))}; }
	private static Point cross(Point a, Point b) { return new Point(a.y().multiply(b.z()).subtract(a.z().multiply(b.y())), a.z().multiply(b.x()).subtract(a.x().multiply(b.z())), a.x().multiply(b.y()).subtract(a.y().multiply(b.x()))); }
	private static ExactRational dot(Point a, Point b) { return a.x().multiply(b.x()).add(a.y().multiply(b.y())).add(a.z().multiply(b.z())); }
	private static ExactRational divide(ExactRational a, ExactRational b) { return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator())); }
	private static int secondIndex(int a, int b) { int low = Math.min(a, b), high = Math.max(a, b); return low == 0 ? 4 + high : low == 1 ? 6 + high : 9; }
	private static int[] coordinate(int cell, int sizeX, int sizeY) { return new int[]{cell % sizeX, cell / sizeX % sizeY, cell / (sizeX * sizeY)}; }
}
