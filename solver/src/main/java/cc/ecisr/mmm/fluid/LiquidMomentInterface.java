package cc.ecisr.mmm.fluid;

/** Moment-of-fluid orientation fit; unresolved centroid defects request spatial refinement. */
final class LiquidMomentInterface {
	record Result(LiquidVolumeInterface interfaceGeometry, LiquidPolyhedron.Point normal, double centroidError, int evaluations) { }
	private LiquidMomentInterface() { }
	static Result reconstruct(LiquidPolyhedron open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, int maximumEvaluations) {
		if (target == null || !(centroidTolerance > 0) || !Double.isFinite(centroidTolerance) || maximumEvaluations < 1)
			throw new IllegalArgumentException("Invalid moment-of-fluid reconstruction request");
		var normal = unit(initialNormal); var current = LiquidVolumeInterface.reconstruct(open, target.volume(), normal, coordinateBits);
		double error = current.centroidError(target); int evaluations = 1;
		while (error > centroidTolerance && evaluations < maximumEvaluations) {
			if (target.volume().equals(open.measure().volume())) break;
			var a = tangent(normal); var b = normal.cross(a); var derivative = current.centroidDerivative();
			double[] ja = new double[3], jb = ja.clone(), rhs = ja.clone();
			var centroid = current.geometry.centroidCoordinates(); var desired = target.centroidCoordinates(); double scale = 0;
			for (int i = 0; i < 3; i++) {
				rhs[i] = desired[i].subtract(centroid[i]).rounded();
				for (int j = 0; j < 3; j++) { double value = derivative[i][j].rounded(); ja[i] += value * a.coordinate(j); jb[i] += value * b.coordinate(j); }
				scale = Math.max(scale, Math.max(Math.abs(ja[i]), Math.abs(jb[i])));
			}
			if (!(scale > 0) || !Double.isFinite(scale)) break;
			double aa = 0, ab = 0, bb = 0, ar = 0, br = 0;
			for (int i = 0; i < 3; i++) {
				ja[i] /= scale; jb[i] /= scale; rhs[i] /= scale;
				aa += ja[i] * ja[i]; ab += ja[i] * jb[i]; bb += jb[i] * jb[i]; ar += ja[i] * rhs[i]; br += jb[i] * rhs[i];
			}
			double det = aa * bb - ab * ab;
			if (!(det > 32 * Math.ulp(Math.max(aa * bb, ab * ab)))) break;
			double x = (bb * ar - ab * br) / det, y = (aa * br - ab * ar) / det, length = Math.hypot(x, y);
			if (!(length > 0) || !Double.isFinite(length)) break;
			// A unit angular trust interval and monotone line search bound each orientation update.
			if (length > 1) { x /= length; y /= length; }
			boolean improved = false;
			for (double step = 1; step > 0 && evaluations < maximumEvaluations; step *= 0.5) {
				var candidate = unit(new LiquidPolyhedron.Point(normal.x() + step * (x * a.x() + y * b.x()),
						normal.y() + step * (x * a.y() + y * b.y()), normal.z() + step * (x * a.z() + y * b.z())));
				if (candidate.equals(normal)) break;
				evaluations++;
				var representation = LiquidVolumeInterface.reconstruct(open, target.volume(), candidate, coordinateBits);
				double nextError = representation.centroidError(target);
				if (nextError < error) { current = representation; normal = candidate; error = nextError; improved = true; break; }
			}
			if (!improved) break;
		}
		return new Result(current, normal, error, evaluations);
	}
	private static LiquidPolyhedron.Point tangent(LiquidPolyhedron.Point normal) {
		int axis = 0; for (int i = 1; i < 3; i++) if (Math.abs(normal.coordinate(i)) < Math.abs(normal.coordinate(axis))) axis = i;
		return unit(normal.cross(new LiquidPolyhedron.Point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0)));
	}
	private static LiquidPolyhedron.Point unit(LiquidPolyhedron.Point value) {
		if (value == null) throw new IllegalArgumentException("Absent moment-of-fluid normal");
		double length = Math.hypot(Math.hypot(value.x(), value.y()), value.z());
		if (!(length > 0) || !Double.isFinite(length)) throw new IllegalArgumentException("Invalid moment-of-fluid normal");
		return new LiquidPolyhedron.Point(value.x() / length, value.y() / length, value.z() / length);
	}
}
