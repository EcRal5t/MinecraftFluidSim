package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Resolves same-cell and open-face sealed gas contacts and orders their phase affinity. */
final class GasPhaseInterfaceResolver {
	private static final int[][] NEIGHBOURS = {
			{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
	};

	private final PhaseChangeStageSupport support;

	GasPhaseInterfaceResolver(PhaseChangeStageSupport support) {
		this.support = support;
	}

	List<Candidate> resolve(
			int cell, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
			GasDomainState gasState, double liquidTemperature
	) {
		int[] nodes = adjacentNodes(cell, gasState);
		ArrayList<Candidate> result = new ArrayList<>(nodes.length);
		for (int node : nodes) {
			double absolutePressure = GasRegistry.STANDARD_PRESSURE_PA
					+ support.thermal().gasGaugePressure(node, gasState.network);
			result.add(new Candidate(node, phase.saturation(
					transition, liquidTemperature, gasState.network.inventory(node), absolutePressure)));
		}
		result.sort((left, right) -> {
			int byImbalance = Double.compare(
					Math.abs(right.saturation().pressureImbalancePa()),
					Math.abs(left.saturation().pressureImbalancePa()));
			return byImbalance != 0 ? byImbalance : Integer.compare(left.node(), right.node());
		});
		return result;
	}

	private int[] adjacentNodes(int cell, GasDomainState gasState) {
		if (gasState.cellNodeId == null || support.state().markerGrid().markerCount[cell] == 0) return new int[0];
		int x = cell % support.state().sizeX();
		int yz = cell / support.state().sizeX();
		int y = yz % support.state().sizeY();
		int z = yz / support.state().sizeY();
		int[] found = new int[NEIGHBOURS.length + 1];
		int count = 0;
		int sameCellNode = gasState.cellNodeId[cell];
		if (sameCellNode < 0 && gasState.cellLineageNodeId != null) sameCellNode = gasState.cellLineageNodeId[cell];
		double residual = support.state().geometry().cutCells.openCellVolume(cell)
				- support.state().markerGrid().exactCellMarkerVolume[cell]
				- SolidVolume.toCellVolume(Math.addExact(support.granularSvq(cell),
						support.thermal().coherentPhases().cellOccupiedSvq(cell)));
		if (sameCellNode > 0 && residual > 0.0) found[count++] = sameCellNode;
		for (int[] direction : NEIGHBOURS) {
			int nx = x + direction[0], ny = y + direction[1], nz = z + direction[2];
			if (!support.inside(nx, ny, nz)) continue;
			var geometry = support.state().geometry().cutCells;
			float aperture = direction[0] != 0 ? geometry.xFaceOpen(Math.max(x, nx), y, z)
					: direction[1] != 0 ? geometry.yFaceOpen(x, Math.max(y, ny), z)
					: geometry.zFaceOpen(x, y, Math.max(z, nz));
			if (!(aperture > 0.0F)) continue;
			int node = gasState.cellNodeId[support.cellIndex(nx, ny, nz)];
			if (node <= 0 || contains(found, count, node)) continue;
			found[count++] = node;
		}
		Arrays.sort(found, 0, count);
		return Arrays.copyOf(found, count);
	}

	private static boolean contains(int[] nodes, int count, int node) {
		for (int index = 0; index < count; index++) if (nodes[index] == node) return true;
		return false;
	}

	record Candidate(int node, GasLiquidPhaseModel.Saturation saturation) { }
}
