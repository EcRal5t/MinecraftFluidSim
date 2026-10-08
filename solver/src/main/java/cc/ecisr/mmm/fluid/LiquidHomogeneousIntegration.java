package cc.ecisr.mmm.fluid;

import java.math.BigInteger;
import java.util.List;

/** Exact polygon integrals and oriented surface tetrahedra on local homogeneous coordinates. */
final class LiquidHomogeneousIntegration {
	private record Coordinates(BigInteger x, BigInteger y, BigInteger z) {
		BigInteger at(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
		BigInteger cross(Coordinates other, int axis) {
			int j = (axis + 1) % 3, k = (axis + 2) % 3;
			return at(j).multiply(other.at(k)).subtract(at(k).multiply(other.at(j)));
		}
		BigInteger triple(Coordinates b, Coordinates c) {
			return x.multiply(b.cross(c, 0)).add(y.multiply(b.cross(c, 1))).add(z.multiply(b.cross(c, 2)));
		}
	}
	static LiquidExactPolygon.Fraction[] area(List<LiquidExactPolygon.Vertex> points) {
		var area = new LiquidExactPolygon.Fraction[]{LiquidExactPolygon.Fraction.ZERO, LiquidExactPolygon.Fraction.ZERO, LiquidExactPolygon.Fraction.ZERO};
		for (int i = 0; i < points.size(); i++) {
			var a = points.get(i); var b = points.get((i + 1) % points.size());
			var x = coordinates(a); var y = coordinates(b); var denominator = a.w().multiply(b.w()).shiftLeft(1);
			for (int axis = 0; axis < 3; axis++) area[axis] = area[axis].add(new LiquidExactPolygon.Fraction(x.cross(y, axis), denominator));
		}
		return area;
	}
	static LiquidExactPolygon.Fraction[] centroid(List<LiquidExactPolygon.Vertex> points, int axis) {
		var a = points.getFirst(); var x = coordinates(a); var position = a.coordinates();
		var weight = LiquidExactPolygon.Fraction.ZERO; var moment = new LiquidExactPolygon.Fraction[]{weight, weight, weight};
		for (int i = 1; i + 1 < points.size(); i++) {
			var b = points.get(i); var c = points.get(i + 1); var y = coordinates(b); var z = coordinates(c);
			var determinant = a.w().multiply(y.cross(z, axis)).subtract(b.w().multiply(x.cross(z, axis))).add(c.w().multiply(x.cross(y, axis)));
			var area = new LiquidExactPolygon.Fraction(determinant, a.w().multiply(b.w()).multiply(c.w())); weight = weight.add(area);
			var second = b.coordinates(); var third = c.coordinates();
			for (int coordinate = 0; coordinate < 3; coordinate++) moment[coordinate] = moment[coordinate]
					.add(area.multiply(position[coordinate].add(second[coordinate]).add(third[coordinate])));
		}
		if (weight.signum() == 0) throw new IllegalArgumentException("Empty exact polygon centroid");
		// Projected oriented areas supply the same planar weights; each triangle's centroid is (a+b+c)/3.
		var divisor = weight.multiply(LiquidExactPolygon.Fraction.of(3));
		for (int coordinate = 0; coordinate < 3; coordinate++) moment[coordinate] = moment[coordinate].divide(divisor);
		return moment;
	}
	static LiquidExactPolygon.Measure measure(List<List<LiquidExactPolygon.Vertex>> faces) {
		var volume = LiquidExactPolygon.Fraction.ZERO; var moment = new LiquidExactPolygon.Fraction[]{volume, volume, volume};
		for (var face : faces) {
			if (face.size() < 3) continue; var a = face.getFirst(); var x = a.coordinates();
			for (int i = 1; i + 1 < face.size(); i++) {
				var b = face.get(i); var c = face.get(i + 1); var contribution = cone(a, b, c); volume = volume.add(contribution);
				var y = b.coordinates(); var z = c.coordinates();
				for (int axis = 0; axis < 3; axis++) moment[axis] = moment[axis]
						.add(contribution.multiply(x[axis].add(y[axis]).add(z[axis])).divide(LiquidExactPolygon.Fraction.of(4)));
			}
		}
		// The signed tetrahedron (0,a,b,c) has volume det(a,b,c)/6 and centroid (a+b+c)/4.
		return new LiquidExactPolygon.Measure(volume, moment);
	}
	static LiquidExactPolygon.Fraction volume(List<List<LiquidExactPolygon.Vertex>> faces) {
		var volume = LiquidExactPolygon.Fraction.ZERO;
		for (var face : faces) {
			if (face.size() < 3) continue; var a = face.getFirst();
			for (int i = 1; i + 1 < face.size(); i++)
				volume = volume.add(cone(a, face.get(i), face.get(i + 1)));
		}
		return volume;
	}
	private static LiquidExactPolygon.Fraction cone(LiquidExactPolygon.Vertex a, LiquidExactPolygon.Vertex b, LiquidExactPolygon.Vertex c) {
		var x = coordinates(a); var y = coordinates(b); var z = coordinates(c);
		return new LiquidExactPolygon.Fraction(x.triple(y, z), a.w().multiply(b.w()).multiply(c.w()).multiply(BigInteger.valueOf(6)));
	}
	private static Coordinates coordinates(LiquidExactPolygon.Vertex point) { return new Coordinates(point.x(), point.y(), point.z()); }
}
