package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/** Derives chambers from the same complete shared faces used by pressure and interface motion. */
final class ResolvedGasGeometry {
	final GasGeometryState state;
	/** Liquid regions have -1; ambient gas has 0; finite gas nodes have positive ordinals. */
	final int[] phaseNodes;
	/** Exact SI volumes, including the ambient support at index zero. */
	final Fraction[] volumes;

	ResolvedGasGeometry(LiquidGeometryPhaseInput input) {
		var roots = LiquidPhasePartition.gasComponents(input.mesh, input.fragments);
		var ambient = new boolean[roots.length];
		for (var face : input.mesh.faces) if (face.second() == -1 && input.fragments[face.first()] < 0)
			ambient[roots[face.first()]] = true;
		var packed = new ArrayList<LiquidGeometryState.ControlVolume>(); var phases = new ArrayList<Integer>();
		for (int c = 0; c < input.fragments.length; c++) if (input.fragments[c] < 0) {
			packed.add(LiquidGeometryStateMapper.pack(input.regions.get(c), new double[3])); phases.add(c);
		}
		var order = new ArrayList<Integer>(); for (int c = 0; c < packed.size(); c++) order.add(c);
		order.sort((a, b) -> LiquidGeometryState.compareGeometry(packed.get(a), packed.get(b)));
		var nodes = new HashMap<Integer, Integer>(); var cells = new ArrayList<LiquidGeometryState.ControlVolume>();
		int[] labels = new int[order.size()]; phaseNodes = new int[input.fragments.length]; Arrays.fill(phaseNodes, -1);
		for (int c = 0; c < order.size(); c++) {
			int original = order.get(c), phase = phases.get(original), root = roots[phase];
			int node = ambient[root] ? 0 : nodes.computeIfAbsent(root, ignored -> nodes.size() + 1);
			cells.add(packed.get(original)); labels[c] = node; phaseNodes[phase] = node;
		}
		state = new GasGeometryState(input.rootWidthMetres, cells, labels, nodes.size());
		volumes = volumes(state);
	}

	static Fraction[] volumes(GasGeometryState state) {
		var volume = new Fraction[state.nodeCount() + 1]; Arrays.fill(volume, Fraction.ZERO);
		var width = Fraction.of(state.rootWidthMetres()); var scale = width.multiply(width).multiply(width);
		int[] nodes = state.nodeOrdinals(); var cells = LiquidGeometryStateMapper.restoreCells(state.cells());
		for (int c = 0; c < cells.size(); c++) volume[nodes[c]] = volume[nodes[c]]
				.add(LiquidConvexRegion.of(cells.get(c).geometry()).measure().volume().multiply(scale));
		return volume;
	}
}
