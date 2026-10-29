package mmm.fluid;

/** Routes external additions while retaining finite gas capacity. */
final class LiquidInsertionGasPlan {
	/** Tracks positive gas support while external liquid is assigned among candidate cells. */
	static final class CapacityRouting {
		private final int[][] groupsByCell;
		private final int[] remainingCells;
		private final boolean[] finiteStock;

		CapacityRouting(HostFluidState domain) {
			groupsByCell = new int[domain.cellCount][];
			if (domain.gasState.frame == null) { remainingCells = new int[0]; finiteStock = new boolean[0]; return; }
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
			int[] groups=new int[4];
			for (int cell = 0; cell < domain.cellCount; cell++) {
				int count=0;
				for(int support:CellRemapMicroTopology.components(frame.geometry().collisionMask(cell))){
					int node=frame.topology().nodeOfComponent(cell,support);if(node<=0)continue;
					int group=root(parent,node);if(group==0)continue;
					int at=0;while(at<count&&groups[at]!=group)at++;
					if(at==count)groups[count++]=group;
				}
				if(count==0)continue;
				groupsByCell[cell] = java.util.Arrays.copyOf(groups,count);
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
