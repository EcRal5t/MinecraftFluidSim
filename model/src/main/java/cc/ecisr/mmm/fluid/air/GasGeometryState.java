package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.geometry.ResolvedControlVolume;


import java.util.Arrays;
import java.util.List;

/** Immutable exact gas support; several chambers may occupy parts of the same base cell. */
public record GasGeometryState(double rootWidthMetres, List<ResolvedControlVolume> cells,
		int[] nodeOrdinals, int nodeCount, boolean sealedExterior) {
	public GasGeometryState {
		if (!(rootWidthMetres > 0) || !Double.isFinite(rootWidthMetres) || cells == null || nodeOrdinals == null
				|| cells.size() != nodeOrdinals.length || nodeCount < 0 || nodeCount > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Invalid resolved gas geometry");
		cells = List.copyOf(cells); nodeOrdinals = nodeOrdinals.clone();
		int[] first = new int[nodeCount + 1]; Arrays.fill(first, -1);
		for (int c = 0; c < cells.size(); c++) {
			if (c > 0 && ResolvedControlVolume.compareGeometry(cells.get(c - 1), cells.get(c)) >= 0)
				throw new IllegalArgumentException("Gas geometry must be unique and canonical");
			for (double velocity : cells.get(c).copyVelocity()) if (velocity != 0)
				throw new IllegalArgumentException("Lumped gas support cannot carry a spatial momentum field");
			int node = nodeOrdinals[c]; if (node < 0 || node > nodeCount) throw new IllegalArgumentException("Unknown gas geometry node");
			if (first[node] < 0) first[node] = c;
		}
		for (int n = 1; n <= nodeCount; n++) if (first[n] < 0 || n > 1 && first[n] <= first[n - 1])
			throw new IllegalArgumentException("Finite gas chambers must have canonical spatial support");
	}
	@Override public int[] nodeOrdinals() { return nodeOrdinals.clone(); }
	public int topologyKey(int node) {
		if (node < 1 || node > nodeCount) throw new IllegalArgumentException("Unknown finite gas chamber");
		for (int c = 0; c < nodeOrdinals.length; c++) if (nodeOrdinals[c] == node) return c;
		throw new IllegalStateException("Finite gas chamber lost its support");
	}
	public long cpuBytes() {
		long bytes = (long) nodeOrdinals.length * Integer.BYTES;
		for (var cell : cells) bytes = Math.addExact(bytes, cell.cpuBytes());
		return bytes;
	}
}
