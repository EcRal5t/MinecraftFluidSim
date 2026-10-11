package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.GasThermalModel;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;

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
			double absolutePressure = support.thermal().requireGasNodeState(node,
					gasState.network.inventory(node), support.thermal().gasNodeEnergy(node, gasState.network), gasState.network);
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

	/** Checks the future contacts reached through the selected source interface. */
	boolean allowsAfter(int cell, int source, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
	                    double liquidTemperature, GasTopologyCoordinator.GasTopologyPlan topology,
	                    GasLiquidPhaseModel.Direction direction) {
		GasDomainState gas = support.gasState();
		var scan = topology.scan();
		int[] nodes = adjacentNodes(cell, scan.cellNodeId(), scan.cellLineageNodeId(),
				scan.cellAvailableVolume()[cell], source);
		if (nodes.length == 0) return false;
		GasThermalModel thermal = new GasThermalModel(support.thermal().registry(), gas.network.registry());
		try {
			for (int node : nodes) {
				GasLiquidPhaseModel.Saturation saturation;
				if (node == 0) saturation = phase.ambientSaturation(transition, liquidTemperature);
				else {
					var inventory = topology.gasPlan().inventory(node);
					double pressure = thermal.absolutePressure(inventory, topology.gasPlan().availableVolume(node),
							topology.thermalPlan().nodeEnergy(node));
					saturation = phase.saturation(transition, liquidTemperature, inventory, pressure);
				}
				if (saturation.direction() != direction
						&& saturation.direction() != GasLiquidPhaseModel.Direction.EQUILIBRIUM) return false;
			}
			return true;
		} catch (ThermalStateFunction.EnergyOutsideValidityException outsideThermalRange) {
			throw outsideThermalRange;
		} catch (IllegalArgumentException outsideGasPhaseRange) {
			throw new PhaseChangeStageSupport.PhaseCandidateUnavailableException(
					"Future gas interface is outside its pressure or saturation validity range", outsideGasPhaseRange);
		}
	}

	private int[] adjacentNodes(int cell, GasDomainState gasState) {
		if (gasState.cellNodeId == null || support.state().markerGrid().markerCount[cell] == 0) return new int[0];
		double residual = support.state().geometry().cutCells.openCellVolume(cell)
				- support.state().markerGrid().exactCellMarkerVolume[cell]
				- SolidVolume.toCellVolume(Math.addExact(support.granularSvq(cell),
						support.thermal().coherentPhases().cellOccupiedSvq(cell)));
		return adjacentNodes(cell, gasState.cellNodeId, gasState.cellLineageNodeId, residual, -1);
	}

	private int[] adjacentNodes(int cell, int[] membership, int[] lineage, double residual, int source) {
		int x = cell % support.state().sizeX();
		int yz = cell / support.state().sizeX();
		int y = yz % support.state().sizeY();
		int z = yz / support.state().sizeY();
		int[] found = new int[NEIGHBOURS.length + 1];
		int count = 0;
		int sameCellNode = membership[cell];
		if (sameCellNode < 0 && lineage != null) sameCellNode = lineage[cell];
		int oldSameCell = support.gasState().cellNodeId[cell];
		if (oldSameCell < 0 && support.gasState().cellLineageNodeId != null)
			oldSameCell = support.gasState().cellLineageNodeId[cell];
		if (sameCellNode >= (source < 0 ? 1 : 0) && residual > 0.0
				&& (source < 0 || oldSameCell == source)) found[count++] = sameCellNode;
		for (int[] direction : NEIGHBOURS) {
			int nx = x + direction[0], ny = y + direction[1], nz = z + direction[2];
			if (!support.inside(nx, ny, nz)) continue;
			var geometry = support.state().geometry().cutCells;
			float aperture = direction[0] != 0 ? geometry.xFaceOpen(Math.max(x, nx), y, z)
					: direction[1] != 0 ? geometry.yFaceOpen(x, Math.max(y, ny), z)
					: geometry.zFaceOpen(x, y, Math.max(z, nz));
			if (!(aperture > 0.0F)) continue;
			int neighbor = support.cellIndex(nx, ny, nz);
			int oldNeighbor = support.gasState().cellNodeId[neighbor];
			if (oldNeighbor < 0 && support.gasState().cellLineageNodeId != null)
				oldNeighbor = support.gasState().cellLineageNodeId[neighbor];
			if (source >= 0 && oldNeighbor != source) continue;
			int node = membership[neighbor];
			if (node < (source < 0 ? 1 : 0) || contains(found, count, node)) continue;
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
