package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared pressure cells retain their physical resolution while auxiliary convex fragments provide exact occupancy. */
final class LiquidMechanicalControlCells {
	final List<LiquidGeometryRefinement.Cell> regions;
	final List<List<LiquidGeometryRefinement.Cell>> support;
	final int[] fragments, gasNodes;
	LiquidMechanicalControlCells(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> primitive,
			List<Integer> source, List<Integer> nodes, Map<Integer, Integer> refined, int maximumRegions) {
		int[] controls = state.copyControlCells();
		var groups = new LinkedHashMap<Integer, ArrayList<LiquidPhysicalControlRefinement.Piece>>();
		for (int c = 0; c < primitive.size(); c++) if (source.get(c) >= 0) {
			int control = controls[source.get(c)];
			groups.computeIfAbsent(control, ignored -> new ArrayList<>()).add(new LiquidPhysicalControlRefinement.Piece(primitive.get(c), source.get(c)));
		}
		var result = new ArrayList<LiquidGeometryRefinement.Cell>(); var represented = new ArrayList<List<LiquidGeometryRefinement.Cell>>();
		var ids = new ArrayList<Integer>(); var labels = new ArrayList<Integer>();
		int representedCount = (int) source.stream().filter(id -> id < 0).count();
		for (var entry : groups.entrySet()) for (var group : LiquidPhysicalControlRefinement.partition(entry.getValue(),
				refined.getOrDefault(entry.getKey(), 0), maximumRegions - representedCount)) {
			representedCount += group.size();
			var pieces = group.stream().map(LiquidPhysicalControlRefinement.Piece::cell).toList(); var geometry = LiquidStarControlPartition.assemble(pieces);
			result.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, geometry));
			represented.add(pieces); ids.add(group.getFirst().source()); labels.add(-1);
		}
		for (int c = 0; c < primitive.size(); c++) if (source.get(c) < 0) {
			result.add(primitive.get(c)); represented.add(List.of(primitive.get(c))); ids.add(-1); labels.add(nodes.get(c));
		}
		regions = List.copyOf(result); support = List.copyOf(represented);
		fragments = ids.stream().mapToInt(Integer::intValue).toArray(); gasNodes = labels.stream().mapToInt(Integer::intValue).toArray();
	}
}
