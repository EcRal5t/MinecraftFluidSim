package cc.ecisr.mmm.fluid;

/** Moment-of-fluid orientation fit; unresolved centroid defects request spatial refinement. */
final class LiquidMomentInterface {
	record Result(LiquidVolumeInterface interfaceGeometry, LiquidPolyhedron.Point normal, double centroidError, int evaluations) { }
	static final class Work {
		final int maximum;
		int evaluations;
		Work(int maximum) { if (maximum < 1) throw new IllegalArgumentException("Missing moment fit evaluation budget"); this.maximum = maximum; }
	}
	private LiquidMomentInterface() { }
	static Result reconstruct(LiquidPolyhedron open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, int maximumEvaluations) {
		return reconstruct(java.util.List.of(open), target, initialNormal, coordinateBits, centroidTolerance, maximumEvaluations);
	}
	static Result reconstruct(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, int maximumEvaluations) {
		return reconstruct(open, target, initialNormal, coordinateBits, centroidTolerance, new Work(maximumEvaluations), true);
	}
	static Result planar(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, int maximumEvaluations) {
		return planar(open, target, initialNormal, coordinateBits, centroidTolerance, new Work(maximumEvaluations));
	}
	static Result planar(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, Work work) { return reconstruct(open, target, initialNormal, coordinateBits, centroidTolerance, work, false); }
	static Result planar(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, Work work, java.util.function.Predicate<LiquidVolumeInterface> occupancyResolved) {
		return reconstruct(open, target, initialNormal, coordinateBits, centroidTolerance, work, false, occupancyResolved);
	}
	private static Result reconstruct(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, Work work, boolean exactVolume) {
		return reconstruct(open, target, initialNormal, coordinateBits, centroidTolerance, work, exactVolume, ignored -> true);
	}
	private static Result reconstruct(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target, LiquidPolyhedron.Point initialNormal,
			int coordinateBits, double centroidTolerance, Work work, boolean exactVolume, java.util.function.Predicate<LiquidVolumeInterface> occupancyResolved) {
		if (target == null || target.signum() <= 0 || !(centroidTolerance > 0) || !Double.isFinite(centroidTolerance) || work == null || work.evaluations != 0)
			throw new IllegalArgumentException("Invalid moment-of-fluid reconstruction request");
		var capacityMeasure = LiquidVolumeInterface.measure(open); var normal = unit(initialNormal);
		var direction = capacityMeasure.centroid().subtract(target.centroid());
		if (normal.dot(direction) < 0) normal = new LiquidPolyhedron.Point(-normal.x(), -normal.y(), -normal.z());
		var current = representation(open, target, normal, coordinateBits, exactVolume, work); var capacity = capacityMeasure.volume();
		double error = current.centroidDistance(target);
		while ((!occupancyResolved.test(current) || error > centroidTolerance) && work.evaluations < work.maximum) {
			if (target.volume().equals(capacity)) break;
			var a = tangent(normal); var b = normal.cross(a); var derivative = current.centroidDerivative();
			var ja = new LiquidExactPolygon.Fraction[3]; var jb = ja.clone(); var rhs = ja.clone();
			var centroid = current.measure.centroidCoordinates(); var desired = target.centroidCoordinates();
			for (int i = 0; i < 3; i++) {
				rhs[i] = desired[i].subtract(centroid[i]); ja[i] = jb[i] = LiquidExactPolygon.Fraction.ZERO;
				for (int j = 0; j < 3; j++) {
					ja[i] = ja[i].add(derivative[i][j].multiply(LiquidExactPolygon.Fraction.of(a.coordinate(j))));
					jb[i] = jb[i].add(derivative[i][j].multiply(LiquidExactPolygon.Fraction.of(b.coordinate(j))));
				}
			}
			var aa = LiquidExactPolygon.Fraction.ZERO; var ab = aa; var bb = aa; var ar = aa; var br = aa;
			for (int i = 0; i < 3; i++) {
				aa = aa.add(ja[i].multiply(ja[i])); ab = ab.add(ja[i].multiply(jb[i])); bb = bb.add(jb[i].multiply(jb[i]));
				ar = ar.add(ja[i].multiply(rhs[i])); br = br.add(jb[i].multiply(rhs[i]));
			}
			var det = aa.multiply(bb).subtract(ab.multiply(ab));
			if (det.signum() == 0) break;
			if (det.signum() < 0) throw new IllegalStateException("Moment fit has an indefinite exact centroid metric");
			var dx = bb.multiply(ar).subtract(ab.multiply(br)).divide(det); var dy = aa.multiply(br).subtract(ab.multiply(ar)).divide(det);
			var magnitude = dx.absolute().compareTo(dy.absolute()) > 0 ? dx.absolute() : dy.absolute();
			if (magnitude.compareTo(LiquidExactPolygon.Fraction.of(1)) > 0) { dx = dx.divide(magnitude); dy = dy.divide(magnitude); }
			double x = dx.rounded(), y = dy.rounded(), length = Math.hypot(x, y);
			if (!(length > 0) || !Double.isFinite(length)) break;
			// A unit angular trust interval and monotone line search bound each orientation update.
			if (length > 1) { x /= length; y /= length; }
			boolean improved = false;
			for (double step = 1; step > 0 && work.evaluations < work.maximum; step *= 0.5) {
				var candidate = unit(new LiquidPolyhedron.Point(normal.x() + step * (x * a.x() + y * b.x()),
						normal.y() + step * (x * a.y() + y * b.y()), normal.z() + step * (x * a.z() + y * b.z())));
				if (candidate.equals(normal)) break;
				var representation = representation(open, target, candidate, coordinateBits, exactVolume, work);
				double nextError = representation.centroidDistance(target);
				if (nextError < error) { current = representation; normal = candidate; error = nextError; improved = true; break; }
			}
			if (!improved) break;
		}
		return new Result(current, normal, error, work.evaluations);
	}
	private static LiquidVolumeInterface representation(java.util.List<LiquidPolyhedron> open, LiquidExactPolygon.Measure target,
			LiquidPolyhedron.Point normal, int bits, boolean exactVolume, Work work) {
		if (work.evaluations == work.maximum) throw new IllegalStateException("Moment reconstruction exceeds its evaluation budget");
		work.evaluations++;
		return exactVolume ? LiquidVolumeInterface.reconstruct(open, target.volume(), normal, bits) : LiquidVolumeInterface.planar(open, target.volume(), normal, bits);
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
