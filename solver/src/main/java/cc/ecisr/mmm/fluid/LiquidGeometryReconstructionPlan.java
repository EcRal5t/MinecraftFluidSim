package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Private reconstructed geometry keeps inventories and P0 momentum while measuring spatial redistribution. */
final class LiquidGeometryReconstructionPlan {
	record Observation(int owner, LiquidPolyhedron.Point positionMetres, LiquidPolyhedron.Point velocityMetresPerSecond) implements LiquidGeometryPublication.Observation { }
	final LiquidGeometryState input;
	final GasGeometryState previousGasGeometry;
	final LiquidGeometryState geometry;
	final GasGeometryState gasGeometry;
	/** Successor node ordinal to the original material chamber; ambient stays zero. */
	final int[] previousGasNodes;
	final List<LiquidGeometryRefinement.Cell> leaves;
	final List<Observation> inventoryObservations;
	final int evaluations;
	final double maximumCentroidError, maximumOccupancyErrorLength;
	final Fraction symmetricDifferenceVolumeBound;

	LiquidGeometryReconstructionPlan(LiquidGeometryState input, List<LiquidGeometryRefinement.Cell> open,
			LiquidMomentReconstruction.Tolerance tolerance, LiquidMomentReconstruction.Budget budget) {
		this(input, null, open, tolerance, budget);
	}
	LiquidGeometryReconstructionPlan(LiquidGeometryState input, GasGeometryState gas, List<LiquidGeometryRefinement.Cell> open,
			LiquidMomentReconstruction.Tolerance tolerance, LiquidMomentReconstruction.Budget budget) {
		if (input == null || open == null || tolerance == null || budget == null || open.size() > budget.maximumLeaves())
			throw new IllegalArgumentException("Invalid geometric reconstruction support");
		if (gas != null && gas.rootWidthMetres() != input.rootWidthMetres()) throw new IllegalArgumentException("Reconstruction phases have different physical roots");
		this.input = input; previousGasGeometry = gas;
		var source = LiquidGeometryStateMapper.restore(input); var remap = new LiquidGeometryRemap(open);
		int[] owners = input.copyFragmentOwners(), controls = input.copyControlCells();
		var local = new ArrayList<LinkedHashMap<Integer, ArrayList<LiquidPolyhedron>>>();
		for (int c = 0; c < open.size(); c++) local.add(new LinkedHashMap<>());
		for (int f = 0; f < source.size(); f++) for (var part : remap.partitionGeometry(source.get(f).geometry()))
			local.get(part.cell()).computeIfAbsent(controls[f], ignored -> new ArrayList<>()).add(part.geometry());
		if (gas != null) {
			var gasCells = LiquidGeometryStateMapper.restoreCells(gas.cells()); var nodes = gas.nodeOrdinals();
			for (int f = 0; f < gasCells.size(); f++) for (var part : remap.partitionGeometry(gasCells.get(f).geometry()))
				local.get(part.cell()).computeIfAbsent(source.size() + nodes[f], ignored -> new ArrayList<>()).add(part.geometry());
		}
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var previousOwner = new ArrayList<Integer>();
		var gasCells = new ArrayList<LiquidGeometryRefinement.Cell>(); var gasOwners = new ArrayList<Integer>();
		var speeds = new ArrayList<double[]>(); var representatives = new ArrayList<Integer>(); var selected = new ArrayList<LiquidGeometryRefinement.Cell>();
		int work = 0; double centroidError = 0, occupancyError = 0; var difference = Fraction.ZERO;
		for (int c = 0; c < open.size(); c++) {
			if (gas != null) {
				var address = LiquidPhasePartition.Address.of(open.get(c));
				LiquidPhasePartition.requireComplete(open.get(c), local.get(c).values().stream().flatMap(List::stream).map(address::cell).toList());
			}
			if (local.get(c).isEmpty()) { selected.add(open.get(c)); continue; }
			if (work == budget.maximumEvaluations()) throw new IllegalStateException("Geometry reconstruction exceeds its evaluation budget");
			var sources = new ArrayList<LiquidMomentReconstruction.Source>();
			for (var group : local.get(c).entrySet()) {
				var normal = open.get(c).geometry().centroid().subtract(LiquidVolumeInterface.measure(group.getValue()).centroid());
				if (normal.dot(normal) == 0) normal = new LiquidPolyhedron.Point(1, 0, 0);
				sources.add(new LiquidMomentReconstruction.Source(group.getKey(), group.getValue(), normal));
			}
			int availableLeaves = budget.maximumLeaves() - selected.size() - (open.size() - c - 1);
			int availableRegions = budget.maximumRegions() - cells.size() - gasCells.size();
			if (availableRegions < 1) throw new IllegalStateException("Geometry reconstruction exceeds its region budget");
			var remaining = new LiquidMomentReconstruction.Budget(availableLeaves, budget.maximumEvaluations() - work, budget.maximumFitEvaluations(), availableRegions);
			var result = LiquidMomentReconstruction.reconstruct(open.get(c), sources, tolerance, remaining);
			work += result.evaluations();
			for (var observation : result.phases()) {
				centroidError = Math.max(centroidError, observation.maximumCentroidError()); occupancyError = Math.max(occupancyError, observation.maximumOccupancyErrorLength());
				difference = difference.add(observation.symmetricDifferenceVolumeBound());
			}
			for (var leaf : result.leaves()) {
				selected.add(leaf.open());
				for (var phase : leaf.phases()) {
					if (phase.control() >= source.size()) {
						for (var piece : phase.pieces()) { gasCells.add(LiquidPhasePartition.Address.of(leaf.open()).cell(piece)); gasOwners.add(phase.control() - source.size()); }
						continue;
					}
					int first = cells.size(); var velocity = input.cells().get(phase.control()).copyVelocity();
					for (var piece : phase.pieces()) {
						cells.add(LiquidPhasePartition.Address.of(leaf.open()).cell(piece)); previousOwner.add(owners[phase.control()]);
						speeds.add(velocity); representatives.add(first);
					}
				}
			}
		}
		var first = new HashMap<Integer, Integer>(); var groups = new int[cells.size()]; var velocity = new double[3 * cells.size()];
		for (int f = 0; f < cells.size(); f++) {
			first.putIfAbsent(previousOwner.get(f), f); groups[f] = first.get(previousOwner.get(f)); System.arraycopy(speeds.get(f), 0, velocity, 3 * f, 3);
		}
		var markers = input.copyMarkerCells(); for (int p = 0; p < markers.length; p++) {
			var owner = first.get(markers[p]); if (owner == null) throw new IllegalStateException("Geometry reconstruction lost an inventory group"); markers[p] = owner;
		}
		geometry = LiquidGeometryStateMapper.capture(cells, input.rootWidthMetres(), markers, groups, velocity, representatives.stream().mapToInt(Integer::intValue).toArray());
		if (gas == null) { gasGeometry = null; previousGasNodes = new int[0]; }
		else {
			var order = new ArrayList<Integer>(); for (int c = 0; c < gasCells.size(); c++) order.add(c);
			var packed = gasCells.stream().map(cell -> LiquidGeometryStateMapper.pack(cell, new double[3])).toList();
			order.sort((a, b) -> LiquidGeometryState.compareGeometry(packed.get(a), packed.get(b)));
			var nodeOrder = new LinkedHashMap<Integer, Integer>(); nodeOrder.put(0, 0); var sorted = new ArrayList<cc.ecisr.mmm.geometry.ResolvedControlVolume>();
			var labels = new int[order.size()];
			for (int c = 0; c < order.size(); c++) { int f = order.get(c); sorted.add(packed.get(f)); labels[c] = nodeOrder.computeIfAbsent(gasOwners.get(f), ignored -> nodeOrder.size()); }
			if (nodeOrder.size() != gas.nodeCount() + 1) throw new IllegalStateException("Reconstruction lost a material gas chamber");
			previousGasNodes = new int[nodeOrder.size()]; nodeOrder.forEach((old, next) -> previousGasNodes[next] = old);
			gasGeometry = new GasGeometryState(input.rootWidthMetres(), sorted, labels, gas.nodeCount(), gas.sealedExterior());
			var oldVolume = ResolvedGasGeometry.volumes(gas); var nextVolume = ResolvedGasGeometry.volumes(gasGeometry);
			for (int node = 0; node < previousGasNodes.length; node++) if (!nextVolume[node].equals(oldVolume[previousGasNodes[node]]))
				throw new IllegalStateException("Reconstruction changed exact material gas volume");
		}
		leaves = List.copyOf(selected); evaluations = work; maximumCentroidError = centroidError; maximumOccupancyErrorLength = occupancyError; symmetricDifferenceVolumeBound = difference;
		var restored = LiquidGeometryStateMapper.restore(geometry); int[] nextOwners = geometry.copyFragmentOwners(), nextMarkers = geometry.copyMarkerCells();
		var originalMarkers = input.copyMarkerCells(); var inherited = new HashMap<Integer, Integer>();
		for (int p = 0; p < nextMarkers.length; p++) inherited.put(nextMarkers[p], originalMarkers[p]);
		var oldMeasure = measures(source, owners); var nextMeasure = measures(restored, nextOwners);
		var beforeMomentum = momentum(input, source, owners); var afterMomentum = momentum(geometry, restored, nextOwners);
		var observations = new ArrayList<Observation>(); var width = Fraction.of(input.rootWidthMetres());
		for (var entry : inherited.entrySet()) {
			int next = entry.getKey(), old = entry.getValue();
			if (!oldMeasure[old].volume().equals(nextMeasure[next].volume()) || !Arrays.equals(beforeMomentum[old], afterMomentum[next]))
				throw new IllegalStateException("Geometry reconstruction changed exact inventory volume or momentum");
			var center = nextMeasure[next].centroidCoordinates(); var mean = new Fraction[3];
			for (int a = 0; a < 3; a++) mean[a] = afterMomentum[next][a].divide(nextMeasure[next].volume());
			observations.add(new Observation(next, new LiquidPolyhedron.Point(center[0].multiply(width).rounded(), center[1].multiply(width).rounded(), center[2].multiply(width).rounded()),
					new LiquidPolyhedron.Point(mean[0].rounded(), mean[1].rounded(), mean[2].rounded())));
		}
		inventoryObservations = List.copyOf(observations);
	}
	private static LiquidExactPolygon.Measure[] measures(List<LiquidGeometryRefinement.Cell> cells, int[] owners) {
		var result = new LiquidExactPolygon.Measure[cells.size()]; Arrays.fill(result, LiquidConvexRegion.EMPTY.measure());
		for (int f = 0; f < cells.size(); f++) result[owners[f]] = result[owners[f]].add(cells.get(f).geometry().measure()); return result;
	}
	private static Fraction[][] momentum(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> cells, int[] owners) {
		var result = new Fraction[cells.size()][3]; for (var row : result) Arrays.fill(row, Fraction.ZERO);
		for (int f = 0; f < cells.size(); f++) {
			var volume = cells.get(f).geometry().measure().volume(); var velocity = state.cells().get(f).copyVelocity();
			for (int a = 0; a < 3; a++) result[owners[f]][a] = result[owners[f]][a].add(volume.multiply(Fraction.of(velocity[a])));
		}
		return result;
	}
}
