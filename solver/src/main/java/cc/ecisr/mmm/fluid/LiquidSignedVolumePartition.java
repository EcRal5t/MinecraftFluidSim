package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Exact indicator-function cancellation turns signed convex volumes into a disjoint material partition. */
final class LiquidSignedVolumePartition {
	private record Difference(List<LiquidConvexRegion> outside, LiquidConvexRegion inside) { }
	private static final class Work {
		private long remaining;
		Work(long maximum) { if (maximum < 1) throw new IllegalArgumentException("Empty signed partition work budget"); remaining = maximum; }
		void spend(long count) {
			if (count > remaining) throw new IllegalStateException("Signed volume partition exceeds its geometric work budget");
			remaining -= count;
		}
	}
	private LiquidSignedVolumePartition() { }
	static List<LiquidConvexRegion> cancel(List<LiquidConvexRegion> positive, List<LiquidConvexRegion> negative, int maximumRegions) {
		return cancel(positive, negative, maximumRegions, 4_194_304);
	}
	static List<LiquidConvexRegion> cancel(List<LiquidConvexRegion> positive, List<LiquidConvexRegion> negative, int maximumRegions, long maximumWork) {
		var work = new Work(maximumWork);
		if (maximumRegions < 1 || (long) positive.size() + negative.size() > maximumRegions
				|| positive.stream().anyMatch(r -> !r.hasVolume()) || negative.stream().anyMatch(r -> !r.hasVolume()))
			throw new IllegalArgumentException("Incomplete signed volume partition");
		var expected = LiquidConvexRegion.EMPTY.measure();
		for (var region : positive) expected = expected.add(region.measure());
		for (var region : negative) {
			var measure = region.measure(); var moment = new LiquidExactPolygon.Fraction[3];
			for (int a = 0; a < 3; a++) moment[a] = measure.firstMoment(a).negate();
			expected = expected.add(new LiquidExactPolygon.Measure(measure.volume().negate(), moment));
		}
		var result = List.copyOf(positive);
		for (var removal : negative) {
			var remaining = List.of(removal); var retained = new ArrayList<LiquidConvexRegion>();
			for (var original : result) {
				var pieces = List.of(original); var next = new ArrayList<LiquidConvexRegion>();
				for (var request : remaining) {
					var cut = difference(request, original, work);
					next.addAll(cut.outside);
					if (cut.inside.hasVolume()) {
						var parts = new ArrayList<LiquidConvexRegion>();
						for (var piece : pieces) parts.addAll(difference(piece, request, work).outside);
						pieces = List.copyOf(parts);
					}
					if ((long) retained.size() + pieces.size() + next.size() > maximumRegions)
						throw new IllegalStateException("Signed volume partition exceeds its region budget");
				}
				remaining = List.copyOf(next); retained.addAll(pieces);
				if ((long) retained.size() + remaining.size() > maximumRegions)
					throw new IllegalStateException("Signed volume partition exceeds its region budget");
			}
			if (!remaining.isEmpty()) throw new LiquidCharacteristicTimeResolutionRequired("Transported surface has negative material winding");
			result = List.copyOf(retained);
		}
		if (expected.signum() <= 0) throw new LiquidCharacteristicTimeResolutionRequired("Transported surface has no positive material volume");
		var measured = LiquidConvexRegion.EMPTY.measure(); var cells = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var region : result) { measured = measured.add(region.measure()); cells.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, region.geometry())); }
		if (!measured.same(expected)) throw new IllegalStateException("Signed volume cancellation changed volume or first moments");
		work.spend((long) result.size() * (result.size() - 1) / 2);
		try { LiquidPhasePartition.requireDisjoint(cells); }
		catch (IllegalArgumentException overlap) { throw new LiquidCharacteristicTimeResolutionRequired("Transported surface has overlapping material winding"); }
		return result;
	}
	private static Difference difference(LiquidConvexRegion region, LiquidConvexRegion cutter, Work work) {
		var overlap = region.faces();
		for (var plane : cutter.faces()) {
			work.spend(1);
			overlap = LiquidExactPolygon.clipVolume(overlap, plane.coefficients(), 1);
			if (overlap.isEmpty()) return new Difference(List.of(region), LiquidConvexRegion.EMPTY);
		}
		if (overlap == region.faces()) return new Difference(List.of(), region);
		var inside = region.faces(); var outside = new ArrayList<LiquidConvexRegion>();
		for (var plane : cutter.faces()) {
			work.spend(2);
			var faces = LiquidExactPolygon.clipVolume(inside, plane.coefficients(), -1);
			if (!faces.isEmpty()) outside.add(faces == region.faces() ? region : LiquidConvexRegion.ofFaces(faces));
			inside = LiquidExactPolygon.clipVolume(inside, plane.coefficients(), 1); if (inside.isEmpty()) break;
		}
		return new Difference(List.copyOf(outside), LiquidConvexRegion.ofFaces(overlap));
	}
}
