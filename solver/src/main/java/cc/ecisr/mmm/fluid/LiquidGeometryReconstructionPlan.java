package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/** Private inventory geometry and conservative P0 regrid with observed spatial and velocity errors. */
final class LiquidGeometryReconstructionPlan {
	record Observation(int owner, LiquidPolyhedron.Point positionMetres, LiquidPolyhedron.Point velocityMetresPerSecond) implements LiquidGeometryPublication.Observation { }
	/** Kinetic terms and momentum bounds use normalized geometric volume; multiply by the inventory mass / volume. */
	record VelocityError(int owner, Fraction volume, double maximumRmsError, Fraction kineticLoss, Fraction kineticRoundingChange, List<Fraction> momentumRoundingBounds) { }
	private static final class VelocityTotals {
		double rmsError;
		Fraction loss = Fraction.ZERO, rounding = Fraction.ZERO;
		final Fraction[] bounds = {Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		void add(LiquidGeometryVelocityProjection.Value value) {
			rmsError = Math.max(rmsError, value.rmsError()); loss = loss.add(value.kineticLoss()); rounding = rounding.add(value.kineticRoundingChange());
			for (int a = 0; a < 3; a++) bounds[a] = bounds[a].add(value.momentumRoundingBounds().get(a));
		}
	}
	final LiquidGeometryState input;
	final GasGeometryState previousGasGeometry;
	final LiquidGeometryState geometry;
	final GasGeometryState gasGeometry;
	/** Successor node ordinal to the original material chamber; ambient stays zero. */
	final int[] previousGasNodes;
	final List<LiquidGeometryRefinement.Cell> leaves;
	final List<Observation> inventoryObservations;
	final List<VelocityError> velocityErrors;
	final int evaluations;
	final double maximumCentroidError, maximumOccupancyErrorLength;
	final Fraction symmetricDifferenceVolumeBound;
	final Fraction redistributedVolume, redistributionSquaredDisplacementVolumeBound;

	LiquidGeometryReconstructionPlan(LiquidGeometryState input, List<LiquidGeometryRefinement.Cell> open,
			LiquidMomentReconstruction.Tolerance tolerance, double maximumRmsVelocityError, LiquidMomentReconstruction.Budget budget) {
		this(input, null, open, tolerance, maximumRmsVelocityError, budget);
	}
	LiquidGeometryReconstructionPlan(LiquidGeometryState input, GasGeometryState gas, List<LiquidGeometryRefinement.Cell> open,
			LiquidMomentReconstruction.Tolerance tolerance, double maximumRmsVelocityError, LiquidMomentReconstruction.Budget budget) {
		if (input == null || open == null || tolerance == null || budget == null || open.size() > budget.maximumLeaves()
				|| !(maximumRmsVelocityError >= 0) || !Double.isFinite(maximumRmsVelocityError))
			throw new IllegalArgumentException("Invalid geometric reconstruction support");
		if (gas != null && gas.rootWidthMetres() != input.rootWidthMetres()) throw new IllegalArgumentException("Reconstruction phases have different physical roots");
		this.input = input; previousGasGeometry = gas;
		var source = LiquidGeometryStateMapper.restore(input); var remap = new LiquidGeometryRemap(open);
		int[] owners = input.copyFragmentOwners();
		var local = new ArrayList<LinkedHashMap<Integer, ArrayList<LiquidPolyhedron>>>();
		var fields = new ArrayList<ArrayList<LiquidGeometryVelocityProjection.Source>>();
		for (int c = 0; c < open.size(); c++) { local.add(new LinkedHashMap<>()); fields.add(new ArrayList<>()); }
		for (int f = 0; f < source.size(); f++) for (var part : remap.partitionGeometry(source.get(f).geometry())) {
			local.get(part.cell()).computeIfAbsent(owners[f], ignored -> new ArrayList<>()).add(part.geometry());
			var u = input.cells().get(f).copyVelocity();
			fields.get(part.cell()).add(new LiquidGeometryVelocityProjection.Source(owners[f], part.geometry(), new LiquidPolyhedron.Point(u[0], u[1], u[2])));
		}
		if (gas != null) {
			var gasCells = LiquidGeometryStateMapper.restoreCells(gas.cells()); var nodes = gas.nodeOrdinals();
			for (int f = 0; f < gasCells.size(); f++) for (var part : remap.partitionGeometry(gasCells.get(f).geometry()))
				local.get(part.cell()).computeIfAbsent(source.size() + nodes[f], ignored -> new ArrayList<>()).add(part.geometry());
		}
		var supports = new ArrayList<LiquidMomentReconstruction.Support>();
		var originalSupports = new HashMap<LiquidPhasePartition.Address, LiquidMomentReconstruction.Support>();
		var projections = new HashMap<LiquidPhasePartition.Address, LiquidGeometryVelocityProjection>();
		for (int c = 0; c < open.size(); c++) {
			if (gas != null) {
				var address = LiquidPhasePartition.Address.of(open.get(c));
				LiquidPhasePartition.requireComplete(open.get(c), local.get(c).values().stream().flatMap(List::stream).map(address::cell).toList());
			}
			var sources = new ArrayList<LiquidMomentReconstruction.Source>();
			for (var group : local.get(c).entrySet()) {
				var normal = open.get(c).geometry().centroid().subtract(LiquidVolumeInterface.measure(group.getValue()).centroid());
				if (normal.dot(normal) == 0) normal = new LiquidPolyhedron.Point(1, 0, 0);
				sources.add(new LiquidMomentReconstruction.Source(group.getKey(), group.getValue(), normal));
			}
			var support = new LiquidMomentReconstruction.Support(open.get(c), sources); supports.add(support);
			var address = LiquidPhasePartition.Address.of(open.get(c)); originalSupports.put(address, support); projections.put(address, new LiquidGeometryVelocityProjection(fields.get(c)));
		}
		LiquidMomentReconstruction.Result result; LiquidConservativeVolumeRedistribution.Result balanced; int work = 0;
		while (true) {
			if (work >= budget.maximumEvaluations()) throw new IllegalStateException("Geometry reconstruction exceeds its evaluation budget");
			var remaining = new LiquidMomentReconstruction.Budget(budget.maximumLeaves(), budget.maximumEvaluations() - work, budget.maximumFitEvaluations(), budget.maximumRegions());
			result = LiquidMomentReconstruction.reconstruct(supports, tolerance, remaining, leaf -> ancestor(projections, leaf).accepts(leaf, maximumRmsVelocityError));
			work += result.evaluations();
			var sources = new ArrayList<LiquidConservativeVolumeRedistribution.Source>(); var targets = new ArrayList<LiquidConservativeVolumeRedistribution.Target>();
			for (int l = 0; l < result.leaves().size(); l++) {
				var leaf = result.leaves().get(l); var projected = ancestor(projections, leaf.open()).values(leaf.open());
				for (var entry : projected.entrySet()) sources.add(new LiquidConservativeVolumeRedistribution.Source(l, entry.getKey(), entry.getValue()));
				for (var phase : leaf.phases()) if (phase.control() < source.size()) targets.add(new LiquidConservativeVolumeRedistribution.Target(l, phase.control(), LiquidVolumeInterface.measure(phase.pieces()).volume()));
			}
			balanced = LiquidConservativeVolumeRedistribution.project(result.leaves().stream().map(LiquidMomentReconstruction.Leaf::open).toList(), sources, targets);
			if (balanced.fields().stream().flatMap(values -> values.values().stream()).allMatch(value -> value.rmsError() <= maximumRmsVelocityError)) break;
			var retry = new ArrayList<LiquidMomentReconstruction.Support>();
			for (int l = 0; l < result.leaves().size(); l++) {
				var leaf = result.leaves().get(l).open(); boolean resolved = balanced.fields().get(l).values().stream().allMatch(value -> value.rmsError() <= maximumRmsVelocityError);
				for (var cell : resolved ? List.of(leaf) : LiquidGeometryRefinement.children(leaf)) {
					var original = ancestor(originalSupports, cell); var clipped = new ArrayList<LiquidMomentReconstruction.Source>();
					for (var group : original.sources()) {
						var pieces = new ArrayList<LiquidPolyhedron>(); for (var piece : group.pieces()) {
							var faces = LiquidExactPolygon.faces(piece); for (var plane : LiquidExactPolygon.faces(cell.geometry())) { faces = LiquidExactPolygon.clipVolume(faces, plane.coefficients(), 1); if (faces.isEmpty()) break; }
							if (!faces.isEmpty()) pieces.add(LiquidPolyhedron.restoredExact(faces));
						}
						clipped.add(new LiquidMomentReconstruction.Source(group.control(), pieces, group.normal()));
					}
					retry.add(new LiquidMomentReconstruction.Support(cell, clipped));
				}
			}
			if (retry.size() > budget.maximumLeaves()) throw new IllegalStateException("Geometry reconstruction exceeds its spatial leaf budget");
			supports = retry;
		}
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var previousOwner = new ArrayList<Integer>();
		var gasCells = new ArrayList<LiquidGeometryRefinement.Cell>(); var gasOwners = new ArrayList<Integer>();
		var speeds = new ArrayList<double[]>(); var representatives = new ArrayList<Integer>(); var selected = new ArrayList<LiquidGeometryRefinement.Cell>();
		var velocityTotals = new HashMap<Integer, VelocityTotals>();
		double centroidError = 0, occupancyError = 0; var difference = Fraction.ZERO;
		for (var observation : result.phases()) {
			centroidError = Math.max(centroidError, observation.maximumCentroidError()); occupancyError = Math.max(occupancyError, observation.maximumOccupancyErrorLength()); difference = difference.add(observation.symmetricDifferenceVolumeBound());
		}
		redistributedVolume = balanced.movedVolume(); redistributionSquaredDisplacementVolumeBound = balanced.squaredDisplacementVolumeBound();
		for (int l = 0; l < result.leaves().size(); l++) {
			var leaf = result.leaves().get(l); selected.add(leaf.open());
			for (var phase : leaf.phases()) {
					if (phase.control() >= source.size()) {
						for (var piece : phase.pieces()) { gasCells.add(LiquidPhasePartition.Address.of(leaf.open()).cell(piece)); gasOwners.add(phase.control() - source.size()); }
						continue;
					}
					int first = cells.size(); var value = balanced.fields().get(l).get(phase.control());
					if (!value.volume().equals(LiquidVolumeInterface.measure(phase.pieces()).volume()))
						throw new IllegalStateException("Reconstructed inventory and projected momentum have different local volumes");
					var velocity = value.velocity(); velocityTotals.computeIfAbsent(phase.control(), ignored -> new VelocityTotals()).add(value);
					for (var piece : phase.pieces()) {
						cells.add(LiquidPhasePartition.Address.of(leaf.open()).cell(piece)); previousOwner.add(phase.control());
						speeds.add(velocity); representatives.add(first);
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
		var beforeMomentum = integratedField(input, source); var afterMomentum = integratedField(geometry, restored);
		var observations = new ArrayList<Observation>(); var width = Fraction.of(input.rootWidthMetres());
		var errors = new ArrayList<VelocityError>();
		for (var entry : inherited.entrySet()) {
			int next = entry.getKey(), old = entry.getValue();
			if (!oldMeasure[old].volume().equals(nextMeasure[next].volume())) throw new IllegalStateException("Geometry reconstruction changed exact inventory volume");
			var error = velocityTotals.get(old);
			for (int a = 0; a < 3; a++) if (beforeMomentum[old][a].subtract(afterMomentum[next][a]).absolute().subtract(error.bounds[a]).signum() > 0)
				throw new IllegalStateException("Geometry reconstruction exceeds its momentum rounding bound");
			if (!beforeMomentum[old][3].subtract(afterMomentum[next][3]).equals(error.loss.subtract(error.rounding)))
				throw new IllegalStateException("Geometry reconstruction kinetic change differs from its measured projection defect");
			errors.add(new VelocityError(next, nextMeasure[next].volume(), error.rmsError, error.loss, error.rounding, List.of(error.bounds)));
			var center = nextMeasure[next].centroidCoordinates(); var mean = new Fraction[3];
			for (int a = 0; a < 3; a++) mean[a] = afterMomentum[next][a].divide(nextMeasure[next].volume());
			observations.add(new Observation(next, new LiquidPolyhedron.Point(center[0].multiply(width).rounded(), center[1].multiply(width).rounded(), center[2].multiply(width).rounded()),
					new LiquidPolyhedron.Point(mean[0].rounded(), mean[1].rounded(), mean[2].rounded())));
		}
		inventoryObservations = List.copyOf(observations);
		velocityErrors = List.copyOf(errors);
	}
	private static <T> T ancestor(java.util.Map<LiquidPhasePartition.Address, T> roots, LiquidGeometryRefinement.Cell cell) {
		for (int level = cell.level(); level >= 0; level--) {
			int shift = cell.level() - level; var value = roots.get(new LiquidPhasePartition.Address(level, cell.x() >>> shift, cell.y() >>> shift, cell.z() >>> shift));
			if (value != null) return value;
		}
		throw new IllegalStateException("Reconstructed leaf has no original spatial support");
	}
	private static LiquidExactPolygon.Measure[] measures(List<LiquidGeometryRefinement.Cell> cells, int[] owners) {
		var result = new LiquidExactPolygon.Measure[cells.size()]; Arrays.fill(result, LiquidConvexRegion.EMPTY.measure());
		for (int f = 0; f < cells.size(); f++) result[owners[f]] = result[owners[f]].add(cells.get(f).geometry().measure()); return result;
	}
	private static Fraction[][] integratedField(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> cells) {
		var result = new Fraction[cells.size()][4]; for (var row : result) Arrays.fill(row, Fraction.ZERO);
		for (var field : LiquidGeometryMomentumAudit.volumesByVelocity(state, cells)) {
			int owner = field.owner(); var volume = field.volume();
			for (int a = 0; a < 3; a++) {
				var velocity = Fraction.of(field.velocity().coordinate(a)); result[owner][a] = result[owner][a].add(volume.multiply(velocity));
				result[owner][3] = result[owner][3].add(volume.multiply(velocity).multiply(velocity).divide(Fraction.of(2)));
			}
		}
		return result;
	}
}
