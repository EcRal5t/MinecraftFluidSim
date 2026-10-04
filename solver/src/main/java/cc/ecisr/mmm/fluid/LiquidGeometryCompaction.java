package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;

/** Removes auxiliary convex cuts only when actual geometry, ownership and the P0 field are identical. */
final class LiquidGeometryCompaction {
	private record FaceKey(LiquidPhasePartition.Address address, int owner, long x, long y, long z, LiquidGeometryPlane.Key plane) { }
	private record Side(int fragment, boolean positive) { }
	private record Pair(int a, int b) { }
	private LiquidGeometryCompaction() { }
	static LiquidGeometryState compact(LiquidGeometryState input, int maximumPairs) {
		if (maximumPairs < 0) throw new IllegalArgumentException("Invalid exact compaction work budget");
		if (maximumPairs == 0 || input.cells().size() < 2) return input;
		var cells = new ArrayList<>(LiquidGeometryStateMapper.restore(input));
		var owners = new ArrayList<Integer>(); var velocity = new ArrayList<double[]>();
		for (int f : input.copyFragmentOwners()) owners.add(f); for (var cell : input.cells()) velocity.add(cell.copyVelocity());
		var faces = new HashMap<FaceKey, ArrayList<Side>>(); var queue = new ArrayDeque<Pair>(); var scheduled = new HashSet<Long>();
		int next = 0, merged = 0;
		while (true) {
			while (next < cells.size() && scheduled.size() < maximumPairs) {
				int f = next++; var cell = cells.get(f); if (cell == null) continue;
				var u = velocity.get(f); var address = LiquidPhasePartition.Address.of(cell);
				for (var plane : cell.geometry().planes()) {
					var key = new FaceKey(address, owners.get(f), Double.doubleToRawLongBits(u[0]), Double.doubleToRawLongBits(u[1]), Double.doubleToRawLongBits(u[2]), plane.key());
					var adjacent = faces.computeIfAbsent(key, ignored -> new ArrayList<>());
					for (var side : adjacent) if (cells.get(side.fragment) != null && side.positive != plane.positive()) {
						long identity = (long) side.fragment << 32 | f;
						if (scheduled.size() == maximumPairs) break;
						if (scheduled.add(identity)) queue.addLast(new Pair(side.fragment, f));
					}
					adjacent.add(new Side(f, plane.positive()));
				}
			}
			if (queue.isEmpty()) break;
			var pair = queue.removeFirst(); var a = cells.get(pair.a); var b = cells.get(pair.b); if (a == null || b == null) continue;
			var union = union(a, b); if (union == null) continue;
			cells.set(pair.a, null); cells.set(pair.b, null); cells.add(LiquidPhasePartition.Address.of(a).cell(union));
			owners.add(owners.get(pair.a)); velocity.add(velocity.get(pair.a)); merged++;
		}
		if (merged == 0) return input;
		var retained = new ArrayList<LiquidGeometryRefinement.Cell>(); var groups = new ArrayList<Integer>(); var field = new ArrayList<double[]>();
		var first = new HashMap<Integer, Integer>();
		for (int f = 0; f < cells.size(); f++) if (cells.get(f) != null) {
			first.putIfAbsent(owners.get(f), retained.size()); retained.add(cells.get(f)); groups.add(owners.get(f)); field.add(velocity.get(f));
		}
		int[] markers = input.copyMarkerCells(); for (int p = 0; p < markers.length; p++) markers[p] = first.get(markers[p]);
		int[] fragments = new int[retained.size()]; var speeds = new double[3 * retained.size()];
		for (int f = 0; f < retained.size(); f++) { fragments[f] = first.get(groups.get(f)); System.arraycopy(field.get(f), 0, speeds, 3 * f, 3); }
		return LiquidGeometryStateMapper.capture(retained, input.rootWidthMetres(), markers, fragments, speeds);
	}
	private static LiquidPolyhedron union(LiquidGeometryRefinement.Cell a, LiquidGeometryRefinement.Cell b) {
		var polygons = new ArrayList<>(LiquidExactPolygon.faces(a.geometry())); polygons.addAll(LiquidExactPolygon.faces(b.geometry()));
		var planes = new ArrayList<LiquidGeometryPlane>();
		for (var face : polygons) {
			var coefficients = face.coefficients();
			if (polygons.stream().allMatch(p -> p.inside(coefficients))) planes.add(face.plane());
		}
		double width = a.width(); var hull = LiquidPolyhedron.box(a.minimum(0), a.minimum(1), a.minimum(2),
				a.minimum(0) + width, a.minimum(1) + width, a.minimum(2) + width);
		for (var plane : planes) hull = plane.clip(hull);
		var original = LiquidConvexRegion.of(a.geometry()).measure().add(LiquidConvexRegion.of(b.geometry()).measure());
		return LiquidConvexRegion.of(hull).measure().same(original) ? hull : null;
	}
}
