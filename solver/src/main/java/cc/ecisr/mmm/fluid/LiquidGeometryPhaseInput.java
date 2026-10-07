package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import cc.ecisr.mmm.fluid.air.GasGeometryState;

/** Complete liquid/gas geometry and resolved incoming velocity for one mechanical candidate. */
final class LiquidGeometryPhaseInput {
	final LiquidControlVolumeMesh mesh;
	final LiquidGeometryState liquidGeometry;
	final List<LiquidGeometryRefinement.Cell> regions;
	final double rootWidthMetres;
	final boolean sealedExterior;
	final GasGeometryState gasGeometry;
	final int[] storedGasNodes;
	final int[] fragments;
	final double[] cellVelocity, faceVelocity;
	final List<List<LiquidGeometryRefinement.Cell>> mechanicalSupport;

	LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, boolean sealed, int maximumRegions) {
		this(state, domain, sealed, maximumRegions, null, null);
	}
	LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, boolean sealed, int maximumRegions, java.util.Set<Integer> refined) {
		this(state, domain, sealed, maximumRegions, null, java.util.Set.copyOf(refined));
	}
	LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, GasGeometryState gas, int maximumRegions) {
		this(state, domain, java.util.Objects.requireNonNull(gas).sealedExterior(), maximumRegions, gas);
	}
	LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, GasGeometryState gas, int maximumRegions, java.util.Set<Integer> refined) {
		this(state, domain, java.util.Objects.requireNonNull(gas).sealedExterior(), maximumRegions, gas, java.util.Set.copyOf(refined));
	}
	private LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, boolean sealed, int maximumRegions, GasGeometryState gas) {
		this(state, domain, sealed, maximumRegions, gas, null);
	}
	private LiquidGeometryPhaseInput(LiquidGeometryState state, LiquidGeometryDomainPartition domain, boolean sealed, int maximumRegions, GasGeometryState gas, java.util.Set<Integer> refined) {
		if (state.rootWidthMetres() != domain.rootWidthMetres || maximumRegions < 1) throw new IllegalArgumentException("Invalid geometric phase input");
		if (gas != null && gas.rootWidthMetres() != domain.rootWidthMetres) throw new IllegalArgumentException("Gas geometry has a different physical root");
		rootWidthMetres = domain.rootWidthMetres;
		liquidGeometry = state;
		gasGeometry = gas;
		sealedExterior = sealed;
		var source = LiquidGeometryStateMapper.restore(state); var remap = new LiquidGeometryRemap(domain.leaves);
		var byLeaf = new ArrayList<ArrayList<LiquidPolyhedron>>(); var ids = new ArrayList<ArrayList<Integer>>();
		for (int c = 0; c < domain.leaves.size(); c++) { byLeaf.add(new ArrayList<>()); ids.add(new ArrayList<>()); }
		for (int f = 0; f < source.size(); f++) for (var piece : remap.partitionGeometry(source.get(f).geometry())) {
			byLeaf.get(piece.cell()).add(piece.geometry()); ids.get(piece.cell()).add(f);
		}
		var gasByLeaf = new ArrayList<ArrayList<LiquidPolyhedron>>(); var gasIds = new ArrayList<ArrayList<Integer>>();
		for (int c = 0; c < domain.leaves.size(); c++) { gasByLeaf.add(new ArrayList<>()); gasIds.add(new ArrayList<>()); }
		if (gas != null) {
			var gasCells = LiquidGeometryStateMapper.restoreCells(gas.cells()); var nodes = gas.nodeOrdinals();
			for (int c = 0; c < gasCells.size(); c++) for (var piece : remap.partitionGeometry(gasCells.get(c).geometry())) {
				gasByLeaf.get(piece.cell()).add(piece.geometry()); gasIds.get(piece.cell()).add(nodes[c]);
			}
		}
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var mapping = new ArrayList<Integer>(); var labels = new ArrayList<Integer>();
		for (int c = 0; c < domain.leaves.size(); c++) {
			if (cells.size() == maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
			if (gas == null) for (var region : LiquidPhasePartition.build(domain.leaves.get(c), byLeaf.get(c), maximumRegions - cells.size())) {
				cells.add(region.cell()); mapping.add(region.liquid() < 0 ? -1 : ids.get(c).get(region.liquid())); labels.add(-1);
			} else {
				int start = cells.size(); var address = LiquidPhasePartition.Address.of(domain.leaves.get(c));
				if ((long) start + byLeaf.get(c).size() + gasByLeaf.get(c).size() > maximumRegions) throw new IllegalStateException("Phase geometry exceeds its region budget");
				for (int f = 0; f < byLeaf.get(c).size(); f++) { cells.add(address.cell(byLeaf.get(c).get(f))); mapping.add(ids.get(c).get(f)); labels.add(-1); }
				for (int f = 0; f < gasByLeaf.get(c).size(); f++) { cells.add(address.cell(gasByLeaf.get(c).get(f))); mapping.add(-1); labels.add(gasIds.get(c).get(f)); }
				LiquidPhasePartition.requireComplete(domain.leaves.get(c), cells.subList(start, cells.size()));
			}
		}
		if (refined == null) {
			regions = List.copyOf(cells); mechanicalSupport = null;
			storedGasNodes = labels.stream().mapToInt(Integer::intValue).toArray(); fragments = mapping.stream().mapToInt(Integer::intValue).toArray();
		} else {
			var controls = new LiquidMechanicalControlCells(state, cells, mapping, labels, refined);
			regions = controls.regions; mechanicalSupport = controls.support; storedGasNodes = controls.gasNodes; fragments = controls.fragments;
		}
		var meshSupport = refined == null ? regions : regions.stream().map(cell -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cell.geometry())).toList();
		mesh = new LiquidControlVolumeMesh(meshSupport, domain.rootWidthMetres, 1, (c, surface) -> domain.boundary(surface, sealed));
		cellVelocity = new double[3 * regions.size()]; faceVelocity = new double[3 * mesh.faces.size()];
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
