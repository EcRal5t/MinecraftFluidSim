package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Shared pressure cells retain their physical resolution while auxiliary convex fragments provide exact occupancy. */
final class LiquidMechanicalControlCells {
	private record Key(int control, LiquidPhasePartition.Address refinement) { }
	final List<LiquidGeometryRefinement.Cell> regions;
	final List<List<LiquidGeometryRefinement.Cell>> support;
	final int[] fragments, gasNodes;
	LiquidMechanicalControlCells(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> primitive,
			List<Integer> source, List<Integer> nodes, Set<Integer> refined) {
		int[] controls = state.copyControlCells();
		var groups = new LinkedHashMap<Key, ArrayList<Integer>>();
		for (int c = 0; c < primitive.size(); c++) if (source.get(c) >= 0) {
			int control = controls[source.get(c)];
			var key = new Key(control, refined.contains(control) ? LiquidPhasePartition.Address.of(primitive.get(c)) : null);
			groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(c);
		}
		var result = new ArrayList<LiquidGeometryRefinement.Cell>(); var represented = new ArrayList<List<LiquidGeometryRefinement.Cell>>();
		var ids = new ArrayList<Integer>(); var labels = new ArrayList<Integer>();
		for (var group : groups.values()) {
			var pieces = group.stream().map(primitive::get).toList(); var geometry = assemble(pieces);
			if (geometry == null) for (int c : group) {
				result.add(primitive.get(c)); represented.add(List.of(primitive.get(c))); ids.add(source.get(c)); labels.add(-1);
			} else {
				result.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, geometry)); represented.add(pieces); ids.add(source.get(group.getFirst())); labels.add(-1);
			}
		}
		for (int c = 0; c < primitive.size(); c++) if (source.get(c) < 0) {
			result.add(primitive.get(c)); represented.add(List.of(primitive.get(c))); ids.add(-1); labels.add(nodes.get(c));
		}
		regions = List.copyOf(result); support = List.copyOf(represented);
		fragments = ids.stream().mapToInt(Integer::intValue).toArray(); gasNodes = labels.stream().mapToInt(Integer::intValue).toArray();
	}
	private static LiquidPolyhedron assemble(List<LiquidGeometryRefinement.Cell> pieces) {
		if (pieces.size() == 1) return pieces.getFirst().geometry();
		var measure = LiquidConvexRegion.EMPTY.measure(); for (var piece : pieces) measure = measure.add(piece.geometry().measure());
		var faces = LiquidExactPolygon.splitEdges(LiquidExteriorGeometry.of(pieces));
		var geometry = LiquidPolyhedron.restoredExact(faces, measure);
		return geometry.kernelPoint() == null ? null : geometry;
	}
}
