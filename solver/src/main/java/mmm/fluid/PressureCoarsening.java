package mmm.fluid;

import java.util.ArrayList;
import java.util.TreeMap;

/** Physical scalar pressure on connected liquid groups inside two-cell aggregates. */
final class PressureCoarsening {
	private PressureCoarsening() { }
	record Face(int row, int minus, int plus) { }

	static void prepare(CellVelocityConstraintOperator operator, CellAffineVelocity basis, int[] liquidRows,
	                    java.util.List<Face> faces, int[] gasRows, int sizeX, int sizeY) {
		int[] parent = new int[basis.blockCount()];
		for (int block = 0; block < parent.length; block++) parent[block] = block;
		for (var face : faces) if (sameAggregate(basis.physicalCell(face.minus()), basis.physicalCell(face.plus()), sizeX, sizeY)) {
			int minus = root(parent, face.minus()), plus = root(parent, face.plus());
			parent[Math.max(minus, plus)] = Math.min(minus, plus);
		}
		var groups = new TreeMap<Integer, Integer>();
		for (int block = 0; block < liquidRows.length; block++) if (liquidRows[block] >= 0)
			groups.computeIfAbsent(root(parent, block), ignored -> groups.size());
		var columns = new ArrayList<TreeMap<Integer, Double>>(groups.size());
		for (int i = 0; i < groups.size(); i++) columns.add(new TreeMap<>());
		for (int block = 0; block < liquidRows.length; block++) if (liquidRows[block] >= 0)
			columns.get(groups.get(root(parent, block))).put(liquidRows[block], 1.0);
		for (var face : faces) {
			int minus = groups.get(root(parent, face.minus())), plus = groups.get(root(parent, face.plus()));
			columns.get(minus).merge(face.row(), -.5, Double::sum);
			columns.get(plus).merge(face.row(), -.5, Double::sum);
		}
		for (int row : gasRows) if (row >= 0) {
			var gas = new TreeMap<Integer, Double>(); gas.put(row, 1.0); columns.add(gas);
		}
		int[][] rows = new int[columns.size()][]; double[][] weights = new double[columns.size()][];
		for (int i = 0; i < columns.size(); i++) {
			rows[i] = columns.get(i).keySet().stream().mapToInt(Integer::intValue).toArray();
			weights[i] = columns.get(i).values().stream().mapToDouble(Double::doubleValue).toArray();
		}
		operator.coarseRows(rows, weights);
	}
	private static int root(int[] parent, int block) {
		while (parent[block] != block) { parent[block] = parent[parent[block]]; block = parent[block]; }
		return block;
	}
	private static boolean sameAggregate(int first, int second, int sizeX, int sizeY) {
		return first % sizeX / 2 == second % sizeX / 2
				&& first / sizeX % sizeY / 2 == second / sizeX % sizeY / 2
				&& first / (sizeX * sizeY) / 2 == second / (sizeX * sizeY) / 2;
	}
}
