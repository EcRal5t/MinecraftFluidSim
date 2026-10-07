package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.List;

/** Shared reconstruction preserves each physical control and the complete common occupancy. */
final class LiquidJointReconstructionValidation {
	static void validate() {
		compositeCutsUseOneVolumeConstraint();
		jointControlsRetainIndependentVolumeAndIdentity();
		reconstructionPlanKeepsInventoriesAndMomentum();
		resolvedLeavesCarryIndependentControls();
	}
	private static Fraction overlap(List<LiquidPolyhedron> a, List<LiquidPolyhedron> b) {
		var result = Fraction.ZERO;
		for (var first : a) for (var second : b) {
			var common = LiquidConvexRegion.of(first); for (var plane : second.planes()) common = common.clip(plane);
			result = result.add(common.measure().volume());
		}
		return result;
	}
	private static void compositeCutsUseOneVolumeConstraint() {
		var open = List.of(LiquidPolyhedron.box(0, 0, 0, 0.375, 1, 1), LiquidPolyhedron.box(0.625, 0, 0, 1, 1, 1));
		var target = open.stream().map(piece -> piece.clip(1, 2, 3, 2.1)).toList(); var measure = LiquidVolumeInterface.measure(target);
		var normal = new LiquidPolyhedron.Point(1, 2, 3); double previous = Double.POSITIVE_INFINITY;
		for (int bits : new int[]{16, 32, 48}) {
			var representation = LiquidVolumeInterface.reconstruct(open, measure.volume(), normal, bits);
			if (!representation.measure.volume().equals(measure.volume()) || !overlap(representation.pieces, open).equals(measure.volume()))
				throw new AssertionError("Composite interface changed exact volume or left its available space");
			var difference = measure.volume().subtract(overlap(representation.pieces, target)).multiply(Fraction.of(2));
			if (difference.subtract(representation.symmetricDifferenceVolumeBound).signum() > 0)
				throw new AssertionError("Composite interface understated its common-cut occupancy error");
			double error = representation.centroidError(measure);
			if (!(error < previous / 4096)) throw new AssertionError("Composite interface first moment did not converge with coordinate precision");
			previous = error;
		}
		var representation = LiquidVolumeInterface.reconstruct(open, measure.volume(), normal, 48); var derivative = representation.centroidDerivative();
		for (int axis = 0; axis < 3; axis++) {
			double h = Math.scalb(1.0, -16); double[] plus = {1, 2, 3}, minus = plus.clone(); plus[axis] += h; minus[axis] -= h;
			var a = LiquidVolumeInterface.reconstruct(open, measure.volume(), new LiquidPolyhedron.Point(plus[0], plus[1], plus[2]), 48).measure.centroidCoordinates();
			var b = LiquidVolumeInterface.reconstruct(open, measure.volume(), new LiquidPolyhedron.Point(minus[0], minus[1], minus[2]), 48).measure.centroidCoordinates();
			for (int i = 0; i < 3; i++) if (Math.abs(a[i].subtract(b[i]).divide(Fraction.of(2 * h)).rounded() - derivative[i][axis].rounded()) > 1e-8)
				throw new AssertionError("Composite centroid derivative lost the coupling between separated cap sections");
		}
		var separated = List.of(LiquidPolyhedron.box(0, 0, 0, 0.25, 1, 1), LiquidPolyhedron.box(0.75, 0, 0, 1, 1, 1));
		var plateau = LiquidVolumeInterface.reconstruct(separated, Fraction.of(0.25), new LiquidPolyhedron.Point(1, 0, 0), 48);
		if (!plateau.measure.same(separated.getFirst().measure()) || plateau.pieces.size() != 1)
			throw new AssertionError("An exact common-cut volume plateau changed its component geometry");
	}
	private static void jointControlsRetainIndependentVolumeAndIdentity() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var remainder = cube.clip(-1, -2, -3, -3);
		var sources = List.of(new LiquidMomentReconstruction.Source(17, List.of(cube.clip(1, 2, 3, 3)), new LiquidPolyhedron.Point(1, 2, 3)),
				new LiquidMomentReconstruction.Source(8, List.of(remainder.clip(0, 0, 1, 0.625)), new LiquidPolyhedron.Point(0, 0, 1)),
				new LiquidMomentReconstruction.Source(94, List.of(remainder.clip(0, 0, -1, -0.625)), new LiquidPolyhedron.Point(0, 0, -1)));
		var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var tolerance = new LiquidMomentReconstruction.Tolerance(1e-8, 1e-7, 48);
		var budget = new LiquidMomentReconstruction.Budget(64, 128, 12, 4096);
		var result = LiquidMomentReconstruction.reconstruct(open, sources, tolerance, budget);
		if (result.leaves().size() != 1 || result.phases().size() != sources.size()) throw new AssertionError("Planar joint interfaces required an unexpected spatial refinement");
		var byControl = new java.util.HashMap<Integer, LiquidMomentReconstruction.Source>(); for (var source : sources) byControl.put(source.control(), source);
		for (var phase : result.phases()) {
			var original = LiquidVolumeInterface.measure(byControl.get(phase.control()).pieces());
			if (!phase.sourceMeasure().same(original) || !phase.representedMeasure().volume().equals(original.volume())
					|| phase.maximumCentroidError() > tolerance.centroidDistance() || phase.maximumOccupancyErrorLength() > tolerance.occupancyErrorLength())
				throw new AssertionError("Joint reconstruction changed a physical control or accepted an unresolved shape");
		}
		var cells = result.leaves().getFirst().phases().stream().flatMap(phase -> phase.pieces().stream()).map(LiquidPhasePartition.Address.of(open)::cell).toList();
		LiquidPhasePartition.requireComplete(open, cells);
		var renamed = List.of(new LiquidMomentReconstruction.Source(400, sources.get(2).pieces(), sources.get(2).normal()),
				new LiquidMomentReconstruction.Source(2, sources.get(0).pieces(), sources.get(0).normal()),
				new LiquidMomentReconstruction.Source(71, sources.get(1).pieces(), sources.get(1).normal()));
		var equivalent = LiquidMomentReconstruction.reconstruct(open, renamed, tolerance, budget);
		int[] names = {17, 8, 94}, replacement = {2, 71, 400};
		for (int i = 0; i < names.length; i++) {
			int oldName = names[i], newName = replacement[i];
			var original = result.phases().stream().filter(phase -> phase.control() == oldName).findFirst().orElseThrow();
			var translated = equivalent.phases().stream().filter(phase -> phase.control() == newName).findFirst().orElseThrow();
			if (!original.representedMeasure().same(translated.representedMeasure()) || !original.symmetricDifferenceVolume().equals(translated.symmetricDifferenceVolume()))
				throw new AssertionError("Control identifiers or submission order changed the reconstructed geometry");
		}
		if (result.evaluations() != equivalent.evaluations()) throw new AssertionError("Control identifiers changed the geometric work budget");
	}
	private static void reconstructionPlanKeepsInventoriesAndMomentum() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var cells = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(1, 2, 3, 3)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(-1, -2, -3, -3)));
		var velocity = new double[]{0.25, -0.5, 0.75, -1.5, 2, -0.25};
		var tolerance = new LiquidMomentReconstruction.Tolerance(1e-8, 1e-7, 48);
		var budget = new LiquidMomentReconstruction.Budget(64, 256, 24, 4096);
		for (boolean shared : new boolean[]{false, true}) {
			var original = LiquidGeometryStateMapper.capture(cells, 1, shared ? new int[]{0} : new int[]{0, 1},
					shared ? new int[]{0, 0} : new int[]{0, 1}, velocity, new int[]{0, 1});
			var input = observation(original, null); LiquidGeometryStateMapper.requireCompatible(original, input, 4, 4, 4, 0.25F, new byte[64]);
			var plan = new LiquidGeometryReconstructionPlan(original, List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube)), tolerance, budget);
			var represented = observation(plan.geometry, plan.inventoryObservations);
			var observed = retainedInventory(input, represented);
			LiquidGeometryStateMapper.requireCompatible(plan.geometry, observed, 4, 4, 4, 0.25F, new byte[64]);
			if (plan.inventoryObservations.size() != original.markerCount() || plan.leaves.size() != 1 || plan.maximumCentroidError > tolerance.centroidDistance()
					|| plan.maximumOccupancyErrorLength > tolerance.occupancyErrorLength() || !java.util.Arrays.equals(integrated(original, input), integrated(plan.geometry, observed)))
				throw new AssertionError("Reconstruction candidate changed inventory, momentum, kinetic energy or its measured spatial limits");
			for (int p = 0; p < input.count(); p++) if (input.volume(p) != observed.volume(p) || input.mass(p) != observed.mass(p))
				throw new AssertionError("Reconstruction changed an independent inventory carrier");
			var controls = plan.geometry.copyControlCells(); var owners = plan.geometry.copyFragmentOwners();
			for (int f = 0; f < controls.length; f++) if (owners[controls[f]] != owners[f]
					|| !java.util.Arrays.equals(plan.geometry.cells().get(f).copyVelocity(), plan.geometry.cells().get(controls[f]).copyVelocity()))
				throw new AssertionError("Canonical reconstruction control lost its owner or P0 field");
		}
	}
	private static void resolvedLeavesCarryIndependentControls() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var shape = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		var source = LiquidGeometryStateMapper.capture(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)), 1, new int[]{0}, new int[]{0}, new double[]{2, -1, 0.5});
		var original = observation(source, null); var plan = new LiquidGeometryReconstructionPlan(source,
				List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube)), new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40),
				new LiquidMomentReconstruction.Budget(512, 4096, 8, 4096));
		var observed = retainedInventory(original, observation(plan.geometry, plan.inventoryObservations));
		LiquidGeometryStateMapper.requireCompatible(plan.geometry, observed, 4, 4, 4, 0.25F, new byte[64]);
		if (java.util.Arrays.stream(plan.geometry.copyControlCells()).distinct().count() != 8 || plan.geometry.cells().size() != 8
				|| !java.util.Arrays.equals(integrated(source, original), integrated(plan.geometry, observed)))
			throw new AssertionError("Resolved closed-interface leaves lost their independent mechanical controls or P0 field");
		var sum = LiquidVolumeInterface.measure(LiquidGeometryStateMapper.restore(plan.geometry).stream().map(LiquidGeometryRefinement.Cell::geometry).toList());
		if (!sum.same(shape.measure()) || plan.symmetricDifferenceVolume.signum() != 0)
			throw new AssertionError("Dyadically resolved closed interface changed its exact occupied geometry");
	}
	private static LiquidGeometryInventoryView retainedInventory(LiquidGeometryInventoryView original, LiquidGeometryInventoryView represented) {
		return new LiquidGeometryInventoryView() {
			public int count() { return original.count(); }
			public double volume(int p) { return original.volume(p); }
			public double mass(int p) { return original.mass(p); }
			public double position(int p, int a) { return represented.position(p, a); }
			public double velocity(int p, int a) { return represented.velocity(p, a); }
		};
	}
	private static LiquidGeometryInventoryView observation(LiquidGeometryState state, List<LiquidGeometryReconstructionPlan.Observation> supplied) {
		var restored = LiquidGeometryStateMapper.restore(state); int[] owners = state.copyFragmentOwners(), markers = state.copyMarkerCells();
		var measures = new LiquidExactPolygon.Measure[restored.size()]; java.util.Arrays.fill(measures, LiquidConvexRegion.EMPTY.measure());
		var momentum = new Fraction[restored.size()][3]; for (var row : momentum) java.util.Arrays.fill(row, Fraction.ZERO);
		for (int f = 0; f < restored.size(); f++) {
			var measured = restored.get(f).geometry().measure(); measures[owners[f]] = measures[owners[f]].add(measured); var velocity = state.cells().get(f).copyVelocity();
			for (int a = 0; a < 3; a++) momentum[owners[f]][a] = momentum[owners[f]][a].add(measured.volume().multiply(Fraction.of(velocity[a])));
		}
		var observations = new java.util.HashMap<Integer, LiquidGeometryReconstructionPlan.Observation>();
		if (supplied != null) for (var value : supplied) observations.put(value.owner(), value);
		return new LiquidGeometryInventoryView() {
			public int count() { return markers.length; }
			public double volume(int marker) { return measures[markers[marker]].volume().multiply(Fraction.of(64)).rounded(); }
			public double mass(int marker) { return marker == 0 ? 500 : 750; }
			public double position(int marker, int axis) {
				return (float) (supplied == null ? measures[markers[marker]].centroidCoordinates()[axis].rounded() : observations.get(markers[marker]).positionMetres().coordinate(axis));
			}
			public double velocity(int marker, int axis) {
				return (float) (supplied == null ? momentum[markers[marker]][axis].divide(measures[markers[marker]].volume()).rounded() : observations.get(markers[marker]).velocityMetresPerSecond().coordinate(axis));
			}
		};
	}
	/** Exact mass-weighted P0 momentum and kinetic energy read the restored candidate field. */
	private static Fraction[] integrated(LiquidGeometryState state, LiquidGeometryInventoryView inventory) {
		var cells = LiquidGeometryStateMapper.restore(state); var groups = LiquidGeometryMomentumAudit.volumesByVelocity(state, cells);
		var volumes = new Fraction[state.cells().size()]; var masses = volumes.clone(); java.util.Arrays.fill(volumes, Fraction.ZERO); java.util.Arrays.fill(masses, Fraction.ZERO);
		for (var group : groups) volumes[group.owner()] = volumes[group.owner()].add(group.volume());
		var markers = state.copyMarkerCells(); for (int p = 0; p < markers.length; p++) masses[markers[p]] = masses[markers[p]].add(Fraction.of(inventory.mass(p)));
		var result = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		for (var group : groups) {
			var weight = group.volume().multiply(masses[group.owner()]).divide(volumes[group.owner()]);
			for (int a = 0; a < 3; a++) {
				var velocity = Fraction.of(group.velocity().coordinate(a)); result[a] = result[a].add(weight.multiply(velocity));
				result[3] = result[3].add(weight.multiply(velocity).multiply(velocity).divide(Fraction.of(2)));
			}
		}
		return result;
	}
}
