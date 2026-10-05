package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.geometry.ResolvedControlVolume;

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
		var packed = new ArrayList<ResolvedControlVolume>(); var phases = new ArrayList<Integer>();
		for (int c = 0; c < input.fragments.length; c++) if (input.fragments[c] < 0) {
			packed.add(LiquidGeometryStateMapper.pack(input.regions.get(c), new double[3])); phases.add(c);
		}
		var order = new ArrayList<Integer>(); for (int c = 0; c < packed.size(); c++) order.add(c);
		order.sort((a, b) -> LiquidGeometryState.compareGeometry(packed.get(a), packed.get(b)));
		var nodes = new HashMap<Integer, Integer>(); var cells = new ArrayList<ResolvedControlVolume>();
		int[] labels = new int[order.size()]; phaseNodes = new int[input.fragments.length]; Arrays.fill(phaseNodes, -1);
		for (int c = 0; c < order.size(); c++) {
			int original = order.get(c), phase = phases.get(original), root = roots[phase];
			int node = ambient[root] ? 0 : nodes.computeIfAbsent(root, ignored -> nodes.size() + 1);
			cells.add(packed.get(original)); labels[c] = node; phaseNodes[phase] = node;
		}
		state = new GasGeometryState(input.rootWidthMetres, cells, labels, nodes.size(), input.sealedExterior);
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

	/** Matches complete support across equivalent refinements and returns the saved node labels for this mesh. */
	static int[] requireCompatible(GasGeometryState saved, LiquidGeometryPhaseInput input, int maximumPairs) {
		var derived = new ResolvedGasGeometry(input);
		if (saved.sealedExterior() != input.sealedExterior || saved.nodeCount() != derived.state.nodeCount())
			throw new IllegalArgumentException("Saved gas chamber count or exterior condition differs from actual phase geometry");
		var overlap = new ResolvedGasOverlap(saved, derived.state, maximumPairs);
		requireCoverage(saved, overlap.oldCovered); requireCoverage(derived.state, overlap.newCovered);
		int[] labels = new int[saved.nodeCount() + 1]; Arrays.fill(labels, -1); labels[0] = 0;
		for (int old = 0; old <= saved.nodeCount(); old++) {
			var destinations = overlap.destinations(old);
			if (old > 0 && destinations.size() != 1) throw new IllegalArgumentException("Saved gas node differs from an actual connected chamber");
			for (int next : destinations.keySet()) {
				if ((old == 0) != (next == 0) || labels[next] >= 0 && labels[next] != old)
					throw new IllegalArgumentException("Saved gas nodes disagree with actual chamber connectivity");
				labels[next] = old;
			}
		}
		var phases = derived.phaseNodes.clone(); for (int c = 0; c < phases.length; c++) if (phases[c] >= 0) phases[c] = labels[phases[c]];
		return phases;
	}
	private static void requireCoverage(GasGeometryState state, Fraction[] covered) {
		var width = Fraction.of(state.rootWidthMetres()); var scale = width.multiply(width).multiply(width);
		var cells = LiquidGeometryStateMapper.restoreCells(state.cells());
		for (int c = 0; c < cells.size(); c++) if (!covered[c].equals(LiquidConvexRegion.of(cells.get(c).geometry()).measure().volume().multiply(scale)))
			throw new IllegalArgumentException("Saved gas support differs from the actual liquid and collision complement");
	}
}
