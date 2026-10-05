package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Complete liquid/gas geometry and resolved incoming velocity for one mechanical candidate. */
final class LiquidGeometryPhaseInput {
	final LiquidControlVolumeMesh mesh;
	final List<LiquidGeometryRefinement.Cell> regions;
	final double rootWidthMetres;
	final boolean sealedExterior;
	final int[] fragments;
	final double[] cellVelocity, faceVelocity;

	LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, boolean sealed, int maximumRegions) {
		if (state.rootWidthMetres() != domain.rootWidthMetres || maximumRegions < 1) throw new IllegalArgumentException("Invalid geometric phase input");
		rootWidthMetres = domain.rootWidthMetres;
		sealedExterior = sealed;
		var source = LiquidGeometryStateMapper.restore(state); var remap = new LiquidGeometryRemap(domain.leaves);
		var byLeaf = new ArrayList<ArrayList<LiquidPolyhedron>>(); var ids = new ArrayList<ArrayList<Integer>>();
		for (int c = 0; c < domain.leaves.size(); c++) { byLeaf.add(new ArrayList<>()); ids.add(new ArrayList<>()); }
		for (int f = 0; f < source.size(); f++) for (var piece : remap.partitionGeometry(source.get(f).geometry())) {
			byLeaf.get(piece.cell()).add(piece.geometry()); ids.get(piece.cell()).add(f);
		}
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var mapping = new ArrayList<Integer>();
		for (int c = 0; c < domain.leaves.size(); c++) {
			if (cells.size() == maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
			for (var region : LiquidPhasePartition.build(domain.leaves.get(c), byLeaf.get(c), maximumRegions - cells.size())) {
				cells.add(region.cell()); mapping.add(region.liquid() < 0 ? -1 : ids.get(c).get(region.liquid()));
			}
		}
		regions = List.copyOf(cells);
		mesh = new LiquidControlVolumeMesh(regions, domain.rootWidthMetres, 1, (c, surface) -> domain.boundary(surface, sealed));
		fragments = mapping.stream().mapToInt(Integer::intValue).toArray(); cellVelocity = new double[3 * cells.size()]; faceVelocity = new double[3 * mesh.faces.size()];
		for (int c = 0; c < fragments.length; c++) if (fragments[c] >= 0) System.arraycopy(state.cells().get(fragments[c]).copyVelocity(), 0, cellVelocity, 3 * c, 3);
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f); int a = face.first(), b = face.second(); boolean liquidA = fragments[a] >= 0, liquidB = b >= 0 && fragments[b] >= 0;
			for (int axis = 0; axis < 3; axis++) {
				double incoming = liquidA && liquidB ? 0.5 * (cellVelocity[3 * a + axis] + cellVelocity[3 * b + axis])
						: liquidA ? cellVelocity[3 * a + axis] : liquidB ? cellVelocity[3 * b + axis] : 0;
				faceVelocity[3 * f + axis] = incoming;
			}
		}
	}
}
