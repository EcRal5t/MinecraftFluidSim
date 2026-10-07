package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.List;

/** Finite planar-interface representation with exact volume and a bounded cap displacement. */
final class LiquidVolumeInterface {
	final LiquidPolyhedron geometry;
	final Fraction symmetricDifferenceVolumeBound;
	final double normalPositionError;
	private final LiquidExactPolygon cap;
	private final Fraction[] normal;
	private LiquidVolumeInterface(LiquidPolyhedron geometry, Fraction difference, double position) {
		this(geometry, difference, position, null, null);
	}
	private LiquidVolumeInterface(LiquidPolyhedron geometry, Fraction difference, double position, LiquidExactPolygon cap, Fraction[] normal) {
		this.geometry = geometry; symmetricDifferenceVolumeBound = difference; normalPositionError = position;
		this.cap = cap; this.normal = normal == null ? null : normal.clone();
	}
	/** The normal fixes the retained side. Precision bounds its offset interval relative to the open region. */
	static LiquidVolumeInterface reconstruct(LiquidPolyhedron open, Fraction volume, LiquidPolyhedron.Point normal, int bits) {
		if (open == null || volume == null || normal == null || bits < 1 || bits > 1074)
			throw new IllegalArgumentException("Invalid finite interface reconstruction");
		var n = new Fraction[]{Fraction.of(normal.x()), Fraction.of(normal.y()), Fraction.of(normal.z())};
		var norm = dot(n, n); if (norm.signum() == 0) throw new IllegalArgumentException("Absent interface normal");
		var original = LiquidConvexRegion.of(open); var capacity = original.measure().volume();
		if (capacity.signum() <= 0 || volume.signum() < 0 || volume.subtract(capacity).signum() > 0)
			throw new IllegalArgumentException("Interface volume exceeds its positive open region");
		if (volume.signum() == 0) return new LiquidVolumeInterface(LiquidPolyhedron.EMPTY, Fraction.ZERO, 0);
		if (volume.equals(capacity)) return new LiquidVolumeInterface(open, Fraction.ZERO, 0);
		Fraction lo = null, hi = null;
		for (var face : original.faces()) for (var p : face.vertices()) {
			var value = dot(n, p.coordinates());
			if (lo == null || value.subtract(lo).signum() < 0) lo = value;
			if (hi == null || value.subtract(hi).signum() > 0) hi = value;
		}
		var lower = LiquidConvexRegion.EMPTY;
		for (int i = 0; i < bits; i++) {
			var mid = lo.add(hi).divide(Fraction.of(2)); var region = clip(original, n, mid);
			int comparison = region.measure().volume().subtract(volume).signum();
			if (comparison == 0) return new LiquidVolumeInterface(region.geometry(), Fraction.ZERO, 0, cap(region, n, mid), n);
			if (comparison < 0) { lo = mid; lower = region; } else hi = mid;
		}
		if (!lower.hasVolume()) throw new LiquidCoordinateResolutionRequired("Interface offset needs finer coordinates for its positive volume");
		var cap = cap(lower, n, lo);
		var difference = volume.subtract(lower.measure().volume()); var area = cap.areaVector();
		var delta = difference.multiply(Fraction.of(3)).divide(dot(area, area));
		var x = cap.centroidCoordinates(); var displacement = new Fraction[3];
		for (int a = 0; a < 3; a++) { displacement[a] = area[a].multiply(delta); x[a] = x[a].add(displacement[a]); }
		var point = LiquidExactPolygon.vertex(x);
		for (var face : original.faces()) if (point.distance(face.coefficients()).signum() > 0)
			throw new LiquidCoordinateResolutionRequired("Finite interface cap exceeds its open space");
		var faces = new ArrayList<LiquidExactPolygon>();
		for (var face : lower.faces()) if (face != cap) faces.add(face);
		var center = LiquidExactPolygon.vertex(lower.measure().centroidCoordinates());
		for (int e = 0; e < cap.vertices().size(); e++) faces.add(LiquidExactPolygon.tetrahedron(List.of(center, point,
				cap.vertices().get(e), cap.vertices().get((e + 1) % cap.vertices().size()))).getFirst());
		// A supporting-face proof makes this one convex body, including at oblique cut-cell corners.
		for (var face : faces) for (var other : faces) for (var vertex : other.vertices())
			if (vertex.distance(face.coefficients()).signum() > 0)
				throw new LiquidCoordinateResolutionRequired("Finite interface cap needs finer convex support");
		var represented = LiquidPolyhedron.restoredExact(List.copyOf(faces));
		if (!represented.measure().volume().equals(volume)) throw new IllegalStateException("Finite interface representation lost exact volume");
		var interval = hi.subtract(lo); var offsetSquared = interval.multiply(interval).divide(norm);
		double error = upperNorm(dot(displacement, displacement)) + upperNorm(offsetSquared);
		if (!Double.isFinite(error)) throw new LiquidCoordinateResolutionRequired("Finite interface has no normal-position error bound");
		// Both the exact planar cut and this body contain the lower cut and have the same volume.
		return new LiquidVolumeInterface(represented, difference.multiply(Fraction.of(2)), Math.nextUp(error), cap, n);
	}
	/** First-moment defect is an observation for spatial refinement, independent of exact volume closure. */
	double centroidError(LiquidExactPolygon.Measure requested) {
		if (!requested.volume().equals(geometry.measure().volume()) || requested.signum() <= 0)
			throw new IllegalArgumentException("Centroid comparison requires the same positive volume");
		var a = requested.centroidCoordinates(); var b = geometry.centroidCoordinates(); var squared = Fraction.ZERO;
		for (int i = 0; i < 3; i++) { var delta = a[i].subtract(b[i]); squared = squared.add(delta.multiply(delta)); }
		return upperNorm(squared);
	}
	/** Fixed-volume planar centroid derivative: minus the cap covariance integral divided by liquid volume. */
	Fraction[][] centroidDerivative() {
		if (cap == null) throw new IllegalStateException("A partial interface is required for its orientation derivative");
		var result = new Fraction[3][3]; for (var row : result) java.util.Arrays.fill(row, Fraction.ZERO);
		var center = cap.centroidCoordinates(); var norm = dot(normal, normal);
		for (int e = 0; e < cap.vertices().size(); e++) {
			var a = cap.vertices().get(e).coordinates(); var b = cap.vertices().get((e + 1) % cap.vertices().size()).coordinates();
			for (int i = 0; i < 3; i++) { a[i] = a[i].subtract(center[i]); b[i] = b[i].subtract(center[i]); }
			var cross = new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])),
					a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))};
			var weight = dot(cross, normal).divide(norm).divide(Fraction.of(24)).divide(geometry.measure().volume()).negate();
			for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
				var covariance = a[i].add(b[i]).multiply(a[j].add(b[j])).add(a[i].multiply(a[j])).add(b[i].multiply(b[j]));
				result[i][j] = result[i][j].add(weight.multiply(covariance));
			}
		}
		return result;
	}
	private static LiquidExactPolygon cap(LiquidConvexRegion region, Fraction[] normal, Fraction offset) {
		for (var face : region.faces()) if (face.vertices().stream().allMatch(p -> dot(normal, p.coordinates()).equals(offset))) return face;
		throw new IllegalStateException("Clipped interface has no retained cap");
	}
	private static LiquidConvexRegion clip(LiquidConvexRegion region, Fraction[] n, Fraction offset) {
		return region.clip(new Fraction[]{n[0], n[1], n[2], offset});
	}
	private static Fraction dot(Fraction[] a, Fraction[] b) {
		var result = Fraction.ZERO; for (int i = 0; i < 3; i++) result = result.add(a[i].multiply(b[i])); return result;
	}
	private static double upperNorm(Fraction squared) {
		return squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded())));
	}
}
