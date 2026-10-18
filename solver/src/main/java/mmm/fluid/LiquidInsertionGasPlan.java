package mmm.fluid;

/** Routes external additions while retaining finite gas capacity. */
final class LiquidInsertionGasPlan {
	/** Tracks positive gas support while external liquid is assigned among candidate cells. */
	static final class CapacityRouting {
		private final int[][] groupsByCell;
		private final int[] remainingCells;
		private final boolean[] finiteStock;

		CapacityRouting(FluidDomain domain) {
			groupsByCell = new int[domain.cellCount][];
			if (domain.gasState.frame == null) { remainingCells = new int[0]; finiteStock = new boolean[0]; return; }
			domain.gasTopologyCoordinator.refresh();
			var frame = domain.gasState.frame;
			int[] parent = new int[frame.nodeCount() + 1];
			for (int node = 0; node < parent.length; node++) parent[node] = node;
			for (var port : frame.topology().connections()) {
				int first = root(parent, port.firstNode()), second = root(parent, port.secondNode());
				parent[Math.max(first, second)] = Math.min(first, second);
			}
			remainingCells = new int[parent.length]; finiteStock = new boolean[parent.length];
			for (int node = 1; node < parent.length; node++)
				if (!domain.gasState.network.inventory(node).isEmpty()) finiteStock[root(parent, node)] = true;
			for (int cell = 0; cell < domain.cellCount; cell++) {
				var groups = new java.util.TreeSet<Integer>();
				for (int micro = 0; micro < 8; micro++) {
					int node = frame.nodeOfPiece(8 * cell + micro);
					if (node > 0) { int group = root(parent, node); if (group != 0) groups.add(group); }
				}
				groupsByCell[cell] = groups.stream().mapToInt(Integer::intValue).toArray();
				for (int group : groupsByCell[cell]) remainingCells[group]++;
			}
		}

		boolean canFill(int cell) {
			if (groupsByCell[cell] != null) for (int group : groupsByCell[cell])
				if (finiteStock[group] && remainingCells[group] == 1) return false;
			return true;
		}

		void filled(int cell) {
			if (groupsByCell[cell] != null) for (int group : groupsByCell[cell]) remainingCells[group]--;
		}

		private static int root(int[] parent, int node) {
			while (parent[node] != node) { parent[node] = parent[parent[node]]; node = parent[node]; }
			return node;
		}
	}

}
