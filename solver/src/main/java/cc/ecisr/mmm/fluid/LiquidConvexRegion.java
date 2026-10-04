package cc.ecisr.mmm.fluid;

import java.util.List;

/** Rational convex preimages retain even sub-ulp face events until the final surface is observed. */
final class LiquidConvexRegion {
	static final LiquidConvexRegion EMPTY = new LiquidConvexRegion(List.of());
	private final List<LiquidExactPolygon> faces;
	private final LiquidExactPolygon.Measure measure;
	private LiquidConvexRegion(List<LiquidExactPolygon> faces) {
		this.faces = faces; measure = LiquidExactPolygon.measure(faces);
		if (measure.signum() < 0) throw new IllegalArgumentException("Inverted exact convex region");
	}
	static LiquidConvexRegion of(LiquidPolyhedron geometry) { return new LiquidConvexRegion(LiquidExactPolygon.faces(geometry)); }
	static LiquidConvexRegion ofFaces(List<LiquidExactPolygon> faces) { return new LiquidConvexRegion(faces); }
	List<LiquidExactPolygon> faces() { return faces; }
	boolean hasVolume() { return measure.signum() > 0; }
	LiquidExactPolygon.Measure measure() { return measure; }
	LiquidConvexRegion transformed(List<LiquidExactPolygon.Fraction> map) { return new LiquidConvexRegion(faces.stream().map(f -> f.transformed(map)).toList()); }
	LiquidConvexRegion clip(LiquidGeometryPlane plane) {
		return clipped(LiquidExactPolygon.clipVolume(faces, plane));
	}
	LiquidConvexRegion clip(LiquidExactPolygon.Fraction[] coefficients) {
		var denominator = java.math.BigInteger.ONE;
		for (var coefficient : coefficients) denominator = denominator.multiply(coefficient.denominator()).divide(denominator.gcd(coefficient.denominator()));
		var integers = new java.util.ArrayList<java.math.BigInteger>();
		for (var coefficient : coefficients) integers.add(coefficient.numerator().multiply(denominator.divide(coefficient.denominator())));
		return clipped(LiquidExactPolygon.clipVolume(faces, integers, 1));
	}
	private LiquidConvexRegion clipped(List<LiquidExactPolygon> clipped) {
		if (clipped == faces) return this;
		return clipped.isEmpty() ? EMPTY : new LiquidConvexRegion(clipped);
	}
	LiquidPolyhedron geometry() {
		if (!hasVolume()) return LiquidPolyhedron.EMPTY;
		return LiquidPolyhedron.restoredExact(faces);
	}
	LiquidConvexRegion joined(LiquidConvexRegion other, LiquidConvexRegion original) {
		boolean adjacent = false;
		for (var a : faces) for (var b : other.faces) {
			var opposite = b.coefficients().stream().map(java.math.BigInteger::negate).toList();
			if (a.coefficients().equals(opposite) && a.intersection(b).hasArea()) { adjacent = true; break; }
		}
		if (!adjacent) return null;
		return joined(List.of(this, other), original);
	}
	static LiquidConvexRegion joined(List<LiquidConvexRegion> regions, LiquidConvexRegion original) {
		var candidate = original; var sum = EMPTY.measure;
		var planes = new java.util.LinkedHashSet<List<java.math.BigInteger>>();
		for (var region : regions) { sum = sum.add(region.measure); for (var face : region.faces) planes.add(face.coefficients()); }
		for (var plane : planes) {
			if (planes.contains(plane.stream().map(java.math.BigInteger::negate).toList())) continue;
			if (regions.stream().allMatch(r -> r.faces.stream().allMatch(f -> f.inside(plane))))
				candidate = candidate.clipped(LiquidExactPolygon.clipVolume(candidate.faces, plane, 1));
		}
		return candidate.measure.same(sum) ? candidate : null;
	}
}
