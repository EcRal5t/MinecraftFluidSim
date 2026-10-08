package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Finite coordinate representation subject to the same exact control-volume constraints. */
final class LiquidCoordinateProjection {
	final Map<Vertex, Vertex> positions;
	final double maximumPositionErrorMetres;
	/** Initial spacing follows the pressure rate tolerance (1/s); actual represented rates still require certification. */
	static int fractionalBits(LiquidControlVolumeMesh mesh, double dt, double rateTolerance) {
		if (!(dt > 0) || !Double.isFinite(dt) || !(rateTolerance > 0) || !Double.isFinite(rateTolerance))
			throw new IllegalArgumentException("Invalid coordinate rate scale");
		double length = Double.POSITIVE_INFINITY;
		for (var face : mesh.faces) {
			length = Math.min(length, face.distance(mesh.cells.get(face.first()).geometry(), 1));
			if (face.second() >= 0) length = Math.min(length, face.distance(mesh.cells.get(face.second()).geometry(), -1));
		}
		if (length == Double.POSITIVE_INFINITY) return 0;
		if (!(length > 0) || !Double.isFinite(length)) throw new LiquidCoordinateResolutionRequired("Resolved geometry has no positive coordinate scale");
		var spacing = Fraction.of(length).multiply(Fraction.of(dt)).multiply(Fraction.of(rateTolerance));
		int bits = Math.min(1074, Math.max(0, spacing.denominator().bitLength() - spacing.numerator().bitLength()));
		if (bits < 1074 && new Fraction(BigInteger.ONE, BigInteger.ONE.shiftLeft(bits)).compareTo(spacing) > 0) bits++;
		return bits;
	}

	LiquidCoordinateProjection(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field,
			Map<Vertex, Vertex> reference, Fraction[] volume, int fractionalBits) {
		if (field.mesh != mesh || volume.length != mesh.cells.size() || fractionalBits < 0 || fractionalBits > 1074)
			throw new IllegalArgumentException("Invalid liquid coordinate representation");
		for (int c = 0; c < volume.length; c++) if (volume[c] != null && (!reference.containsKey(field.cellCenters.get(c)) || volume[c].signum() <= 0))
			throw new IllegalArgumentException("Coordinate constraint requires complete positive liquid support");
		for (var tet : field.tetrahedra) if (volume[tet.cell()] != null && !reference.keySet().containsAll(tet.vertices()))
			throw new IllegalArgumentException("Coordinate representation has incomplete shared nodes");
		// A common displacement has no deformation to represent. Its exact image needs no coordinate reset.
		if (commonDisplacement(reference) && java.util.stream.IntStream.range(0, volume.length)
				.allMatch(c -> volume[c] == null || volume[c].equals(mesh.cells.get(c).geometry().measure().volume()))) {
			positions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(reference)); maximumPositionErrorMetres = 0; return;
		}
		var moved = new LinkedHashMap<Vertex, Vertex>();
		for (var entry : reference.entrySet())
			moved.put(entry.getKey(), field.constrainedPosition(entry.getKey(), round(entry.getValue(), fractionalBits), entry.getValue()));
		var current = volumes(mesh, field, moved, volume);
		int count = volume.length, faces = mesh.faces.size();
		var source = new Fraction[count + 1]; Arrays.fill(source, Fraction.ZERO);
		var constrained = new boolean[count + 1];
		for (int c = 0; c < count; c++) if (volume[c] != null) {
			constrained[c] = true; source[c] = volume[c].subtract(current[c]);
		}
		var first = new int[faces]; var second = new int[faces];
		var fixed = new Fraction[faces]; var weights = new double[faces];
		var area = new Fraction[faces][];
		for (int f = 0; f < faces; f++) {
			var face = mesh.faces.get(f); first[f] = face.first(); second[f] = face.second() < 0 ? count : face.second();
			boolean liquid = volume[face.first()] != null || face.second() >= 0 && volume[face.second()] != null;
			if (!liquid || face.second() == -2) fixed[f] = Fraction.ZERO;
			if (!liquid) continue;
			area[f] = area(mesh.exactPolygons.get(f).vertices().stream().map(moved::get).toList());
			var squared = dot(area[f], area[f]);
			if (squared.signum() == 0) throw new LiquidCoordinateResolutionRequired("Coordinate precision collapsed a liquid face");
			weights[f] = 1 / squared.rounded();
			if (fixed[f] == null && (!(weights[f] > 0) || !Double.isFinite(weights[f])))
				throw new LiquidCoordinateResolutionRequired("Coordinate face metric exceeds the supported numerical range");
		}
		var coordinates = new ConservativeFluxCoordinates(count + 1, first, second, constrained, fixed, weights);
		var independent = new Fraction[coordinates.size()]; Arrays.fill(independent, Fraction.ZERO);
		var displacementVolume = coordinates.liftExact(independent, source);
		for (int f = 0; f < faces; f++) if (displacementVolume[f].signum() != 0) {
			var factor = displacementVolume[f].multiply(Fraction.of(3)).divide(dot(area[f], area[f]));
			var center = field.faceCenters.get(f); var next = moved.get(center).coordinates();
			for (int a = 0; a < 3; a++) next[a] = next[a].add(area[f][a].multiply(factor));
			moved.put(center, LiquidExactPolygon.vertex(next));
		}
		current = volumes(mesh, field, moved, volume);
		for (int c = 0; c < count; c++) if (volume[c] != null && !volume[c].equals(current[c]))
			throw new IllegalStateException("Finite coordinates lost a control-volume constraint");
		double error = 0;
		for (var entry : moved.entrySet()) {
			var actual = entry.getValue().coordinates(); var desired = reference.get(entry.getKey()).coordinates(); var squared = Fraction.ZERO;
			for (int a = 0; a < 3; a++) { var delta = actual[a].subtract(desired[a]); squared = squared.add(delta.multiply(delta)); }
			if (squared.signum() != 0) error = Math.max(error, Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded()))));
		}
		if (!Double.isFinite(error)) throw new IllegalArgumentException("Coordinate representation has no finite displacement bound");
		maximumPositionErrorMetres = error;
		positions = java.util.Collections.unmodifiableMap(moved);
	}
	private static boolean commonDisplacement(Map<Vertex, Vertex> points) {
		Fraction[] displacement = null;
		for (var entry : points.entrySet()) {
			var old = entry.getKey().coordinates(); var next = entry.getValue().coordinates();
			for (int a = 0; a < 3; a++) next[a] = next[a].subtract(old[a]);
			if (displacement == null) displacement = next; else if (!Arrays.equals(displacement, next)) return false;
		}
		return displacement != null;
	}

	/** The endpoint closed-surface integral is independent of the auxiliary cell apex. */
	private static Fraction[] volumes(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field,
			Map<Vertex, Vertex> moved, Fraction[] support) {
		var result = new Fraction[support.length]; Arrays.fill(result, Fraction.ZERO);
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f);
			if (support[face.first()] == null && (face.second() < 0 || support[face.second()] == null)) continue;
			var points = mesh.exactPolygons.get(f).vertices(); var center = moved.get(field.faceCenters.get(f)).coordinates();
			var integral = Fraction.ZERO;
			for (int v = 0; v < points.size(); v++) {
				var a = moved.get(points.get(v)).coordinates(); var b = moved.get(points.get((v + 1) % points.size())).coordinates();
				var cross = new Fraction[3];
				for (int k = 0; k < 3; k++) cross[k] = a[(k + 1) % 3].multiply(b[(k + 2) % 3]).subtract(a[(k + 2) % 3].multiply(b[(k + 1) % 3]));
				integral = integral.add(dot(center, cross).divide(Fraction.of(6)));
			}
			result[face.first()] = result[face.first()].add(integral);
			if (face.second() >= 0) result[face.second()] = result[face.second()].subtract(integral);
		}
		return result;
	}
	private static Vertex round(Vertex point, int bits) {
		var x = point.coordinates(); var denominator = BigInteger.ONE.shiftLeft(bits);
		for (int a = 0; a < 3; a++) {
			var quotient = x[a].numerator().abs().shiftLeft(bits).divideAndRemainder(x[a].denominator());
			int comparison = quotient[1].shiftLeft(1).compareTo(x[a].denominator());
			var numerator = quotient[0];
			if (comparison > 0 || comparison == 0 && numerator.testBit(0)) numerator = numerator.add(BigInteger.ONE);
			if (x[a].signum() < 0) numerator = numerator.negate();
			x[a] = new Fraction(numerator, denominator);
		}
		return LiquidExactPolygon.vertex(x);
	}
	private static Fraction[] area(List<Vertex> points) {
		var result = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		for (int i = 0; i < points.size(); i++) {
			var a = points.get(i).coordinates(); var b = points.get((i + 1) % points.size()).coordinates();
			for (int k = 0; k < 3; k++) result[k] = result[k].add(a[(k + 1) % 3].multiply(b[(k + 2) % 3]).subtract(a[(k + 2) % 3].multiply(b[(k + 1) % 3])));
		}
		for (int k = 0; k < 3; k++) result[k] = result[k].divide(Fraction.of(2));
		return result;
	}
	private static Fraction dot(Fraction[] a, Fraction[] b) {
		var result = Fraction.ZERO; for (int k = 0; k < 3; k++) result = result.add(a[k].multiply(b[k])); return result;
	}
}
