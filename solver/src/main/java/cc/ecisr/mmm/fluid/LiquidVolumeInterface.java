package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.List;

/** Common planar cut of disjoint convex open pieces, with exact volume and bounded cap displacement. */
final class LiquidVolumeInterface {
	private record Part(LiquidConvexRegion open, List<LiquidExactPolygon> faces, LiquidExactPolygon cap) {
		LiquidPolyhedron geometry() { return faces == open.faces() ? open.geometry() : LiquidPolyhedron.restoredExact(faces); }
	}
	private record Cut(List<Part> parts, Fraction volume) { }
	final List<LiquidPolyhedron> pieces;
	final LiquidExactPolygon.Measure measure;
	final Fraction symmetricDifferenceVolumeBound;
	final double normalPositionError;
	private final List<LiquidExactPolygon> caps;
	private final Fraction[] normal;
	private LiquidVolumeInterface(List<LiquidPolyhedron> pieces, Fraction difference, double position, List<LiquidExactPolygon> caps, Fraction[] normal) {
		this.pieces = List.copyOf(pieces); measure = measure(pieces); symmetricDifferenceVolumeBound = difference; normalPositionError = position;
		this.caps = List.copyOf(caps); this.normal = normal.clone();
	}
	LiquidPolyhedron singleGeometry() {
		if (pieces.size() > 1) throw new IllegalStateException("Interface spans multiple open pieces");
		return pieces.isEmpty() ? LiquidPolyhedron.EMPTY : pieces.getFirst();
	}
	static LiquidVolumeInterface reconstruct(LiquidPolyhedron open, Fraction volume, LiquidPolyhedron.Point normal, int bits) {
		return reconstruct(List.of(open), volume, normal, bits);
	}
	/** Open pieces share a coordinate frame and must be disjoint. The normal fixes the retained side. */
	static LiquidVolumeInterface reconstruct(List<LiquidPolyhedron> open, Fraction volume, LiquidPolyhedron.Point normal, int bits) {
		return reconstruct(open, volume, normal, bits, true);
	}
	/** Finite planar partition; the joint shared-face projection subsequently imposes all phase volumes. */
	static LiquidVolumeInterface planar(List<LiquidPolyhedron> open, Fraction volume, LiquidPolyhedron.Point normal, int bits) {
		return reconstruct(open, volume, normal, bits, false);
	}
	private static LiquidVolumeInterface reconstruct(List<LiquidPolyhedron> open, Fraction volume, LiquidPolyhedron.Point normal, int bits, boolean exactVolume) {
		if (open == null || open.isEmpty() || volume == null || normal == null || bits < 1 || bits > 1074)
			throw new IllegalArgumentException("Invalid finite interface reconstruction");
		var n = new Fraction[]{Fraction.of(normal.x()), Fraction.of(normal.y()), Fraction.of(normal.z())};
		var norm = dot(n, n); if (norm.signum() == 0) throw new IllegalArgumentException("Absent interface normal");
		var originals = open.stream().map(LiquidConvexRegion::of).toList(); var capacity = measure(open).volume();
		if (originals.stream().anyMatch(region -> !region.hasVolume()) || volume.signum() < 0 || volume.subtract(capacity).signum() > 0)
			throw new IllegalArgumentException("Interface volume exceeds its positive open region");
		if (volume.signum() == 0) return new LiquidVolumeInterface(List.of(), Fraction.ZERO, 0, List.of(), n);
		if (volume.equals(capacity)) return new LiquidVolumeInterface(open, Fraction.ZERO, 0, List.of(), n);
		Fraction lo = null, hi = null;
		for (var original : originals) for (var face : original.faces()) for (var p : face.vertices()) {
			var value = dot(n, p.coordinates());
			if (lo == null || value.subtract(lo).signum() < 0) lo = value;
			if (hi == null || value.subtract(hi).signum() > 0) hi = value;
		}
		if (!exactVolume) { lo = dyadic(lo, bits, false); hi = dyadic(hi, bits, true); }
		var lower = new Cut(List.of(), Fraction.ZERO);
		for (int i = 0; i < bits; i++) {
			var mid = lo.add(hi).divide(Fraction.of(2)); var cut = cut(originals, n, mid);
			int comparison = cut.volume.subtract(volume).signum();
			if (comparison == 0) return new LiquidVolumeInterface(cut.parts.stream().map(Part::geometry).toList(), Fraction.ZERO, 0, caps(cut), n);
			if (comparison < 0) { lo = mid; lower = cut; } else hi = mid;
		}
		if (lower.volume.signum() == 0) throw new LiquidCoordinateResolutionRequired("Interface offset needs finer coordinates for its positive volume");
		if (!exactVolume) {
			var interval = hi.subtract(lo); double error = upperNorm(interval.multiply(interval).divide(norm));
			return new LiquidVolumeInterface(lower.parts.stream().map(Part::geometry).toList(), volume.subtract(lower.volume), error, caps(lower), n);
		}
		Part selected = null; var largest = Fraction.ZERO;
		for (var part : lower.parts) if (part.cap != null) {
			var squared = dot(part.cap.areaVector(), part.cap.areaVector());
			if (squared.subtract(largest).signum() > 0) { selected = part; largest = squared; }
		}
		if (selected == null) throw new LiquidCoordinateResolutionRequired("Interface offset has no partial cap at its coordinate precision");
		var difference = volume.subtract(lower.volume); var area = selected.cap.areaVector();
		var delta = difference.multiply(Fraction.of(3)).divide(largest);
		var x = selected.cap.centroidCoordinates(); var displacement = new Fraction[3];
		for (int a = 0; a < 3; a++) { displacement[a] = area[a].multiply(delta); x[a] = x[a].add(displacement[a]); }
		var point = LiquidExactPolygon.vertex(x);
		for (var face : selected.open.faces()) if (point.distance(face.coefficients()).signum() > 0)
			throw new LiquidCoordinateResolutionRequired("Finite interface cap exceeds its open space");
		var faces = new ArrayList<LiquidExactPolygon>();
		for (var face : selected.faces) if (face != selected.cap) faces.add(face);
		var center = LiquidExactPolygon.vertex(selected.geometry().measure().centroidCoordinates());
		for (int e = 0; e < selected.cap.vertices().size(); e++) faces.add(LiquidExactPolygon.tetrahedron(List.of(center, point,
				selected.cap.vertices().get(e), selected.cap.vertices().get((e + 1) % selected.cap.vertices().size()))).getFirst());
		for (var face : faces) for (var other : faces) for (var vertex : other.vertices())
			if (vertex.distance(face.coefficients()).signum() > 0) throw new LiquidCoordinateResolutionRequired("Finite interface cap needs finer convex support");
		var geometry = new ArrayList<LiquidPolyhedron>();
		for (var part : lower.parts) geometry.add(part == selected ? LiquidPolyhedron.restoredExact(List.copyOf(faces)) : part.geometry());
		if (!measure(geometry).volume().equals(volume)) throw new IllegalStateException("Finite interface representation lost exact volume");
		var interval = hi.subtract(lo); var offsetSquared = interval.multiply(interval).divide(norm);
		double error = upperNorm(dot(displacement, displacement)) + upperNorm(offsetSquared);
		if (!Double.isFinite(error)) throw new LiquidCoordinateResolutionRequired("Finite interface has no normal-position error bound");
		// Both the exact planar cut and this union contain the lower cut and have the same volume.
		return new LiquidVolumeInterface(geometry, difference.multiply(Fraction.of(2)), Math.nextUp(error), caps(lower), n);
	}
	double centroidError(LiquidExactPolygon.Measure requested) {
		if (!requested.volume().equals(measure.volume()) || requested.signum() <= 0)
			throw new IllegalArgumentException("Centroid comparison requires the same positive volume");
		return centroidDistance(requested);
	}
	double centroidDistance(LiquidExactPolygon.Measure requested) {
		if (requested.signum() <= 0 || measure.signum() <= 0) throw new IllegalArgumentException("Centroid comparison requires positive occupied space");
		var a = requested.centroidCoordinates(); var b = measure.centroidCoordinates(); var squared = Fraction.ZERO;
		for (int i = 0; i < 3; i++) { var delta = a[i].subtract(b[i]); squared = squared.add(delta.multiply(delta)); }
		return upperNorm(squared);
	}
	private static Fraction dyadic(Fraction value, int bits, boolean upper) {
		var division = value.numerator().shiftLeft(bits).divideAndRemainder(value.denominator()); var integer = division[0];
		if (division[1].signum() != 0) {
			if (upper && value.signum() > 0) integer = integer.add(java.math.BigInteger.ONE);
			if (!upper && value.signum() < 0) integer = integer.subtract(java.math.BigInteger.ONE);
		}
		return new Fraction(integer, java.math.BigInteger.ONE.shiftLeft(bits));
	}
	/** Fixed-volume centroid derivative uses the covariance of all cap sections about their common centroid. */
	Fraction[][] centroidDerivative() {
		var result = new Fraction[3][3]; for (var row : result) java.util.Arrays.fill(row, Fraction.ZERO);
		if (caps.isEmpty()) return result;
		var center = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; var sum = Fraction.ZERO; var norm = dot(normal, normal);
		for (var cap : caps) {
			var weight = dot(cap.areaVector(), normal).divide(norm); sum = sum.add(weight); var x = cap.centroidCoordinates();
			for (int i = 0; i < 3; i++) center[i] = center[i].add(x[i].multiply(weight));
		}
		for (int i = 0; i < 3; i++) center[i] = center[i].divide(sum);
		for (var cap : caps) for (int e = 0; e < cap.vertices().size(); e++) {
			var c = cap.centroidCoordinates(); var a = cap.vertices().get(e).coordinates(); var b = cap.vertices().get((e + 1) % cap.vertices().size()).coordinates();
			var edgeA = new Fraction[3]; var edgeB = new Fraction[3];
			for (int i = 0; i < 3; i++) { edgeA[i] = a[i].subtract(c[i]); edgeB[i] = b[i].subtract(c[i]); a[i] = a[i].subtract(center[i]); b[i] = b[i].subtract(center[i]); c[i] = c[i].subtract(center[i]); }
			var weight = dot(cross(edgeA, edgeB), normal).divide(norm).divide(Fraction.of(24)).divide(measure.volume()).negate();
			for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
				var covariance = a[i].add(b[i]).add(c[i]).multiply(a[j].add(b[j]).add(c[j]))
						.add(a[i].multiply(a[j])).add(b[i].multiply(b[j])).add(c[i].multiply(c[j]));
				result[i][j] = result[i][j].add(weight.multiply(covariance));
			}
		}
		return result;
	}
	static LiquidExactPolygon.Measure measure(List<LiquidPolyhedron> geometry) {
		var result = LiquidConvexRegion.EMPTY.measure(); for (var part : geometry) result = result.add(part.measure()); return result;
	}
	private static Cut cut(List<LiquidConvexRegion> originals, Fraction[] normal, Fraction offset) {
		var parts = new ArrayList<Part>(); var volume = Fraction.ZERO;
		var denominator = java.math.BigInteger.ONE;
		for (var value : new Fraction[]{normal[0], normal[1], normal[2], offset}) denominator = denominator.divide(LiquidIntegerArithmetic.gcd(denominator, value.denominator())).multiply(value.denominator());
		var integers = new ArrayList<java.math.BigInteger>();
		for (var value : new Fraction[]{normal[0], normal[1], normal[2], offset}) integers.add(value.numerator().multiply(denominator.divide(value.denominator())));
		for (var original : originals) {
			var faces = LiquidExactPolygon.clipVolume(original.faces(), integers, 1); if (faces.isEmpty()) continue;
			LiquidExactPolygon cap = null;
			if (faces != original.faces()) for (var face : faces) if (face.vertices().stream().allMatch(p -> dot(normal, p.coordinates()).equals(offset))) { cap = face; break; }
			parts.add(new Part(original, faces, cap)); volume = volume.add(faces == original.faces() ? original.measure().volume() : LiquidExactPolygon.volume(faces));
		}
		return new Cut(List.copyOf(parts), volume);
	}
	private static List<LiquidExactPolygon> caps(Cut cut) { return cut.parts.stream().map(Part::cap).filter(java.util.Objects::nonNull).toList(); }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) {
		return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))};
	}
	private static Fraction dot(Fraction[] a, Fraction[] b) {
		var result = Fraction.ZERO; for (int i = 0; i < 3; i++) result = result.add(a[i].multiply(b[i])); return result;
	}
	private static double upperNorm(Fraction squared) { return squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded()))); }
}
