package cc.ecisr.mmm.fluid;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Polynomial surface integrals on one common homogeneous denominator; each result is reduced once. */
final class LiquidHomogeneousIntegration {
	private record Coordinates(BigInteger x, BigInteger y, BigInteger z) {
		BigInteger at(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
		Coordinates subtract(Coordinates other) { return new Coordinates(x.subtract(other.x), y.subtract(other.y), z.subtract(other.z)); }
		BigInteger cross(Coordinates other, int axis) {
			int j = (axis + 1) % 3, k = (axis + 2) % 3;
			return at(j).multiply(other.at(k)).subtract(at(k).multiply(other.at(j)));
		}
		BigInteger triple(Coordinates b, Coordinates c) {
			return x.multiply(b.cross(c, 0)).add(y.multiply(b.cross(c, 1))).add(z.multiply(b.cross(c, 2)));
		}
	}
	private final BigInteger denominator;
	private final Map<LiquidExactPolygon.Vertex, Coordinates> coordinates;
	private LiquidHomogeneousIntegration(List<LiquidExactPolygon.Vertex> points) {
		var unique = new java.util.LinkedHashSet<>(points); var common = BigInteger.ONE;
		for (var point : unique) common = common.divide(LiquidIntegerArithmetic.gcd(common, point.w())).multiply(point.w());
		denominator = common; coordinates = new LinkedHashMap<>();
		for (var point : unique) {
			var scale = common.divide(point.w());
			coordinates.put(point, new Coordinates(point.x().multiply(scale), point.y().multiply(scale), point.z().multiply(scale)));
		}
	}
	static LiquidExactPolygon.Fraction[] area(List<LiquidExactPolygon.Vertex> points) {
		var integral = new LiquidHomogeneousIntegration(points); var area = new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO};
		for (int i = 0; i < points.size(); i++) {
			var a = integral.coordinates.get(points.get(i)); var b = integral.coordinates.get(points.get((i + 1) % points.size()));
			for (int axis = 0; axis < 3; axis++) area[axis] = area[axis].add(a.cross(b, axis));
		}
		var divisor = integral.denominator.pow(2).shiftLeft(1);
		return fractions(area, divisor);
	}
	static LiquidExactPolygon.Fraction[] centroid(List<LiquidExactPolygon.Vertex> points, int axis) {
		var integral = new LiquidHomogeneousIntegration(points); var a = integral.coordinates.get(points.getFirst());
		var weight = BigInteger.ZERO; var moment = new BigInteger[]{weight, weight, weight};
		for (int i = 1; i + 1 < points.size(); i++) {
			var b = integral.coordinates.get(points.get(i)); var c = integral.coordinates.get(points.get(i + 1));
			var area = b.subtract(a).cross(c.subtract(a), axis); weight = weight.add(area);
			for (int coordinate = 0; coordinate < 3; coordinate++)
				moment[coordinate] = moment[coordinate].add(area.multiply(a.at(coordinate).add(b.at(coordinate)).add(c.at(coordinate))));
		}
		if (weight.signum() == 0) throw new IllegalArgumentException("Empty exact polygon centroid");
		// Projected oriented areas supply the same planar weights; each triangle's centroid is (a+b+c)/3.
		return fractions(moment, weight.multiply(integral.denominator).multiply(BigInteger.valueOf(3)));
	}
	static LiquidExactPolygon.Measure measure(List<List<LiquidExactPolygon.Vertex>> faces) {
		var integral = new LiquidHomogeneousIntegration(faces.stream().flatMap(List::stream).toList());
		var volume = BigInteger.ZERO; var moment = new BigInteger[]{volume, volume, volume};
		for (var face : faces) {
			if (face.size() < 3) continue; var a = integral.coordinates.get(face.getFirst());
			for (int i = 1; i + 1 < face.size(); i++) {
				var b = integral.coordinates.get(face.get(i)); var c = integral.coordinates.get(face.get(i + 1));
				var determinant = a.triple(b, c); volume = volume.add(determinant);
				for (int axis = 0; axis < 3; axis++)
					moment[axis] = moment[axis].add(determinant.multiply(a.at(axis).add(b.at(axis)).add(c.at(axis))));
			}
		}
		// The signed tetrahedron (0,a,b,c) has volume det(a,b,c)/6 and centroid (a+b+c)/4.
		return new LiquidExactPolygon.Measure(new LiquidExactPolygon.Fraction(volume, integral.denominator.pow(3).multiply(BigInteger.valueOf(6))),
				fractions(moment, integral.denominator.pow(4).multiply(BigInteger.valueOf(24))));
	}
	static LiquidExactPolygon.Fraction volume(List<List<LiquidExactPolygon.Vertex>> faces) {
		var integral = new LiquidHomogeneousIntegration(faces.stream().flatMap(List::stream).toList()); var volume = BigInteger.ZERO;
		for (var face : faces) {
			if (face.size() < 3) continue; var a = integral.coordinates.get(face.getFirst());
			for (int i = 1; i + 1 < face.size(); i++)
				volume = volume.add(a.triple(integral.coordinates.get(face.get(i)), integral.coordinates.get(face.get(i + 1))));
		}
		return new LiquidExactPolygon.Fraction(volume, integral.denominator.pow(3).multiply(BigInteger.valueOf(6)));
	}
	private static LiquidExactPolygon.Fraction[] fractions(BigInteger[] numerators, BigInteger denominator) {
		var result = new LiquidExactPolygon.Fraction[3];
		for (int axis = 0; axis < 3; axis++) result[axis] = new LiquidExactPolygon.Fraction(numerators[axis], denominator);
		return result;
	}
}
