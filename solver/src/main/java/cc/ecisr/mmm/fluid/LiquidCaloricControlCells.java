package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Exact material regions on spatial leaves; internal convex cuts carry no thermal unknowns. */
final class LiquidCaloricControlCells {
	private record Group(boolean liquid, int owner, LiquidPhasePartition.Address address) { }
	final List<LiquidGeometryRefinement.Cell> regions;
	final int[] fragments, gasNodes;
	LiquidCaloricControlCells(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> primitive,
			List<Integer> source, List<Integer> nodes) {
		int[] controls = state.copyControlCells(); var groups = new LinkedHashMap<Group, ArrayList<Integer>>();
		for (int c = 0; c < primitive.size(); c++) {
			boolean liquid = source.get(c) >= 0; int owner = liquid ? controls[source.get(c)] : nodes.get(c);
			groups.computeIfAbsent(new Group(liquid, owner, LiquidPhasePartition.Address.of(primitive.get(c))), ignored -> new ArrayList<>()).add(c);
		}
		var result = new ArrayList<LiquidGeometryRefinement.Cell>(); var ids = new ArrayList<Integer>(); var labels = new ArrayList<Integer>();
		for (var entry : groups.entrySet()) {
			var pieces = entry.getValue().stream().map(primitive::get).toList();
			result.add(entry.getKey().address().cell(LiquidStarControlPartition.assemble(pieces)));
			ids.add(entry.getKey().liquid() ? source.get(entry.getValue().getFirst()) : -1);
			labels.add(entry.getKey().liquid() ? -1 : entry.getKey().owner());
		}
		regions = List.copyOf(result); fragments = ids.stream().mapToInt(Integer::intValue).toArray(); gasNodes = labels.stream().mapToInt(Integer::intValue).toArray();
	}
}
