package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** A common offset of supporting planes imposes phase volume while retaining the open shell. */
final class LiquidSupportVolumeFit {
	record Result(List<LiquidPolyhedron> pieces, Fraction volumeErrorBound) {
		Result { pieces = List.copyOf(pieces); }
	}
	static final class Work {
		final int maximum; int evaluations; String context = "";
		Work(int maximum) { if (maximum < 1) throw new IllegalArgumentException("Invalid supporting volume work budget"); this.maximum = maximum; }
		void charge() {
			if (evaluations >= maximum) throw new IllegalStateException("Interface reconstruction exceeds its evaluation budget: evaluations=" + evaluations + context);
			evaluations++;
		}
	}
	private record Cut(List<LiquidPolyhedron> pieces, Fraction volume, Fraction derivative) { }
	private LiquidSupportVolumeFit() { }
	static Result fit(LiquidGeometryRefinement.Cell cell, List<LiquidPolyhedron> available,
			LiquidSupportInterface.Result support, Fraction target, Work work) {
		if (target.signum() <= 0 || work == null) throw new IllegalArgumentException("Incomplete supporting volume fit");
		var initial = LiquidVolumeInterface.measure(support.pieces()).volume();
		if (initial.equals(target)) return new Result(support.pieces(), Fraction.ZERO);
		// A defect enclosed by plane quantization is already handled by the exact joint volume projection.
		if (initial.subtract(target).absolute().compareTo(support.coordinateDifferenceVolumeBound()) <= 0
				&& initial.compareTo(LiquidVolumeInterface.measure(available).volume()) < 0)
			return new Result(support.pieces(), initial.subtract(target).absolute());
		var capacity = LiquidVolumeInterface.measure(available).volume();
		if (target.compareTo(capacity) >= 0 || support.planes().isEmpty()) return new Result(List.of(), Fraction.ZERO);
		BigInteger lo = BigInteger.ZERO, hi = BigInteger.ZERO;
		var low = new Cut(support.pieces(), initial, Fraction.ZERO); var high = low;
		for (var plane : support.planes()) {
			Fraction minimum = null, maximum = null;
			for (var face : LiquidExactPolygon.faces(cell.geometry())) for (var vertex : face.vertices()) {
				var value = Fraction.ZERO; var point = vertex.coordinates();
				for (int a = 0; a < 3; a++) value = value.add(new Fraction(plane.normal().get(a), BigInteger.ONE).multiply(point[a]));
				if (minimum == null || value.compareTo(minimum) < 0) minimum = value;
				if (maximum == null || value.compareTo(maximum) > 0) maximum = value;
			}
			var offset = new Fraction(plane.offset(), BigInteger.ONE);
			if (initial.compareTo(target) > 0) {
				var upper = offset.subtract(minimum); hi = upper.numerator().divide(upper.denominator()).add(BigInteger.TWO);
				high = new Cut(List.of(), Fraction.ZERO, Fraction.ZERO); break;
			}
			var lower = offset.subtract(maximum); lo = lo.min(lower.numerator().divide(lower.denominator()).subtract(BigInteger.TWO));
			low = new Cut(available, capacity, Fraction.ZERO);
		}
		BigInteger first = lo.add(hi).shiftRight(1), second = first;
		while (hi.subtract(lo).compareTo(BigInteger.ONE) > 0) {
			Cut nearest = null; BigInteger nearestOffset = null; Fraction distance = null;
			for (var offset : first.equals(second) ? List.of(first) : List.of(first, second)) {
				if (offset.compareTo(lo) <= 0 || offset.compareTo(hi) >= 0) continue;
				work.charge(); var trial = cut(available, support.planes(), offset);
				var error = trial.volume.subtract(target);
				if (error.signum() == 0) return new Result(trial.pieces, Fraction.ZERO);
				if (error.signum() > 0) { lo = offset; low = trial; } else { hi = offset; high = trial; }
				if (distance == null || error.absolute().compareTo(distance) < 0) { nearest = trial; nearestOffset = offset; distance = error.absolute(); }
			}
			first = lo.add(hi).shiftRight(1); second = first;
			if (nearest != null && nearest.derivative.signum() > 0) {
				var proposed = new Fraction(nearestOffset, BigInteger.ONE).add(nearest.volume.subtract(target).divide(nearest.derivative));
				var rounded = proposed.numerator().divideAndRemainder(proposed.denominator());
				var floor = rounded[0]; if (proposed.signum() < 0 && rounded[1].signum() != 0) floor = floor.subtract(BigInteger.ONE);
				if (floor.compareTo(lo) > 0 && floor.add(BigInteger.ONE).compareTo(hi) < 0) { first = floor; second = floor.add(BigInteger.ONE); }
			}
		}
		// The exact common-offset root lies between these nested convex cuts.
		var chosen = low.volume.subtract(target).compareTo(target.subtract(high.volume)) <= 0 ? low : high;
		if (chosen.volume.signum() <= 0 || chosen.volume.equals(capacity))
			throw new LiquidCoordinateResolutionRequired("Supporting volume fit needs finer positive phase coordinates");
		return new Result(chosen.pieces, low.volume.subtract(high.volume));
	}
	private static Cut cut(List<LiquidPolyhedron> available, List<LiquidSupportInterface.Plane> planes, BigInteger offset) {
		var coefficients = planes.stream().map(p -> List.of(p.normal().get(0), p.normal().get(1), p.normal().get(2), p.offset().subtract(offset))).toList();
		var keys = coefficients.stream().map(p -> LiquidGeometryPlane.exact(p).key()).collect(java.util.stream.Collectors.toSet());
		var result = new ArrayList<LiquidPolyhedron>(); var volume = Fraction.ZERO; var derivative = Fraction.ZERO;
		for (var open : available) {
			var original = LiquidExactPolygon.faces(open); var faces = original;
			for (var plane : coefficients) { faces = LiquidExactPolygon.clipVolume(faces, plane, 1); if (faces.isEmpty()) break; }
			if (faces.isEmpty()) continue;
			var geometry = faces == original ? open : LiquidPolyhedron.restoredExact(faces); result.add(geometry); volume = volume.add(geometry.measure().volume());
			for (var face : faces) if (keys.contains(face.plane().key())) {
				var normal = face.coefficients().subList(0, 3); var scaled = planes.stream().filter(p -> parallel(p.normal(), normal)).findFirst().orElseThrow().normal();
				var area = face.areaVector(); var squared = Fraction.ZERO; var dot = Fraction.ZERO;
				for (int a = 0; a < 3; a++) { var n = new Fraction(scaled.get(a), BigInteger.ONE); squared = squared.add(n.multiply(n)); dot = dot.add(area[a].multiply(n)); }
				derivative = derivative.add(dot.divide(squared));
			}
		}
		if (derivative.signum() < 0) throw new IllegalStateException("Supporting volume derivative lost its inward orientation");
		return new Cut(List.copyOf(result), volume, derivative);
	}
	private static boolean parallel(List<BigInteger> a, List<BigInteger> b) {
		int axis = 0; while (a.get(axis).signum() == 0) axis++;
		if (a.get(axis).signum() != b.get(axis).signum()) return false;
		for (int i = 0; i < 3; i++) if (!a.get(i).multiply(b.get(axis)).equals(b.get(i).multiply(a.get(axis)))) return false;
		return true;
	}
}
