package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.List;

/** Shared reconstruction preserves each physical control and the complete common occupancy. */
final class LiquidJointReconstructionValidation {
	static void validate() {
		compositeCutsUseOneVolumeConstraint();
		sharedVolumeCorrectionBoundsItsOccupiedSpaceChange();
		globalVolumeCorrectionKeepsSharedLeafCoverage();
		jointCutsRetainSmallRemainingPhases();
		jointControlsRetainIndependentVolumeAndIdentity();
		equalMomentControlsKeepGeometricOrder();
		reconstructionPlanKeepsInventoriesAndMomentum();
		velocityProjectionRefinesSpatialError();
		volumeRedistributionCarriesConservativeMoments();
		resolvedLeavesCarryIndependentControls();
		jointGasReconstructionPreservesMaterialChambers();
	}
	private static void jointCutsRetainSmallRemainingPhases() {
		var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		double width = Math.scalb(1.0, -100); var small = open.geometry().clip(1, 0, 0, width); var large = open.geometry().clip(-1, 0, 0, -width);
		var tolerance = new LiquidMomentReconstruction.Tolerance(1e-8, 1e-7, 32); var budget = new LiquidMomentReconstruction.Budget(1, 64, 8, 4096);
		var result = LiquidMomentReconstruction.reconstruct(open, List.of(new LiquidMomentReconstruction.Source(13, List.of(large), new LiquidPolyhedron.Point(0, 1, 0)),
				new LiquidMomentReconstruction.Source(4, List.of(small), new LiquidPolyhedron.Point(1, 0, 0))), tolerance, budget);
		if (result.leaves().size() != 1 || result.phases().size() != 2) throw new AssertionError("A resolved minority phase requested unnecessary spatial refinement");
		for (var phase : result.phases()) if (!phase.sourceMeasure().same(phase.representedMeasure()) || phase.symmetricDifferenceVolumeBound().signum() != 0)
			throw new AssertionError("Majority centroid rounding displaced a resolved remaining phase");
		var angled = open.geometry().clip(1, 2, 3, 3); var remainder = open.geometry().clip(-1, -2, -3, -3);
		var occupiedTolerance = new LiquidMomentReconstruction.Tolerance(0.5, 1e-4, 48);
		var fitted = LiquidMomentReconstruction.reconstruct(open, List.of(new LiquidMomentReconstruction.Source(13, List.of(angled), new LiquidPolyhedron.Point(1, 0, 0)),
				new LiquidMomentReconstruction.Source(4, List.of(remainder), new LiquidPolyhedron.Point(1, 0, 0))), occupiedTolerance, new LiquidMomentReconstruction.Budget(1, 128, 24, 4096));
		if (fitted.leaves().size() != 1 || fitted.evaluations() <= 1 || fitted.phases().stream().anyMatch(phase -> phase.maximumOccupancyErrorLength() > occupiedTolerance.occupancyErrorLength()))
			throw new AssertionError("Moment fitting stopped at its centroid tolerance while occupied-space accuracy remained unresolved");
	}
	private static void globalVolumeCorrectionKeepsSharedLeafCoverage() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new java.util.ArrayList<LiquidMomentReconstruction.Leaf>(); var targets = new java.util.LinkedHashMap<Integer, Fraction>();
		var delta = Fraction.of(Math.scalb(1.0, -18)); targets.put(11, Fraction.of(0.5).add(delta)); targets.put(29, Fraction.of(0.5).subtract(delta));
		for (var cell : LiquidGeometryRefinement.children(root)) {
			double level = cell.minimum(1) + cell.width() * 0.5;
			leaves.add(new LiquidMomentReconstruction.Leaf(cell, List.of(new LiquidMomentReconstruction.Phase(11, List.of(cell.geometry().clip(0, 1, 0, level))),
					new LiquidMomentReconstruction.Phase(29, List.of(cell.geometry().clip(0, -1, 0, -level))))));
		}
		var corrected = LiquidJointInterfaceVolume.project(leaves, targets, 4096); var volumes = new java.util.HashMap<Integer, Fraction>();
		for (int l = 0; l < leaves.size(); l++) {
			var leaf = corrected.leaves().get(l); var address = LiquidPhasePartition.Address.of(leaf.open());
			LiquidPhasePartition.requireComplete(leaf.open(), leaf.phases().stream().flatMap(phase -> phase.pieces().stream()).map(address::cell).toList());
			for (int p = 0; p < leaf.phases().size(); p++) {
				var phase = leaf.phases().get(p); var measured = LiquidVolumeInterface.measure(phase.pieces()); volumes.merge(phase.control(), measured.volume(), Fraction::add);
				var original = LiquidVolumeInterface.measure(leaves.get(l).phases().get(p).pieces());
				var actualDifference = original.volume().add(measured.volume()).subtract(overlap(leaves.get(l).phases().get(p).pieces(), phase.pieces()).multiply(Fraction.of(2)));
				if (actualDifference.subtract(corrected.symmetricDifferenceBounds().get(l).get(p)).signum() > 0) throw new AssertionError("Global volume correction understated a leaf's occupied-space error");
			}
		}
		if (!volumes.equals(targets)) throw new AssertionError("Global shared-face correction lost material volume");
	}
	private static void volumeRedistributionCarriesConservativeMoments() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var children = LiquidGeometryRefinement.children(root); var leaves = List.of(children.get(0), children.get(1));
		var delta = Fraction.of(Math.scalb(1.0, -10)); var firstVolume = Fraction.of(0.0625).add(delta); var secondVolume = Fraction.of(0.03125).subtract(delta);
		var first = LiquidGeometryVelocityProjection.observe(firstVolume, List.of(Fraction.ZERO, Fraction.ZERO, Fraction.ZERO), Fraction.ZERO);
		var second = LiquidGeometryVelocityProjection.observe(secondVolume, List.of(secondVolume, Fraction.ZERO, Fraction.ZERO), secondVolume.divide(Fraction.of(2)));
		var sources = List.of(new LiquidConservativeVolumeRedistribution.Source(0, 7, first), new LiquidConservativeVolumeRedistribution.Source(1, 7, second));
		var targets = List.of(new LiquidConservativeVolumeRedistribution.Target(0, 7, Fraction.of(0.0625)), new LiquidConservativeVolumeRedistribution.Target(1, 7, Fraction.of(0.03125)));
		var result = LiquidConservativeVolumeRedistribution.project(leaves, sources, targets);
		var a = result.fields().get(0).get(7); var b = result.fields().get(1).get(7);
		if (a.velocity()[0] != 0 || b.velocity()[0] != 31.0 / 32 || !a.momentum().getFirst().add(b.momentum().getFirst()).equals(secondVolume)
				|| !a.originalKinetic().add(b.originalKinetic()).equals(secondVolume.divide(Fraction.of(2)))
				|| !a.kineticLoss().add(b.kineticLoss()).equals(Fraction.of(31.0 / 65536)) || !result.movedVolume().equals(delta)
				|| !result.squaredDisplacementVolumeBound().equals(delta.multiply(Fraction.of(1.5))))
			throw new AssertionError("Positive volume redistribution lost momentum, its kinetic audit or spatial displacement bound");
		var reordered = LiquidConservativeVolumeRedistribution.project(leaves, List.of(sources.get(1), sources.get(0)), List.of(targets.get(1), targets.get(0)));
		if (!java.util.Arrays.equals(reordered.fields().get(1).get(7).velocity(), b.velocity()) || !reordered.movedVolume().equals(result.movedVolume()))
			throw new AssertionError("Conservative volume redistribution depends on source submission order");
		try {
			LiquidConservativeVolumeRedistribution.project(leaves, sources, List.of(targets.getFirst()));
			throw new AssertionError("Unequal inventory volumes accepted a conservative redistribution");
		} catch (IllegalStateException expected) { if (!expected.getMessage().contains("unequal source and target volumes")) throw expected; }
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
			if (error > previous / 4096) throw new AssertionError("Composite interface first moment did not converge with coordinate precision");
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
		var thin = LiquidPolyhedron.box(0, 0, 0, 1, 1, Math.scalb(1.0, -40)); var requested = thin.clip(1, 2, 0, 1.5).measure();
		var fit = LiquidMomentInterface.reconstruct(thin, requested, new LiquidPolyhedron.Point(1, 0, 0), 48, 1e-9, 24);
		if (fit.centroidError() > 1e-9 || fit.evaluations() <= 1 || !fit.interfaceGeometry().measure.volume().equals(requested.volume()))
			throw new AssertionError("An anisotropic positive-area cap was discarded as a rank-deficient moment fit");
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
			if (!original.representedMeasure().same(translated.representedMeasure()) || !original.symmetricDifferenceVolumeBound().equals(translated.symmetricDifferenceVolumeBound()))
				throw new AssertionError("Control identifiers or submission order changed the reconstructed geometry");
		}
		if (result.evaluations() != equivalent.evaluations()) throw new AssertionError("Control identifiers changed the geometric work budget");
	}
	private static void sharedVolumeCorrectionBoundsItsOccupiedSpaceChange() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var parts = List.of(cube.clip(1, 0, 0, 0.375), cube.clip(-1, 0, 0, -0.375).clip(0, 1, 0, 0.625),
				cube.clip(-1, 0, 0, -0.375).clip(0, -1, 0, -0.625));
		var phases = new java.util.ArrayList<LiquidMomentReconstruction.Phase>(); var target = new java.util.ArrayList<Fraction>();
		var delta = Fraction.of(Math.scalb(1.0, -20));
		for (int p = 0; p < parts.size(); p++) {
			phases.add(new LiquidMomentReconstruction.Phase(p, List.of(parts.get(p))));
			target.add(parts.get(p).measure().volume().add(p == 0 ? delta : p == 1 ? delta.negate().multiply(Fraction.of(2)) : delta));
		}
		var result = LiquidJointInterfaceVolume.project(open, phases, target, 4096);
		var occupied = result.phases().stream().flatMap(phase -> phase.pieces().stream()).map(LiquidPhasePartition.Address.of(open)::cell).toList();
		LiquidPhasePartition.requireComplete(open, occupied);
		for (int p = 0; p < parts.size(); p++) {
			var next = result.phases().get(p).pieces(); var measure = LiquidVolumeInterface.measure(next);
			var difference = parts.get(p).measure().volume().add(measure.volume()).subtract(overlap(List.of(parts.get(p)), next).multiply(Fraction.of(2)));
			if (!measure.volume().equals(target.get(p)) || difference.signum() <= 0 || difference.subtract(result.symmetricDifferenceBounds().get(p)).signum() > 0)
				throw new AssertionError("Shared coordinate projection changed an exact volume or understated its occupied-space change");
		}
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
			var plan = new LiquidGeometryReconstructionPlan(original, List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube)), tolerance, 2, budget);
			var represented = observation(plan.geometry, plan.inventoryObservations);
			var observed = retainedInventory(input, represented);
			LiquidGeometryStateMapper.requireCompatible(plan.geometry, observed, 4, 4, 4, 0.25F, new byte[64]);
			var before = integrated(original, input); var after = integrated(plan.geometry, observed);
			if (plan.inventoryObservations.size() != original.markerCount() || plan.leaves.size() != 1 || plan.maximumCentroidError > tolerance.centroidDistance()
					|| plan.maximumOccupancyErrorLength > tolerance.occupancyErrorLength() || !java.util.Arrays.equals(java.util.Arrays.copyOf(before, 3), java.util.Arrays.copyOf(after, 3)))
				throw new AssertionError("Reconstruction candidate changed inventory, momentum or its measured spatial limits");
			if (shared) {
				var error = plan.velocityErrors.getFirst();
				if (plan.geometry.cells().size() != 1 || java.util.Arrays.stream(plan.geometry.copyControlCells()).distinct().count() != 1
						|| error.kineticLoss().signum() <= 0 || !before[3].subtract(after[3]).equals(error.kineticLoss().multiply(Fraction.of(500))))
					throw new AssertionError("Inventory regrid retained a numerical interface or hid its kinetic projection loss");
			} else if (!before[3].equals(after[3])) throw new AssertionError("Separate inventory reconstruction changed kinetic energy");
			for (int p = 0; p < input.count(); p++) if (input.volume(p) != observed.volume(p) || input.mass(p) != observed.mass(p))
				throw new AssertionError("Reconstruction changed an independent inventory carrier");
			var controls = plan.geometry.copyControlCells(); var owners = plan.geometry.copyFragmentOwners();
			for (int f = 0; f < controls.length; f++) if (owners[controls[f]] != owners[f]
					|| !java.util.Arrays.equals(plan.geometry.cells().get(f).copyVelocity(), plan.geometry.cells().get(controls[f]).copyVelocity()))
				throw new AssertionError("Canonical reconstruction control lost its owner or P0 field");
		}
	}
	private static void velocityProjectionRefinesSpatialError() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var cells = List.of(LiquidPhasePartition.Address.of(open).cell(cube.clip(1, 0, 0, 0.375)), LiquidPhasePartition.Address.of(open).cell(cube.clip(-1, 0, 0, -0.375)));
		var source = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[]{0, 0}, new double[]{0, 0, 0, 1, 0, 0}, new int[]{0, 1});
		var inventory = observation(source, null); var before = integrated(source, inventory);
		var tolerance = new LiquidMomentReconstruction.Tolerance(1e-8, 1e-7, 40); var budget = new LiquidMomentReconstruction.Budget(512, 4096, 8, 4096);
		var previousLoss = Fraction.of(1); int previousCells = 0;
		for (double accuracy : new double[]{1, 0.45, 0}) {
			var plan = new LiquidGeometryReconstructionPlan(source, List.of(open), tolerance, accuracy, budget);
			var error = plan.velocityErrors.getFirst(); var actual = integrated(plan.geometry, retainedInventory(inventory, observation(plan.geometry, plan.inventoryObservations)));
			if (error.maximumRmsError() > accuracy || error.kineticLoss().subtract(previousLoss).signum() >= 0 || plan.geometry.cells().size() <= previousCells
					|| !java.util.Arrays.equals(java.util.Arrays.copyOf(before, 3), java.util.Arrays.copyOf(actual, 3))
					|| !before[3].subtract(actual[3]).equals(error.kineticLoss().multiply(Fraction.of(500))))
				throw new AssertionError("Velocity refinement lost conservative momentum, error control or decreasing kinetic projection loss");
			previousLoss = error.kineticLoss(); previousCells = plan.geometry.cells().size();
		}
		if (previousLoss.signum() != 0) throw new AssertionError("Resolved velocity discontinuity retained an unresolved kinetic defect");
		try {
			new LiquidGeometryReconstructionPlan(source, List.of(open), tolerance, 0, new LiquidMomentReconstruction.Budget(1, 32, 8, 4096));
			throw new AssertionError("Insufficient velocity refinement budget published a candidate");
		} catch (IllegalStateException expected) { if (!expected.getMessage().contains("spatial leaf budget")) throw expected; }
		var tetrahedron = cube.clip(1, 1, 1, 1); var complement = LiquidGasPartition.build(open, List.of(LiquidPhasePartition.Address.of(open).cell(tetrahedron)), 32);
		var fields = new java.util.ArrayList<LiquidGeometryVelocityProjection.Source>();
		fields.add(new LiquidGeometryVelocityProjection.Source(0, tetrahedron, new LiquidPolyhedron.Point(1, 0, 0)));
		for (var piece : complement) fields.add(new LiquidGeometryVelocityProjection.Source(0, piece, new LiquidPolyhedron.Point(0, 0, 0)));
		var projected = new LiquidGeometryVelocityProjection(fields).values(open).get(0);
		var exact = new Fraction(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(6));
		var defect = Fraction.of(projected.velocity()[0]).subtract(exact).absolute();
		if (defect.signum() <= 0 || defect.subtract(projected.momentumRoundingBounds().getFirst()).signum() > 0
				|| projected.kineticRoundingChange().signum() == 0 || !projected.volume().equals(Fraction.of(1)))
			throw new AssertionError("Non-dyadic P0 mean lacks an observed and bounded rounding defect");
		var fragments = fields.stream().map(field -> LiquidPhasePartition.Address.of(open).cell(field.geometry())).toList();
		var speeds = new double[3 * fragments.size()]; speeds[0] = 1;
		var original = LiquidGeometryStateMapper.capture(fragments, 1, new int[]{0}, new int[fragments.size()], speeds,
				java.util.stream.IntStream.range(0, fragments.size()).toArray());
		var plan = new LiquidGeometryReconstructionPlan(original, List.of(open), tolerance, 1, budget);
		var saved = integrated(plan.geometry, retainedInventory(observation(original, null), observation(plan.geometry, plan.inventoryObservations)));
		var error = plan.velocityErrors.getFirst();
		if (plan.geometry.cells().size() != 1 || saved[0].subtract(exact.multiply(Fraction.of(500))).absolute().subtract(error.momentumRoundingBounds().getFirst().multiply(Fraction.of(500))).signum() > 0
				|| !Fraction.of(500).divide(Fraction.of(12)).subtract(saved[3]).equals(error.kineticLoss().subtract(error.kineticRoundingChange()).multiply(Fraction.of(500))))
			throw new AssertionError("Saved regrid changed non-dyadic momentum or its independently integrated kinetic audit");
	}
	private static void equalMomentControlsKeepGeometricOrder() {
		var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var a = new java.util.ArrayList<LiquidPolyhedron>(); var b = new java.util.ArrayList<LiquidPolyhedron>();
		for (var child : LiquidGeometryRefinement.children(open)) ((child.x() + child.y() + child.z()) % 2 == 0 ? a : b).add(child.geometry());
		var normal = new LiquidPolyhedron.Point(1, 0, 0); var tolerance = new LiquidMomentReconstruction.Tolerance(0.5, 2, 32);
		var budget = new LiquidMomentReconstruction.Budget(64, 256, 8, 4096);
		var first = LiquidMomentReconstruction.reconstruct(open, List.of(new LiquidMomentReconstruction.Source(7, a, normal), new LiquidMomentReconstruction.Source(8, b, normal)), tolerance, budget);
		var second = LiquidMomentReconstruction.reconstruct(open, List.of(new LiquidMomentReconstruction.Source(11, b, normal), new LiquidMomentReconstruction.Source(12, a, normal)), tolerance, budget);
		for (int p = 0; p < 2; p++) {
			int old = p == 0 ? 7 : 8, replacement = p == 0 ? 12 : 11;
			var x = first.phases().stream().filter(phase -> phase.control() == old).findFirst().orElseThrow();
			var y = second.phases().stream().filter(phase -> phase.control() == replacement).findFirst().orElseThrow();
			if (!x.representedMeasure().same(y.representedMeasure()) || !x.symmetricDifferenceVolumeBound().equals(y.symmetricDifferenceVolumeBound()))
				throw new AssertionError("Equal volume and centroid made reconstruction depend on control IDs or submission order");
		}
	}
	private static void resolvedLeavesCarryIndependentControls() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var shape = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		var source = LiquidGeometryStateMapper.capture(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)), 1, new int[]{0}, new int[]{0}, new double[]{2, -1, 0.5});
		var original = observation(source, null); var plan = new LiquidGeometryReconstructionPlan(source,
				List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube)), new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40), 0,
				new LiquidMomentReconstruction.Budget(512, 4096, 8, 4096));
		var observed = retainedInventory(original, observation(plan.geometry, plan.inventoryObservations));
		LiquidGeometryStateMapper.requireCompatible(plan.geometry, observed, 4, 4, 4, 0.25F, new byte[64]);
		if (java.util.Arrays.stream(plan.geometry.copyControlCells()).distinct().count() != 8 || plan.geometry.cells().size() != 8
				|| !java.util.Arrays.equals(integrated(source, original), integrated(plan.geometry, observed)))
			throw new AssertionError("Resolved closed-interface leaves lost their independent mechanical controls or P0 field");
		var sum = LiquidVolumeInterface.measure(LiquidGeometryStateMapper.restore(plan.geometry).stream().map(LiquidGeometryRefinement.Cell::geometry).toList());
		if (!sum.same(shape.measure()) || plan.symmetricDifferenceVolumeBound.signum() != 0)
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
	private static void jointGasReconstructionPreservesMaterialChambers() {
		jointGasReconstructionPreservesMaterialChambers(ignored -> { });
	}
	static void jointGasReconstructionPreservesMaterialChambers(java.util.function.Consumer<FluidDomain> initialize) {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var lower = cube.clip(0, 0, 1, 0.5).clip(0, -1, 0.25, -0.375).clip(0, 1, -0.25, 0.625);
		var upper = cube.clip(0, 0, -1, -0.5).clip(0, -1, -0.25, -0.625).clip(0, 1, 0.25, 0.875);
		var source = LiquidGeometryStateMapper.capture(List.of(LiquidPhasePartition.Address.of(open).cell(lower), LiquidPhasePartition.Address.of(open).cell(upper)),
				2, new int[]{0}, new int[]{0, 0}, new double[]{0.125, -0.25, 0.5, 0.125, -0.25, 0.5}, new int[]{0, 0});
		var original = observation(source, null); var positions = new float[4]; var velocity = new float[3];
		for (int a = 0; a < 3; a++) { positions[a] = (float) original.position(0, a); velocity[a] = (float) original.velocity(0, a); } positions[3] = (float) original.volume(0);
		var particles = new FlipParticleState(4, 4, 4, 0.5F, 1, positions[3], positions, velocity, new byte[64], source);
		var config = new FluidSolverConfig(0.5F, 1000, 0, 0.01F, 512, 1e-7F, 0.45F, 0.002F, 0.8F);
		var live = new FluidDomain(4, 4, 4, config); live.restoreParticleState(particles); live.sealDomainBoundary(); initialize.accept(live);
		String fingerprint = live.chemicalParcels == null ? "" : live.chemicalParcels.registry().fingerprint();
		if (live.gasState.geometry == null) live.bootstrapGasState(fingerprint);
		source = live.liquidGeometryState; var gas = live.gasState.geometry;
		if (gas.nodeCount() != 2) throw new AssertionError("Bent liquid sheet lacks its two original gas chambers");
		var tolerance = new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40);
		var plan = new LiquidGeometryReconstructionPlan(source, gas, List.of(open), tolerance, 0, new LiquidMomentReconstruction.Budget(512, 4096, 12, 4096));
		var represented = retainedInventory(original, observation(plan.geometry, plan.inventoryObservations));
		LiquidGeometryStateMapper.requireCompatible(plan.geometry, represented, 4, 4, 4, 0.5F, new byte[64]);
		var partition = new LiquidGeometryDomainPartition(4, 4, 4, 0.5F, new byte[64], plan.leaves, 4096);
		var next = new LiquidGeometryPhaseInput(plan.geometry, partition, plan.gasGeometry, 4096);
		ResolvedGasGeometry.match(plan.gasGeometry, next, 65536);
		var before = ResolvedGasGeometry.volumes(gas); var after = ResolvedGasGeometry.volumes(plan.gasGeometry);
		for (int node = 0; node < after.length; node++) if (!after[node].equals(before[plan.previousGasNodes[node]]))
			throw new AssertionError("Joint reconstruction introduced material gas compression");
		if (plan.leaves.size() == 1 || plan.maximumCentroidError > tolerance.centroidDistance() || plan.maximumOccupancyErrorLength > tolerance.occupancyErrorLength()
				|| !java.util.Arrays.equals(integrated(source, original), integrated(plan.geometry, represented)))
			throw new AssertionError("Bent phase reconstruction lost spatial refinement, P0 momentum or kinetic energy");
		var stock = live.materialRegistry == null ? live.exportParticleState() : live.exportMaterialParticleState().particles();
		var gasBefore = live.exportGasState(fingerprint); var internal = live.exactInternalEnergy();
		double kinetic = live.particleKineticEnergyJoules(), mass = live.totalMass(); long species = live.totalChemicalSpeciesQuanta();
		var energies = new double[gas.nodeCount() + 1]; var pressure = energies.clone();
		for (int node = 1; node < energies.length; node++) {
			pressure[node] = live.gasNodeGaugePressurePa(node);
			if (live.thermalState != null) energies[node] = live.thermalState.gasNodeEnergy(node, live.gasState.network);
		}
		var obsolete = new ResolvedGasMotionPlan(live, plan, 4096, 65536);
		live.gasState.network.addOrMergeEdge(1, 2, 0.001, 0.5); live.gasState.network.removeEdge(1, 2);
		try { obsolete.publish(live, plan); throw new AssertionError("Stale joint phase publication overwrote a revised gas owner"); }
		catch (IllegalStateException rejected) {
			if (!rejected.getMessage().contains("changed after remap")) throw rejected;
		}
		var unchanged = live.materialRegistry == null ? live.exportParticleState() : live.exportMaterialParticleState().particles();
		if (live.liquidGeometryState != source || live.gasState.geometry != gas || !live.exactInternalEnergy().equals(internal)
				|| !java.util.Arrays.equals(stock.copyPositionVolume(), unchanged.copyPositionVolume()) || !java.util.Arrays.equals(stock.copyParticleVelocities(), unchanged.copyParticleVelocities()))
			throw new AssertionError("Stale reconstruction partially published liquid geometry, observations or energy");
		var gasPlan = new ResolvedGasMotionPlan(live, plan, 4096, 65536); gasPlan.publish(live, plan);
		var gasAfter = live.exportGasState(fingerprint);
		for (int node = 1; node <= plan.gasGeometry.nodeCount(); node++) {
			int old = plan.previousGasNodes[node];
			if (!gasAfter.nodes().get(node - 1).inventory().equals(gasBefore.nodes().get(old - 1).inventory())
					|| live.gasNodeGaugePressurePa(node) != pressure[old]
					|| live.thermalState != null && live.thermalState.gasNodeEnergy(node, live.gasState.network) != energies[old])
				throw new AssertionError("Pure phase reconstruction changed material gas inventory, pressure or internal energy");
		}
		if (live.particles.volume(0) != stock.copyPositionVolume()[3] || live.totalMass() != mass || live.totalChemicalSpeciesQuanta() != species
				|| live.particleKineticEnergyJoules() != kinetic || !live.exactInternalEnergy().equals(internal)
				|| !gasBefore.externalSource().equals(gasAfter.externalSource()) || !gasBefore.escapedSink().equals(gasAfter.escapedSink()))
			throw new AssertionError("Pure phase reconstruction changed authoritative inventory or energy");
		var restored = FluidDomain.restoreAuthoritativeState(live.captureAuthoritativeState());
		if (!restored.exportGasState(fingerprint).nodes().equals(gasAfter.nodes()) || !restored.exactInternalEnergy().equals(internal)
				|| restored.totalMass() != mass || restored.totalChemicalSpeciesQuanta() != species || restored.particleKineticEnergyJoules() != kinetic)
			throw new AssertionError("Joint reconstructed candidate lost its complete restored state");
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
				return (float) (supplied == null ? measures[markers[marker]].centroidCoordinates()[axis].multiply(Fraction.of(state.rootWidthMetres())).rounded() : observations.get(markers[marker]).positionMetres().coordinate(axis));
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
