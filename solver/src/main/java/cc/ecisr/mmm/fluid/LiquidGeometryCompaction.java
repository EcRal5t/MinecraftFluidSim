package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;

/** Removes auxiliary convex cuts only when actual geometry, ownership and the P0 field are identical. */
final class LiquidGeometryCompaction {
	private record FaceKey(LiquidPhasePartition.Address address, int owner, int control, long x, long y, long z, LiquidGeometryPlane.Key plane) { }
	private record Side(int fragment, boolean positive) { }
	private record Pair(int a, int b) { }
	private LiquidGeometryCompaction() { }
	static LiquidGeometryState compact(LiquidGeometryState input, int maximumPairs) {
		if (maximumPairs < 0) throw new IllegalArgumentException("Invalid exact compaction work budget");
		if (maximumPairs == 0 || input.cells().size() < 2) return input;
		var cells = new ArrayList<>(LiquidGeometryStateMapper.restore(input));
		var owners = new ArrayList<Integer>(); var velocity = new ArrayList<double[]>();
		var controls = new ArrayList<Integer>(); for (int c : input.copyControlCells()) controls.add(c);
		for (int f : input.copyFragmentOwners()) owners.add(f); for (var cell : input.cells()) velocity.add(cell.copyVelocity());
		var faces = new HashMap<FaceKey, ArrayList<Side>>(); var queue = new ArrayDeque<Pair>(); var scheduled = new HashSet<Long>();
		int next = 0, merged = 0;
		while (true) {
			while (next < cells.size() && scheduled.size() < maximumPairs) {
				int f = next++; var cell = cells.get(f); if (cell == null) continue;
				var u = velocity.get(f); var address = LiquidPhasePartition.Address.of(cell);
				for (var plane : cell.geometry().planes()) {
					var key = new FaceKey(address, owners.get(f), controls.get(f), Double.doubleToRawLongBits(u[0]), Double.doubleToRawLongBits(u[1]), Double.doubleToRawLongBits(u[2]), plane.key());
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
			controls.add(controls.get(pair.a));
		}
		if (merged == 0) return input;
		var retained = new ArrayList<LiquidGeometryRefinement.Cell>(); var groups = new ArrayList<Integer>(); var field = new ArrayList<double[]>();
		var first = new HashMap<Integer, Integer>();
		var firstControl = new HashMap<Integer, Integer>(); var retainedControls = new ArrayList<Integer>();
		for (int f = 0; f < cells.size(); f++) if (cells.get(f) != null) {
			first.putIfAbsent(owners.get(f), retained.size()); retained.add(cells.get(f)); groups.add(owners.get(f)); field.add(velocity.get(f));
			firstControl.putIfAbsent(controls.get(f), retained.size() - 1); retainedControls.add(firstControl.get(controls.get(f)));
		}
		int[] markers = input.copyMarkerCells(); for (int p = 0; p < markers.length; p++) markers[p] = first.get(markers[p]);
		int[] fragments = new int[retained.size()]; var speeds = new double[3 * retained.size()];
		for (int f = 0; f < retained.size(); f++) { fragments[f] = first.get(groups.get(f)); System.arraycopy(field.get(f), 0, speeds, 3 * f, 3); }
		return LiquidGeometryStateMapper.capture(retained, input.rootWidthMetres(), markers, fragments, speeds, retainedControls.stream().mapToInt(Integer::intValue).toArray());
	}
	private static LiquidPolyhedron union(LiquidGeometryRefinement.Cell a, LiquidGeometryRefinement.Cell b) {
		var first = LiquidExactPolygon.faces(a.geometry()); var second = LiquidExactPolygon.faces(b.geometry());
		var exterior = new ArrayList<LiquidExactPolygon>();
		// A convex union retains supporting faces and removes faces entirely covered by the other region.
		// Partial coverage of a nonsupporting face proves a concavity; no auxiliary hull is needed.
		if (!exterior(first, second, exterior) || !exterior(second, first, exterior)) return null;
		var original = LiquidConvexRegion.of(a.geometry()).measure().add(LiquidConvexRegion.of(b.geometry()).measure());
		var sharedEdges = LiquidExactPolygon.splitEdges(LiquidCoplanarFaceUnion.compact(exterior));
		// Complementary interior faces cancel algebraically, preserving volume and every spatial moment.
		return LiquidPolyhedron.restoredExact(sharedEdges, original);
	}
	private static boolean exterior(java.util.List<LiquidExactPolygon> source, java.util.List<LiquidExactPolygon> other, ArrayList<LiquidExactPolygon> result) {
		var planes = other.stream().map(LiquidExactPolygon::coefficients).toList();
		for (var face : source) {
			var plane = face.coefficients();
			if (other.stream().allMatch(p -> p.inside(plane))) result.add(face);
			else if (planes.stream().anyMatch(p -> !face.inside(p))) return false;
		}
		return true;
	}
}
